import argparse
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch
import xml.etree.ElementTree as ET


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


class StartedByManagerTest(unittest.TestCase):
    def setUp(self):
        self.smoke, directory = runner()
        self.addCleanup(directory.cleanup)
        self.smoke.pid = Mock(return_value="4711")

    def test_a_debug_build_is_read_from_its_adb_client_log(self):
        self.smoke.adb = Mock(return_value="D AdbClient: Handshake succeeded.")
        self.smoke.started_by_manager()
        self.smoke.adb.assert_called_once_with("logcat", "-d", "--pid=4711", "-s", "AdbClient:D", "*:S")

    def test_a_release_build_is_not_asked_for_a_log_it_cannot_write(self):
        self.smoke.args.release = True
        self.smoke.adb = Mock(return_value="")
        self.smoke.started_by_manager()
        self.smoke.adb.assert_not_called()


class NotificationTapTest(unittest.TestCase):
    def setUp(self):
        self.smoke, directory = runner()
        self.addCleanup(directory.cleanup)
        self.smoke.shell = Mock()
        self.shade = ET.fromstring(f'''<hierarchy><node package="{boot.SYSTEMUI}"
            resource-id="{boot.SYSTEMUI}:id/expandableNotificationRow">
            <node text="Porter" package="{boot.SYSTEMUI}" />
            <node text="Start" package="{boot.SYSTEMUI}" enabled="true" bounds="[100,500][300,600]" />
            </node></hierarchy>''')
        patcher = patch.object(boot.base, "TAP_SETTLE", 0)
        patcher.start()
        self.addCleanup(patcher.stop)

    @patch.object(boot.base.subprocess, "run", return_value=boot.base.subprocess.CompletedProcess(["adb"], 0, b"", b""))
    def test_an_unanswered_action_leaves_evidence_for_every_attempt(self, run):
        self.smoke.ui = Mock(return_value=self.shade)
        with self.assertRaisesRegex(AssertionError, "'Start' in 'Porter' 3 times.*unanswered-tap-3.txt"):
            self.smoke.tap_notification_action("Porter", "Start")
        self.assertEqual(run.call_count, boot.base.TAP_ATTEMPTS)
        self.assertIn("'Start' in 'Porter' at 200,550: screen unchanged",
                      (self.smoke.output / "unanswered-tap-1.txt").read_text())


class ResumedComponentsTest(unittest.TestCase):
    """What the home-start case reads to see the starter screen open and close by itself."""
    STARTER = "ActivityRecord{2b0e4d2 u0 eu.darken.porter/.manager.starter.StarterActivity t15}"
    HOME = "ActivityRecord{7f0a2c4 u0 eu.darken.porter/.manager.MainActivity t15}"

    def activities(self, resumed):
        return ("ACTIVITY MANAGER ACTIVITIES (dumpsys activity activities)\n"
                f"    * Hist #1: {self.STARTER}\n"
                f"    * Hist #0: {self.HOME}\n"
                f"  mLastPausedActivity: {self.STARTER}\n"
                f"{resumed}")

    def test_later_releases_name_the_resumed_activity(self):
        self.assertEqual(boot.resumed_components(self.activities(
            f"  topResumedActivity={self.STARTER}\n    ResumedActivity: {self.STARTER}\n")),
            {boot.STARTER_RECORD})

    def test_android_7_names_it_too(self):
        self.assertEqual(boot.resumed_components(self.activities(f"  mResumedActivity: {self.HOME}\n")),
                         {boot.base.HOME_RECORD})

    def test_history_and_paused_records_are_not_resumed(self):
        self.assertEqual(boot.resumed_components(self.activities(f"  topResumedActivity={self.HOME}\n")),
                         {boot.base.HOME_RECORD})

    def test_nothing_resumed_is_nothing(self):
        self.assertEqual(boot.resumed_components(self.activities("  mResumedActivity: null\n")), set())
        self.assertEqual(boot.resumed_components(""), set())


class SwitchedOnTest(unittest.TestCase):
    KEY = "wireless_debugging_off"

    def prefs(self, *entries):
        return ("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n"
                + "".join(f"    {entry}\n" for entry in entries) + "</map>")

    def test_true_is_on(self):
        self.assertTrue(boot.switched_on(self.prefs(f'<boolean name="{self.KEY}" value="true" />'), self.KEY))

    def test_false_is_off(self):
        self.assertFalse(boot.switched_on(self.prefs(f'<boolean name="{self.KEY}" value="false" />'), self.KEY))

    def test_never_written_is_off(self):
        self.assertFalse(boot.switched_on(self.prefs('<string name="adb_key">secret</string>'), self.KEY))

    def test_another_key_that_is_on_says_nothing_about_this_one(self):
        self.assertFalse(boot.switched_on(self.prefs('<boolean name="start_on_boot" value="true" />'), self.KEY))


class SecureSettingsTest(unittest.TestCase):
    """The home-start case's revocation has to take, or it would start with the permission held."""
    GRANTED = "    runtime permissions:\n      android.permission.WRITE_SECURE_SETTINGS: granted=true, flags=[ ]"
    REVOKED = "    runtime permissions:\n      android.permission.WRITE_SECURE_SETTINGS: granted=false, flags=[ ]"

    def setUp(self):
        self.smoke, directory = runner()
        self.addCleanup(directory.cleanup)

    def drive(self, dumps):
        """Each dump is what one dumpsys package returns, oldest first; pm answers with nothing."""
        remaining = list(dumps)
        self.smoke.shell = Mock(side_effect=lambda *a, **k: remaining.pop(0) if a[0] == "dumpsys" else "")
        self.smoke.revoke_secure_settings()

    def test_a_revocation_that_took(self):
        self.drive([self.GRANTED, self.REVOKED])
        self.smoke.shell.assert_any_call("pm", "revoke", boot.base.MANAGER, boot.SECURE_SETTINGS)

    def test_a_revocation_that_changed_nothing_fails(self):
        with self.assertRaisesRegex(AssertionError, "left .* granted"):
            self.drive([self.GRANTED, self.GRANTED])

    def test_a_dump_that_never_shows_the_grant_is_not_a_revocation(self):
        with self.assertRaisesRegex(AssertionError, "does not hold"):
            self.drive([self.REVOKED, self.REVOKED])
        self.assertNotIn(("pm", "revoke", boot.base.MANAGER, boot.SECURE_SETTINGS),
                         [c.args for c in self.smoke.shell.call_args_list])


class WirelessDebuggingOffRestoreTest(unittest.TestCase):
    def setUp(self):
        self.smoke, directory = runner()
        self.addCleanup(directory.cleanup)

    def test_the_switch_and_the_grant_come_back_before_the_manager_is_stopped(self):
        steps = []
        self.smoke.shell = Mock(side_effect=lambda *a, **k: steps.append(a) or boot.NO_PROCESS)
        self.smoke.adb = Mock(return_value=b"")
        self.smoke.set_wireless_debugging_off = Mock(side_effect=lambda enable: steps.append(("switch", enable)))
        with patch.object(boot.base.Smoke, "restore") as inherited:
            self.smoke.restore("wireless-debugging-off", "wireless-debugging")
        inherited.assert_not_called()
        stop = steps.index(("am", "force-stop", boot.base.MANAGER))
        self.assertLess(steps.index(("pm", "grant", boot.base.MANAGER, boot.SECURE_SETTINGS)), stop)
        self.assertLess(steps.index(("switch", False)), stop)
        self.assertIn(("settings", "put", "global", "adb_wifi_enabled", "0"), steps)


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
        for name in boot.OPT_IN:
            self.assertNotIn(name, cases)
        self.assertEqual(set(cases) | set(boot.OPT_IN), set(boot.CASES))

    def test_the_wireless_debugging_off_cases_run_only_when_named(self):
        for name in ("wireless-off-home-start", "wireless-off-background-start"):
            self.assertIn(name, boot.OPT_IN)
            self.assertEqual(boot.REQUIRES[name], ("wireless-pairing",))

    def test_the_wireless_debugging_off_cases_run_after_pairing_in_declared_order(self):
        cases = ("setup", "wireless-pairing", "wireless-off-home-start", "wireless-off-background-start")
        self.assertEqual(self.parse(*cases).cases, list(cases))
        self.assertEqual([name for name in boot.CASES if name in cases], list(cases))

    def test_a_wireless_debugging_off_case_needs_the_pairing(self):
        for name in ("wireless-off-home-start", "wireless-off-background-start"):
            with self.assertRaises(SystemExit):
                self.parse("setup", name)

    def test_the_release_flag_is_accepted(self):
        self.assertFalse(self.parse().release)
        argv = ["--serial", "emulator-5554", "--manager", "m.apk", "--native", "n.apk",
                "--output", "out", "--release"]
        self.assertTrue(boot.parse_args(argv).release)

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
