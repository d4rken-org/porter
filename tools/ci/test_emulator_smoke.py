import importlib.util
from pathlib import Path
import unittest
from unittest.mock import Mock, call, patch
import xml.etree.ElementTree as ET


spec = importlib.util.spec_from_file_location("emulator_smoke", Path(__file__).with_name("emulator-smoke.py"))
smoke = importlib.util.module_from_spec(spec)
spec.loader.exec_module(smoke)


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
