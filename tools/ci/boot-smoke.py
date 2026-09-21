#!/usr/bin/env python3
"""Start Porter the way a user does, from the app itself, and make it survive a reboot."""
import argparse
import importlib.util
from pathlib import Path
import shlex
import time

spec = importlib.util.spec_from_file_location("porter_smoke", Path(__file__).with_name("emulator-smoke.py"))
base = importlib.util.module_from_spec(spec)
spec.loader.exec_module(base)

# The port adbd is told to listen on. Nothing about it is special; the app reads whichever port
# the device reports, and this is only what the harness sets it to.
TCP_PORT = "5555"
# A reboot has to bring back the framework, the manager's boot receiver, its worker, and the
# connection that worker makes, so "nothing came back" needs a generous window to mean anything.
BOOT_TIMEOUT = 300
# Told apart from an empty reply, which a failed adb call also produces.
NO_PROCESS = "porter-ci-no-server"
# What WorkManager logs when it hands the work to the worker, which is what tells a worker that
# actually ran from one that was only enqueued.
WORKER_STARTED = "Starting work for eu.darken.porter.manager.worker.AdbStartWorker"
# How long the no-port case waits before calling the absence of a server a result. The worker
# retries with backoff, so this only has to outlive the first attempt.
NO_START_SETTLE = 60
# How long the worker is given to give up. With no port to find it waits on a wireless-debugging
# authorization that is never coming, and what ends that is WorkManager stopping the worker at its
# ten-minute execution limit, not anything the app decides. Bounded by that, not by a guess.
# The order run() declares, which --case narrows without ever reordering.
CASES = ("setup", "app-adb-start", "start-on-boot", "start-on-boot-off", "boot-without-adb")
# Each of these inherits what the case before it established on the device.
# Each of these inherits what the case before it established on the device. boot-without-adb does
# not name start-on-boot-off: it sets the toggle it needs rather than assuming a previous case
# left it either way.
REQUIRES = {
    "start-on-boot": ("app-adb-start",),
    "start-on-boot-off": ("app-adb-start", "start-on-boot"),
    "boot-without-adb": ("app-adb-start", "start-on-boot"),
}


class Boot(base.Smoke):

    def setup(self):
        assert self.args.serial.startswith("emulator-"), "A disposable emulator serial is required"
        assert self.shell("getprop", "ro.kernel.qemu") == "1", "Refusing a physical device"
        assert self.shell("id", "-u") == "2000", "ADB must run as shell, not root"
        # setprop on a persist. property is refused for the shell, and the reboot case has no
        # other way to leave a TCP port behind for the app to find.
        assert self.root_available(), "this image's su does not give the ADB shell root"
        installed = set(self.shell("pm", "list", "packages").splitlines())
        assert not installed.intersection("package:" + p for p in (base.MANAGER, base.NATIVE)), "Use a fresh emulator"
        for apk in (self.args.manager, self.args.native):
            self.adb("install", str(apk.resolve()))
        self.shell("sh", "-c", f"printf %s {shlex.quote(base.PAYLOAD)} > /data/local/tmp/porter-probe.txt; "
                               "chmod 600 /data/local/tmp/porter-probe.txt")
        # What a user grants through the system UI, which this suite is not about: the wireless
        # start needs secure settings, and the notifications carry what start-on-boot reports.
        self.shell("pm", "grant", base.MANAGER, "android.permission.WRITE_SECURE_SETTINGS")
        self.shell("pm", "grant", base.MANAGER, "android.permission.POST_NOTIFICATIONS", check=False)
        self.shell("dumpsys", "deviceidle", "whitelist", "+" + base.MANAGER)
        self.home()
        assert not self.pid("porter_server"), "a server was already running before the app started one"

    def home(self):
        self.shell("am", "start", "-W", "-f", "0x04000000", "-n",
                   base.MANAGER + "/eu.darken.porter.manager.MainActivity")

    def offer_tcp_adb(self):
        """Puts adbd on TCP, which is what a user enabling wireless debugging leaves behind."""
        self.adb("tcpip", TCP_PORT)
        self.adb("wait-for-device")
        # Unchecked: restarting adbd drops connections for a moment, and the failure that shows
        # up here is a closed transport rather than an answer. getprop can be asked again.
        self.until("adbd is listening on TCP",
                   lambda: self.shell("getprop", "service.adb.tcp.port", check=False) == TCP_PORT,
                   timeout=60)

    def boot_id(self):
        """The kernel's id for this boot, which is a different string after every restart."""
        return self.shell("cat", "/proc/sys/kernel/random/boot_id", check=False)

    def reboot(self):
        before = self.boot_id()
        assert before, "no boot id to compare against"
        self.adb("reboot")
        # Compared rather than watched for a gap: a dropped transport also answers nothing, so
        # "it stopped replying and then said it had booted" is satisfied by the boot that was
        # already running. A new id is only ever a new boot.
        self.until("the device came back on a new boot",
                   lambda: (now := self.boot_id()) and now != before,
                   timeout=BOOT_TIMEOUT)
        self.until("the device finished booting",
                   lambda: self.shell("getprop", "sys.boot_completed", check=False) == "1",
                   timeout=BOOT_TIMEOUT)

    def manager_notifications(self):
        """The manager's own notification records, as dumpsys prints them."""
        dump = self.shell("sh", "-c", "dumpsys notification --noredact")
        records, keeping = [], False
        for line in dump.splitlines():
            if "NotificationRecord(" in line:
                # The trailing space matters: probe packages start with the manager's own name.
                keeping = f"pkg={base.MANAGER} " in f"{line} "
                if keeping:
                    records.append([line])
            elif keeping and records:
                records[-1].append(line)
        return ["\n".join(record) for record in records]

    def no_server(self):
        """Absence, established by a reply rather than by a query that failed to produce one."""
        listing = self.shell("sh", "-c", "pidof porter_server || echo " + NO_PROCESS)
        assert listing in (NO_PROCESS, "") or listing.isdigit(), listing
        return listing == NO_PROCESS

    def run(self):
        self.case("setup", self.setup)

        def app_adb_start():
            """The manager's own wireless start, over loopback, with its own ADB key."""
            self.offer_tcp_adb()
            self.home()
            # The wireless-debugging card's own Start button. Asserting the card first means a
            # layout change fails here rather than silently tapping a different Start.
            assert self.locate("Start via Wireless debugging"), "the wireless start card is gone"
            self.tap("Start", screenshot="app-adb-start")
            pid = self.until("a server the app started", lambda: self.pid("porter_server"), timeout=120)
            self.until("the manager's own ADB client did the starting",
                       lambda: "AdbClient" in self.adb("logcat", "-d", "--pid=" + self.pid(base.MANAGER),
                                                      "-s", "AdbClient:D", "*:S"))
            self.until("the server sent its binders", lambda: "sent binders" in self.server_log(pid))
            self.home()
            assert self.locate("Running via ADB", prefix=True), "the manager does not report an ADB start"

            self.launch_probe(base.NATIVE)
            self.tap("Allow all the time", base.MANAGER)
            self.authorized(base.NATIVE)
            return {"server_pid": pid}
        self.case("app-adb-start", app_adb_start)

        def start_on_boot_is(enable):
            listing = self.shell("sh", "-c", f"dumpsys package {base.MANAGER} | grep -A4 enabledComponents")
            return ("BootCompleteReceiver" in listing) == enable

        def toggle_start_on_boot(enable):
            """Leaves the setting in the asked-for state, whatever it was before."""
            self.home()
            self.tap("Settings", desc="Settings")
            if start_on_boot_is(enable):
                return
            self.tap("Start on boot", screenshot="start-on-boot-" + ("on" if enable else "off"))
            # Below API 33 the switch opens a warning about wireless debugging first, and the
            # write only happens when it is confirmed. Both outcomes are watched for at once: the
            # dialog takes a moment to appear, so a single look would miss it and then wait out
            # the state check against a switch that is still asking.
            outcome = self.until(
                "the switch either asked or applied",
                lambda: ("asked" if self.locate("OK") else
                         "applied" if start_on_boot_is(enable) else None))
            if outcome == "asked":
                self.tap("OK")
            self.until(f"start on boot is {'on' if enable else 'off'}", lambda: start_on_boot_is(enable))
            # The component list changes before the write that makes it survive a restart has
            # finished. The preference is written after that call returns, so it is what says the
            # app is done rather than merely under way.
            wanted = f'name="start_on_boot" value="{str(enable).lower()}"'
            self.until("the app finished saving the setting",
                       lambda: wanted in self.shell(
                           "su", "0", "cat", f"/data/user_de/0/{base.MANAGER}/shared_prefs/settings.xml",
                           check=False),
                       timeout=60)

        def start_on_boot():
            """Nothing on the host starts anything: the device has to do it by itself."""
            toggle_start_on_boot(True)
            # service.adb.tcp.port does not survive a reboot. This is the persistent form, which
            # models a device whose TCP adb is still there afterwards; finding a port over mDNS
            # instead is a different path and not what this case is about.
            self.shell("su", "0", "setprop", "persist.adb.tcp.port", TCP_PORT)
            before = self.pid("porter_server")

            # Straight into the reboot, deliberately. The setting has to be on disk by the time
            # the switch reports it is on; a case that idled here first would pass against an
            # implementation that only gets round to writing it eventually.
            self.reboot()
            pid = self.until("a server the device brought back on its own",
                             lambda: self.pid("porter_server"), timeout=BOOT_TIMEOUT)
            assert pid != before
            self.until("the server sent its binders", lambda: "sent binders" in self.server_log(pid))
            # The saved decision outlives the reboot too, so the client is not asked a second time.
            self.launch_probe(base.NATIVE)
            self.authorized(base.NATIVE)
            return {"server_pid": pid}
        self.case("start-on-boot", start_on_boot)

        def start_on_boot_off():
            """The same device with the same way in, and the setting the only difference."""
            toggle_start_on_boot(False)
            self.shell("su", "0", "pkill", "-9", "-f", "porter_server")
            self.until("the server is gone", self.no_server)

            self.reboot()
            assert self.shell("getprop", "persist.adb.tcp.port") == TCP_PORT, "the way in went away too"
            time.sleep(NO_START_SETTLE)
            assert self.no_server(), "a server started with start on boot turned off"
            return {}
        self.case("start-on-boot-off", start_on_boot_off)

        def boot_without_adb():
            """Start on boot back on, and no way in: the app cannot, and says so."""
            toggle_start_on_boot(True)
            # Emptied rather than deleted: persistent properties live together in one file, so
            # there is nothing per-property to remove, and getInt reads an empty value as absent.
            self.shell("su", "0", "setprop", "persist.adb.tcp.port", "")
            self.reboot()
            assert self.shell("getprop", "persist.adb.tcp.port", check=False) == ""
            assert self.shell("getprop", "service.adb.tcp.port", check=False) == ""
            # That the work ran, not merely that it was enqueued: the starter posts its first
            # notification when it queues the work, so the notification alone says nothing about
            # an attempt. What the worker then reports is deliberately not asserted. With no port
            # to find it can settle on a retry within seconds or sit in "Starting Porter…"
            # indefinitely, depending on whether discovery times out or the authorization wait
            # takes over, and a case that picked one of those would pass on the environment rather
            # than on Porter.
            self.until("the manager's start worker ran",
                       lambda: WORKER_STARTED in self.adb("logcat", "-d", "-s", "WM-WorkerWrapper:D", "*:S"),
                       timeout=BOOT_TIMEOUT)
            assert self.manager_notifications(), "the user was told nothing while it tried"
            time.sleep(NO_START_SETTLE)
            assert self.no_server(), "a server started with no ADB port to start it through"
            self.screenshot("boot-without-adb")
            return {}
        self.case("boot-without-adb", boot_without_adb)


def parse_args(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    for name in ("manager", "native"):
        parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--case", action="append", dest="cases", choices=CASES, metavar="NAME",
                        help="run only the named case, repeatable, in declared order; "
                             "omit to run all of: " + ", ".join(CASES))
    args = parser.parse_args(argv)
    if args.cases and "setup" not in args.cases:
        parser.error("--case setup is required: every other case needs the installs and the "
                     "grants it performs")
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
                         "assert against a device those build")
    return args


if __name__ == "__main__":
    smoke = Boot(parse_args())
    try:
        smoke.run()
    finally:
        smoke.reports()
