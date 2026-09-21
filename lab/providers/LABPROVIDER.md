# LabProvider interface contract (v0)

Every execution substrate implements this interface. The parity engine consumes
**capabilities only** — it never branches on provider identity.

```python
class LabProvider(Protocol):
    slug: str                     # "e2b", "gcp-nested-kvm", "local-kvm", …

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

## Capability set (v0)

```yaml
capabilities:
  gui: bool               # headed display available (X server / streaming)
  persistent: bool        # environment survives across runs
  android_emulator: bool  # can run an Android emulator at all
  emulator_acceleration: none | kvm | hvf   # acceptable for the lab: kvm
  adb: bool               # Android Debug Bridge access
  camera_fixture: bool    # deterministic virtual-camera injection
  screenshots: bool
  recording: bool
  snapshot: bool
  android_studio: bool    # IDE/toolchain present (implementation env)
```

Scenario `meta.requires` must be a subset of the provider's reported capabilities
for a run to be schedulable. **Operator directive (2026-09-21): E2B-only** — no GCP,
no external provider. The lab therefore accepts `emulator_acceleration: none` (TCG)
for v1 with **generous, explicit per-scenario timeouts**; scenarios record the
acceleration they ran under, and `emulator_acceleration: kvm` remains a declared
capability slot for future Flauz providers so the ledger can distinguish
`PASS (tcg)` from `PASS (kvm)` when a faster provider arrives.

## Provider registry (status)

| slug | status | notes |
|---|---|---|
| `e2b` | **control plane + TCG substrate** | 2026-09-21: `base` template has no nested KVM (accelerated impossible); the public `desktop` template (8 vCPU/~8 GB) runs the emulator in QEMU TCG software mode — gated empirically, see `../substrate/VALIDATION-2026-09-21-TCG.md`. Reports `emulator_acceleration: none`. |
| `gcp-nested-kvm` | **RETIRED — operator directive (2026-09-21): no GCP** | Do not implement. Kept here only as historical record: the supplied credential was API-key-only and could not provision compute anyway. |
| `local-kvm` | **not available** | lead sandbox has no `/dev/kvm`. |
| future Flauz providers | open slot | when the operator provisions a KVM-capable host, implement against this same contract and report `emulator_acceleration: kvm`. |

Provider implementations live one-per-directory here (`e2b/`, …) and are
worker deliverables under normal work orders (the lead owns only this contract).
