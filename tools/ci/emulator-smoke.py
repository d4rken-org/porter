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
CASES = ("setup", "standalone", "debug-recording", "compatibility", "coexistence", "porsh",
         "server-crash-recovery", "root-server", "decisions-across-start-modes",
         "daemon-host-uninstalled", "daemon-host-upgraded", "host-removed-from-one-user",
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
# Past the first host deadline after a record was created. A scan that finds nothing changed puts
# the next one 30s out, so waiting this long leaves a stretch no scan falls into.
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
# The framework's own crash and ANR dialogs, which belong to no app under test and sit in front of
# whatever the case was about. Both offer this button; the message is phrased around the crashed
# app's name, so the wording that is not is what identifies them.
FRAMEWORK_ERROR_BUTTON = "Close app"
FRAMEWORK_ERROR_TEXT = re.compile(r"(keeps stopping|kept stopping|has stopped|isn't responding)")
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
LAUNCH_ATTEMPTS = 3
FOREIGN_PASSWORD = "porterci"
USER_ID = re.compile(r"UserInfo\{(\d+):")


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


class Smoke:
    def __init__(self, args):
        self.args = args
        self.output = args.output.resolve()
        self.output.mkdir(parents=True, exist_ok=True)
        self.results = []
        self.probe_pid = None
        self.foreign_apk = None
        self.install_hangs = 0

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
        """The package of the newest crash the device recorded, or None if it recorded none."""
        found = CRASHED_PROCESS.findall(self.adb("logcat", "-d", "-b", "crash", check=False))
        return found[-1] if found else None

    def framework_error(self, root):
        """The bounds of the button that clears a crash or ANR dialog raised by something else.

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

    def heard(self, before):
        """Whether the screen stopped being [before] within [TAP_SETTLE].

        A dump that fails inside the window answers neither way, so it is polled past rather than
        counted. What follows a window that ends undecided is another look at the button on its
        own full budget, not a tap at bounds nothing has confirmed.
        """
        deadline = time.monotonic() + TAP_SETTLE
        while True:
            try:
                if ET.tostring(self.ui(UI_POLL_TIMEOUT)) != before:
                    return True
            except RuntimeError:
                pass
            if time.monotonic() >= deadline:
                return False
            time.sleep(0.4)

    def tap(self, text=None, package=MANAGER, prefix=False, screenshot=None, scroll=False, occurrence=0, desc=None):
        """Taps a button and returns once the screen has acknowledged it.

        An injected gesture can be dropped before it reaches the window it was aimed at, and a
        dropped one leaves nothing behind: the case carries on and fails later, somewhere that
        says nothing about the tap. So the screen is read again afterwards, and a screen that is
        byte for byte the one that was tapped is a tap that never landed.

        Only an unchanged screen is tapped a second time, and only while the button is still
        there. Anything else - the dialog gone, the button gone, a different screen - is the tap
        having been acted on, however little of it has finished.
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
            self.shell("input", "tap", (left + right) // 2, (top + bottom) // 2)
            if self.heard(before):
                return
            print(f"NOTE the screen did not answer a tap on {description}", flush=True)
        raise AssertionError(f"Tapped {description} {TAP_ATTEMPTS} times and the screen never "
                             f"changed; see {self.output / 'last-ui.xml'}")

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

    def recording(self, command):
        # run-as starts in the manager's data directory, where debug sessions live.
        return self.shell("run-as", MANAGER, "sh", "-c", command, check=False)

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
        if "grants" in aspects:
            for package, permission in ((NATIVE, PERMISSION), (LEGACY, LEGACY_PERMISSION)):
                self.shell("pm", "revoke", package, permission, check=False)
                self.shell("am", "force-stop", package)
        if "service" in aspects and not self.pid("porter_server"):
            self.shell("am", "start", "-W", "-f", "0x04000000", "-n", MANAGER + "/eu.darken.porter.manager.MainActivity")
            self.start_service()
        if "shell-service" in aspects:
            # A root server left running would become the fixture for every case after this one,
            # so this replaces whatever is there rather than only filling a gap.
            self.kill_server(check=False)
            self.until("the server is gone", lambda: not self.pid("porter_server"))
            self.shell("am", "start", "-W", "-f", "0x04000000", "-n", MANAGER + "/eu.darken.porter.manager.MainActivity")
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

    def stop_porter(self):
        self.shell("am", "start", "-W", "-f", "0x04000000", "-n", MANAGER + "/eu.darken.porter.manager.MainActivity")
        self.tap("Porter is running", prefix=True)
        self.tap("Stop Porter")
        # The dialog repeats "Stop Porter" as its title, so the second match is the confirm button
        # and waiting for it also waits for the dialog to replace the single-match screen.
        self.tap("Stop Porter", occurrence=1, screenshot="running-service-dialog")
        self.until("Porter stopped", lambda: not self.pid("porter_server"))

    def case(self, name, action, restore=()):
        """run() calls setup() once, so a destructive scenario must undo itself for the next one."""
        cases = getattr(self.args, "cases", None)
        if cases and name not in cases:
            # Left out of self.results entirely: a case the run never reached has no verdict.
            # It restores nothing either, having broken nothing.
            print(f"SKIP {name}", flush=True)
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
        self.shell("am", "start", "-W", "-f", "0x04000000", "-n", MANAGER + "/eu.darken.porter.manager.MainActivity")
        self.start_service()

    def run(self):
        self.case("setup", self.setup)
        self.case("standalone", lambda: self.grant_and_revoke(NATIVE, PERMISSION))

        def debug_recording():
            if int(self.shell("getprop", "ro.build.version.sdk")) >= 33:
                # Otherwise the consent dialog's confirm opens the permission controller instead.
                self.shell("pm", "grant", MANAGER, "android.permission.POST_NOTIFICATIONS")

            def open_support():
                # CLEAR_TOP destroys the support screen opened earlier, so it is navigated again.
                self.shell("am", "start", "-W", "-f", "0x04000000", "-n", MANAGER + "/eu.darken.porter.manager.MainActivity")
                self.tap(desc="Settings")
                self.tap("Help & support", scroll=True)
            open_support()
            self.tap("Record debug log")
            self.tap("Record debug log", occurrence=1, screenshot="debug-recording-consent")
            # The active marker is written before the stream is attached, so both are waited on.
            session = self.until("recording session", lambda: self.recording("cat no_backup/debug-logs/active 2>/dev/null"))
            path = "no_backup/debug-logs/" + session
            self.until("attached server stream",
                       lambda: "Server stream attached pid=" in self.recording(f"cat {path}/events.txt 2>/dev/null"))
            server_pid = self.pid("porter_server")
            supervisor = self.until("the service spawned the stream's supervisor",
                                    lambda: [pid for pid, started in self.spawned(server_pid).items()
                                             if "logcat" in started])
            assert len(supervisor) == 1, supervisor
            # Size growth is a valid liveness signal only below the rotation segment, which a run
            # this short never reaches. Past it the pair shrinks too, so watch for new content.
            baseline = self.recording_size(f"{path}/server.log")
            self.launch_probe(NATIVE)
            self.tap("Allow all the time")
            self.authorized(NATIVE)
            # -T 1 supplies a first line on attach, so only the bytes after the baseline count.
            self.until("service log carries the probe attaching", lambda: f"attachApplication: {NATIVE}"
                       in self.recording(f"tail -c +{baseline + 1} {path}/server.log 2>/dev/null"))
            followed = self.recording_size(f"{path}/server.log")
            assert followed > baseline, (baseline, followed)
            self.launch_probe(NATIVE)
            self.until("service log keeps following", lambda: self.recording_size(f"{path}/server.log") > followed)
            streamed = self.recording_size(f"{path}/server.log")
            # The probe is in the foreground and tap only sees the visible window.
            open_support()
            self.tap("Stop recording")
            self.tap("Stop recording", occurrence=1)
            self.until("stopped recording", lambda: any(node.get("text") == "Record debug log"
                                                        for node in self.ui().iter("node")))
            # Closing the stream destroys what the service spawned for it, so the supervisor going
            # away is what says the stop reached the service rather than only the screen.
            self.until("the stopped stream's supervisor was destroyed",
                       lambda: supervisor[0] not in self.spawned(server_pid))
            events = self.recording(f"cat {path}/events.txt 2>/dev/null")
            attached = re.search(r"Server stream attached pid=(\d+)", events)
            assert attached, events
            # Read again rather than reused: a service replaced mid-case would take its spawned
            # processes with it and pass the teardown wait above for the wrong reason.
            assert attached.group(1) == self.pid("porter_server") == server_pid, events
            for name in ("server-start.txt", "server-stop.txt"):
                assert self.recording_size(f"{path}/{name}"), name
            (self.output / f"debug-recording-{session}.tar").write_bytes(
                self.adb("exec-out", "run-as", MANAGER, "tar", "-c", "-C", "no_backup/debug-logs", session, binary=True))

            # A second recording, for the half the first cannot show: the service destroys what it
            # spawned for a client that dies without closing anything. The stop above is the client
            # asking; this is the service noticing on its own.
            open_support()
            self.tap("Record debug log")
            self.tap("Record debug log", occurrence=1)
            abandoned = self.until("second recording session",
                                   lambda: self.recording("cat no_backup/debug-logs/active 2>/dev/null"))
            self.until("attached server stream", lambda: "Server stream attached pid=" in
                       self.recording(f"cat no_backup/debug-logs/{abandoned}/events.txt 2>/dev/null"))
            bereaved = self.until("the second stream's supervisor",
                                  lambda: [pid for pid, started in self.spawned(server_pid).items()
                                           if "logcat" in started])
            assert len(bereaved) == 1 and bereaved != supervisor, (bereaved, supervisor)
            self.shell("am", "force-stop", MANAGER)
            self.until("the dead client's spawned process was destroyed",
                       lambda: bereaved[0] not in self.spawned(server_pid))
            # After the wait, not before it: a service that went away with its manager has no
            # children either, and would satisfy that wait without destroying anything.
            assert self.pid("porter_server") == server_pid, "stopping the manager app stopped the service"
            # Evidence, not an assertion. The supervisor traps TERM to take its logcat with it, and
            # what destroy() sends is not something this suite gets to choose; a logcat listed here
            # is one that outlived the supervisor that started it.
            orphans = self.remote_logcat(server_pid)
            # While the manager is stopped: this recording was ended by its client dying, so its
            # active marker is still there, and a manager the system revives would resume it and
            # attach a stream to the session this is removing.
            self.recording("rm -rf no_backup/debug-logs")

            self.shell("pm", "revoke", NATIVE, PERMISSION)
            self.shell("am", "force-stop", NATIVE)
            self.until("revocation terminates user service", lambda: not self.pid(NATIVE + ":porter-probe"))
            return {"session": session, "baseline": baseline, "followed": followed,
                    "streamed": streamed, "supervisor": supervisor[0], "abandoned": abandoned,
                    "bereaved": bereaved[0], "orphaned_logcats": orphans}
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
        # The five pre-existing cases leave grants and probe installations behind, so this block
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

        def replaced_by_a_foreign_signer():
            """A live daemon of the original signer, with the replacement installed over it."""
            original = self.authorized_daemon()
            # Between the uninstall and the install the package is absent in every user. On
            # Android 7 that outlasts the scan's confirmation grace, so a scan landing there removes
            # the daemon before the replacement can bind.
            time.sleep(HOST_QUIET)
            self.adb("uninstall", NATIVE)
            # Bounds the interval hand_over_reason() reads: the replacement only exists from here
            # on, so every warning about it was logged after this point.
            self.clear_logcat()
            self.adb("install", str(self.foreign_probe()))
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
            service_pid = self.authorized_daemon()
            self.adb("uninstall", NATIVE)
            # The uninstall kills the daemon itself, so its absence proves nothing about the scan.
            # What this case is about is the scan removing the record of a package that came back
            # under a different signer, and the scan says so itself. Clearing here bounds the
            # interval to the replacement.
            self.clear_logcat()
            self.adb("install", str(self.foreign_probe()))
            self.until("the host scan removed the replaced package's record",
                       lambda: f"host replaced {NATIVE}" in self.adb(
                           "logcat", "-d", "-s", "ApkReconciler:W", "*:S"),
                       timeout=HOST_SCAN_TIMEOUT)
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


def parse_args(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    for name in ("manager", "compat", "native", "legacy", "shizuku"):
        parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
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
