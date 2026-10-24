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
curl -X POST -H "Authorization: Bearer TOKEN" -H "Content-Type: application/json" \
  -d '{"message":"Washer done","title":"Laundry","duration":20}' \
  http://FRAME-IP:53287/api/v1/actions/notify
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

Turning the display off over RPC or MQTT overrides **Active Times** until the schedule next
changes: turned off at 15:00 with an active window of 07:00–23:00, the frame stays off through
23:00 and wakes at 07:00 as usual. Turning it on outside the active window holds until the next
change or for two hours, whichever comes first: turned on at 23:30, it stays on until 01:30. A
command that matches the schedule clears any override. Without Active Times, "off" lasts until
the next command. The override survives a restart of the app; the next scheduled change is
taken from the schedule at the time of the command.

For automatic Home Assistant device/entity creation, enable **MQTT**, enter a broker URI such as
`tcp://HOME-ASSISTANT-IP:1883` (or `ssl://HOST:8883` with a trusted certificate), and provide the
broker credentials. The frame publishes retained MQTT Discovery definitions for display and
playback switches, brightness, next/previous/refresh/dismiss buttons, a *Use system brightness*
button, a notify entity, a connectivity sensor, and diagnostic offline-fallback, presentation-mode
and app-version sensors. It also publishes a retained availability state with an MQTT
last will and republishes discovery/state after Home Assistant's `homeassistant/status` birth
message. The generated device ID is stable across upgrades as long as application data is retained.

- **Playback** is the viewer's pause, not whether slides are advancing: the display sleeping, the
  settings screen or a camera keeps it as it was, and a frame paused at night wakes still paused.
  Next and previous resume playback.
- **Brightness** shows *unknown* while the frame follows the system brightness; *Use system
  brightness* returns to it.
- **Refresh** reloads the server settings and starts a new slideshow session (the page, in
  WebView mode); next only advances.
- Turning **MQTT** off in the settings, while the frame is connected to the broker, removes the
  device and its entities from Home Assistant.
  Clearing the app's data gives the frame a new device ID, which leaves the old device behind as
  unavailable; delete it in Home Assistant.

### Health sensors

While RPC or MQTT is enabled, the frame samples its health every minute and reports it as
`health` in `GET /api/v1/state` and as diagnostic sensors on the MQTT device: **App memory** (this
app's PSS in MB, the figure the WF1561 monitor alerts on above 120 MB), **Free memory**,
**Temperature** (the hottest thermal zone, only on frames that expose one: the WF1561 does, the
W10J does not), **Wi-Fi signal** (dBm), **IP address** (useful on the WF1561, whose address
changes at reboot), and **Last boot**, **App started** and **Last photo** as timestamps. Values a
frame cannot read are unknown. Each sensor is published only when its value changes.

```yaml
# Warn when the frame has not shown a new photo for 15 minutes
triggers:
  - trigger: template
    value_template: >-
      {{ now() - states('sensor.immichframe_living_room_last_photo') | as_datetime
         > timedelta(minutes=15) }}
```

### Notifications

`notify.<frame>_notification` shows a banner at the top of the screen over the slideshow or web
page, by default for 10 seconds:

```yaml
action: notify.send_message
target:
  entity_id: notify.immichframe_living_room_notification
data:
  message: Washer done
```

Home Assistant's MQTT notify entity forwards only the message text. For a title or a duration
(3–300 seconds), send a JSON object instead, through `mqtt.publish` to
`immichframe/<device-id>/notify` or the RPC `POST /api/v1/actions/notify` shown above:
`{"message": "Front door open", "title": "Security", "duration": 30}`. Messages are cut at 500
characters and titles at 100. Notifications show one at a time; a burst keeps only the newest
three.

While the display is off (active times, Home Assistant, or RPC), a notification is dropped unless
it has `"wake": true`. A waking notification turns the screen on and shows the banner on a black
screen, then the screen turns off again once no notification is left. The slideshow does not
start, and the active-times schedule and the display state reported to Home Assistant do not
change. Notifications are best effort: one sent while the frame is offline, reconnecting, or
running as a screensaver is lost, and Home Assistant gets no confirmation that it was shown, so
keep alerts that matter on your phone.

### Camera snapshots

A camera snapshot can take over the screen, for example when the doorbell rings. Set **Home
Assistant URL** (such as `http://homeassistant.local:8123`) in the MQTT settings first: the frame
fetches snapshots only from that address. Use the address the frame itself reaches on the LAN,
not a Nabu Casa or remote URL; an `https` address needs a certificate the frame trusts. With no
address, or a wrong one, camera commands are ignored (logged only). A relative URL is resolved against it, an absolute URL
must have the same scheme, host and port, and anything else is ignored. This keeps anyone who can
publish to the broker from making the frame fetch other addresses; show other cameras (Frigate,
for instance) through a Home Assistant camera entity. A camera's `entity_picture` is a relative
URL with an access token that Home Assistant accepts for at least five minutes, so the frame needs
no Home Assistant credentials.

```yaml
alias: Doorbell on the frame
triggers:
  - trigger: state
    entity_id: binary_sensor.doorbell
    to: "on"
actions:
  - action: mqtt.publish
    data:
      topic: immichframe/<device-id>/camera
      payload: >-
        {{ {"url": state_attr('camera.front_door', 'entity_picture'), "title": "Front door",
            "duration": 60, "refresh": 2, "wake": true} | to_json }}
```

The same JSON can go to the RPC `POST /api/v1/actions/camera`, which answers 400 when the URL is
outside the Home Assistant URL (over MQTT such a command is only logged). `duration` is 5–300 seconds
(default 30); `refresh` re-fetches the snapshot every 1–30 seconds for a near-live view (default
0, a single snapshot); `wake` works as for notifications. A new snapshot replaces the one showing,
notifications still appear over it, and tapping the screen or pressing the **Dismiss overlay**
button (`POST /api/v1/actions/dismiss`) ends it and any notifications. If the first snapshot
cannot be fetched, nothing is shown; a failed refresh keeps the last snapshot. In native mode the
slideshow holds still underneath (no zoom, video or advancing) without being reported as paused;
a WebView slideshow keeps running underneath. Like notifications, snapshots are best effort.

With **Report current photo** on (off by default), the frame also reports
the photo on screen. It asks the server for unformatted metadata (`includeMetadata=true`, ImmichFrame
server newer than 1.0.37.3; older servers send none) and publishes two more entities:

- `sensor.<frame>_current_photo`: state is the asset id; attributes carry `taken_at`, `time_zone`,
  `photo_date`, `location`, `city`/`state`/`country`, `description`, `people`, `albums`,
  `favorite`, `account`, `media_type`, `duration`, `layout`, `slots` (every photo of a split
  slide with its people), `source` (`server`, or `cache` for the offline photo, which has no asset
  id) and `shown_at`.
- `sensor.<frame>_photo_taken_at`: the capture time as a `timestamp` sensor.
- `image.<frame>_current_photo_image`, only when **Home Assistant photo image** is not *Off*
  (the default). *Blurred colours only* publishes the photo's thumbhash, which shows nothing
  recognisable. *Thumbnail of the screen* publishes a JPEG of the rendered frame, split slides and
  background included, at most 480 px on its long edge (roughly 30–50 KB per slide). Both are
  retained on the broker and resent when Home Assistant restarts. The image goes over MQTT only,
  not the RPC state.

The same object is `currentPhoto` in `GET /api/v1/state`. Both update only when a slide is
presented, not when it is prefetched, and keep the last photo while the display is off. Each
state change publishes only the topics whose payload changed. **Report people names** removes
`people` everywhere. Turning reporting off removes the photo entities and clears the last photo
from the broker; turning the image off removes only the image entity and its last image. Photo reporting runs in the activity (HOME) mode only, not as a screensaver. People
and places end up in Home Assistant history, so consider excluding these entities from the
recorder or InfluxDB:

```yaml
# Announce when someone is on screen, on either side of a split slide
trigger:
  - platform: state
    entity_id: sensor.immichframe_living_room_current_photo
condition:
  - "{{ 'Mom' in (state_attr(trigger.entity_id, 'slots') | map(attribute='people') | sum(start=[])) }}"
```

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
radius 16 with three passes, and alpha 84 respectively. During activity and screensaver playback,
server settings are checked every 30 seconds; changed presentation options are applied without
restarting an unchanged slideshow, while failed checks retain the last settings. The offline
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
