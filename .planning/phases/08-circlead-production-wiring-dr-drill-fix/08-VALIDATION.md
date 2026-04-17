---
phase: 08
slug: circlead-production-wiring-dr-drill-fix
status: draft
nyquist_compliant: true
wave_0_complete: false
created: 2026-04-17
---

# Phase 08 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 + Testcontainers + WireMock |
| **Config file** | fabric-app/pom.xml (surefire/failsafe) |
| **Quick run command** | `mvn test -pl fabric-app -Dtest=CircleadPlaceholderResolutionTest -Dspotless.check.skip=true -Dlicense.skip=true` |
| **Full suite command** | `mvn test -pl fabric-app -Dspotless.check.skip=true -Dlicense.skip=true` |
| **Estimated runtime** | ~30 seconds |

---

## Sampling Rate

- **After every task commit:** Run quick run command
- **After every plan wave:** Run full suite command
- **Before `/gsd-verify-work`:** Full suite must be green
- **Max feedback latency:** 30 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| 08-01-01 | 01 | 1 | CIRC-01, CIRC-02 | — | N/A | unit+IT | `mvn test -pl fabric-app -Dtest=CircleadPlaceholderResolutionTest,CircleadSchedulerWiringIT` | ❌ W0 | ⬜ pending |
| 08-01-02 | 01 | 1 | OPS-05 | — | N/A | script | `bash scripts/dr_drill.sh` | ✅ (needs fix) | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

*Existing infrastructure covers all phase requirements.*

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| DR drill runs end-to-end | OPS-05 | Requires Docker + AGE container | Run `bash scripts/dr_drill.sh` with Docker running |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 30s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
