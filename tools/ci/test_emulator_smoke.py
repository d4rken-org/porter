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
