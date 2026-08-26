#!/usr/bin/env python3
"""Derive bounded release qualification evidence from monitor CSV histories."""

import argparse
import csv
import datetime as dt
import hashlib
import json
import pathlib


def file_hash(path: pathlib.Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def truth(value: str) -> bool:
    return value.strip().lower() in {"true", "1", "yes"}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--metadata", required=True, type=pathlib.Path)
    parser.add_argument("--history", action="append", required=True, type=pathlib.Path)
    parser.add_argument("--network-recovery-evidence", type=pathlib.Path)
    parser.add_argument("--network-recovery-passed", action="store_true")
    parser.add_argument("--output", required=True, type=pathlib.Path)
    args = parser.parse_args()
    metadata = json.loads(args.metadata.read_text(encoding="utf-8"))
    timestamps, cycles, devices, evidence = [], 0, set(), []
    for path in args.history:
        previous = False
        with path.open(newline="", encoding="utf-8-sig") as source:
            for row in csv.DictReader(source):
                stamp = row.get("timestamp", "")
                if stamp:
                    timestamps.append(dt.datetime.fromisoformat(stamp.replace("Z", "+00:00")))
                active = bool(row.get("appPid")) and truth(row.get("resumedApp", ""))
                if active and not previous:
                    cycles += 1
                previous = active
                identity = row.get("deviceSerial", "")
                if identity:
                    devices.add(identity)
                elif row.get("serial"):
                    devices.add("endpoint-" + hashlib.sha256(row["serial"].encode()).hexdigest()[:12])
        evidence.append({"path": path.name, "sha256": file_hash(path)})
    network_passed = False
    if args.network_recovery_evidence:
        evidence.append({"path": args.network_recovery_evidence.name,
                         "sha256": file_hash(args.network_recovery_evidence)})
        network_passed = args.network_recovery_passed
    soak = 0.0 if len(timestamps) < 2 else (max(timestamps) - min(timestamps)).total_seconds() / 3600
    result = {"schemaVersion": 2, "releaseId": metadata["releaseId"],
              "apkSha256": metadata["apkSha256"], "networkRecovery": "passed" if network_passed else "missing",
              "sleepWakeCycles": max(0, cycles - len(args.history)), "soakHours": round(soak, 3),
              "devices": sorted(devices), "evidence": evidence}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
