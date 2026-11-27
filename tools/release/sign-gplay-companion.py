#!/usr/bin/env python3
"""Re-sign the compatibility companion with the Google Play app signing key and check the result."""
import argparse
import os
from pathlib import Path
import re
import subprocess
import zipfile

PIN = Path(__file__).resolve().parent / "gplay-signing-certificate.txt"
SIGNATURE_FILE = re.compile(r"META-INF/([^/]+\.(SF|RSA|DSA|EC)|MANIFEST\.MF)$")


def certificates(apksigner, apk):
    output = subprocess.check_output([apksigner, "verify", "--print-certs", apk], text=True)
    return sorted(set(re.findall(r"certificate SHA-256 digest: (\w+)", output)))


def contents(apk):
    with zipfile.ZipFile(apk) as archive:
        return {i.filename: i.CRC for i in archive.infolist() if not SIGNATURE_FILE.match(i.filename)}


parser = argparse.ArgumentParser()
parser.add_argument("companion")
parser.add_argument("output")
parser.add_argument("--pin", default=str(PIN))
args = parser.parse_args()

pin_file = Path(args.pin)
if not pin_file.is_file():
    raise SystemExit(f"No pinned Play signing certificate at {pin_file}")
# Play Console shows the fingerprint as colon-separated upper-case hex; apksigner prints it bare.
pinned = pin_file.read_text().strip().replace(":", "").lower()
if not re.fullmatch(r"[0-9a-f]{64}", pinned):
    raise SystemExit(f"{pin_file} does not hold a SHA-256 fingerprint")

env = {name: os.environ.get(name, "") for name in ["GPLAY_STORE_PATH", "GPLAY_STORE_PASSWORD", "GPLAY_KEY_ALIAS", "GPLAY_KEY_PASSWORD"]}
missing = [name for name, value in env.items() if not value]
if missing:
    raise SystemExit(f"Play app signing requires {', '.join(missing)}")

apksigner = str(Path(os.environ["ANDROID_HOME"]) / "build-tools/37.0.0/apksigner")
subprocess.run([
    apksigner, "sign",
    "--ks", env["GPLAY_STORE_PATH"],
    "--ks-key-alias", env["GPLAY_KEY_ALIAS"],
    "--ks-pass", "env:GPLAY_STORE_PASSWORD",
    "--key-pass", "env:GPLAY_KEY_PASSWORD",
    "--v4-signing-enabled", "false",
    "--out", args.output,
    args.companion,
], check=True)

assert contents(args.output) == contents(args.companion), "Re-signing changed more than the signature"
signed = certificates(apksigner, args.output)
assert signed == [pinned], f"Signed with {signed}, not the pinned Play certificate {pinned}"
assert certificates(apksigner, args.companion) != signed, "The input companion already carries the Play certificate"
print("Play companion verified:", Path(args.output).name)
print("Signing certificate SHA-256:", pinned)
