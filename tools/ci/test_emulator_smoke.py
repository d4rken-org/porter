import argparse
import contextlib
import importlib.util
import io
import itertools
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, call, patch
import xml.etree.ElementTree as ET


spec = importlib.util.spec_from_file_location("emulator_smoke", Path(__file__).with_name("emulator-smoke.py"))
smoke = importlib.util.module_from_spec(spec)
spec.loader.exec_module(smoke)

OFFLINE = b"adb: device offline\n"
NOT_FOUND = b"adb: device 'emulator-5554' not found\n"


def completed(returncode, stdout=b"", stderr=b""):
    return smoke.subprocess.CompletedProcess(["adb"], returncode, stdout, stderr)


class UiDumpTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.runner = smoke.Smoke(argparse.Namespace(
            serial="emulator-5554", output=Path(directory.name)))
        self.path = "/data/local/tmp/porter-ci-ui.xml"
        self.xml = '<hierarchy><node text="Settings" /></hierarchy>'
        self.success = completed(0, stdout=f"UI hierchary dumped to: {self.path}\n".encode())
        self.null_root = completed(0, stderr=b"ERROR: null root node returned by UiTestAutomationBridge.\n")

    @patch.object(smoke.time, "sleep")
    def test_null_root_with_zero_exit_status_is_retried(self, sleep):
        with patch.object(smoke.subprocess, "run", side_effect=[
                completed(0), self.null_root,
                completed(0), self.success, completed(0, stdout=self.xml.encode())]) as run:
            self.assertEqual(self.runner.ui().find("node").get("text"), "Settings")
        self.assertEqual([c.args[0][-1] for c in run.call_args_list], [
            f"rm -f {self.path}", f"uiautomator dump {self.path}",
            f"rm -f {self.path}", f"uiautomator dump {self.path}", f"cat {self.path}",
        ])
        self.assertEqual((self.runner.output / "last-ui.xml").read_text(), self.xml)
        self.assertIn("null root node", (self.runner.output / "commands.log").read_text())
        sleep.assert_called_once_with(0.4)

    @patch.object(smoke.time, "sleep")
    def test_failed_dump_cannot_reuse_a_previous_hierarchy(self, sleep):
        remote = {self.path: '<hierarchy><node text="Stale" /></hierarchy>'}

        def shell(*args):
            if args == ("rm", "-f", self.path):
                remote.pop(self.path, None)
                return ""
            if args == ("uiautomator", "dump", self.path):
                return ""
            self.fail(f"Unexpected command: {args}")

        self.runner.shell = Mock(side_effect=shell)
        with self.assertRaisesRegex(RuntimeError, "after 3 attempts.*commands.log"):
            self.runner.ui()
        self.assertNotIn(self.path, remote)
        self.assertFalse((self.runner.output / "last-ui.xml").exists())
        self.assertEqual(sleep.call_count, 2)
        self.assertEqual(self.runner.shell.call_count, 6)

    @patch.object(smoke.time, "sleep")
    def test_successful_dump_needs_no_retry(self, sleep):
        with patch.object(smoke.subprocess, "run", side_effect=[
                completed(0), self.success, completed(0, stdout=self.xml.encode())]):
            self.assertEqual(self.runner.ui().tag, "hierarchy")
        sleep.assert_not_called()

    @patch.object(smoke.time, "sleep")
    def test_command_failure_is_not_hidden_by_ui_retries(self, sleep):
        with patch.object(smoke.subprocess, "run", side_effect=[
                completed(0), completed(1, stderr=b"uiautomator: inaccessible\n")]) as run:
            with self.assertRaisesRegex(RuntimeError, "uiautomator: inaccessible"):
                self.runner.ui()
        self.assertEqual(run.call_count, 2)
        sleep.assert_not_called()


class ScrollToActionTest(unittest.TestCase):
    def setUp(self):
        self.runner = smoke.Smoke.__new__(smoke.Smoke)
        self.runner.shell = Mock()
        self.hidden = ET.fromstring('''<hierarchy><node package="eu.darken.porter"
            scrollable="true" bounds="[0,231][1080,1794]">
            <node text="Import saved approvals" package="eu.darken.porter" enabled="true" />
            </node></hierarchy>''')
        self.visible = ET.fromstring('''<hierarchy><node package="eu.darken.porter"
            scrollable="true" bounds="[0,231][1080,1794]">
            <node text="Install automatically" package="eu.darken.porter" enabled="true"
            bounds="[600,1100][950,1200]" /></node></hierarchy>''')

    @patch.object(smoke.time, "sleep")
    def test_retry_scrolls_before_tapping_action_below_saved_import(self, sleep):
        self.runner.ui = Mock(side_effect=[self.hidden, self.visible])
        self.runner.tap("Install automatically", scroll=True)
        self.assertEqual(self.runner.shell.call_args_list, [
            call("input", "swipe", 540, 1482, 540, 543, 300),
            call("input", "tap", 775, 1150),
        ])

    def test_visible_action_does_not_scroll(self):
        self.runner.ui = Mock(return_value=self.visible)
        self.runner.tap("Install automatically", scroll=True)
        self.runner.shell.assert_called_once_with("input", "tap", 775, 1150)

    @patch.object(smoke.time, "sleep")
    @patch.object(smoke.time, "monotonic", side_effect=[0, 0, 31])
    def test_existing_callers_do_not_scroll_and_missing_action_still_fails(self, monotonic, sleep):
        self.runner.ui = Mock(return_value=self.hidden)
        with self.assertRaisesRegex(AssertionError, "Timed out"):
            self.runner.tap("Install automatically")
        self.runner.shell.assert_not_called()


class ContentDescriptionTapTest(unittest.TestCase):
    def setUp(self):
        self.runner = smoke.Smoke.__new__(smoke.Smoke)
        self.runner.shell = Mock()
        # The settings icon carries a content description and no text of its own.
        self.screen = ET.fromstring('''<hierarchy><node package="eu.darken.porter">
            <node text="Settings" package="eu.darken.porter" enabled="true" bounds="[0,0][100,100]" />
            <node content-desc="Settings" package="eu.darken.porter" enabled="true"
            bounds="[953,126][1058,231]" /></node></hierarchy>''')

    def test_taps_the_node_carrying_the_content_description(self):
        self.runner.ui = Mock(return_value=self.screen)
        self.runner.tap(desc="Settings")
        self.runner.shell.assert_called_once_with("input", "tap", 1005, 178)

    def test_text_matching_ignores_content_descriptions(self):
        self.runner.ui = Mock(return_value=self.screen)
        self.runner.tap("Settings")
        self.runner.shell.assert_called_once_with("input", "tap", 50, 50)

    @patch.object(smoke.time, "sleep")
    @patch.object(smoke.time, "monotonic", side_effect=[0, 0, 31])
    def test_an_absent_description_still_fails(self, monotonic, sleep):
        self.runner.ui = Mock(return_value=self.screen)
        with self.assertRaisesRegex(AssertionError, "content-desc 'Saved debug logs'"):
            self.runner.tap(desc="Saved debug logs")
        self.runner.shell.assert_not_called()


class StopPorterConfirmationTest(unittest.TestCase):
    def setUp(self):
        self.runner = smoke.Smoke.__new__(smoke.Smoke)
        self.runner.shell = Mock()
        self.runner.screenshot = Mock()
        self.home = ET.fromstring('''<hierarchy><node package="eu.darken.porter">
            <node text="Porter is running" package="eu.darken.porter" enabled="true"
            bounds="[200,326][524,389]" /></node></hierarchy>''')
        self.service = ET.fromstring('''<hierarchy><node package="eu.darken.porter">
            <node text="Stop Porter" package="eu.darken.porter" enabled="true"
            bounds="[158,595][347,648]" /></node></hierarchy>''')
        # A dialog covers the screen behind it, so only its own window is dumped.
        self.dialog = ET.fromstring('''<hierarchy><node package="eu.darken.porter">
            <node text="Stop Porter" package="eu.darken.porter" enabled="true"
            bounds="[183,691][501,775]" />
            <node text="Cancel" package="eu.darken.porter" enabled="true"
            bounds="[477,1076][591,1129]" />
            <node text="Stop Porter" package="eu.darken.porter" enabled="true"
            bounds="[676,1076][865,1129]" /></node></hierarchy>''')

    @patch.object(smoke.time, "sleep")
    def test_confirms_the_dialog_before_waiting_for_the_server_to_exit(self, sleep):
        self.runner.ui = Mock(side_effect=[self.home, self.service, self.service, self.dialog])
        self.runner.pid = Mock(side_effect=["5271", ""])
        self.runner.stop_porter()
        self.assertEqual(self.runner.shell.call_args_list, [
            call("am", "start", "-W", "-f", "0x04000000",
                 "-n", "eu.darken.porter/eu.darken.porter.manager.MainActivity"),
            call("input", "tap", 362, 357),
            call("input", "tap", 252, 621),
            call("input", "tap", 770, 1102),
        ])
        self.runner.screenshot.assert_called_once_with("running-service-dialog")

    @patch.object(smoke.time, "sleep")
    @patch.object(smoke.time, "monotonic", side_effect=[0, 0, 31])
    def test_a_single_match_never_satisfies_the_confirm_button(self, monotonic, sleep):
        self.runner.ui = Mock(return_value=self.service)
        with self.assertRaisesRegex(AssertionError, "Timed out"):
            self.runner.tap("Stop Porter", occurrence=1)
        self.runner.shell.assert_not_called()


class TransportRetryTest(unittest.TestCase):
    def setUp(self):
        self.runner = smoke.Smoke.__new__(smoke.Smoke)
        self.runner.args = Mock(serial="emulator-5554")
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.runner.output = Path(directory.name)

    def logged(self, command):
        return (self.runner.output / "commands.log").read_text().splitlines().count(command)

    @patch.object(smoke.time, "sleep")
    def test_a_dropped_transport_is_retried_and_every_attempt_is_logged(self, sleep):
        with patch.object(smoke.subprocess, "run", side_effect=[
                completed(1, stderr=OFFLINE), completed(0, stdout=b"5271\n")]) as run:
            self.assertEqual(self.runner.adb("shell", "pidof porter_server"), "5271")
        self.assertEqual(run.call_count, 2)
        self.assertEqual(self.logged("adb -s emulator-5554 shell 'pidof porter_server'"), 2)

    @patch.object(smoke.time, "sleep")
    def test_an_exhausted_transport_failure_surfaces(self, sleep):
        with patch.object(smoke.subprocess, "run", return_value=completed(1, stderr=NOT_FOUND)) as run:
            with self.assertRaisesRegex(RuntimeError, "not found"):
                self.runner.adb("shell", "true")
        self.assertEqual(run.call_count, 3)
        self.assertEqual(self.logged("adb -s emulator-5554 shell true"), 3)

    @patch.object(smoke.time, "sleep")
    def test_a_genuine_command_failure_is_not_retried(self, sleep):
        stderr = b"cmd: Failure calling service package: Unknown permission\n"
        with patch.object(smoke.subprocess, "run", return_value=completed(1, stderr=stderr)) as run:
            with self.assertRaisesRegex(RuntimeError, "Failure calling service"):
                self.runner.adb("shell", "pm revoke eu.darken.porter.probe.native nonsense")
        run.assert_called_once()
        sleep.assert_not_called()

    @patch.object(smoke.time, "sleep")
    def test_a_timeout_propagates_instead_of_running_the_command_again(self, sleep):
        with patch.object(smoke.subprocess, "run",
                          side_effect=smoke.subprocess.TimeoutExpired(["adb"], 45)) as run:
            with self.assertRaises(smoke.subprocess.TimeoutExpired):
                self.runner.adb("shell", "true")
        run.assert_called_once()
        sleep.assert_not_called()

    @patch.object(smoke.time, "sleep")
    def test_a_marker_on_a_successful_command_is_not_a_transport_failure(self, sleep):
        with patch.object(smoke.subprocess, "run",
                          return_value=completed(0, stdout=b"device offline\n", stderr=OFFLINE)) as run:
            self.assertEqual(self.runner.adb("shell", "echo device offline"), "device offline")
        run.assert_called_once()
        sleep.assert_not_called()

    @patch.object(smoke.time, "sleep")
    def test_an_unchecked_caller_observes_the_retried_result(self, sleep):
        with patch.object(smoke.subprocess, "run", side_effect=[
                completed(1, stderr=OFFLINE), completed(0, stdout=b"5271\n")]) as run:
            self.assertEqual(self.runner.shell("pidof", "porter_server", check=False), "5271")
        self.assertEqual(run.call_count, 2)

class DetachedCommandTest(unittest.TestCase):
    def setUp(self):
        self.runner = smoke.Smoke.__new__(smoke.Smoke)
        self.runner.args = Mock(serial="emulator-5554")
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.runner.output = Path(directory.name)

    def pending(self, returncode, stdout=b"", stderr=b""):
        process = Mock(args=["adb", "-s", "emulator-5554", "shell", "sh -c porsh"], returncode=returncode)
        process.communicate.return_value = (stdout, stderr)
        return process

    def test_the_command_is_started_like_a_foreground_shell_and_left_running(self):
        with patch.object(smoke.subprocess, "Popen") as popen:
            self.assertIs(self.runner.detached("sh", "-c", "printf hello"), popen.return_value)
        self.assertEqual(popen.call_args.args[0],
                         ["adb", "-s", "emulator-5554", "shell", "sh -c 'printf hello'"])
        self.assertEqual(popen.call_args.kwargs["stdin"], smoke.subprocess.DEVNULL)

    def test_settling_logs_the_command_with_its_output(self):
        self.runner.settle(self.pending(0, stdout=b"done\n", stderr=b"warned\n"))
        self.assertEqual((self.runner.output / "commands.log").read_text(),
                         "adb -s emulator-5554 shell 'sh -c porsh'\ndone\nwarned\n")

    def test_a_failed_command_surfaces_its_stderr(self):
        with self.assertRaisesRegex(RuntimeError, "sh -c porsh.*device offline"):
            self.runner.settle(self.pending(1, stderr=OFFLINE))

    def test_a_command_that_never_ends_is_killed_before_the_timeout_propagates(self):
        process = self.pending(None)
        process.communicate.side_effect = [smoke.subprocess.TimeoutExpired(process.args, 45), (b"", b"")]
        with self.assertRaises(smoke.subprocess.TimeoutExpired):
            self.runner.settle(process, timeout=45)
        process.kill.assert_called_once()
        self.assertEqual(process.communicate.call_count, 2)


class CaseSelectionTest(unittest.TestCase):
    def setUp(self):
        self.runner = smoke.Smoke.__new__(smoke.Smoke)
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.runner.output = Path(directory.name)
        self.runner.results = []
        self.runner.adb = Mock(return_value="")
        self.ran = []

    def declare(self, *cases):
        """Offer every case to the choke point in the order run() declares them."""
        self.runner.args = Mock(cases=list(cases) or None)
        for name in smoke.CASES:
            self.runner.case(name, lambda name=name: self.ran.append(name))

    def recorded(self):
        return [result["name"] for result in self.runner.results]

    def test_the_valid_names_are_the_order_run_declares(self):
        self.runner.args = Mock(cases=None)
        self.runner.case = Mock()
        self.runner.run()
        self.assertEqual([c.args[0] for c in self.runner.case.call_args_list], list(smoke.CASES))

    def test_without_a_selection_every_case_runs(self):
        self.declare()
        self.assertEqual(self.ran, list(smoke.CASES))
        self.assertEqual(self.recorded(), list(smoke.CASES))

    def test_a_selection_runs_only_its_cases_in_declared_order(self):
        self.declare("porsh", "setup")
        self.assertEqual(self.ran, ["setup", "porsh"])
        self.assertEqual(self.recorded(), ["setup", "porsh"])

    def test_a_skipped_case_is_absent_from_both_reports(self):
        self.declare("setup", "porsh")
        self.runner.reports()
        results = json.loads((self.runner.output / "results.json").read_text())
        self.assertEqual([result["name"] for result in results], ["setup", "porsh"])
        suite = ET.parse(self.runner.output / "junit.xml").getroot()
        self.assertEqual([case.get("name") for case in suite], ["setup", "porsh"])
        self.assertEqual((suite.get("tests"), suite.get("failures")), ("2", "0"))

    def test_a_namespace_without_case_support_still_runs_every_case(self):
        # compat-install-smoke.py builds its own parser, which has no --case flag at all.
        self.runner.args = argparse.Namespace()
        self.runner.case("setup", lambda: self.ran.append("setup"))
        self.assertEqual(self.ran, ["setup"])
        self.assertEqual(self.recorded(), ["setup"])


class CaseArgumentTest(unittest.TestCase):
    REQUIRED = ["--serial", "emulator-5554", "--output", "build/emulator-results",
                "--manager", "manager.apk", "--compat", "compat.apk", "--native", "native.apk",
                "--legacy", "legacy.apk", "--shizuku", "shizuku.apk"]

    def parse(self, *extra):
        return smoke.parse_args(self.REQUIRED + list(extra))

    def rejected(self, *extra):
        stderr = io.StringIO()
        with self.assertRaises(SystemExit), contextlib.redirect_stderr(stderr):
            self.parse(*extra)
        return stderr.getvalue()

    def test_an_omitted_flag_leaves_the_suite_unfiltered(self):
        self.assertIsNone(self.parse().cases)

    def test_the_flag_repeats_into_one_selection(self):
        self.assertEqual(self.parse("--case", "setup", "--case", "porsh").cases, ["setup", "porsh"])

    def test_a_selection_without_setup_is_rejected(self):
        self.assertIn("--case setup is required", self.rejected("--case", "porsh"))

    def test_an_unknown_case_is_rejected_and_names_the_valid_ones(self):
        message = self.rejected("--case", "setup", "--case", "porsch")
        self.assertIn("porsch", message)
        for name in smoke.CASES:
            self.assertIn(name, message)
class LaunchProbeModeTest(unittest.TestCase):
    def setUp(self):
        self.runner = smoke.Smoke.__new__(smoke.Smoke)
        self.runner.shell = Mock(return_value="")
        self.runner.until = Mock(return_value="4711")
        self.runner.expect_log = Mock()

    def launched(self):
        return [c for c in self.runner.shell.call_args_list if c.args[0] == "am" and c.args[1] == "start"]

    def test_the_default_launch_asks_for_neither_mode(self):
        self.runner.launch_probe(smoke.NATIVE)
        self.assertEqual(self.launched(), [call(
            "am", "start", "-W", "-n", smoke.NATIVE + "/eu.darken.porter.probe.ProbeActivity",
            timeout=smoke.LAUNCH_TIMEOUT)])
        self.runner.expect_log.assert_any_call(smoke.NATIVE, "MODE daemon=false peek=false")

    def test_a_daemon_launch_passes_the_extra_and_asserts_the_mode_that_ran(self):
        self.runner.launch_probe(smoke.NATIVE, daemon=True)
        self.assertEqual(self.launched(), [call(
            "am", "start", "-W", "-n", smoke.NATIVE + "/eu.darken.porter.probe.ProbeActivity",
            "--ez", "daemon", "true", timeout=smoke.LAUNCH_TIMEOUT)])
        self.runner.expect_log.assert_any_call(smoke.NATIVE, "MODE daemon=true peek=false")

    def test_a_peek_launch_exercises_the_no_create_path(self):
        self.runner.launch_probe(smoke.NATIVE, peek=True)
        self.assertEqual(self.launched(), [call(
            "am", "start", "-W", "-n", smoke.NATIVE + "/eu.darken.porter.probe.ProbeActivity",
            "--ez", "peek", "true", timeout=smoke.LAUNCH_TIMEOUT)])
        self.runner.expect_log.assert_any_call(smoke.NATIVE, "MODE daemon=false peek=true")


class DeviceStateReadingTest(unittest.TestCase):
    def setUp(self):
        self.runner = smoke.Smoke.__new__(smoke.Smoke)
        self.runner.shell = Mock()

    def test_only_secondary_users_count_as_extra(self):
        self.runner.shell.return_value = (
            "Users:\n\tUserInfo{0:Owner:c13} running\n\tUserInfo{10:porter-ci:410} running")
        self.assertEqual(self.runner.extra_users(), ["10"])

    def test_a_single_user_device_has_none(self):
        self.runner.shell.return_value = "Users:\n\tUserInfo{0:Owner:c13} running"
        self.assertEqual(self.runner.extra_users(), [])

    def test_a_prefix_match_is_not_an_installed_package(self):
        self.runner.shell.return_value = "package:eu.darken.porter.probe.native.extra"
        self.assertFalse(self.runner.installed(smoke.NATIVE))

    def test_an_exact_match_is_installed(self):
        self.runner.shell.return_value = (
            "package:eu.darken.porter.probe.native.extra\npackage:eu.darken.porter.probe.native")
        self.assertTrue(self.runner.installed(smoke.NATIVE))

    def test_service_pids_reads_every_process_under_the_nice_name(self):
        self.runner.shell.return_value = "5271 5272"
        self.assertEqual(self.runner.service_pids(smoke.NATIVE), {"5271", "5272"})

    def test_no_service_process_is_an_empty_set(self):
        self.runner.shell.return_value = ""
        self.assertEqual(self.runner.service_pids(smoke.NATIVE), set())


class ScenarioRestoreTest(unittest.TestCase):
    def setUp(self):
        self.runner = smoke.Smoke.__new__(smoke.Smoke)
        self.runner.results = []
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.runner.output = Path(directory.name)
        self.runner.adb = Mock(return_value="")
        self.runner.shell = Mock(return_value="")
        self.runner.pid = Mock(return_value="5271")
        self.runner.installed = Mock(return_value=False)
        self.runner.extra_users = Mock(return_value=["10"])
        self.runner.start_service = Mock()
        # cases=None explicitly: a bare Mock would hand case() a truthy attribute to filter on.
        self.runner.args = Mock(cases=None, manager=Path("/apks/manager.apk"),
                                native=Path("/apks/native.apk"), legacy=Path("/apks/legacy.apk"))

    def test_a_passing_scenario_restores_what_it_declared(self):
        self.runner.case("probe-case", lambda: {"ok": True}, restore=("probes",))
        self.assertEqual(self.runner.results[0]["passed"], True)
        self.assertIn(call("uninstall", smoke.NATIVE, check=False), self.runner.adb.call_args_list)
        self.assertIn(call("install", str(Path("/apks/native.apk").resolve())),
                      self.runner.adb.call_args_list)

    def test_a_failing_scenario_still_restores(self):
        def boom():
            raise AssertionError("Timed out: daemon removed after host uninstall")
        with self.assertRaisesRegex(AssertionError, "Timed out"):
            self.runner.case("probe-case", boom, restore=("users",))
        self.assertEqual(self.runner.results[0]["passed"], False)
        self.runner.shell.assert_any_call("pm", "remove-user", "10", check=False)

    def test_a_scenario_declaring_nothing_restores_nothing(self):
        self.runner.case("probe-case", lambda: None)
        self.runner.adb.assert_called_once_with("logcat", "-d", "-v", "threadtime", check=False)

    def test_a_restore_failure_fails_the_run(self):
        self.runner.extra_users = Mock(side_effect=RuntimeError("device offline"))
        with self.assertRaisesRegex(RuntimeError, "device offline"):
            self.runner.case("probe-case", lambda: {"ok": True}, restore=("users",))
        self.assertEqual(self.runner.results[0]["passed"], False)
        self.assertIn("device offline", self.runner.results[0]["restore_failure"])

    def test_a_restore_failure_does_not_mask_the_scenario(self):
        self.runner.extra_users = Mock(side_effect=RuntimeError("device offline"))

        def boom():
            raise AssertionError("Timed out: daemon removed after host uninstall")
        with self.assertRaisesRegex(AssertionError, "Timed out"):
            self.runner.case("probe-case", boom, restore=("users",))
        self.assertEqual(self.runner.results[0]["passed"], False)
        self.assertIn("device offline", self.runner.results[0]["restore_failure"])

    def test_a_running_service_is_not_restarted(self):
        self.runner.case("probe-case", lambda: None, restore=("service",))
        self.runner.start_service.assert_not_called()

    def test_a_stopped_service_is_restarted_after_the_manager_is_back(self):
        self.runner.pid = Mock(return_value="")
        self.runner.case("probe-case", lambda: None, restore=("manager", "service"))
        self.assertIn(call("install", str(Path("/apks/manager.apk").resolve())),
                      self.runner.adb.call_args_list)
        self.runner.start_service.assert_called_once()


class StartServiceBinaryTest(unittest.TestCase):
    """The starter binary is named per package: only Porter's APK ships libporter.so."""

    def setUp(self):
        self.runner = smoke.Smoke.__new__(smoke.Smoke)
        self.runner.pid = Mock(return_value="")
        self.runner.until = Mock(return_value="4242")
        self.runner.adb = Mock(return_value="starting server...")

    def started_binary(self, package, *args):
        apk = f"/data/app/~~abc==/{package}-def==/base.apk"
        self.runner.shell = Mock(side_effect=[f"package:{apk}", "x86_64", ""])
        self.runner.start_service(*args)
        return self.runner.shell.call_args_list[-1].args[0]

    def test_the_manager_is_started_from_libporter(self):
        self.assertEqual(self.started_binary(smoke.MANAGER),
                         "/data/app/~~abc==/eu.darken.porter-def==/lib/x86_64/libporter.so")

    def test_the_original_shizuku_is_started_from_its_own_binary(self):
        self.assertEqual(self.started_binary(smoke.COMPAT, smoke.COMPAT),
                         "/data/app/~~abc==/moe.shizuku.privileged.api-def==/lib/x86_64/libshizuku.so")


class StartServicePushTest(unittest.TestCase):
    """The natives line is not the end of startup: the binder push that follows starts client
    processes, and a caller that force-stops one of those mid-start loses its own launch."""

    APK = "/data/app/~~abc==/eu.darken.porter-def==/base.apk"
    OPEN = "Add 0:eu.darken.porter.probe.legacy to power save temp whitelist for 30s"
    SENT = "send binder to user app eu.darken.porter.probe.legacy in user 0"
    NULL = "provider is null eu.darken.porter.probe.legacy.shizuku 0"

    def setUp(self):
        self.runner = smoke.Smoke.__new__(smoke.Smoke)
        self.runner.pid = Mock(side_effect=["", "4242"])
        self.runner.shell = Mock(side_effect=[f"package:{self.APK}", "x86_64", ""])

    def test_an_open_push_is_named_and_a_closed_one_is_not(self):
        self.assertEqual(list(smoke.PushTracker().feed(self.OPEN)),
                         ["eu.darken.porter.probe.legacy"])
        self.assertFalse(smoke.PushTracker().feed(f"{self.OPEN}\n{self.SENT}"))
        # A provider that never answered closes the push just as delivery does.
        self.assertFalse(smoke.PushTracker().feed(f"{self.OPEN}\n{self.NULL}"))
        # Two pushes, one answer: still in flight.
        self.assertEqual(list(smoke.PushTracker().feed(f"{self.OPEN}\n{self.NULL}\n{self.OPEN}")),
                         ["eu.darken.porter.probe.legacy"])
        # A lone close leaves nothing open rather than a negative count.
        self.assertFalse(smoke.PushTracker().feed(self.SENT))
        # A package whose name merely prefixes another is not closed by it.
        sibling = self.SENT.replace("probe.legacy", "probe.legacy.extra")
        self.assertEqual(list(smoke.PushTracker().feed(f"{self.OPEN}\n{sibling}")),
                         ["eu.darken.porter.probe.legacy"])

    def test_an_open_push_survives_its_line_rotating_out_of_the_buffer(self):
        tracker = smoke.PushTracker()
        tracker.feed(f"starting server...\n{self.OPEN}")
        # The blocked push logs nothing while other processes push its opening line out.
        for _ in range(smoke.SERVER_QUIET_POLLS + 2):
            self.assertEqual(list(tracker.feed("")), ["eu.darken.porter.probe.legacy"])
        # Only an observed close ends it, however late it arrives.
        self.assertFalse(tracker.feed(self.SENT))

    def test_lines_carried_over_between_reads_are_not_counted_twice(self):
        tracker = smoke.PushTracker()
        tracker.feed(f"starting server...\n{self.OPEN}")
        # The same open is still in the buffer; re-reading it must not open a second push.
        tracker.feed(f"starting server...\n{self.OPEN}")
        self.assertEqual(dict(tracker.feed(f"starting server...\n{self.OPEN}\n{self.SENT}")), {})

    def test_a_read_that_lost_its_oldest_lines_still_tracks_the_newest(self):
        tracker = smoke.PushTracker()
        tracker.feed(f"starting server...\n{self.OPEN}\n{self.SENT}")
        # The buffer has dropped everything before the newest open.
        self.assertEqual(list(tracker.feed(f"{self.SENT}\n{self.OPEN}")),
                         ["eu.darken.porter.probe.legacy"])

    @patch.object(smoke.time, "sleep")
    def test_the_manager_waits_for_its_server_to_say_it_sent_them(self, sleep):
        self.runner.adb = Mock(side_effect=["starting server...", "starting server...\nsending binders",
                                            "starting server...\nsending binders\nsent binders"])
        self.runner.start_service()
        self.assertEqual(self.runner.adb.call_count, 3)

    @patch.object(smoke.time, "sleep")
    def test_a_silent_gap_inside_an_open_push_is_not_read_as_finished(self, sleep):
        # The provider call blocks without logging, so the log repeats while the push is still on.
        blocked = f"starting server...\n{self.OPEN}"
        done = f"{blocked}\n{self.SENT}"
        self.runner.adb = Mock(side_effect=(
            ["starting server..."] + [blocked] * (smoke.SERVER_QUIET_POLLS + 3)
            + [done] * smoke.SERVER_QUIET_POLLS))
        self.runner.start_service(smoke.COMPAT)
        self.assertEqual(self.runner.adb.call_count,
                         1 + smoke.SERVER_QUIET_POLLS + 3 + smoke.SERVER_QUIET_POLLS)

    @patch.object(smoke.time, "sleep")
    @patch.object(smoke.time, "monotonic", side_effect=itertools.count(0, 5))
    def test_a_push_that_never_closes_fails_the_run(self, monotonic, sleep):
        self.runner.adb = Mock(return_value=f"starting server...\n{self.OPEN}")
        with self.assertRaisesRegex(AssertionError, "finished starting client processes"):
            self.runner.start_service(smoke.COMPAT)
        self.assertGreater(self.runner.adb.call_count, smoke.SERVER_QUIET_POLLS + 1)


class LaunchProbeRetryTest(unittest.TestCase):
    """A launch that does not land raced a process start already in flight for the package."""

    def setUp(self):
        self.runner = smoke.Smoke.__new__(smoke.Smoke)
        self.runner.shell = Mock(return_value="")
        self.runner.pid = Mock(return_value="4711")
        self.runner.expect_log = Mock()

    def starts(self):
        return [c for c in self.runner.shell.call_args_list if c.args[0] == "am" and c.args[1] == "start"]

    def stops(self):
        return [c for c in self.runner.shell.call_args_list if c.args[:2] == ("am", "force-stop")]

    def test_a_landed_launch_is_not_retried(self):
        self.runner.launch_probe(smoke.NATIVE)
        self.assertEqual(len(self.starts()), 1)
        self.assertEqual(self.runner.expect_log.call_count, 2)

    def test_a_hung_launch_is_force_stopped_again_before_the_retry(self):
        hung = smoke.subprocess.TimeoutExpired("am start", smoke.LAUNCH_TIMEOUT)
        self.runner.shell = Mock(side_effect=[""] * 2 + [hung] + [""] * 3)
        self.runner.launch_probe(smoke.NATIVE)
        self.assertEqual(len(self.starts()), 2)
        # Both probes are stopped before each attempt, so the retry is the only start outstanding.
        self.assertEqual(len(self.stops()), 4)

    def test_a_launch_that_never_logs_its_mode_is_retried_then_surfaces(self):
        self.runner.expect_log = Mock(side_effect=AssertionError("Timed out: MODE"))
        with self.assertRaisesRegex(AssertionError, "MODE"):
            self.runner.launch_probe(smoke.NATIVE)
        self.assertEqual(len(self.starts()), smoke.LAUNCH_ATTEMPTS)

    def test_a_second_probe_process_is_waited_out_rather_than_followed(self):
        self.runner.pid = Mock(side_effect=["4711 4712", "4711 4712", "4713"])
        with patch.object(smoke.time, "sleep"):
            self.runner.launch_probe(smoke.NATIVE)
        self.assertEqual(self.runner.probe_pid, "4713")

    def test_a_missing_binder_is_not_retried_away(self):
        # The activity ran, so relaunching would only hide a real hand-over failure.
        self.runner.expect_log = Mock(side_effect=[None, AssertionError("Timed out: BINDER")])
        with self.assertRaisesRegex(AssertionError, "BINDER"):
            self.runner.launch_probe(smoke.NATIVE)
        self.assertEqual(len(self.starts()), 1)


class SpawnedProcessReadingTest(unittest.TestCase):
    """What the service spawned for a debug recording, as the case finds it and loses it."""
    SERVER = "3120"
    LISTING = "\n".join((
        "PID PPID NAME",
        "3120 1 porter_server",
        # The supervisor and the logcat it started.
        "4180 3120 sh",
        "4181 4180 logcat",
        # A user service is a child of the service too, and is not what newProcess spawned.
        "4190 3120 native:porter-probe",
        # A user service being started: a shell of the service's own, with nothing under it.
        "4195 3120 sh",
        # Someone else's shell, under a parent this case knows nothing about.
        "4200 2900 sh",
    ))

    def setUp(self):
        self.runner = smoke.Smoke.__new__(smoke.Smoke)
        self.runner.shell = Mock(return_value=self.LISTING)

    def test_the_supervisor_is_the_shell_with_the_logcat_under_it(self):
        self.assertEqual(self.runner.spawned(self.SERVER), {"4180": ["logcat"], "4195": []})

    def test_a_destroyed_supervisor_takes_its_children_out_of_the_answer(self):
        self.runner.shell.return_value = "PID PPID NAME\n3120 1 porter_server\n4181 1 logcat"
        self.assertEqual(self.runner.spawned(self.SERVER), {})

    def test_a_header_or_a_wrapped_argument_line_is_not_a_process(self):
        self.runner.shell.return_value = "PID PPID NAME\nwhile kill -0 $c\n4180 3120 sh"
        self.assertEqual(self.runner.spawned(self.SERVER), {"4180": []})

    def test_the_remote_logcat_is_found_by_what_it_follows(self):
        self.runner.shell.return_value = "\n".join((
            "PID ARGS",
            "4181 logcat -v threadtime --pid=3120 -T 1",
            # The supervisor names the same pid and is not a logcat. Its script reaches ps as
            # several lines, one of which starts with the command it backgrounds.
            "4180 sh -c pending=0",
            "logcat -v threadtime --pid=3120 -T 1 &",
            # Another logcat, following something else.
            "4300 logcat -v threadtime --pid=9999 -T 1",
        ))
        self.assertEqual(self.runner.remote_logcat(self.SERVER), ["4181"])

    def test_a_reaped_logcat_leaves_nothing_to_find(self):
        self.runner.shell.return_value = "PID ARGS\n4300 logcat -v threadtime --pid=9999 -T 1"
        self.assertEqual(self.runner.remote_logcat(self.SERVER), [])

    def test_a_longer_pid_starting_with_this_one_is_a_different_service(self):
        self.runner.shell.return_value = "PID ARGS\n4181 logcat -v threadtime --pid=31200 -T 1"
        self.assertEqual(self.runner.remote_logcat("3120"), [])


class DecisionDatabaseTest(unittest.TestCase):
    """What secondary-user-prompt reads to tell "no answer was recorded" from "denied"."""

    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.runner = smoke.Smoke(argparse.Namespace(
            serial="emulator-5554", output=Path(directory.name)))

    def flags(self, raw, uid=1010217):
        with patch.object(self.runner, "shell", return_value=raw) as shell:
            result = self.runner.decision_flags(uid)
        shell.assert_called_once_with("cat", smoke.DECISIONS, check=False)
        return result

    def test_a_device_that_answered_nothing_has_no_database(self):
        # adb prints the error on stdout for a missing file, so this is what the helper sees.
        self.assertEqual(self.flags("cat: " + smoke.DECISIONS + ": No such file or directory"), 0)

    def test_a_uid_the_database_never_heard_of_reads_as_nothing_recorded(self):
        saved = json.dumps({"version": 2, "packages": [{"uid": 10217, "flags": 2}]})
        self.assertEqual(self.flags(saved), 0)

    def test_an_allowed_uid_reads_back_its_flag(self):
        saved = json.dumps({"version": 2, "packages": [{"uid": 1010217, "flags": smoke.DECISION_ALLOWED}]})
        self.assertEqual(self.flags(saved) & smoke.DECISION_ALLOWED, smoke.DECISION_ALLOWED)

    def test_a_denied_uid_is_not_mistaken_for_an_unanswered_one(self):
        saved = json.dumps({"version": 2, "packages": [{"uid": 1010217, "flags": smoke.DECISION_DENIED}]})
        self.assertTrue(self.flags(saved) & (smoke.DECISION_ALLOWED | smoke.DECISION_DENIED))

    def test_a_database_with_a_null_package_list_reads_as_nothing_recorded(self):
        self.assertEqual(self.flags(json.dumps({"version": 2, "packages": None})), 0)


class AppUidTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.runner = smoke.Smoke(argparse.Namespace(
            serial="emulator-5554", output=Path(directory.name)))

    def test_the_uid_is_read_for_the_user_that_was_asked_about(self):
        listing = "package:eu.darken.porter.probe.native uid:1010217"
        with patch.object(self.runner, "shell", return_value=listing) as shell:
            self.assertEqual(self.runner.app_uid("eu.darken.porter.probe.native", "10"), 1010217)
        shell.assert_called_once_with(
            "pm", "list", "packages", "--user", "10", "-U", "eu.darken.porter.probe.native")

    def test_the_owner_user_is_the_default(self):
        with patch.object(self.runner, "shell", return_value="package:p uid:10217") as shell:
            self.assertEqual(self.runner.app_uid("p"), 10217)
        self.assertEqual(shell.call_args.args[4], "0")
