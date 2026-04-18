---
phase: 06
slug: metrics-instrumentation-wiring
status: secured
threats_open: 0
asvs_level: 1
created: 2026-04-17
---

# Phase 06 — Security

> Per-phase security contract: threat register, accepted risks, and audit trail.

---

## Trust Boundaries

| Boundary | Description | Data Crossing |
|----------|-------------|---------------|
| Prometheus scrape endpoint | Already configured in Phase 5 with `prometheus.access: unrestricted` + network-level firewall (accepted risk T-05-00-01) | Aggregate counter values (no tenant-specific data) |

---

## Threat Register

| Threat ID | Category | Component | Disposition | Mitigation | Status |
|-----------|----------|-----------|-------------|------------|--------|
| T-06-01 | I (Information Disclosure) | MetricsPort counters | accept | Counters expose aggregate counts (ingest rate, rule evals, conflicts) — no tenant-specific data in counter values. Prometheus endpoint already firewalled per T-05-00-01. | closed |
| T-06-02 | D (Denial of Service) | recordShaclValidationNanos hot path | accept | System.nanoTime() is a single native call (~25ns); negligible overhead vs. the Jena SHACL validation it wraps (~1-2ms). No allocation on the hot path. | closed |
| T-06-03 | T (Tampering) | MetricsPort SPI | accept | MetricsPort is an internal SPI with no external surface. Only TesseraMetricsAdapter implements it; Spring Boot wires it as a singleton. No user input reaches the port methods. | closed |

*Status: open · closed*
*Disposition: mitigate (implementation required) · accept (documented risk) · transfer (third-party)*

---

## Accepted Risks Log

| Risk ID | Threat Ref | Rationale | Accepted By | Date |
|---------|------------|-----------|-------------|------|
| AR-06-01 | T-06-01 | Aggregate counters contain no tenant-specific data; Prometheus endpoint firewalled per T-05-00-01 | GSD security auditor | 2026-04-17 |
| AR-06-02 | T-06-02 | System.nanoTime() overhead (~25ns) negligible vs. SHACL validation (~1-2ms); no allocation on hot path | GSD security auditor | 2026-04-17 |
| AR-06-03 | T-06-03 | Internal SPI with no external surface; singleton wiring; no user input reaches port methods | GSD security auditor | 2026-04-17 |

---

## Security Audit Trail

| Audit Date | Threats Total | Closed | Open | Run By |
|------------|---------------|--------|------|--------|
| 2026-04-17 | 3 | 3 | 0 | GSD secure-phase |
