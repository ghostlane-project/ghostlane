#!/usr/bin/env python3
"""Verify that every assembled APK contains every shared sing-box rule set."""

from pathlib import Path
import sys
import zipfile


def main() -> int:
    if len(sys.argv) != 3:
        raise SystemExit("usage: verify-android-rule-assets.py APK_DIR RULES_DIR")
    apk_dir, rules_dir = map(Path, sys.argv[1:])
    apks = sorted(apk_dir.rglob("*.apk"))
    if not apks:
        raise SystemExit("no APK was available for routing asset verification")
    required = sorted(path.name for path in rules_dir.glob("*.srs"))
    prefix = "assets/composeResources/multiplatform_app.sharedui.generated.resources/files/rules/"
    for apk in apks:
        with zipfile.ZipFile(apk) as archive:
            names = set(archive.namelist())
        missing = [name for name in required if prefix + name not in names]
        if missing:
            raise SystemExit(f"{apk.name} is missing routing assets: {', '.join(missing)}")
        print(f"{apk.name}: verified {len(required)} routing assets")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
