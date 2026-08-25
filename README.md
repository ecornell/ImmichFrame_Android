<div align="center">
  <a href="https://github.com/immichFrame/ImmichFrame_Android">
    <img src="https://github.com/immichFrame/ImmichFrame_Desktop/blob/main/src-tauri/icons/icon.png" alt="Logo" width="200" height="200">
  </a>

  <h3 align="center">ImmichFrame Android</h3>

  <p align="center">
    Android client for <a href="https://github.com/immichFrame/ImmichFrame">ImmichFrame</a>.
    <br />
    <br />
    <a href="https://immichframe.dev">Documentation</a>
  <p>
</div>

## 📄 Documentation
You can find the documentation [here](https://immichframe.dev).

# Development
- [Android Studio](https://developer.android.com/studio/), or run `scripts/setup-dev.sh` on Linux.
- The command-line setup installs Temurin 17, Android SDK 36, build tools and ADB without root.
- Validate changes with:
  ```bash
  export JAVA_HOME="$HOME/.local/share/jdks/temurin-17"
  export ANDROID_HOME="$HOME/Android/Sdk" ANDROID_SDK_ROOT="$ANDROID_HOME"
  bash gradlew testDebugUnitTest lintDebug assembleDebug
  ```

## Dedicated frames and WF1561

Native mode is the default because old dedicated devices may ship an unusably old WebView. The
Euphro WF1561 deployment is Android 8.1/API 27 with 1 GB RAM, so keep image dimensions bounded and
perform a 24–72 hour memory/network-loss soak test before deployment. RPC is disabled by default;
when enabled it requires `POST` and `Authorization: Bearer <RPC token>`.

Native rendering uses viewport-sized output frames, equal fixed slots for paired portraits,
per-photo metadata, sampled decoding, soft/average/black background choices, fit/crop scaling,
safe overlay margins, and optional metadata fading/burn-in movement. WebView is created lazily only
when WebView mode is selected. On the WF1561 this reduced observed native-mode PSS from about 150 MB
to roughly 74–96 MB during initial live validation.

The native Ken Burns path defaults to 30 FPS and retains zoom while reducing work on old HWUI
drivers. It adds constrained directional panning and selects a gentler 1.06–1.10 endpoint for
letterboxed/group-like and split images, with up to 1.16 for better-filled images. Full images are
Base64-decoded once, sampled for their actual fit/crop destination, and old frame drawables are
released after each crossfade. High-confidence embedded black borders are removed before layout.
Side fill comes from a 320×180 ARGB blurred, darkened copy of the actual photo with adaptive
five-stop feathered seams; split dividers use a darkened blend of both photos and default to 6dp.
Native settings expose edge width, blur radius, and background darkness; WF1561 defaults are 32dp,
radius 16 with three passes, and alpha 84 respectively. The offline
cache stores compressed binary image data and is written at
most every 15 minutes; a compact indicator briefly reports failed image refreshes. `Ken Burns zoom
frame rate` can be tuned from 10–60 FPS in native settings. On the WF1561, the 30 FPS path reduced
measured application CPU from roughly 100–120% to about 43% of one core and janky frames from 69%
to about 11%, while keeping animated zoom and pan enabled.

WF1561 operational constraints:

- Keep the OEM `com.zeasn.frame` package enabled and running; it maintains the physical panel path.
- Keep the separately installed BootStopper receiver; it removes the OEM logo layer after boot.
- Do not rely on a DHCP address or WLAN MAC because both can change after reboot.
- Keep ImmichFrame selected as HOME. The activity is `singleTask` so repeated HOME commands reuse it.
- Use native mode with a reachable LAN backend; the last successful image is retained for outages.

### Current WF1561 local deployment

The frame currently runs the side-by-side debug package
`com.immichframe.immichframe.debug` as its preferred HOME. Official
`com.immichframe.immichframe` v50 remains installed as a rollback target. Debug builds deliberately
retain the HOME category and are signed by the WSL identity at `~/.android/debug.keystore`; preserve
that keystore to keep in-place updates possible.

The authorized ADB key is restored in WSL `~/.android/` and Windows `%USERPROFILE%\.android\` from
the protected local backup. Windows Platform Tools at
`%LOCALAPPDATA%\Android\platform-tools\adb.exe` are the proven client for this WSL setup. Discover
the current frame address by scanning the LAN for TCP 5555 because its IP and MAC change at reboot.

After the validation command above, copy `app/build/outputs/apk/debug/app-debug.apk` to a Windows
path and deploy it without uninstalling the existing debug package:

```powershell
$adb = "$env:LOCALAPPDATA\Android\platform-tools\adb.exe"
& $adb connect FRAME-IP`:5555
& $adb -s FRAME-IP`:5555 install -r C:\Users\elija\AppData\Local\Temp\ImmichFrame-debug.apk
& $adb -s FRAME-IP`:5555 shell cmd package set-home-activity --user 0 `
  com.immichframe.immichframe.debug/com.immichframe.immichframe.MainActivity
```

Prefer the included deployment wrapper, which performs those steps in the correct order and also
dismisses a resolver that Android 8.1 may have opened while replacing the HOME package:

```powershell
.\scripts\deploy-wf1561.ps1 -FrameIp FRAME-IP `
  -ApkPath C:\Users\elija\AppData\Local\Temp\ImmichFrame-debug.apk
```

Install the optional read-only host monitor after deployment. It runs for less than two minutes
every five minutes, records only health/resource summaries, keeps 14 days of daily CSV history, and
does not restart or modify the frame:

```powershell
.\scripts\install-wf1561-monitor.ps1 -FrameIp FRAME-IP
Get-Content "$env:LOCALAPPDATA\ImmichFrame\monitor\latest.json"
```

The monitor checks ADB reachability, ImmichFrame and OEM panel processes, preferred HOME, PSS, CPU,
temperature, whether ADUPS FOTA has returned, and flags PSS above 120 MB or temperature above 80°C.
A current problem creates
`%LOCALAPPDATA%\ImmichFrame\monitor\alert.txt`; a healthy sample removes it. Because this ROM's IP
changes after reboot, the monitor caches the last working endpoint and automatically scans the
original `/24` for TCP 5555 only when known endpoints fail. Candidates must report the expected
WF1561 hardware serial, so another Android ADB device is not mistaken for the frame. Remove it with
`Unregister-ScheduledTask -TaskName 'ImmichFrame WF1561 Monitor' -Confirm:$false`.

Android may clear its preferred-HOME record whenever a HOME-capable APK is replaced. Applications
cannot silently grant themselves HOME status, so every deployment must run
`cmd package set-home-activity` after `install -r`; the wrapper automates this and verifies the
resolved component. Do not send HOME or click the resolver between those steps.

Verify with `cmd package resolve-activity` before changing foreground state. To restart the preferred
debug HOME, use only `am force-stop com.immichframe.immichframe.debug` and let Android relaunch it
once. The authoritative live-device state and rollback commands are maintained in the sibling
`wf1561-photo-frame/HANDOFF.md` repository.

Updates must use the same application ID and signing certificate. Test an upgrade with `adb install
-r app.apk`, verify settings and HOME selection are retained, then reboot with and without the
backend available. Never clear package data unless reconfiguration is acceptable.

Recommended release checks include repeated HOME events, server loss/recovery, ten scheduled
sleep/wake cycles, physical-panel verification (not only `screencap`), `dumpsys meminfo`, and an
unattended 72-hour soak. On WF1561, preserve the recovery artifacts and instructions maintained in
the separate `wf1561-photo-frame` project.
