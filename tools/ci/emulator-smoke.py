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
import xml.etree.ElementTree as ET

MANAGER = "eu.darken.porter"
COMPAT = "moe.shizuku.privileged.api"
NATIVE = "eu.darken.porter.probe.native"
LEGACY = "eu.darken.porter.probe.legacy"
PERMISSION = "eu.darken.porter.permission.API_V23"
LEGACY_PERMISSION = "moe.shizuku.manager.permission.API_V23"
PAYLOAD = "porter-ci-shell-access"


class Smoke:
    def __init__(self, args):
        self.args = args
        self.output = args.output.resolve()
        self.output.mkdir(parents=True, exist_ok=True)
        self.results = []
        self.probe_pid = None

    def adb(self, *args, check=True, binary=False):
        command = ["adb", "-s", self.args.serial, *map(str, args)]
        result = subprocess.run(command, capture_output=True, timeout=45)
        with (self.output / "commands.log").open("a") as log:
            log.write(shlex.join(command) + "\n")
            if not binary:
                log.write(result.stdout.decode(errors="replace") + result.stderr.decode(errors="replace"))
        if check and result.returncode:
            raise RuntimeError(f"{shlex.join(command)}: {result.stderr.decode(errors='replace')}")
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

    def tap(self, text, package=MANAGER, prefix=False, screenshot=None, scroll=False, occurrence=0):
        def locate():
            root = self.ui()
            found = []
            for node in root.iter("node"):
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
        ordinal = f" #{occurrence}" if occurrence else ""
        left, top, right, bottom = self.until(f"button {text!r}{ordinal} in {package}", locate)
        if screenshot:
            self.screenshot(screenshot)
        self.shell("input", "tap", (left + right) // 2, (top + bottom) // 2)

    def screenshot(self, name):
        (self.output / f"{name}.png").write_bytes(self.adb("exec-out", "screencap", "-p", binary=True))

    def pid(self, name):
        return self.shell("pidof", name, check=False)

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

        def companion():
            self.adb("install", str(self.args.compat.resolve()))
            self.start_service()
            return self.grant_and_revoke(LEGACY, LEGACY_PERMISSION)
        self.case("compatibility", companion)

        def coexistence():
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

    def reports(self):
        (self.output / "results.json").write_text(json.dumps(self.results, indent=2))
        suite = ET.Element("testsuite", name="Porter device smoke", tests=str(len(self.results)),
                           failures=str(sum(not r["passed"] for r in self.results)))
        for result in self.results:
            case = ET.SubElement(suite, "testcase", name=result["name"], time=str(result["seconds"]))
            if not result["passed"]:
                ET.SubElement(case, "failure").text = result["failure"]
        ET.ElementTree(suite).write(self.output / "junit.xml", encoding="utf-8", xml_declaration=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    for name in ("manager", "compat", "native", "legacy", "shizuku"):
        parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    smoke = Smoke(parser.parse_args())
    try:
        smoke.run()
    finally:
        smoke.reports()
