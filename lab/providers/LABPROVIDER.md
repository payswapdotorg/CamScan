# LabProvider interface contract (v0.1)

Every execution substrate implements this interface. The parity engine consumes
**capabilities only** — it never branches on provider identity. Provider
selection is capability matching via `scheduler.py`:

```
scenario meta.requires → capability matching → eligible provider → execution
```

```python
class LabProvider(Protocol):
    slug: str                     # "e2b", "local-kvm", future Flauz providers

    # declarative capability report (validated, never assumed)
    def capabilities(self) -> CapabilityReport: ...

    # lifecycle
    def provision(self, spec: EnvironmentSpec) -> EnvironmentId: ...
    def start(self, env: EnvironmentId) -> None: ...
    def stop(self, env: EnvironmentId) -> None: ...
    def reset(self, env: EnvironmentId, state: ResetSpec) -> None: ...
        # ResetSpec encodes preconditions: fresh-install, permissions, library
        # fixtures, settings — established deterministically, never by hand.
    def snapshot(self, env: EnvironmentId, name: str) -> SnapshotId: ...
    def restore(self, env: EnvironmentId, snap: SnapshotId) -> None: ...
    def destroy(self, env: EnvironmentId) -> None: ...

    # operation
    def execute(self, env: EnvironmentId, cmd: str, timeout: int) -> CommandResult: ...
    def interact(self, env: EnvironmentId, action: Interaction) -> None: ...
        # Interaction: tap/swipe/type/press(back|home)/key — semantic targets
        # resolved per app via the target registry.
    def capture(self, env: EnvironmentId, kind: CaptureKind) -> Artifact: ...
        # CaptureKind: screenshot | recording | ui_hierarchy | logcat

    # evidence & transfer
    def collect_evidence(self, env: EnvironmentId, run_id: str) -> EvidenceBundle: ...
    def transfer(self, env: EnvironmentId, direction: Literal["push","pull"],
                 local: Path, remote: Path) -> None: ...

    def report(self, env: EnvironmentId) -> HealthReport: ...
```

## Capability set (v0.1 — typed, machine-verifiable)

Capability reports are typed mappings validated against
`capabilities.schema.json` (JSON Schema, CI-enforced). Scenario
`meta.requires` uses the same vocabulary with typed requirement forms
(boolean; enum scalar; `{allowed: [...]}`); matching lives in `scheduler.py`
(`provider_matches` / `select_provider` / `pair_compatible`).

```yaml
capabilities:                # provider capability report
  gui: true                  # screen content observable (screenshot + UI hierarchy)
  persistent: false          # environment survives across runs
  android_emulator: true
  emulator_acceleration: none   # enum: none | kvm | hvf — substrate TRUTH
  adb: true
  camera_fixture: false      # deterministic virtual-camera injection
  screenshots: true
  recording: true
  snapshot: true
  android_studio: false      # IDE/toolchain present (implementation env)
```

Scenario `meta.requires` must be satisfiable by the provider's reported
capabilities for a run to be schedulable. **Operator directive (2026-09-21):
E2B-only** — the lab runs v1 on `emulator_acceleration: none` (TCG) with
**explicit per-scenario timeouts** (`meta.timeout_seconds` /
`meta.step_timeout_seconds`, see `../scenarios/SCENARIO-DSL.md`); runs record
the acceleration they executed under, and `kvm` remains a declared capability
slot for future Flauz providers so the ledger can distinguish `PASS (tcg)`
from `PASS (kvm)`.

Timeout layering (never conflated): provider-level budgets (bootstrap/boot/
renewal — substrate-calibrated, e.g. TCG boot 2400 s vs measured 410 s) are
separate from scenario execution budgets (`meta.timeout_seconds`, enforced
from env-ready to evidence-complete) and per-step budgets
(`meta.step_timeout_seconds`, passed to `interact`/`execute`).

## Provider registry (status)

| slug | status | notes |
|---|---|---|
| `e2b` | **IMPLEMENTED (CAMSCAN-008, lead)** | `e2b/` — baked TCG recipe (Java 17 + truststore hard gate + known-good SDK + AVD + `-accel off`), all 13 contract operations, acceptance gate in `e2b/acceptance.py`. Capability report: `e2b/capability-report.json` (CI-validated; `emulator_acceleration: none`). |
| `gcp-nested-kvm` | **RETIRED — operator directive (2026-09-21): no GCP** | Do not implement. Historical record only. |
| `local-kvm` | **not available** | lead sandbox has no `/dev/kvm`. |
| future Flauz providers | open slot | when the operator provisions a KVM-capable host, implement against this same contract and report `emulator_acceleration: kvm`. |

Provider implementations live one-per-directory here. The e2b provider was
lead-implemented (2026-09-21 handoff §26 item 1: the lab host station holds
the E2B credential, the proven substrate probe, and the acceptance gate);
normal worker deliverables under future work orders follow the same contract.
