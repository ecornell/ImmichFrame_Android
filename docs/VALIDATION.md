# Development validation

Run the host checks, unit/integration tests, Android lint, and all validation APK builds with:

```bash
bash scripts/validate.sh testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest assembleRelease assembleFrame
```

`assembleFrame` builds the R8-minified, non-debuggable `frame` variant that frames run (see
`docs/DEPLOYMENT.md`). Unit tests and lint still run against the debug variant, so the frame build
must also be exercised on a device: R8 problems such as stripped reflection only appear at runtime.

The script selects the local JDK/SDK defaults documented in the README. It also runs Python
operational tests and Pester deployment tests when PowerShell/Pester is available. Deployment
tests use fake ADB; this command does not install APKs or contact deployed frames.

The Gitea workflow builds the same Android variants. Without `signing.properties`, `assembleRelease`
produces `app/build/outputs/apk/release/app-release-unsigned.apk`, exercising R8 without production
credentials. An unsigned APK is a **validation artifact**, not an installable deployment release.
An explicitly configured signing file retains the existing signed-release behavior. These builds
do not establish release provenance or physical-device qualification; use the guarded deployment
runbook for those steps.

## Automated coverage

- `PlaybackSessionTest`: invalidation rejects callbacks from obsolete native sessions; cached
  portraits bypass split pairing, while live portraits still pair.
- `PlaybackTimerTest`: stop/restart across mode changes, idempotent startup, immediate weather
  ticks, retry replacement, changed-interval rescheduling, and no rescheduling while stopped.
  `AsyncRefreshTimerTest` verifies the shared 30-second server-settings cadence, single in-flight
  refresh, lifecycle cancellation, and rejection of stale completions. Activity and DreamService
  share these timer implementations so running flags cannot drift from callback removal.
- `NextSlideQueueTest`: a prefetched slide is presented immediately, an advance during a build
  waits for it, only one build runs at a time, stale slides are discarded, a failed prefetch is
  silent while a failed requested build is reported, and pausing keeps but never presents a slide.
- `FrameRendererTest`: letterbox regions cover everything outside a fitted photo (none when
  cropped), the feather overlay is the complement of the former photo mask, and the half-size
  background stage halves the blur radius.
- `ImageResponseTest`: a metadata-only response (server `includeImage=false`) requires fetching
  the raw preview bytes by asset id, while an older server's inline Base64 image is still used.
  Requested metadata parses, and its absence (older servers) leaves it null.
- `FrameNotificationTest`: plain, JSON, and malformed notification payloads, missing messages,
  duration bounds and text limits, the `wake` flag, and the queue that keeps only the newest
  notifications.
  `RemoteControlTest` covers the RPC notify route and the notify discovery entry, and the MQTT
  integration test delivers a notification published to the notify topic. The banner itself
  (fade, queueing on screen, dropping while the display is off, and a `wake` notification turning
  the screen on and off again without starting the slideshow or a manual override) needs a device
  check.
- `FrameHealthTest`: thermal readings to °C (millidegrees, degrees, unreadable and implausible
  values), minute rounding, the `health` RPC state, the diagnostic sensor discovery, no
  temperature entity without a thermal zone, and `None` for unreadable values. The sampler's
  Android reads (PSS, free memory, Wi-Fi RSSI, IP address) need a device check.
- `DisplayHoldTest`: a remote display command that differs from Active Times holds until the
  next scheduled change (or the next command without one), an "on" for at most two hours, and
  one that matches clears it.
  `HelpersTest` covers `nextScheduleChange` in both directions. Holding against the 30-second
  check, `onResume`, a manual wake, and an app restart needs a device check.
- `CameraRequestTest`: camera command parsing, timing bounds, and the origin check that resolves
  relative snapshot URLs against the Home Assistant URL and rejects every other origin.
  `RemoteControlTest` covers the camera and dismiss routes and the dismiss button, and the MQTT
  integration test delivers a camera command and a dismiss press. Fetching, decoding, refresh,
  replacement, tap-to-dismiss, the held slideshow, and wake need a device check.
- `CurrentPhotoTest`: the reported photo for single, split, video and offline-cache slides,
  people removed when not reported, and `currentPhoto` in the RPC state.
- `FrameScheduleManagerTest`: the wake alarm is exact below API 31 and, from API 31, only while
  the exact-alarm grant is held; otherwise it falls back to `setAndAllowWhileIdle`.
- `HelpersTest`: next inactive-to-active schedule transitions, same-day/overnight intervals,
  adjacent intervals, always-active schedules, and spring/fall daylight-saving changes.
- `RpcHttpServerIntegrationTest`: a real NanoHTTPD instance on an ephemeral loopback port verifies
  authentication, state JSON, mutation dispatch, invalid JSON, and oversized-body rejection. Raw
  sockets check that an unread request body closes the keep-alive connection instead of being
  parsed as the next request, that a bodyless one stays open, and that a ninth concurrent
  connection is closed (`RpcHttpServer.MAX_CONNECTIONS`).
- `MqttControlClientIntegrationTest`: the real Paho client connects to a bounded loopback-only
  MQTT test peer, publishes retained discovery/state, handles commands, resynchronizes after a
  retained Home Assistant birth, and publishes offline availability on stop. It also checks that
  only changed state topics are sent, that the current photo is published, and that a birth
  resends the full state. `MqttStatePublisherTest` holds back an older update's listener until a
  newer update has been published and checks that the newest state stays retained, and that 1,600
  concurrent updates leave the final state on the broker. No broker service,
  credentials, extra dependencies, or external network are required. The test peer implements
  only the MQTT subset used here; TLS, broker restart/reconnect, and will delivery need broader
  broker/device qualification. Retained command filtering also has a separate policy unit test.
  `RemoteControlTest` covers the photo discovery ids, `None` for unknown values, and the empty
  retained payloads that remove the photo entities when reporting is off. The opt-in image entity
  is checked the same way, and a second integration test publishes a retained photo image and
  receives it again after a Home Assistant birth. The JPEG encoding itself (`PhotoImage`) uses
  Android bitmaps and is not covered by JVM tests: check the image, its size, and memory on a
  device.

JUnit results are under `app/build/reports/tests/testDebugUnitTest/`; lint reports are under
`app/build/reports/`. Building `assembleDebugAndroidTest` compiles instrumentation tests; it does
**not** execute them.

## Playback device/emulator regression checklist

Run on an explicitly selected test device/emulator, not an operational frame. Use an API 27
target and a modern Android target. Do not run unqualified builds against production frames.

1. Seed a successful image/settings cache, stop the process, and disable the backend. Start native
   activity and screensaver separately: both should show a full-sized last-known image. Repeat
   with one cached portrait and split-view settings; it must not wait for a second portrait.
2. Restore the backend: fresh images should replace the cache, weather should resume, and the
   offline indicator should clear only after a valid live image is presented.
3. On an emulator with a working WebView, switch native → WebView → native repeatedly. Both image
   and weather timers must restart once, with no duplicate callbacks. Also test schedule sleep/wake
   and repeated dream start/stop while settings/weather/image requests are delayed.
4. Serve a successful HTTP response with invalid Base64 or undecodable image bytes. The last good
   frame should remain visible; automatic playback should retry without an uncaught exception.
   Resume valid payloads and verify recovery. Pause or change modes during decode/retry and confirm
   stale work cannot present a frame, fetch a paired image, or restart a stopped timer.
   The next slide is built after each crossfade: a slide should change exactly on the interval,
   next/previous and pause/resume must not skip or double-advance, and a failed prefetch must not
   show the offline indicator while the current slide is still good. In split view, check that
   portraits still pair and a lone portrait followed by a landscape shows the landscape.
   Compare feathered fit-mode edges (blurred, average, and black backgrounds) against a
   pre-change screenshot for seams or banding.
5. With `ShowVideos` enabled on the server and **Play videos in native mode** on, a video should
   show its poster, fade into playback (muted unless `PlayAudio`), and advance when it ends — not
   at the photo interval. Pause before and during playback, use previous/next, toggle display
   sleep, and break the stream mid-video: the poster must remain, the watchdog must advance, and
   no stopped timer may restart. Turning the preference off, or an older server, yields photos
   only. Record decoder support and PSS for 1080p H.264 and HEVC on each target device.
6. Check stream throughput on each frame's real Wi-Fi: play the largest-bitrate videos in the
   library several times and confirm none stall or are cut off by the watchdog (duration + 15 s).
   Videos are streamed through ImmichFrame at their Immich playback bitrate, often the original
   (~15 Mbit/s for 1080p) with no on-device cache. Record the frame's link rate/RSSI. If playback
   stalls, lower Immich's playback bitrate rather than changing the app. Production now
   re-encodes anything above 720p with a 4500k cap; the settings, the Immich v3 metadata refresh
   a video needs before it will encode, and the gap for small high-bitrate videos are documented
   in `DEPLOYMENT.md` of the ImmichFrame repository. The W10J measured about 25 Mbit/s.
7. Active Times wake on newer Android: both deployed frames are below the API levels this
   concerns (W10J Android 7.1.2/API 25, WF1561 Android 8.1/API 27), so neither exercises it.
   Before running on an API 29+ frame, check that the scheduled wake alarm actually brings
   MainActivity to the front while it is in the background (Android 10 restricts background
   activity starts; `ScheduleWakeReceiver` starts it directly), and on API 31+ that the alarm is
   exact (`dumpsys alarm` shows the app's alarm as exact while the exact-alarm grant is held, and
   inexact `setAndAllowWhileIdle` when it is revoked).
8. Remote brightness: set 30% from Home Assistant, turn the display off and on (remotely, by
   Active Times, and with the power button), and wake it with a `"wake": true` notification. The
   panel must come back at 30%, Home Assistant must keep showing 30%, and a brightness change sent
   while the display is off must not light the panel until it wakes.
9. Configuration changes: with RPC and MQTT on, attach and detach a keyboard or remote, rotate
   (or resize a multi-window emulator), and change the locale. The frame must not restart (the
   MQTT availability stays online, `App started` is unchanged, playback is not reset), the clock
   uses the new locale on its next tick, a showing notification is resized, and the next slide
   fits the new window.
10. Run existing renderer instrumentation tests and the established memory/network-loss soak.
   Screenshot, HOME, panel-path, and thermal checks remain deployment qualification requirements.

TLS/secret-storage changes and a broader unified rendering pipeline are deliberately outside this
change. Shared session/timer/pairing policy is extracted; Android UI, bitmap ownership, and the two
presentation surfaces remain separate.
