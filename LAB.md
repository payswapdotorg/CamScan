# The Parity Lab

How the laboratory runs: scenarios, fixtures, evidence, reconciliation, local and CI
execution.

## Scenario DSL

All parity tests are executable and machine-readable. One file per scenario:
`lab/scenarios/S###.yaml`.

```yaml
id: scan-single-document
title: Single-document capture end-to-end
status: UNKNOWN          # ledger status mirrored here for readability (ledger is truth)

preconditions:
  - fresh-install
  - camera-permission-not-granted

fixture:
  camera: clean-a4

steps:
  - launch
  - tap: scan
  - grant-camera-permission
  - capture
  - accept-document
  - save

assertions:
  behavior:
    - document-detected
    - crop-stage-visible
    - save-succeeds
  ui:
    - scan-control-visible
    - preview-visible
  state:
    - document-added-to-library
  output:
    - pdf-created
    - pdf-readable
    - one-page-document

meta:
  requires: [gui, adb, camera_fixture]   # provider capabilities needed
  owner: reference-discovery             # who specified it
```

The DSL evolves but remains **provider-neutral**: steps and assertions describe
*what* is observed, never *how* a provider performs them. Scenario validation runs
in CI (schema + cross-consistency with the ledger).

## Fixture corpus (`lab/fixtures/`)

Deterministic inputs — no humans holding documents at cameras for the core
regression suite:

```
lab/fixtures/
  documents/   clean-a4 · skewed-document · receipt · business-card ·
               handwritten · low-light · multi-page
  sequences/   document-pan · camera-motion · rotation
```

Fixtures are content-addressed and applied identically to CamScanner and CamScan
via the provider's `camera_fixture` capability. The set expands as Worker 2
discovers additional behaviors.

## Evidence bundles

Every scenario execution produces `runs/<run-id>/`:

```
runs/<run-id>/
  scenario.yaml            # verbatim copy used
  manifest.json            # every artifact + sha256 + provider + versions
  reference/               # when run against CamScanner
    screenshots/ recordings/ ui/ logs/ outputs/
  implementation/          # when run against CamScan
    screenshots/ recordings/ ui/ logs/ outputs/
  reconciliation/
    diff.json verdict.json
```

Large artifacts → R2 (`camscan-parity-evidence`); durable specs/manifests/hashes/
verdicts → Git. Full model: `lab/evidence/EVIDENCE.md`.

## Parity dimensions

Reconciliation compares at least:

- **Behavior** — actions, navigation, gestures, permissions, errors, success/failure
- **State** — screens, document existence, saved state, settings, history, nav position
- **UI** — visible controls, labels, enabled/disabled, key layout relations, dialogs,
  menus (no pixel-perfect requirement unless explicitly useful)
- **Output** — PDF, JPG, OCR text, metadata, other artifacts (semantic/document-level
  comparison where possible, not just screenshots)

## Reconciliation loop

```
DISCOVER → REFERENCE → SPECIFY → IMPLEMENT → VERIFY → RECONCILE
     → pass? ACCEPT : (gap task → Worker 1 → VERIFY)
```

Gaps are structured (`lab/reconciliation/GAP-FORMAT.md`): scenario, feature, reference
behavior, implementation behavior, evidence links, difference, severity,
required change, verification scenario. Worker 3 files them; Worker 1 implements;
Worker 3 reruns until PASS.

## Local execution

Interactive scenarios need a provider whose capabilities satisfy `meta.requires`
(see `lab/providers/`). Non-interactive gates (scenario schema validation, ledger
consistency, manifest hash verification, report generation) run anywhere:

```bash
# bootstrap gates (lead-owned, minimal by design; workers extend via tools/)
python3 tools/lab-cli/validate.py check --all
```

## CI execution (GitHub Actions)

CI covers only what does not require the interactive lab:

- build / unit tests / lint / static analysis / APK packaging (once `app/` exists)
- scenario DSL schema validation
- parity manifest validation
- ledger/ledger↔scenario cross-consistency
- evidence manifest hash verification (public-artifact integrity)
- regression checks on the DSL/ledger/tooling
- report generation

Interactive Android execution is **delegated to a capable provider** — never forced
into GitHub Actions (hosted runners have no KVM).
