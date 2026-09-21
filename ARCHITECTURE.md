# Architecture

## Control plane

The laboratory is controlled from **chat.z.ai**. The chat.z.ai tech-lead agent is the
orchestrator: it dispatches workers, owns the parity ledger, reviews deliverables,
runs acceptance gates, and merges. **The tech lead does not implement product code.**

```
Chat.z.ai (tech lead / orchestrator)
   │  replay console (agents tab, GLM-5.3, Full-Stack skill)
   ├── Worker 1 — implementation (CamScan app)
   ├── Worker 2 — reference/oracle (CamScanner black-box observation)
   └── Worker 3 — reconciliation (gap reports)
   │
   ├── E2B_API_KEY      → E2B Desktop sandboxes (control/agent environment)
   ├── GITHUB_TOKEN     → payswapdotorg/CamScan (code, PRs, issues, Actions)
   └── R2_*             → Cloudflare R2 bucket `camscan-parity-evidence`
```

Credential distribution is least-privilege — see [SECURITY.md](SECURITY.md).

## Provider neutrality (hard requirement)

All interactive execution goes through the **`LabProvider`** abstraction. The parity
engine never cares which provider executed a scenario; providers declare capabilities
and the orchestrator schedules scenarios only onto providers whose capabilities satisfy
the scenario requirements.

```text
LabProvider
├── capabilities      # declarative: gui, adb, camera_fixture, recording, snapshot…
├── provision         # create environment instance
├── start / stop      # lifecycle
├── reset             # deterministic clean state (fresh-install preconditions)
├── snapshot/restore  # state banking for fast iteration
├── execute           # run a command
├── interact          # tap / swipe / type / back / home
├── capture           # screenshot / screen recording / UI hierarchy dump / logcat
├── collectEvidence   # pull the evidence bundle for a run
├── transfer          # push fixtures / pull outputs
├── destroy
└── report            # structured capability + health report
```

Provider capability contract (reported, never assumed):

```yaml
capabilities:
  gui: true
  persistent: true
  android_emulator: true
  adb: true
  camera_fixture: true
  screenshots: true
  recording: true
  snapshot: true
```

### Validated substrate truth (2026-09-21)

| Substrate | Verdict | Evidence |
|---|---|---|
| E2B Desktop (`base`) | **No nested KVM** — accelerated emulation impossible; 2 vCPU/478 MB too small anyway | `lab/substrate/VALIDATION-2026-09-21.md` |
| E2B Desktop (`desktop`, public) | 8 vCPU / ~8 GB RAM / 25 GB disk. **TCG software-emulation gate: see TCG addendum** — this is the lab substrate per the operator directive | `lab/substrate/VALIDATION-2026-09-21-TCG.md` |
| GCP | **RETIRED by operator directive (2026-09-21): no GCP.** (Historically also blocked: the supplied key was API-key-only, which cannot provision compute VMs.) | operator directive; original report §3 |
| Local lead sandbox | No `/dev/kvm`, 4.1 GB RAM — control plane only, not a candidate | original report §4 |
| GitHub Actions runners | No KVM on hosted runners — CI is non-interactive gates only | CI design note in `LAB.md` |

**Consequence (operator directive 2026-09-21):** the lab is **E2B-only** — no GCP, no
external provider credential. Interactive scenarios execute on QEMU TCG software
emulation inside the E2B `desktop` template, gated by the empirical TCG probe
(`lab/substrate/VALIDATION-2026-09-21-TCG.md`). The `LabProvider` abstraction stays
provider-neutral so a future Flauz provider (accelerated) can slot in without
redesign. A BLOCKED substrate is never counted as PASS.

## Workers

- **Worker 1 — implementation.** Owns `app/` + implementation AVD + build tooling.
  Implements against executable scenarios; never declares done from its own tests
  alone (the reference oracle is authoritative). Receives `ZAI_API_KEY` through the
  sandbox secret mechanism (never in Git).
- **Worker 2 — reference/oracle.** Owns the reference Android environment with the
  official CamScanner app installed as a **black box**. Discovers capabilities
  systematically, builds the capability map, records reference evidence, curates
  deterministic fixtures. Never mutates Worker 1's environment, and vice versa.
- **Worker 3 — reconciliation.** Compares reference vs implementation evidence.
  Produces precise gap reports (`lab/reconciliation/*.gap.yaml`); never casually
  rewrites Worker 1's code. Gaps loop back to Worker 1 with required changes +
  verification scenarios.

## Evidence flow

```
run (scenario S### on provider P)
  → evidence bundle: screenshots / recordings / ui dumps / logs / action trace /
    versions / device config / fixture ids / output artifacts + SHA-256 hashes
  → large artifacts → R2 (bucket: camscan-parity-evidence)
  → specs, manifests, hashes, findings, verdicts → Git (runs/<run-id>/ manifests)
```

Every artifact is content-addressed (or at minimum SHA-256-hashed). See `lab/evidence/EVIDENCE.md`.
