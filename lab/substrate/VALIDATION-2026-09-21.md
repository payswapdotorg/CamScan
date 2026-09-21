# E2B Android substrate validation — 2026-09-21

**Purpose:** hard capability gate (mission §4) — determine whether an Android
emulator can run with acceptable acceleration inside an E2B Desktop sandbox,
before installing the full toolchain.

## Method

Live probe of an E2B sandbox created via the E2B API (credential from the lead's
private environment; never committed). Probe script: lead-staging
`e2b_kvm_probe.py` (kept outside the repo — it imports credentials from env).

## Results

| Probe | Result |
|---|---|
| Sandbox id | `i4fyr7a6ezpchodr1my21` (template `base`, Debian 12) |
| `/dev/kvm` | **absent** (`ls: cannot access '/dev/kvm'`) |
| CPU virtualization flags (`vmx`/`svm`) | **none** in `/proc/cpuinfo` |
| `lsmod \| grep kvm` | no kvm module |
| CPU count | 2 |
| Memory | 478 MB (cgroup view) |
| Disk | 22 GB total / 20 GB free |
| qemu/kvm binaries | none preinstalled |
| Account templates | only a custom `flauz-parity` template exists, **buildStatus: error** (0 CPU / 0 MB, spawnCount 0) |

## Verdict

**ACCELERATED_EMULATOR_IMPOSSIBLE inside E2B.**

E2B guests run on Firecracker microVMs which do not expose nested virtualization:
there is no `/dev/kvm` and no CPU virtualization flags, on any template. An Android
emulator would fall back to QEMU TCG software emulation, which is unacceptably slow
for an interactive parity lab (boot times and per-action latency would dominate every
scenario run).

Per the mission fallback this means:

1. **Documented** (this file).
2. **E2B is retained as the agent/control environment** — sandbox creation, command
   execution, file staging and the control plane all work well there.
3. **A provider capable of accelerated Android emulation must be introduced** and
   connected through the same `LabProvider` abstraction.
4. Provider-neutral architecture enforced from day one (see `../providers/`).

## §3 — GCP as candidate Android provider: credential limitation

The operator-provided GCP credential is an **API key** (`AIza……`). Validated live:

- `serviceusage.googleapis.com` → `403 PERMISSION_DENIED`
- compute provisioning endpoints require **OAuth2 / service-account** auth; an API
  key cannot create or manage Compute Engine VMs.

**Consequence:** the GCP provider slot is OPEN and BLOCKED on credential type. To
unblock it, the operator must supply a GCP **service account JSON** (or OAuth
credential) with Compute Engine permissions; a nested-virtualization VM
(`--enable-nested-virtualization`, N2/N2D class or better) then becomes the
accelerated Android substrate. Alternative acceptable: any dedicated host with
KVM (bare-metal server, on-prem Linux box).

## §4 — Local lead sandbox

No `/dev/kvm`, no `vmx`/`svm` flags, 4.1 GB RAM, ~7 GB free disk → not a candidate
for the emulator substrate; it remains the control/orchestration host (replay stack).

## Ledger impact

All interactive scenarios (S001+) remain `UNKNOWN` → the accelerator dependency is
recorded as a top-level `BLOCKED` entry in the parity ledger. BLOCKED is never
counted as PASS.

## Raw probe log (sanitized)

```
dev_kvm          ls: cannot access '/dev/kvm': No such file or directory
cpuinfo_flags    (empty — no vmx/svm lines)
cpu_count        2
mem              478 MB
disk             22G total, 20G free
os               PRETTY_NAME="Debian GNU/Linux 12 (bookworm)"
kvm_module       no_kvm_module
```
