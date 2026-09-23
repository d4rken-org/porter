import argparse
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch


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
        "      android.text=something from another app",
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

    def test_a_package_whose_name_starts_the_same_is_not_the_manager(self):
        with patch.object(self.smoke, "shell", return_value=self.DUMP):
            records = self.smoke.manager_notifications()
        self.assertFalse(any("something else" in record for record in records))

    def test_a_dump_with_nothing_from_the_manager_reports_nothing(self):
        dump = "  NotificationRecord(0x1: pkg=com.example user=0)\n      android.text=hello"
        with patch.object(self.smoke, "shell", return_value=dump):
            self.assertEqual(self.smoke.manager_notifications(), [])


class ManagerJobsTest(unittest.TestCase):
    """Unfinished WorkManager work, as JobScheduler lists it for the manager."""

    def test_both_dump_formats_are_read(self):
        runner = boot.Boot.__new__(boot.Boot)
        runner.shell = Mock(return_value="\n".join((
            "Registered 3 jobs:",
            # Android 16, as dumped on an API 36 emulator.
            "  JOB androidx.work.systemjobscheduler:u0a216/2: 17e38cd "
            "@androidx.work.systemjobscheduler@eu.darken.porter/androidx.work.impl.background.systemjob.SystemJobService",
            # The older form.
            "  JOB #u0a216/3: 4b2c1b1 eu.darken.porter/androidx.work.impl.background.systemjob.SystemJobService",
            # A probe package whose name starts with the manager's.
            "  JOB #u0a217/1: 5c3d2e2 eu.darken.porter.probe.native/androidx.work.impl.background.systemjob.SystemJobService",
            "    Source: uid=u0a216 user=0 pkg=eu.darken.porter",
        )))
        self.assertEqual(len(runner.manager_jobs()), 2)


class CaseSelectionTest(unittest.TestCase):
    """A narrowed run must not assert against a device the cases it dropped would have built."""

    def parse(self, *cases):
        argv = ["--serial", "emulator-5554", "--manager", "m.apk", "--native", "n.apk",
                "--output", "out"]
        for case in cases:
            argv += ["--case", case]
        return boot.parse_args(argv)

    def test_the_full_run_is_every_case_but_the_opt_in_ones(self):
        cases = self.parse().cases
        self.assertEqual(cases[0], "setup")
        self.assertNotIn("wireless-pairing", cases)
        self.assertEqual(set(cases) | {"wireless-pairing"}, set(boot.CASES))

    def test_pairing_needs_only_setup(self):
        self.assertEqual(self.parse("setup", "wireless-pairing").cases, ["setup", "wireless-pairing"])

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

    def test_the_automation_case_needs_the_app_started_server(self):
        with self.assertRaises(SystemExit):
            self.parse("setup", "automation-broadcasts")
        self.assertEqual(self.parse("setup", "app-adb-start", "automation-broadcasts").cases,
                         ["setup", "app-adb-start", "automation-broadcasts"])

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
