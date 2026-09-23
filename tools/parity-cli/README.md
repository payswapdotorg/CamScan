# parity-cli — compare · gap · ledger-update (CAMSCAN-005)

Worker 3's reconciliation engine: turns **two evidence bundles** (exactly
what [`tools/evidence-cli`](../evidence-cli/) produces, per
[`lab/evidence/EVIDENCE.md`](../../lab/evidence/EVIDENCE.md)) into a
structured diff, a verdict, gap reports and ledger updates.

`python3 tools/parity-cli/main.py <command>` — **stdlib only** (jinja2
not needed; the manifest vocabulary is *imported* from
`tools.evidence_cli.schema`, never forked).

```
compare <run-id>       runs/<run-id>/{reference,implementation}/manifest.json
                       → runs/<run-id>/reconciliation/{diff.json,verdict.json}
gap <run-id>           open divergences (severity ≥ medium)
                       → lab/reconciliation/<stem>.<feature>.gap.yaml
                       (--from-diff reuses the existing diff.json)
ledger-update <run-id> verdict → lab/parity-ledger/ledger.json
                       (+ scenario status mirror), schema-valid
```

Common flags: `--repo-root` (default: the repo containing this tool),
`--runs-dir`, `--masks-dir`, `--no-masks`; `compare --check` gates
staleness of the on-disk reconciliation outputs without writing;
`ledger-update --dry-run` computes without writing.

Exit codes: `0` ok — **a FAIL/BLOCKED verdict is a successful
comparison, not a tool error**; `1` operational failure; `2` usage.

## Determinism

Identical evidence ⇒ byte-identical outputs (pinned by tests):

- entries sorted by `(dimension, id)`; every set iterated through
  `sorted()`;
- JSON via `tools.evidence_cli.jsonio` (sorted keys, 2-space indent,
  one trailing newline, atomic write);
- `generated_utc` is **evidence-derived** — max of both manifests'
  `finished_at` (fallback: the run-id timestamp, then epoch) — never
  the tool's wall clock (same policy as evidence-cli's bundle);
- gap yamls are emitted by a hand-rolled deterministic YAML writer
  (double-quoted scalars via `json.dumps`; JSON string syntax is valid
  YAML 1.2 double-quoted style) so the CLI stays stdlib-only;
- the ledger is re-serialized in its **document key order** (minimal
  reviewable diffs; ASCII-escaped scalars, so a handful of non-ASCII
  lines may re-escape — semantically identical, gates stay green).

## Run-dir contract (consumed)

Per the EVIDENCE.md run-dir layout decision (lead, 2026-09-23): no
run-root manifest; each subject subtree is a complete single-subject
bundle —

```
runs/<run-id>/
  scenario.yaml                    # verbatim copy (required by gap)
  reference/manifest.json          # subject: "reference"
  implementation/manifest.json     # subject: "implementation"
  reconciliation/                  # written by compare (this tool)
```

`compare` reads **manifests only** — bulk bytes live in R2; the
manifest (hashes, sizes, r2 keys) is the durable git-side truth, so
comparison works from a fresh clone. A run dir assembled today:
bundle each subject with `tools/evidence-cli bundle` in staging dirs
named `<run-id>` (the single-subject model per invocation), then move
each subject subtree + its `manifest.json` under the paired run root
(see `tests/make_demo_pair.py`).

## Comparison dimensions → diff.json

Every divergence is one entry: `{id, dimension, severity, difference,
path, reference, implementation, feature, evidence{reference,
implementation}}` (null = no evidence recorded on that side). Entries
are counted per severity and summarized per dimension.

| dim | what is compared | severity on divergence |
|---|---|---|
| `bundle` | subject bundle usable? (missing / schema-invalid / no observation) | critical (routes to BLOCKED/NOT_OBSERVED, not FAIL) |
| a `environment` | device model / android version / screen / locale / timezone / permission baseline (EVIDENCE.md run-pair requirements) | **high**; provider capability disagreement medium (SCENARIO-DSL run-pair consistency); provider slug low |
| b `fixtures` | same fixture ids + sha256 both sides | **critical** (different input bytes invalidate the pair) |
| c `artifacts` | per-step screenshots (numeric prefix ↔ trace step) and ui dumps for every **surviving** trace step; category counts | screenshot missing **high**; ui dump missing medium; counts low |
| d `actions` | masked, positionally-aligned action_trace: step count, action per position, outcome class per step | step-count/action **high**; implementation failing where reference succeeded **high**; permission flip (ok→denied) **critical**; implementation launch failure **critical**; broken *reference* observation medium (not the implementation's fault) |
| e `outputs` | outputs/ by type + count + sha256 | type missing on implementation medium; count/sha **low** — equality is NOT required (recorded, never enforced) |

Outcome classes: `action_trace[].result` literals map to
ok/denied/failed (two runners' equivalent literals — `saved` vs `ok` —
compare equal); unknown literals compare by value and are **never
silently assumed ok**. Target *strings* are not compared (SCENARIO-DSL:
semantic control ids resolved per app); the target-reached **outcome**
is. The engine never trusts app self-reports — the manifest's
runner-captured trace, artifacts and outputs are the evidence.

## Verdicts (deterministic, from diff.json)

Routing precedence (each rule pinned by tests):

1. **BLOCKED** — implementation bundle missing/incomplete for external
   reasons. Recorded, **never counted as PASS** (wins even over FAIL).
2. **NOT_OBSERVED** — reference bundle lacks the capability observation
   (missing / invalid / empty or all-failed trace). **Never silently
   converted into PASS.**
3. **FAIL** — any critical or high divergence.
4. **PARTIAL** — medium divergences only.
5. **PASS** — no critical/high/medium divergence. Low-only divergence
   (e.g. output-byte sha, which the work order explicitly does not
   require equal) is recorded but does not block PASS — otherwise the
   loop's terminal state (`PASS` → acceptance) would be unreachable
   for two different apps.

`verdict.json` = `{run_id, scenario, verdict, summary, counts,
generated_utc}` (counts = critical/high/medium/low).

## Masks (`masks/`)

Documented reference-app-specific steps (e.g. CamScanner's premium
upsell) are removed from alignment before comparison — JSON files,
fail-closed loading, reasons mandatory. See
[`masks/README.md`](masks/README.md).

## Gaps (`gap`)

One yaml per feature (severity ≥ medium) per
[`lab/reconciliation/GAP-FORMAT.md`](../../lab/reconciliation/GAP-FORMAT.md):
id `<stem>-<feature>-1`, both sides' behavior + evidence paths,
difference, severity, required_change (per-dimension templates),
verification (the scenario's own assertions), `status: open`. Existing
gaps with a non-open status are never clobbered. `--from-diff` reuses
`reconciliation/diff.json`.

## Ledger (`ledger-update`)

Reads `reconciliation/verdict.json` + `diff.json` (run `compare`
first) and updates the S### entry whose scenario matches the
manifests':

- `status` ← verdict via the explicit mapping (the ledger status
  vocabulary has no FAIL — the precise verdict always lives in
  `last_run.verdict`):

  | verdict | PASS | PARTIAL | FAIL | BLOCKED | NOT_OBSERVED |
  |---|---|---|---|---|---|
  | status | PASS | PARTIAL | IMPLEMENTED | BLOCKED | UNKNOWN |

- `last_run` ← `{utc (generated_utc), side: "both", provider,
  emulator_acceleration, run_id, verdict}` (mixed-substrate pairs
  record the conservative `none` and are flagged in the environment
  dimension);
- `evidence` += schema-valid refs only: the scenario spec
  (`lab/scenarios/…`), R2 manifest keys for sides whose bundle records
  `r2_key` entries, gap reports naming this run — deduplicated;
- `notes` gains one idempotent `parity:` line; ids/titles are
  **verified** (never rewritten);
- because `tools/lab-cli/validate.py` (CI) enforces ledger↔scenario
  status-mirror equality, the `status:` line of
  `lab/scenarios/S###-<id>.yaml` is updated too — a surgical
  single-line edit that preserves comments; the ledger remains the
  truth, the yaml the documented human-readable mirror;
- structural invariants (required keys, status vocabulary, evidence
  ref patterns, last_run shape — a stdlib mirror of
  `ledger.schema.json`) are checked **before** anything is written.

## Tests

```
python3 -m pytest tools/parity-cli -q
```

Hermetic (tmp run trees; no network, no R2, no device, no wall-clock
stamps): every verdict path (PASS/PARTIAL/FAIL/BLOCKED/NOT_OBSERVED +
precedence), every dimension, mask fail-closed rules, gap
emission/clobbering, ledger mapping + mirror + idempotence, CLI exit
codes, evidence-cli interop (a bundle built by the real `bundle_run`
is compared), and the determinism test — two runs produce
byte-identical `diff.json` (and `verdict.json`).

## The committed synthetic demo pair

`runs/20260923T120000Z-S004-synth01/` is **fixture data** (see its
`SYNTHETIC-RUN.md`): produced by `tools/evidence-cli bundle` from tiny
synthetic artifacts, assembled into the paired layout, carrying one
deliberate medium divergence + two low output divergences and
exercising the shipped upsell mask → verdict PARTIAL. Regenerate:

```
python3 tools/parity-cli/tests/make_demo_pair.py
python3 tools/parity-cli/main.py compare 20260923T120000Z-S004-synth01
```

## Contract concerns (filed, not decided here)

1. **Ledger evidence-ref vocabulary** cannot express
   `runs/<run-id>/reconciliation/{diff,verdict}.json` (git-side durable
   truth per EVIDENCE.md) — the ref patterns allow only
   `lab|docs|app|tools/`, `r2:…`, `https://…`. `ledger-update` records
   only schema-valid refs (scenario doc, R2 manifest keys, gap
   reports) and carries the run linkage in `last_run.run_id`.
   Recommend the lead widens the pattern (or blesses an
   `lab/reconciliation/` mirror).
2. **evidence-cli bundle + paired layout**: `bundle` writes
   `manifest.json` at the run-dir root (single-subject model) while
   EVIDENCE.md's paired layout wants it at the subject-subtree root.
   Assembly today = bundle in staging + move (see above /
   `tests/make_demo_pair.py`); a `--subject-dir` mode on evidence-cli
   would remove the shuffle (lead decision).
3. **Status-mirror writes**: `ledger-update` necessarily touches the
   scenario yaml's mirror line (CI enforces equality). Lab *contracts*
   (EVIDENCE.md, GAP-FORMAT.md, schemas, DSL spec) are untouched by
   this tool.
