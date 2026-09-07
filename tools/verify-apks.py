#!/usr/bin/env python3
"""Verify the two APK identities and their shared signing certificate."""
import hashlib
import os
from pathlib import Path
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

sdk = Path(os.environ["ANDROID_HOME"])
aapt = sdk / "cmdline-tools/latest/bin/apkanalyzer"
signer = sdk / "build-tools/37.0.0/apksigner"
android = "{http://schemas.android.com/apk/res/android}"
expected = [
    ("eu.darken.porter", "eu.darken.porter.permission.API_V23"),
    ("moe.shizuku.privileged.api", "moe.shizuku.manager.permission.API_V23"),
]
if len(sys.argv) != 3:
    sys.exit("Usage: verify-apks.py MANAGER.apk COMPAT.apk")
certificates = []
for apk, (package, permission) in zip(sys.argv[1:], expected):
    manifest = ET.fromstring(subprocess.check_output([str(aapt), "manifest", "print", apk], text=True))
    assert manifest.attrib["package"] == package, apk
    declared = {p.attrib[android + "name"] for p in manifest.findall("permission")}
    assert permission in declared, (apk, declared)
    api_permission = next(p for p in manifest.findall("permission") if p.attrib[android + "name"] == permission)
    assert int(api_permission.attrib[android + "protectionLevel"], 0) & 0xf == 1, "API permission must be dangerous"
    if package == "eu.darken.porter":
        identities = declared | {p.attrib[android + "name"] for p in manifest.findall("permission-group")}
        assert not any(name.startswith("moe.shizuku.manager.") for name in identities), identities
    other_permission = expected[1 if package == expected[0][0] else 0][1]
    assert other_permission not in declared, (apk, declared)
    output = subprocess.check_output([str(signer), "verify", "--print-certs", apk], text=True)
    certificates.append(sorted(set(re.findall(r"certificate SHA-256 digest: (\w+)", output))))
    print(hashlib.sha256(Path(apk).read_bytes()).hexdigest(), Path(apk).name)
assert certificates[0] and certificates[0] == certificates[1], "Manager and companion signatures differ"
print("APK identities and matching certificates verified")
print("Signing certificate SHA-256:", ", ".join(certificates[0]))
