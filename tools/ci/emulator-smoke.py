#!/usr/bin/env python3
"""Exercise real Binder grants on a fresh, disposable emulator using only ADB."""
import argparse
import json
from pathlib import Path
import re
import shlex
import subprocess
import time
import traceback
import zipfile
import xml.etree.ElementTree as ET

MANAGER = "eu.darken.porter"
COMPAT = "moe.shizuku.privileged.api"
NATIVE = "eu.darken.porter.probe.native"
LEGACY = "eu.darken.porter.probe.legacy"
PERMISSION = "eu.darken.porter.permission.API_V23"
LEGACY_PERMISSION = "moe.shizuku.manager.permission.API_V23"
PAYLOAD = "porter-ci-shell-access"
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
# The order run() declares, which --case narrows without ever reordering.
CASES = ("setup", "standalone", "debug-recording", "compatibility", "coexistence", "porsh")


class Smoke:
    def __init__(self, args):
        self.args = args
        self.output = args.output.resolve()
        self.output.mkdir(parents=True, exist_ok=True)
        self.results = []
        self.probe_pid = None

    def adb(self, *args, check=True, binary=False):
        command = ["adb", "-s", self.args.serial, *map(str, args)]
        for attempt in range(TRANSPORT_ATTEMPTS):
            result = subprocess.run(command, capture_output=True, timeout=45)
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

    def shell(self, *args, **kwargs):
        return self.adb("shell", shlex.join(map(str, args)), **kwargs)

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

    def ui(self):
        self.shell("uiautomator", "dump", "/data/local/tmp/porter-ci-ui.xml")
        xml = self.shell("cat", "/data/local/tmp/porter-ci-ui.xml")
        (self.output / "last-ui.xml").write_text(xml)
        return ET.fromstring(xml)

    def tap(self, text=None, package=MANAGER, prefix=False, screenshot=None, scroll=False, occurrence=0, desc=None):
        def locate():
            root = self.ui()
            found = []
            for node in root.iter("node"):
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
            if scroll:
                container = next((n for n in root.iter("node") if n.get("package") == package
                                  and n.get("scrollable") == "true"), None)
                if container is not None:
                    left, top, right, bottom = map(int, re.findall(r"\d+", container.get("bounds", "")))
                    x = (left + right) // 2
                    inset = (bottom - top) // 5
                    self.shell("input", "swipe", x, bottom - inset, x, top + inset, 300)
            return None
        wanted = repr(text) if desc is None else f"content-desc {desc!r}"
        ordinal = f" #{occurrence}" if occurrence else ""
        left, top, right, bottom = self.until(f"button {wanted}{ordinal} in {package}", locate)
        if screenshot:
            self.screenshot(screenshot)
        self.shell("input", "tap", (left + right) // 2, (top + bottom) // 2)

    def screenshot(self, name):
        (self.output / f"{name}.png").write_bytes(self.adb("exec-out", "screencap", "-p", binary=True))

    def pid(self, name):
        return self.shell("pidof", name, check=False)

    def recording(self, command):
        # run-as starts in the manager's data directory, where debug sessions live.
        return self.shell("run-as", MANAGER, "sh", "-c", command, check=False)

    def recording_size(self, path):
        return int(self.recording(f"stat -c %s {path} 2>/dev/null || echo 0") or 0)

    def start_service(self, package=MANAGER):
        name = "porter_server" if package == MANAGER else "shizuku_server"
        previous = self.pid(name)
        apk = self.shell("pm", "path", package).removeprefix("package:").splitlines()[0]
        assert apk.startswith("/data/app/") and apk.endswith("/base.apk"), apk
        abi = self.shell("getprop", "ro.product.cpu.abi")
        library_dir = {"x86": "x86", "x86_64": "x86_64", "arm64-v8a": "arm64", "armeabi-v7a": "arm"}[abi]
        self.shell(str(Path(apk).parent / "lib" / library_dir / "libshizuku.so"))
        self.until(f"new {name} process", lambda: (pid := self.pid(name)) and pid != previous)

    def launch_probe(self, package):
        for probe in (NATIVE, LEGACY):
            self.shell("am", "force-stop", probe)
        self.shell("am", "start", "-W", "-n", package + "/eu.darken.porter.probe.ProbeActivity")
        self.probe_pid = self.until("probe process", lambda: self.pid(package))
        self.expect_log(package, "BINDER uid=2000 version=13")

    def authorized(self, package, require_manager_guard=True):
        # The original Shizuku baseline does not enforce Porter's manager-only gate.
        result = "AUTHORIZED managerOperationDenied=" + ("true" if require_manager_guard else "")
        self.expect_log(package, result)
        self.expect_log(package, "USER_SERVICE uid=2000 file=" + PAYLOAD)
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
        self.shell("am", "start", "-W", "-f", "0x04000000", "-n", MANAGER + "/moe.shizuku.manager.MainActivity")
        self.tap("Porter is running", prefix=True)
        self.tap("Stop Porter")
        # The dialog repeats "Stop Porter" as its title, so the second match is the confirm button
        # and waiting for it also waits for the dialog to replace the single-match screen.
        self.tap("Stop Porter", occurrence=1, screenshot="running-service-dialog")
        self.until("Porter stopped", lambda: not self.pid("porter_server"))

    def case(self, name, action):
        if self.args.cases and name not in self.args.cases:
            # Left out of self.results entirely: a case the run never reached has no verdict.
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
        self.shell("am", "start", "-W", "-f", "0x04000000", "-n", MANAGER + "/moe.shizuku.manager.MainActivity")
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
                self.shell("am", "start", "-W", "-f", "0x04000000", "-n", MANAGER + "/moe.shizuku.manager.MainActivity")
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
            events = self.recording(f"cat {path}/events.txt 2>/dev/null")
            attached = re.search(r"Server stream attached pid=(\d+)", events)
            assert attached, events
            assert attached.group(1) == self.pid("porter_server"), events
            for name in ("server-start.txt", "server-stop.txt"):
                assert self.recording_size(f"{path}/{name}"), name
            (self.output / f"debug-recording-{session}.tar").write_bytes(
                self.adb("exec-out", "run-as", MANAGER, "tar", "-c", "-C", "no_backup/debug-logs", session, binary=True))
            self.shell("pm", "revoke", NATIVE, PERMISSION)
            self.shell("am", "force-stop", NATIVE)
            self.until("revocation terminates user service", lambda: not self.pid(NATIVE + ":porter-probe"))
            # Never while a recording is active: without the marker a later stop() returns early.
            self.recording("rm -rf no_backup/debug-logs")
            return {"session": session, "baseline": baseline, "followed": followed, "streamed": streamed}
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

            # No grant interaction: the server runs as uid 2000, and Service.checkSelfPermission
            # returns true for a caller whose uid is the server's, so adb shell needs no entry.
            self.shell("sh", "-c", redirected("banner", "printf hello"))
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
