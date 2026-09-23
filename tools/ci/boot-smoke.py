#!/usr/bin/env python3
"""Start Porter the way a user does, from the app itself, and make it survive a reboot."""
import argparse
import importlib.util
import re
from pathlib import Path
import shlex
import time
import xml.etree.ElementTree as ET

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
SETTINGS_DIR = "/data/user_de/0/eu.darken.porter/shared_prefs"
AUTOMATION_TOKEN = "porter-ci-automation-token"
AUTOMATION_SECRETS = ('<?xml version="1.0" encoding="utf-8" standalone="yes" ?>'
                      f'<map><string name="auth_token">{AUTOMATION_TOKEN}</string></map>')
SETTINGS = "com.android.settings"
SYSTEMUI = "com.android.systemui"
# Where the system keeps paired keys and the networks wireless debugging is always allowed on.
ADB_KEYSTORE = "/data/misc/adb/adb_temp_keys.xml"
# The order run() declares, which --case narrows without ever reordering.
CASES = ("setup", "wireless-pairing", "app-adb-start", "automation-broadcasts", "start-on-boot",
         "start-on-boot-off", "boot-without-adb")
# Run only when named.
OPT_IN = ("wireless-pairing",)
# Each of these inherits what the case before it established on the device. boot-without-adb does
# not name start-on-boot-off: it sets the toggle it needs rather than assuming a previous case
# left it either way.
REQUIRES = {
    "automation-broadcasts": ("app-adb-start",),
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

    def manager_jobs(self):
        """The JobScheduler jobs the manager has registered, which is where WorkManager keeps work
        that has not finished."""
        dump = self.shell("dumpsys", "jobscheduler", base.MANAGER, check=False)
        # Android 16 prints "JOB <namespace>:u0a216/2: ... @<namespace>@eu.darken.porter/...",
        # where older releases print "JOB #u0a216/2: ... eu.darken.porter/...".
        return [line.strip() for line in dump.splitlines()
                if re.match(r"\s*JOB\s", line) and re.search(rf"[ @]{re.escape(base.MANAGER)}/", line)]

    def tap_notification_action(self, title, action, screenshot=None):
        """Taps this action of the notification with this title, expanding that notification in the
        shade where it is collapsed. Every look stays inside its own expandableNotificationRow and
        taps what that same look found, so another notification's Expand or button of the same
        name is never the one tapped. A screen that did not change is looked at and tapped again,
        as base tap() does."""
        self.shell("cmd", "statusbar", "expand-notifications")

        def center(node):
            left, top, right, bottom = map(int, re.findall(r"\d+", node.get("bounds", "")))
            return (left + right) // 2, (top + bottom) // 2

        def own_action():
            root = self.ui()
            rows = [row for row in root.iter("node") if row.get("resource-id") == f"{SYSTEMUI}:id/expandableNotificationRow"
                    and any(node.get("text") == title for node in row.iter("node"))]
            if not rows:
                return None
            # The innermost, where a group nests one row inside another.
            row = rows[-1]
            button = next((node for node in row.iter("node") if node.get("text") == action
                           and node.get("enabled") == "true"), None)
            if button is not None:
                return ET.tostring(root), center(button)
            expand = next((node for node in row.iter("node") if node.get("content-desc") == "Expand"), None)
            if expand is not None:
                self.shell("input", "tap", *center(expand))
            return None

        description = f"the notification '{title}' shows '{action}'"
        for attempt in range(base.TAP_ATTEMPTS):
            found = self.until(description, own_action) if attempt == 0 else own_action()
            # Gone between an unanswered tap and this look: it was acted on after all.
            if found is None:
                return
            before, (x, y) = found
            if screenshot and attempt == 0:
                self.screenshot(screenshot)
            self.shell("input", "tap", x, y)
            if self.heard(before):
                return
            print(f"NOTE the screen did not answer a tap on '{action}' in '{title}'", flush=True)
        raise AssertionError(f"Tapped '{action}' in '{title}' {base.TAP_ATTEMPTS} times and the screen never changed")

    def no_server(self):
        """Absence, established by a reply rather than by a query that failed to produce one."""
        listing = self.shell("sh", "-c", "pidof porter_server || echo " + NO_PROCESS)
        assert listing in (NO_PROCESS, "") or listing.isdigit(), listing
        return listing == NO_PROCESS

    def restore(self, *aspects):
        if "wireless-debugging" in aspects:
            self.kill_server(check=False)
            self.until("no server is left", self.no_server)
            self.shell("settings", "put", "global", "adb_wifi_enabled", "0")
            self.shell("cmd", "statusbar", "collapse")
            # A network always allowed would let the manager's start worker turn wireless
            # debugging back on by itself, a way in for boot-without-adb.
            assert b"wifiAP" not in self.adb("exec-out", "su", "0", "cat", ADB_KEYSTORE, binary=True), \
                "a network stayed allowed for wireless debugging"
            # A fresh manager process, so the next case's look at the manager's own log sees
            # only what that case caused.
            self.shell("am", "force-stop", base.MANAGER)
        rest = tuple(aspect for aspect in aspects if aspect != "wireless-debugging")
        if rest:
            super().restore(*rest)

    def run(self):
        self.case("setup", self.setup)

        def wireless_pairing():
            """Pairing through Porter's notification with the code the device shows, then starting
            over the paired connection: the way in for someone without a computer."""
            def notified(title):
                # Polled for many seconds, so one dump that fails is a look that answered nothing.
                try:
                    return any(title in record for record in self.manager_notifications())
                except RuntimeError as e:
                    print(f"NOTE {e}", flush=True)
                    return False

            def wireless_on():
                return self.shell("settings", "get", "global", "adb_wifi_enabled") == "1"

            self.shell("pm", "grant", base.MANAGER, "android.permission.NEARBY_WIFI_DEVICES", check=False)
            self.home()
            self.tap("Pairing", scroll=True, screenshot="home-pairing")
            self.until("Porter searches for the pairing service", lambda: notified("Searching for pairing service"))

            # What a user does once by tapping the build number, and the steps the tutorial names:
            # its own button into Developer options, then Wireless debugging and its pairing code.
            self.shell("settings", "put", "global", "development_settings_enabled", "1")
            self.tap("Developer options", scroll=True)
            # The button lands on the Wireless debugging page itself where Settings allows it.
            landed = self.until("Settings opened", lambda: (
                "page" if self.locate("Use wireless debugging", SETTINGS) else
                "list" if self.locate("Wireless debugging", SETTINGS, scroll=True) else None))
            if landed == "list":
                self.tap("Wireless debugging", SETTINGS, scroll=True)
            if not wireless_on():
                self.tap("Use wireless debugging", SETTINGS)
                # Allowed this once only, so the network is not remembered past this case.
                self.tap("Allow", SYSTEMUI)
            self.until("wireless debugging is on", wireless_on)
            self.tap("Pair device with pairing code", SETTINGS, scroll=True, screenshot="pairing-code")
            code = self.until("the pairing code", lambda: next(
                (n.get("text") for n in self.ui().iter("node")
                 if n.get("package") == SETTINGS and re.fullmatch(r"\d{6}", n.get("text", ""))), None))

            # The dialog stays up while the code goes in through the notification, as it must.
            self.until("Porter found the pairing service", lambda: notified("Pairing service found"), timeout=60)
            self.tap_notification_action("Pairing service found", "Enter pairing code", screenshot="pairing-notification")
            self.shell("input", "text", code)
            self.shell("input", "keyevent", "KEYCODE_ENTER")
            self.until("Porter reports the pairing", lambda: notified("Pairing successful"), timeout=60)

            # The success notification's own Start, over the connection just paired.
            self.tap_notification_action("Pairing successful", "Start", screenshot="pairing-start")
            pid = self.until("a server started over wireless debugging", lambda: self.pid("porter_server"),
                             timeout=120)
            self.until("the manager's own ADB client did the starting",
                       lambda: "AdbClient" in self.adb("logcat", "-d", "--pid=" + self.pid(base.MANAGER),
                                                      "-s", "AdbClient:D", "*:S"))
            self.until("the server sent its binders", lambda: "sent binders" in self.server_log(pid))
            return {"server_pid": pid, "code": code}
        self.case("wireless-pairing", wireless_pairing, restore=("wireless-debugging",))

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

        def automation_broadcasts():
            """The START and STOP intents the automation sheet tells users to send, token and all."""
            # Only the token lives in these preferences. Written while the manager is stopped, so no
            # process holds a copy to write back over it; the server outlives that stop.
            self.shell("am", "force-stop", base.MANAGER)
            self.shell("run-as", base.MANAGER, "sh", "-c",
                       f"mkdir -p {SETTINGS_DIR} && printf %s {shlex.quote(AUTOMATION_SECRETS)}"
                       f" > {SETTINGS_DIR}/secrets.xml")
            # A force-stopped package receives no broadcast that names only its package, and a
            # device whose automation fires has the manager in its ordinary state.
            self.home()
            server = self.pid("porter_server")
            assert server, "app-adb-start left no server running"
            # A STOP that reaches a manager still waiting for its binder is dropped, not deferred.
            self.until("the relaunched manager is connected",
                       lambda: self.locate("Running via ADB", prefix=True))

            def broadcast(action, auth):
                self.shell("am", "broadcast", "-a", f"{base.MANAGER}.{action}", "-p", base.MANAGER,
                           "--es", "auth", auth)

            broadcast("STOP", "not-the-token")
            # The refusal shows itself, which is what makes the server still running mean something.
            self.until("the wrong token is reported", lambda: any(
                "Invalid auth token" in record for record in self.manager_notifications()))
            assert self.pid("porter_server") == server, "a STOP with the wrong token stopped the server"

            broadcast("STOP", AUTOMATION_TOKEN)
            self.until("STOP stopped the server", self.no_server)

            broadcast("START", AUTOMATION_TOKEN)
            started = self.until("START started a server", lambda: self.pid("porter_server"), timeout=120)
            self.until("the new server sent its binders", lambda: "sent binders" in self.server_log(started))
            # START went through WorkManager, which runs unfinished work again after a reboot. Left
            # pending, it could start the server the next case's boot is meant to start by itself.
            self.until("the start work finished", lambda: not self.manager_jobs(), timeout=60)
            return {"stopped": server, "started": started}
        self.case("automation-broadcasts", automation_broadcasts)

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
            self.kill_server()
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
                             "omit to run all of: " + ", ".join(c for c in CASES if c not in OPT_IN)
                             + "; " + ", ".join(OPT_IN) + " only when named")
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
    else:
        args.cases = [name for name in CASES if name not in OPT_IN]
    return args


if __name__ == "__main__":
    smoke = Boot(parse_args())
    try:
        smoke.run()
    finally:
        smoke.reports()
