#!/usr/bin/env python3
"""Produce a deterministic release-readiness verdict from deployment evidence."""

import argparse
import datetime as dt
import hashlib
import json
import math
import os
import pathlib
import subprocess
import sys


def sha256(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def git(*args: str) -> str:
    return subprocess.check_output(["git", *args], text=True).strip()


def android_tool_environment() -> dict[str, str]:
    env = os.environ.copy()
    if not env.get("JAVA_HOME"):
        bundled_jdk = pathlib.Path.home() / ".local/share/jdks/temurin-17"
        if (bundled_jdk / "bin/java").is_file():
            env["JAVA_HOME"] = str(bundled_jdk)
    if env.get("JAVA_HOME"):
        java_bin = str(pathlib.Path(env["JAVA_HOME"]) / "bin")
        env["PATH"] = java_bin + os.pathsep + env.get("PATH", "")
    return env


def inspect_apk(path: pathlib.Path) -> dict:
    sdk = pathlib.Path(os.environ.get("ANDROID_HOME", pathlib.Path.home() / "Android/Sdk"))
    resolver_path = pathlib.Path(__file__).with_name("resolve-android-tools.py")
    spec = __import__("importlib.util").util.spec_from_file_location("android_tools_resolver", resolver_path)
    resolver = __import__("importlib.util").util.module_from_spec(spec)
    spec.loader.exec_module(resolver)
    tools = resolver.resolve(sdk)
    analyzer, signer = pathlib.Path(tools["apkanalyzer"]), pathlib.Path(tools["apksigner"])

    def run(*command: str) -> str:
        return subprocess.check_output(
            command,
            text=True,
            stderr=subprocess.STDOUT,
            env=android_tool_environment(),
        ).strip()

    run(str(signer), "verify", str(path))
    certs = run(str(signer), "verify", "--print-certs", str(path))
    prefix = "Signer #1 certificate SHA-256 digest: "
    digest = next((line[len(prefix):] for line in certs.splitlines() if line.startswith(prefix)), "")
    if not digest:
        raise RuntimeError(f"signer digest unavailable for {path}")
    return {
        "applicationId": run(str(analyzer), "manifest", "application-id", str(path)),
        "versionCode": int(run(str(analyzer), "manifest", "version-code", str(path))),
        "versionName": run(str(analyzer), "manifest", "version-name", str(path)),
        "signerSha256": digest,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--metadata", required=True, type=pathlib.Path)
    parser.add_argument("--qualification", type=pathlib.Path)
    parser.add_argument("--output", type=pathlib.Path)
    args = parser.parse_args()

    failures: list[str] = []
    outstanding: list[str] = []
    metadata = json.loads(args.metadata.read_text(encoding="utf-8"))
    target = pathlib.Path(metadata.get("copiedTo", ""))
    rollback_data = metadata.get("rollback") or {}
    rollback = pathlib.Path(rollback_data.get("path", ""))

    if metadata.get("schemaVersion") != 1:
        failures.append("unsupported or missing metadata schemaVersion")
    if git("status", "--porcelain"):
        failures.append("source tree is dirty")
    if metadata.get("sourceCommit") != git("rev-parse", "HEAD"):
        failures.append("metadata source commit is not HEAD")
    if metadata.get("validation", {}).get("status") != "passed":
        failures.append("tests, lint, and assembly are not recorded as passed")
    if not target.is_file():
        failures.append("target APK is missing")
    else:
        if sha256(target) != metadata.get("apkSha256"):
            failures.append("target APK hash mismatch")
        try:
            target_actual = inspect_apk(target)
            for field in ("applicationId", "versionCode", "versionName", "signerSha256"):
                if target_actual[field] != metadata.get(field):
                    failures.append(f"target APK {field} does not match metadata")
        except (OSError, RuntimeError, subprocess.SubprocessError, ValueError) as error:
            failures.append(f"target APK inspection failed: {error}")

    if not rollback_data:
        failures.append("rollback artifact metadata is missing")
    elif not rollback.is_file():
        failures.append("rollback APK is missing")
    else:
        if sha256(rollback) != rollback_data.get("apkSha256"):
            failures.append("rollback APK hash mismatch")
        if rollback_data.get("signerSha256") != metadata.get("signerSha256"):
            failures.append("rollback and target signers differ")
        try:
            rollback_actual = inspect_apk(rollback)
            if rollback_actual["applicationId"] != metadata.get("applicationId"):
                failures.append("rollback APK applicationId differs from target")
            for field in ("versionCode", "versionName", "signerSha256"):
                if rollback_actual[field] != rollback_data.get(field):
                    failures.append(f"rollback APK {field} does not match metadata")
        except (OSError, RuntimeError, subprocess.SubprocessError, ValueError) as error:
            failures.append(f"rollback APK inspection failed: {error}")
        if int(rollback_data.get("versionCode", -1)) >= int(metadata.get("versionCode", -1)):
            failures.append("rollback version is not older than target")

    qualification = None
    if args.qualification:
        if not args.qualification.is_file():
            failures.append("qualification evidence file is missing")
        else:
            qualification = json.loads(args.qualification.read_text(encoding="utf-8"))
            if qualification.get("schemaVersion", 1) >= 2:
                if qualification.get("releaseId") != metadata.get("releaseId"):
                    failures.append("qualification releaseId does not match target")
                if qualification.get("apkSha256") != metadata.get("apkSha256"):
                    failures.append("qualification APK hash does not match target")
            if qualification.get("networkRecovery") != "passed":
                outstanding.append("network recovery is not recorded as passed")
            sleep_wake = qualification.get("sleepWakeCycles", 0)
            if (isinstance(sleep_wake, bool) or not isinstance(sleep_wake, (int, float)) or
                    not math.isfinite(sleep_wake) or sleep_wake < 10):
                outstanding.append("fewer than 10 sleep/wake cycles are recorded")
            soak_hours = qualification.get("soakHours", 0)
            if (isinstance(soak_hours, bool) or not isinstance(soak_hours, (int, float)) or
                    not math.isfinite(soak_hours) or soak_hours < 24):
                outstanding.append("fewer than 24 soak hours are recorded")
            if not qualification.get("evidence"):
                outstanding.append("qualification evidence references are missing")
    else:
        outstanding.append("qualification evidence was not supplied")

    status = "not-ready" if failures else ("conditionally-ready" if outstanding else "ready")
    report = {
        "schemaVersion": 1,
        "generatedAt": dt.datetime.now(dt.timezone.utc).isoformat(),
        "status": status,
        "releaseId": metadata.get("releaseId"),
        "sourceCommit": metadata.get("sourceCommit"),
        "versionCode": metadata.get("versionCode"),
        "apkSha256": metadata.get("apkSha256"),
        "signerSha256": metadata.get("signerSha256"),
        "failures": failures,
        "outstanding": outstanding,
        "qualification": qualification,
    }
    rendered = json.dumps(report, indent=2) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(rendered, encoding="utf-8")
    sys.stdout.write(rendered)
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
