# Frame deployment runbook

This runbook covers repeatable in-place deployment of the debug package to authorized dedicated
frames. It preserves application data and restores ImmichFrame as Android HOME after package
replacement.

## Build types

`prepare-deployment.sh` takes `BUILD_TYPE=debug` (default) or `BUILD_TYPE=frame`:

- `debug`: the unoptimized, debuggable build. `run-as`, `am profile`, and app-data inspection
  work, so use it for on-device diagnosis.
- `frame`: inherits the release configuration (R8 minify, not debuggable) but keeps the `.debug`
  application ID and is signed with the build machine's debug key, the same identity as the
  installed builds. It upgrades frames in place, keeping settings and the HOME role, and any
  older debug build remains a valid rollback. Because it is not debuggable, `adb run-as` can no
  longer read the stored server secret, RPC token, or MQTT password. The APK is about a quarter of
  the debug size.

The build machine's `~/.android/debug.keystore` is effectively the frames' signing key. Back it up;
a build signed with a different keystore cannot upgrade the frames, and the signer checks in these
scripts reject it.

```bash
BUILD_TYPE=frame scripts/prepare-deployment.sh \
  /mnt/c/Users/USER/AppData/Local/Temp/ImmichFrame-debug.apk \
  /path/to/previous-signed-ImmichFrame.apk
cat app/build/outputs/apk/frame/deployment-metadata.json
```

Switching between `debug` and `frame` builds still needs a new `versionCode`, as below.

## 1. Preflight

1. Start from a clean, committed source tree: `git status --short` must be empty.
2. Give every distinct APK a new `versionCode`, even for a small hotfix. Reusing a version code
   makes package evidence ambiguous and prevents the immediately previous APK from being a clean
   rollback target. Re-running the exact same release is idempotent; a different APK with the
   installed version code is rejected.
3. Prepare a verified APK from the clean tree. This runs validation, verifies the debug application
   ID and APK signature, copies the artifact, verifies the copied hash, and emits deployment
   metadata containing source commit, version, signer, APK SHA-256, validation results, and a
   verified rollback artifact. The rollback APK must use the same application ID and signer and
   must have an older version code. Preparation copies it beside the target with a
   `-rollback.apk` suffix and copies metadata with a `-deployment-metadata.json` suffix so all
   deployment inputs have Windows-accessible paths:

   ```bash
   scripts/prepare-deployment.sh \
     /mnt/c/Users/USER/AppData/Local/Temp/ImmichFrame-debug.apk \
     /path/to/previous-signed-ImmichFrame.apk
   cat app/build/outputs/apk/debug/deployment-metadata.json
   ```

4. Optionally create qualification evidence from `scripts/qualification.example.json`, then audit
   readiness. Missing qualification evidence produces `conditionally-ready`; artifact, signer,
   provenance, or rollback failures produce `not-ready`:

   ```bash
   scripts/audit-release.py \
     --metadata app/build/outputs/apk/debug/deployment-metadata.json \
     --qualification path/to/qualification.json \
     --output app/build/outputs/release-readiness.json
   ```

   Prefer deriving the file from monitor history instead of entering counters manually:

   ```bash
   scripts/collect-qualification.py \
     --metadata app/build/outputs/apk/debug/deployment-metadata.json \
     --history path/to/history-YYYY-MM-DD.csv \
     --output app/build/outputs/qualification.json
   ```

5. Copy `scripts/frames.example.json` to the untracked `scripts/frames.local.json`. Treat the
   WF1561 `serial` as a last-known endpoint only: configure `scanSubnet` and
   `expectedDeviceSerial` so the script can rediscover its random post-reboot IP and verify the
   physical frame before installation. Never deploy to a device whose model or hardware serial
   does not match.

## 2. Deploy

From PowerShell or WSL via `powershell.exe`. Before approval, verify that the Windows paths supplied
for `apkanalyzer.bat`, `apksigner.bat`, and `adb.exe` all exist. A WSL-only Android SDK is not a
substitute for those Windows tools; install the Windows command-line/build tools or use a reviewed
repository-provided bridge rather than creating ad hoc deployment commands.

```powershell
.\scripts\deploy-frames.ps1 `
  -ManifestPath .\scripts\frames.local.json `
  -ApkPath "$env:LOCALAPPDATA\Temp\ImmichFrame-debug.apk" `
  -RollbackApkPath "$env:LOCALAPPDATA\Temp\ImmichFrame-debug-rollback.apk" `
  -DeploymentMetadataPath "$env:LOCALAPPDATA\Temp\ImmichFrame-debug-deployment-metadata.json" `
  -ApkAnalyzerPath "$env:ANDROID_HOME\cmdline-tools\latest\bin\apkanalyzer.bat" `
  -ApkSignerPath "$env:ANDROID_HOME\build-tools\36.0.0\apksigner.bat" `
  -ApprovalReleaseId RELEASE_ID_FROM_METADATA `
  -OutputJsonPath PATH_TO_DEPLOYMENT_RESULT_JSON
```

The script tries the manifest hint and already-known ADB devices first. When those fail, a frame
with `scanSubnet` configured is discovered by a bounded TCP 5555 scan of that `/24`. The expected
model and hardware serial are checked before any mutation. Mutable deployment also requires an
exact release-ID approval, fresh APK manifest/signature inspection, target and rollback hash
checks, and matching target/rollback/installed signers. A normal deployment must increase the
installed version code unless the exact approved APK hash is already installed. The script then installs with
`adb install -r`, restores HOME, starts the single-task Activity once, and verifies version, PID,
HOME, and resumed Activity for every frame. It stops on the first failed safety check.
Use `-VerifyOnly` without an APK or metadata to run the connection, model, installed-version, HOME,
PID, and resumed-Activity checks without modifying either frame.

The former WF1561-specific deployment script is disabled because it cannot enforce these gates.

## 3. Post-deployment checks

- Capture and inspect one screenshot per active frame. A scheduled-sleep WF1561 may correctly be
  black; do not wake it merely for screenshot verification.
- Inspect recent `AndroidRuntime`/ImmichFrame errors with `adb logcat`.
- Run the WF1561 monitor immediately and confirm `healthy`.
- For a versioned read-only health bundle including non-sensitive classifications from a bounded
  logcat window, run
`scripts/collect-frame-health.ps1` and retain its JSON output with the deployment result.
- For every configured model, run `collect-frames-health.ps1`; model capabilities in the local
  manifest determine whether an OEM panel process is required. Health bundles include the observed
  installed APK hash.
- Capture rendering evidence with `capture-render-regression.ps1`, then run
  `analyze-render-seam.py SCREENSHOT.png`. The analyzer detects the historical consistent
  one-pixel vertical seam; it supplements rather than replaces physical-panel inspection.
- Confirm settings survived, including server URL, native/WebView mode, active schedule, RPC, and
  MQTT configuration.
- For remote-control changes, verify authenticated REST state and one idempotent command. When MQTT
  is enabled, verify one Home Assistant device, availability, state updates, and that a retained
  action command is ignored. With photo reporting on, verify that the current photo sensor changes
  on the next slide and that `currentPhoto` in the REST state carries metadata (it has only the
  display strings against a server without `includeMetadata`). With the photo image on, check the
  image entity, the payload size on the broker, and crossfade smoothness and memory against *Off*. Send
  one notification and confirm the banner is readable; send another with the display off and
  confirm it is dropped. Send one with `"wake": true` while the display is off and confirm the
  banner shows on a black screen, the slideshow does not start, and the screen turns off again.
  With a Home Assistant URL set, send a camera snapshot with `refresh` and confirm it updates,
  holds the slideshow, ends on time and on a tap, and that a URL on another host is ignored.

## 4. Qualification and rollback

For changes to rendering, networking, scheduling, caching, REST, or MQTT, perform network-loss and
recovery tests and an appropriate soak on the constrained frame. Preserve the previous signed APK
as a recovery artifact. Android requires explicit downgrade authorization to install an older
version. After separately reviewing and confirming the device set, current/rollback versions,
identities, signer, and hashes, run the guarded script with `-Rollback` and the distinct approval
value `rollback-RELEASE_ID`. This is the only path that adds Android's data-preserving `-d` flag;
it still verifies identity and signer, restores HOME, and verifies the installed version. Never
uninstall or clear package data; those operations destroy configuration. Without `-Rollback`, the
script refuses downgrades.

```powershell
.\scripts\deploy-frames.ps1 `
  -ManifestPath .\scripts\frames.local.json `
  -ApkPath "$env:LOCALAPPDATA\Temp\ImmichFrame-debug.apk" `
  -RollbackApkPath "$env:LOCALAPPDATA\Temp\ImmichFrame-debug-rollback.apk" `
  -DeploymentMetadataPath "$env:LOCALAPPDATA\Temp\ImmichFrame-debug-deployment-metadata.json" `
  -ApkAnalyzerPath "$env:ANDROID_HOME\cmdline-tools\latest\bin\apkanalyzer.bat" `
  -ApkSignerPath "$env:ANDROID_HOME\build-tools\36.0.0\apksigner.bat" `
  -Rollback `
  -ApprovalReleaseId rollback-RELEASE_ID
```

Project-local agent skills under `.pi/skills/` codify release auditing, guarded deployment, and
read-only health triage. Their safety rules supplement rather than replace the script-enforced
identity, hash, signer, rollback, version, and approval gates.

Record the deployed commit, APK SHA-256, version, frame serial/model, and verification outcome in
the device-specific operational repository. A source tag should describe what was actually
deployed, not merely what was built.

At minimum, retain a sanitized record containing the readiness status and gaps, deployment and
post-deployment verification results, health classification, screenshot SHA-256, and manual visual
result. Temporary files and screenshots may be deleted, so a successful chat transcript is not an
operational record. Never upgrade the readiness status merely because a visual smoke test passed;
network recovery, sleep/wake cycling, and soak requirements remain separate qualification evidence.

Create a content-addressed sanitized evidence bundle with `archive-release.py` after all checks.
The bundle retains the exact target and rollback APKs as well as sanitized evidence. The project
`deployment-closeout` skill defines the required inputs and stop conditions.
