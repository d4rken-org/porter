#!/usr/bin/env python3
"""Integrated compatibility setup on an explicitly supplied disposable emulator."""
import argparse
import importlib.util
import json
from pathlib import Path
import time
import re

spec = importlib.util.spec_from_file_location("porter_smoke", Path(__file__).with_name("emulator-smoke.py"))
base = importlib.util.module_from_spec(spec)
spec.loader.exec_module(base)

# A Shizuku fork under its own package, which owns the client permission in Shizuku's place.
NIGHTZUKU = "kerneldroid.nightzuku"
SHIZUKU_DATABASE = "/data/user_de/0/com.android.shell/shizuku.json"


def parse_args(argv=None):
    parser = argparse.ArgumentParser()
    for name in ("manager", "compat", "native", "legacy", "shizuku", "nightzuku", "output"):
        parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--dpad", action="store_true")
    base.add_release_argument(parser)
    return parser.parse_args(argv)


def main():
    args = parse_args()
    smoke = base.Smoke(args)

    def activate(label, description=False, scroll=False):
        if not args.dpad and not description:
            smoke.tap(label, scroll=scroll)
            return
        visited = {}
        for _ in range(50):
            root = smoke.ui()
            target = next((n for n in root.iter("node") if n.get("content-desc" if description else "text") == label and n.get("enabled") == "true"), None)
            focused = next((n for n in root.iter("node") if n.get("focused") == "true"), None)
            if target is not None:
                bounds = list(map(int, re.findall(r"\d+", target.get("bounds"))))
                if not args.dpad:
                    smoke.shell("input", "tap", (bounds[0] + bounds[2]) // 2, (bounds[1] + bounds[3]) // 2)
                    return
                if focused is not None and target in list(focused.iter()):
                    smoke.shell("input", "keyevent", "KEYCODE_DPAD_CENTER")
                    return
            directions = ["DOWN", "RIGHT", "UP", "LEFT"]
            if focused is not None and target is not None:
                f = list(map(int, re.findall(r"\d+", focused.get("bounds"))))
                dx, dy = bounds[0] + bounds[2] - f[0] - f[2], bounds[1] + bounds[3] - f[1] - f[3]
                preferred = "DOWN" if dy > 0 else "UP"
                if abs(dx) > abs(dy): preferred = "RIGHT" if dx > 0 else "LEFT"
                directions.remove(preferred)
                directions.insert(0, preferred)
            key = (focused.get("bounds"), tuple((n.get("text"), n.get("content-desc")) for n in focused.iter())) if focused is not None else "none"
            count = visited.get(key, 0)
            visited[key] = count + 1
            smoke.shell("input", "keyevent", "KEYCODE_DPAD_" + directions[count % len(directions)])
        raise AssertionError("Could not focus " + label)

    # Onboarding comes up at setup's first start of Home, so it too is driven the way --dpad asks.
    smoke.onboarding_activate = activate

    def setup_screen():
        smoke.open_home()
        if any(n.get("text") == "Shizuku compatibility" for n in smoke.ui().iter("node")):
            activate("Shizuku compatibility")
        else:
            activate("Settings", description=True)
            activate("Shizuku compatibility")

    def fresh_install():
        smoke.shell("am", "start", "-W", "-n", base.LEGACY + "/eu.darken.porter.probe.ProbeActivity")
        pid = smoke.until("legacy probe started before companion", lambda: smoke.pid(base.LEGACY))
        smoke.probe_pid = pid
        setup_screen()
        smoke.screenshot("install-ready")
        activate("Install automatically")
        smoke.until("companion installed by Porter", lambda: smoke.shell("pm", "path", base.COMPAT, check=False).startswith("package:"), timeout=90)
        smoke.expect_log(base.LEGACY, "BINDER uid=2000 version=13")
        smoke.tap("Allow all the time", base.MANAGER)
        smoke.authorized(base.LEGACY)
        assert smoke.pid(base.LEGACY) == pid, "Client was restarted instead of receiving a fresh Binder"
        setup_screen()
        smoke.until("installed compatibility details", lambda: {"Compatibility app is installed", base.COMPAT}.issubset(n.get("text") for n in smoke.ui().iter("node")))
        smoke.screenshot("installed")
        return {"legacy_pid_preserved": pid}

    def replacement_import():
        smoke.stop_porter()
        smoke.adb("uninstall", base.COMPAT)
        smoke.shell("am", "force-stop", base.LEGACY)
        # Reset only this test's Porter decisions so the next case can exercise an import.
        smoke.shell("rm", "-f", "/data/user_de/0/com.android.shell/porter.json", "/data/user_de/0/com.android.shell/porter.json.bak")
        smoke.adb("install", str(args.shizuku.resolve()))
        # Android 9 keeps the client's grant of the companion's permission once Shizuku redefines it,
        # so Shizuku would never ask.
        if f"{base.LEGACY_PERMISSION}: granted=true" in smoke.shell("dumpsys", "package", base.LEGACY):
            smoke.shell("pm", "revoke", base.LEGACY, base.LEGACY_PERMISSION)
        smoke.start_service(base.COMPAT)
        smoke.launch_probe(base.LEGACY)
        smoke.tap("Allow all the time", base.COMPAT)
        smoke.authorized(base.LEGACY, require_manager_guard=False)
        original_uid = int(smoke.shell("cmd", "package", "list", "packages", "-U", base.LEGACY).split("uid:")[-1])
        def saved_original():
            try:
                data = json.loads(smoke.shell("cat", "/data/user_de/0/com.android.shell/shizuku.json"))
                return any(e["uid"] == original_uid and e["flags"] == 2 for e in data["packages"])
            except Exception:
                return False
        smoke.until("Shizuku approval persisted", saved_original, timeout=30)
        smoke.start_service()
        setup_screen()
        activate("Replace")
        smoke.until("import preview", lambda: any(n.get("text") == "Switch to Porter?" for n in smoke.ui().iter("node")))
        smoke.screenshot("replacement-preview")
        original_pid = smoke.pid("shizuku_server")
        assert original_pid
        smoke.shell(*smoke.in_manager_data("chmod 500 cache/compat"))
        activate("Switch to Porter")
        smoke.until("original service stopped by Porter", lambda: not smoke.pid("shizuku_server"))
        smoke.until("replacement retains reviewed import on install failure", lambda: any(n.get("text") == "Setup did not finish. You can retry." for n in smoke.ui().iter("node")), timeout=90)
        assert not smoke.shell("pm", "path", base.COMPAT, check=False).startswith("package:")
        saved = smoke.shell(*smoke.in_manager_data("cat no_backup/compatibility-import.json"))
        assert json.loads(saved)["decisions"][0]["uid"] == original_uid
        smoke.screenshot("replacement-install-blocked")
        smoke.shell("am", "force-stop", base.MANAGER)
        smoke.shell(*smoke.in_manager_data("chmod 700 cache/compat"))
        setup_screen()
        activate("Install automatically", scroll=True)
        smoke.until("replacement and import complete", lambda: any(n.get("text") == "Import complete" for n in smoke.ui().iter("node")), timeout=90)
        smoke.screenshot("replacement-imported")
        smoke.shell("am", "force-stop", base.MANAGER)
        setup_screen()
        smoke.until("undismissed import result survives restart", lambda: any(n.get("text") == "Import complete" for n in smoke.ui().iter("node")))
        activate("Dismiss")
        smoke.until("dismissed result reveals compatibility details", lambda: {"Compatibility app is installed", base.COMPAT}.issubset(n.get("text") for n in smoke.ui().iter("node")))
        smoke.shell("am", "force-stop", base.MANAGER)
        setup_screen()
        smoke.until("dismissal survives restart", lambda: {"Compatibility app is installed", base.COMPAT}.issubset(n.get("text") for n in smoke.ui().iter("node")))
        assert not any(n.get("text") == "Import complete" for n in smoke.ui().iter("node"))
        result = json.loads(smoke.shell(*smoke.in_manager_data("cat no_backup/compatibility-result.json")))
        assert not result["completed"] and result["applied"] == 0 and result["skipped"] == 0
        smoke.launch_probe(base.LEGACY)
        smoke.authorized(base.LEGACY)
        assert not smoke.pid("shizuku_server")
        # The import doesn't overwrite the original database.
        assert saved_original()
        return {"imported_uid": original_uid, "original_database_preserved": True, "resumed_after_install_failure_and_manager_restart": True}

    def fork_replacement():
        smoke.stop_porter()
        assert not smoke.pid("shizuku_server")
        smoke.adb("uninstall", base.COMPAT)
        smoke.shell("am", "force-stop", base.LEGACY)
        smoke.shell("rm", "-f", base.DECISIONS, base.DECISIONS + ".bak", SHIZUKU_DATABASE, SHIZUKU_DATABASE + ".bak")
        uid = smoke.app_uid(base.LEGACY)
        def approval():
            try:
                data = json.loads(smoke.shell("sh", "-c", f"cat {SHIZUKU_DATABASE} 2>/dev/null || true"))
                return next((e["flags"] for e in data["packages"] if e["uid"] == uid), None)
            except (ValueError, KeyError, TypeError):
                return None
        assert approval() is None and not smoke.decision_flags(uid), "an earlier approval survived the reset"
        smoke.adb("install", str(args.nightzuku.resolve()))
        # Android 9 again keeps the grant across the permission's change of owner.
        if f"{base.LEGACY_PERMISSION}: granted=true" in smoke.shell("dumpsys", "package", base.LEGACY):
            smoke.shell("pm", "revoke", base.LEGACY, base.LEGACY_PERMISSION)
        supervisor = smoke.start_service(NIGHTZUKU, supervised=True)
        try:
            # Nightzuku's starter stays on as the server's parent and restarts it, which Replace
            # has to stop as well.
            server = smoke.pid("shizuku_server")
            table = smoke.process_table()
            starter = table.get(server, (None, None))[0]
            assert starter and table.get(starter, (None, ""))[1].endswith("libshizuku.so"), \
                f"shizuku_server {server} is not under Nightzuku's starter: {table.get(server)} {table.get(starter)}"
            smoke.launch_probe(base.LEGACY)
            smoke.tap("Allow all the time", NIGHTZUKU)
            smoke.authorized(base.LEGACY, require_manager_guard=False)
            smoke.until("Nightzuku approval persisted", lambda: approval() == 2, timeout=30)
            smoke.start_service()
            setup_screen()
            def conflict():
                texts = {n.get("text") for n in smoke.ui().iter("node")}
                return {"Another Shizuku app is installed", NIGHTZUKU, "Replace"}.issubset(texts) and texts
            assert "Install automatically" not in smoke.until("Nightzuku named as the conflicting app", conflict)
            smoke.screenshot("fork-conflict")
            activate("Replace")
            smoke.until("fork import preview", lambda: any(n.get("text") == "Switch to Porter?" for n in smoke.ui().iter("node")))
            smoke.screenshot("fork-replacement-preview")
            activate("Switch to Porter")
            smoke.until("fork replaced and imported", lambda: any(n.get("text") == "Import complete" for n in smoke.ui().iter("node")), timeout=90)
            smoke.screenshot("fork-replacement-imported")
            assert not smoke.shell("pm", "path", NIGHTZUKU, check=False).startswith("package:")
            assert smoke.installed(base.COMPAT)
            assert not smoke.pid("shizuku_server")
            table = smoke.process_table()
            assert starter not in table and not any(name.endswith("libshizuku.so") for _, name in table.values()), \
                "Nightzuku's starter is still running"
            smoke.launch_probe(base.LEGACY)
            smoke.authorized(base.LEGACY)
        finally:
            if supervisor.poll() is None:
                supervisor.kill()
            smoke.settle(supervisor, check=False)
        return {"imported_uid": uid, "starter_pid": starter, "fork_uninstalled": True}

    try:
        smoke.case("setup", smoke.setup)
        smoke.case("integrated-install", fresh_install)
        smoke.case("replacement-import", replacement_import)
        smoke.case("fork-replacement", fork_replacement)
    finally:
        (smoke.output / "results.json").write_text(json.dumps(smoke.results, indent=2))


if __name__ == "__main__":
    main()
