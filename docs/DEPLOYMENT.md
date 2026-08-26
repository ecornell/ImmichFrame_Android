# Frame deployment runbook

This runbook covers repeatable in-place deployment of the debug package to authorized dedicated
frames. It preserves application data and restores ImmichFrame as Android HOME after package
replacement.

## 1. Preflight

1. Start from a clean, committed source tree: `git status --short` must be empty.
2. Prepare a verified APK from the clean tree. This runs validation, verifies the debug application
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

3. Optionally create qualification evidence from `scripts/qualification.example.json`, then audit
   readiness. Missing qualification evidence produces `conditionally-ready`; artifact, signer,
   provenance, or rollback failures produce `not-ready`:

   ```bash
   scripts/audit-release.py \
     --metadata app/build/outputs/apk/debug/deployment-metadata.json \
     --qualification path/to/qualification.json \
     --output app/build/outputs/release-readiness.json
   ```

4. Copy `scripts/frames.example.json` to the untracked `scripts/frames.local.json`. Treat the
   WF1561 `serial` as a last-known endpoint only: configure `scanSubnet` and
   `expectedDeviceSerial` so the script can rediscover its random post-reboot IP and verify the
   physical frame before installation. Never deploy to a device whose model or hardware serial
   does not match.

## 2. Deploy

From PowerShell or WSL via `powershell.exe`:

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
checks, matching target/rollback/installed signers, and an upgrade-or-same version code. The script then installs with
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
- Confirm settings survived, including server URL, native/WebView mode, active schedule, RPC, and
  MQTT configuration.
- For remote-control changes, verify authenticated REST state and one idempotent command. When MQTT
  is enabled, verify one Home Assistant device, availability, state updates, and that a retained
  action command is ignored.

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
