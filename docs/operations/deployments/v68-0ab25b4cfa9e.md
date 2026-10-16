# Deployment record: v68-0ab25b4cfa9e

- Deployed and verified: 2026-10-16 UTC.
- Source commit: v68 version bump on top of `e0d6460` (local `main`; not pushed at deployment time). Debug build type.
- Version: `68` (`1.0.68.0-debug`), package `com.immichframe.immichframe.debug`.
- Targets: WF1561 at `192.168.1.216`, then W10J at `10.10.0.79`, each verified by repository deployment scripts.
- APK SHA-256: `0ab25b4cfa9e7213d20baba7c750f8894d3d26c8d4c43abd1f119ff63140e31b`.
- Verified same-signer rollback: v57 (`1.0.57.0-debug`).

## Changes since v67

- In split view, a portrait followed by a non-portrait is no longer dropped after the server counted it as shown. It waits one slide for a portrait partner and is shown alone if it still has none.

## Result

The guarded installer upgraded both frames in place and verified v68, the APK hash, correct HOME, a running process, and resumed MainActivity. The first W10J attempt reported no matching authorized frame although ADB listed the device; a retry seconds later succeeded. No fatal exception, ANR, or image load failure appeared in the last 3,000 logcat lines on either frame.

## Server

Deployed first: ImmichFrame server 1.0.25.5 (revision `cf4d21036a8b`, rollback image `immichframe:rollback-20261016-154748`). Selection picks at random from the least recently shown quarter of the album, using one history shared by every client and saved to `ShownAssets.json` in the mounted config directory (`/opt/stacks/immich/immichframe-config`). Smoke checks passed; the history file held 11 entries 30 s after start, and the server logged one pick about every 3 s for the two frames. The frames send no `clientIdentifier`, so the per-pick log shows an empty client name.

## Qualification and evidence

**Conditionally-ready.** Not exercised on a device: split-view pairing (neither frame was observed in split view) and the video fixes from v67. No evidence archive, rollback, uninstall, data clear, or tag.
