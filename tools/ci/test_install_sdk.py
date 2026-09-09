import importlib.util
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch


spec = importlib.util.spec_from_file_location("install_sdk", Path(__file__).with_name("install-sdk.py"))
installer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(installer)


class InstallSdkTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.sdk = Path(self.directory.name)

    @patch.object(installer.time, "sleep")
    @patch.object(installer.subprocess, "run")
    def test_success_does_not_retry(self, run, sleep):
        run.return_value = subprocess.CompletedProcess([], 0)
        self.assertEqual(0, installer.install(self.sdk, ["platforms;android-30", "emulator"]))
        run.assert_called_once_with([
            "sdkmanager", f"--sdk_root={self.sdk}", "--install", "platforms;android-30", "emulator",
        ])
        sleep.assert_not_called()

    @patch.object(installer.time, "sleep")
    @patch.object(installer.subprocess, "run")
    def test_bad_archive_is_removed_without_touching_other_operations_or_installed_packages(self, run, sleep):
        """Model an interrupted installer leaving temporary downloads behind."""
        old = self.sdk / ".temp/PackageOperation01"
        old.mkdir(parents=True)
        (old / "keep.zip").write_text("another operation")
        installed = self.sdk / "platform-tools/adb"
        installed.parent.mkdir()
        installed.write_text("installed tool")
        operation = self.sdk / ".temp/PackageOperation02"

        def attempt(command):
            if run.call_count == 1:
                operation.mkdir()
                (operation / "download.zip").write_text("invalid archive")
                (operation / "unzip").mkdir()
                (operation / "unzip/partial").write_text("incomplete")
                (operation / "linked-sdk").symlink_to(installed.parent, target_is_directory=True)
                return subprocess.CompletedProcess(command, 1)
            self.assertTrue(operation.is_dir())
            self.assertEqual([], list(operation.iterdir()))
            self.assertEqual("another operation", (old / "keep.zip").read_text())
            self.assertEqual("installed tool", installed.read_text())
            return subprocess.CompletedProcess(command, 0)

        run.side_effect = attempt
        self.assertEqual(0, installer.install(self.sdk, ["emulator"]))
        self.assertEqual(2, run.call_count)
        sleep.assert_called_once_with(10)

    @patch.object(installer.time, "sleep")
    @patch.object(installer.subprocess, "run")
    def test_three_failures_preserve_exit_code_and_last_attempt_evidence(self, run, sleep):
        """Model repeated interruptions leaving temporary downloads behind."""
        operation = self.sdk / ".temp/PackageOperation01"

        def attempt(command):
            operation.mkdir(parents=True, exist_ok=True)
            self.assertFalse((operation / "download.zip").exists())
            (operation / "download.zip").write_text("invalid archive")
            return subprocess.CompletedProcess(command, 7)

        run.side_effect = attempt
        self.assertEqual(7, installer.install(self.sdk, ["emulator"]))
        self.assertEqual(3, run.call_count)
        self.assertEqual([10, 20], [call.args[0] for call in sleep.call_args_list])
        self.assertEqual("invalid archive", (operation / "download.zip").read_text())


if __name__ == "__main__":
    unittest.main()
