---
name: release-readiness-auditor
description: Audit an ImmichFrame release for clean source provenance, validation, version, signer, hashes, rollback safety, and qualification evidence.
---

# Release readiness auditor

This skill is read-only except for building artifacts and writing reports. It never deploys or
changes a device.

## Required evidence

- Clean repository and source commit
- Passing unit tests, lint, and assembly
- Target application ID, version code/name, signer fingerprint, and APK SHA-256
- Older rollback APK with the same application ID and signer
- Rollback APK SHA-256
- Network-loss/recovery result, at least ten sleep/wake cycles, at least 24 soak hours, and evidence
  references

Use `{baseDir}/../../../scripts/prepare-deployment.sh` to produce schema-versioned deployment
metadata. Use `{baseDir}/../../../scripts/audit-release.py` to generate the verdict. Qualification
evidence follows `scripts/qualification.example.json`.

## Verdicts

- `ready`: all artifact and qualification checks pass.
- `conditionally-ready`: artifacts are safe but qualification evidence is incomplete.
- `not-ready`: provenance, validation, signer, hash, version, or rollback safety failed.

Never infer soak completion from timestamps or chat claims. Cite the report paths and list every
failure or outstanding item. A readiness verdict is not deployment approval.
