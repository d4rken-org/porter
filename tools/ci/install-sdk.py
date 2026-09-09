#!/usr/bin/env python3
"""Install SDK packages with bounded retries on a sequential CI runner."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import time


def install(sdk_root, packages):
    temporary = sdk_root / ".temp"
    previous_operations = set(temporary.glob("PackageOperation*"))
    command = ["sdkmanager", f"--sdk_root={sdk_root}", "--install", *packages]
    for attempt in range(1, 4):
        print(f"SDK installation attempt {attempt}/3: {', '.join(packages)}", flush=True)
        result = subprocess.run(command)
        if result.returncode == 0 or attempt == 3:
            return result.returncode

        # Clear leftovers if an interrupted sdkmanager missed its exit cleanup.
        for operation in set(temporary.glob("PackageOperation*")) - previous_operations:
            if operation.is_symlink() or not operation.is_dir():
                continue
            for item in operation.iterdir():
                if item.is_dir() and not item.is_symlink():
                    shutil.rmtree(item)
                else:
                    item.unlink()
        delay = attempt * 10
        print(f"SDK installation failed; retrying with fresh downloads in {delay}s", flush=True)
        time.sleep(delay)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("packages", nargs="+")
    args = parser.parse_args()
    root = os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
    if not root:
        parser.error("ANDROID_SDK_ROOT or ANDROID_HOME must identify the SDK")
    raise SystemExit(install(Path(root).resolve(), args.packages))
