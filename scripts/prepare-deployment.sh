#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
destination="${1:-}"
rollback_apk="${2:-}"
if [[ -z "$destination" ]]; then
  echo "Usage: scripts/prepare-deployment.sh <destination-apk-path> [rollback-apk-path]" >&2
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
apkanalyzer="$(python3 scripts/resolve-android-tools.py --sdk "$sdk" --get apkanalyzer)"
apksigner="$(python3 scripts/resolve-android-tools.py --sdk "$sdk" --get apksigner)"
apk="app/build/outputs/apk/debug/app-debug.apk"
[[ -f "$apkanalyzer" ]] || { echo "Missing $apkanalyzer" >&2; exit 1; }
[[ -f "$apksigner" ]] || { echo "Missing $apksigner" >&2; exit 1; }

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
release_id="v${version_code}-${apk_sha256:0:12}"

mkdir -p "$(dirname "$destination")"
cp -f "$apk" "$destination"
copied_sha256="$(sha256sum "$destination" | awk '{print $1}')"
[[ "$copied_sha256" == "$apk_sha256" ]] || { echo "Copied APK hash mismatch" >&2; exit 1; }

metadata="app/build/outputs/apk/debug/deployment-metadata.json"
rollback_json='null'
if [[ -n "$rollback_apk" ]]; then
  [[ -f "$rollback_apk" ]] || { echo "Rollback APK was not found: $rollback_apk" >&2; exit 1; }
  rollback_application_id="$($apkanalyzer manifest application-id "$rollback_apk")"
  rollback_version_code="$($apkanalyzer manifest version-code "$rollback_apk")"
  rollback_version_name="$($apkanalyzer manifest version-name "$rollback_apk")"
  $apksigner verify "$rollback_apk" >/dev/null 2>&1
  rollback_signer="$($apksigner verify --print-certs "$rollback_apk" 2>/dev/null | sed -n 's/^Signer #1 certificate SHA-256 digest: //p' | head -1)"
  rollback_hash="$(sha256sum "$rollback_apk" | awk '{print $1}')"
  [[ "$rollback_application_id" == "$application_id" ]] || { echo "Rollback application ID mismatch" >&2; exit 1; }
  [[ "$rollback_signer" == "$signer_sha256" ]] || { echo "Rollback signer mismatch" >&2; exit 1; }
  (( rollback_version_code < version_code )) || { echo "Rollback version must be older than target" >&2; exit 1; }
  rollback_destination="${destination%.apk}-rollback.apk"
  cp -f "$rollback_apk" "$rollback_destination"
  [[ "$(sha256sum "$rollback_destination" | awk '{print $1}')" == "$rollback_hash" ]] || {
    echo "Copied rollback APK hash mismatch" >&2; exit 1;
  }
  rollback_json="$(python - "$rollback_destination" "$rollback_version_code" "$rollback_version_name" "$rollback_hash" "$rollback_signer" <<'PY'
import json, sys
path, code, name, apk_hash, signer = sys.argv[1:]
print(json.dumps({"path": path, "versionCode": int(code), "versionName": name,
                  "apkSha256": apk_hash, "signerSha256": signer}))
PY
)"
fi

python - "$metadata" "$application_id" "$version_code" "$version_name" "$apk_sha256" "$signer_sha256" "$(git rev-parse HEAD)" "$destination" "$release_id" "$rollback_json" <<'PY'
import json, sys
from datetime import datetime, timezone
path, app_id, code, name, apk_hash, signer, commit, destination, release_id, rollback = sys.argv[1:]
with open(path, "w", encoding="utf-8") as out:
    json.dump({
        "schemaVersion": 1,
        "releaseId": release_id,
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "applicationId": app_id,
        "versionCode": int(code),
        "versionName": name,
        "apkSha256": apk_hash,
        "signerSha256": signer,
        "sourceCommit": commit,
        "copiedTo": destination,
        "validation": {"status": "passed", "commands": ["testDebugUnitTest", "lintDebug", "assembleDebug"]},
        "rollback": json.loads(rollback),
    }, out, indent=2)
    out.write("\n")
PY

metadata_destination="${destination%.apk}-deployment-metadata.json"
cp -f "$metadata" "$metadata_destination"
cat "$metadata"
printf 'Deployment metadata copied to %s\n' "$metadata_destination"
