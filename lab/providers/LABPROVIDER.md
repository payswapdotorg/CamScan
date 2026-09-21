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
for a run to be schedulable. **Accelerated emulator scenarios additionally require
`emulator_acceleration: kvm`** (software-only TCG is not acceptable for the lab).

## Provider registry (status)

| slug | status | notes |
|---|---|---|
| `e2b` | **control-plane only** | validated 2026-09-21: no nested KVM → `android_emulator` accelerated = false. Used for agent/control work, staging, non-interactive steps. |
| `gcp-nested-kvm` | **BLOCKED (credential)** | API-key credential cannot provision compute; awaiting service-account JSON with compute scope. Target shape: N2-class VM, `--enable-nested-virtualization`, KVM inside, Android emulator + AVDs. |
| `local-kvm` | **not available** | lead sandbox has no `/dev/kvm`. |

Provider implementations live one-per-directory here (`e2b/`, `gcp/`, …) and are
worker deliverables under normal work orders (the lead owns only this contract).
