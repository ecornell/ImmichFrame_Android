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

The same validation is available as `scripts/validate.sh`. See
[`docs/VALIDATION.md`](docs/VALIDATION.md) for release/R8 checks, network integration tests, and
the playback regression checklist, and
[`docs/DEPLOYMENT.md`](docs/DEPLOYMENT.md) for the multi-frame deployment and verification runbook.

## Dedicated frames and WF1561

Native mode is the default because old dedicated devices may ship an unusably old WebView. The
Euphro WF1561 deployment is Android 8.1/API 27 with 1 GB RAM, so keep image dimensions bounded and
perform a 24–72 hour memory/network-loss soak test before deployment. RPC is disabled by default.
Legacy command routes require `POST`; the versioned API uses `GET`, `PUT`, and `POST`. Every route
requires `Authorization: Bearer <RPC token>` except the availability-only `GET /health` probe.

### REST and Home Assistant control

Enable **LAN RPC server** and copy the generated token from the app settings. The existing
`/on`, `/off`, `/next`, `/previous`, `/pause`, `/settings`, and `/brightness?value=0.5` POST
routes remain available. The versioned API returns JSON and uses the same bearer token:

```bash
curl -H "Authorization: Bearer TOKEN" http://FRAME-IP:53287/api/v1/state
curl -X PUT -H "Authorization: Bearer TOKEN" -H "Content-Type: application/json" \
  -d '{"active":true}' http://FRAME-IP:53287/api/v1/display
curl -X PUT -H "Authorization: Bearer TOKEN" -H "Content-Type: application/json" \
  -d '{"paused":false}' http://FRAME-IP:53287/api/v1/playback
curl -X PUT -H "Authorization: Bearer TOKEN" -H "Content-Type: application/json" \
  -d '{"brightness":0.5}' http://FRAME-IP:53287/api/v1/brightness
curl -X POST -H "Authorization: Bearer TOKEN" -H "Content-Type: application/json" \
  -d '{}' http://FRAME-IP:53287/api/v1/actions/next
```

Home Assistant can poll `/api/v1/state` with its REST integration and call action endpoints with
`rest_command`. Store the bearer token in `secrets.yaml` and pass it as an `Authorization` header.
For example:

```yaml
rest:
  - resource: http://FRAME-IP:53287/api/v1/state
    scan_interval: 30
    headers:
      Authorization: !secret immichframe_authorization
    sensor:
      - name: ImmichFrame playback
        value_template: "{{ value_json.playback }}"
        json_attributes: [displayActive, serverConnected, offlineFallback, presentationMode]

rest_command:
  immichframe_next:
    url: http://FRAME-IP:53287/api/v1/actions/next
    method: POST
    headers:
      Authorization: !secret immichframe_authorization
      Content-Type: application/json
    payload: '{}'
```

Set the secret value to `Bearer TOKEN`, including the `Bearer` prefix.

For automatic Home Assistant device/entity creation, enable **MQTT**, enter a broker URI such as
`tcp://HOME-ASSISTANT-IP:1883` (or `ssl://HOST:8883` with a trusted certificate), and provide the
broker credentials. The frame publishes retained MQTT Discovery definitions for display and
playback switches, brightness, next/previous/refresh buttons, connectivity/offline sensors,
presentation mode, and app version. It also publishes a retained availability state with an MQTT
last will and republishes discovery/state after Home Assistant's `homeassistant/status` birth
message. The generated device ID is stable across upgrades as long as application data is retained.

Both interfaces are intended only for a trusted LAN. Plain `http://`, `tcp://`, and `ws://` expose commands
and credentials to the local network; do not port-forward either interface to the internet. Home
Assistant publishes commands without retention, and the frame also rejects retained command
messages so stale actions cannot replay after reconnect. The retained `homeassistant/status` birth
message remains accepted so discovery and state can be resynchronized.

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

Do not install directly with ADB or use the retired `deploy-wf1561.ps1` path. Follow
[`docs/DEPLOYMENT.md`](docs/DEPLOYMENT.md), which requires artifact/signature checks, a verified
rollback APK, hardware identity, an explicit release-ID approval, and post-install verification.

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
`cmd package set-home-activity` after package replacement; the guarded deployment script automates this and verifies the
resolved component. Do not send HOME or click the resolver between those steps.

Verify with `cmd package resolve-activity` before changing foreground state. To restart the preferred
debug HOME, use only `am force-stop com.immichframe.immichframe.debug` and let Android relaunch it
once. The authoritative live-device state and rollback commands are maintained in the sibling
`wf1561-photo-frame/HANDOFF.md` repository.

Updates must use the same application ID and signing certificate. Use the guarded deployment
workflow to test an upgrade, verify settings and HOME selection are retained, then reboot with and
without the backend available. Never clear package data unless reconfiguration is acceptable.

Recommended release checks include repeated HOME events, server loss/recovery, ten scheduled
sleep/wake cycles, physical-panel verification (not only `screencap`), `dumpsys meminfo`, and an
unattended 72-hour soak. On WF1561, preserve the recovery artifacts and instructions maintained in
the separate `wf1561-photo-frame` project.
