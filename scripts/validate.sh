#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

export JAVA_HOME="${JAVA_HOME:-$HOME/.local/share/jdks/temurin-17}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_HOME}"
export PATH="$JAVA_HOME/bin:$PATH"

if [[ ! -x "$JAVA_HOME/bin/java" ]]; then
  echo "Java was not found at $JAVA_HOME. Run scripts/setup-dev.sh or set JAVA_HOME." >&2
  exit 1
fi
if [[ ! -d "$ANDROID_HOME" ]]; then
  echo "Android SDK was not found at $ANDROID_HOME. Run scripts/setup-dev.sh or set ANDROID_HOME." >&2
  exit 1
fi

cd "$repo_root"
python3 -m unittest discover -s scripts/tests
if command -v pwsh >/dev/null 2>&1 && pwsh -NoProfile -Command \
    'if (Get-Module -ListAvailable Pester) { exit 0 } else { exit 1 }'; then
  pwsh -NoProfile -Command "Invoke-Pester -Path '$repo_root/scripts/tests/deploy-frames.Tests.ps1' -CI"
elif command -v powershell.exe >/dev/null 2>&1 && powershell.exe -NoProfile -Command \
    'if (Get-Module -ListAvailable Pester) { exit 0 } else { exit 1 }'; then
  pester_test="$(wslpath -w "$repo_root/scripts/tests/deploy-frames.Tests.ps1")"
  powershell.exe -NoProfile -ExecutionPolicy Bypass -Command \
    "\$result=Invoke-Pester -Script '$pester_test' -PassThru; if(\$result.FailedCount -gt 0){exit 1}"
else
  echo 'SKIP: Pester deployment tests (pwsh/Pester unavailable)' >&2
fi
if (($#)); then
  exec bash gradlew "$@"
else
  exec bash gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest assembleFrame
fi
