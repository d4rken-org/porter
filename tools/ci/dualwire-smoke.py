#!/usr/bin/env python3
"""Exercise the Porter SDK's Shizuku wire, and its choice between the two, on an emulator."""
import argparse
import importlib.util
from pathlib import Path
import shlex
import time

spec = importlib.util.spec_from_file_location("porter_smoke", Path(__file__).with_name("emulator-smoke.py"))
base = importlib.util.module_from_spec(spec)
spec.loader.exec_module(base)

BRIDGE = "eu.darken.porter.probe.bridge"
UNLISTED = "eu.darken.porter.unlistedmanager"
SHIZUKU_PERMISSION = "moe.shizuku.manager.permission.API_V23"
# A binder that is coming arrives inside the launch window, so that is how long "none arrived" has
# to hold for. The activity's own mode line says only that the activity ran.
NO_BINDER_SETTLE = base.LAUNCH_TIMEOUT
# The order run() declares, which --case narrows without ever reordering.
CASES = ("setup", "visibility-listed-manager", "visibility-unlisted-manager",
         "shizuku-permission-lifecycle", "selection-prefers-porter", "selection-porter-stopped",
         "multiprocess-delivery-and-recovery")
# Each of these inherits installs and a running server from the case before it, so a narrowing
# that drops one leaves the next asserting against a fixture that was never built.
REQUIRES = {
    "selection-prefers-porter": ("shizuku-permission-lifecycle",),
    "selection-porter-stopped": ("selection-prefers-porter",),
    "multiprocess-delivery-and-recovery": ("shizuku-permission-lifecycle",),
}


def added(before, after):
    """What `after` holds that `before` did not; the log buffer drops from the front."""
    for cut in range(len(before) + 1):
        kept = before[cut:]
        if after[:len(kept)] == kept:
            return after[len(kept):]
    return after


class Dualwire(base.Smoke):
    def logs_for(self, pid):
        """One process's probe lines; logs() answers only for the last launched activity."""
        return self.adb("logcat", "-d", "--pid=" + pid, "-s", "PorterProbe:I", "*:S")

    def since(self, pid):
        """A boundary to read later lines against, so a reconnection is not matched by its first
        connection."""
        return self.logs_for(pid).splitlines()

    def fresh(self, pid, boundary, message):
        return self.until(f"{pid}: {message} after the boundary",
                          lambda: any(message in line
                                      for line in added(boundary, self.logs_for(pid).splitlines())))

    def launch_bridge(self, activity=".ProbeActivity", expect_binder=True):
        """A fresh bridge probe, which unlike the base's probes may be expected to get no binder."""
        command = ["am", "start", "-W", "-n", BRIDGE + "/eu.darken.porter.probe" + activity]
        for attempt in range(base.LAUNCH_ATTEMPTS):
            self.shell("am", "force-stop", BRIDGE)
            try:
                self.shell(*command, timeout=base.LAUNCH_TIMEOUT)
                self.probe_pid = self.until(
                    "one bridge probe process",
                    lambda: (pids := self.pid(BRIDGE).split()) and len(pids) == 1 and pids[0],
                    timeout=base.LAUNCH_TIMEOUT)
                self.expect_log(BRIDGE, "MODE daemon=false peek=false")
                break
            except (base.subprocess.TimeoutExpired, AssertionError):
                if attempt == base.LAUNCH_ATTEMPTS - 1:
                    raise
        # Outside the retry: the activity ran, so a binder that never arrives is a hand-over
        # failure rather than a lost launch, and relaunching would only hide it.
        if expect_binder:
            self.expect_log(BRIDGE, "BINDER uid=2000 version=")
            return
        time.sleep(NO_BINDER_SETTLE)
        assert BRIDGE + " BINDER uid=" not in self.logs()
        # A probe still waiting for a binder is a probe that will take one later: the next case to
        # start a server would push into it, and its permission dialog would answer for a process
        # this suite is no longer watching.
        self.shell("am", "force-stop", BRIDGE)

    # Only one package at a time may own the Shizuku permission, so an owner goes away before the
    # next case installs its own. The base's aspects put things back instead of removing them.
    REMOVALS = {"listed-manager": base.COMPAT, "unlisted-manager": UNLISTED}

    def restore(self, *aspects):
        for aspect in aspects:
            if aspect in self.REMOVALS:
                self.adb("uninstall", self.REMOVALS[aspect], check=False)
        super().restore(*(aspect for aspect in aspects if aspect not in self.REMOVALS))

    def setup(self):
        assert self.args.serial.startswith("emulator-"), "A disposable emulator serial is required"
        assert self.shell("getprop", "ro.kernel.qemu") == "1", "Refusing a physical device"
        assert self.shell("id", "-u") == "2000", "ADB must run as shell, not root"
        installed = set(self.shell("pm", "list", "packages").splitlines())
        assert not installed.intersection(
            "package:" + package for package in
            (base.MANAGER, base.COMPAT, base.NATIVE, base.LEGACY, BRIDGE, UNLISTED)), "Use a fresh emulator"
        self.adb("install", str(self.args.bridge.resolve()))
        # The app sandbox cannot read this file; the user service must run as the ADB shell.
        self.shell("sh", "-c", f"printf %s {shlex.quote(base.PAYLOAD)} > /data/local/tmp/porter-probe.txt;"
                               " chmod 600 /data/local/tmp/porter-probe.txt")
        # No manager and no server: nothing owns either permission yet.
        self.launch_bridge(expect_binder=False)
        self.expect_log(BRIDGE, "AVAILABILITY NOT_INSTALLED")

    def run(self):
        self.case("setup", self.setup)

        def visibility_listed_manager():
            """The permission's owner is the manager the SDK's queries block names."""
            self.adb("install", str(self.args.compat.resolve()))
            # Deliberately no server: this is about who owns the permission, not who answers.
            self.launch_bridge(expect_binder=False)
            self.expect_log(BRIDGE, "AVAILABILITY INSTALLED_NOT_CONNECTED")
        self.case("visibility-listed-manager", visibility_listed_manager, restore=("listed-manager",))

        def visibility_unlisted_manager():
            """A Shizuku-compatible manager published under some other package name."""
            self.adb("install", str(self.args.unlisted.resolve()))
            self.launch_bridge(expect_binder=False)
            # Not NOT_INSTALLED: the lookup behind this is a bare getPermissionInfo(permission, 0)
            # in PorterSession.permissionOwner, and on API 30 the platform answers it with an
            # instant-app caller check and a permission-registry lookup, with no visibility
            # filtering. The SDK's javadoc and README claim the opposite; that documentation defect
            # is a follow-up against the api submodule and is deliberately not fixed here. The API
            # 36 implementation could not be read, so this level is inferred from API 30's and from
            # the client API stating no filtering; if API 36 disagrees, this case is where it shows.
            self.expect_log(BRIDGE, "AVAILABILITY INSTALLED_UNRECOGNIZED")
        self.case("visibility-unlisted-manager", visibility_unlisted_manager,
                  restore=("unlisted-manager",))

        def shizuku_permission_lifecycle():
            """The SDK speaking Shizuku's protocol to a real Shizuku server, end to end.

            What a device cannot isolate is the SDK synthesizing a permission-state push into its
            cached state: nothing here distinguishes that from the SDK querying and being told the
            same answer. ShizukuProtocolWirePermissionStateTest and RedF4RevokedPermissionTest
            cover it.
            """
            self.adb("install", str(self.args.shizuku.resolve()))
            self.start_service(base.COMPAT)
            self.launch_bridge()
            self.tap("Deny", base.COMPAT)
            self.expect_log(BRIDGE, "DENIED")
            assert BRIDGE + " USER_SERVICE uid=" not in self.logs()
            self.launch_bridge()
            self.tap("Allow all the time", base.COMPAT, screenshot="bridge-permission")
            self.expect_log(BRIDGE, f"BINDER uid=2000 version={base.SHIZUKU_API_VERSION}")
            self.expect_log(BRIDGE, "BACKEND SHIZUKU")
            # The original Shizuku server does not enforce Porter's manager-only gate.
            self.authorized(BRIDGE, require_manager_guard=False)
            self.until("privileged user service",
                       lambda: self.pid(BRIDGE + ":porter-probe"))
            self.shell("pm", "revoke", BRIDGE, SHIZUKU_PERMISSION)
            # Upstream Shizuku records its own per-uid authorization, granted here with
            # onetime=false, and does not watch Android's permission state, so revoking the Android
            # permission leaves the app authorized. Porter's own server does honour a revoke, which
            # is why the base suite's grant_and_revoke asserts the opposite on the Porter wire. This
            # pins the difference rather than the behaviour we would prefer.
            self.launch_bridge()
            self.expect_log(BRIDGE, "AUTHORIZED managerOperationDenied=")
            return {"shizuku_pid": self.pid("shizuku_server")}
        self.case("shizuku-permission-lifecycle", shizuku_permission_lifecycle)

        def selection_prefers_porter():
            """A Shizuku binder is pushed at this process too, and Porter's is the one it takes."""
            # Without a second server there is no choice to observe, whether a narrowing dropped
            # the case that starts it or that server died earlier in a full run.
            shizuku_pid = self.pid("shizuku_server")
            assert shizuku_pid, "no Shizuku server for Porter to be preferred over"
            self.adb("install", str(self.args.manager.resolve()))
            self.shell("am", "start", "-W", "-f", "0x04000000", "-n",
                       base.MANAGER + "/eu.darken.porter.manager.MainActivity")
            self.start_service()
            self.launch_bridge()
            self.expect_log(BRIDGE, "BACKEND PORTER")
            self.expect_log(BRIDGE, f"BINDER uid=2000 version={base.PORTER_PROTOCOL_VERSION}")
            return {"porter_pid": self.pid("porter_server"), "shizuku_pid": shizuku_pid}
        # The Porter manager and its server stay up for the next case, which needs both: an
        # uninstall here would leave a server outliving its package for a scan period.
        self.case("selection-prefers-porter", selection_prefers_porter)

        def selection_porter_stopped():
            """An installed Porter is selected even with its service stopped, so the Shizuku binder
            still being pushed is refused rather than silently substituted."""
            self.stop_porter()
            self.launch_bridge(expect_binder=False)
            self.expect_log(BRIDGE, "AVAILABILITY INSTALLED_NOT_CONNECTED")
            self.adb("uninstall", base.MANAGER)
            assert not self.installed(base.MANAGER), "the Porter manager survived its uninstall"
            # Not restarted: removing Porter does not stop Shizuku, and a restart here would hide
            # it if that stopped being true.
            shizuku_pid = self.pid("shizuku_server")
            assert shizuku_pid, "removing Porter stopped Shizuku"
            return {"shizuku_pid": shizuku_pid}
        self.case("selection-porter-stopped", selection_porter_stopped)

        def multiprocess_delivery_and_recovery():
            """Delivery into a process the server never reached, and both processes recovering.

            The dispatch ordering between a process's death listeners and the replacement its
            post-death hook fetches is not asserted here: observing it needs a dispatch held open
            from another thread, which RedDeathDispatchOrderingTest, RedDeathBeforeReconnectTest
            and RedDeathDispatchOrderingHandlerTest do and a device cannot without a hook in
            production code.
            """
            assert not self.installed(base.MANAGER), "Porter is installed, so this process would select it rather than Shizuku"
            self.launch_bridge()
            self.expect_log(BRIDGE, "BACKEND SHIZUKU")
            main_pid = self.probe_pid
            # No force-stop: the binder this asserts on is the one the provider process already
            # holds, and stopping the package would leave a server delivery to explain it instead.
            self.shell("am", "start", "-W", "-n",
                       BRIDGE + "/eu.darken.porter.probe.SecondaryActivity")
            secondary_pid = self.until("secondary probe process",
                                       lambda: self.pid(BRIDGE + ":secondary"))
            self.until("secondary fetched the binder from the provider",
                       lambda: BRIDGE + " SECONDARY BINDER uid=2000 backend=SHIZUKU"
                       in self.logs_for(secondary_pid))

            boundaries = {pid: self.since(pid) for pid in (main_pid, secondary_pid)}
            server_pid = self.pid("shizuku_server")
            self.shell("kill", "-9", server_pid)
            self.until("server exited", lambda: not self.pid("shizuku_server"))
            self.fresh(main_pid, boundaries[main_pid], BRIDGE + " BINDER_DEAD")
            self.fresh(secondary_pid, boundaries[secondary_pid], BRIDGE + " SECONDARY BINDER_DEAD")
            assert self.pid(BRIDGE) == main_pid, "the main process did not outlive the server"
            assert self.pid(BRIDGE + ":secondary") == secondary_pid, "the secondary process did not outlive the server"

            boundaries = {pid: self.since(pid) for pid in (main_pid, secondary_pid)}
            # No relaunch: a relaunch force-stops the package and turns this into two fresh
            # connections, which would pass with receiver registration or the refetch hook broken.
            self.start_service(base.COMPAT)
            self.fresh(main_pid, boundaries[main_pid], BRIDGE + " BINDER uid=2000 version=")
            self.fresh(secondary_pid, boundaries[secondary_pid],
                       BRIDGE + " SECONDARY BINDER uid=2000 backend=SHIZUKU")
            assert self.pid(BRIDGE) == main_pid, "the main process was replaced on reconnection"
            assert self.pid(BRIDGE + ":secondary") == secondary_pid, "the secondary process was replaced on reconnection"
            assert BRIDGE + " SECONDARY FAILED" not in self.logs_for(secondary_pid)
            return {"main_pid": main_pid, "secondary_pid": secondary_pid,
                    "killed_server_pid": server_pid, "server_pid": self.pid("shizuku_server")}
        self.case("multiprocess-delivery-and-recovery", multiprocess_delivery_and_recovery)


def parse_args(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    for name in ("manager", "compat", "native", "legacy", "shizuku", "bridge", "unlisted"):
        parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--case", action="append", dest="cases", choices=CASES, metavar="NAME",
                        help="run only the named case, repeatable, in declared order; "
                             "omit to run all of: " + ", ".join(CASES))
    args = parser.parse_args(argv)
    if args.cases and "setup" not in args.cases:
        parser.error("--case setup is required: every other case needs the install and the "
                     "payload file it performs")
    if args.cases:
        needed = set(args.cases)
        while True:
            grown = needed.union(*(REQUIRES.get(name, ()) for name in needed))
            if grown == needed:
                break
            needed = grown
        missing = [name for name in CASES if name in needed and name not in args.cases]
        if missing:
            parser.error("--case " + " --case ".join(missing) + " is required: the selected cases "
                         "assert against installs and servers those build")
    # The inverse of REQUIRES: a case whose side effects a later one cannot tolerate until a third
    # undoes them. The test below keys on position, "any case declared after
    # selection-porter-stopped", while the actual hazard is "cannot tolerate Porter being
    # installed". Those coincide only because the recovery case is the one case declared later: a
    # Porter-agnostic case appended after it would be rejected with no cause, and a
    # Porter-intolerant case inserted before selection-porter-stopped would not be caught at all.
    # Generalising waits until a second such case exists to generalise from.
    if args.cases:
        later = CASES[CASES.index("selection-porter-stopped") + 1:]
        if ("selection-prefers-porter" in args.cases
                and any(name in args.cases for name in later)
                and "selection-porter-stopped" not in args.cases):
            parser.error("--case selection-porter-stopped is required: selection-prefers-porter "
                         "leaves the Porter manager installed and its server running, and only "
                         "selection-porter-stopped removes them")
    return args


if __name__ == "__main__":
    smoke = Dualwire(parse_args())
    try:
        smoke.run()
    finally:
        smoke.reports()
