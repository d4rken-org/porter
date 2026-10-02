#!/usr/bin/env python3
"""Exercise real Binder grants on a fresh, disposable emulator using only ADB."""
import argparse
from collections import Counter
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import time
import traceback
import zipfile
import xml.etree.ElementTree as ET

MANAGER = "eu.darken.porter"
COMPAT = "moe.shizuku.privileged.api"
NATIVE = "eu.darken.porter.probe.native"
LEGACY = "eu.darken.porter.probe.legacy"
# Everything a suite installs and drives. A crash dialog about one of these is the case failing,
# not something standing in front of it; the derived suites add their own.
UNDER_TEST = {MANAGER, COMPAT, NATIVE, LEGACY}
PERMISSION = "eu.darken.porter.permission.API"
FORCE_PM_FALLBACK = "debug.porter.pm_fallback"
LEGACY_PERMISSION = "moe.shizuku.manager.permission.API_V23"
# What each probe reports on its BINDER line: the Porter protocol version from the porter
# flavour, the Shizuku API level from the legacy one. Different numbers, different meanings.
PORTER_PROTOCOL_VERSION = 4
# ConfigManager.FLAG_ALLOWED and FLAG_DENIED, as porter.json stores them. Pinned by
# secondary-user-prompt, which is about which of the two a refused prompt writes: neither.
DECISIONS = "/data/user_de/0/com.android.shell/porter.json"
DECISION_ALLOWED = 1 << 1
DECISION_DENIED = 1 << 2
SHIZUKU_API_VERSION = 13
PAYLOAD = "porter-ci-shell-access"
# NotificationChannels.ADB_START. Channel ids persist in the user's notification settings, so the
# app cannot change this one either.
NOTIFICATION_CHANNEL_ADB_START = "porter.adb_start"
PORSH_DIR = "/data/local/tmp"
# Where run-as starts, which a release build refuses; a root shell reaches the same files by path.
MANAGER_DATA = "/data/user/0/" + MANAGER
# Comfortably past a 64 KiB pipe buffer, so a reader that never drains blocks the writer.
PORSH_BULK = 4096 * 64
# adb reports these itself before dispatching anything to the device, so repeating is safe. Stream
# failures ("closed", "protocol fault") are deliberately absent: they can surface after the device
# already ran the command, and a repeated tap, install or service start is not idempotent. The
# serial sits inside the not-found line, as in: adb: device 'emulator-5554' not found
TRANSPORT_FAILURE = re.compile(r"^(adb: |error: ).*(device offline|device still connecting|not found)",
                               re.MULTILINE)
TRANSPORT_ATTEMPTS = 3
TRANSPORT_BACKOFF = 2
# Android 7's logd refuses a clear now and then ("failed to clear the 'main' log") and takes the
# next one.
LOGCAT_CLEAR_ATTEMPTS = 5
# The order run() declares, which --case narrows without ever reordering.
CASES = ("setup", "standalone", "app-list-grant", "app-list-fallback", "debug-recording", "compatibility",
         "coexistence", "porsh", "server-crash-recovery", "force-stop-stays-stopped", "root-server",
         "decisions-across-start-modes", "daemon-host-uninstalled", "daemon-host-upgraded", "host-removed-from-one-user",
         "foreign-signer-peeks", "foreign-signer-binds", "foreign-signer-never-binds",
         "non-daemon-control", "daemon-revoked-in-settings",
         "manager-stopped-then-uninstalled", "manager-upgraded-then-uninstalled",
         # Last: it creates and destroys an Android user, and the framework finishes tearing that
         # down after the case has returned.
         "secondary-user-prompt")
# The host lane backs off to 300s between scans, so a change it has to notice can take that long
# plus the confirmation grace. The manager lane never backs off.
HOST_SCAN_TIMEOUT = 360
MANAGER_SCAN_TIMEOUT = 60
# Without a permission observer the server re-checks granted apps every 15s, and a removed
# user-service record is killed 3s after that.
PERMISSION_POLL_TIMEOUT = 45
# Removing an Android user returns before the user list reflects it.
USER_REMOVAL_TIMEOUT = 120
# am reports the target user as soon as a switch is queued, so a wait on that report returning is
# not the switch being over. The screen can take another ~20s while the incoming user's windows
# are rebuilt, which is what absent() sits through.
USER_SWITCH_TIMEOUT = 90
# Enough to cover the first two host deadlines after a record was created, which is what a scenario
# asserting "nothing was removed" has to outlive to mean anything.
HOST_SETTLE = 45
# Past the usual first host deadline after a record was created. A scan that finds nothing changed
# puts the next one 30s out, reducing the chance of a scan in the uninstall/install gap. Earlier
# deadlines and slow installs can still overlap it; this is not synchronization.
HOST_QUIET = 17
MANAGER_SETTLE = 20
ADB_TIMEOUT = 45
# An install compiles the APK on the device, which an Android 7 emulator under software rendering
# has run past ADB_TIMEOUT for the manager.
INSTALL_TIMEOUT = 180
# A budget rather than a number of tries: a dump that comes back with no accessibility root has
# started its own VM to get there, so each failed attempt costs seconds that a count would hide.
UI_DUMP_TIMEOUT = 30
# For a dump taken inside another wait's condition, where the full budget would let one screen
# spend the whole outer window in a single poll.
UI_POLL_TIMEOUT = 5
# Consecutive readable dumps that stand in for the screen having stopped changing, so that a window
# on its way out cannot answer a question about what is on screen.
UI_STABLE_POLLS = 2
# How long a tapped screen has to answer before the tap counts as lost. The platform can drop an
# injected gesture outright, and a dropped one is indistinguishable from a tap nobody acted on.
TAP_SETTLE = 3
TAP_ATTEMPTS = 3
# What is asked of the device when a tap goes unanswered, and how long all of it may take.
# SurfaceFlinger comes last, being the largest; it says whether a window the window manager counts
# as shown ever became a visible layer, which is where the input dispatcher's windows come from.
TAP_EVIDENCE_QUERIES = ("input", "window windows", "activity activities", "SurfaceFlinger")
TAP_EVIDENCE_TIMEOUT = 20
# The framework's own crash dialog, which belongs to no app under test and sits in front of
# whatever the case was about. The message is phrased around the crashed app's name, so the
# wording that is not is what identifies it. An ANR dialog offers the same button but is never
# cleared: the crash buffer does not record ANRs, so nothing attributes one.
FRAMEWORK_ERROR_BUTTON = "Close app"
FRAMEWORK_ERROR_TEXT = re.compile(r"(keeps stopping|kept stopping|has stopped)")
FRAMEWORK_ERROR_DISMISSALS = 2
# Which app a crash dialog is about, which the dialog itself says only as a label. The crash buffer
# names the package, and the newest entry in it is the crash whose dialog is in front.
CRASHED_PROCESS = re.compile(r"\bProcess: (\S+?),", re.MULTILINE)
# How the pinned Shizuku server brackets one binder push. It whitelists the package, then calls
# that package's provider, which starts the app's process; the binder line or the null-provider
# line closes it. Porter's own server says "sent binders" instead and never logs the first of
# these, which is why start_service reads the two servers differently.
PUSH_START = re.compile(r"Add 0:(\S+) to power save temp whitelist")
PUSH_END = re.compile(r"send binder to user app (\S+) in user|provider is null (\S+)\.shizuku")
# Identical consecutive reads that stand in for the sweep having begun at all; a push in flight is
# read from the brackets above rather than from silence, which a blocking provider call also makes.
SERVER_QUIET_POLLS = 4
SERVER_QUIET_TIMEOUT = 60
# am start -W answers in well under a second when it is the only start outstanding for the package.
LAUNCH_TIMEOUT = 20
# How long a force-stopped client is watched for coming back.
STOPPED_WATCH = 10
LAUNCH_ATTEMPTS = 3
FOREIGN_PASSWORD = "porterci"
USER_ID = re.compile(r"UserInfo\{(\d+):")
HOME = MANAGER + "/eu.darken.porter.manager.MainActivity"
# The titles of first-run onboarding's pages, in the order they appear. The middle one only shows
# while another app owns the Shizuku permission. A page in transition shows two titles, and the
# earlier one names it until it has gone.
ONBOARDING_TITLES = ("Welcome to Porter", "Shizuku is installed", "Privacy")
# How dumpsys activity activities names Home and onboarding, and the lines in it naming the resumed
# activity, which releases spell differently.
HOME_RECORD = MANAGER + "/.manager.MainActivity"
ONBOARDING_RECORD = MANAGER + "/.manager.onboarding.OnboardingActivity"
RESUMED_ACTIVITY = re.compile(r"^\s*(topResumedActivity|mResumedActivity|ResumedActivity)\s*[=:]")
# What a debug recording's events.txt says about each service instance it follows, in this order:
#   Service binder arrived attach=2 pid=4242 at 1790607903123
#   Debug logging granted for 1795000ms at 1790607903180
#   Server stream attached pid=4242 boot=7 attach=2 replay=1790607901.000 at 1790607903200
BINDER_ARRIVED = re.compile(r"Service binder arrived attach=\d+ pid=(\d+|unknown) at \d+")
LEASE_GRANTED = re.compile(r"Debug logging granted for \d+ms at \d+")
STREAM_ATTACHED = re.compile(r"Server stream attached pid=(\d+) boot=-?\d+ attach=(\d+) replay=\S+ at \d+")
#   Clock attach 1 wall=2026-09-28T17:05:03.123+0200 epochMs=1790607903123 elapsedMs=5000123 uptimeMs=4000456 bootCount=7
CLOCK_ANCHOR = re.compile(r"Clock (.+?) wall=\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d{3}[+-]\d{4} "
                          r"epochMs=\d+ elapsedMs=\d+ uptimeMs=\d+ bootCount=-?\d+")
# The head of a threadtime line, which is how both adb and the recording's server.log print:
#   09-28 17:05:03.123  4242  4250 I Service : starting server...
THREADTIME = re.compile(r"\d\d-\d\d \d\d:\d\d:\d\d\.\d{3}\s+(\d+)\s+\d+\s+[VDIWEFA]\s")


def stream_attaches(events):
    """(pid, attach number) for every server stream a recording attached, oldest first."""
    return [(found.group(1), int(found.group(2))) for line in events.splitlines()
            if (found := STREAM_ATTACHED.fullmatch(line.strip()))]


def lease_granted(events, pid):
    """Whether the newest arrival of service [pid] was granted a debug lease before anything else
    arrived. A grant belongs to the arrival before it, not to whichever instance is newest."""
    granted = False
    listening = False
    for line in events.splitlines():
        line = line.strip()
        arrived = BINDER_ARRIVED.fullmatch(line)
        if arrived:
            listening = arrived.group(1) == pid
            if listening:
                granted = False
        elif listening and LEASE_GRANTED.fullmatch(line):
            granted = True
    return granted


def clock_anchors(events):
    """The labels of a recording's well-formed clock anchors, oldest first: "start", "attach 1"."""
    return [found.group(1) for line in events.splitlines()
            if (found := CLOCK_ANCHOR.fullmatch(line.strip()))]


def in_order(items, expected):
    """Whether [expected] appears in [items] in that order, with anything else in between."""
    remaining = iter(items)
    return all(item in remaining for item in expected)


def logged_by(log, pid, message):
    """The threadtime lines of [log] that process [pid] wrote and that contain [message]."""
    return [line.strip() for line in log.splitlines()
            if (found := THREADTIME.match(line.strip())) and found.group(1) == pid and message in line]


def resumed_elsewhere(activities, package):
    """Whether dumpsys activity activities shows some activity resumed and none of [package]'s.
    Nothing resumed is a transition still under way, not [package] having left the foreground."""
    resumed = [line for line in activities.splitlines()
               if RESUMED_ACTIVITY.match(line) and "ActivityRecord{" in line]
    return bool(resumed) and not any(f" {package}/" in line for line in resumed)


def user_home_resumed(activities, user, component):
    """Whether the resolved HOME is actually resumed in this user, not merely in task history."""
    home = re.fullmatch(r"([\w.]+)/([\w.$]+)", component)
    if not home:
        return False
    package, activity = home.groups()
    if package in ("com.google.android.googlesdksetup", "com.android.provision"):
        return False
    activity = package + activity if activity.startswith(".") else activity
    for line in activities.splitlines():
        if not RESUMED_ACTIVITY.match(line):
            continue
        record = re.search(r"ActivityRecord\{\S+ u(\d+) ([\w.]+)/([\w.$]+)(?:\s|})", line)
        if record:
            record_user, record_package, record_activity = record.groups()
            if record_activity.startswith("."):
                record_activity = record_package + record_activity
            if (record_user, record_package, record_activity) == (str(user), package, activity):
                return True
    return False


class PushTracker:
    """The server's binder pushes that have been opened and not yet closed.

    Accumulated across reads rather than re-derived from each one. A push that blocks long enough
    for its opening line to rotate out of the log buffer would otherwise read as finished, and the
    pid filter selects what is printed, not what the buffer keeps.
    """

    def __init__(self):
        self.seen = []
        self.open = Counter()

    def feed(self, log):
        lines = log.splitlines()
        for line in self.added(lines):
            opened = PUSH_START.search(line)
            if opened:
                self.open[opened.group(1)] += 1
                continue
            closed = PUSH_END.search(line)
            if closed:
                package = closed.group(1) or closed.group(2)
                # Never below zero: a close whose open predates this tracker is not a credit
                # against the next push to the same package.
                if self.open[package]:
                    self.open[package] -= 1
                    if not self.open[package]:
                        del self.open[package]
        self.seen = lines
        return self.open

    def added(self, lines):
        """What this read holds that the last one did not; the buffer drops from the front."""
        for cut in range(len(self.seen) + 1):
            kept = self.seen[cut:]
            if lines[:len(kept)] == kept:
                return lines[len(kept):]
        return lines


def apksigner():
    """The newest apksigner in the local SDK; the signer scenario cannot run without it."""
    for variable in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        root = os.environ.get(variable)
        if not root:
            continue
        found = sorted(Path(root, "build-tools").glob("*/apksigner"))
        if found:
            return found[-1]
    located = shutil.which("apksigner")
    assert located, "apksigner not found; set ANDROID_HOME to an SDK with build-tools"
    return Path(located)


def without_bounds(screen):
    """A dump as ET.tostring() wrote it, with every node's position left out."""
    return re.sub(rb' bounds="[^"]*"', b"", screen)


class Smoke:
    # How open_home() presses onboarding's buttons, called with the label; None taps them.
    onboarding_activate = None

    def __init__(self, args):
        self.args = args
        self.output = args.output.resolve()
        self.output.mkdir(parents=True, exist_ok=True)
        self.results = []
        self.probe_pid = None
        self.foreign_apk = None
        self.install_hangs = 0
        self.unanswered_taps = 0

    def adb(self, *args, check=True, binary=False, timeout=None):
        if timeout is None:
            timeout = INSTALL_TIMEOUT if args and args[0] == "install" else ADB_TIMEOUT
        command = ["adb", "-s", self.args.serial, *map(str, args)]
        for attempt in range(TRANSPORT_ATTEMPTS):
            try:
                result = subprocess.run(command, capture_output=True, timeout=timeout)
            except subprocess.TimeoutExpired:
                if not args or args[0] != "install" or "--no-streaming" in args:
                    raise
                # An Android 7 emulator in CI has hung a streamed install past any budget, with
                # nothing in the case's log to say why. What the device was doing is kept, and the
                # install is tried once more the older way: a push, then pm install. -r keeps the
                # second attempt harmless if the first one did finish.
                self.install_hang_evidence(command)
                again = ["--no-streaming", *(() if "-r" in args else ("-r",)), *args[1:]]
                return self.adb("install", *again, check=check, binary=binary, timeout=timeout)
            stderr = result.stderr.decode(errors="replace")
            with (self.output / "commands.log").open("a") as log:
                log.write(shlex.join(command) + "\n")
                if not binary:
                    log.write(result.stdout.decode(errors="replace") + stderr)
            # Before check, so that check=False callers such as pid() cannot read a dropped
            # transport as an observation of the device.
            if not (result.returncode and TRANSPORT_FAILURE.search(stderr)):
                break
            if attempt < TRANSPORT_ATTEMPTS - 1:
                time.sleep(TRANSPORT_BACKOFF)
        if check and result.returncode:
            raise RuntimeError(f"{shlex.join(command)}: {stderr}")
        return result.stdout if binary else result.stdout.decode(errors="replace").strip()

    def install_hang_evidence(self, command):
        """The device's state when an install stopped answering, beside the case's own log."""
        print(f"NOTE install timed out, retrying without streaming: {shlex.join(command)}", flush=True)
        self.install_hangs += 1
        with (self.output / f"install-hang-{self.install_hangs}.txt").open("w") as evidence:
            for label, query in (
                    ("processes", ("shell", "toybox ps -A -o PID,PPID,STAT,NAME,ARGS")),
                    ("broadcasts", ("shell", "dumpsys activity broadcasts")),
                    ("logcat", ("logcat", "-d", "-v", "threadtime"))):
                try:
                    evidence.write(f"=== {label}\n{self.adb(*query, check=False)}\n")
                except subprocess.TimeoutExpired:
                    evidence.write(f"=== {label}\n(timed out)\n")

    def shell(self, *args, **kwargs):
        return self.adb("shell", shlex.join(map(str, args)), **kwargs)

    def detached(self, *args):
        """An adb shell left running, for a command that waits on a window this side has to tap.

        The device-side program's own output is what its caller redirected; what is captured here
        is adb's, for the log and for a transport failure. settle() closes it out.
        """
        command = ["adb", "-s", self.args.serial, "shell", shlex.join(map(str, args))]
        return subprocess.Popen(command, stdin=subprocess.DEVNULL,
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE)

    def settle(self, pending, timeout=ADB_TIMEOUT):
        try:
            stdout, stderr = pending.communicate(timeout=timeout)
        except subprocess.TimeoutExpired:
            pending.kill()
            pending.communicate()
            raise
        stderr = stderr.decode(errors="replace")
        with (self.output / "commands.log").open("a") as log:
            log.write(shlex.join(pending.args) + "\n" + stdout.decode(errors="replace") + stderr)
        if pending.returncode:
            raise RuntimeError(f"{shlex.join(pending.args)}: {stderr}")

    def until(self, description, condition, timeout=30):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            value = condition()
            if value:
                return value
            time.sleep(0.4)
        raise AssertionError(f"Timed out: {description}")

    def logs(self):
        assert self.probe_pid is not None
        return self.adb("logcat", "-d", "--pid=" + self.probe_pid, "-s", "PorterProbe:I", "*:S")

    def expect_log(self, package, message):
        return self.until(f"{package}: {message}", lambda: package + " " + message in self.logs())

    def dump(self, budget=UI_DUMP_TIMEOUT):
        path = "/data/local/tmp/porter-ci-ui.xml"
        started = time.monotonic()
        attempts = 0
        while True:
            # Unchecked: an Android 7 emulator once failed this with no output at all, and the
            # dump below is judged by uiautomator's own reply, not by the file being gone first.
            self.shell("rm", "-f", path, check=False)
            result = self.shell("uiautomator", "dump", path)
            attempts += 1
            # A null accessibility root is reported on stderr with exit status zero.
            if f"UI hierchary dumped to: {path}" in result:
                xml = self.shell("cat", path)
                root = ET.fromstring(xml)
                (self.output / "last-ui.xml").write_text(xml)
                return root
            time.sleep(0.4)
            # Checked after the sleep, so the budget gates the attempt about to start rather than
            # the one just finished. Reported as time spent, which runs past the budget by however
            # long the last attempt took.
            elapsed = time.monotonic() - started
            if elapsed >= budget:
                raise RuntimeError(f"uiautomator produced no UI dump in {attempts} attempts over "
                                   f"{elapsed:.0f}s; see {self.output / 'commands.log'}")

    def crashed(self):
        """The package of the newest crash the device recorded, or None if it recorded none. The
        buffer names the process: "eu.darken.porter:remote" is Porter's."""
        found = CRASHED_PROCESS.findall(self.adb("logcat", "-d", "-b", "crash", check=False))
        return found[-1].split(":")[0] if found else None

    def framework_error(self, root):
        """The bounds of the button that clears a crash dialog raised by something else.

        Such a dialog is drawn by the framework, so every node in it carries the "android"
        package, the way the user-switching overlay does; what tells those two apart is the
        message. What the message cannot say is which app crashed, because it names the label:
        the crash buffer names the package, and a dialog about an app this suite is testing is
        the failure rather than something in front of it, so it is left where it is. A crash
        nothing recorded is one nothing can attribute, which is the same answer.
        """
        nodes = list(root.iter("node"))
        if any(node.get("package") not in ("android", "", None) for node in nodes):
            return None
        if not any(FRAMEWORK_ERROR_TEXT.search(node.get("text", "")) for node in nodes):
            return None
        crashed = self.crashed()
        if crashed is None or crashed in UNDER_TEST:
            return None
        return self.find(nodes, FRAMEWORK_ERROR_BUTTON, package="android")

    def ui(self, budget=UI_DUMP_TIMEOUT):
        """One readable dump of a screen the device under test owns.

        A crashed system app's dialog answers every question about the screen with itself, and it
        is about neither Porter nor the probe, so it is cleared and what it covered is read
        instead. Bounded rather than repeated until clear: a service that crashes again on restart
        puts its dialog back, and outsitting that is not this suite's job.
        """
        root = self.dump(budget)
        for _ in range(FRAMEWORK_ERROR_DISMISSALS):
            bounds = self.framework_error(root)
            if bounds is None:
                return root
            message = next(node.get("text") for node in root.iter("node")
                           if FRAMEWORK_ERROR_TEXT.search(node.get("text", "")))
            print(f"NOTE dismissing a framework error dialog: {message}", flush=True)
            left, top, right, bottom = bounds
            self.shell("input", "tap", (left + right) // 2, (top + bottom) // 2)
            root = self.dump(budget)
        return root

    def settled_screen(self, budget=UI_DUMP_TIMEOUT):
        """The nodes of a screen belonging to the user now in front, or [] while there is none.

        The framework draws its user-switching overlay itself, so a dump whose every node carries
        the "android" package is the transition rather than a screen. Reading one as an answer is
        how a question about what is on screen gets answered by what is on its way out.
        """
        try:
            nodes = list(self.ui(budget).iter("node"))
        except RuntimeError:
            return []
        if any(node.get("package") not in ("android", "", None) for node in nodes):
            return nodes
        return []

    def find(self, nodes, text=None, package=MANAGER, prefix=False, occurrence=0, desc=None):
        """The button's bounds among nodes already read, or None while it is not there."""
        found = []
        for node in nodes:
            if desc is not None:
                matches = node.get("content-desc", "") == desc
            else:
                label = node.get("text", "")
                matches = label.startswith(text) if prefix else label == text
            if node.get("package") == package and matches and node.get("enabled") == "true":
                bounds = list(map(int, re.findall(r"\d+", node.get("bounds", ""))))
                if len(bounds) == 4:
                    found.append(bounds)
                    if len(found) > occurrence:
                        return found[occurrence]
        return None

    def absent(self, text, package=MANAGER, timeout=USER_SWITCH_TIMEOUT):
        """Whether a button stays missing across consecutive settled reads.

        For asserting that nothing is on screen, where one read proves the least: the window that
        answers it may be the one leaving, and the one being asked about may not have arrived. A
        read of an unsettled screen starts the run over, so the reads that decide are consecutive
        and nothing moved between them.

        What it cannot tell is which user a window belongs to, which no dump records. Across a
        user switch it answers for the screen that settles, not for a named user's screen.
        """
        deadline = time.monotonic() + timeout
        settled = 0
        while settled < UI_STABLE_POLLS:
            # Before the read rather than after it, so that a read slow enough to outlast the
            # budget is not followed by another one.
            if time.monotonic() >= deadline:
                raise AssertionError(f"Timed out: a settled screen, looking for {text!r}")
            nodes = self.settled_screen()
            if not nodes:
                settled = 0
                time.sleep(0.4)
                continue
            if self.find(nodes, text, package) is not None:
                return False
            settled += 1
        return True

    def locate(self, text=None, package=MANAGER, prefix=False, scroll=False, occurrence=0, desc=None,
               budget=UI_DUMP_TIMEOUT):
        """One look at the current window: the button's bounds, or None while it is not there."""
        root = self.ui(budget)
        # Kept so that whoever acts on these bounds can tell the screen it acted on from the one
        # it is looking at afterwards.
        self.last_screen = ET.tostring(root)
        bounds = self.find(root.iter("node"), text, package, prefix, occurrence, desc)
        if bounds is not None:
            return bounds
        if scroll:
            container = next((n for n in root.iter("node") if n.get("package") == package
                              and n.get("scrollable") == "true"), None)
            if container is not None:
                left, top, right, bottom = map(int, re.findall(r"\d+", container.get("bounds", "")))
                x = (left + right) // 2
                inset = (bottom - top) // 5
                self.shell("input", "swipe", x, bottom - inset, x, top + inset, 300)
        return None

    def unanswered(self, before):
        """None once the screen stops being [before] within [TAP_SETTLE], or why it did not:
        "unchanged" when a dump read the tapped screen again, "moved" when it read the tapped screen
        with only positions changed, "unreadable" when no dump could be read.

        A dump that fails inside the window answers neither way, so it is polled past rather than
        counted. What follows a window that ends undecided is another look at the button on its
        own full budget, not a tap at bounds nothing has confirmed.
        """
        deadline = time.monotonic() + TAP_SETTLE
        reason = "unreadable"
        while True:
            try:
                screen = ET.tostring(self.ui(UI_POLL_TIMEOUT))
                if screen == before:
                    reason = "unchanged"
                # A system bar coming or going can shift the window's nodes without changing them,
                # and a tap aimed at the bounds read before the shift can land beside the button.
                elif without_bounds(screen) == without_bounds(before):
                    reason = "moved"
                else:
                    return None
            except RuntimeError:
                pass
            if time.monotonic() >= deadline:
                return reason
            time.sleep(0.4)

    def unanswered_tap_evidence(self, description, x, y, reason, before):
        """The device's input and window state when a tap went unanswered, beside the case's log.

        The dump shows what was drawn, not which window the gesture went to or why the one drawn
        there did not take it; only these dumps say that. Taken before the next attempt and before
        the case restores anything, in one device command on one attempt, because whatever it
        costs is time a slow dialog gets that the retry did not give it. A query that fails or
        runs out of time is written down as such, so the tap's own failure stays the one reported.
        """
        self.unanswered_taps += 1
        path = self.output / f"unanswered-tap-{self.unanswered_taps}.txt"
        # Each section stamped to the second, to line up against logcat, and closed with its own status.
        script = "; ".join(f"echo \"=== dumpsys {query} at $(date '+%m-%d %H:%M:%S')\"; "
                           f"dumpsys {query} 2>&1; echo \"=== dumpsys {query} exit status $?\""
                           for query in TAP_EVIDENCE_QUERIES)
        command = ["adb", "-s", self.args.serial, "shell", script]
        try:
            result = subprocess.run(command, capture_output=True, timeout=TAP_EVIDENCE_TIMEOUT)
            dumps, stderr, outcome = result.stdout, result.stderr, f"adb exit status {result.returncode}"
        except subprocess.TimeoutExpired as e:
            dumps, stderr, outcome = e.stdout or b"", e.stderr or b"", f"timed out after {TAP_EVIDENCE_TIMEOUT}s"
        with path.open("w") as evidence:
            evidence.write(f"=== tap\n{description} at {x},{y}: screen {reason}\n")
            evidence.write(f"=== tapped screen\n{before.decode(errors='replace')}\n")
            evidence.write(dumps.decode(errors="replace"))
            evidence.write(f"\n=== evidence query: {outcome}\n{stderr.decode(errors='replace')}")
        with (self.output / "commands.log").open("a") as log:
            log.write(f"{shlex.join(command)}\n({outcome}, written to {path.name})\n")
        return path

    def tap(self, text=None, package=MANAGER, prefix=False, screenshot=None, scroll=False, occurrence=0, desc=None):
        """Taps a button and returns once the screen has acknowledged it.

        An injected gesture can be dropped before it reaches the window it was aimed at, and a
        dropped one leaves nothing behind: the case carries on and fails later, somewhere that
        says nothing about the tap. So the screen is read again afterwards, and a screen that is
        the one that was tapped, byte for byte or with only its positions shifted, is taken for a
        tap that never landed.

        Only an unchanged or moved screen is tapped a second time, at the button's bounds as read
        again, and only while the button is still there. Anything else - the dialog gone, the
        button gone, a different screen - is the tap having been acted on, however little of it
        has finished.
        """
        wanted = repr(text) if desc is None else f"content-desc {desc!r}"
        ordinal = f" #{occurrence}" if occurrence else ""
        description = f"button {wanted}{ordinal} in {package}"
        for attempt in range(TAP_ATTEMPTS):
            if attempt == 0:
                bounds = self.until(description,
                                    lambda: self.locate(text, package, prefix, scroll, occurrence, desc))
            else:
                bounds = self.locate(text, package, prefix, scroll, occurrence, desc)
                # Gone between the failed tap and this look: it was acted on after all, and
                # tapping where it used to be would hit whatever took its place.
                if bounds is None:
                    return
            before = self.last_screen
            # Once: the screenshot records what was tapped, and a retry taps the same screen.
            if screenshot and attempt == 0:
                self.screenshot(screenshot)
            left, top, right, bottom = bounds
            x, y = (left + right) // 2, (top + bottom) // 2
            self.shell("input", "tap", x, y)
            reason = self.unanswered(before)
            if reason is None:
                return
            evidence = self.unanswered_tap_evidence(description, x, y, reason, before)
            print(f"NOTE the screen did not answer a tap on {description} ({reason}); see {evidence}",
                  flush=True)
        raise AssertionError(f"Tapped {description} {TAP_ATTEMPTS} times and the screen never "
                             f"answered; see {evidence}")

    def screenshot(self, name):
        (self.output / f"{name}.png").write_bytes(self.adb("exec-out", "screencap", "-p", binary=True))

    def pid(self, name):
        return self.shell("pidof", name, check=False)

    def processes(self, columns):
        """Every process, one line per row with a header first. Named as toybox's, because Android 7
        points ps at toolbox's, which takes neither -A nor -o."""
        return self.shell("toybox", "ps", "-A", "-o", columns, check=False).splitlines()

    def kill_server(self, check=True):
        """Every porter_server, as root. By pid, because Android 7's pkill takes neither -9 nor -f."""
        pids = self.pid("porter_server").split()
        if pids:
            self.shell("su", "0", "kill", "-9", *pids, check=check)
        elif check:
            raise AssertionError("no porter_server to kill")

    def create_user(self, name):
        """A new Android user's id. Android 7's pm exits 1 even after printing that it succeeded,
        so the reply decides."""
        reply = self.shell("pm", "create-user", name, check=False)
        found = re.search(r"created user id (\d+)", reply)
        assert found, f"pm create-user {name}: {reply}"
        return found.group(1)

    def clear_logcat(self):
        """Empties the log. A clear that did not happen would leave in the lines it is there to
        bound, so a refusal is retried rather than ignored."""
        for attempt in range(1, LOGCAT_CLEAR_ATTEMPTS + 1):
            try:
                self.adb("logcat", "-c")
                return
            except RuntimeError as e:
                if "failed to clear" not in str(e) or attempt == LOGCAT_CLEAR_ATTEMPTS:
                    raise
                time.sleep(1)

    def unlock(self):
        """Dismisses the lock screen until it stays gone. A switch back to the owner user can raise
        it over everything a case then looks for, a moment after am reports the switch done; seen
        on Android 7 and on 11. Both report it in the policy dump's KeyguardStateMonitor as
        mIsShowing, and only Android 7 also as mShowingLockscreen."""
        def gone():
            self.shell("wm", "dismiss-keyguard", check=False)
            return "mIsShowing=true" not in self.shell("dumpsys", "window", "policy", check=False)
        self.until("the lock screen is gone", gone)

    def install_for_user(self, user, package, apk):
        """The installation user 0 has, added for another user. Android 7's pm has no
        install-existing and says so; there the same APK goes in for that user as an update."""
        try:
            reply = self.shell("pm", "install-existing", "--user", user, package)
        except RuntimeError as e:
            if "unknown command" not in str(e).lower():
                raise
            reply = str(e)
        if "unknown command" in reply.lower():
            self.adb("install", "-r", "--user", user, str(apk.resolve()))

    def remote_logcat(self, server_pid):
        """The logcat the service follows itself with, by the arguments it is started with rather
        than by parentage, so one left behind by a destroyed supervisor is still found after init
        has adopted it."""
        following = f"--pid={server_pid}"
        found = []
        for line in self.processes("PID,ARGS"):
            pid, arguments = (line.split(maxsplit=1) + ["", ""])[:2]
            # A whole argument, not a substring: --pid=312 is a prefix of --pid=3120.
            if pid.isdigit() and arguments.startswith("logcat") and following in arguments.split():
                found.append(pid)
        return found

    def spawned(self, server_pid):
        """The shells the service started, as {pid: the names of what each of them started}.

        Read as names and parentage rather than command lines, because the command line worth
        matching on is a multi-line script that ps prints across as many lines as it has. A user
        service is started through a shell too, so what tells the remote logcat's supervisor apart
        is the logcat under it; the user service's shell is handed its command on stdin and exits.
        """
        rows = []
        for line in self.processes("PID,PPID,NAME"):
            fields = line.split()
            if len(fields) == 3 and fields[0].isdigit() and fields[1].isdigit():
                rows.append(fields)
        shells = {pid: [] for pid, ppid, name in rows if ppid == server_pid and name == "sh"}
        for pid, ppid, name in rows:
            if ppid in shells:
                shells[ppid].append(name)
        return shells

    def in_manager_data(self, command):
        """argv running a shell command from inside the manager's data directory."""
        if not self.release_build():
            return ("run-as", MANAGER, "sh", "-c", command)
        # Once, so that a missing su fails here rather than as whatever the command was waiting for.
        if not getattr(self, "manager_data_root", False):
            assert self.root_available(), "a release build refuses run-as, and this image's su does not give the ADB shell root"
            self.manager_data_root = True
        return ("su", "0", "sh", "-c", f"cd {MANAGER_DATA} && {command}")

    def recording(self, command):
        # Debug sessions live in the manager's data directory.
        return self.shell(*self.in_manager_data(command), check=False)

    def recording_size(self, path):
        return int(self.recording(f"stat -c %s {path} 2>/dev/null || echo 0") or 0)

    def starter_binary(self, package):
        apk = self.shell("pm", "path", package).removeprefix("package:").splitlines()[0]
        assert apk.startswith("/data/app/") and apk.endswith("/base.apk"), apk
        abi = self.shell("getprop", "ro.product.cpu.abi")
        library_dir = {"x86": "x86", "x86_64": "x86_64", "arm64-v8a": "arm64", "armeabi-v7a": "arm"}[abi]
        # Coexistence starts the original Shizuku from its own APK, which ships the upstream
        # starter name; only Porter's own package carries libporter.so.
        binary = "libporter.so" if package == MANAGER else "libshizuku.so"
        return str(Path(apk).parent / "lib" / library_dir / binary)

    def root_available(self):
        """Whether this image's su lets the ADB shell become root, which the root cases need."""
        return self.shell("su", "0", "id", "-u", check=False) == "0"

    def start_service(self, package=MANAGER, root=False):
        name = "porter_server" if package == MANAGER else "shizuku_server"
        previous = self.pid(name)
        starter = self.starter_binary(package)
        self.shell(*(("su", "0", starter) if root else (starter,)))
        started = self.until(f"new {name} process",
                             lambda: (pid := self.pid(name)) and pid != previous and pid)
        # Returning on the pid alone is too early: the server is still loading native code out of
        # the manager's code path, so a caller that replaces the manager next pulls that path out
        # from under the load. This line marks the load complete.
        self.until(f"{name} finished loading its natives",
                   lambda: "starting server..." in self.server_log(started))
        # Still too early. The server then pushes its binder into every package that requests the
        # permission, reaching each one by calling that package's provider, which starts that
        # app's process. A force-stop landing on such a package while its start is in flight loses
        # the launch that follows it, so wait until no push is still open.
        if package == MANAGER:
            self.until(f"{name} sent its binders",
                       lambda: "sent binders" in self.server_log(started))
            return
        # The pinned server brackets each push instead of the sweep, so an unmatched open bracket
        # is the thing to wait out. Repeated reads only cover the gap before the first one appears.
        tracker = PushTracker()
        recent = []
        def settled():
            log = self.server_log(started)
            open_pushes = tracker.feed(log)
            recent.append(log)
            del recent[:-SERVER_QUIET_POLLS]
            return (not open_pushes
                    and len(recent) == SERVER_QUIET_POLLS and len(set(recent)) == 1)
        self.until(f"{name} finished starting client processes", settled,
                   timeout=SERVER_QUIET_TIMEOUT)

    def server_log(self, pid):
        return self.adb("logcat", "-d", "--pid=" + pid, "-s", "Service:V", "*:S")

    def launch_probe(self, package, daemon=False, peek=False, server_uid="2000"):
        command = ["am", "start", "-W", "-n", package + "/eu.darken.porter.probe.ProbeActivity"]
        for name, value in (("daemon", daemon), ("peek", peek)):
            if value:
                command += ["--ez", name, "true"]
        mode = f"MODE daemon={str(daemon).lower()} peek={str(peek).lower()}"
        for attempt in range(LAUNCH_ATTEMPTS):
            for probe in (NATIVE, LEGACY):
                self.shell("am", "force-stop", probe)
            try:
                self.shell(*command, timeout=LAUNCH_TIMEOUT)
                # One pid, because two mean a start this did not ask for is still resolving and
                # the process it settles on may not be the one that runs the activity.
                self.probe_pid = self.until(
                    "one probe process",
                    lambda: (pids := self.pid(package).split()) and len(pids) == 1 and pids[0],
                    timeout=LAUNCH_TIMEOUT)
                # Asserted rather than assumed: a scenario that needs a daemon must not silently
                # get one.
                self.expect_log(package, mode)
                break
            except (subprocess.TimeoutExpired, AssertionError):
                # A launch that never lands is one that raced a process start already in flight
                # for this package. The force-stop above makes the retry the only one outstanding.
                if attempt == LAUNCH_ATTEMPTS - 1:
                    raise
        # Outside the retry on purpose: the activity ran, so a binder that never arrives is the
        # server or the client failing to hand it over, and relaunching would only hide that.
        version = PORTER_PROTOCOL_VERSION if package == NATIVE else SHIZUKU_API_VERSION
        self.expect_log(package, f"BINDER uid={server_uid} version={version}")

    def service_pids(self, package):
        return set(self.pid(package + ":porter-probe").split())

    def installed(self, package):
        return "package:" + package in self.shell("pm", "list", "packages", package).splitlines()

    def extra_users(self):
        return [user for user in USER_ID.findall(self.shell("pm", "list", "users")) if user != "0"]

    def app_uid(self, package, user="0"):
        try:
            listing = self.shell("pm", "list", "packages", "--user", user, "-U", package)
        except RuntimeError as e:
            if "unknown option" not in str(e).lower():
                raise
            listing = ""
        found = re.search(r"uid:(\d+)", listing)
        if found:
            return int(found.group(1))
        # Android 7's pm has no -U. Its package dump names the app id userId, and a user's uids
        # are that user's range of 100000 plus the app id.
        app_id = re.search(r"userId=(\d+)", self.shell("dumpsys", "package", package))
        assert app_id, f"no uid for {package}"
        return int(user) * 100000 + int(app_id.group(1))

    # Told apart from the file's contents on the device, so that a read this side could not
    # perform reads as "nothing was written" and passes an assertion it never checked.
    NO_DECISIONS = "porter-ci-no-decision-file"

    def decision_flags(self, uid):
        """The saved flags for a uid, or 0 when nothing is recorded about it."""
        # The file only appears once something is saved, so a device that has answered no prompt
        # has no database at all rather than an empty one.
        raw = self.shell("sh", "-c", f"test -e {DECISIONS} && cat {DECISIONS} || echo {self.NO_DECISIONS}")
        if raw == self.NO_DECISIONS:
            return 0
        assert raw.startswith("{"), f"cannot read {DECISIONS}: {raw!r}"
        for entry in json.loads(raw).get("packages") or ():
            if entry.get("uid") == uid:
                return entry.get("flags", 0)
        return 0

    def wait_user_home(self, user):
        """Finish the switch's initial HOME transition before starting a client activity."""
        self.unlock()

        def ready():
            if self.shell("am", "get-current-user") != user:
                return False
            component = self.shell("cmd", "package", "resolve-activity", "--components", "--user", user,
                                   "-a", "android.intent.action.MAIN", "-c", "android.intent.category.HOME")
            activities = self.shell("dumpsys", "activity", "activities")
            return user_home_resumed(activities, user, component)

        # get-current-user changes before the framework starts HOME. Keep its resolved component
        # and resumed record in commands.log; the probe's logcat is cleared after this barrier.
        self.until(f"user {user} HOME is resumed", ready, timeout=USER_SWITCH_TIMEOUT)

    def launch_probe_as(self, package, user):
        """Starts the probe in another user and adopts its process for [logs]."""
        # Every user, not just the target one: pidof does not say which user a process belongs to,
        # so a copy left running in user 0 by an earlier case would be the one adopted below, and
        # the log this returns would be the wrong process's.
        self.shell("am", "force-stop", "--user", "all", package)
        self.until("no probe process anywhere", lambda: not self.pid(package), timeout=LAUNCH_TIMEOUT)
        # No -W: it waits for the activity to be drawn, which never happens for a user that is
        # not on screen, and the wait outlives the adb timeout.
        self.shell("am", "start", "--user", user, "-n", package + "/eu.darken.porter.probe.ProbeActivity")
        self.probe_pid = self.until(
            f"probe process in user {user}",
            lambda: (pids := self.pid(package).split()) and len(pids) == 1 and pids[0],
            timeout=LAUNCH_TIMEOUT)
        return self.probe_pid

    def foreign_probe(self):
        """The native probe re-signed with a throwaway key, keeping its package name."""
        if self.foreign_apk:
            return self.foreign_apk
        work = self.output / "foreign"
        work.mkdir(parents=True, exist_ok=True)
        keystore = work / "foreign.jks"
        if not keystore.exists():
            subprocess.run(["keytool", "-genkeypair", "-keystore", str(keystore),
                            "-storepass", FOREIGN_PASSWORD, "-keypass", FOREIGN_PASSWORD,
                            "-alias", "foreign", "-keyalg", "RSA", "-keysize", "2048",
                            "-validity", "3650", "-dname", "CN=Porter CI Foreign"],
                           check=True, capture_output=True)
        apk = work / "probe-foreign.apk"
        shutil.copyfile(self.args.native.resolve(), apk)
        subprocess.run([str(apksigner()), "sign", "--ks", str(keystore),
                        "--ks-pass", "pass:" + FOREIGN_PASSWORD, "--key-pass", "pass:" + FOREIGN_PASSWORD,
                        "--ks-key-alias", "foreign", str(apk)], check=True, capture_output=True)
        self.foreign_apk = apk
        return apk

    def restore(self, *aspects):
        """Puts back what a destructive scenario declared it would break."""
        if "users" in aspects:
            # A case that failed partway can leave another user on screen, and the current user is
            # not removable: back to user 0 first, or the removals below quietly do nothing.
            if self.shell("am", "get-current-user", check=False) != "0":
                self.shell("am", "switch-user", "0", check=False)
                self.until("am reports user 0",
                           lambda: self.shell("am", "get-current-user", check=False) == "0", timeout=60)
            # Asked again on every poll rather than once: a removal issued while the framework is
            # still putting that user down is refused, and a refusal is not worth telling apart
            # from a removal that has not landed yet.
            def gone():
                for user in self.extra_users():
                    self.shell("pm", "remove-user", user, check=False)
                return not self.extra_users()
            self.until("the extra users are gone", gone, timeout=USER_REMOVAL_TIMEOUT)
        if "manager" in aspects and not self.installed(MANAGER):
            self.adb("install", str(self.args.manager.resolve()))
        if "probes" in aspects:
            # Always uninstall first: a scenario may have left a differently signed APK under the
            # package name, which `install -r` refuses and `installed()` cannot tell apart.
            for package, apk in ((NATIVE, self.args.native), (LEGACY, self.args.legacy)):
                self.adb("uninstall", package, check=False)
                self.adb("install", str(apk.resolve()))
        if "pm-fallback" in aspects:
            self.shell("setprop", FORCE_PM_FALLBACK, "0")
            assert self.shell("getprop", FORCE_PM_FALLBACK) == "0", "the forced fallback is still set"
        if "grants" in aspects:
            for package, permission in ((NATIVE, PERMISSION), (LEGACY, LEGACY_PERMISSION)):
                self.shell("pm", "revoke", package, permission, check=False)
                self.shell("am", "force-stop", package)
        if "service" in aspects and not self.pid("porter_server"):
            self.open_home()
            self.start_service()
        if "shell-service" in aspects:
            # A root server left running would become the fixture for every case after this one,
            # so this replaces whatever is there rather than only filling a gap.
            self.kill_server(check=False)
            self.until("the server is gone", lambda: not self.pid("porter_server"))
            self.open_home()
            self.start_service()

    def authorized(self, package, require_manager_guard=True, server_uid="2000"):
        # The original Shizuku baseline does not enforce Porter's manager-only gate.
        result = "AUTHORIZED managerOperationDenied=" + ("true" if require_manager_guard else "")
        self.expect_log(package, result)
        self.expect_log(package, f"USER_SERVICE uid={server_uid} file=" + PAYLOAD)
        assert package + " FAILED" not in self.logs()

    def grant_and_revoke(self, package, permission, prompt_package=MANAGER):
        self.launch_probe(package)
        self.tap("Deny", prompt_package)
        self.expect_log(package, "DENIED")
        assert package + " USER_SERVICE uid=" not in self.logs()
        self.launch_probe(package)
        self.tap("Allow all the time", prompt_package, screenshot=package.rsplit(".", 1)[-1] + "-permission")
        self.authorized(package)
        service_pid = self.until("privileged user service", lambda: self.pid(package + ":porter-probe"))
        self.shell("pm", "revoke", package, permission)
        self.until("revocation terminates user service", lambda: not self.pid(package + ":porter-probe"))
        self.launch_probe(package)
        self.tap("Deny", prompt_package)
        self.expect_log(package, "DENIED")
        assert package + " AUTHORIZED" not in self.logs()
        self.shell("am", "force-stop", package)
        return {"revoked_user_service_pid": service_pid}

    def open_home(self):
        """Starts Home, and completes first-run onboarding when the start opened that instead.

        Home hands every start to onboarding until it has been completed once, which a fresh
        installation, or one after an uninstall, has not.
        """
        self.shell("am", "start", "-W", "-f", "0x04000000", "-n", HOME)
        activate = self.onboarding_activate or self.tap

        def manager_screen():
            nodes = self.settled_screen()
            return nodes if any(node.get("package") == MANAGER for node in nodes) else None

        def page(nodes):
            return next((title for title in ONBOARDING_TITLES if self.find(nodes, title) is not None), None)

        # Waited for rather than read once: am can return while Home is still handing over to
        # onboarding, and the window in front until then would answer "no onboarding".
        nodes = self.until("the manager's window", manager_screen)
        current = page(nodes)
        if current != ONBOARDING_TITLES[0]:
            return
        for _ in ONBOARDING_TITLES:
            if self.find(nodes, "Get started") is not None:
                activate("Get started")
                break
            activate("Continue")
            previous = current
            nodes = self.until(f"the onboarding page after {previous!r}",
                               lambda: (n := manager_screen()) and page(n) != previous and n)
            current = page(nodes)
            # A press that reached the last page's button as well has already completed it.
            if current is None:
                break
        else:
            raise AssertionError("onboarding never offered 'Get started'")
        # Read from dumpsys rather than a UI dump: a uiautomator session starting or ending while
        # onboarding's window is torn down crashes Android 14 inside ViewRootImpl.
        self.until("Home resumed and onboarding destroyed", self.onboarding_gone)
        self.until("onboarding completed and Home in front",
                   lambda: (n := manager_screen()) and page(n) is None and self.find(n, "Get started") is None)

    def onboarding_gone(self):
        activities = self.shell("dumpsys", "activity", "activities", check=False)
        resumed = [line for line in activities.splitlines() if RESUMED_ACTIVITY.match(line)]
        return any(HOME_RECORD in line for line in resumed) and ONBOARDING_RECORD not in activities

    def stop_porter(self):
        self.open_home()
        self.tap("Porter is running", prefix=True)
        self.tap("Stop Porter")
        # The dialog repeats "Stop Porter" as its title, so the second match is the confirm button
        # and waiting for it also waits for the dialog to replace the single-match screen.
        self.tap("Stop Porter", occurrence=1, screenshot="running-service-dialog")
        self.until("Porter stopped", lambda: not self.pid("porter_server"))

    def release_build(self):
        # Compared with True: a Mock standing in for the arguments answers every name truthily.
        return getattr(self.args, "release", False) is True

    def case(self, name, action, restore=(), debuggable=False):
        """run() calls setup() once, so a destructive scenario must undo itself for the next one.

        A debuggable case waits on logging only a debug build of the manager writes."""
        cases = getattr(self.args, "cases", None)
        if cases and name not in cases:
            # Left out of self.results entirely: a case the run never reached has no verdict.
            # It restores nothing either, having broken nothing.
            print(f"SKIP {name}", flush=True)
            return
        if debuggable and self.release_build():
            print(f"SKIP {name}, which needs a debuggable manager", flush=True)
            return
        started = time.monotonic()
        result = {"name": name}
        try:
            result["details"] = action()
            result["passed"] = True
            print(f"PASS {name}", flush=True)
        except Exception:
            result["passed"] = False
            result["failure"] = traceback.format_exc()
            raise
        finally:
            result["seconds"] = time.monotonic() - started
            self.results.append(result)
            (self.output / f"{name}-logcat.txt").write_text(self.adb("logcat", "-d", "-v", "threadtime", check=False))
            if restore:
                try:
                    self.restore(*restore)
                except Exception:
                    result["restore_failure"] = traceback.format_exc()
                    # Everything after this runs against a compromised fixture, so the run stops
                    # here. Only when the scenario itself passed: re-raising while its own failure
                    # is propagating would replace the diagnosis with this one.
                    if "failure" not in result:
                        result["passed"] = False
                        raise

    def setup(self):
        assert self.args.serial.startswith("emulator-"), "A disposable emulator serial is required"
        assert self.shell("getprop", "ro.kernel.qemu") == "1", "Refusing a physical device"
        assert self.shell("id", "-u") == "2000", "ADB must run as shell, not root"
        installed = set(self.shell("pm", "list", "packages").splitlines())
        assert not installed.intersection("package:" + p for p in (MANAGER, COMPAT, NATIVE, LEGACY)), "Use a fresh emulator"
        for apk in (self.args.manager, self.args.native, self.args.legacy):
            self.adb("install", str(apk.resolve()))
        # The app sandbox cannot read this file; the user service must run as the ADB shell.
        self.shell("sh", "-c", f"printf %s {shlex.quote(PAYLOAD)} > /data/local/tmp/porter-probe.txt; chmod 600 /data/local/tmp/porter-probe.txt")
        self.open_home()
        self.start_service()

    def run(self):
        self.case("setup", self.setup)
        self.case("standalone", lambda: self.grant_and_revoke(NATIVE, PERMISSION))

        def app_list_grant():
            # The probe asks nothing after its refusal, so a grant it logs afterwards is one the
            # server told it about.
            self.launch_probe(NATIVE)
            self.tap("Deny")
            self.expect_log(NATIVE, "DENIED")
            self.open_home()
            self.tap("Applications")
            self.tap(NATIVE, scroll=True, screenshot="app-list-grant")
            self.expect_log(NATIVE, "PERMISSION granted")
            self.tap(NATIVE, scroll=True)
            self.until("switching the app off stops it", lambda: not self.pid(NATIVE))
        self.case("app-list-grant", app_list_grant, restore=("grants",))

        def app_list_fallback():
            # CI's emulators answer the context lookup, so only this reaches the hidden-API path.
            self.shell("setprop", FORCE_PM_FALLBACK, "1")
            server = self.pid("porter_server")
            self.open_home()
            self.tap("Applications")
            self.until(f"{NATIVE} in the app list", lambda: self.locate(NATIVE, scroll=True))
            forced = self.adb("logcat", "-d", "--pid=" + server, "-s", "InstalledPackagesCompat:I", "*:S")
            assert "Hidden API lookup forced" in forced, "The server never took the hidden-API path"
        self.case("app-list-fallback", app_list_fallback, restore=("pm-fallback",))

        def debug_recording():
            if int(self.shell("getprop", "ro.build.version.sdk")) >= 33:
                # Otherwise the consent dialog's confirm opens the permission controller instead.
                self.shell("pm", "grant", MANAGER, "android.permission.POST_NOTIFICATIONS")

            def open_support():
                # CLEAR_TOP destroys the support screen opened earlier, so it is navigated again.
                self.open_home()
                self.tap(desc="Settings")
                self.tap("Help & support", scroll=True)

            def stop_recording():
                # Navigated every time: the probe may be in front, and tap only sees the visible window.
                open_support()
                self.tap("Stop recording")
                self.tap("Stop recording", occurrence=1)
                self.until("stopped recording", lambda: any(node.get("text") == "Record debug log"
                                                            for node in self.ui().iter("node")))

            def events_of(path):
                return self.recording(f"cat {path}/events.txt 2>/dev/null")

            def in_server_log(path, pid, message):
                # Narrowed on the device: under the lease the whole file is too much to pull per poll.
                return logged_by(self.recording(f"grep -F {shlex.quote(message)} {path}/server.log 2>/dev/null"),
                                 pid, message)

            def stream_supervisor(server_pid):
                return self.until("the service spawned the stream's supervisor",
                                  lambda: [pid for pid, started in self.spawned(server_pid).items()
                                           if "logcat" in started])

            def followed_from_its_start(path, description):
                """Starts a service under a running recording, and its pid once the recording has it."""
                self.start_service()
                server_pid = self.pid("porter_server")
                self.until(f"{description}: a stream attached to {server_pid}",
                           lambda: any(pid == server_pid for pid, _ in stream_attaches(events_of(path))),
                           timeout=60)
                # Read after the wait: the lease outcome is written before the attached line.
                events = events_of(path)
                assert lease_granted(events, server_pid), events
                # An INFO line, so it is there without the lease. Only a replay from the recording's
                # start reaches it: the stream is opened after the service logged it.
                self.until(f"{description}: server.log carries {server_pid} starting",
                           lambda: in_server_log(path, server_pid, "starting server..."))
                return server_pid

            # No service when the recording starts, so it has to pick up the one started next.
            self.kill_server()
            self.until("the server is gone", lambda: not self.pid("porter_server"))
            open_support()
            self.tap("Record debug log")
            self.tap("Record debug log", occurrence=1, screenshot="debug-recording-consent")
            session = self.until("recording session", lambda: self.recording("cat no_backup/debug-logs/active 2>/dev/null"))
            path = "no_backup/debug-logs/" + session
            self.until("the recording waits for the service", lambda: "Waiting for Porter service" in events_of(path))
            first_pid = followed_from_its_start(path, "service started after the recording")
            server_pid = first_pid
            first_supervisor = stream_supervisor(server_pid)
            assert len(first_supervisor) == 1, first_supervisor
            # Size growth is a valid liveness signal only below the rotation segment, which a run
            # this short never reaches. Past it the pair shrinks too, so watch for new content.
            baseline = self.recording_size(f"{path}/server.log")
            self.launch_probe(NATIVE)
            self.tap("Allow all the time")
            self.authorized(NATIVE)
            # The replay supplies lines on attach, so only the bytes after the baseline count.
            self.until("service log carries the probe attaching", lambda: f"attachApplication: {NATIVE}"
                       in self.recording(f"tail -c +{baseline + 1} {path}/server.log 2>/dev/null"))
            followed = self.recording_size(f"{path}/server.log")
            assert followed > baseline, (baseline, followed)
            self.launch_probe(NATIVE)
            self.until("service log keeps following", lambda: self.recording_size(f"{path}/server.log") > followed)
            streamed = self.recording_size(f"{path}/server.log")

            # A service restarted mid-recording gets a stream and a lease of its own.
            lost = events_of(path).count("Service binder lost")
            self.kill_server()
            self.until("the recording saw the service go",
                       lambda: events_of(path).count("Service binder lost") > lost)
            server_pid = followed_from_its_start(path, "service restarted during the recording")
            assert server_pid != first_pid, server_pid
            events = events_of(path)
            grants = [line for line in events.splitlines() if LEASE_GRANTED.fullmatch(line.strip())]
            assert len(grants) >= 2, events
            supervisor = stream_supervisor(server_pid)
            assert len(supervisor) == 1, supervisor

            stop_recording()
            # Closing the stream destroys what the service spawned for it, so the supervisor going
            # away is what says the stop reached the service rather than only the screen.
            self.until("the stopped stream's supervisor was destroyed",
                       lambda: supervisor[0] not in self.spawned(server_pid))
            events = events_of(path)
            attaches = stream_attaches(events)
            assert attaches and attaches[0] == (first_pid, 1) and attaches[-1] == (server_pid, 2), events
            # Read again rather than reused: a service replaced mid-case would take its spawned
            # processes with it and pass the teardown wait above for the wrong reason.
            assert attaches[-1][0] == self.pid("porter_server") == server_pid, events
            anchors = clock_anchors(events)
            assert in_order(anchors, ("start", "attach 1", "lost", "attach 2", "stop")), events
            for name in ("server-start.txt", "server-stop.txt",
                         f"server-attach-1-pid{first_pid}.txt", f"server-attach-2-pid{server_pid}.txt"):
                assert self.recording_size(f"{path}/{name}"), name
            (self.output / f"debug-recording-{session}.tar").write_bytes(
                self.adb("exec-out", shlex.join(self.in_manager_data(
                    f"tar -c -C no_backup/debug-logs {shlex.quote(session)}")), binary=True))

            # A second recording, for the half the first cannot show: the service destroys what it
            # spawned for a client that dies without closing anything. The stop above is the client
            # asking; this is the service noticing on its own.
            open_support()
            self.tap("Record debug log")
            self.tap("Record debug log", occurrence=1)
            abandoned = self.until("second recording session",
                                   lambda: self.recording("cat no_backup/debug-logs/active 2>/dev/null"))
            abandoned_path = "no_backup/debug-logs/" + abandoned
            self.until("attached server stream", lambda: "Server stream attached pid=" in events_of(abandoned_path))
            bereaved = self.until("the second stream's supervisor",
                                  lambda: [pid for pid, started in self.spawned(server_pid).items()
                                           if "logcat" in started])
            assert len(bereaved) == 1 and bereaved != supervisor, (bereaved, supervisor)
            # Home first: Android 7.0 restarts an app force-stopped in front for the activity below.
            self.shell("input", "keyevent", "KEYCODE_HOME")
            self.until("the manager out of the foreground", lambda: resumed_elsewhere(
                self.shell("dumpsys", "activity", "activities", check=False), MANAGER))
            self.shell("am", "force-stop", MANAGER)
            self.until("the manager to stay stopped; it came back after its force-stop",
                       lambda: not self.pid(MANAGER))
            self.until("the dead client's spawned process was destroyed",
                       lambda: bereaved[0] not in self.spawned(server_pid))
            # After the wait, not before it: a service that went away with its manager has no
            # children either, and would satisfy that wait without destroying anything.
            assert self.pid("porter_server") == server_pid, "stopping the manager app stopped the service"
            # Evidence, not an assertion. The supervisor traps TERM to take its logcat with it, and
            # what destroy() sends is not something this suite gets to choose; a logcat listed here
            # is one that outlived the supervisor that started it.
            orphans = self.remote_logcat(server_pid)

            # The recording's active marker outlives its dead client, so the next manager process
            # resumes it. What the service logs in between reaches server.log only through that
            # resume's replay. The push is logged at INFO, so it needs no lease; attachApplication
            # is DEBUG and could not stand in for it.
            push = f"send binder to user app {NATIVE} in user 0"

            def pushes():
                return logged_by(self.adb("logcat", "-d", "-v", "threadtime", "--pid=" + server_pid,
                                          "-s", "Service:V", "*:S"), server_pid, push)
            earlier = set(pushes())
            # Peek instead of binding: binding starts the manager, which would resume the recording
            # before the Home launch below.
            self.launch_probe(NATIVE, peek=True)
            gap = self.until("the service pushed the probe its binder",
                             lambda: [line for line in pushes() if line not in earlier])[-1]
            # Once the peek has returned, so the check below comes after the probe's last call.
            self.expect_log(NATIVE, "PEEK version=")
            assert not self.pid(MANAGER), "launching the probe started the manager, which could read the push live"
            events = events_of(abandoned_path)
            assert "Clock resume" not in events, events
            self.open_home()
            self.until("the relaunched manager resumed the recording",
                       lambda: "Clock resume" in events_of(abandoned_path), timeout=60)
            self.until("the resumed recording attached the same service again",
                       lambda: [pid for pid, _ in stream_attaches(events_of(abandoned_path))].count(server_pid) >= 2,
                       timeout=60)
            self.until("server.log carries the push logged while no manager ran",
                       lambda: gap in in_server_log(abandoned_path, server_pid, push))
            resumed = stream_attaches(events_of(abandoned_path))
            stop_recording()
            # The stop writes this last, after the screen has changed; removing the session before
            # then would race that write.
            self.until("the resumed recording finished",
                       lambda: self.recording_size(f"{abandoned_path}/server-stop.txt"))
            self.recording("rm -rf no_backup/debug-logs")

            self.shell("pm", "revoke", NATIVE, PERMISSION)
            self.shell("am", "force-stop", NATIVE)
            self.until("probe user-service cleanup completed", lambda: not self.pid(NATIVE + ":porter-probe"))
            return {"session": session, "first_server": first_pid, "restarted_server": server_pid,
                    "attaches": attaches, "clock_anchors": anchors, "baseline": baseline,
                    "followed": followed, "streamed": streamed, "first_supervisor": first_supervisor[0],
                    "supervisor": supervisor[0], "abandoned": abandoned, "bereaved": bereaved[0],
                    "orphaned_logcats": orphans, "resumed_attaches": resumed, "replayed_push": gap}
        self.case("debug-recording", debug_recording)

        def companion():
            self.adb("install", str(self.args.compat.resolve()))
            self.start_service()
            return self.grant_and_revoke(LEGACY, LEGACY_PERMISSION)
        self.case("compatibility", companion)

        def coexistence():
            if f"package:{COMPAT}" in self.shell("pm", "list", "packages").splitlines():
                self.adb("uninstall", COMPAT)
            self.adb("install", str(self.args.shizuku.resolve()))
            self.start_service(COMPAT)
            original_pid = self.pid("shizuku_server")
            self.start_service()
            assert self.pid("shizuku_server") == original_pid, "Starting Porter replaced Shizuku"
            self.launch_probe(NATIVE)
            self.tap("Allow all the time")
            self.authorized(NATIVE)
            self.launch_probe(LEGACY)
            self.tap("Allow all the time", COMPAT)
            self.authorized(LEGACY, require_manager_guard=False)
            self.stop_porter()
            assert self.pid("shizuku_server") == original_pid, "Stopping Porter stopped Shizuku"
            self.launch_probe(LEGACY)
            self.authorized(LEGACY, require_manager_guard=False)
            self.start_service()
            self.launch_probe(NATIVE)
            self.authorized(NATIVE)
            porter_pid = self.pid("porter_server")
            self.start_service(COMPAT)
            assert self.pid("porter_server") == porter_pid, "Restarting Shizuku replaced Porter"
            self.launch_probe(NATIVE)
            self.authorized(NATIVE)
            self.launch_probe(LEGACY)
            self.authorized(LEGACY, require_manager_guard=False)
            return {"porter_pid": porter_pid, "shizuku_pid": self.pid("shizuku_server")}
        self.case("coexistence", coexistence)
        def porsh():
            with zipfile.ZipFile(self.args.manager.resolve()) as archive:
                for name in ("porsh", "porsh.dex"):
                    extracted = self.output / name
                    extracted.write_bytes(archive.read("assets/" + name))
                    self.adb("push", str(extracted), f"{PORSH_DIR}/{name}")
            # app_process refuses a writable dex on Android 14+. Doing it here also keeps the
            # script's own chmod branch quiet, which would otherwise print to the stdout the
            # byte-exact assertions below read.
            self.shell("chmod", "400", f"{PORSH_DIR}/porsh.dex")
            # adb shell is uid 2000, which owns more than one package, so the loader takes its
            # environment branch. The server only requires the package to belong to the uid.
            invoke = f"PORSH_APPLICATION_ID=com.android.shell sh {PORSH_DIR}/porsh"

            def redirected(name, script):
                # The redirections wrap the porsh invocation itself. Inside -c they would point
                # the remote shell's own descriptors at the files and never exercise the pipes.
                return (f"{invoke} -c {shlex.quote(script)}"
                        f" > {PORSH_DIR}/{name}-stdout 2> {PORSH_DIR}/{name}-stderr;"
                        f" echo $? > {PORSH_DIR}/{name}-status")

            def evidence(name):
                # Bytes, because adb()'s text mode strips the whitespace under test.
                return self.adb("exec-out", "cat", f"{PORSH_DIR}/{name}", binary=True)

            def status(name):
                # adb() raises on a non-zero exit and discards the code, so the device records it.
                return int(evidence(f"{name}-status").decode())

            def prompted(name, script, answer):
                # adb shell is a client like any other: porsh attaches as com.android.shell, and
                # the server admits uid 2000 on the user's decision alone, which it asks for on
                # the manager's prompt. porsh waits on that prompt, so it cannot be the foreground
                # adb call; it runs detached while the answer is tapped from here.
                pending = self.detached("sh", "-c", redirected(name, script))
                try:
                    self.tap(answer, screenshot=f"porsh-{name}")
                    self.settle(pending)
                finally:
                    if pending.poll() is None:
                        pending.kill()

            def terminal(script):
                # -tt gives the device side a pty although this side has none. That pty carries
                # stdout and stderr together, with \n turned into \r\n.
                command = ["adb", "-s", self.args.serial, "shell", "-tt", script]
                for attempt in range(TRANSPORT_ATTEMPTS):
                    try:
                        result = subprocess.run(command, stdin=subprocess.DEVNULL,
                                                capture_output=True, timeout=ADB_TIMEOUT)
                    except subprocess.TimeoutExpired:
                        with (self.output / "commands.log").open("a") as log:
                            log.write(f"{shlex.join(command)}\ntimed out\n")
                        raise
                    # adb's own errors stay on stderr; the device's streams arrive on stdout.
                    stderr = result.stderr.decode(errors="replace")
                    with (self.output / "commands.log").open("a") as log:
                        log.write(f"{shlex.join(command)}\nexit {result.returncode}\n{stderr}")
                    if not (result.returncode and TRANSPORT_FAILURE.search(stderr)):
                        break
                    if attempt < TRANSPORT_ATTEMPTS - 1:
                        time.sleep(TRANSPORT_BACKOFF)
                return result.returncode, result.stdout

            # A refusal is one-time, so the same prompt comes back for the grant that follows.
            prompted("denied", "printf hello", "Deny")
            assert status("denied") == 1, status("denied")
            assert evidence("denied-stdout") == b"", evidence("denied-stdout")
            assert evidence("denied-stderr") == b"Permission denied\n", evidence("denied-stderr")

            prompted("banner", "printf hello", "Allow all the time")
            assert status("banner") == 0, evidence("banner-stderr")
            # Byte-exact: an unconditional "Entering shell..." here breaks command substitution.
            assert evidence("banner-stdout") == b"hello", evidence("banner-stdout")

            self.shell("sh", "-c", redirected("exit", "exit 37"))
            assert status("exit") == 37, status("exit")

            self.shell("sh", "-c", redirected("streams", "printf out; printf err >&2"))
            assert evidence("streams-stdout") == b"out", evidence("streams-stdout")
            assert evidence("streams-stderr") == b"err", evidence("streams-stderr")

            # >&2 duplicates the stderr pipe onto stdout before 2> silences dd's own summary.
            self.shell("sh", "-c", redirected(
                "bulk", f"dd if=/dev/zero bs=4096 count={PORSH_BULK // 4096} >&2 2>/dev/null"))
            bulk = evidence("bulk-stderr")
            assert status("bulk") == 0, status("bulk")
            assert bulk == b"\0" * PORSH_BULK, len(bulk)

            self.shell("sh", "-c", redirected("tail", "printf out; printf last >&2; exit 3"))
            assert status("tail") == 3, status("tail")
            assert evidence("tail-stdout") == b"out", evidence("tail-stdout")
            # The last write before exit is the one truncated when stderr is not drained.
            assert evidence("tail-stderr") == b"last", evidence("tail-stderr")

            # A signal ends the shell with 128 plus its number, as a local shell reports it.
            self.shell("sh", "-c", redirected("signal", "kill -TERM $$"))
            assert status("signal") == 143, status("signal")

            # Every stream on a terminal: porsh switches it to raw mode and has to restore it.
            # Android 7 has no stty, so there both sides of the comparison are empty.
            code, output = terminal(
                f"before=$(stty -g 2>/dev/null); {invoke} -c 'tty; printf err >&2; exit 7'; status=$?;"
                f" [ \"$(stty -g 2>/dev/null)\" = \"$before\" ] || printf ' still raw'; exit $status")
            assert code == 7, (code, output)
            assert re.fullmatch(rb"/dev/pts/\d+\r\nerr", output), output

            # `porsh -c cmd > file` from a terminal: stderr still has to reach the terminal, and
            # more of it than a pty buffers must not block the command.
            code, output = terminal(f"{invoke} -c " + shlex.quote(
                f"printf out; dd if=/dev/zero bs=4096 count={PORSH_BULK // 4096} >&2 2>/dev/null;"
                f" printf err >&2; exit 5") + f" > {PORSH_DIR}/piped-stdout")
            assert code == 5, (code, output[-64:])
            assert evidence("piped-stdout") == b"out", evidence("piped-stdout")
            assert output == b"\0" * PORSH_BULK + b"err", (len(output), output[-64:])

            # The same shape writing more to /dev/tty than a pty buffers: it must still finish.
            code, output = terminal(f"{invoke} -c " + shlex.quote(
                f"dd if=/dev/zero bs=4096 count={PORSH_BULK // 4096} > /dev/tty 2>/dev/null; exit 6")
                + f" > {PORSH_DIR}/devtty-stdout")
            assert code == 6, (code, output[-64:])

            # A client that dies takes its whole command with it, not only the shell it started.
            # The probe's name is computed on the device, so the client's own arguments never match.
            def probes():
                return [row.split(None, 1)[0] for row in self.processes("PID,ARGS")
                        if "porsh-orphan-42" in row]

            pending = self.detached("sh", "-c", f"{invoke} -c " + shlex.quote(
                "sh -c 'sleep 300; :' porsh-orphan-$((6*7)) & wait"))
            try:
                deadline = time.monotonic() + 20
                while not probes():
                    assert time.monotonic() < deadline, "the probe never started"
                    time.sleep(0.5)
                self.shell("kill", "-9", *self.pid("porsh").split())
                deadline = time.monotonic() + 10
                while survivors := probes():
                    assert time.monotonic() < deadline, f"outlived its client: {survivors}"
                    time.sleep(0.5)
            finally:
                # Whatever a failed attempt left behind would disturb the steps that follow.
                leftovers = self.pid("porsh").split() + probes()
                if leftovers:
                    self.shell("kill", "-9", *leftovers, check=False)
                if pending.poll() is None:
                    pending.kill()
                pending.communicate()

            # A stalled service must end the shell with a message instead of hanging forever.
            # SIGSTOP reproduces deterministically what a binder-buffer exhaustion does by chance.
            server = self.pid("porter_server")
            try:
                self.shell("kill", "-STOP", server)
                self.shell("sh", "-c", redirected("stalled", "printf hello"))
            finally:
                self.shell("kill", "-CONT", server)
            assert status("stalled") != 0, status("stalled")
            assert b"timed out" in evidence("stalled-stderr"), evidence("stalled-stderr")
            return {"exit_status": status("exit"), "bulk_bytes": len(bulk),
                    "stalled_status": status("stalled")}
        self.case("porsh", porsh)

        self.reconciliation()

    def allow_if_requested(self, package=NATIVE, prompt_package=MANAGER, screenshot=None):
        """Grants the probe when it asked, and does nothing when it already holds the permission.

        A probe that already holds the permission reports AUTHORIZED straight away and never asks,
        so waiting for the dialog would wait for a window that cannot appear. Which of the two
        happens is decided on the device, so both are watched for at once.

        The dump this polls with gets the shorter budget, and a dump that fails inside it counts
        as "no dialog yet" rather than ending the case: it is one look among many rather than the
        answer, and on the full budget a single pathological screen spends the whole wait in one
        poll. What tolerates an unreadable screen is the outer wait, which is still 30s of looks.
        """
        def asking():
            try:
                return self.locate("Allow all the time", prompt_package, budget=UI_POLL_TIMEOUT)
            except RuntimeError:
                return None

        state = self.until(
            f"{package} authorized or asking for permission",
            lambda: ("granted" if package + " AUTHORIZED" in self.logs()
                     else "asked" if asking() else None))
        if state == "asked":
            self.tap("Allow all the time", prompt_package, screenshot=screenshot)

    def authorized_daemon(self):
        """A granted probe holding a daemon user service, and that daemon's pid."""
        self.launch_probe(NATIVE, daemon=True)
        self.allow_if_requested()
        self.authorized(NATIVE)
        return self.until("privileged user service", lambda: self.pid(NATIVE + ":porter-probe"))

    def hand_over_reason(self, package):
        """Which path ended the original signer's record, asserting only that one of them did.

        Two are correct: the bind-time check refusing the record when the replacement asks for it,
        and the host scan removing it on its own deadline. Which arrives first is a function of how
        fast the scenario ran against a 15s scan, so demanding either one fails runs where the code
        did its job by the other route. What must hold is that the daemon did not survive for a
        reason nobody recorded.

        The bind-time behaviour itself is pinned deterministically by UserServiceBindIdentityTest,
        on both the reuse and the noCreate path, so nothing here has to force that race.
        """
        warnings = self.adb("logcat", "-d", "-s", "UserServiceManager:W", "ApkReconciler:W", "*:S")
        refused = f"does not belong to the current installation of {package}" in warnings
        removed = f"host replaced {package}" in warnings
        assert refused or removed, "nothing recorded why the original signer's daemon went away"
        return "bind" if refused else "scan"

    def reconciliation(self):
        # The cases before this one leave grants and probe installations behind, so this block
        # establishes its own baseline instead of inheriting whichever one ran last.
        self.restore("probes", "grants")

        def server_crash_recovery():
            """A server killed outright, and the clients that outlive it."""
            service_pid = self.authorized_daemon()
            client_pid = self.probe_pid
            server_pid = self.pid("porter_server")
            manager_pid = self.pid(MANAGER)
            boundary = len(self.logs())
            # The manager's log is never cleared and earlier cases replace the server, so a
            # CRASHED already in the buffer says nothing about the kill below.
            def manager_log():
                return self.adb("logcat", "-d", "--pid=" + manager_pid, "-s", "PorterStateMachine:D", "*:S")
            manager_boundary = len(manager_log())
            self.shell("su", "0", "kill", "-9", server_pid)
            self.until("the server is gone", lambda: not self.pid("porter_server"))

            # The client process stays: what it loses is the connection, not its own life, so it
            # has to be told rather than simply disappear with the server.
            self.until("the client is told the connection died",
                       lambda: NATIVE + " BINDER_DEAD" in self.logs()[boundary:])
            assert self.pid(NATIVE) == client_pid, "the client died with the server"
            self.until("the user service follows the server that hosted it",
                       lambda: not self.pid(NATIVE + ":porter-probe"))
            # Only a debug build logs the state it moved to.
            if not self.release_build():
                self.until("the manager calls it a crash",
                           lambda: "PorterStateMachine: CRASHED" in manager_log()[manager_boundary:])

            self.start_service()
            # The same process, reconnected: a privileged call working again is the assertion, not
            # a binder arriving. The grant is not asked for a second time because it was saved.
            for message in (f"BINDER uid=2000 version={PORTER_PROTOCOL_VERSION}",
                            "AUTHORIZED managerOperationDenied=true",
                            "USER_SERVICE uid=2000 file=" + PAYLOAD):
                self.until(f"after the restart: {message}",
                           lambda m=message: NATIVE + " " + m in self.logs()[boundary:], timeout=60)
            assert self.pid(NATIVE) == client_pid, "the client was replaced rather than reconnected"
            assert self.pid("porter_server") != server_pid
            return {"client_pid": client_pid, "server_pid": server_pid, "service_pid": service_pid}
        self.case("server-crash-recovery", server_crash_recovery, restore=("probes", "grants", "service"))

        def force_stop_stays_stopped():
            """A client stopped from outside is not started again by the server delivering to it."""
            self.launch_probe(NATIVE)
            self.allow_if_requested()
            self.authorized(NATIVE)
            # Restarted while the client is already in front, so the new server has not recorded it
            # starting by the time the force-stop comes.
            client_pid = self.probe_pid
            boundary = len(self.logs())
            self.kill_server()
            self.until("the server is gone", lambda: not self.pid("porter_server"))
            self.start_service()
            self.until("the running client reconnected",
                       lambda: f"{NATIVE} BINDER uid=2000" in self.logs()[boundary:], timeout=60)
            assert self.pid(NATIVE) == client_pid, "the client was replaced rather than reconnected"
            # Home first: Android 7.0 restarts an app force-stopped in front for its activity.
            self.shell("input", "keyevent", "KEYCODE_HOME")
            self.until("the probe out of the foreground", lambda: resumed_elsewhere(
                self.shell("dumpsys", "activity", "activities", check=False), NATIVE))

            def starts():
                return [line for line in self.adb("logcat", "-d", "-v", "threadtime", "-s",
                                                  "ActivityManager:I", "*:S").splitlines()
                        if "Start proc " in line and f":{NATIVE}/" in line]
            earlier_starts = set(starts())
            self.shell("am", "force-stop", NATIVE)

            def restarts():
                # Read from the log rather than from pidof: a start that died again between two
                # looks is still a start.
                return [line for line in starts() if line not in earlier_starts]
            deadline = time.monotonic() + STOPPED_WATCH
            while time.monotonic() < deadline:
                started = restarts()
                assert not started, f"the force-stopped client was started again: {started}"
                time.sleep(0.4)
            assert not self.pid(NATIVE), "the force-stopped client is still running"

            # A client started for its provider alone still has to be delivered to. The server logs
            # a push only once the client's provider has taken it.
            server_pid = self.pid("porter_server")
            push = f"send binder to user app {NATIVE} in user 0"

            def pushes():
                return logged_by(self.adb("logcat", "-d", "-v", "threadtime", "--pid=" + server_pid,
                                          "-s", "Service:V", "*:S"), server_pid, push)
            earlier = set(pushes())
            # The query only has to start the process; what the provider answers is irrelevant.
            self.shell("content", "query", "--uri", f"content://{NATIVE}.porter.api", check=False)
            self.until("a client started for its provider is delivered to",
                       lambda: [line for line in pushes() if line not in earlier])

            # And the stop broke nothing: the next launch connects and is still authorized.
            self.launch_probe(NATIVE)
            self.authorized(NATIVE)
            return {"client_pid": client_pid}
        self.case("force-stop-stays-stopped", force_stop_stays_stopped, restore=("probes", "grants"))

        def root_server():
            """Everything the ADB-started server is asked for, asked of a uid 0 one."""
            assert self.root_available(), "this image's su does not give the ADB shell root"
            self.kill_server()
            self.until("the shell server is gone", lambda: not self.pid("porter_server"))
            self.start_service(root=True)
            server_pid = self.pid("porter_server")
            assert self.shell("su", "0", "stat", "-c", "%u", "/proc/" + server_pid) == "0"

            self.launch_probe(NATIVE, daemon=True, server_uid="0")
            self.allow_if_requested(screenshot="root-permission")
            self.authorized(NATIVE, server_uid="0")
            service_pid = self.until("privileged user service", lambda: self.pid(NATIVE + ":porter-probe"))
            # The user service inherits the server's identity, so under root it reads the payload
            # as root rather than as the shell.
            assert self.shell("su", "0", "stat", "-c", "%u", "/proc/" + service_pid) == "0"
            return {"server_pid": server_pid, "service_pid": service_pid}
        self.case("root-server", root_server, restore=("probes", "grants", "shell-service"))

        def decisions_across_start_modes():
            """One decision database, written by a uid 0 server and read by a uid 2000 one."""
            assert self.root_available(), "this image's su does not give the ADB shell root"
            self.kill_server()
            self.until("the shell server is gone", lambda: not self.pid("porter_server"))
            self.start_service(root=True)

            self.launch_probe(NATIVE, server_uid="0")
            self.tap("Allow all the time", MANAGER)
            self.authorized(NATIVE, server_uid="0")
            uid = self.app_uid(NATIVE)
            assert self.decision_flags(uid) & DECISION_ALLOWED, "the root server saved nothing"
            # Root writes into the ADB shell's directory, so it hands the file back rather than
            # leaving one the shell cannot open the next time Porter is started that way.
            # Android 7's stat pads the group name ("shell:   shell").
            owner = "".join(self.shell("su", "0", "stat", "-c", "%U:%G", DECISIONS).split())
            assert owner == "shell:shell", f"the root server left the database owned by {owner}"

            self.kill_server()
            self.until("the root server is gone", lambda: not self.pid("porter_server"))
            self.start_service()
            self.launch_probe(NATIVE)
            # No prompt: an answer given under one start mode is still the answer under the other.
            self.authorized(NATIVE)
            assert self.decision_flags(uid) & DECISION_ALLOWED
            return {"uid": uid, "owner": owner}
        self.case("decisions-across-start-modes", decisions_across_start_modes,
                  restore=("probes", "grants", "shell-service"))

        def daemon_host_uninstalled():
            service_pid = self.authorized_daemon()
            self.shell("am", "force-stop", NATIVE)
            # The daemon runs as shell, outside the app's process group, so force-stop leaves it
            # alone. That is what makes host-process death useless as the removal signal.
            time.sleep(2)
            assert self.pid(NATIVE + ":porter-probe") == service_pid, "the daemon did not outlive its host"
            self.adb("uninstall", NATIVE)
            self.until("daemon removed after host uninstall",
                       lambda: not self.pid(NATIVE + ":porter-probe"), timeout=HOST_SCAN_TIMEOUT)
            return {"service_pid": service_pid}
        self.case("daemon-host-uninstalled", daemon_host_uninstalled, restore=("probes", "grants"))

        def daemon_host_upgraded():
            service_pid = self.authorized_daemon()
            self.adb("install", "-r", str(self.args.native.resolve()))
            time.sleep(HOST_SETTLE)
            assert self.pid(NATIVE + ":porter-probe") == service_pid, "an upgrade removed a live daemon"
            self.adb("uninstall", NATIVE)
            self.until("daemon removed after the upgrade",
                       lambda: not self.pid(NATIVE + ":porter-probe"), timeout=HOST_SCAN_TIMEOUT)
            return {"service_pid": service_pid}
        self.case("daemon-host-upgraded", daemon_host_upgraded, restore=("probes", "grants"))

        def host_removed_from_one_user():
            service_pid = self.authorized_daemon()
            user = self.create_user("porter-ci")
            self.install_for_user(user, NATIVE, self.args.native)
            self.shell("pm", "uninstall", "--user", "0", NATIVE)
            # Cross-user sharing is a documented contract: any user holding a matching installation
            # keeps the record alive.
            time.sleep(HOST_SETTLE)
            assert self.pid(NATIVE + ":porter-probe") == service_pid, "a record shared across users was removed"
            self.shell("pm", "uninstall", "--user", user, NATIVE)
            self.until("daemon removed once no user holds it",
                       lambda: not self.pid(NATIVE + ":porter-probe"), timeout=HOST_SCAN_TIMEOUT)
            return {"service_pid": service_pid, "user": user}
        self.case("host-removed-from-one-user", host_removed_from_one_user,
                  restore=("users", "probes", "grants"))

        def replaced_by_a_foreign_signer(*, require_survival=True):
            """Replace the host of an authorized daemon without preparing an APK in the gap."""
            apk = self.foreign_probe()
            self.authorized_daemon()
            # Destruction of a prior daemon is asynchronous; never capture a multi-PID string as
            # the original, and wait only after the new service's authorization was checked.
            original = self.until(
                "one privileged user service",
                lambda: (pids := self.service_pids(NATIVE)) and len(pids) == 1 and next(iter(pids)))
            # A scan in the uninstall/install gap can confirm absence instead of replacement.
            time.sleep(HOST_QUIET)
            # Include that gap in the evidence, so host-absent cannot be cleared away.
            self.clear_logcat()
            self.adb("uninstall", NATIVE)
            self.adb("install", str(apk))
            if require_survival:
                assert original in self.service_pids(NATIVE), "the daemon was gone before the bind"
            return original

        def foreign_signer_peeks():
            # peek is the noCreate path, which hands back an existing binder without creating.
            original = replaced_by_a_foreign_signer()
            self.launch_probe(NATIVE, peek=True)
            self.allow_if_requested()
            self.expect_log(NATIVE, "PEEK version=-1")
            # Record destruction is dispatched one-way, so the process outlives the refusal by an
            # unbounded moment. Polling asserts the same property without racing the teardown.
            self.until("the noCreate path released the old daemon",
                       lambda: original not in self.service_pids(NATIVE))
            after = self.service_pids(NATIVE)
            return {"original": original, "after": sorted(after),
                    "reason": self.hand_over_reason(NATIVE)}
        self.case("foreign-signer-peeks", foreign_signer_peeks, restore=("probes", "grants"))

        def foreign_signer_binds():
            # No peek first: the reuse path has to refuse the record on its own.
            original = replaced_by_a_foreign_signer()
            self.launch_probe(NATIVE, daemon=True)
            self.allow_if_requested()
            self.authorized(NATIVE)
            self.until("the replacement was refused the original signer's daemon",
                       lambda: original not in self.service_pids(NATIVE))
            after = self.service_pids(NATIVE)
            return {"original": original, "after": sorted(after),
                    "reason": self.hand_over_reason(NATIVE)}
        self.case("foreign-signer-binds", foreign_signer_binds, restore=("probes", "grants"))

        def foreign_signer_never_binds():
            # Scan cleanup may already have completed before install returned. Unlike bind/peek,
            # this case needs no surviving daemon to hand over, only replacement evidence and exit.
            service_pid = replaced_by_a_foreign_signer(require_survival=False)

            def replacement_logged():
                warnings = self.adb("logcat", "-d", "-s", "ApkReconciler:W", "*:S")
                assert f"host absent {NATIVE} " not in warnings, \
                    "invalid replacement setup: host absent during uninstall/install"
                return f"host replaced {NATIVE} " in warnings

            def scanned():
                if replacement_logged():
                    return True
                if service_pid not in self.service_pids(NATIVE):
                    # The scan logs before dispatching destruction, but it may have landed
                    # between the warning read and the PID read. Check fresh evidence once.
                    assert replacement_logged(), \
                        "lost replacement setup: original daemon exited without host-replaced evidence"
                    return True
                return False

            self.until("the host scan removed the replaced package's record", scanned,
                       timeout=HOST_SCAN_TIMEOUT)
            self.until("the host scan released the original daemon",
                       lambda: service_pid not in self.service_pids(NATIVE), timeout=HOST_SCAN_TIMEOUT)
            return {"service_pid": service_pid}
        self.case("foreign-signer-never-binds", foreign_signer_never_binds, restore=("probes", "grants"))

        def non_daemon_control():
            self.launch_probe(NATIVE)
            self.allow_if_requested()
            self.authorized(NATIVE)
            service_pid = self.until("privileged user service", lambda: self.pid(NATIVE + ":porter-probe"))
            self.shell("am", "force-stop", NATIVE)
            # Unchanged behaviour: a non-daemon record is removed by connection death alone.
            self.until("non-daemon service ends with its connection",
                       lambda: not self.pid(NATIVE + ":porter-probe"))
            return {"service_pid": service_pid}
        self.case("non-daemon-control", non_daemon_control, restore=("grants",))

        def daemon_revoked_in_settings():
            daemon_pid = self.authorized_daemon()
            # What revoking the permission in Android's settings does. Killing the app leaves a
            # daemon running as shell; only the server's own reconciliation ends it, pushed by the
            # permission observer where the platform lets the shell register one and by the
            # server's polling where it does not.
            self.shell("pm", "revoke", NATIVE, PERMISSION)
            self.until("a settings revocation terminates the daemon",
                       lambda: not self.pid(NATIVE + ":porter-probe"), timeout=PERMISSION_POLL_TIMEOUT)
            return {"daemon_pid": daemon_pid}
        self.case("daemon-revoked-in-settings", daemon_revoked_in_settings, restore=("grants",))

        def manager_stopped_then_uninstalled():
            server_pid = self.pid("porter_server")
            service_pid = self.authorized_daemon()
            self.shell("am", "force-stop", MANAGER)
            time.sleep(2)
            assert self.pid("porter_server") == server_pid, "stopping the manager app stopped the server"
            self.adb("uninstall", MANAGER)
            self.until("server exits once the manager is gone",
                       lambda: not self.pid("porter_server"), timeout=MANAGER_SCAN_TIMEOUT)
            self.until("user service follows the server",
                       lambda: not self.pid(NATIVE + ":porter-probe"), timeout=MANAGER_SCAN_TIMEOUT)
            return {"server_pid": server_pid, "service_pid": service_pid}
        self.case("manager-stopped-then-uninstalled", manager_stopped_then_uninstalled,
                  restore=("manager", "probes", "grants", "service"))

        def manager_upgraded_then_uninstalled():
            server_pid = self.pid("porter_server")
            self.adb("install", "-r", str(self.args.manager.resolve()))
            time.sleep(MANAGER_SETTLE)
            assert self.pid("porter_server") == server_pid, "an ordinary manager upgrade killed the server"
            service_pid = self.authorized_daemon()
            self.adb("uninstall", MANAGER)
            self.until("server exits once the manager is gone",
                       lambda: not self.pid("porter_server"), timeout=MANAGER_SCAN_TIMEOUT)
            self.until("user service follows the server",
                       lambda: not self.pid(NATIVE + ":porter-probe"), timeout=MANAGER_SCAN_TIMEOUT)
            return {"server_pid": server_pid, "service_pid": service_pid}
        self.case("manager-upgraded-then-uninstalled", manager_upgraded_then_uninstalled,
                  restore=("manager", "probes", "grants", "service"))

        def secondary_user_prompt():
            """The manager's prompt lives in user 0, so another user on screen cannot answer it."""
            user = self.create_user("porter-ci-client")
            self.shell("am", "start-user", user)
            self.install_for_user(user, NATIVE, self.args.native)
            uid = self.app_uid(NATIVE, user)
            assert uid != self.app_uid(NATIVE), "the two installations share a uid"

            self.shell("am", "switch-user", user)
            self.until(f"am reports user {user}", lambda: self.shell("am", "get-current-user") == user,
                       timeout=USER_SWITCH_TIMEOUT)
            # Only the Android 16 and 17 images were seen to resume the new user's HOME. 7 leaves
            # its launcher asleep and 11 never starts it, so the wait would only time out there.
            if int(self.shell("getprop", "ro.build.version.sdk")) >= 36:
                self.wait_user_home(user)
            self.clear_logcat()
            self.launch_probe_as(NATIVE, user)
            # The point of the case: a request nobody could answer is answered rather than left
            # open. Before the server checked, this waited here until the case timed out.
            self.expect_log(NATIVE, "DENIED")
            assert not self.decision_flags(uid) & (DECISION_ALLOWED | DECISION_DENIED), \
                "a prompt that was never shown was written down as the user's answer"

            self.shell("am", "switch-user", "0")
            self.until("am reports user 0", lambda: self.shell("am", "get-current-user") == "0",
                       timeout=USER_SWITCH_TIMEOUT)
            # Before the absence check too, which a lock screen covering everything would pass.
            self.unlock()
            self.shell("am", "force-stop", "--user", user, NATIVE)
            assert self.absent("Allow all the time", MANAGER), \
                "a prompt refused in another user surfaced later"
            # Left running, the second user cost enough memory on Android 17's google_apis image
            # that the low-memory killer took the owner's probe while its prompt was on screen.
            self.shell("am", "stop-user", "-w", user)

            # The owner user's copy still works, and its answer stays its own. Whether it is
            # asked again depends on what the case before this one left it holding, which is not
            # what this case is about: standalone covers the prompt itself on every run.
            self.launch_probe(NATIVE)
            self.allow_if_requested(screenshot="after-secondary-user")
            self.authorized(NATIVE)
            assert self.decision_flags(self.app_uid(NATIVE)) & DECISION_ALLOWED, "the answered grant was not saved"
            assert not self.decision_flags(uid) & DECISION_ALLOWED, "the owner user's grant reached the other user's copy"
            return {"user": user, "uid": uid}
        self.case("secondary-user-prompt", secondary_user_prompt,
                  restore=("users", "probes", "grants"))


    def reports(self):
        (self.output / "results.json").write_text(json.dumps(self.results, indent=2))
        suite = ET.Element("testsuite", name="Porter device smoke", tests=str(len(self.results)),
                           failures=str(sum(not r["passed"] for r in self.results)))
        for result in self.results:
            case = ET.SubElement(suite, "testcase", name=result["name"], time=str(result["seconds"]))
            if not result["passed"]:
                ET.SubElement(case, "failure").text = result["failure"]
        ET.ElementTree(suite).write(self.output / "junit.xml", encoding="utf-8", xml_declaration=True)


def add_release_argument(parser):
    parser.add_argument("--release", action="store_true",
                        help="the manager is a release build: skip the cases that need a debuggable one")


def parse_args(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    for name in ("manager", "compat", "native", "legacy", "shizuku"):
        parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    add_release_argument(parser)
    parser.add_argument("--case", action="append", dest="cases", choices=CASES, metavar="NAME",
                        help="run only the named case, repeatable, in declared order; "
                             "omit to run all of: " + ", ".join(CASES))
    args = parser.parse_args(argv)
    if args.cases and "setup" not in args.cases:
        parser.error("--case setup is required: every other case needs the installs and the "
                     "service start it performs")
    return args


if __name__ == "__main__":
    smoke = Smoke(parse_args())
    try:
        smoke.run()
    finally:
        smoke.reports()
