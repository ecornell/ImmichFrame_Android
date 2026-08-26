#!/usr/bin/env python3
"""Resolve Android SDK tools consistently on Linux, Windows, and WSL."""

import argparse
import json
import os
import pathlib
import re
import sys


def version_key(path: pathlib.Path) -> tuple[int, ...]:
    numbers = re.findall(r"\d+", path.name)
    return tuple(map(int, numbers)) if numbers else (0,)


def resolve(sdk: pathlib.Path, windows: bool = False) -> dict[str, str]:
    suffix = ".exe" if windows else ""
    batch = ".bat" if windows else ""
    build_roots = sorted((sdk / "build-tools").glob("*"), key=version_key, reverse=True)
    signer = next((p / f"apksigner{batch}" for p in build_roots
                   if (p / f"apksigner{batch}").is_file()), None)
    analyzer = sdk / "cmdline-tools/latest/bin" / f"apkanalyzer{batch}"
    adb = sdk / "platform-tools" / f"adb{suffix}"
    result = {"sdk": str(sdk), "adb": str(adb), "apkanalyzer": str(analyzer),
              "apksigner": str(signer or "")}
    missing = [name for name in ("adb", "apkanalyzer", "apksigner")
               if not result[name] or not pathlib.Path(result[name]).is_file()]
    if missing:
        raise FileNotFoundError(f"Android SDK at {sdk} is missing: {', '.join(missing)}")
    return result


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--sdk", type=pathlib.Path)
    parser.add_argument("--windows", action="store_true")
    parser.add_argument("--get", choices=("sdk", "adb", "apkanalyzer", "apksigner"))
    args = parser.parse_args()
    default = pathlib.Path.home() / "Android/Sdk"
    sdk = args.sdk or pathlib.Path(os.environ.get("ANDROID_HOME", default))
    try:
        tools = resolve(sdk, args.windows)
    except FileNotFoundError as error:
        print(error, file=sys.stderr)
        return 1
    print(tools[args.get] if args.get else json.dumps(tools, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
