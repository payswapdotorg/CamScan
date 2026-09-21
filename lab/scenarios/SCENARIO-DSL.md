# Scenario DSL specification (v0)

One file per scenario: `S###.yaml`. The DSL is provider-neutral by construction:
steps/assertions describe **what** is observed and done, never how a provider
performs it.

## Schema

```yaml
id: string              # unique, kebab-case; file stem must be S### matching order
title: string
status: enum            # mirror of ledger status, ledger is truth
  # UNKNOWN | DISCOVERED | SPECIFIED | IMPLEMENTED | PARTIAL | BLOCKED | PASS
preconditions: [string] # e.g. fresh-install, camera-permission-not-granted,
                        # document-in-library(<fixture>), setting(<k>=<v>)
fixture:                # deterministic input binding (lab/fixtures/)
  camera: string | null # documents/* fixture id injected via camera_fixture
  sequence: string | null # sequences/* fixture id
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
  requires: [capability]  # subset of LabProvider capabilities
  owner: reference-discovery | lead | reconciliation
  notes: string
```

## Semantics

- `preconditions` are established by the provider's `reset`/fixture hooks, not by
  ad-hoc manual steps.
- Assertion categories map to the four parity dimensions (behavior/state/ui/output).
- A scenario `PASS` requires **every** assertion in all four categories to hold with
  recorded evidence; anything less is `PARTIAL` with gap reports.
- Scenarios must stay small and bounded: one bounded behavior per file; composite
  flows chain via preconditions.

## Validation

`tools/lab-cli/validate.py check --all` (CI): YAML parses, ids unique, S### stem/id
consistency, `meta.requires` ⊆ known capability set, ledger cross-consistency.

## Lifecycle

Worker 2 discovers behavior → lead specifies/updates the scenario file → both
environments run it → Worker 3 reconciles. Status transitions live in the **ledger**
(the `status` field here is a human-readable mirror only).
