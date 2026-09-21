# Work orders

Status truth for work orders lives here; scenario status truth lives in
`lab/parity-ledger/ledger.json`. One session = one bounded task packet (rebuilt into
`packets/` at dispatch time — gitignored).

## Prerequisites (resolved — no operator-gated blockers remain)

| ID | Requirement | Status |
|---|---|---|
| P0 | chat.z.ai login through the replay console (worker dispatch) | **CLEARED 2026-09-21 10:07 UTC** (operator logged in via replay image) |
| P1 | Android execution substrate via `LabProvider` | **RESOLVED 2026-09-21: E2B-only (no GCP, no external provider)** — substrate = E2B `desktop` template + QEMU TCG software emulation, gated empirically (`lab/substrate/VALIDATION-2026-09-21-TCG.md`); implemented in `lab/providers/e2b/` (CAMSCAN-008) |

## Real dependency graph (2026-09-21 handoff §20)

```
CAMSCAN-001 ──→ CAMSCAN-002 ──→ CAMSCAN-008 ──→ CAMSCAN-009 ──→ CAMSCAN-010 ──→ CAMSCAN-011
(app skeleton)  (adb bridge)    (e2b provider)  (reference env)  (impl runs)     (reconcile)

CAMSCAN-003 (fixtures, W2)  ── feeds S004+ capture scenarios + evidence hashes
CAMSCAN-004 (reference discovery, W2) ── feeds CAMSCAN-009
```

- CAMSCAN-008 was **lead-implemented ahead of the strict chain** per the
  2026-09-21 handoff §26 (the lab host station holds the E2B credential and
  the proven substrate probe); its acceptance gate
  (`lab/providers/e2b/acceptance.py`) is the entry condition for CAMSCAN-009
  and CAMSCAN-001's install/launch verification.
- CAMSCAN-003 / CAMSCAN-004 are independently parallelizable while the
  provider acceptance completes; they feed the appropriate downstream workers.
- No P0/P1 gates remain operator-blocked.

## Queue

| WO | Worker | Scope | Deps | Status |
|---|---|---|---|---|
| CAMSCAN-001 | W1 | Android app skeleton in `app/`: Gradle project, min SDK, CI-runnable `assembleDebug` + unit tests + lint; APK must install+launch on the implementation AVD (verified via the e2b provider) | P0, CAMSCAN-008 (acceptance) | QUEUED (dispatch after 008 acceptance) |
| CAMSCAN-002 | W1 | `tools/adb-bridge`: provider-neutral adb command wrapper + interaction/capture verbs per `lab/providers/LABPROVIDER.md` + semantic target registry | P0, CAMSCAN-001 | QUEUED |
| CAMSCAN-003 | W2 | `lab/fixtures/` corpus: 7 document + 3 sequence fixtures, `manifest.json` (schema: `fixture.schema.json`), per-fixture id/file/format/dimensions/ground-truth/sha256, deterministic `generate.py` | P0 | **READY — dispatching** (independent of 008) |
| CAMSCAN-004 | W2 | Reference capability discovery: install official CamScanner in the isolated reference environment (separate AVD from the implementation env, equivalent device profile); record version/package/Android version/device/locale/timezone/permissions/account requirements/workflow/UI/state changes/outputs/errors; capability-map skeleton | P0, CAMSCAN-008 (acceptance), CAMSCAN-003 (fixtures for observation) | QUEUED |
| CAMSCAN-005 | W3 | `tools/parity-cli`: manifest-driven comparison of reference vs implementation evidence bundles → `diff.json` + `verdict.json` (PASS/PARTIAL/FAIL/BLOCKED/NOT_OBSERVED) | P0, CAMSCAN-003, CAMSCAN-006 | QUEUED |
| CAMSCAN-006 | W3 | `tools/evidence-cli`: bundle/manifest builder + R2 upload (creds via env), hash sidecars, upload verification; no credentials in manifests | P0 | QUEUED (independent of 008 — dispatchable when capacity admits) |
| CAMSCAN-007 | W1 | `tools/lab-cli` full implementation (validate/run/report) extending the lead's validator | P0, CAMSCAN-002 | QUEUED |
| CAMSCAN-008 | **lead** | Provider `e2b` full implementation per `lab/providers/LABPROVIDER.md` — DONE in code (`lab/providers/e2b/`): baked TCG recipe, all 13 contract operations, typed capability report, provider scheduler (`lab/providers/scheduler.py`); **acceptance gate** (`lab/providers/e2b/acceptance.py`): full lifecycle on a fresh sandbox with no manual repair | P1 (TCG gate PASS) | **DONE 2026-09-21 — acceptance gate PASS** (run `camscan008-acceptance-20260921T151742Z`: provision→TCG boot 470s→adb→APK build/install/launch→interact→captures→evidence→pull→snapshot probe→report→stop→destroy, zero manual repairs; probed: recording=true, snapshot=true, camera_fixture=false-not-injectable-on-public-template; evidence: `r2:camscan-parity-evidence/providers/e2b/acceptance/20260921T151742Z`, 10 objects sha256-verified) |
| CAMSCAN-009 | W2 | Reference env bring-up + CamScanner install + S001–S004 reference runs (evidence bundles) | CAMSCAN-004, CAMSCAN-008 (acceptance) | QUEUED |
| CAMSCAN-010 | W1 | S001–S004 implementation runs against reference evidence | CAMSCAN-009, CAMSCAN-001 | QUEUED |
| CAMSCAN-011 | W3 | First reconciliation: S001–S004 diff + verdict + gaps → ledger updates | CAMSCAN-009, CAMSCAN-010 | QUEUED |

## Optimization (explicitly OFF the critical path — handoff §24)

| WO | Worker | Scope | Deps | Status |
|---|---|---|---|---|
| CAMSCAN-OPT-001 | lead/W3 | Bake Java 17 + Android SDK + known-good AVD into a fixed custom E2B template (current `flauz-parity` template is broken: buildStatus error) to cut the ~8 min cold bootstrap and improve TCG throughput | **first end-to-end parity loop (S001–S004) complete** | DEFERRED — do not block functional progress on this |

The first END-TO-END loop (reference observation → scenario → implementation →
evidence → reconciliation → gap → implementation → rerun → PASS) must complete on
S001–S004 before the suite scales (handoff §21). A worker's textual claim is not
evidence — the integration station independently re-runs acceptance commands.

## Report format (mandatory, verbatim headline)

```
=== CAMSCAN-<WO-ID> COMPLETION REPORT ===
task: …
environment: …
what was implemented/observed: …
verification: commands + outputs (verbatim)
evidence: artifact list + sha256 + R2 keys (no raw bytes in chat)
assumptions: …
open questions / handoffs: …
base sha: <40-hex>
```
