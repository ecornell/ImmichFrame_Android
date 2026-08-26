---
name: frame-health-triage
description: Collect and interpret read-only ImmichFrame health evidence including monitor JSON, logcat, memory, HOME, panel process, and thermal state.
---

# Frame health triage

This skill is strictly read-only with respect to the Android device. Never restart or stop a
process, change HOME, wake or reboot a frame, clear logcat, install an APK, or alter settings.

## Collection

Prefer an existing `latest.json` when the user asks only for interpretation. For fresh evidence,
run `{baseDir}/../../../scripts/collect-frame-health.ps1`. It verifies the hardware serial, reuses
the monitor, classifies a bounded logcat window without retaining log text, and emits a
versioned JSON bundle.

## Interpretation

Report one primary state and supporting evidence:

- `healthy`
- `offline`
- `boot-incomplete`
- `app-not-running`
- `app-not-foreground`
- `home-changed`
- `panel-process-missing`
- `memory-pressure`
- `thermal-warning`
- `application-crash`
- `network-or-authentication-failure`
- `insufficient-evidence`

Distinguish monitor observations from interpretation. Treat a scheduled sleeping/black frame as
potentially expected. Do not infer physical-panel health from screenshots alone. Thermal-zone names
and units vary, so rely on the monitor's normalized threshold and state uncertainty when raw data is
ambiguous. Never reproduce secrets, URLs containing credentials, bearer tokens, RPC tokens, or MQTT
passwords.

Conclude with safe next evidence to collect. Any repair or deployment must be a separate request and,
for deployment, use the frame-deployment-guardian skill.
