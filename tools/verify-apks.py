#!/usr/bin/env python3
"""Verify the two APK identities and their shared signing certificate."""
import argparse
import zipfile
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
parser = argparse.ArgumentParser()
parser.add_argument("manager")
parser.add_argument("companion")
parser.add_argument("--embedded", choices=["required", "absent"], required=True)
args = parser.parse_args()
with zipfile.ZipFile(args.manager) as archive:
    name = "assets/compat/porter-compat.apk"
    if args.embedded == "required":
        assert archive.read(name) == Path(args.companion).read_bytes(), "Embedded companion differs from release APK"
    else:
        assert name not in archive.namelist(), "Google Play build must not embed companion APK"
versions = []
certificates = []
for apk, (package, permission) in zip([args.manager, args.companion], expected):
    manifest = ET.fromstring(subprocess.check_output([str(aapt), "manifest", "print", apk], text=True))
    assert manifest.attrib["package"] == package, apk
    versions.append(manifest.attrib[android + "versionCode"])
    declared = {p.attrib[android + "name"] for p in manifest.findall("permission")}
    assert permission in declared, (apk, declared)
    api_permission = next(p for p in manifest.findall("permission") if p.attrib[android + "name"] == permission)
    assert int(api_permission.attrib[android + "protectionLevel"], 0) & 0xf == 1, "API permission must be dangerous"
    if package == "eu.darken.porter":
        identities = declared | {p.attrib[android + "name"] for p in manifest.findall("permission-group")}
        assert not any(name.startswith("moe.shizuku.manager.") for name in identities), identities
        requested = {p.attrib[android + "name"] for p in manifest.findall("uses-permission")}
        assert ("android.permission.REQUEST_INSTALL_PACKAGES" in requested) == (args.embedded == "required"), "Installer permission must match the embedded build"
    other_permission = expected[1 if package == expected[0][0] else 0][1]
    assert other_permission not in declared, (apk, declared)
    output = subprocess.check_output([str(signer), "verify", "--print-certs", apk], text=True)
    certificates.append(sorted(set(re.findall(r"certificate SHA-256 digest: (\w+)", output))))
    print(hashlib.sha256(Path(apk).read_bytes()).hexdigest(), Path(apk).name)
assert versions[0] == versions[1], "Manager and companion versions differ"
assert certificates[0] and certificates[0] == certificates[1], "Manager and companion signatures differ"
print("APK identities and matching certificates verified")
print("Signing certificate SHA-256:", ", ".join(certificates[0]))
