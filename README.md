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
| `lab/providers/` | Lead + workers | Provider-neutral `LabProvider` abstraction (E2B, GCP/KVM, local…) |
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

- **Bootstrap phase.** No product code exists yet. Parity ledger: all scenarios `UNKNOWN`.
- **E2B substrate gate: FAILED for accelerated Android emulation** — E2B guests have no
  nested `/dev/kvm` (validated live, see `lab/substrate/VALIDATION-2026-09-21.md`).
  E2B is retained as the **agent/control environment**; an accelerated-Android
  `LabProvider` must be connected before any interactive scenario can run.
- Worker dispatch runs through the chat.z.ai replay console (agents tab, model
  **GLM-5.3**, skill **Full-Stack**). See `AGENTS.md`.

## Completion criterion

The project is parity-complete only when the accepted scenario suite passes across
behavior, state, UI contract and outputs, with no unresolved critical/high-severity
differences except explicitly documented external blockers. Final reports distinguish
`PASS / PARTIAL / FAIL / BLOCKED / NOT OBSERVED`. An untested feature is never a pass.
