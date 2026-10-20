# Deployment record: v74-eb8b5e1bbe76

- Deployed and verified: 2026-10-19 UTC, approved by the owner by release ID.
- Source commit: `443bcd1` (`main`). Debug build type.
- Version: `74` (`1.0.74.0-debug`), package `com.immichframe.immichframe.debug`.
- Targets: WF1561 at `192.168.1.216`, then W10J at `10.10.0.79`, in one guarded run with the full
  frame manifest.
- APK SHA-256: `eb8b5e1bbe76357d167bb18ddfe0a8be3b5515f7d63540c46a38363eed46642a`.
- Verified same-signer rollback: v72 (`c67ddc588e1d`), the version both frames ran before. v73
  (`75cca15705fe`) was built but never installed; v74 replaced it to include the caption change.

## Changes since v72

Notifications and camera captions take their text size from the screen (`OverlayText`, a
sixteenth of the shorter side: 50 px on the W10J, 68 px on the WF1561) instead of a fixed 22 sp,
which read at about 2% of the height on both frames. Notification titles are 1.2 times larger,
the banner's padding and corners scale with the text, and it may use 80% of the width. Camera
captions wrap to at most two centred lines.

## Checks

The installer verified hardware serial, version 74, APK hash, HOME, and the resumed Activity on
both frames. The Windows run needed `JAVA_HOME` set to the user-local Temurin 17 under
`%LOCALAPPDATA%\ImmichFrame\toolchains`; the first attempt stopped at APK inspection, before any
change to a frame.

Through a temporary Home Assistant webhook automation (removed afterwards):

- `script.message_the_frames`'s path: `rest_command.w10j_notify` returned HTTP 200 and the MQTT
  notify topic reached the WF1561. Screenshots during the 30 s message showed the bold title and
  message on one line each at the new size on both frames.
- A front-camera takeover with the caption "Person at the front" (W10J over RPC, WF1561 over MQTT)
  showed the snapshot with the caption at the new size on both frames.

Neither frame logged a fatal exception or ANR in the last 3,000 lines. MQTT on the WF1561 survived
the update, as the notify and camera messages show.

## Home Assistant (same day)

Added `rest_command.w10j_notify` to `configuration.yaml` and `script.message_the_frames` to
`scripts.yaml` (message, title, duration 3–300 s, wake; queued; W10J over RPC with
`continue_on_error`, then WF1561 over MQTT). Backups carry the suffix `.bak-2026-10-19-notify`.
