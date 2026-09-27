import importlib.util
import os
from pathlib import Path
import tempfile
import threading
import unittest
from unittest.mock import Mock


spec = importlib.util.spec_from_file_location("update_smoke", Path(__file__).with_name("update-smoke.py"))
update = importlib.util.module_from_spec(spec)
spec.loader.exec_module(update)

APKS = ["--manager", "m.apk", "--compat", "c.apk", "--native", "n.apk", "--legacy", "l.apk",
        "--shizuku", "s.apk", "--fixture", "f.apk"]


class CaseSelectionTest(unittest.TestCase):
    def parse(self, *cases):
        argv = ["--serial", "emulator-5554", "--output", "out", *APKS]
        for case in cases:
            argv += ["--case", case]
        return update.parse_args(argv)

    def test_the_workflow_arguments_are_accepted(self):
        self.assertIsNone(self.parse().cases)

    def test_the_release_flag_is_accepted(self):
        self.assertFalse(self.parse().release)
        self.assertTrue(update.parse_args(["--serial", "emulator-5554", "--output", "out", *APKS,
                                           "--release"]).release)

    def test_setup_is_required(self):
        with self.assertRaises(SystemExit):
            self.parse("manual-update")

    def test_a_single_case_runs_with_setup(self):
        self.assertEqual(self.parse("setup", "root-update").cases, ["setup", "root-update"])


class RestoreTest(unittest.TestCase):
    """Each case leaves no server and no fixture session behind for the next one to trip over."""

    def setUp(self):
        self.runner = update.Update.__new__(update.Update)
        self.runner.kill_server = Mock()
        self.runner.pid = Mock(return_value="")
        self.runner.until = Mock()
        self.session = Mock()
        self.session.poll.return_value = None
        self.runner.fixture_session = self.session

    def test_servers_and_the_fixture_session_are_cleared(self):
        self.runner.restore("servers")
        self.runner.kill_server.assert_called_once_with(check=False)
        self.session.kill.assert_called_once()
        self.assertIsNone(self.runner.fixture_session)

    def test_a_session_that_already_ended_is_only_collected(self):
        self.session.poll.return_value = 137
        self.runner.restore("servers")
        self.session.kill.assert_not_called()
        self.session.communicate.assert_called_once()


def poll(description, condition, timeout=30):
    while not (value := condition()):
        pass
    return value


class SuccessorTest(unittest.TestCase):
    def test_waits_out_the_old_servers_child(self):
        runner = update.Update.__new__(update.Update)
        runner.pid = Mock(side_effect=["6113", "6113 6173", "", "6178"])
        runner.until = poll
        runner.server_log = Mock(return_value="I Service : sent binders")
        runner.classpath = Mock(return_value="base.apk")
        runner.manager_apk = Mock(return_value="base.apk")
        self.assertEqual(runner.successor("6113"), "6178")
        runner.server_log.assert_called_once_with("6178")


class FixturePidTest(unittest.TestCase):
    """The fixture's pid is the one its shell printed, never a guess from pidof."""

    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.runner = update.Update.__new__(update.Update)
        self.runner.output = Path(directory.name)
        self.runner.fixture_session = None
        # What the device answers once the manager has already replaced the fixture.
        self.runner.pid = Mock(side_effect=["", "2410"])
        self.runner.shell = Mock(return_value="package:/data/app/fixture/base.apk")
        self.runner.starter_binary = Mock(return_value="/data/app/porter/lib/x86_64/libporter.so")
        self.runner.until = poll
        read, self.write = os.pipe()
        self.addCleanup(self.close_writer)
        self.stdout = os.fdopen(read, "rb")
        self.addCleanup(self.stdout.close)
        self.session = Mock(stdout=self.stdout, args=["adb", "shell", "sh -c ..."])
        self.runner.detached = Mock(return_value=self.session)

    def close_writer(self):
        if self.write is not None:
            os.close(self.write)
            self.write = None

    def test_a_fixture_replaced_before_any_poll_is_still_the_old_server(self):
        os.write(self.write, b"2371\n")
        self.assertEqual(self.runner.start_fixture("outdated", verify=False), "2371")
        self.assertIn("(fixture pid 2371)", (self.runner.output / "commands.log").read_text())

    def test_the_shell_prints_its_pid_and_execs_the_fixture_onto_it(self):
        os.write(self.write, b"2371\n")
        self.runner.start_fixture("outdated", verify=False)
        command = self.runner.detached.call_args.args[-1]
        self.assertTrue(command.startswith("echo $$; export CLASSPATH=/data/app/fixture/base.apk; exec app_process "))
        self.assertTrue(command.endswith(" outdated </dev/null >/dev/null 2>&1"))

    def test_a_root_fixture_execs_in_the_shell_su_started(self):
        os.write(self.write, b"2371\n")
        self.runner.start_fixture("outdated", root=True, verify=False)
        self.assertEqual(self.runner.detached.call_args.args[:4], ("su", "0", "sh", "-c"))

    def test_a_launch_that_fails_on_stderr_reports_it_and_leaves_nothing_to_restore(self):
        self.close_writer()
        self.session.poll.return_value = 1
        self.session.communicate.return_value = (b"", b"/system/bin/sh: su: inaccessible or not found\n")
        with self.assertRaisesRegex(AssertionError, "ended before printing its pid.*su: inaccessible"):
            self.runner.start_fixture("outdated", root=True, verify=False)
        self.session.kill.assert_not_called()
        self.assertIsNone(self.runner.fixture_session)
        self.assertIn("adb shell 'sh -c ...'", (self.runner.output / "commands.log").read_text())

    def test_a_pid_that_arrives_in_pieces_is_read_to_its_newline(self):
        os.write(self.write, b"23")
        threading.Timer(0.1, os.write, (self.write, b"71\n")).start()
        self.assertEqual(self.runner.launched_pid(self.session, timeout=5), "2371")

    def test_a_shell_that_ends_without_a_pid_fails(self):
        os.write(self.write, b"23")
        self.close_writer()
        with self.assertRaisesRegex(AssertionError, "ended before printing its pid"):
            self.runner.launched_pid(self.session, timeout=5)

    def test_output_that_is_not_a_pid_fails(self):
        os.write(self.write, b"su: not found\n")
        with self.assertRaisesRegex(AssertionError, "not a pid"):
            self.runner.launched_pid(self.session, timeout=5)

    def test_a_shell_that_prints_nothing_times_out(self):
        with self.assertRaisesRegex(AssertionError, "Timed out: the fixture's pid"):
            self.runner.launched_pid(self.session, timeout=0.1)


if __name__ == "__main__":
    unittest.main()
