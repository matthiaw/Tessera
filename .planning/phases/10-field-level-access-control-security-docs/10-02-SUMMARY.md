---
phase: "10"
plan: "02"
subsystem: ops/security
tags: [tde, luks, encryption, data-at-rest, runbook, postgres, ionos]
dependency_graph:
  requires: []
  provides: [tde-deployment-runbook]
  affects: [docs/ops]
tech_stack:
  added: []
  patterns: [luks2-dm-crypt, gpg-symmetric-backup, vault-key-storage]
key_files:
  created:
    - docs/ops/tde-deployment-runbook.md
  modified: []
decisions:
  - "Included LUKS header backup appendix as critical recovery safeguard beyond plan scope (Rule 2)"
  - "Added systemd dependency configuration for boot ordering in troubleshooting section"
metrics:
  duration_seconds: 161
  completed: "2026-04-17T19:39:13Z"
---

# Phase 10 Plan 02: Postgres TDE Deployment Runbook Summary

LUKS2/dm-crypt operational runbook for encrypting Postgres data at rest on IONOS VPS with CMK-encrypted backups stored via Vault key management.

## Task Completion

| Task | Name | Commit | Files |
|------|------|--------|-------|
| 1 | Create Postgres TDE deployment runbook | b9b5fb0 | docs/ops/tde-deployment-runbook.md |

## What Was Built

Created `docs/ops/tde-deployment-runbook.md` (801 lines) covering 9 sections plus 2 appendices:

1. **Overview** -- scope, prerequisites, deployment architecture
2. **LUKS Volume Setup** -- step-by-step `cryptsetup luksFormat` with LUKS2, aes-xts-plain64, 512-bit key
3. **Postgres Data Directory Configuration** -- Docker Compose volume mount, PGDATA subdirectory pattern, verification commands
4. **Auto-Unlock on Boot** -- two options documented: key file (default) and manual unlock (higher security), with Vault passphrase storage
5. **Key Rotation Procedure** -- passphrase rotation, key file rotation, quarterly schedule, full LUKS2 re-encryption for master key compromise
6. **CMK-Encrypted Backups** -- `pg_dump | gpg --symmetric --cipher-algo AES256` pipeline, Vault CMK storage, backup rotation policy (7 daily/4 weekly/3 monthly), CMK rotation via Vault KV versioning
7. **DR Restore from Encrypted Backup** -- 10-step end-to-end procedure from fresh VPS provisioning through health verification
8. **Monitoring** -- LUKS status checks, S.M.A.R.T. disk monitoring, automated alerting script with webhook support, Prometheus node_exporter metrics and alert rules
9. **Troubleshooting** -- LUKS unlock failures, Postgres boot ordering, backup decryption issues, performance degradation, lost credentials recovery

Appendices cover quick-reference command table and LUKS header backup procedure.

## Deviations from Plan

### Auto-added Functionality

**1. [Rule 2 - Missing Critical] Added LUKS header backup appendix**
- **Found during:** Task 1
- **Issue:** Plan did not mention LUKS header backup, but a damaged header makes the entire encrypted volume unrecoverable regardless of having the correct passphrase
- **Fix:** Added Appendix B with `cryptsetup luksHeaderBackup` procedure and off-host storage guidance
- **Files modified:** docs/ops/tde-deployment-runbook.md
- **Commit:** b9b5fb0

**2. [Rule 2 - Missing Critical] Added systemd boot ordering for LUKS-before-Docker**
- **Found during:** Task 1
- **Issue:** Troubleshooting section needed guidance on ensuring LUKS volume opens before Docker starts on systemd-based systems
- **Fix:** Added systemd override configuration (`After=systemd-cryptsetup@pg_encrypted.service`)
- **Files modified:** docs/ops/tde-deployment-runbook.md
- **Commit:** b9b5fb0

## Threat Model Compliance

All three threats from the plan's threat model are addressed:

| Threat ID | Disposition | Mitigation in Runbook |
|-----------|-------------|----------------------|
| T-10-02-01 | mitigate | Key file permissions `0400` (Section 4, step 2); Option B documents remote key server alternative |
| T-10-02-02 | mitigate | GPG AES256 symmetric encryption (Section 6); CMK in Vault, not on backup media |
| T-10-02-03 | accept | Troubleshooting section covers unlock failures; manual unlock documented as fallback (Section 4, Option B) |

## Known Stubs

None -- the runbook is a complete operational document with no placeholder sections.

## Self-Check: PASSED
