# CamScan — Feature-Parity Laboratory

CamScan is an independently implemented Android document-scanning application whose
**observable behavior** is systematically reconciled against the official CamScanner
Android application inside a repeatable, evidence-producing parity laboratory.

> The end goal is **not** a visually similar application.
> The goal is: CamScan reaches parity with the observable functionality of CamScanner
> across the accepted scenario suite — behavior, state transitions, UI behavior and
> generated outputs — subject to explicitly documented platform/account/provider
> limitations. **Parity is never claimed from screenshots alone.**

## The parity loop

```
CamScanner reference → observe behavior → record executable scenario
  → run identical scenario against CamScan → collect evidence
  → reconcile differences → generate implementation task → GLM-5.3 implements
  → rerun scenario → accept / iterate
```

## Repository layout

| Path | Owner | Contents |
|---|---|---|
| `app/` | Worker 1 | The CamScan Android application implementation |
| `lab/providers/` | Lead + workers | Provider-neutral `LabProvider` abstraction (E2B TCG first; future Flauz providers) |
| `lab/emulator/` | Workers 1 & 2 | Android emulator/AVD management per environment |
| `lab/scenarios/` | Lead (spec), Worker 2 (discovery) | Scenario DSL + executable scenario suite (`S###.yaml`) |
| `lab/fixtures/` | Worker 2 | Deterministic camera/document fixture corpus |
| `lab/evidence/` | Workers 1–3 | Evidence bundle model, manifests, hashes |
| `lab/reconciliation/` | Worker 3 | Gap reports (`gap.yaml`) and verdicts |
| `lab/parity-ledger/` | Lead | Persistent parity ledger (status truth) |
| `lab/orchestration/` | Lead | The loop, work orders, dispatch packets |
| `lab/substrate/` | Lead | Substrate capability validation reports (E2B KVM gate etc.) |
| `tools/` | Workers | `adb-bridge`, `lab-cli`, `evidence-cli`, `parity-cli` |
| `docs/` | all | Working documents |
| `.github/workflows/` | Lead | CI (non-interactive gates only) |

Read next: [ARCHITECTURE.md](ARCHITECTURE.md) · [LAB.md](LAB.md) · [AGENTS.md](AGENTS.md) · [SECURITY.md](SECURITY.md)

## Current state (honest, 2026-09-21)

```
Parity Lab Infrastructure: IN PROGRESS
CamScan Product: BOOTSTRAP
CamScanner Oracle: NOT YET ESTABLISHED
Parity Acceptance: 0 scenarios accepted
```

- **LabProvider `e2b`: implemented** (`lab/providers/e2b/` — baked TCG recipe,
  all 13 contract operations, typed capability report, provider scheduler;
  CAMSCAN-008). Acceptance gate: `python3 lab/providers/e2b/acceptance.py`.
  Parity ledger: all scenarios `UNKNOWN` — the first end-to-end loop
  (S001–S004) is the next milestone.
- **E2B substrate gate: FAILED for accelerated Android emulation; TCG gate: PASS.** E2B guests
  have no nested `/dev/kvm` (validated live, see `lab/substrate/VALIDATION-2026-09-21.md`).
  **Operator directive (2026-09-21): no GCP, no external provider — E2B-only per the handoff.**
  The lab therefore runs on QEMU TCG software emulation inside the E2B `desktop`
  template (8 vCPU / ~8 GB RAM), gated empirically — see
  `lab/substrate/VALIDATION-2026-09-21-TCG.md`.
  The `LabProvider` seam stays provider-neutral for future Flauz providers.
- Worker dispatch runs through the chat.z.ai replay console (agents tab, model
  **GLM-5.3**, skill **Full-Stack**). See `AGENTS.md`.

## Completion criterion

The project is parity-complete only when the accepted scenario suite passes across
behavior, state, UI contract and outputs, with no unresolved critical/high-severity
differences except explicitly documented external blockers. Final reports distinguish
`PASS / PARTIAL / FAIL / BLOCKED / NOT OBSERVED`. An untested feature is never a pass.
