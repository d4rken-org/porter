#!/usr/bin/env python3
"""Replace a running Porter service of another build with the installed one, as the manager does."""
import argparse
import importlib.util
from pathlib import Path
import shlex

spec = importlib.util.spec_from_file_location("porter_smoke", Path(__file__).with_name("emulator-smoke.py"))
base = importlib.util.module_from_spec(spec)
spec.loader.exec_module(base)

FIXTURE = "eu.darken.porter.updatefixture"
# The manager waits 60 seconds for a successor before it calls a replacement failed; this outlasts it.
HANDOFF_TIMEOUT = 90
# From the tap to a successor that sent its binders: a native starter, an app_process start and a
# binder push, with a margin for a software-rendered emulator.
REPLACEMENT_TIMEOUT = 90
# The order run() declares, which --case narrows without ever reordering. The automatic update
# goes first: the manager skips one for a build it already holds a record of, and every manual
# case writes a record for the same installed build.
CASES = ("setup", "automatic-after-reinstall", "automatic-on-connection", "manual-update",
         "preflight-rejected", "handoff-failure", "root-update")


class Update(base.Smoke):

    def __init__(self, args):
        super().__init__(args)
        self.fixture_session = None

    def setup(self):
        assert self.args.serial.startswith("emulator-"), "A disposable emulator serial is required"
        assert self.shell("getprop", "ro.kernel.qemu") == "1", "Refusing a physical device"
        assert self.shell("id", "-u") == "2000", "ADB must run as shell, not root"
        installed = set(self.shell("pm", "list", "packages").splitlines())
        assert not installed.intersection("package:" + p for p in (base.MANAGER, FIXTURE)), "Use a fresh emulator"
        for apk in (self.args.manager, self.args.fixture):
            self.adb("install", str(apk.resolve()))
        self.home()
        assert not self.pid("porter_server"), "a server was running before the fixture was started"

    def home(self):
        self.shell("am", "start", "-W", "-f", "0x04000000", "-n",
                   base.MANAGER + "/eu.darken.porter.manager.MainActivity")

    def start_fixture(self, mode, root=False, verify=True):
        """A porter_server of another build, started the way the fixture's README describes.

        It loads its natives from the installed manager, as the real service does, and reaches the
        manager by pushing its binder, so the manager sees it as the service that is running.
        """
        assert not self.pid("porter_server"), "another server is still running"
        apk = self.shell("pm", "path", FIXTURE).removeprefix("package:").splitlines()[0]
        library_dir = str(Path(self.starter_binary(base.MANAGER)).parent)
        command = (f"CLASSPATH={shlex.quote(apk)} app_process -Dporter.library.path={shlex.quote(library_dir)}"
                   f" /system/bin --nice-name=porter_server eu.darken.porter.updatefixture.FixtureService"
                   f" {mode} </dev/null >/dev/null 2>&1")
        # Held open for the fixture's lifetime, so nothing depends on how adbd treats a background
        # process when its session ends.
        self.fixture_session = self.detached(*(("su", "0", "sh", "-c", command) if root else ("sh", "-c", command)))
        pid = self.until("the fixture is running", lambda: self.pid("porter_server"))
        if verify:
            self.until("the fixture sent its binders", lambda: "sent binders" in self.server_log(pid))
            assert self.classpath(pid, root) == apk, "the running porter_server is not the fixture"
        return pid

    def opt_into_automatic_updates(self):
        settings = f"/data/user_de/0/{base.MANAGER}/shared_prefs/settings.xml"
        enabled = 'name="auto_update_service" value="true"'
        if enabled in self.shell("su", "0", "cat", settings, check=False):
            return
        self.home()
        self.tap("Settings", desc="Settings")
        self.tap("Update service automatically", scroll=True, screenshot="auto-update-setting")
        # The switch moves before the write lands, and what follows restarts the app.
        self.until("the app saved the setting",
                   lambda: enabled in self.shell("su", "0", "cat", settings, check=False), timeout=60)

    def classpath(self, pid, root=False):
        """The APK a porter_server runs from: the fixture's for the fixture, the manager's for a
        successor the real starter launched."""
        read = f"tr '\\0' '\\n' < /proc/{pid}/environ | sed -n 's/^CLASSPATH=//p'"
        return self.shell(*(("su", "0") if root else ()), "sh", "-c", read, check=False)

    def uid_of(self, pid):
        return self.shell("su", "0", "stat", "-c", "%u", f"/proc/{pid}", check=False)

    def manager_apk(self):
        return self.shell("pm", "path", base.MANAGER).removeprefix("package:").splitlines()[0]

    def service_screen(self):
        self.home()
        # The status card opens it; stop_porter() reaches the same screen the same way.
        self.tap("Porter is running", prefix=True)

    def update_from_service_screen(self):
        self.service_screen()
        # An earlier failed attempt for the same installed build turns the offer into a retry.
        action = self.until("the service screen offers an update", lambda: (
            "Retry update" if self.locate("Retry update", scroll=True) else
            "Update service" if self.locate("Service update available") else None))
        self.tap(action, scroll=True)
        # The confirmation repeats the action as its title, so its button is the second match.
        self.tap(action, occurrence=1, screenshot="update-confirmation")

    def successor(self, old, root=False):
        """The replacement for server pid `old`: a new process running the manager's build."""
        new = self.until("a replacement server", lambda: (pid := self.pid("porter_server")) and pid != old and pid,
                         timeout=REPLACEMENT_TIMEOUT)
        self.until("the replacement sent its binders", lambda: "sent binders" in self.server_log(new),
                   timeout=REPLACEMENT_TIMEOUT)
        assert self.classpath(new, root) == self.manager_apk(), "the replacement does not run the installed build"
        return new

    def replacement_requests(self, pid):
        """The launches the server at pid was asked for, as its core logs them, which the
        fixture's failure modes have already rewritten by then."""
        log = self.adb("logcat", "-d", "--pid=" + pid, "-s", "PorterCore:D", "*:S")
        return [line for line in log.splitlines() if "newProcess:" in line]

    def restore(self, *aspects):
        if "servers" in aspects:
            self.kill_server(check=False)
            self.until("no server is left", lambda: not self.pid("porter_server"))
            if self.fixture_session is not None:
                if self.fixture_session.poll() is None:
                    self.fixture_session.kill()
                self.fixture_session.communicate()
                self.fixture_session = None
        rest = tuple(aspect for aspect in aspects if aspect != "servers")
        if rest:
            super().restore(*rest)

    def run(self):
        self.case("setup", self.setup)

        def automatic_after_reinstall():
            """An app update replacing the service in the background, once the user opted in."""
            # Started before the opt-in: with it on, the fixture's first connection would already be
            # replaced, and this case is about the update that follows reinstalling the app.
            old = self.start_fixture("outdated")
            self.opt_into_automatic_updates()
            assert self.pid("porter_server") == old, "the service was replaced before the app was updated"
            # In the background, as an update from a store arrives.
            self.shell("input", "keyevent", "KEYCODE_HOME")
            self.adb("install", "-r", str(self.args.manager.resolve()))
            new = self.successor(old)
            assert self.uid_of(new) == "2000", "the replacement changed privileges"
            return {"fixture": old, "successor": new}
        self.case("automatic-after-reinstall", automatic_after_reinstall, restore=("servers",))

        def automatic_on_connection():
            """An outdated service's connection alone starts the automatic update, for an app that
            never received MY_PACKAGE_REPLACED."""
            self.opt_into_automatic_updates()
            # The record of the previous case's update spends this build's automatic attempt.
            self.shell("am", "force-stop", base.MANAGER)
            self.shell("su", "0", "rm", "-f", f"/data/user_de/0/{base.MANAGER}/shared_prefs/service-update.xml")
            # Not verified as the fixture: the manager may replace it before a check could run.
            old = self.start_fixture("outdated", verify=False)
            new = self.successor(old)
            assert self.uid_of(new) == "2000", "the replacement changed privileges"
            return {"fixture": old, "successor": new}
        self.case("automatic-on-connection", automatic_on_connection, restore=("servers",))

        def manual_update():
            """The Service screen's update, from a shell-started service of another build."""
            old = self.start_fixture("outdated")
            self.update_from_service_screen()
            new = self.successor(old)
            assert self.uid_of(new) == "2000", "the replacement changed privileges"
            self.until("the service screen reports the installed build",
                       lambda: self.locate("Everything is working normally."))
            return {"fixture": old, "successor": new}
        self.case("manual-update", manual_update, restore=("servers",))

        def preflight_rejected():
            """A replacement command the native starter refuses, which must leave the service alone."""
            old = self.start_fixture("preflight")
            self.update_from_service_screen()
            # The screen may still show a failure from an earlier case; this request is this one's.
            self.until("the fixture was asked for the refused launch",
                       lambda: any("--replace=1" in line for line in self.replacement_requests(old)))
            self.until("the refused update is reported",
                       lambda: self.locate("The previous update did not finish. The service is still running.",
                                           prefix=True))
            assert self.pid("porter_server") == old, "a refused replacement touched the running service"
            assert self.locate("Retry update", scroll=True), "a still-running service offers no retry"
            return {"fixture": old}
        self.case("preflight-rejected", preflight_rejected, restore=("servers",))

        def handoff_failure():
            """A service that dies after accepting the replacement, with nothing started in its place."""
            old = self.start_fixture("handoff-failure")
            self.update_from_service_screen()
            # What makes its death the handoff: the screen may still show an earlier case's failure.
            self.until("the fixture accepted the replacement",
                       lambda: any(f"kill -9 {old}" in line for line in self.replacement_requests(old)))
            self.until("the fixture went away", lambda: not self.pid("porter_server"))
            # Only the manager's own deadline ends the wait for a successor that is never coming.
            self.until("the lost service is reported",
                       lambda: self.locate("The previous update did not finish. The service is stopped.",
                                           prefix=True),
                       timeout=HANDOFF_TIMEOUT)
            assert not self.pid("porter_server"), "a server appeared after the failed handoff"
            return {"fixture": old}
        self.case("handoff-failure", handoff_failure, restore=("servers",))

        def root_update():
            """A root-started service, whose replacement has to keep running as root."""
            assert self.root_available(), "this image's su does not give the ADB shell root"
            old = self.start_fixture("outdated", root=True)
            assert self.uid_of(old) == "0", "the fixture did not start as root"
            self.update_from_service_screen()
            new = self.successor(old, root=True)
            assert self.uid_of(new) == "0", "the replacement lost root"
            return {"fixture": old, "successor": new}
        self.case("root-update", root_update, restore=("servers",))


def parse_args(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    # The workflow hands every suite the same APKs; this one uses the manager and the fixture.
    for name in ("manager", "compat", "native", "legacy", "shizuku", "fixture"):
        parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--case", action="append", dest="cases", choices=CASES, metavar="NAME",
                        help="run only the named case, repeatable, in declared order; "
                             "omit to run all of: " + ", ".join(CASES))
    args = parser.parse_args(argv)
    if args.cases and "setup" not in args.cases:
        parser.error("--case setup is required: every other case needs the installs it performs")
    return args


if __name__ == "__main__":
    smoke = Update(parse_args())
    try:
        smoke.run()
    finally:
        if smoke.fixture_session is not None and smoke.fixture_session.poll() is None:
            smoke.fixture_session.kill()
        smoke.reports()
