#!/usr/bin/env python3
"""Verify that every file-based Compose resource is present in an APK."""

from __future__ import annotations

import argparse
from pathlib import Path
from zipfile import ZipFile


ASSET_PREFIX = (
    "assets/composeResources/"
    "multiplatform_app.sharedui.generated.resources/files"
)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("apk", type=Path)
    parser.add_argument(
        "--resources",
        type=Path,
        default=Path("sharedUI/src/commonMain/composeResources/files"),
    )
    args = parser.parse_args()

    resources = sorted(path for path in args.resources.rglob("*") if path.is_file())
    if not resources:
        parser.error(f"no Compose file resources found under {args.resources}")

    missing: list[str] = []
    changed: list[str] = []
    with ZipFile(args.apk) as apk:
        names = set(apk.namelist())
        for source in resources:
            relative = source.relative_to(args.resources).as_posix()
            archived = f"{ASSET_PREFIX}/{relative}"
            if archived not in names:
                missing.append(archived)
            elif apk.read(archived) != source.read_bytes():
                changed.append(archived)

    if missing or changed:
        if missing:
            print("Missing Compose assets:")
            print("\n".join(f"  {path}" for path in missing))
        if changed:
            print("Compose assets whose packaged bytes differ from the source:")
            print("\n".join(f"  {path}" for path in changed))
        return 1

    print(f"Verified {len(resources)} Compose file resources in {args.apk}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
