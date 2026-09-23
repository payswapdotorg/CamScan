# tools/lab-cli — the lab's operator CLI (CAMSCAN-007)

Turns the validated control plane into **orchestrated runs** and
**aggregate reports**: the SPEC → VERIFY → RECONCILE loop's operator
surface (`lab/orchestration/LOOP.md`). Composes the siblings it never
forks — the lead's validator (imported), `tools/adb-bridge` verbs,
`tools/evidence-cli` bundle writer, `tools/parity-cli` compare/gap
engine, and `lab/providers/scheduler.py` capability matching.

```
python3 tools/lab-cli/main.py <command> …
```

Python 3.12, stdlib core + PyYAML/jsonschema (the control-plane
dependencies the lead's validator already pins). No network, no
credentials in code/tests/reports; the live provider path reads
`E2B_API_KEY` from the environment only.

## Commands

### `validate` — the lead's gates, re-exposed verbatim

```
python3 tools/lab-cli/main.py validate
```

Runs the lead's `tools/lab-cli/validate.py` **by import** (never a
fork): stdout and exit code are byte-identical to the CI entry
`python3 tools/lab-cli/validate.py` — pinned by tests on the green
plane and on a known-bad plane. On top of the lead's gates (which only
the lead ever extends), the subcommand adds one **extension** gate:
the lab-cli composition surface (the sibling entry points `run`/`report`
compose) must exist. The extension is silent when green and runs only
after the lead's gates pass — never a loosening. `validate.py` itself is
untouched; the CI keeps invoking it directly.

Exit: 0 gates green; 1 any failure.

### `run` — orchestrate a scenario (the VERIFY stage)

```
python3 tools/lab-cli/main.py run <scenario-id | --all>
    [--env reference|implementation|both]   # default: both
    [--plan]                                # resolution ONLY
    [--driver live|recording]               # default: live
    [--runs-dir PATH]   [--gaps-dir PATH]   # default <repo>/runs, <repo>/lab/reconciliation
    [--stamp YYYYMMDDTHHMMSSZ]  [--suffix SLUG]  [--apk PATH]  [--repo-root PATH]
```

Stages:

1. **Resolve** — the scenario's typed `meta.requires` go through
   `lab/providers/scheduler.py` against every capability report on
   record (`lab/providers/*/capability-report.json`). Capability
   matching only — never provider identity.
2. **Provision/execute via a driver** — when no capable provider is on
   record for an env, the run NO-OPs for that env with an explicit
   `planned:` line carrying the scheduler's unsatisfied reasons (e.g.
   `camera_fixture: requirement True not satisfied by e2b: False` — the
   honest BLOCKED state for the 9 capture-flow scenarios today). When a
   capable provider exists but no **live driver** is on record for that
   env, the same honest NO-OP (no such env today: the reference env's
   live driver landed with CAMSCAN-009 — see
   [the reference live driver](#the-reference-live-driver-camscan-009)).
3. **Evidence per EVIDENCE.md** — each subject executes into a
   single-subject staging dir, is bundled by `tools/evidence-cli`
   (hashes, sidecars, manifest), then assembled into the paired layout
   `runs/<stamp>-S###-<suffix>/{reference,implementation}/` with
   `scenario.yaml` at the run root.
4. **Reconcile** — once BOTH envs exist for the run:
   `tools/parity-cli` `compare` + `gap` are invoked **via API import**
   (in-process composition, mirroring how parity-cli composes
   evidence-cli — structured results, deterministic bytes, no process
   spawn). The CLI prints the exact parity-cli command for manual
   reproduction. A FAIL/BLOCKED/PARTIAL verdict is a **successful**
   orchestration (exit 0) — parity-cli's own exit-code doctrine.

`--plan` does ONLY the resolution: scheduler matching + `planned:`
lines + the step→verb mapping + timeout budget. It provisions nothing,
executes nothing, writes nothing under `runs/`.

`--driver recording` selects the
[RecordingDriver](#the-recordingdriver) — substrate-free, deterministic;
`--stamp` fixes the run-id timestamp so recording runs are
byte-reproducible.

Exit: 0 orchestrated (including honest planned NO-OPs); 1 operational
failure (unknown scenario, run-dir collision, driver failure, compare
error); 2 usage.

### `report` — the aggregate operator report

```
python3 tools/lab-cli/main.py report
    [--runs-dir PATH] [--gaps-dir PATH] [--ledger PATH]  # defaults under <repo-root>
    [--scenario <kebab-id | S###>]  [--json]  [--repo-root PATH]
```

Aggregates, read-only:

- **runs found** — every `runs/<run-id>/` (EVIDENCE.md run-id shape):
  id, scenario, subject envs present, verdict from
  `reconciliation/verdict.json` when present (`—`/`null` otherwise,
  never guessed); non-run dirs are listed under `ignored`, never
  parsed;
- **gap counts by severity** — the reconciliation gap yamls
  (`lab/reconciliation/*.gap.yaml`, GAP-FORMAT.md — what parity-cli's
  `gap` writes; `--gaps-dir` overrides): counts per severity plus
  open/acted-on split and per-file detail;
- **ledger status snapshot** — `lab/parity-ledger/ledger.json`: status
  histogram + per-entry `{id, scenario, status, verdict}`. Read-only —
  ledger mutations are lead-reviewed (parity-cli `ledger-update`).

`--json` machine output follows parity-cli's deterministic
serialization (`tools.evidence_cli.jsonio`: sorted keys, 2-space
indent, one trailing newline) and carries **no timestamps except
evidence-derived ones** (a run's `verdict.generated_utc` rides inside
the run entry; there is no report-level wall-clock stamp).

Exit: 0 always, unless the runs dir is unreadable (→ 1). Unreadable
verdict/gap/ledger files degrade into per-item problems, never exit
codes.

## The driver contract (what a live provider driver must implement)

Stages (b)+(c) of a run are pluggable **driver functions**
(`tools/lab-cli/drivers.py`):

```python
class RunDriver(Protocol):
    slug: str

    def provision(self, request: ProvisionRequest) -> DriverHandle: ...
    def execute(self, handle: DriverHandle,
                request: ExecutionRequest) -> SubjectRunResult: ...
    def teardown(self, handle: DriverHandle, error: str = "",
                 emit: Callable[[str], None] = print) -> None: ...
```

| stage | owns | must |
|---|---|---|
| `provision` | LabProvider lifecycle: `provision(EnvironmentSpec(purpose=<subject>))` → `start` → `reset(ResetSpec)` (preconditions from the scenario: fresh-install wipe, permission grants, fixture bindings) | record provider/app/device facts on the `DriverHandle`; never be reached without a scheduler-resolved provider record; preflight credentials BEFORE provisioning (the e2b driver refuses without `E2B_API_KEY`) |
| `execute` | step dispatch through adb-bridge verbs (per-step budget = `meta.step_timeout_seconds`; scenario wall-clock = `meta.timeout_seconds` from env-ready to evidence-complete — BLOCKED on timeout, never silently truncated), per-step captures (screenshot + ui dump), logcat | write artifacts under `<stage>/<subject>/{screenshots,ui,logs,outputs}/` (flat category dirs — evidence-cli's walker is strict) and `run-metadata.json` (the EVIDENCE.md manifest minus `artifacts`) at the stage root; record device-side failures as `ok=False`, don't raise |
| `teardown` | stop + destroy | run on EVERY path (the runner enforces it in `finally`); never raise; paid environments are never leaked |

The runner (not the driver) owns: scheduler resolution, run-id/dir
naming, the stage→bundle→assemble shuffle, and compare+gap. Requests
carry everything a driver needs (scenario, provider report, step plans
with the mapping table's verb calls, pinned fixture entries,
timeouts); `ExecutionRequest.clock` provides a real-time ISO callable
for live timestamps (recording drivers use the deterministic
run-id-derived window instead — timestamps are schema-designated
fields, never content-addressed).

**Registering a live driver**: add a factory to
`tools/lab-cli/drivers.py` (`register_live_driver(env, provider_slug,
factory)`) — or land a new provider (report its capabilities under
`lab/providers/<slug>/capability-report.json` and the scheduler picks
it up with zero identity branching). The reference-env driver is this
path's worked example: the proven recipe
`lab/substrate/REFERENCE-INSTALL-2026-09-22.md` became
`tools/lab-cli/reference_live.py` (CAMSCAN-009), registered for both
the `e2b` base record and the `e2b-reference` env-class record — see
[the reference live driver](#the-reference-live-driver-camscan-009)).

### The RecordingDriver

`--driver recording` (in `tools/lab-cli/recording.py`): logs every
would-be call (`would: …` lines), writes deterministic synthetic
evidence (tiny PNG/XML payloads, the demo-pair technique), identical
step traces both sides → verdict PASS. It fabricates no substrate
claims (the manifest carries the resolved provider's capabilities and
an explicitly `-recording-` environment id), runs no network, needs no
credentials — the pytest path. Deliberate-divergence fixtures are
parity-cli's own (`runs/20260923T120000Z-S004-synth01`).

### The reference live driver (CAMSCAN-009)

`run --env reference` (driver kind `live`) resolves
`tools/lab-cli/reference_live.py` — the `ReferenceDriver` — which puts
the **official CamScanner** (com.intsig.camscanner, black-box,
observable behavior only) through the probe-17 recipe
(`lab/substrate/REFERENCE-INSTALL-2026-09-22.md`; the
`tools/reference-observe/observe.py` lessons are its spec):

1. **provision** — the reference env's OWN profile (never shared with
   the implementation env): `google_apis;android-30;x86_64` image
   @ 4096 MB (the image class carrying `libndk_translation` for the
   app's arm64-v8a-only splits — the run-002 lesson), AVD
   `camscan-reference`, TCG boot (provider-owned budget), package
   service settle (`pm list packages` answers; +60 s);
2. **dexopt filter** — `setprop pm.dexopt.install verify` BEFORE any
   install (root → setprop → unroot): the cheap filter eliminates the
   dexopt monitor storm that killed `system_server` in probe 9;
3. **install** — XAPK acquisition in-sandbox from a presigned URL
   (`CAMSCAN_APK_URL` — the proven delivery; the e2b files-API push
   of the 221 MB bundle stalls in an httpx retry loop),
   sha256-verified in-sandbox (the local `--apk`'s hash when present,
   else the recorded archive hash), splits unzipped; or local `--apk`
   extraction + push as the fallback. Then `adb install-multiple -r`
   with sandbox-side paths through the GMS-churn retry ladder
   (background install + `EXIT_n` marker, a 6-min outcome window per
   attempt — never a 20-min hang; package-service re-settle probes
   between failed attempts; 8 bounded attempts; sandbox death → clean
   abort with the sandbox destroyed; `pm path` registry verification
   after Success — probe 15's silent-commit-death lesson);
4. **launch** — dynamic launcher resolution (`cmd package
   resolve-activity` — never statically-derived components), patient
   process wait, and the SystemUI-ANR dump-tap dismissal ladder after
   launch (uiautomator dump → Wait-button bounds → center tap; the
   proven `(540, 1244)` fallback);
5. **execute** — scenario steps through adb-bridge verbs, per-step
   screenshot + ui-dump captures, final logcat, discovered dumpsys
   package facts — into the single-subject staging dir this pipeline
   bundles.

Every retry/wait constant in the recipe is provenance-pinned in the
module (probe-17 / run-003 / run-005 lessons / observe.py lines — no
bare numbers). Like the implementation driver it is **lab-exercised,
never pytest-exercised**: the hermetic tests drive it through an
injected scripted transport (call order, ladder state machine,
registry flip, teardown invariant), never the e2b SDK.

Two deliberate deviations from the implementation driver: no
`provider.reset` (its reinstall path is a single-APK `adb install -r`
— DEAD for split bundles, probe 16 — and its wipe relaunch is
redundant on a fresh sandbox, which is fresh-install state by
construction), and permission baselines use the full
`pm grant <pkg> <perm>` form.

**The reference env's own capability record**
(`lab/providers/e2b-reference/capability-report.json` — honest
values: pixel_4 / Android 11 / TCG `none` / camera2-live-but-
fixture-false) sits where scheduler discovery reads it. See contract
concern 4 for the env-dimension limitation and the pool-order policy
that keeps the base `e2b` record first.

### The lead execution recipe — S001–S004 reference runs

The live execution is the LEAD's (the only E2B_API_KEY holder); the
driver + registry wiring + hermetic proof are this delivery. Verbatim:

```text
E2B_API_KEY=<lead-held> python3 tools/lab-cli/main.py run S001 --env reference \
    --apk <camscanner-7.25.5.xapk>   # or CAMSCAN_APK_URL=<presigned>
E2B_API_KEY=<lead-held> python3 tools/lab-cli/main.py run S002 --env reference \
    --apk <camscanner-7.25.5.xapk>   # or CAMSCAN_APK_URL=<presigned>
E2B_API_KEY=<lead-held> python3 tools/lab-cli/main.py run S003 --env reference \
    --apk <camscanner-7.25.5.xapk>   # or CAMSCAN_APK_URL=<presigned>
E2B_API_KEY=<lead-held> python3 tools/lab-cli/main.py run S004 --env reference \
    --apk <camscanner-7.25.5.xapk>   # honestly NO-OPs: camera_fixture
```

- The XAPK is archived at
  `r2:camscan-parity-evidence/reference/camscanner/CamScanner_7.25.5.2609020000_apkcombo.com.xapk`
  — 221499725 bytes, sha256
  `7ef8e46525a1210bbaa332d5f5d560ce30d768b4375ea92265d2e292d25dba65`
  (recorded in-repo: `docs/reference-observations/session-manifest.json`,
  `docs/reference-observations/static-apk-analysis.md`). For URL
  delivery, presign that object and export `CAMSCAN_APK_URL`; the
  driver sha256-verifies the download in-sandbox before installing.
- S001–S003 resolve (prefix `--plan` to preview the resolution
  lines); S004 honestly NO-OPs with a `planned:` line naming
  `camera_fixture` from both on-record reports.
- TCG time scales: boot ~7–40 min (provider-owned budget), the
  install ladder usually succeeds on attempt 1–2 (probe 17: attempt
  2), the whole run fits the 60-min E2B sandbox window (polls renew
  the lifetime; sandbox death aborts cleanly for a fresh run).
- S002+ steps referencing reference-app controls (`tap: scan` …) fail
  loud (`UnknownTargetError`) until the seed registry's reserved
  CamScanner scope is populated from the live dumps these very runs
  capture — the designed discovery loop (contract concern 3).

## Step → verb mapping table

`tools/lab-cli/steps.py` maps every corpus step action (and the DSL's
base vocabulary) to adb-bridge verb calls; an unmapped action is an
operational error (fail-loud). Semantic targets resolve at runtime
(ui-selector in the latest dump, else registered coordinates); the
`--plan` output marks each target `registered(<scopes>)` or
`design-contract` — the seed registry's own honesty vocabulary for ids
the implementation flows must still materialize (`rotate_button`,
`enhance_button`, `ocr_button`, `export_button`, `share_button`, and
the scenario-referenced `scan`).

## Determinism

Identical inputs ⇒ byte-identical outputs (the 005 rules, pinned by
tests): recording runs with the same `--stamp` produce byte-identical
run trees (manifests included); `--plan` and `report --json` are
byte-stable; all JSON goes through `tools.evidence_cli.jsonio`.
Timestamps only ever derive from the run-id stamp (recording) or real
execution (live) — never the tool's wall clock inside
content-addressed sections.

## Tests

```
python3 -m pytest tools/lab-cli -q
```

Hermetic (tmp trees, no network, no device, no credentials, no
wall-clock stamps): validator parity (green + known-bad + extension
planes, byte-compared against the CI entry), `--plan` on the REAL
corpus (S001 schedulable / S004 NO-OP / --all / id spellings),
RecordingDriver end-to-end producing a paired run dir both envs →
parity-cli compare consumed it (incl. parity-cli's own `--check`
staleness gate), the teardown invariant (a stub driver whose execute
raises still gets torn down), report aggregation on a synthetic runs
tree built from the committed 005 demo pair + real parity-cli gap
yamls, CLI exit codes, and byte-level determinism — plus the
reference live driver's contract suite (CAMSCAN-009,
`test_labcli_reference.py`: scripted-transport call order +
install-ladder state machine + registry flip + scheduler S001–S004 +
teardown invariant; the live path itself stays lab-exercised, never
pytest-exercised) — 74 tests today. Test modules carry
`test_labcli_*` names + a `labcli_helpers` module so combined
multi-tool pytest invocations (`pytest tools/lab-cli tools/parity-cli
tools/adb-bridge`) collect cleanly — note `tools/parity-cli` +
`tools/evidence-cli` still collide with each other on `helpers.py`
today (pre-existing; per-directory runs are the CI convention).

## Contract concerns (filed, not decided here)

1. **Validator crash on missing required keys**: a scenario file
   missing a required key (e.g. `steps:`) makes the lead's
   `check_ledger` raise `TypeError` (`file_stems` contains `None`
   because `docs[-1]["stem"]` is skipped on the early `continue`)
   instead of printing a clean gate message. Lead-owned file — filed
   in the CAMSCAN-007 report, not fixed here.
2. **Reference-env driver — LANDED (CAMSCAN-009)**:
   `tools/lab-cli/reference_live.py` implements the proven recipe;
   `run --env reference` resolves `driver=reference-live` for every
   scenario with a capable provider on record (S001–S003 today). The
   honest NO-OP doctrine is unchanged — S004 still NO-OPs on
   `camera_fixture`, never a fabricated observation.
3. **Design-contract targets**: the mapping table names control ids the
   seed registry does not carry yet (`scan`, `rotate_button`, …). Live
   runs of those steps fail loud (`UnknownTargetError`) — an honest
   BLOCKED, mirroring the registry's own note that ids come from live
   dumps / CAMSCAN-010, never invention.
4. **Env-scoped capability records (CAMSCAN-009)**: the capability
   vocabulary is env-agnostic — a per-env record
   (`lab/providers/e2b-reference/capability-report.json`, the
   reference env's own profile) can only exist as a peer provider
   record in the scheduler's discovery pool. The runner's pool-order
   policy (slug-ascending, `run.py`) keeps the base `e2b` record
   first so an env-class record never silently flips a paired run's
   substrate record — but a first class env dimension (or an explicit
   ranking API in `scheduler.py`) is a lead-owned contract decision,
   filed here, not decided in a worker delivery.
