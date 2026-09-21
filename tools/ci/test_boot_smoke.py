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
    """The reboot has to be observed, not assumed: the device answers as its old self for a while."""

    def setUp(self):
        self.smoke, directory = runner()
        self.addCleanup(directory.cleanup)

    def drive(self, answers):
        """Each answer is what one getprop returns, oldest first."""
        remaining = list(answers)
        with patch.object(self.smoke, "adb") as adb, \
             patch.object(self.smoke, "shell", side_effect=lambda *a, **k: remaining.pop(0)):
            self.smoke.reboot()
        return adb, remaining

    def test_a_device_still_answering_as_its_old_self_is_not_a_finished_reboot(self):
        # "1" twice: the old connection outlives the command. Only the empty answers are the
        # device actually going away, and only the "1" after those is the new boot.
        adb, remaining = self.drive(["1", "1", "", "", "1"])
        self.assertEqual(remaining, [])
        adb.assert_called_once_with("reboot")

    def test_a_reboot_that_never_comes_back_fails_rather_than_passing_quietly(self):
        with patch.object(self.smoke, "adb"), \
             patch.object(self.smoke, "shell", return_value=""), \
             patch.object(boot, "BOOT_TIMEOUT", 0.2):
            with self.assertRaisesRegex(AssertionError, "finished booting"):
                self.smoke.reboot()

    def test_a_device_that_never_goes_down_fails_rather_than_reading_the_old_boot(self):
        with patch.object(self.smoke, "adb"), \
             patch.object(self.smoke, "shell", return_value="1"), \
             patch.object(boot, "DOWN_TIMEOUT", 0.2):
            with self.assertRaisesRegex(AssertionError, "went down"):
                self.smoke.reboot()


class NotificationChannelTest(unittest.TestCase):
    def setUp(self):
        self.smoke, directory = runner()
        self.addCleanup(directory.cleanup)

    def test_only_the_manager_s_own_notifications_are_reported(self):
        dump = "\n".join([
            "  NotificationRecord(pkg=com.example channel=porter.adb_start)",
            "  NotificationRecord(pkg=eu.darken.porter channel=porter.adb_start)",
            "  NotificationRecord(pkg=eu.darken.porter channel=porter.watchdog)",
        ])
        with patch.object(self.smoke, "shell", return_value=dump):
            lines = self.smoke.manager_notification_channels()
        self.assertEqual(len(lines), 2)
        self.assertTrue(all("eu.darken.porter " not in line.split("pkg=")[0] for line in lines))
        self.assertTrue(any(boot.base.NOTIFICATION_CHANNEL_ADB_START in line for line in lines))


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
