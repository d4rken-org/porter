import argparse
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch


spec = importlib.util.spec_from_file_location("boot_smoke", Path(__file__).with_name("boot-smoke.py"))
boot = importlib.util.module_from_spec(spec)
spec.loader.exec_module(boot)


def runner():
    directory = tempfile.TemporaryDirectory()
    smoke = boot.Boot(argparse.Namespace(serial="emulator-5554", output=Path(directory.name)))
    return smoke, directory


class RebootTest(unittest.TestCase):
    """The reboot has to be observed, not assumed: a dropped transport looks like a shutdown."""

    OLD = "11111111-1111-1111-1111-111111111111"
    NEW = "22222222-2222-2222-2222-222222222222"

    def setUp(self):
        self.smoke, directory = runner()
        self.addCleanup(directory.cleanup)

    def drive(self, answers):
        """Each answer is what one device query returns, oldest first."""
        remaining = list(answers)
        with patch.object(self.smoke, "adb") as adb, \
             patch.object(self.smoke, "shell", side_effect=lambda *a, **k: remaining.pop(0)):
            self.smoke.reboot()
        return adb, remaining

    def test_the_boot_that_answers_afterwards_has_to_be_a_different_one(self):
        # The empty answers are the transport being down. The old id after them is the boot that
        # was already running, which is exactly what must not count.
        adb, remaining = self.drive([self.OLD, "", self.OLD, self.NEW, "1"])
        self.assertEqual(remaining, [])
        adb.assert_called_once_with("reboot")

    def test_a_device_that_keeps_its_boot_id_never_rebooted(self):
        with patch.object(self.smoke, "adb"), \
             patch.object(self.smoke, "shell", return_value=self.OLD), \
             patch.object(boot, "BOOT_TIMEOUT", 0.2):
            with self.assertRaisesRegex(AssertionError, "new boot"):
                self.smoke.reboot()

    def test_a_reboot_that_never_comes_back_fails_rather_than_passing_quietly(self):
        answers = [self.OLD] + [""] * 10000
        with patch.object(self.smoke, "adb"), \
             patch.object(self.smoke, "shell", side_effect=lambda *a, **k: answers.pop(0)), \
             patch.object(boot, "BOOT_TIMEOUT", 0.2):
            with self.assertRaisesRegex(AssertionError, "new boot"):
                self.smoke.reboot()

    def test_a_device_that_cannot_be_asked_at_all_is_not_a_reboot(self):
        with patch.object(self.smoke, "adb"), patch.object(self.smoke, "shell", return_value=""):
            with self.assertRaisesRegex(AssertionError, "no boot id"):
                self.smoke.reboot()


class AbsenceTest(unittest.TestCase):
    """"No server" has to come back from the device, not from a command that failed to run."""

    def setUp(self):
        self.smoke, directory = runner()
        self.addCleanup(directory.cleanup)

    def test_the_device_says_there_is_none(self):
        with patch.object(self.smoke, "shell", return_value=boot.NO_PROCESS):
            self.assertTrue(self.smoke.no_server())

    def test_a_running_server_is_not_an_absence(self):
        with patch.object(self.smoke, "shell", return_value="4821"):
            self.assertFalse(self.smoke.no_server())

    def test_an_answer_that_is_neither_fails_rather_than_reading_as_absence(self):
        with patch.object(self.smoke, "shell", return_value="error: device offline"):
            with self.assertRaises(AssertionError):
                self.smoke.no_server()


class NotificationChannelTest(unittest.TestCase):
    def setUp(self):
        self.smoke, directory = runner()
        self.addCleanup(directory.cleanup)

    DUMP = "\n".join([
        "  NotificationRecord(0x1: pkg=com.example user=0)",
        "      android.text=Waiting to retry",
        "  NotificationRecord(0x2: pkg=eu.darken.porter user=0)",
        "      android.title=Starting Porter",
        "      android.text=Awaiting Wi-Fi connection before proceeding",
        "  NotificationRecord(0x3: pkg=eu.darken.porter.probe.native user=0)",
        "      android.text=something else",
    ])

    def test_a_record_carries_the_lines_underneath_it(self):
        with patch.object(self.smoke, "shell", return_value=self.DUMP):
            records = self.smoke.manager_notifications()
        self.assertEqual(len(records), 1)
        self.assertIn("Awaiting Wi-Fi", records[0])

    def test_another_package_s_retry_notice_is_not_the_manager_s(self):
        with patch.object(self.smoke, "shell", return_value=self.DUMP):
            records = self.smoke.manager_notifications()
        self.assertFalse(any(boot.RETRY_TEXT in record for record in records))

    def test_a_package_whose_name_starts_the_same_is_not_the_manager(self):
        with patch.object(self.smoke, "shell", return_value=self.DUMP):
            records = self.smoke.manager_notifications()
        self.assertFalse(any("something else" in record for record in records))

    def test_the_retry_notice_is_found_when_it_is_the_manager_s(self):
        dump = "\n".join([
            "  NotificationRecord(0x2: pkg=eu.darken.porter user=0)",
            "      android.text=" + boot.RETRY_TEXT,
        ])
        with patch.object(self.smoke, "shell", return_value=dump):
            records = self.smoke.manager_notifications()
        self.assertTrue(any(boot.RETRY_TEXT in record for record in records))


class CaseSelectionTest(unittest.TestCase):
    """A narrowed run must not assert against a device the cases it dropped would have built."""

    def parse(self, *cases):
        argv = ["--serial", "emulator-5554", "--manager", "m.apk", "--native", "n.apk",
                "--output", "out"]
        for case in cases:
            argv += ["--case", case]
        return boot.parse_args(argv)

    def test_the_full_run_needs_no_selection(self):
        self.assertIsNone(self.parse().cases)

    def test_setup_is_required(self):
        with self.assertRaises(SystemExit):
            self.parse("start-on-boot")

    def test_a_case_that_needs_an_earlier_one_says_so(self):
        with self.assertRaises(SystemExit):
            self.parse("setup", "start-on-boot")

    def test_the_last_case_does_not_need_the_one_before_it(self):
        # It sets the toggle it needs rather than inheriting whichever way a previous case left it.
        args = self.parse("setup", "app-adb-start", "start-on-boot", "boot-without-adb")
        self.assertNotIn("start-on-boot-off", args.cases)

    def test_the_whole_chain_is_accepted(self):
        args = self.parse("setup", "app-adb-start", "start-on-boot")
        self.assertEqual(args.cases, ["setup", "app-adb-start", "start-on-boot"])

    def test_every_declared_case_can_be_reached_through_its_requirements(self):
        for name in boot.CASES:
            needed = {name}
            while True:
                grown = needed.union(*(boot.REQUIRES.get(n, ()) for n in needed))
                if grown == needed:
                    break
                needed = grown
            self.assertTrue(needed.issubset(set(boot.CASES)), name)


if __name__ == "__main__":
    unittest.main()
