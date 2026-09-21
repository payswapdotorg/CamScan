# Scenario DSL specification (v0.1)

One file per scenario: `S###-<id>.yaml`. The DSL is provider-neutral by
construction: steps/assertions describe **what** is observed and done, never
how a provider performs it.

## Schema

```yaml
id: string              # unique, kebab-case; file stem must be S###-<id>
title: string
status: enum            # mirror of ledger status, ledger is truth
  # UNKNOWN | DISCOVERED | SPECIFIED | IMPLEMENTED | PARTIAL | BLOCKED | PASS
preconditions: [string] # e.g. fresh-install, camera-permission-not-granted,
                        # document-in-library(<fixture>), setting(<k>=<v>)
fixture:                # deterministic input binding (lab/fixtures/)
  camera: string | null     # documents/* fixture id injected via camera_fixture
  sequence: string | null   # sequences/* fixture id
steps: [step]           # ordered
step:
  - launch
  - tap: <target>       # target: semantic control id (resolved per app)
  - swipe: {from: <t>, to: <t>}
  - type: <text>
  - press: back | home
  - grant-permission: camera | storage | …
  - capture
  - wait-for: <condition>
  - assert-visible: <target>
assertions:
  behavior: [string]
  ui: [string]
  state: [string]
  output: [string]
meta:
  timeout_seconds: 1800        # REQUIRED (int >= 60)
  step_timeout_seconds: 120    # REQUIRED (int >= 30)
  requires:                    # REQUIRED typed mapping (v0.1 — not a string list)
    gui: true
    adb: true
    android_emulator: true
    emulator_acceleration:
      allowed: [none]
    camera_fixture: true       # only when the flow opens the camera
  owner: reference-discovery | lead | reconciliation | implementation
  notes: string
```

## Timeout semantics (three explicit layers — never conflated)

1. **`meta.timeout_seconds`** — wall-clock budget for the scenario EXECUTION
   phase: measured from environment-ready to evidence-collection complete.
   Environment provisioning and emulator boot are **not** counted against it
   (they are provider-level budgets, below). Enforced by the scenario runner.
2. **`meta.step_timeout_seconds`** — budget for ONE interactive step (adb
   input, UI wait, single capture). The runner passes it to the provider's
   `interact`/`execute` as the per-command timeout.
3. **Provider budgets** — substrate-calibrated, owned by the provider config
   (e.g. e2b TCG: boot 2400 s vs measured 410 s, install 600 s, capture
   300 s). Never buried in shell scripts; declared in the provider config and
   reported in health reports.

A scenario that cannot finish within `timeout_seconds` is `BLOCKED
(timeout)`, never silently truncated.

## Typed capability requirements (v0.1)

`meta.requires` is a mapping of capability → requirement, validated against
the same vocabulary as provider capability reports
(`lab/providers/capabilities.schema.json`):

- boolean capabilities (`gui`, `adb`, `camera_fixture`, …) take booleans;
- enum capabilities take a scalar or a typed form:

```yaml
emulator_acceleration:
  allowed: [none]      # values acceptable for this scenario
```

Semantics:
- `allowed: [none]` — schedulable on TCG providers today. When a future
  KVM/HVF provider is provisioned, the lead **deliberately** widens the list
  per scenario (timing-sensitive scenarios may pin `allowed: [none]`).
- Runs record the acceleration they actually executed under
  (`last_run.emulator_acceleration` in the ledger) so the ledger can
  distinguish `PASS (tcg)` from `PASS (kvm)`.
- **Run-pair consistency**: the reference run and the implementation run of
  one scenario must execute on providers whose capability reports agree on
  every capability the scenario names (`scheduler.pair_compatible`) —
  otherwise timing/rendering-sensitive assertions compare across substrate
  classes.

Scheduling is capability matching only (`lab/providers/scheduler.py`):

```
scenario meta.requires → capability matching → eligible provider → execution
```

The parity engine never branches on provider identity.

## Semantics

- `preconditions` are established by the provider's `reset`/fixture hooks,
  not by ad-hoc manual steps.
- Assertion categories map to the four parity dimensions (behavior/state/ui/output).
- A scenario `PASS` requires **every** assertion in all four categories to
  hold with recorded evidence; anything less is `PARTIAL` with gap reports.
- Scenarios must stay small and bounded: one bounded behavior per file;
  composite flows chain via preconditions.

## Validation

`tools/lab-cli/validate.py` (CI): YAML parses; ids unique; `S###-<id>`
filename mapping; typed `meta.requires` (known keys, valid forms);
timeout fields present and within floors; ledger cross-consistency
(schema + status/title mirrors + evidence refs); fixture references resolve
against `lab/fixtures/manifest.json` when it exists; capability reports
schema-valid.

## Lifecycle

Worker 2 discovers behavior → lead specifies/updates the scenario file → both
environments run it → Worker 3 reconciles. Status transitions live in the
**ledger** (the `status` field here is a human-readable mirror only).
