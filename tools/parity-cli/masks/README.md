# Mask files — documented reference-app-specific step ignore-list

A mask removes **documented** app-specific `action_trace` steps from
parity alignment, so a CamScanner-only UX step (e.g. a premium upsell
dialog) does not fail an otherwise-par CamScan run. Masks exist to keep
comparisons honest: the alternative is silently ignoring step counts,
which the comparator refuses to do.

## File format (JSON — this is `tools/parity-cli`'s own design; JSON
keeps the CLI stdlib-only)

One file per scenario, named `<scenario-id>.json`:

```json
{
  "scenario": "single-document-capture",
  "description": "CamScanner-only steps masked from parity alignment",
  "steps": [
    {
      "action": "tap",
      "target": "premium-upsell-dismiss",
      "index": 4,
      "side": "reference",
      "reason": "CamScanner 7.x shows a premium upsell after save; CamScan has no premium tier (reference-only UX, documented by Worker 2)."
    }
  ]
}
```

| field    | required | meaning |
|---|---|---|
| `scenario` | yes | kebab scenario id the mask applies to; files for other scenarios are ignored |
| `steps[].action` | yes | trace action to match (e.g. `tap`, `wait-for`) |
| `steps[].target` | no | narrow to steps whose `target` equals this |
| `steps[].index` | no | narrow to one original 0-based step position |
| `steps[].side` | no | which side's trace the rule removes from alignment: `reference` (default, the documented use), `implementation`, or `both` |
| `steps[].reason` | yes | the justification — a mask without a recorded reason is a silent divergence and aborts the comparison (fail-closed) |

## Semantics

- Matching steps are removed **before** positional alignment; step-count
  parity is judged after masking.
- A step is removed at most once (first matching rule wins).
- Masked steps' per-step artifacts are no longer required on their side.
- `diff.json` records the applied rules, their reasons, and the removed
  step indices (`masks` section) — masking is always visible in the
  evidence, never silent.
- A rule that matches nothing is considered-but-inert (apps change
  versions); it is *not* an error, but it is visible via
  `rules_considered` in `diff.json`.
- Loading is fail-closed: unparseable JSON, a missing `action`, or a
  missing `reason` aborts `compare`/`gap`.

## Rules of use

- Masks are for **app-specific steps that have no CamScan counterpart by
  design** (upsells, sign-in walls, ads). They are never a way to hide
  an implementation failure: a divergence in a *shared* step still
  produces a diff entry and a gap.
- Every mask must name its evidence trail in `reason` (observation doc,
  work order, or lead decision).
- `--no-masks` compares unmasked (use it to audit what the masks are
  hiding); `--masks-dir` points at an alternative directory (tests).
