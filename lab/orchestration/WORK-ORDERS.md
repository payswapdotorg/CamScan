# Work orders

Status truth for work orders lives here; scenario status truth lives in
`lab/parity-ledger/ledger.json`. One session = one bounded task packet (rebuilt into
`packets/` at dispatch time — gitignored).

## Prerequisites (operator-gated)

| ID | Requirement | Status |
|---|---|---|
| P0 | chat.z.ai login through the replay console (worker dispatch) | **PENDING — operator action** |
| P1 | accelerated-Android provider connected via `LabProvider` (GCP service-account JSON with compute scope, or a KVM-capable host) | **PENDING — operator decision** (see `lab/substrate/VALIDATION-2026-09-21.md` §3) |

Non-interactive work orders (CI-runnable, no provider needed) can start immediately
once P0 clears; interactive orders wait for P1.

## Queue

| WO | Worker | Scope | Deps | Status |
|---|---|---|---|---|
| CAMSCAN-001 | W1 | Android app skeleton in `app/`: Gradle project, min SDK, CI-runnable `assembleDebug` + unit tests + lint; no feature code | P0 | READY |
| CAMSCAN-002 | W1 | `tools/adb-bridge`: provider-neutral adb command wrapper + interaction/capture verbs per `lab/providers/LABPROVIDER.md` | P0, CAMSCAN-001 | QUEUED |
| CAMSCAN-003 | W2 | `lab/tools` → fixture corpus generation: the 7 document + 3 sequence fixtures with manifests + ground-truth annotations (synthetic content only) | P0 | READY |
| CAMSCAN-004 | W2 | Reference observation protocol doc + capability-map skeleton (`docs/capability-map.md`) from mission §6 list, statuses NOT OBSERVED | P0 | READY |
| CAMSCAN-005 | W3 | `tools/parity-cli`: run-matrix comparison of two evidence bundles (manifest-driven, hash-verified) + `diff.json`/`verdict.json` emitters | P0, CAMSCAN-003 | QUEUED |
| CAMSCAN-006 | W3 | `tools/evidence-cli`: bundle/manifest builder + R2 upload (creds via env), hash sidecars | P0, CAMSCAN-003 | QUEUED |
| CAMSCAN-007 | W1 | `tools/lab-cli` full implementation (validate/run/report) extending the lead's bootstrap validator | P0, CAMSCAN-002 | QUEUED |
| CAMSCAN-008 | Lead→W1 | Provider `gcp-nested-kvm` implementation once P1 credential lands | P1 | BLOCKED |
| CAMSCAN-009 | W2 | Reference env bring-up + CamScanner install + S001–S003 reference runs (evidence bundles) | P1, CAMSCAN-004 | BLOCKED |
| CAMSCAN-010 | W1 | S001–S003 implementation against reference evidence | CAMSCAN-009 | BLOCKED |
| CAMSCAN-011 | W3 | First reconciliation: S001–S003 diff + verdict + gaps | CAMSCAN-009, CAMSCAN-010 | BLOCKED |

The first END-TO-END loop (reference observation → scenario → implementation →
evidence → reconciliation → gap → implementation → rerun → PASS) must complete on
S001–S004 before the suite scales (mission §20 step 10).

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
