import contextlib
import importlib.util
import io
from pathlib import Path
import unittest


spec = importlib.util.spec_from_file_location("compat_install_smoke", Path(__file__).with_name("compat-install-smoke.py"))
compat = importlib.util.module_from_spec(spec)
spec.loader.exec_module(compat)


class ArgumentTest(unittest.TestCase):
    REQUIRED = ["--serial", "emulator-5554", "--output", "build/emulator-results",
                "--manager", "manager.apk", "--compat", "compat.apk", "--native", "native.apk",
                "--legacy", "legacy.apk", "--shizuku", "shizuku.apk", "--nightzuku", "nightzuku.apk"]

    def rejected(self, argv):
        stderr = io.StringIO()
        with self.assertRaises(SystemExit), contextlib.redirect_stderr(stderr):
            compat.parse_args(argv)
        return stderr.getvalue()

    def test_the_shared_workflow_arguments_and_the_fork_are_both_accepted(self):
        args = compat.parse_args(self.REQUIRED)
        self.assertEqual((args.shizuku, args.nightzuku), (Path("shizuku.apk"), Path("nightzuku.apk")))
        self.assertFalse(args.dpad)
        self.assertFalse(args.release)

    def test_the_tv_and_release_flags_are_accepted(self):
        args = compat.parse_args(self.REQUIRED + ["--dpad", "--release"])
        self.assertTrue(args.dpad)
        self.assertTrue(args.release)

    def test_an_omitted_fork_is_rejected(self):
        self.assertIn("--nightzuku", self.rejected(self.REQUIRED[:-2]))

    def test_an_unknown_argument_is_rejected(self):
        self.assertIn("--bridge", self.rejected(self.REQUIRED + ["--bridge", "bridge.apk"]))


if __name__ == "__main__":
    unittest.main()
