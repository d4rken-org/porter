import contextlib
import importlib.util
import io
from pathlib import Path
import unittest
from unittest.mock import Mock, call, patch


spec = importlib.util.spec_from_file_location("dualwire_smoke", Path(__file__).with_name("dualwire-smoke.py"))
dualwire = importlib.util.module_from_spec(spec)
spec.loader.exec_module(dualwire)


class ArgumentTest(unittest.TestCase):
    REQUIRED = ["--serial", "emulator-5554", "--output", "build/emulator-results",
                "--manager", "manager.apk", "--compat", "compat.apk", "--native", "native.apk",
                "--legacy", "legacy.apk", "--shizuku", "shizuku.apk", "--bridge", "bridge.apk",
                "--unlisted", "unlisted.apk"]

    def parse(self, *extra):
        return dualwire.parse_args(self.REQUIRED + list(extra))

    def rejected(self, *extra):
        stderr = io.StringIO()
        with self.assertRaises(SystemExit), contextlib.redirect_stderr(stderr):
            self.parse(*extra)
        return stderr.getvalue()

    def test_the_shared_workflow_arguments_and_this_suite_s_own_are_both_accepted(self):
        args = self.parse()
        self.assertEqual((args.manager, args.compat, args.native, args.legacy, args.shizuku),
                         (Path("manager.apk"), Path("compat.apk"), Path("native.apk"),
                          Path("legacy.apk"), Path("shizuku.apk")))
        self.assertEqual((args.bridge, args.unlisted), (Path("bridge.apk"), Path("unlisted.apk")))
        self.assertIsNone(args.cases)

    def test_an_omitted_apk_is_rejected(self):
        stderr = io.StringIO()
        with self.assertRaises(SystemExit), contextlib.redirect_stderr(stderr):
            dualwire.parse_args(self.REQUIRED[:-2])
        self.assertIn("--unlisted", stderr.getvalue())

    def test_the_flag_repeats_into_one_selection(self):
        self.assertEqual(self.parse("--case", "setup", "--case", "visibility-listed-manager").cases,
                         ["setup", "visibility-listed-manager"])

    def test_a_selection_without_setup_is_rejected(self):
        self.assertIn("--case setup is required", self.rejected("--case", "selection-prefers-porter"))

    def test_a_selection_dropping_a_case_a_later_one_needs_is_rejected(self):
        # Only shizuku-permission-lifecycle installs Shizuku and starts its server, so without it
        # selection-prefers-porter asserts a preference on a device holding one backend.
        message = self.rejected("--case", "setup", "--case", "selection-prefers-porter")
        self.assertIn("shizuku-permission-lifecycle", message)

    def test_a_selection_leaving_porter_installed_for_a_later_case_is_rejected(self):
        # selection-prefers-porter leaves the manager installed and its server up, and only
        # selection-porter-stopped removes them, so without it the recovery case waits out its
        # timeout on BACKEND SHIZUKU against a device that answers with Porter.
        message = self.rejected("--case", "setup", "--case", "shizuku-permission-lifecycle",
                                "--case", "selection-prefers-porter",
                                "--case", "multiprocess-delivery-and-recovery")
        self.assertIn("selection-porter-stopped", message)

    def test_a_selection_that_never_installs_porter_keeps_the_recovery_case(self):
        selection = ["setup", "shizuku-permission-lifecycle", "multiprocess-delivery-and-recovery"]
        self.assertEqual(self.parse(*[arg for name in selection for arg in ("--case", name)]).cases,
                         selection)

    def test_a_selection_ending_at_the_case_that_installs_porter_is_accepted(self):
        selection = ["setup", "shizuku-permission-lifecycle", "selection-prefers-porter"]
        self.assertEqual(self.parse(*[arg for name in selection for arg in ("--case", name)]).cases,
                         selection)

    def test_a_selection_naming_the_whole_chain_is_accepted(self):
        chain = ["setup", "shizuku-permission-lifecycle", "selection-prefers-porter",
                 "selection-porter-stopped"]
        self.assertEqual(self.parse(*[arg for name in chain for arg in ("--case", name)]).cases,
                         chain)

    def test_an_unknown_case_is_rejected_and_names_the_valid_ones(self):
        message = self.rejected("--case", "setup", "--case", "dualwire")
        self.assertIn("dualwire", message)
        for name in dualwire.CASES:
            self.assertIn(name, message)


class LaunchBridgeTest(unittest.TestCase):
    def setUp(self):
        self.runner = dualwire.Dualwire.__new__(dualwire.Dualwire)
        self.runner.shell = Mock(return_value="")
        self.runner.until = Mock(return_value="4711")
        self.runner.expect_log = Mock()
        self.runner.logs = Mock(return_value="")

    def starts(self):
        return [c for c in self.runner.shell.call_args_list
                if c.args[0] == "am" and c.args[1] == "start"]

    def stops(self):
        return [c for c in self.runner.shell.call_args_list if c.args[1] == "force-stop"]

    def test_only_the_bridge_package_is_stopped_before_its_own_activity_starts(self):
        self.runner.launch_bridge()
        self.assertEqual(self.stops(), [call("am", "force-stop", dualwire.BRIDGE)])
        self.assertEqual(self.starts(), [call(
            "am", "start", "-W", "-n", dualwire.BRIDGE + "/eu.darken.porter.probe.ProbeActivity",
            timeout=dualwire.base.LAUNCH_TIMEOUT)])
        self.assertEqual(self.runner.probe_pid, "4711")

    def test_an_expected_binder_is_waited_for_after_the_mode_line(self):
        self.runner.launch_bridge()
        self.assertEqual(self.runner.expect_log.call_args_list, [
            call(dualwire.BRIDGE, "MODE daemon=false peek=false"),
            call(dualwire.BRIDGE, "BINDER uid=2000 version="),
        ])

    @patch.object(dualwire.time, "sleep")
    def test_no_expected_binder_settles_and_asserts_absence_instead_of_waiting_for_a_line(self, sleep):
        self.runner.launch_bridge(expect_binder=False)
        self.runner.expect_log.assert_called_once_with(
            dualwire.BRIDGE, "MODE daemon=false peek=false")
        sleep.assert_called_once_with(dualwire.NO_BINDER_SETTLE)
        self.runner.logs.assert_called_once_with()

    @patch.object(dualwire.time, "sleep")
    def test_a_binder_that_did_arrive_fails_the_launch_that_expected_none(self, sleep):
        self.runner.logs = Mock(return_value=dualwire.BRIDGE + " BINDER uid=2000 version=13\n")
        with self.assertRaises(AssertionError):
            self.runner.launch_bridge(expect_binder=False)

    @patch.object(dualwire.time, "sleep")
    def test_a_launch_that_expected_no_binder_stops_the_package_it_leaves_behind(self, sleep):
        # A probe left running keeps its sticky binder listeners registered, so a server a later
        # case starts pushes a binder into it and that process answers a permission dialog this
        # suite is no longer watching. Stopping it before the next launch is too late: the push
        # happens in between.
        order = Mock()
        order.attach_mock(self.runner.shell, "shell")
        order.attach_mock(self.runner.logs, "logs")
        self.runner.launch_bridge(expect_binder=False)
        self.assertEqual(order.mock_calls[-2:],
                         [call.logs(), call.shell("am", "force-stop", dualwire.BRIDGE)],
                         "the no-binder branch must force-stop the bridge after asserting that no "
                         "binder arrived, not only before the launch")
        self.assertEqual(self.stops(), [call("am", "force-stop", dualwire.BRIDGE)] * 2)

    def test_the_activity_names_the_component_that_starts(self):
        self.runner.launch_bridge(activity=".SecondaryActivity")
        self.assertEqual(self.starts(), [call(
            "am", "start", "-W", "-n",
            dualwire.BRIDGE + "/eu.darken.porter.probe.SecondaryActivity",
            timeout=dualwire.base.LAUNCH_TIMEOUT)])


class ProcessLogTest(unittest.TestCase):
    MAIN = "4711"
    SECONDARY = "4712"

    def setUp(self):
        self.runner = dualwire.Dualwire.__new__(dualwire.Dualwire)
        self.buffers = {
            self.MAIN: [dualwire.BRIDGE + " MODE daemon=false peek=false",
                        dualwire.BRIDGE + " BINDER uid=2000 version=13"],
            self.SECONDARY: [dualwire.BRIDGE + " SECONDARY STARTED",
                             dualwire.BRIDGE + " SECONDARY BINDER uid=2000 backend=SHIZUKU"],
        }
        self.runner.adb = Mock(side_effect=lambda *args: "\n".join(
            self.buffers[next(a for a in args if a.startswith("--pid=")).removeprefix("--pid=")]))

    def test_each_process_reads_only_its_own_lines(self):
        self.assertIn("BINDER uid=2000 version=13", self.runner.logs_for(self.MAIN))
        self.assertNotIn("SECONDARY", self.runner.logs_for(self.MAIN))
        self.assertIn("SECONDARY BINDER", self.runner.logs_for(self.SECONDARY))
        self.assertEqual(self.runner.adb.call_args_list[0],
                         call("logcat", "-d", "--pid=4711", "-s", "PorterProbe:I", "*:S"))

    def test_a_line_from_before_the_boundary_is_not_a_fresh_one(self):
        boundary = self.runner.since(self.MAIN)
        with patch.object(dualwire.time, "sleep"), \
                patch.object(dualwire.time, "monotonic", side_effect=[0, 0, 31]):
            with self.assertRaisesRegex(AssertionError, "Timed out"):
                self.runner.fresh(self.MAIN, boundary, "BINDER uid=2000 version=")

    def test_the_same_line_logged_again_after_the_boundary_is_fresh(self):
        boundary = self.runner.since(self.MAIN)
        self.buffers[self.MAIN].append(dualwire.BRIDGE + " BINDER_DEAD")
        self.buffers[self.MAIN].append(dualwire.BRIDGE + " BINDER uid=2000 version=13")
        with patch.object(dualwire.time, "sleep"), \
                patch.object(dualwire.time, "monotonic", side_effect=[0, 1]):
            self.assertTrue(self.runner.fresh(self.MAIN, boundary, "BINDER uid=2000 version="))

    def test_one_process_s_boundary_does_not_hide_the_other_s_lines(self):
        boundary = self.runner.since(self.SECONDARY)
        self.buffers[self.SECONDARY].append(dualwire.BRIDGE + " SECONDARY BINDER_DEAD")
        with patch.object(dualwire.time, "sleep"), \
                patch.object(dualwire.time, "monotonic", side_effect=[0, 1]):
            self.assertTrue(self.runner.fresh(self.SECONDARY, boundary, "SECONDARY BINDER_DEAD"))

    def test_lines_the_buffer_dropped_from_the_front_do_not_replay_as_new(self):
        boundary = [str(line) for line in range(4)]
        # The first two aged out while the last two stayed, so only "4" is new.
        self.assertEqual(dualwire.added(boundary, ["2", "3", "4"]), ["4"])
        self.assertEqual(dualwire.added(boundary, boundary), [])
        self.assertEqual(dualwire.added([], ["0"]), ["0"])


class ShizukuPermissionLifecycleTest(unittest.TestCase):
    """The permission case's call order, harvested by running run() with case() stubbed.

    Coupled to case bodies staying inside run(): if they ever move into methods, this needs
    rewriting rather than adjusting.
    """

    REVOKE = call.shell("pm", "revoke", dualwire.BRIDGE, dualwire.SHIZUKU_PERMISSION)

    def setUp(self):
        self.runner = dualwire.Dualwire.__new__(dualwire.Dualwire)
        self.runner.args = Mock()
        bodies = {}
        self.runner.case = lambda name, action, restore=(): bodies.setdefault(name, action)
        self.order = Mock()
        for name, mock in (
                ("shell", Mock(return_value="")),
                ("adb", Mock(return_value="")),
                ("pid", Mock(return_value="4711")),
                ("logs", Mock(return_value="")),
                # The real until() calls its condition, so running it here records on the parent
                # which process each wait polls.
                ("until", Mock(side_effect=lambda description, condition, **kwargs:
                               condition() or "4711")),
                ("start_service", Mock()),
                ("launch_bridge", Mock()),
                ("tap", Mock()),
                ("expect_log", Mock()),
                ("authorized", Mock())):
            setattr(self.runner, name, mock)
            self.order.attach_mock(mock, name)
        self.runner.run()
        bodies["shizuku-permission-lifecycle"]()

    def between_the_revoke_and_the_relaunch(self):
        calls = self.order.mock_calls
        revoked = calls.index(self.REVOKE)
        relaunched = next(index for index, entry in enumerate(calls[revoked:], revoked)
                          if entry[0] == "launch_bridge")
        return calls[revoked + 1:relaunched]

    def test_the_revoked_client_and_its_user_service_are_waited_out_before_the_relaunch(self):
        # pm revoke returns before Android has killed the revoked uid. Relaunching inside that
        # window lets the queued kill land on the replacement process instead, and the assertion
        # that follows sits outside launch_bridge's retry loop, so the case times out rather than
        # retrying.
        # A wait's own arguments are not the subject here, the process its condition polls is.
        polled = [(name, args[0] if name == "pid" else None)
                  for name, args, _ in self.between_the_revoke_and_the_relaunch()]
        self.assertEqual(polled, [
            ("until", None), ("pid", dualwire.BRIDGE),
            ("until", None), ("pid", dualwire.BRIDGE + ":porter-probe"),
        ], "between the revoke and the relaunch the case must wait for the client process and then "
           "for its user service to go away, so that the revoke's queued uid kill cannot land on "
           "the process the relaunch starts")

    def test_each_of_those_waits_holds_while_its_process_is_still_there(self):
        # Which process a wait polls says nothing about which answer it waits for, and a wait for
        # the process to appear is satisfied by the very process the kill is still queued against.
        conditions = [c.args[1] for c in self.between_the_revoke_and_the_relaunch()
                      if c[0] == "until"]
        self.assertEqual(len(conditions), 2,
                         "the revoke is not followed by two waits before the relaunch")
        for index, condition in enumerate(conditions):
            self.runner.pid = Mock(return_value="4711")
            self.assertFalse(condition(), f"wait {index} is over while its process is still alive")
            self.runner.pid = Mock(return_value="")
            self.assertTrue(condition(), f"wait {index} never ends once its process is gone")


if __name__ == "__main__":
    unittest.main()
