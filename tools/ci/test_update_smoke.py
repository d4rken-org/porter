import importlib.util
from pathlib import Path
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


if __name__ == "__main__":
    unittest.main()
