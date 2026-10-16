# Deployment record: v63-dad33f8cbafb

- Deployed and verified: 2026-10-15 UTC.
- Source commit: v63 version bump on top of `6d1f401` (local `feat/native-video-playback`; not pushed at deployment time).
- Version: `63` (`1.0.63.0-debug`), package `com.immichframe.immichframe.debug`.
- Target: WF1561 only, at `192.168.1.216`; model and hardware serial `c3d9b8674f4b94f6` were verified by repository deployment scripts. W10J was not changed and remains on v57.
- APK SHA-256: `dad33f8cbafb3df8c389642cf61f87b4c93b71310d61f2a0260f2d34ba55b43b`.
- Verified same-signer rollback: v57 (`1.0.57.0-debug`), APK SHA-256 `0f9f1cd7c57fff63c63a850f3be5fc45c7abe23790512694ee0ed089a6863f43`.

## Changes since v62

- RPC `PUT` requests (`/api/v1/display`, `/playback`, `/brightness`) read their JSON body. NanoHTTPD writes PUT bodies to a temp file rather than `postData`, so these endpoints had rejected every valid request with 400 since they were added. This was found while verifying v62's remote pause fix on the device.

## Result

The guarded installer upgraded in place from v62 and verified v63, correct HOME, a running process, and resumed MainActivity. Remote control was then exercised through the frame's RPC API:

- `GET /api/v1/state` reported `playing` (before v62 it reported `paused` after boot).
- Two `PUT /api/v1/playback {"paused":true}` requests returned 200, the state became `paused`, and the slide thread used no CPU over the following 15 s, so the repeated pause did not toggle playback back on.
- `PUT {"paused":false}` returned 200, the state became `playing`, and slides resumed.

## Qualification and evidence

Same open items as v62, except remote pause/resume via RPC is now verified. MQTT control, display and brightness PUTs, the invalid-URL path, the widget, split view, prefetched videos, sleep/wake, and the soak remain unexercised on a device. No evidence archive, rollback, uninstall, data clear, tag, or push occurred.
