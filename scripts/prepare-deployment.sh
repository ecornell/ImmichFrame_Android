#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
destination="${1:-}"
if [[ -z "$destination" ]]; then
  echo "Usage: scripts/prepare-deployment.sh <destination-apk-path>" >&2
  exit 2
fi

cd "$repo_root"
if [[ -n "$(git status --short)" ]]; then
  echo "Refusing to prepare a deployment from a dirty source tree. Commit or stash changes first." >&2
  exit 1
fi

scripts/validate.sh

sdk="${ANDROID_HOME:-$HOME/Android/Sdk}"
export JAVA_HOME="${JAVA_HOME:-$HOME/.local/share/jdks/temurin-17}"
export PATH="$JAVA_HOME/bin:$PATH"
apkanalyzer="$sdk/cmdline-tools/latest/bin/apkanalyzer"
apksigner="$sdk/build-tools/36.0.0/apksigner"
apk="app/build/outputs/apk/debug/app-debug.apk"
[[ -x "$apkanalyzer" ]] || { echo "Missing $apkanalyzer" >&2; exit 1; }
[[ -x "$apksigner" ]] || { echo "Missing $apksigner" >&2; exit 1; }

application_id="$($apkanalyzer manifest application-id "$apk")"
version_code="$($apkanalyzer manifest version-code "$apk")"
version_name="$($apkanalyzer manifest version-name "$apk")"
if [[ "$application_id" != "com.immichframe.immichframe.debug" ]]; then
  echo "Unexpected application ID: $application_id" >&2
  exit 1
fi
$apksigner verify "$apk" >/dev/null 2>&1
signer_sha256="$($apksigner verify --print-certs "$apk" 2>/dev/null | sed -n 's/^Signer #1 certificate SHA-256 digest: //p' | head -1)"
apk_sha256="$(sha256sum "$apk" | awk '{print $1}')"

mkdir -p "$(dirname "$destination")"
cp -f "$apk" "$destination"
copied_sha256="$(sha256sum "$destination" | awk '{print $1}')"
[[ "$copied_sha256" == "$apk_sha256" ]] || { echo "Copied APK hash mismatch" >&2; exit 1; }

metadata="app/build/outputs/apk/debug/deployment-metadata.json"
python - "$metadata" "$application_id" "$version_code" "$version_name" "$apk_sha256" "$signer_sha256" "$(git rev-parse HEAD)" "$destination" <<'PY'
import json, sys
path, app_id, code, name, apk_hash, signer, commit, destination = sys.argv[1:]
with open(path, "w", encoding="utf-8") as out:
    json.dump({
        "applicationId": app_id,
        "versionCode": int(code),
        "versionName": name,
        "apkSha256": apk_hash,
        "signerSha256": signer,
        "sourceCommit": commit,
        "copiedTo": destination,
    }, out, indent=2)
    out.write("\n")
PY

cat "$metadata"
