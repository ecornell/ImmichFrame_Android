---
name: frame-deployment-guardian
description: Safely prepare, verify, and deploy ImmichFrame APKs to authorized frames. Use for frame deployment, rollout, rollback, or verify-only requests.
---

# Frame deployment guardian

Use only repository scripts from `{baseDir}/../../../scripts`; do not construct alternative ADB
mutation commands.

## Non-negotiable safety rules

- Never run `adb uninstall`, `pm clear`, delete application data, or perform an implicit downgrade.
- Never mutate a device that lacks a matching model and hardware serial.
- Never deploy from a dirty tree or an unverified APK.
- Never deploy without a verified, signer-compatible rollback APK.
- Never treat a build or verify-only request as approval to mutate a frame.
- Request explicit user confirmation after displaying the exact release ID, target devices, current
  and target versions, target hash/signer, and rollback version/hash.
- Confirmation is valid only for the displayed release ID and device set. If either changes, ask
  again.

## Workflow

1. Read `docs/DEPLOYMENT.md` and the local frame manifest. Do not print credentials.
2. Prepare the target and rollback evidence:

   ```bash
   scripts/prepare-deployment.sh TARGET_APK_PATH ROLLBACK_APK_PATH
   scripts/audit-release.py --metadata app/build/outputs/apk/debug/deployment-metadata.json \
     --qualification QUALIFICATION_JSON --output app/build/outputs/release-readiness.json
   ```

3. Stop if readiness is `not-ready`. Explain any `conditionally-ready` qualification gaps.
4. Run `scripts/deploy-frames.ps1 -VerifyOnly`. This is the only permitted preapproval device
   operation.
5. Present the deployment plan and ask: `Deploy RELEASE_ID to DEVICE_NAMES?`
6. Only after an unambiguous yes, invoke `deploy-frames.ps1` with the metadata and the exact
   `-ApprovalReleaseId`. Also provide `-OutputJsonPath` for the audit record.
7. Run verify-only again, then use the frame-health-triage skill.
8. Record commit, release ID, version, APK hash, signer, device identity, and result in the device
   operational repository.

Rollback is a separate, explicit recovery operation. Do not turn a rollback artifact into a normal
target. Re-run verify-only, present the identity/signer/hash/version plan, request a second explicit
confirmation for `rollback-RELEASE_ID`, and only then use the script's `-Rollback` mode. This is the
sole permitted downgrade path and preserves application data.
