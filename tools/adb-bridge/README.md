# tools/adb-bridge — provider-neutral device-driving layer (CAMSCAN-002)

`AdbBridge` is the thin, typed facade the scenario runner sits on. It exposes
semantic device verbs and maps **every one onto LabProvider calls only** — it
never spawns adb (or any process) itself, so the same bridge drives the e2b
TCG substrate today and any future provider (Flauz KVM, ...) tomorrow.

Contract: `lab/providers/LABPROVIDER.md` (the 13 verbs + typed specs).
Typed specs come from `lab/providers/types.py` (`Interaction`, `CaptureKind`,
`CommandResult`, `Artifact`).

```python
from lab.providers.e2b.provider import E2BProvider
from tools.adb_bridge.bridge import AdbBridge

provider = E2BProvider()                       # any LabProvider
bridge = AdbBridge(provider, env_id, adb="/opt/android-sdk/platform-tools/adb")

bridge.launch("org.payswap.camscan", "MainActivity")        # am start -W
bridge.wait_for(bridge.activity_resumed("MainActivity"), timeout=120)
bridge.tap_semantic("shutter_button")                      # semantic target
bridge.wait_for(bridge.text_visible("Scan"), timeout=180)  # bounded poll
shot = bridge.screenshot()                                 # .data = PNG bytes
```

## Import path (dashed deliverable, underscored module)

The work-order directory is dashed; Python identifiers are not.
`tools/__init__.py` registers `tools/adb-bridge` as the synthetic package
`tools.adb_bridge`, so both binding conventions hold verbatim:

```
python3 -c "from tools.adb_bridge.bridge import AdbBridge; print('import ok')"
python3 -m pytest tools/adb-bridge -q
```

There is deliberately **no `__init__.py` inside `tools/adb-bridge`** — pytest
imports collected `__init__.py`-bearing directories as package nodes, and a
dash-named package is not importable.

## Verbs (19)

Every verb returns a `VerbResult(verb, ok, detail, duration_ms, data, text,
error)` — `screenshot` bytes ride in `.data`, ui-dump/logcat text in
`.text`; verbs never raise on *device-side* failure (they return `ok=False`
with the reason), only on authoring errors. Every verb appends a semantic
event to the provider's action trace when the provider exposes one
(duck-typed `trace`/`_trace`; absence is tolerated).

| verb | provider calls | notes |
|---|---|---|
| `launch(app, activity=None)` | `execute` | `am start -W -n app/activity` (parses `Status`/`LaunchState`/`TotalTime`); component-less launch via `monkey` LAUNCHER intent — success requires the `Events injected` confirmation, not just exit 0. Sets the app scope for target lookup. |
| `force_stop(app)` | `execute` | `am force-stop` |
| `grant/revoke(app, permission)` | `execute` | `pm grant/revoke`; denials are structured failures carrying pm's stderr |
| `tap(x, y)` / `tap_semantic(id)` | `interact` | typed `Interaction(kind="tap")`; the semantic form resolves via the registry (below) and labels the trace with the target id |
| `swipe(x1,y1,x2,y2,ms)` | `interact` | `Interaction(kind="swipe")` |
| `type_text(text)` | `interact` | spaces encoded as `%s` (adb `input text` cannot carry raw spaces); original preserved in `detail` |
| `key(code)` / `back()` / `home()` / `wake()` | `interact` | keycode names normalized to `KEYCODE_*` |
| `wait_idle(timeout)` | `execute` (probes) | boot completed + window focus identical across 2 consecutive probes |
| `screenshot()` | `capture` + `transfer(pull)` | PNG bytes in `.data`; provider-side sha256 cross-checked against bridge-side |
| `ui_dump()` | `capture` + `transfer(pull)` | XML in `.text`; refreshes the resolution cache |
| `logcat(filter=None, tail=None)` | `capture` + `transfer(pull)` | deterministic bridge-side post-processing: last N lines, substring keep-filter |
| `install(apk_path)` | `transfer(push)` + `execute` | local APK → env staging (`/tmp/camscan-bridge/`) → `adb install -r -t`; pass `timeout=600` for full app builds under TCG |
| `push(local, remote)` / `pull(remote, local)` | `transfer` + `execute` | **device-path** semantics: env staging hop via `transfer`, device hop via `adb push/pull` |
| `wait_for(predicate, timeout, poll)` | predicate-driven | the only sleep in the bridge — deadline-bounded poll interval |

## Determinism (rule, not convention)

* **No sleep without a poll**: the only `time.sleep` lives inside
  `wait_for`, which always re-checks the deadline first and sleeps
  `min(poll, remaining)`.
* **Every wait is bounded**: default verb timeout **180 s** (TCG scale — an
  adb shell round-trip can take 30-60 s under software emulation),
  overridable per bridge (`default_timeout_s=`) and per verb (`timeout=`).
  Timeout layering is never conflated: provider bootstrap/boot budgets stay
  provider-owned (see `LABPROVIDER.md`).
* Monkey-launch success is never inferred from exit code alone (monkey
  exits 0 even with no activities found).

## Waiting + ready-made predicates

`wait_for(predicate, timeout, poll)` evaluates the predicate *before* the
first sleep (an already-true condition costs exactly one probe); predicate
exceptions count as "not yet satisfied" and surface in
`detail.last_error`. Ready-made predicate factories:

* `activity_resumed(name)` — top/resumed activity matches (substring:
  short name, `.MainActivity`, or full component).
* `text_visible(text)` — fresh ui-hierarchy dump contains the text (raw or
  entity-decoded); each check refreshes the dump cache.
* `package_foreground(pkg)` — current window focus belongs to the package.
* `device_idle(stable_polls=2)` — boot completed + stable focus (backs
  `wait_idle`).

## Semantic target registry (`targets.yaml` — data, not code)

Scenario files reference semantic ids only. Resolution order per id:

1. **ui-selector match in the latest ui dump** (center of the matched
   node's bounds) — the dump comes from the cache, auto-fetched when
   absent, refreshed when older than `dump_max_age_s` (if set) or when
   `tap_semantic(..., refresh=True)`;
2. **else the registered coordinates** (deterministic fallback, recorded
   in `detail.source="coordinates"` with a `fallback_reason`).

Scoping: lookups check the app scope (`apps.<package>.targets`) first, then
the global scope (`targets`) — system-UI targets (permission dialogs) are
global; app controls live under their package. The scope is set by
`launch()`/`set_app()`. Selector grammar: conjunction of `key="value"`
terms — `text`, `text_contains`, `resource-id` (alias `id`),
`content-desc` (alias `desc`), `class` (alias `class-name`). Selectors are
validated at **load** time: a typo fails before any device interaction.
An unknown id raises `UnknownTargetError` (authoring error — loud); an
unmatched selector-only target is a structured `ok=False` (runtime state).

The seed registry carries honest data: coordinates are pinned to the
pixel_4 1080x2280 profile and are the *fallback*; implementation-app ids
are the design contract CAMSCAN-010 must materialize; the reference-app
section stays **reserved** until live dumps exist.

## Provider neutrality

* Interaction verbs → `provider.interact(Interaction)` (typed spec — the
  provider owns the adb incantation, exactly like `Interaction.to_cmd`).
* Captures → `provider.capture(CaptureKind)` + `provider.transfer(pull)`.
* Device-shell verbs → `provider.execute("adb …")` with the adb prefix as
  *configuration* (ctor `adb=`, provider attributes `adb_command`/`adb_path`,
  default `adb`) — the bridge never runs adb itself.
* `interact`/`capture` may omit the `timeout` keyword (pure contract
  signatures) — the bridge probes signatures once at construction.
* No credentials, no network at runtime; stdlib + PyYAML only.

## Tests

`python3 -m pytest tools/adb-bridge -q` — a `FakeProvider` (in-memory
Interaction/capture/execute/transfer recording, programmable device state,
budget-based timeout simulation — never wall-clocked) covers every verb,
target resolution order, and timeout paths. No network, no adb, ~2 s.
