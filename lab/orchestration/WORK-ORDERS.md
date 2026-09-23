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
| CAMSCAN-001 | W1 | Android app skeleton in `app/`: Gradle project, min SDK, CI-runnable `assembleDebug` + unit tests + lint; APK must install+launch on the implementation AVD (verified via the e2b provider) | P0, CAMSCAN-008 (acceptance) | **DONE 2026-09-22 — skeleton built + verified** (Worker 1 delivered inline (sandboxless session); trio gates re-run on E2B by lead: ASSEMBLE/TEST/LINT OK, app-debug.apk 5.9MB; APK install+launch on the implementation AVD pending with CAMSCAN-010 runs) |
| CAMSCAN-002 | W1 | `tools/adb-bridge`: provider-neutral adb command wrapper + interaction/capture verbs per `lab/providers/LABPROVIDER.md` + semantic target registry | P0, CAMSCAN-001 | **DONE 2026-09-22 — merged** (Worker 1 landed in the evening window after the longest capacity fight: 1500+ create rounds, 16+ wedged prompt-chats; chat b2d7ab77, DOM-verified completion; tarball transit 44,920,933 B sha256 c450c370…, byte-perfect; integration gates re-run: pytest 81 passed, validator green, import ok; branch work/CAMSCAN-002-integration @ b629808; worker's own commit 093e38f on work/CAMSCAN-002 in its pod) |
| CAMSCAN-003 | W2 | `lab/fixtures/` corpus: 7 document + 3 sequence fixtures, `manifest.json` (schema: `fixture.schema.json`), per-fixture id/file/format/dimensions/ground-truth/sha256, deterministic `generate.py` | P0 | **DONE 2026-09-21 (merge a87ef55)** — Worker 2 delivered via files-API tarball transit (sha256-verified byte-perfect); gates re-run at integration: generate.py --check PASSED (69 files byte-identical), validator green with 10 fixture ids known; branch work/CAMSCAN-003 @ 235749b |
| CAMSCAN-004 | W2 | Reference capability discovery: install official CamScanner in the isolated reference environment (separate AVD from the implementation env, equivalent device profile); record version/package/Android version/device/locale/timezone/permissions/account requirements/workflow/UI/state changes/outputs/errors; capability-map skeleton | P0, CAMSCAN-008 (acceptance), CAMSCAN-003 (fixtures for observation) | **DONE 2026-09-22 (static) — LIVE OBSERVATION UNBLOCKED 2026-09-22T11:40Z**: ABI probe 17 PROVED install+launch of genuine CamScanner 7.25.5 on the TCG substrate (`adb install-multiple -r` host-path streaming + `setprop pm.dexopt.install verify` + dynamic launcher resolution + SystemUI-ANR dismissal ladder; crash buffer EMPTY; see `lab/substrate/REFERENCE-INSTALL-2026-09-22.md`); static capability map + XAPK/APK already archived (r2:camscan-parity-evidence/reference/camscanner/); live S001–S004 observation now proceeds under CAMSCAN-009 |
| CAMSCAN-005 | W3 | `tools/parity-cli`: manifest-driven comparison of reference vs implementation evidence bundles → `diff.json` + `verdict.json` (PASS/PARTIAL/FAIL/BLOCKED/NOT_OBSERVED) | P0, CAMSCAN-003, CAMSCAN-006 | **DONE 2026-09-23 (merge 70628c8)** — Worker 3 on fresh base 6af60a8 (commit 23db253): compare/gap/ledger-update + masks/ (fail-closed ignore-list) + 146 hermetic tests + synthetic demo pair (PARTIAL by design, verdict determinism byte-identical across 3 independent builds); git-bundle transit (45,288,748 B sha256 4d8b02f8… + files.tgz 58,285 B sha256 635e7583…, both byte-verified at harvest); integration gates re-run: 146+108+81 tests, validate OK, ruff clean (lead reconciliation commit dd99c49: lint fixes, +x CLI entries, 006 branch-time alias assertion evolved), leak-scan 0; contract concerns filed in README (ledger evidence-ref vocabulary, manifest-at-run-root vs per-subject, scenario status mirror write) |
| CAMSCAN-006 | W3 | `tools/evidence-cli`: bundle/manifest builder + R2 upload (creds via env), hash sidecars, upload verification; no credentials in manifests | P0 | **DONE 2026-09-23 (merge 90992d8)** — Worker 3 rebuilt after the sandbox resets ate the original f17e7073 (never pushed, token lead-held); git-bundle transit (45,100,090 B sha256 def73f69…, byte-verified); integration gates re-run: 108+1d tests, adb-bridge 81, validate OK, ruff clean, leak-scan 0; open questions filed (both-subject run dirs, scenario.yaml R2 upload) |
| CAMSCAN-007 | W1 | `tools/lab-cli` full implementation (validate/run/report) extending the lead's validator | P0, CAMSCAN-002 | **DONE 2026-09-23 (merge a67c5e9)** — Worker 4 on base da49707 (commit 3c1125e, clean capacity window: dispatched 19:25 → complete 20:23): validate/run/report + pluggable drivers (RecordingDriver deterministic, E2bLiveDriver with E2B_API_KEY preflight, documented reference-env NO-OP) + scheduler→driver→EVIDENCE.md bundle→parity-cli compare+gap wiring, 19 files/3511 lines + 55 hermetic tests; git-bundle transit (45,347,747 B sha256 d9a64bb2… + files.tgz 45,458 B sha256 1547e2eb…, both byte-verified at harvest); integration gates re-run: 55+108+146+81 tests, validate OK exit 0, ruff net-new clean (lead reconciliation 45620d1: 45 auto-fixes, S110 noqa on intentional guards, +x main.py), leak-scan 0; concerns filed in lab-cli README (honest target registry status, planned-run NO-OP lines) |
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
