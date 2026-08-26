# Frame deployment runbook

This runbook covers repeatable in-place deployment of the debug package to authorized dedicated
frames. It preserves application data and restores ImmichFrame as Android HOME after package
replacement.

## 1. Preflight

1. Start from a clean, committed source tree: `git status --short` must be empty.
2. Prepare a verified APK from the clean tree. This runs validation, verifies the debug application
   ID and APK signature, copies the artifact, verifies the copied hash, and emits deployment
   metadata containing source commit, version, signer, and APK SHA-256:

   ```bash
   scripts/prepare-deployment.sh \
     /mnt/c/Users/USER/AppData/Local/Temp/ImmichFrame-debug.apk
   cat app/build/outputs/apk/debug/deployment-metadata.json
   ```

3. Copy `scripts/frames.example.json` to the untracked `scripts/frames.local.json`. Treat the
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
  -ExpectedApkSha256 HASH_FROM_PREFLIGHT
```

The script tries the manifest hint and already-known ADB devices first. When those fail, a frame
with `scanSubnet` configured is discovered by a bounded TCP 5555 scan of that `/24`. The expected
model and optional hardware serial are checked before any mutation. The script then installs with
`adb install -r`, restores HOME, starts the single-task Activity once, and verifies version, PID,
HOME, and resumed Activity for every frame. It stops on the first failed safety check.
Use `-VerifyOnly` to run the connection, model, installed-version, HOME, PID, and resumed-Activity
checks without modifying either frame.

The WF1561-specific `deploy-wf1561.ps1` remains available for one-frame recovery work.

## 3. Post-deployment checks

- Capture and inspect one screenshot per active frame. A scheduled-sleep WF1561 may correctly be
  black; do not wake it merely for screenshot verification.
- Inspect recent `AndroidRuntime`/ImmichFrame errors with `adb logcat`.
- Run the WF1561 monitor immediately and confirm `healthy`.
- Confirm settings survived, including server URL, native/WebView mode, active schedule, RPC, and
  MQTT configuration.
- For remote-control changes, verify authenticated REST state and one idempotent command. When MQTT
  is enabled, verify one Home Assistant device, availability, state updates, and that a retained
  action command is ignored.

## 4. Qualification and rollback

For changes to rendering, networking, scheduling, caching, REST, or MQTT, perform network-loss and
recovery tests and an appropriate soak on the constrained frame. Preserve the previous signed APK
and install it with `adb install -r` to roll back without clearing settings. Never uninstall or
clear package data unless reconfiguration is acceptable.

Record the deployed commit, APK SHA-256, version, frame serial/model, and verification outcome in
the device-specific operational repository. A source tag should describe what was actually
deployed, not merely what was built.
