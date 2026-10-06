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

# One entry of the crash buffer, which is where the package behind a crash dialog is named.
CRASH = "E AndroidRuntime: FATAL EXCEPTION: main\nE AndroidRuntime: Process: %s, PID: 4242\n"

OFFLINE = b"adb: device offline\n"
NOT_FOUND = b"adb: device 'emulator-5554' not found\n"


def completed(returncode, stdout=b"", stderr=b""):
    return smoke.subprocess.CompletedProcess(["adb"], returncode, stdout, stderr)


class AdbTimeoutTest(unittest.TestCase):
    """An install compiles on the device and gets longer than any other command."""

    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.runner = smoke.Smoke(argparse.Namespace(serial="emulator-5554", output=Path(directory.name)))

    def timeout_of(self, *args, **kwargs):
        with patch.object(smoke.subprocess, "run", return_value=completed(0)) as run:
            self.runner.adb(*args, **kwargs)
        return run.call_args.kwargs["timeout"]

    def test_installs_get_the_install_budget(self):
        self.assertEqual(self.timeout_of("install", "-r", "/tmp/a.apk"), smoke.INSTALL_TIMEOUT)

    def test_other_commands_keep_the_adb_budget(self):
        self.assertEqual(self.timeout_of("shell", "pm list packages"), smoke.ADB_TIMEOUT)

    def test_an_explicit_timeout_still_wins(self):
        self.assertEqual(self.timeout_of("install", "/tmp/a.apk", timeout=7), 7)

    def hang_then(self, *answers):
        hung = smoke.subprocess.TimeoutExpired(["adb"], 180)
        return patch.object(smoke.subprocess, "run", side_effect=[hung, *answers])

    def test_a_hung_install_is_tried_once_more_without_streaming(self):
        # The evidence reads come between the two attempts.
        with self.hang_then(completed(0), completed(0), completed(0), completed(0, stdout=b"Success")) as run:
            self.assertEqual(self.runner.adb("install", "/tmp/a.apk"), "Success")
        retry = run.call_args_list[-1].args[0]
        self.assertEqual(retry[3:], ["install", "--no-streaming", "-r", "/tmp/a.apk"])
        self.assertTrue((self.runner.output / "install-hang-1.txt").exists())

    def test_a_reinstall_is_not_given_a_second_r(self):
        with self.hang_then(completed(0), completed(0), completed(0), completed(0)) as run:
            self.runner.adb("install", "-r", "/tmp/a.apk")
        self.assertEqual(run.call_args_list[-1].args[0][3:], ["install", "--no-streaming", "-r", "/tmp/a.apk"])

    def test_the_retry_is_not_retried(self):
        hung = smoke.subprocess.TimeoutExpired(["adb"], 180)
        with patch.object(smoke.subprocess, "run", side_effect=[hung, completed(0), completed(0), completed(0), hung]):
            with self.assertRaises(smoke.subprocess.TimeoutExpired):
                self.runner.adb("install", "/tmp/a.apk")

    def test_other_commands_that_hang_still_fail(self):
        with patch.object(smoke.subprocess, "run", side_effect=smoke.subprocess.TimeoutExpired(["adb"], 45)):
            with self.assertRaises(smoke.subprocess.TimeoutExpired):
                self.runner.adb("shell", "pm list packages")


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
    @patch.object(smoke.time, "monotonic", side_effect=[0, 5, 11, 17, 23])
    def test_dumps_are_retried_past_three_attempts_while_the_budget_lasts(self, monotonic, sleep):
        # Each failed attempt starts its own VM before reporting, so a count of three is spent in
        # about twelve seconds. The fifth attempt here is still inside the thirty second budget.
        with patch.object(smoke.subprocess, "run", side_effect=[
                completed(0), self.null_root, completed(0), self.null_root,
                completed(0), self.null_root, completed(0), self.null_root,
                completed(0), self.success, completed(0, stdout=self.xml.encode())]):
            self.assertEqual(self.runner.ui().tag, "hierarchy")
        self.assertEqual(sleep.call_count, 4)

    @patch.object(smoke.time, "sleep")
    @patch.object(smoke.time, "monotonic", side_effect=[0, 10, 20, 31])
    def test_failed_dump_cannot_reuse_a_previous_hierarchy(self, monotonic, sleep):
        remote = {self.path: '<hierarchy><node text="Stale" /></hierarchy>'}

        def shell(*args, **kwargs):
            if args == ("rm", "-f", self.path):
                remote.pop(self.path, None)
                return ""
            if args == ("uiautomator", "dump", self.path):
                return ""
            self.fail(f"Unexpected command: {args}")

        self.runner.shell = Mock(side_effect=shell)
        with self.assertRaisesRegex(RuntimeError, "in 3 attempts over 31s.*commands.log"):
            self.runner.ui()
        self.assertNotIn(self.path, remote)
        self.assertFalse((self.runner.output / "last-ui.xml").exists())
        self.assertEqual(sleep.call_count, 3)
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


class SettledScreenTest(unittest.TestCase):
    def setUp(self):
        self.runner = smoke.Smoke.__new__(smoke.Smoke)
        # The user-switching overlay, as it dumps: the framework's own package and nothing else.
        self.switching = ET.fromstring('''<hierarchy><node package="android">
            <node resource-id="android:id/progress_circular" package="android" enabled="true"
            bounds="[238,522][841,1125]" />
            <node text="Switching to Owner…" resource-id="android:id/message" package="android"
            enabled="true" bounds="[296,1141][782,1212]" /></node></hierarchy>''')
        self.prompt = ET.fromstring('''<hierarchy><node package="eu.darken.porter">
            <node text="Allow all the time" package="eu.darken.porter" enabled="true"
            bounds="[556,1176][897,1324]" /></node></hierarchy>''')
        self.home = ET.fromstring('''<hierarchy><node package="com.google.android.apps.nexuslauncher">
            <node text="Phone" package="com.google.android.apps.nexuslauncher" enabled="true"
            bounds="[0,0][100,100]" /></node></hierarchy>''')

    def test_the_switching_overlay_is_not_a_settled_screen(self):
        self.runner.ui = Mock(return_value=self.switching)
        self.assertEqual(self.runner.settled_screen(), [])

    def test_a_dump_that_never_arrived_is_not_a_settled_screen(self):
        self.runner.ui = Mock(side_effect=RuntimeError("uiautomator produced no UI dump"))
        self.assertEqual(self.runner.settled_screen(), [])

    def test_an_app_window_is_a_settled_screen(self):
        self.runner.ui = Mock(return_value=self.home)
        self.assertEqual([n.get("package") for n in self.runner.settled_screen()],
                         ["com.google.android.apps.nexuslauncher"] * 2)

    @patch.object(smoke.time, "sleep")
    def test_the_overlay_cannot_answer_for_the_prompt_behind_it(self, sleep):
        # The overlay carries no prompt of its own, so reading it once answers "absent" for a
        # screen never looked at.
        self.runner.ui = Mock(side_effect=[self.switching, self.prompt])
        self.assertFalse(self.runner.absent("Allow all the time"))

    @patch.object(smoke.time, "sleep")
    def test_absence_is_confirmed_on_consecutive_settled_reads(self, sleep):
        self.runner.ui = Mock(side_effect=[self.home, self.home])
        self.assertTrue(self.runner.absent("Allow all the time"))
        self.assertEqual(self.runner.ui.call_count, 2)

    @patch.object(smoke.time, "sleep")
    def test_a_prompt_arriving_on_the_second_read_is_not_absent(self, sleep):
        self.runner.ui = Mock(side_effect=[self.home, self.prompt])
        self.assertFalse(self.runner.absent("Allow all the time"))

    @patch.object(smoke.time, "sleep")
    def test_a_transition_between_settled_reads_starts_the_run_over(self, sleep):
        # Two settled reads either side of the overlay are not consecutive: the screen moved in
        # between, and what moved onto it was the prompt.
        self.runner.ui = Mock(side_effect=[self.home, self.switching, self.home, self.prompt])
        self.assertFalse(self.runner.absent("Allow all the time"))

    @patch.object(smoke.time, "sleep")
    @patch.object(smoke.time, "monotonic", side_effect=[0, 10, 50, 91])
    def test_a_screen_that_never_settles_times_out(self, monotonic, sleep):
        self.runner.ui = Mock(return_value=self.switching)
        with self.assertRaisesRegex(AssertionError, "Timed out.*'Allow all the time'"):
            self.runner.absent("Allow all the time")
        self.assertEqual(self.runner.ui.call_count, 2)

    @patch.object(smoke.time, "sleep")
    @patch.object(smoke.time, "monotonic", side_effect=[0, 5, 95])
    def test_a_settled_read_does_not_get_a_pass_on_the_deadline(self, monotonic, sleep):
        # A read slow enough to outlast the budget must not be followed by another: settling is
        # what the count is about, not what excuses it from the clock.
        self.runner.ui = Mock(side_effect=[self.home, self.home])
        with self.assertRaisesRegex(AssertionError, "Timed out"):
            self.runner.absent("Allow all the time")
        self.assertEqual(self.runner.ui.call_count, 1)


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
        # What a tap on it leads to, which is how tap() tells a gesture that landed from one the
        # platform dropped on its way to the window.
        self.after = ET.fromstring('''<hierarchy><node package="eu.darken.porter">
            <node text="Installing" package="eu.darken.porter" enabled="true" /></node></hierarchy>''')

    @patch.object(smoke.time, "sleep")
    def test_retry_scrolls_before_tapping_action_below_saved_import(self, sleep):
        self.runner.ui = Mock(side_effect=[self.hidden, self.visible, self.after])
        self.runner.tap("Install automatically", scroll=True)
        self.assertEqual(self.runner.shell.call_args_list, [
            call("input", "swipe", 540, 1482, 540, 543, 300),
            call("input", "tap", 775, 1150),
        ])

    def test_visible_action_does_not_scroll(self):
        self.runner.ui = Mock(side_effect=[self.visible, self.after])
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
        self.opened = ET.fromstring('''<hierarchy><node package="eu.darken.porter">
            <node text="Help &amp; support" package="eu.darken.porter" enabled="true" />
            </node></hierarchy>''')

    def test_taps_the_node_carrying_the_content_description(self):
        self.runner.ui = Mock(side_effect=[self.screen, self.opened])
        self.runner.tap(desc="Settings")
        self.runner.shell.assert_called_once_with("input", "tap", 1005, 178)

    def test_text_matching_ignores_content_descriptions(self):
        self.runner.ui = Mock(side_effect=[self.screen, self.opened])
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
        # One read that finds Home rather than onboarding in front, then two per tap: the one
        # that locates the button and the one that confirms the screen moved on from it.
        self.runner.ui = Mock(side_effect=[self.home, self.home, self.service,
                                           self.service, self.dialog,
                                           self.dialog, self.service])
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


class OpenHomeTest(unittest.TestCase):
    """Home is only reachable through onboarding until that has been completed once."""

    START = call("am", "start", "-W", "-f", "0x04000000",
                 "-n", "eu.darken.porter/eu.darken.porter.manager.MainActivity")
    ACTIVITIES = call("dumpsys", "activity", "activities", check=False)
    MAIN = "ActivityRecord{c1d2e3 u0 eu.darken.porter/.manager.MainActivity t5}"
    ONBOARDING = "ActivityRecord{a4b5c6 u0 eu.darken.porter/.manager.onboarding.OnboardingActivity t5}"

    def setUp(self):
        self.runner = smoke.Smoke.__new__(smoke.Smoke)
        self.runner.shell = Mock(side_effect=self.shell)
        self.runner.tap = Mock()
        self.dumps = [self.activities(self.MAIN, resumed=self.MAIN)]
        patcher = patch.object(smoke.time, "sleep")
        patcher.start()
        self.addCleanup(patcher.stop)

    def shell(self, *args, **kwargs):
        if args == ("dumpsys", "activity", "activities"):
            return self.dumps.pop(0) if len(self.dumps) > 1 else self.dumps[0]
        return ""

    def activities(self, *records, resumed):
        history = "".join(f"    * Hist  #{len(records) - 1 - index}: {record}\n"
                          for index, record in enumerate(records))
        return ("ACTIVITY MANAGER ACTIVITIES (dumpsys activity activities)\n"
                "Display #0 (activities from top to bottom):\n"
                "  * Task{8a3c1e5 #5 type=standard A=10123:eu.darken.porter}\n"
                f"{history}"
                f"  topResumedActivity={resumed}\n")

    def screen(self, *texts, package="eu.darken.porter"):
        nodes = "".join(f'<node text="{text}" package="{package}" enabled="true" '
                        f'bounds="[0,{100 * index}][100,{100 * index + 50}]" />'
                        for index, text in enumerate(texts))
        return ET.fromstring(f'<hierarchy><node package="{package}">{nodes}</node></hierarchy>')

    def home(self):
        return self.screen("Porter is running")

    def welcome(self):
        return self.screen("Welcome to Porter", "Continue")

    def shizuku(self):
        return self.screen("Shizuku is installed", "Compatibility guide", "Continue")

    def privacy(self):
        return self.screen("Privacy", "Privacy policy", "Get started")

    def test_home_in_front_is_only_started(self):
        self.runner.ui = Mock(side_effect=[self.home()])
        self.runner.open_home()
        self.assertEqual(self.runner.shell.call_args_list, [self.START])
        self.runner.tap.assert_not_called()

    def test_onboarding_without_the_shizuku_page_continues_then_gets_started(self):
        self.runner.ui = Mock(side_effect=[self.welcome(), self.privacy(), self.home()])
        self.runner.open_home()
        self.assertEqual(self.runner.shell.call_args_list, [self.START, self.ACTIVITIES])
        self.assertEqual(self.runner.tap.call_args_list, [call("Continue"), call("Get started")])

    def test_onboarding_with_the_shizuku_page_continues_twice(self):
        self.runner.ui = Mock(side_effect=[self.welcome(), self.shizuku(), self.privacy(), self.home()])
        self.runner.open_home()
        self.assertEqual(self.runner.tap.call_args_list,
                         [call("Continue"), call("Continue"), call("Get started")])

    def test_a_dpad_activation_replaces_the_taps(self):
        self.runner.onboarding_activate = Mock()
        self.runner.ui = Mock(side_effect=[self.welcome(), self.privacy(), self.home()])
        self.runner.open_home()
        self.assertEqual(self.runner.onboarding_activate.call_args_list,
                         [call("Continue"), call("Get started")])
        self.runner.tap.assert_not_called()

    def test_a_window_still_in_front_of_the_start_is_not_read_as_no_onboarding(self):
        launcher = self.screen("Phone", package="com.google.android.apps.nexuslauncher")
        self.runner.ui = Mock(side_effect=[launcher, self.welcome(), self.privacy(), self.home()])
        self.runner.open_home()
        self.assertEqual(self.runner.tap.call_args_list, [call("Continue"), call("Get started")])

    def test_a_page_in_transition_is_not_the_next_page(self):
        # Both titles are on screen while the pages swap, and Continue pressed then would land on
        # the page still leaving.
        both = self.screen("Welcome to Porter", "Privacy", "Get started")
        self.runner.ui = Mock(side_effect=[self.welcome(), both, self.privacy(), self.home()])
        self.runner.open_home()
        self.assertEqual(self.runner.tap.call_args_list, [call("Continue"), call("Get started")])
        self.assertEqual(self.runner.ui.call_count, 4)

    def test_it_returns_only_once_onboarding_has_gone(self):
        finishing = self.screen("Privacy", "Privacy policy")
        self.runner.ui = Mock(side_effect=[self.welcome(), self.privacy(), finishing, self.home()])
        self.runner.open_home()
        self.assertEqual(self.runner.ui.call_count, 4)

    def test_no_ui_dump_runs_until_onboarding_has_been_destroyed(self):
        events = []
        self.dumps = [self.activities(self.ONBOARDING, self.MAIN, resumed=self.ONBOARDING),
                      self.activities(self.MAIN, self.ONBOARDING, resumed=self.MAIN),
                      self.activities(self.MAIN, resumed=self.MAIN)]
        screens = [self.welcome(), self.privacy(), self.home()]

        def shell(*args, **kwargs):
            events.append(args[0])
            return self.shell(*args, **kwargs)

        def ui(*args):
            events.append("ui")
            return screens.pop(0)

        self.runner.shell = Mock(side_effect=shell)
        self.runner.ui = Mock(side_effect=ui)
        self.runner.tap = Mock(side_effect=lambda text: events.append(text))
        self.runner.open_home()
        self.assertEqual(events, ["am", "ui", "Continue", "ui", "Get started",
                                  "dumpsys", "dumpsys", "dumpsys", "ui"])


class TapConfirmationTest(unittest.TestCase):
    """The platform can drop an injected gesture, so a tap is only done once the screen says so."""

    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.runner = smoke.Smoke(argparse.Namespace(
            serial="emulator-5554", output=Path(directory.name)))
        self.runner.shell = Mock()
        self.runner.screenshot = Mock()
        self.prompt = ET.fromstring('''<hierarchy><node package="eu.darken.porter">
            <node text="Allow all the time" package="eu.darken.porter" enabled="true"
            bounds="[556,1144][897,1292]" /></node></hierarchy>''')
        self.granted = ET.fromstring('''<hierarchy><node package="eu.darken.porter.probe.native">
            <node text="AUTHORIZED" package="eu.darken.porter.probe.native" enabled="true" />
            </node></hierarchy>''')
        # settle() is spent one read at a time, so zero buys exactly one look per attempt.
        patcher = patch.object(smoke, "TAP_SETTLE", 0)
        patcher.start()
        self.addCleanup(patcher.stop)
        # The evidence an unanswered tap leaves, which is read past shell() in one command.
        patcher = patch.object(smoke.subprocess, "run", return_value=completed(
            0, stdout=b"=== dumpsys input at 09-26 15:18:49\nINPUT MANAGER (dumpsys input)\n"))
        self.evidence_run = patcher.start()
        self.addCleanup(patcher.stop)

    def taps(self):
        return [c for c in self.runner.shell.call_args_list if c.args[0] == "input"]

    def test_a_dropped_gesture_is_tapped_again(self):
        # The same screen twice is the gesture never having reached the window it was aimed at.
        self.runner.ui = Mock(side_effect=[self.prompt, self.prompt, self.prompt, self.granted])
        self.runner.tap("Allow all the time")
        self.assertEqual(self.taps(), [call("input", "tap", 726, 1218)] * 2)

    def test_a_gesture_the_screen_answers_is_not_repeated(self):
        self.runner.ui = Mock(side_effect=[self.prompt, self.granted])
        self.runner.tap("Allow all the time")
        self.assertEqual(self.taps(), [call("input", "tap", 726, 1218)])

    def moved(self):
        """The prompt pushed 75 px down, so that the centre tapped before the push misses it by a pixel."""
        return ET.fromstring(ET.tostring(self.prompt).replace(b"[556,1144][897,1292]", b"[556,1219][897,1367]"))

    def test_a_screen_that_only_moved_is_tapped_again_where_the_button_is_now(self):
        self.runner.ui = Mock(side_effect=[self.prompt, self.moved(), self.moved(), self.granted])
        self.runner.tap("Allow all the time")
        self.assertEqual(self.taps(), [call("input", "tap", 726, 1218), call("input", "tap", 726, 1293)])
        self.assertIn("at 726,1218: screen moved", self.evidence(1))

    def test_a_screen_that_moved_and_then_answered_is_not_tapped_again(self):
        with patch.object(smoke, "TAP_SETTLE", 60), \
             patch.object(smoke.time, "monotonic", return_value=0), \
             patch.object(smoke.time, "sleep"):
            self.runner.ui = Mock(side_effect=[self.prompt, self.moved(), self.granted])
            self.runner.tap("Allow all the time")
        self.assertEqual(self.taps(), [call("input", "tap", 726, 1218)])
        self.assertEqual(self.runner.ui.call_count, 3)
        self.evidence_run.assert_not_called()

    def test_a_moved_screen_that_also_changed_answers_the_tap(self):
        changed = self.moved()
        changed.find("node/node").set("enabled", "false")
        self.runner.ui = Mock(side_effect=[self.prompt, changed])
        self.runner.tap("Allow all the time")
        self.assertEqual(self.taps(), [call("input", "tap", 726, 1218)])

    def test_a_button_gone_by_the_retry_counts_as_tapped(self):
        # The screen answered too slowly to be seen, not not at all: tapping where the button was
        # would land on whatever replaced it.
        self.runner.ui = Mock(side_effect=[self.prompt, self.prompt, self.granted])
        self.runner.tap("Allow all the time")
        self.assertEqual(self.taps(), [call("input", "tap", 726, 1218)])

    def test_a_screen_that_never_answers_fails_naming_the_button(self):
        self.runner.ui = Mock(return_value=self.prompt)
        with self.assertRaisesRegex(AssertionError, "'Allow all the time'"):
            self.runner.tap("Allow all the time")
        self.assertEqual(self.taps(), [call("input", "tap", 726, 1218)] * smoke.TAP_ATTEMPTS)

    def test_an_unreadable_screen_decides_nothing_either_way(self):
        # A dump that failed says nothing about the tap, so the look that decides is the next one,
        # taken on its own full budget by the retry rather than inside the settle window.
        self.runner.ui = Mock(side_effect=[
            self.prompt, RuntimeError("no UI dump"), self.prompt, self.granted])
        self.runner.tap("Allow all the time")
        self.assertEqual(self.taps(), [call("input", "tap", 726, 1218)] * 2)

    def test_an_unreadable_screen_over_a_button_that_went_away_is_not_tapped_again(self):
        self.runner.ui = Mock(side_effect=[self.prompt, RuntimeError("no UI dump"), self.granted])
        self.runner.tap("Allow all the time")
        self.assertEqual(self.taps(), [call("input", "tap", 726, 1218)])

    def evidence(self, number):
        return (self.runner.output / f"unanswered-tap-{number}.txt").read_text()

    def test_every_unanswered_tap_leaves_the_input_and_window_state(self):
        self.runner.ui = Mock(return_value=self.prompt)
        with self.assertRaisesRegex(AssertionError, "unanswered-tap-3.txt"):
            self.runner.tap("Allow all the time")
        first = self.evidence(1)
        self.assertIn("button 'Allow all the time' in eu.darken.porter at 726,1218: screen unchanged", first)
        self.assertIn('bounds="[556,1144][897,1292]"', first)
        self.assertIn("INPUT MANAGER (dumpsys input)", first)
        self.assertIn("=== evidence query: adb exit status 0", first)
        self.assertEqual(self.evidence_run.call_count, smoke.TAP_ATTEMPTS)
        script = self.evidence_run.call_args.args[0][-1]
        for query in ("dumpsys input", "dumpsys window windows", "dumpsys activity activities",
                      "dumpsys SurfaceFlinger"):
            self.assertIn(f'{query} 2>&1; echo "=== {query} exit status $?"', script)

    def test_the_evidence_is_one_attempt_on_its_own_budget(self):
        self.runner.ui = Mock(side_effect=[self.prompt, self.prompt, self.prompt, self.granted])
        self.runner.tap("Allow all the time")
        self.evidence_run.assert_called_once()
        self.assertEqual(self.evidence_run.call_args.kwargs["timeout"], smoke.TAP_EVIDENCE_TIMEOUT)

    def test_a_screen_no_dump_could_read_is_told_apart_from_an_unchanged_one(self):
        self.runner.ui = Mock(side_effect=[
            self.prompt, RuntimeError("no UI dump"), self.prompt, self.granted])
        self.runner.tap("Allow all the time")
        self.assertIn("screen unreadable", self.evidence(1))

    def test_evidence_that_runs_out_of_time_keeps_what_it_read_and_the_tap_failure(self):
        self.evidence_run.side_effect = smoke.subprocess.TimeoutExpired(
            ["adb"], smoke.TAP_EVIDENCE_TIMEOUT, output=b"=== dumpsys input at 09-26 15:18:49\npartial")
        self.runner.ui = Mock(return_value=self.prompt)
        with self.assertRaisesRegex(AssertionError, "'Allow all the time' in eu.darken.porter 3 times"):
            self.runner.tap("Allow all the time")
        first = self.evidence(1)
        self.assertIn("partial", first)
        self.assertIn(f"=== evidence query: timed out after {smoke.TAP_EVIDENCE_TIMEOUT}s", first)

    def test_a_failed_evidence_query_says_so(self):
        # Nothing on stdout is otherwise indistinguishable from a device with nothing to say.
        self.evidence_run.return_value = completed(1, stderr=b"adb: device offline\n")
        self.runner.ui = Mock(side_effect=[self.prompt, self.prompt, self.prompt, self.granted])
        self.runner.tap("Allow all the time")
        self.assertIn("=== evidence query: adb exit status 1\nadb: device offline", self.evidence(1))

    def test_the_screenshot_records_what_was_tapped_once(self):
        self.runner.ui = Mock(side_effect=[self.prompt, self.prompt, self.prompt, self.granted])
        self.runner.tap("Allow all the time", screenshot="native-permission")
        self.runner.screenshot.assert_called_once_with("native-permission")


class FrameworkErrorDialogTest(unittest.TestCase):
    """A crashed system app's dialog is about neither Porter nor the probe, and covers both."""

    def setUp(self):
        self.runner = smoke.Smoke.__new__(smoke.Smoke)
        self.runner.shell = Mock()
        self.runner.adb = Mock(return_value=CRASH % "com.android.bluetooth")
        self.crash = ET.fromstring('''<hierarchy><node package="android">
            <node text="Bluetooth keeps stopping" package="android" enabled="true"
            bounds="[133,760][947,831]" />
            <node text="App info" package="android" enabled="true" bounds="[70,870][1010,996]" />
            <node text="Close app" package="android" enabled="true"
            bounds="[70,996][1010,1122]" /></node></hierarchy>''')
        self.switching = ET.fromstring('''<hierarchy><node package="android">
            <node text="Switching to Owner…" package="android" enabled="true"
            bounds="[296,1141][782,1212]" /></node></hierarchy>''')
        self.prompt = ET.fromstring('''<hierarchy><node package="eu.darken.porter">
            <node text="Allow all the time" package="eu.darken.porter" enabled="true"
            bounds="[556,1144][897,1292]" /></node></hierarchy>''')

    def test_a_crash_in_an_app_under_test_is_left_on_screen(self):
        # Dismissing it would hide the failure the case is there to catch behind a later timeout.
        self.runner.adb = Mock(return_value=CRASH % smoke.MANAGER)
        self.runner.dump = Mock(return_value=self.crash)
        self.assertIs(self.runner.ui(), self.crash)
        self.runner.shell.assert_not_called()

    def test_a_crash_nothing_recorded_is_left_on_screen(self):
        # Nothing attributes it, and an unattributed dialog is not one to clear on a guess.
        self.runner.adb = Mock(return_value="")
        self.runner.dump = Mock(return_value=self.crash)
        self.assertIs(self.runner.ui(), self.crash)
        self.runner.shell.assert_not_called()

    def test_a_crash_in_a_process_of_an_app_under_test_is_left_on_screen(self):
        self.runner.adb = Mock(return_value=CRASH % (smoke.MANAGER + ":remote"))
        self.runner.dump = Mock(return_value=self.crash)
        self.assertIs(self.runner.ui(), self.crash)
        self.runner.shell.assert_not_called()

    def test_an_anr_dialog_is_left_on_screen_whatever_crashed_before(self):
        for label in ("Porter", "Process system"):
            with self.subTest(label=label):
                self.runner.shell.reset_mock()
                anr = ET.fromstring(f'''<hierarchy><node package="android">
                    <node text="{label} isn't responding" package="android" enabled="true"
                    bounds="[133,760][947,831]" />
                    <node text="Close app" package="android" enabled="true"
                    bounds="[70,870][1010,996]" />
                    <node text="Wait" package="android" enabled="true"
                    bounds="[70,996][1010,1122]" /></node></hierarchy>''')
                self.runner.dump = Mock(return_value=anr)
                self.assertIs(self.runner.ui(), anr)
                self.runner.shell.assert_not_called()

    def test_the_newest_crash_is_the_one_the_dialog_is_about(self):
        # The buffer keeps every crash of the run, and an old one of ours is not this dialog.
        self.runner.adb = Mock(return_value=(CRASH % smoke.NATIVE) + (CRASH % "com.android.bluetooth"))
        self.runner.dump = Mock(side_effect=[self.crash, self.prompt])
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertIs(self.runner.ui(), self.prompt)
        self.runner.shell.assert_called_once_with("input", "tap", 540, 1059)

    def test_the_dialog_is_cleared_and_what_it_covered_is_returned(self):
        self.runner.dump = Mock(side_effect=[self.crash, self.prompt])
        with contextlib.redirect_stdout(io.StringIO()) as printed:
            self.assertIs(self.runner.ui(), self.prompt)
        self.runner.shell.assert_called_once_with("input", "tap", 540, 1059)
        self.assertIn("Bluetooth keeps stopping", printed.getvalue())

    def test_the_switching_overlay_is_left_alone(self):
        # Framework-drawn too, and the one thing the suite has to sit through rather than clear.
        self.runner.dump = Mock(return_value=self.switching)
        self.assertIs(self.runner.ui(), self.switching)
        self.runner.shell.assert_not_called()

    def test_a_dialog_that_keeps_returning_is_given_up_on(self):
        # A service that crashes again on restart would otherwise be dismissed forever.
        self.runner.dump = Mock(return_value=self.crash)
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertIs(self.runner.ui(), self.crash)
        self.assertEqual(self.runner.shell.call_count, smoke.FRAMEWORK_ERROR_DISMISSALS)


class AllowIfRequestedTest(unittest.TestCase):
    def setUp(self):
        self.runner = smoke.Smoke.__new__(smoke.Smoke)
        self.runner.tap = Mock()
        self.prompt = ET.fromstring('''<hierarchy><node package="eu.darken.porter">
            <node text="Allow all the time" package="eu.darken.porter" enabled="true"
            bounds="[556,1144][897,1292]" /></node></hierarchy>''')

    def test_a_grant_already_held_is_not_waited_on_for_a_dialog(self):
        self.runner.logs = Mock(return_value=smoke.NATIVE + " AUTHORIZED managerOperationDenied=true")
        self.runner.ui = Mock()
        self.runner.allow_if_requested()
        self.runner.ui.assert_not_called()
        self.runner.tap.assert_not_called()

    @patch.object(smoke.time, "sleep")
    def test_a_screen_that_cannot_be_dumped_is_not_the_answer(self, sleep):
        # The dump this polls with has the short budget, so one unreadable screen used to end the
        # case where the wait around it still had looks left.
        self.runner.logs = Mock(return_value="")
        self.runner.ui = Mock(side_effect=[RuntimeError("no UI dump"), self.prompt])
        self.runner.allow_if_requested()
        self.assertEqual([c.args[0] for c in self.runner.ui.call_args_list],
                         [smoke.UI_POLL_TIMEOUT] * 2)
        self.runner.tap.assert_called_once_with(
            "Allow all the time", smoke.MANAGER, screenshot=None)


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

    def test_a_release_build_skips_a_debuggable_case_and_runs_the_rest(self):
        self.runner.args = argparse.Namespace(cases=None, release=True)
        self.runner.case("setup", lambda: self.ran.append("setup"))
        self.runner.case("debug-recording", lambda: self.ran.append("debug-recording"), debuggable=True)
        self.assertEqual(self.ran, ["setup"])
        self.assertEqual(self.recorded(), ["setup"])

    def test_a_debug_build_runs_a_debuggable_case(self):
        self.runner.args = argparse.Namespace(cases=None, release=False)
        self.runner.case("debug-recording", lambda: self.ran.append("debug-recording"), debuggable=True)
        self.assertEqual(self.ran, ["debug-recording"])

    def test_a_namespace_without_case_support_still_runs_every_case(self):
        # compat-install-smoke.py builds its own parser, which has no --case flag at all.
        self.runner.args = argparse.Namespace()
        self.runner.case("setup", lambda: self.ran.append("setup"))
        self.assertEqual(self.ran, ["setup"])
        self.assertEqual(self.recorded(), ["setup"])


class ManagerDataTest(unittest.TestCase):
    def setUp(self):
        self.runner = smoke.Smoke.__new__(smoke.Smoke)
        self.runner.shell = Mock(return_value="0")

    def test_a_debug_build_is_reached_through_run_as(self):
        self.runner.args = argparse.Namespace(release=False)
        self.assertEqual(self.runner.in_manager_data("cat a"), ("run-as", smoke.MANAGER, "sh", "-c", "cat a"))
        self.runner.shell.assert_not_called()

    def test_a_release_build_is_reached_as_root_by_path(self):
        self.runner.args = argparse.Namespace(release=True)
        self.assertEqual(self.runner.in_manager_data("cat a"),
                         ("su", "0", "sh", "-c", f"cd {smoke.MANAGER_DATA} && cat a"))

    def test_root_is_asked_for_once(self):
        self.runner.args = argparse.Namespace(release=True)
        self.runner.in_manager_data("cat a")
        self.runner.in_manager_data("cat b")
        self.runner.shell.assert_called_once_with("su", "0", "id", "-u", check=False)

    def test_an_image_without_root_fails_on_a_release_build(self):
        self.runner.args = argparse.Namespace(release=True)
        self.runner.shell = Mock(return_value="")
        with self.assertRaises(AssertionError):
            self.runner.in_manager_data("cat a")

    def test_a_recording_read_goes_through_the_same_door(self):
        self.runner.args = argparse.Namespace(release=True)
        self.runner.recording("cat no_backup/debug-logs/active")
        self.runner.shell.assert_called_with(
            "su", "0", "sh", "-c", f"cd {smoke.MANAGER_DATA} && cat no_backup/debug-logs/active", check=False)


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

    def test_the_manager_is_a_debug_build_unless_named_a_release_one(self):
        self.assertFalse(self.parse().release)
        self.assertTrue(self.parse("--release").release)

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


class ForeignSignerReconciliationTest(unittest.TestCase):
    """Run the nested replacement cases, with only their fixture I/O mocked."""
    ORIGINAL = "4200"
    APK = Path("/apks/probe-foreign.apk")
    REPLACED = f"host replaced {smoke.NATIVE} user 0 (signer changed) via host"
    ABSENT = f"host absent {smoke.NATIVE} (absent in every user) via host"

    def setUp(self):
        self.runner = smoke.Smoke.__new__(smoke.Smoke)
        self.runner.restore = Mock()
        self.bodies = {}
        self.restores = {}

        def case(name, action, restore=()):
            self.bodies[name] = action
            self.restores[name] = restore
        self.runner.case = case
        self.runner.reconciliation()
        self.runner.restore.assert_called_once_with("probes", "grants")
        self.installed = False
        self.before_pids = [{self.ORIGINAL}]
        self.after_pids = [set()]
        self.warnings = [self.REPLACED]
        self.order = Mock()
        for name, mock in (
                ("foreign_probe", Mock(return_value=self.APK)),
                ("launch_probe", Mock()),
                ("allow_if_requested", Mock()),
                ("expect_log", Mock()),
                ("logs", Mock(return_value="")),
                # authorized_daemon's raw PID can include a prior daemon still being destroyed.
                ("pid", Mock(return_value="4100 4200")),
                ("service_pids", Mock(side_effect=self.service_pids)),
                ("clear_logcat", Mock()),
                ("adb", Mock(side_effect=self.adb)),
                ("hand_over_reason", Mock(return_value="bind")),
                ("shell", Mock(side_effect=AssertionError("unexpected shell command")))):
            setattr(self.runner, name, mock)
            self.order.attach_mock(mock, name)
        sleep = patch.object(smoke.time, "sleep")
        self.order.attach_mock(sleep.start(), "sleep")
        self.addCleanup(sleep.stop)
        # Keep the real until() conditions and timeout behavior, without waiting on wall time.
        clock = patch.object(smoke.time, "monotonic", side_effect=itertools.count())
        clock.start()
        self.addCleanup(clock.stop)

    def service_pids(self, package):
        self.assertEqual(package, smoke.NATIVE)
        answers = self.after_pids if self.installed else self.before_pids
        return answers.pop(0) if len(answers) > 1 else answers[0]

    def adb(self, *args):
        if args == ("uninstall", smoke.NATIVE):
            return "Success"
        if args == ("install", str(self.APK)):
            self.installed = True
            return "Success"
        if args == ("logcat", "-d", "-s", "ApkReconciler:W", "*:S"):
            return self.warnings.pop(0) if len(self.warnings) > 1 else self.warnings[0]
        self.fail(f"Unexpected command: {args}")

    def run_case(self, name="foreign-signer-never-binds"):
        self.assertEqual(self.restores[name], ("probes", "grants"))
        return self.bodies[name]()

    def warning_reads(self):
        return [c for c in self.runner.adb.call_args_list if c.args[0] == "logcat"]

    def test_prepares_then_authorizes_settles_one_pid_and_bounds_the_install_gap(self):
        self.before_pids = [{"4100", self.ORIGINAL}, set(), {self.ORIGINAL}]
        self.assertEqual(self.run_case(), {"service_pid": self.ORIGINAL})
        install = call.adb("install", str(self.APK))
        self.assertEqual(self.order.mock_calls[:self.order.mock_calls.index(install) + 1], [
            call.foreign_probe(),
            call.launch_probe(smoke.NATIVE, daemon=True),
            call.allow_if_requested(),
            call.expect_log(smoke.NATIVE, "AUTHORIZED managerOperationDenied=true"),
            call.expect_log(smoke.NATIVE, "USER_SERVICE uid=2000 file=" + smoke.PAYLOAD),
            call.logs(),
            call.pid(smoke.NATIVE + ":porter-probe"),
            call.service_pids(smoke.NATIVE), call.sleep(0.4),
            call.service_pids(smoke.NATIVE), call.sleep(0.4),
            call.service_pids(smoke.NATIVE),
            call.sleep(smoke.HOST_QUIET), call.clear_logcat(),
            call.adb("uninstall", smoke.NATIVE), install,
        ])
        self.runner.foreign_probe.assert_called_once_with()
        # Only the original authorized app launches; the foreign app never launches, binds or peeks.
        self.runner.launch_probe.assert_called_once_with(smoke.NATIVE, daemon=True)
        self.runner.hand_over_reason.assert_not_called()
        self.runner.shell.assert_not_called()

    def test_multiple_service_pids_never_become_the_original(self):
        self.before_pids = [{"4100", self.ORIGINAL}]
        with self.assertRaisesRegex(AssertionError, "Timed out: one privileged user service"):
            self.run_case()
        self.runner.adb.assert_not_called()
        self.runner.clear_logcat.assert_not_called()

    def test_completed_scan_cleanup_before_install_returns_is_valid(self):
        self.assertEqual(self.run_case(), {"service_pid": self.ORIGINAL})
        self.assertEqual(len(self.warning_reads()), 1)
        self.assertEqual(self.order.mock_calls[-2:], [
            call.adb("logcat", "-d", "-s", "ApkReconciler:W", "*:S"),
            call.service_pids(smoke.NATIVE),
        ])

    def test_absence_fails_promptly_even_while_original_pid_is_alive(self):
        self.warnings = [self.ABSENT]
        self.after_pids = [{self.ORIGINAL}]
        with self.assertRaisesRegex(AssertionError, "invalid replacement setup: host absent"):
            self.run_case()
        self.assertEqual(len(self.warning_reads()), 1)
        self.runner.service_pids.assert_called_once_with(smoke.NATIVE)

    def test_absence_cannot_be_rescued_by_replacement_evidence(self):
        self.warnings = [self.ABSENT + "\n" + self.REPLACED]
        with self.assertRaisesRegex(AssertionError, "host absent"):
            self.run_case()
        self.assertEqual(len(self.warning_reads()), 1)

    def test_replacement_warning_without_original_pid_exit_cannot_pass(self):
        self.after_pids = [{self.ORIGINAL}]
        with self.assertRaisesRegex(AssertionError, "Timed out: the host scan released the original daemon"):
            self.run_case()
        self.assertEqual(len(self.warning_reads()), 1)
        self.assertGreater(self.runner.service_pids.call_count, 2)

    def test_replacement_evidence_waits_for_original_pid_not_every_service_to_exit(self):
        self.after_pids = [{self.ORIGINAL}, {self.ORIGINAL, "4300"}, {"4300"}]
        self.assertEqual(self.run_case(), {"service_pid": self.ORIGINAL})
        self.assertEqual(len(self.warning_reads()), 1)
        self.assertEqual(self.runner.service_pids.call_count, 4)

    def test_each_scan_poll_reads_warnings_once_while_the_pid_lives(self):
        self.warnings = ["", "", self.REPLACED]
        self.after_pids = [{self.ORIGINAL}, {self.ORIGINAL}, set()]
        self.assertEqual(self.run_case(), {"service_pid": self.ORIGINAL})
        self.assertEqual(len(self.warning_reads()), 3)
        self.assertEqual(self.runner.service_pids.call_count, 4)

    def test_scan_between_warning_and_pid_reads_is_reclassified(self):
        self.warnings = ["", self.REPLACED]
        self.assertEqual(self.run_case(), {"service_pid": self.ORIGINAL})
        self.assertEqual(len(self.warning_reads()), 2)
        self.assertEqual(self.order.mock_calls[-4:], [
            call.adb("logcat", "-d", "-s", "ApkReconciler:W", "*:S"),
            call.service_pids(smoke.NATIVE),
            call.adb("logcat", "-d", "-s", "ApkReconciler:W", "*:S"),
            call.service_pids(smoke.NATIVE),
        ])

    def test_absence_between_warning_and_pid_reads_is_rejected(self):
        self.warnings = ["", self.ABSENT]
        with self.assertRaisesRegex(AssertionError, "host absent"):
            self.run_case()
        self.assertEqual(len(self.warning_reads()), 2)

    def test_original_pid_loss_without_evidence_fails_promptly(self):
        self.warnings = [""]
        with self.assertRaisesRegex(AssertionError, "exited without host-replaced evidence"):
            self.run_case()
        self.assertEqual(len(self.warning_reads()), 2)
        self.assertEqual(self.runner.service_pids.call_count, 2)

    def test_bind_refusal_or_another_packages_replacement_cannot_pass(self):
        for warning in (
                f"does not belong to the current installation of {smoke.NATIVE}",
                self.REPLACED.replace(smoke.NATIVE, smoke.LEGACY),
                self.REPLACED.replace(smoke.NATIVE, smoke.NATIVE + ".extra")):
            with self.subTest(warning=warning):
                self.installed = False
                self.warnings = [warning]
                with self.assertRaisesRegex(AssertionError, "without host-replaced evidence"):
                    self.run_case()

    def test_another_packages_absence_is_not_this_packages_absence(self):
        self.warnings = [self.ABSENT.replace(smoke.NATIVE, smoke.NATIVE + ".extra")
                         + "\n" + self.REPLACED]
        self.assertEqual(self.run_case(), {"service_pid": self.ORIGINAL})

    def test_bind_and_peek_still_require_post_install_survival(self):
        for name in ("foreign-signer-binds", "foreign-signer-peeks"):
            with self.subTest(case=name):
                self.installed = False
                self.runner.launch_probe.reset_mock()
                with self.assertRaisesRegex(AssertionError, "the daemon was gone before the bind"):
                    self.run_case(name)
                self.runner.launch_probe.assert_called_once_with(smoke.NATIVE, daemon=True)
        self.assertEqual(len(self.warning_reads()), 0)

    def check_hand_over(self, name, **mode):
        self.after_pids = [{self.ORIGINAL}, set()]
        result = self.run_case(name)
        self.assertEqual(result, {"original": self.ORIGINAL, "after": [], "reason": "bind"})
        self.assertEqual(self.runner.launch_probe.call_args_list, [
            call(smoke.NATIVE, daemon=True), call(smoke.NATIVE, **mode),
        ])
        install = self.order.mock_calls.index(call.adb("install", str(self.APK)))
        self.assertEqual(self.order.mock_calls[install + 1:install + 3], [
            call.service_pids(smoke.NATIVE), call.launch_probe(smoke.NATIVE, **mode),
        ])

    def test_bind_launches_only_after_post_install_survival(self):
        self.check_hand_over("foreign-signer-binds", daemon=True)

    def test_peek_launches_only_after_post_install_survival(self):
        self.check_hand_over("foreign-signer-peeks", peek=True)
        self.runner.expect_log.assert_any_call(smoke.NATIVE, "PEEK version=-1")


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
        # The users aspect asks who is on screen before it removes anything.
        self.runner.shell = Mock(side_effect=lambda *args, **kwargs:
                                 "0" if args[:2] == ("am", "get-current-user") else "")
        self.runner.pid = Mock(return_value="5271")
        self.runner.installed = Mock(return_value=False)
        # Present, then gone: the aspect waits for the removal to show up in the user list.
        self.users = ["10"]
        self.runner.extra_users = Mock(side_effect=lambda: list(self.users))
        self.runner.shell_removes_user = None
        self.runner.start_service = Mock()
        self.runner.open_home = Mock()
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
        removes = self.runner.shell.side_effect

        def removing(*args, **kwargs):
            if args[:2] == ("pm", "remove-user"):
                self.users = []
            return removes(*args, **kwargs)
        self.runner.shell.side_effect = removing
        with self.assertRaisesRegex(AssertionError, "Timed out"):
            self.runner.case("probe-case", boom, restore=("users",))
        self.assertEqual(self.runner.results[0]["passed"], False)
        self.runner.shell.assert_any_call("pm", "remove-user", "10", check=False)
        self.assertEqual(self.users, [])

    def test_a_scenario_that_passed_restores_its_users_and_says_nothing(self):
        """The successful path through the users aspect, which the two below never reach."""
        removes = self.runner.shell.side_effect

        def removing(*args, **kwargs):
            if args[:2] == ("pm", "remove-user"):
                self.users = []
            return removes(*args, **kwargs)
        self.runner.shell.side_effect = removing
        self.runner.case("probe-case", lambda: {"ok": True}, restore=("users",))
        self.assertTrue(self.runner.results[0]["passed"])
        self.assertNotIn("restore_failure", self.runner.results[0])
        self.runner.shell.assert_any_call("pm", "remove-user", "10", check=False)

    def test_a_user_the_framework_never_finished_removing_fails_the_restore(self):
        with patch.object(smoke, "USER_REMOVAL_TIMEOUT", 0.2), patch.object(smoke.time, "sleep"):
            with self.assertRaisesRegex(AssertionError, "extra users are gone"):
                self.runner.case("probe-case", lambda: None, restore=("users",))
        self.assertFalse(self.runner.results[0]["passed"])
        self.assertIn("extra users are gone", self.runner.results[0]["restore_failure"])

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
        self.runner.open_home.assert_called_once()
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


class KillServerTest(unittest.TestCase):
    """Killing every server by pid, since Android 7's pkill takes neither -9 nor -f."""

    def setUp(self):
        self.runner = smoke.Smoke.__new__(smoke.Smoke)
        self.runner.shell = Mock(return_value="")
        self.runner.pid = Mock(return_value="3120 3300")

    def test_every_server_is_killed_as_root(self):
        self.runner.kill_server()
        self.runner.pid.assert_called_once_with("porter_server")
        self.runner.shell.assert_called_once_with("su", "0", "kill", "-9", "3120", "3300", check=True)

    def test_no_server_where_one_was_expected_fails(self):
        self.runner.pid.return_value = ""
        with self.assertRaisesRegex(AssertionError, "no porter_server"):
            self.runner.kill_server()
        self.runner.shell.assert_not_called()

    def test_no_server_is_fine_when_only_clearing_the_way(self):
        self.runner.pid.return_value = ""
        self.runner.kill_server(check=False)
        self.runner.shell.assert_not_called()


class ClearLogcatTest(unittest.TestCase):
    """The clear Android 7's logd refuses now and then."""
    REFUSED = RuntimeError("adb -s emulator-5554 logcat -c: failed to clear the 'main' log")

    def setUp(self):
        self.runner = smoke.Smoke.__new__(smoke.Smoke)
        self.runner.adb = Mock(return_value="")
        sleep = patch.object(smoke.time, "sleep")
        sleep.start()
        self.addCleanup(sleep.stop)

    def test_a_refusal_is_asked_again(self):
        self.runner.adb.side_effect = [self.REFUSED, ""]
        self.runner.clear_logcat()
        self.assertEqual(self.runner.adb.call_count, 2)

    def test_a_clear_that_never_happens_fails_the_case(self):
        self.runner.adb.side_effect = self.REFUSED
        with self.assertRaisesRegex(RuntimeError, "failed to clear"):
            self.runner.clear_logcat()
        self.assertEqual(self.runner.adb.call_count, smoke.LOGCAT_CLEAR_ATTEMPTS)

    def test_any_other_failure_is_not_retried(self):
        self.runner.adb.side_effect = RuntimeError("adb: device offline")
        with self.assertRaises(RuntimeError):
            self.runner.clear_logcat()
        self.assertEqual(self.runner.adb.call_count, 1)


class UnlockTest(unittest.TestCase):
    """The lock screen Android 7 raises on a switch back to the owner user."""

    def setUp(self):
        self.runner = smoke.Smoke.__new__(smoke.Smoke)
        # Each look dismisses, then reads the policy dump; answers alternate accordingly.
        self.dumps = []
        self.runner.shell = Mock(side_effect=lambda *args, **kwargs:
                                 self.dumps.pop(0) if args[:2] == ("dumpsys", "window") else "")

        def until(description, predicate, **kwargs):
            for _ in range(5):
                if predicate():
                    return True
            raise AssertionError(f"Timed out: {description}")
        self.runner.until = until

    def dismissals(self):
        return [call for call in self.runner.shell.call_args_list if call.args[:2] == ("wm", "dismiss-keyguard")]

    def test_a_lock_screen_raised_late_is_dismissed_again(self):
        # Android 11's KeyguardStateMonitor lines; its dump has no mShowingLockscreen at all.
        self.dumps = ["    KeyguardStateMonitor\n        mIsShowing=true",
                      "    KeyguardStateMonitor\n        mIsShowing=false"]
        self.runner.unlock()
        self.assertEqual(len(self.dismissals()), 2)

    def test_a_dump_without_the_field_counts_as_unlocked(self):
        self.dumps = ["WINDOW MANAGER POLICY STATE"]
        self.runner.unlock()
        self.assertEqual(len(self.dismissals()), 1)


class CreateUserTest(unittest.TestCase):
    """A new user's id, read from pm's reply because Android 7 exits 1 after succeeding."""

    def setUp(self):
        self.runner = smoke.Smoke.__new__(smoke.Smoke)
        self.runner.shell = Mock(return_value="Success: created user id 11")

    def test_the_id_comes_from_the_reply_whatever_the_exit_status(self):
        self.assertEqual(self.runner.create_user("porter-ci"), "11")
        self.runner.shell.assert_called_once_with("pm", "create-user", "porter-ci", check=False)

    def test_a_reply_without_an_id_fails_the_case(self):
        self.runner.shell.return_value = "Error: couldn't create User."
        with self.assertRaisesRegex(AssertionError, "couldn't create User"):
            self.runner.create_user("porter-ci")


class InstallForUserTest(unittest.TestCase):
    """Adding user 0's installation for another user, with and without pm install-existing."""
    APK = Path("/tmp/probe.apk")

    def setUp(self):
        self.runner = smoke.Smoke.__new__(smoke.Smoke)
        self.runner.shell = Mock(return_value="Package eu.darken.porter.probe.native installed for user: 10")
        self.runner.adb = Mock(return_value="Success")

    def test_install_existing_where_pm_has_it(self):
        self.runner.install_for_user("10", smoke.NATIVE, self.APK)
        self.runner.shell.assert_called_once_with("pm", "install-existing", "--user", "10", smoke.NATIVE)
        self.runner.adb.assert_not_called()

    def test_android_7_answering_on_stdout_gets_the_apk(self):
        self.runner.shell.return_value = "Error: unknown command 'install-existing'"
        self.runner.install_for_user("10", smoke.NATIVE, self.APK)
        self.runner.adb.assert_called_once_with("install", "-r", "--user", "10", str(self.APK.resolve()))

    def test_android_7_failing_the_command_gets_the_apk(self):
        self.runner.shell.side_effect = RuntimeError("adb shell ...: Error: unknown command 'install-existing'")
        self.runner.install_for_user("10", smoke.NATIVE, self.APK)
        self.runner.adb.assert_called_once_with("install", "-r", "--user", "10", str(self.APK.resolve()))

    def test_any_other_failure_is_the_case_failing(self):
        self.runner.shell.side_effect = RuntimeError("adb shell ...: Failure [not installed for 0]")
        with self.assertRaisesRegex(RuntimeError, "not installed"):
            self.runner.install_for_user("10", smoke.NATIVE, self.APK)
        self.runner.adb.assert_not_called()


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

    def test_the_listing_is_toyboxs_which_android_7_does_not_link_as_ps(self):
        self.runner.spawned(self.SERVER)
        self.runner.remote_logcat(self.SERVER)
        for call in self.runner.shell.call_args_list:
            self.assertEqual(call.args[:4], ("toybox", "ps", "-A", "-o"))

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

    def test_padded_columns_still_find_the_logcat(self):
        self.runner.shell.return_value = "  PID ARGS\n 4181   logcat -v threadtime --pid=3120 -T 1"
        self.assertEqual(self.runner.remote_logcat(self.SERVER), ["4181"])

    def test_a_reaped_logcat_leaves_nothing_to_find(self):
        self.runner.shell.return_value = "PID ARGS\n4300 logcat -v threadtime --pid=9999 -T 1"
        self.assertEqual(self.runner.remote_logcat(self.SERVER), [])

    def test_a_longer_pid_starting_with_this_one_is_a_different_service(self):
        self.runner.shell.return_value = "PID ARGS\n4181 logcat -v threadtime --pid=31200 -T 1"
        self.assertEqual(self.runner.remote_logcat("3120"), [])


class RecordingEventsTest(unittest.TestCase):
    """How the debug-recording case reads a session's events.txt and server.log."""
    EVENTS = "\n".join((
        "Recording manager pid=900 at 1790607900000",
        "Clock start wall=2026-09-28T17:05:00.000+0200 epochMs=1790607900000 elapsedMs=5000000 uptimeMs=4000000 bootCount=7",
        "Waiting for Porter service at 1790607900010",
        "Service binder arrived attach=1 pid=3120 at 1790607903123",
        "Clock attach 1 wall=2026-09-28T17:05:03.123+0200 epochMs=1790607903123 elapsedMs=5003123 uptimeMs=4003123 bootCount=7",
        "Debug logging granted for 1795000ms at 1790607903180",
        "Server stream attached pid=3120 boot=7 attach=1 replay=1790607900.000 at 1790607903200",
        "Service binder lost at 1790607910000",
        "Clock lost wall=2026-09-28T17:05:10.000+0200 epochMs=1790607910000 elapsedMs=5010000 uptimeMs=4010000 bootCount=7",
        "Server stream ended pid=3120 at 1790607910005",
        "Service binder arrived attach=2 pid=4242 at 1790607915000",
        "Clock attach 2 wall=2026-09-28T17:05:15.000-0130 epochMs=1790607915000 elapsedMs=5015000 uptimeMs=4015000 bootCount=-1",
        "Debug logging refused at 1790607915050",
        "Server stream attached pid=4242 boot=-1 attach=2 replay=all at 1790607915100",
        "Clock stop wall=2026-09-28T17:05:20.000+0200 epochMs=1790607920000 elapsedMs=5020000 uptimeMs=4020000 bootCount=7",
    ))

    def test_attaches_are_read_in_order_with_their_numbers(self):
        self.assertEqual(smoke.stream_attaches(self.EVENTS), [("3120", 1), ("4242", 2)])

    def test_an_attach_line_needs_its_time(self):
        self.assertEqual(smoke.stream_attaches("Server stream attached pid=3120 boot=7 attach=1 replay=all"), [])

    def test_a_line_read_through_adb_may_end_in_a_carriage_return(self):
        self.assertEqual(smoke.stream_attaches(
            "Server stream attached pid=3120 boot=7 attach=1 replay=all at 1\r\n"), [("3120", 1)])

    def test_a_lease_belongs_to_the_arrival_before_it(self):
        self.assertTrue(smoke.lease_granted(self.EVENTS, "3120"))
        # Refused for the second instance: the first one's grant is not carried over to it.
        self.assertFalse(smoke.lease_granted(self.EVENTS, "4242"))

    def test_an_instance_that_never_arrived_has_no_lease(self):
        self.assertFalse(smoke.lease_granted(self.EVENTS, "5555"))

    def test_a_grant_that_lands_after_the_next_arrival_is_not_the_earlier_instances(self):
        events = "\n".join((
            "Service binder arrived attach=1 pid=3120 at 1",
            "Service binder arrived attach=2 pid=4242 at 2",
            "Debug logging granted for 5ms at 3",
        ))
        self.assertFalse(smoke.lease_granted(events, "3120"))
        self.assertTrue(smoke.lease_granted(events, "4242"))

    def test_the_newest_arrival_of_a_pid_decides(self):
        events = "\n".join((
            "Service binder arrived attach=1 pid=3120 at 1",
            "Debug logging granted for 5ms at 2",
            "Service binder arrived attach=2 pid=3120 at 3",
            "Debug logging granted nothing at 4",
        ))
        self.assertFalse(smoke.lease_granted(events, "3120"))

    def test_clock_anchors_are_read_by_label_in_order(self):
        self.assertEqual(smoke.clock_anchors(self.EVENTS), ["start", "attach 1", "lost", "attach 2", "stop"])

    def test_a_malformed_anchor_is_not_one(self):
        for line in (
                # No zone offset on the wall time.
                "Clock start wall=2026-09-28T17:05:00.000 epochMs=1 elapsedMs=2 uptimeMs=3 bootCount=7",
                # A field missing.
                "Clock start wall=2026-09-28T17:05:00.000+0200 epochMs=1 elapsedMs=2 bootCount=7",
                # Something after the last field.
                "Clock start wall=2026-09-28T17:05:00.000+0200 epochMs=1 elapsedMs=2 uptimeMs=3 bootCount=7 at 1"):
            self.assertEqual(smoke.clock_anchors(line), [], line)

    def test_in_order_allows_anything_between(self):
        self.assertTrue(smoke.in_order(["start", "resume", "attach 1", "stop"], ("start", "attach 1", "stop")))

    def test_in_order_refuses_a_reordering_or_a_gap(self):
        self.assertFalse(smoke.in_order(["attach 1", "start", "stop"], ("start", "attach 1", "stop")))
        self.assertFalse(smoke.in_order(["start", "stop"], ("start", "attach 1", "stop")))

    def test_logged_by_keeps_only_that_pids_lines_with_the_message(self):
        log = "\n".join((
            "--------- beginning of main",
            "09-28 17:05:03.123  3120  3130 I Service : starting server...",
            "09-28 17:05:04.000  4242  4250 I Service : starting server...",
            "09-28 17:05:05.000  3120  3130 I Service : sent binders",
            # A pid that only starts with this one.
            "09-28 17:05:06.000 31200 31210 I Service : starting server...",
        ))
        self.assertEqual(smoke.logged_by(log, "3120", "starting server..."),
                         ["09-28 17:05:03.123  3120  3130 I Service : starting server..."])

    def test_logged_by_does_not_read_the_message_as_the_pid(self):
        self.assertEqual(smoke.logged_by("starting server... 3120", "3120", "starting server..."), [])


class ResumedElsewhereTest(unittest.TestCase):
    """Whether the manager has left the foreground before the debug-recording case force-stops it."""
    LAUNCHER = "ActivityRecord{2c1e3b1 u0 com.android.launcher3/.Launcher t1}"
    SUPPORT = "ActivityRecord{7f0a2c4 u0 eu.darken.porter/.manager.support.SupportActivity t5}"
    PROBE = "ActivityRecord{9d8e7f6 u0 eu.darken.porter.probe.native/.ProbeActivity t9}"

    def activities(self, resumed, history=SUPPORT):
        return ("ACTIVITY MANAGER ACTIVITIES (dumpsys activity activities)\n"
                f"    * Hist #0: {history}\n"
                f"{resumed}")

    def test_android_7_names_the_launcher_resumed(self):
        self.assertTrue(smoke.resumed_elsewhere(
            self.activities(f"  mResumedActivity: {self.LAUNCHER}\n"), "eu.darken.porter"))

    def test_later_releases_name_it_as_top_resumed(self):
        self.assertTrue(smoke.resumed_elsewhere(
            self.activities(f"  topResumedActivity={self.LAUNCHER}\n"), "eu.darken.porter"))

    def test_the_manager_resumed_is_still_in_front(self):
        self.assertFalse(smoke.resumed_elsewhere(
            self.activities(f"  mResumedActivity: {self.SUPPORT}\n"), "eu.darken.porter"))

    def test_any_resumed_line_naming_the_manager_keeps_it_in_front(self):
        self.assertFalse(smoke.resumed_elsewhere(
            self.activities(f"    ResumedActivity: {self.SUPPORT}\n  topResumedActivity={self.LAUNCHER}\n"),
            "eu.darken.porter"))

    def test_a_package_that_only_starts_with_the_managers_is_not_it(self):
        self.assertTrue(smoke.resumed_elsewhere(
            self.activities(f"  mResumedActivity: {self.PROBE}\n"), "eu.darken.porter"))

    def test_nothing_resumed_is_not_left(self):
        self.assertFalse(smoke.resumed_elsewhere(self.activities("  mResumedActivity: null\n"), "eu.darken.porter"))
        self.assertFalse(smoke.resumed_elsewhere(self.activities(""), "eu.darken.porter"))


class UserHomeResumedTest(unittest.TestCase):
    HOME = "com.android.fakesystemapp/.launcher.EmptyHomeActivity"

    def record(self, component=HOME, user="11"):
        return f"ActivityRecord{{123abc u{user} {component} t1100002}}"

    def test_resumed_formats_and_component_spellings(self):
        full = self.HOME.replace("/.", "/com.android.fakesystemapp.")
        for prefix in ("mResumedActivity: ", "topResumedActivity=", "ResumedActivity: "):
            for resolved, recorded in ((self.HOME, full), (full, self.HOME), (self.HOME, self.HOME)):
                with self.subTest(prefix=prefix, resolved=resolved, recorded=recorded):
                    self.assertTrue(smoke.user_home_resumed(
                        "  " + prefix + self.record(recorded), "11", resolved))

    def test_wrong_user_component_history_or_missing_record_is_not_ready(self):
        for activities in (
                "mResumedActivity: " + self.record(user="0"),
                "mResumedActivity: " + self.record(user="110"),
                "mResumedActivity: " + self.record(component="com.android.fakesystemapp/.Different"),
                "mResumedActivity: " + self.record(component="another.package/.launcher.EmptyHomeActivity"),
                "Hist #0: " + self.record(), "mResumedActivity: null", ""):
            with self.subTest(activities=activities):
                self.assertFalse(smoke.user_home_resumed(activities, "11", self.HOME))

    def test_missing_or_temporary_setup_resolver_is_not_ready(self):
        for component in ("", "No activity found", "unexpected output\n" + self.HOME,
                          "com.google.android.googlesdksetup/.DefaultActivity",
                          "com.android.provision/.DefaultActivity"):
            with self.subTest(component=component):
                self.assertFalse(smoke.user_home_resumed(
                    "topResumedActivity=" + self.record(component), "11", component))

    def test_barrier_retains_resolver_and_resumed_evidence_in_commands_log(self):
        with tempfile.TemporaryDirectory() as directory:
            runner = smoke.Smoke(argparse.Namespace(serial="emulator-5554", output=Path(directory)))
            activities = "  mResumedActivity: " + self.record()
            replies = ["", "mIsShowing=false", "11", self.HOME, activities]
            with patch.object(smoke.subprocess, "run", side_effect=[
                    completed(0, stdout=(reply + "\n").encode()) for reply in replies]):
                runner.wait_user_home("11")
            commands = (runner.output / "commands.log").read_text()
            self.assertIn("resolve-activity --components --user 11", commands)
            self.assertIn(self.HOME + "\n", commands)
            self.assertIn(activities, commands)
            self.assertNotIn("logcat", commands)


class SecondaryUserPromptTest(unittest.TestCase):
    """Exercise the real case and readiness poll; fake only device I/O."""
    HOME = UserHomeResumedTest.HOME
    SETUP = "com.google.android.googlesdksetup/.DefaultActivity"
    UID = 1110140
    OWNER_UID = 10140

    def setUp(self):
        self.runner = smoke.Smoke.__new__(smoke.Smoke)
        self.runner.args = argparse.Namespace(native=Path("/apks/native.apk"))
        self.runner.restore = Mock()
        self.bodies = {}
        self.restores = {}

        def case(name, action, restore=()):
            self.bodies[name] = action
            self.restores[name] = restore
        self.runner.case = case
        self.runner.reconciliation()
        self.sdk = "36"
        self.current = "0"
        # get-current-user already says 11 throughout these incomplete transitions.
        self.states = [(self.HOME, "0", self.HOME),
                       (self.SETUP, "11", self.SETUP),
                       (self.HOME, "11", self.SETUP),
                       (self.HOME, "11", None),
                       (self.HOME, "11", self.HOME)]
        self.reads = 0
        self.order = Mock()
        for name, mock in (
                ("create_user", Mock(return_value="11")),
                ("install_for_user", Mock()),
                ("app_uid", Mock(side_effect=lambda package, user="0":
                    self.OWNER_UID if user == "0" else self.UID)),
                ("shell", Mock(side_effect=self.shell)),
                ("unlock", Mock()),
                ("clear_logcat", Mock()),
                ("launch_probe_as", Mock()),
                ("logs", Mock(return_value=smoke.NATIVE + " DENIED")),
                ("expect_log", Mock(wraps=self.runner.expect_log)),
                ("decision_flags", Mock(side_effect=[0, smoke.DECISION_ALLOWED, 0])),
                ("absent", Mock(return_value=True)),
                ("launch_probe", Mock()),
                ("allow_if_requested", Mock()),
                ("authorized", Mock())):
            setattr(self.runner, name, mock)
            self.order.attach_mock(mock, name)
        sleep = patch.object(smoke.time, "sleep")
        sleep.start()
        self.addCleanup(sleep.stop)
        clock = patch.object(smoke.time, "monotonic", side_effect=itertools.count())
        clock.start()
        self.addCleanup(clock.stop)

    def shell(self, *args):
        if args == ("getprop", "ro.build.version.sdk"):
            return self.sdk
        if args == ("am", "start-user", "11"):
            return "Success"
        if args[:2] == ("am", "switch-user"):
            self.current = args[2]
            return ""
        if args == ("am", "get-current-user"):
            return self.current
        if args == ("cmd", "package", "resolve-activity", "--components", "--user", "11",
                    "-a", "android.intent.action.MAIN", "-c", "android.intent.category.HOME"):
            self.runner.launch_probe_as.assert_not_called()
            self.runner.clear_logcat.assert_not_called()
            return self.states[0][0]
        if args == ("dumpsys", "activity", "activities"):
            _, user, component = self.states[0]
            if len(self.states) > 1:
                self.states.pop(0)
            self.reads += 1
            return (f"topResumedActivity=ActivityRecord{{abc u{user} {component} t7}}"
                    if component else "topResumedActivity=null")
        if args in (("am", "force-stop", "--user", "11", smoke.NATIVE),
                    ("am", "stop-user", "-w", "11")):
            return ""
        self.fail(f"Unexpected command: {args}")

    def run_case(self):
        self.assertEqual(self.restores["secondary-user-prompt"], ("users", "probes", "grants"))
        return self.bodies["secondary-user-prompt"]()

    def test_home_finishes_before_clear_and_single_launch_then_original_denial_check(self):
        self.assertEqual(self.run_case(), {"user": "11", "uid": self.UID})
        self.assertEqual(self.reads, 5)
        self.runner.launch_probe_as.assert_called_once_with(smoke.NATIVE, "11")
        self.runner.expect_log.assert_called_once_with(smoke.NATIVE, "DENIED")
        calls = self.order.mock_calls
        clear = calls.index(call.clear_logcat())
        self.assertEqual(calls[clear - 1], call.shell("dumpsys", "activity", "activities"))
        self.assertEqual(calls[clear:clear + 5], [call.clear_logcat(),
            call.launch_probe_as(smoke.NATIVE, "11"), call.expect_log(smoke.NATIVE, "DENIED"),
            call.logs(), call.decision_flags(self.UID)])
        self.assertLess(calls.index(call.unlock()), clear)
        self.assertEqual(self.runner.decision_flags.call_args_list,
                         [call(self.UID), call(self.OWNER_UID), call(self.UID)])
        self.runner.absent.assert_called_once_with("Allow all the time", smoke.MANAGER)
        self.runner.authorized.assert_called_once_with(smoke.NATIVE)
        self.assertLess(calls.index(call.shell("am", "stop-user", "-w", "11")),
                        calls.index(call.launch_probe(smoke.NATIVE)))

    def test_android30_launches_without_waiting_for_home(self):
        self.sdk = "30"
        self.assertEqual(self.run_case(), {"user": "11", "uid": self.UID})
        self.assertEqual(self.reads, 0)
        calls = self.order.mock_calls
        clear = calls.index(call.clear_logcat())
        self.assertEqual(calls[clear - 2:clear + 3], [
            call.shell("am", "get-current-user"), call.shell("getprop", "ro.build.version.sdk"),
            call.clear_logcat(), call.launch_probe_as(smoke.NATIVE, "11"),
            call.expect_log(smoke.NATIVE, "DENIED")])

    def test_missing_home_fails_before_clearing_evidence_or_launching(self):
        self.states = [(self.SETUP, "11", self.SETUP)]
        with self.assertRaisesRegex(AssertionError, "Timed out: user 11 HOME is resumed"):
            self.run_case()
        self.runner.clear_logcat.assert_not_called()
        self.runner.launch_probe_as.assert_not_called()
        self.runner.expect_log.assert_not_called()

    def test_missing_denial_keeps_original_timeout_without_retry_or_owner_success(self):
        self.runner.logs.return_value = smoke.NATIVE + " MODE daemon=false\n" + smoke.NATIVE + " BINDER"
        with patch.object(self.runner, "until", wraps=self.runner.until) as until:
            with self.assertRaisesRegex(AssertionError, f"Timed out: {smoke.NATIVE}: DENIED"):
                self.run_case()
        denial = [c for c in until.call_args_list if c.args[0] == smoke.NATIVE + ": DENIED"]
        self.assertEqual(len(denial), 1)
        self.assertEqual(len(denial[0].args), 2)
        self.assertEqual(denial[0].kwargs, {})  # The original default deadline, not an override.
        self.runner.launch_probe_as.assert_called_once_with(smoke.NATIVE, "11")
        self.runner.clear_logcat.assert_called_once_with()
        self.runner.decision_flags.assert_not_called()
        self.runner.launch_probe.assert_not_called()
        self.runner.absent.assert_not_called()

    def test_secondary_persisted_decision_still_fails_after_denial(self):
        for flag in (smoke.DECISION_ALLOWED, smoke.DECISION_DENIED):
            with self.subTest(flag=flag):
                self.runner.decision_flags.side_effect = [flag]
                with self.assertRaisesRegex(AssertionError, "never shown was written down"):
                    self.run_case()
                self.runner.launch_probe.assert_not_called()
                self.runner.launch_probe_as.reset_mock()
                self.runner.clear_logcat.reset_mock()

    def test_owner_grant_must_be_saved_and_remain_isolated(self):
        for flags, message in (([0, 0], "answered grant was not saved"),
                               ([0, smoke.DECISION_ALLOWED, smoke.DECISION_ALLOWED], "grant reached")):
            with self.subTest(flags=flags):
                self.runner.decision_flags.side_effect = flags
                with self.assertRaisesRegex(AssertionError, message):
                    self.run_case()
                self.runner.launch_probe_as.reset_mock()
                self.runner.clear_logcat.reset_mock()


class LaunchProbeAsTest(unittest.TestCase):
    """Which process the secondary-user case reads its log from."""

    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.runner = smoke.Smoke(argparse.Namespace(
            serial="emulator-5554", output=Path(directory.name)))

    def test_a_copy_running_in_another_user_is_stopped_before_the_launch(self):
        pids = ["4001 4002", ""]
        stopped = []

        def shell(*args, **kwargs):
            if args[:2] == ("am", "force-stop"):
                stopped.append(args)
                return ""
            return ""
        with patch.object(self.runner, "shell", side_effect=shell), \
             patch.object(self.runner, "pid", side_effect=lambda p: pids.pop(0) if pids else "4100"), \
             patch.object(smoke.time, "sleep"):
            self.assertEqual(self.runner.launch_probe_as(smoke.NATIVE, "11"), "4100")
        self.assertIn(("am", "force-stop", "--user", "all", smoke.NATIVE), stopped)

    def test_two_surviving_processes_are_waited_out_rather_than_picked_between(self):
        with patch.object(self.runner, "shell"), \
             patch.object(self.runner, "pid", return_value="4001 4002"), \
             patch.object(smoke.time, "sleep"), \
             patch.object(smoke, "LAUNCH_TIMEOUT", 0.2):
            with self.assertRaisesRegex(AssertionError, "no probe process anywhere"):
                self.runner.launch_probe_as(smoke.NATIVE, "11")


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
        shell.assert_called_once()
        # The device decides which of the two answers comes back, so the harness never has to
        # read an error message to tell "no file" from "could not read".
        self.assertIn(smoke.Smoke.NO_DECISIONS, shell.call_args.args[-1])
        self.assertIn(smoke.DECISIONS, shell.call_args.args[-1])
        return result

    def test_a_device_that_answered_nothing_has_no_database(self):
        self.assertEqual(self.flags(smoke.Smoke.NO_DECISIONS), 0)

    def test_a_read_that_failed_is_not_mistaken_for_an_empty_database(self):
        with self.assertRaisesRegex(AssertionError, "cannot read"):
            self.flags("cat: " + smoke.DECISIONS + ": Permission denied")

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

    def test_android_7_without_minus_u_is_read_from_the_package_dump(self):
        # What API 24 answers: pm rejects -U, and dumpsys names the app id userId.
        replies = ["Error: Unknown option: -U", "  Package [p] (8f2c1d0):\n    userId=10085\n"]
        with patch.object(self.runner, "shell", side_effect=replies) as shell:
            self.assertEqual(self.runner.app_uid("p", "10"), 1010085)
        self.assertEqual(shell.call_args.args, ("dumpsys", "package", "p"))

    def test_minus_u_rejected_with_an_exit_status_also_falls_back(self):
        replies = [RuntimeError("adb shell ...: Error: Unknown option: -U"), "    userId=10085\n"]
        with patch.object(self.runner, "shell", side_effect=replies):
            self.assertEqual(self.runner.app_uid("p"), 10085)

    def test_any_other_pm_failure_is_the_case_failing(self):
        with patch.object(self.runner, "shell", side_effect=RuntimeError("adb: device offline")):
            with self.assertRaisesRegex(RuntimeError, "offline"):
                self.runner.app_uid("p")
