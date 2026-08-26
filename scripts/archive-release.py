#!/usr/bin/env python3
"""Create a sanitized, content-addressed release evidence bundle."""

import argparse
import hashlib
import json
import pathlib
import shutil
import tempfile


SENSITIVE_KEYS = ("token", "password", "secret", "authorization", "url", "mqtt")


def sha256(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def sanitized(value, key: str = ""):
    if any(part in key.lower() for part in SENSITIVE_KEYS):
        return "<redacted>"
    if key.lower() in {"path", "copiedto"} and isinstance(value, str):
        return pathlib.PurePath(value.replace("\\", "/")).name
    if isinstance(value, dict):
        return {k: sanitized(v, k) for k, v in value.items()}
    if isinstance(value, list):
        return [sanitized(v, key) for v in value]
    if key.lower() == "serial" and isinstance(value, str) and ":" in value:
        return "<redacted-endpoint>"
    return value


def write_json(source: pathlib.Path, destination: pathlib.Path) -> None:
    data = json.loads(source.read_text(encoding="utf-8-sig"))
    destination.write_text(json.dumps(sanitized(data), indent=2) + "\n", encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--metadata", required=True, type=pathlib.Path)
    parser.add_argument("--readiness", required=True, type=pathlib.Path)
    parser.add_argument("--deployment", required=True, type=pathlib.Path)
    parser.add_argument("--target-apk", required=True, type=pathlib.Path)
    parser.add_argument("--rollback-apk", required=True, type=pathlib.Path)
    parser.add_argument("--verify", type=pathlib.Path)
    parser.add_argument("--health", action="append", default=[], type=pathlib.Path)
    parser.add_argument("--screenshot", action="append", default=[], type=pathlib.Path)
    parser.add_argument("--manual-notes", type=pathlib.Path)
    parser.add_argument("--output-dir", required=True, type=pathlib.Path)
    args = parser.parse_args()
    metadata = json.loads(args.metadata.read_text(encoding="utf-8"))
    readiness = json.loads(args.readiness.read_text(encoding="utf-8"))
    release_id = metadata.get("releaseId", "")
    if not release_id or readiness.get("releaseId") != release_id:
        raise SystemExit("metadata/readiness releaseId mismatch")
    if sha256(args.target_apk) != metadata.get("apkSha256"):
        raise SystemExit("target APK hash does not match metadata")
    rollback = metadata.get("rollback") or {}
    if sha256(args.rollback_apk) != rollback.get("apkSha256"):
        raise SystemExit("rollback APK hash does not match metadata")
    sources = [("metadata.json", args.metadata, True), ("readiness.json", args.readiness, True),
               ("deployment.json", args.deployment, True), ("target.apk", args.target_apk, False),
               ("rollback.apk", args.rollback_apk, False)]
    if args.verify:
        sources.append(("verify.json", args.verify, True))
    sources += [(f"health-{i + 1}.json", p, True) for i, p in enumerate(args.health)]
    sources += [(f"screenshot-{i + 1}.png", p, False) for i, p in enumerate(args.screenshot)]
    if args.manual_notes:
        sources.append(("manual-notes.txt", args.manual_notes, False))
    for _, path, _ in sources:
        if not path.is_file():
            raise SystemExit(f"evidence file missing: {path}")
    args.output_dir.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=args.output_dir) as temporary:
        stage = pathlib.Path(temporary)
        for name, source, is_json in sources:
            write_json(source, stage / name) if is_json else shutil.copyfile(source, stage / name)
        files = [{"name": p.name, "sha256": sha256(p), "bytes": p.stat().st_size}
                 for p in sorted(stage.iterdir())]
        manifest = {"schemaVersion": 1, "releaseId": release_id,
                    "apkSha256": metadata.get("apkSha256"), "files": files}
        canonical = json.dumps(manifest, sort_keys=True, separators=(",", ":")).encode()
        bundle_hash = hashlib.sha256(canonical).hexdigest()
        manifest["bundleSha256"] = bundle_hash
        (stage / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
        destination = args.output_dir / f"{release_id}-{bundle_hash[:12]}"
        if destination.exists():
            shutil.rmtree(destination)
        shutil.copytree(stage, destination)
    print(destination)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
