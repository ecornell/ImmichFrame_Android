---
name: deployment-closeout
description: Close an ImmichFrame deployment with observed installed hashes, health, screenshots, qualification status, evidence archive, provenance, and handoff.
---

# Deployment closeout

Use after `frame-deployment-guardian`; this skill is read-only with respect to frames. Never install,
restart, wake, change HOME, or alter settings.

1. Verify the source tree and report upstream divergence. Do not commit, tag, or push without
   explicit authorization.
2. Require a unique `versionCode` for each distinct APK hash. Treat an exact installed hash as an
   idempotent release, never as evidence that a changed same-version APK is safe.
3. Run guarded verify-only checks and collect manifest-driven health bundles.
4. Pull and hash the installed base APK through a reviewed repository script; compare application
   ID, version, signer, and SHA-256 with deployment metadata.
5. Capture identity-verified screenshots for active frames and run the seam analyzer for rendering
   changes. Physical-panel claims still require human confirmation.
6. Derive qualification with `collect-qualification.py`; never promote `conditionally-ready` based
   on a smoke test. State missing network recovery, cycles, and soak explicitly.
7. Create a sanitized content-addressed bundle with `archive-release.py`. Include metadata,
   readiness, incremental deployment results, verify-only results, health, screenshot hashes, and
   bounded manual notes; exclude credentials, local manifests, raw logcat, and topology.
8. Record deployed commit/release/hash/device models and archive location. Recommend an annotated
   deployed tag and push, but execute neither without approval.

Stop with a blocked closeout if installed hashes differ, any device identity is unverified, evidence
archiving fails, or a partial deployment has no journal. Report validation commands and skipped
platform-specific checks exactly.
