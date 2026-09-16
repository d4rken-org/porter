import argparse
import contextlib
import importlib.util
import io
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
                 "-n", "eu.darken.porter/moe.shizuku.manager.MainActivity"),
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
            "am", "start", "-W", "-n", smoke.NATIVE + "/eu.darken.porter.probe.ProbeActivity")])
        self.runner.expect_log.assert_any_call(smoke.NATIVE, "MODE daemon=false peek=false")

    def test_a_daemon_launch_passes_the_extra_and_asserts_the_mode_that_ran(self):
        self.runner.launch_probe(smoke.NATIVE, daemon=True)
        self.assertEqual(self.launched(), [call(
            "am", "start", "-W", "-n", smoke.NATIVE + "/eu.darken.porter.probe.ProbeActivity",
            "--ez", "daemon", "true")])
        self.runner.expect_log.assert_any_call(smoke.NATIVE, "MODE daemon=true peek=false")

    def test_a_peek_launch_exercises_the_no_create_path(self):
        self.runner.launch_probe(smoke.NATIVE, peek=True)
        self.assertEqual(self.launched(), [call(
            "am", "start", "-W", "-n", smoke.NATIVE + "/eu.darken.porter.probe.ProbeActivity",
            "--ez", "peek", "true")])
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
