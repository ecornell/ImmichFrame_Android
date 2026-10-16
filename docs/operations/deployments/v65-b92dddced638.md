# Deployment record: v65-b92dddced638

- Deployed and verified: 2026-10-16 UTC.
- Source commit: `6565ce5` (local `main`). Debug build type.
- Version: `65` (`1.0.65.0-debug`), package `com.immichframe.immichframe.debug`.
- Target: WF1561 only, at `192.168.1.216`, verified by repository deployment scripts. W10J remains on v64.
- APK SHA-256 prefix: `b92dddced6387b7e`.
- Verified same-signer rollback: v57 (`1.0.57.0-debug`).

## Changes since v64

- Experimental local preference `screenBuffer16Bit` (no UI, default off) requesting an RGB_565 window. Reverted in source by `fda6f05`; the installed v65 is otherwise identical to v64 while the preference is absent.

## Result

The guarded installer upgraded in place from v64 and verified v65, the APK hash, correct HOME, a running process, and resumed MainActivity. With the preference off, two 60 s runs matched v64: total 72–73% of one core (surfaceflinger 25%, composer 9%, app 38–39%), 1,055–1,062 frames, 3.1–3.4% janky, 90th percentile 11–12 ms.

With the preference on, the window requested RGB_565 (`dumpsys window`: `fmt=4`), but SurfaceFlinger still showed the layer as `RGBA_8888` with 8,100 KiB buffers. Android 8.1 gives hardware-accelerated windows a 32-bit surface regardless of the requested format, so the setting could not reduce compositing cost and was not measured further. The preference was removed from the device, the app restarted, and the source change reverted.

## Qualification and evidence

**Conditionally-ready**, with the same open items as v64. No evidence archive, rollback, uninstall, data clear, tag, or push.
