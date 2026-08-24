#!/usr/bin/env bash
set -euo pipefail

JDK_DIR="${JDK_DIR:-$HOME/.local/share/jdks/temurin-17}"
SDK_DIR="${ANDROID_HOME:-$HOME/Android/Sdk}"
mkdir -p "$(dirname "$JDK_DIR")" "$SDK_DIR/cmdline-tools"

if [[ ! -x "$JDK_DIR/bin/java" ]]; then
  curl -fL --retry 3 \
    'https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse' \
    -o /tmp/immichframe-jdk17.tar.gz
  mkdir -p "$JDK_DIR"
  tar -xzf /tmp/immichframe-jdk17.tar.gz -C "$JDK_DIR" --strip-components=1
fi

if [[ ! -x "$SDK_DIR/cmdline-tools/latest/bin/sdkmanager" ]]; then
  curl -fL --retry 3 \
    'https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip' \
    -o /tmp/immichframe-android-tools.zip
  rm -rf /tmp/immichframe-android-tools "$SDK_DIR/cmdline-tools/latest"
  python3 - <<'PY'
import zipfile
with zipfile.ZipFile('/tmp/immichframe-android-tools.zip') as archive:
    archive.extractall('/tmp/immichframe-android-tools')
PY
  mv /tmp/immichframe-android-tools/cmdline-tools "$SDK_DIR/cmdline-tools/latest"
  chmod -R u+rx "$SDK_DIR/cmdline-tools/latest/bin"
fi

export JAVA_HOME="$JDK_DIR" ANDROID_HOME="$SDK_DIR" ANDROID_SDK_ROOT="$SDK_DIR"
yes | "$SDK_DIR/cmdline-tools/latest/bin/sdkmanager" --licenses >/dev/null || true
"$SDK_DIR/cmdline-tools/latest/bin/sdkmanager" \
  platform-tools 'platforms;android-36' 'build-tools;36.0.0'
printf 'sdk.dir=%s\n' "$SDK_DIR" > local.properties
printf 'Ready. Run:\n  export JAVA_HOME=%q ANDROID_HOME=%q ANDROID_SDK_ROOT=%q\n' \
  "$JDK_DIR" "$SDK_DIR" "$SDK_DIR"
