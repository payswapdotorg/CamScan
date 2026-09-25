"""CAMSCAN-010F — the dump-first onboarding discovery contract tests
(hermetic).

The S002 blocker #1 root cause, verified by the lead (2026-09-25,
campaign run 20260925T180736Z-S002-live attempt 1, sandbox
e2b-c785b352): the run walked the IDENTICAL successful S001 chain
(boot 640.9 s, install attempt-2 Success, facts stash landed, GMS
restored, launcher resolved, launch ladder won on attempt 4, ANR
ladder ran, budget governor fired at age 3123 s) — and died at the
FIRST onboarding interaction with

    reference: FAILED — UnknownTargetError: "unknown semantic target
    'next_button' (looked in app scope 'com.intsig.camscanner' and
    global; known ids there: permission_allow, permission_allow_this_
    time, permission_deny)"

because steps.py mapped complete-onboarding to a registry tap on an
id that exists ONLY in the org.payswap.camscan scope (the design
contract), while the com.intsig.camscanner scope is EMPTY BY DESIGN
("populated only from observed live ui dumps").

Pinned here (no network, no e2b SDK, no credentials — the
FakeClock + ScriptedProvider doubles from test_labcli_reference, the
REAL AdbBridge, and the real CLI in subprocesses):

- STEPS MAPPING: the S002 scenario's complete-onboarding step plans
  the NEW composite verb ``onboarding_complete`` (NOT
  tap_semantic next_button); the document-flow affirmatives
  (accept-document/accept/confirm/confirm-delete/done) KEEP the
  next_button tap; the CLI ``--plan`` renders the discovery verb;
- DISCOVERY LOOP full path on scripted dumps: round-1 permission
  dialog ("While using the app") → allow tapped at the bounds-center;
  round-2 "Next" → tapped; round-3 clean main screen → onboarding
  complete; the emitted per-round lines carry the coordinates;
- SKIP PATH: the only actionable control "Skip" → tapped → completes;
- TRAP EXCLUSION: the only clickable "Upgrade to Premium" → NOT
  tapped → the loop exhausts its rounds → the honest False;
- CAPTURE-FAILURE TOLERANCE: a hardened capture that keeps raising
  counts rounds, settles, continues — and fails honestly at
  exhaustion, never crashing;
- PATTERN-SET SEMANTICS: permission exact-match precedes affirmative
  discovery; excluded traps are skipped in mixed dumps; matching is
  case-insensitive over text= AND content-desc=; document order wins;
- END-TO-END dispatch: the reference driver's execute() runs S002
  (launch → complete-onboarding) through the discovery ladder — step
  evidence pair + run-metadata land, the run is ok;
- E2B-LIVE dispatch: the registry tap FIRST (the design contract —
  the default registry's next_button coordinates), and on a registry
  miss (UnknownTargetError) the SAME dump-first discovery loop runs
  and completes;
- RECORDING DRIVER: a plan containing onboarding_complete renders
  without error — recording.py needed NO change (its verb rendering
  is generic), proven by the full paired recording run reconciling
  to PASS.
"""
from __future__ import annotations

from pathlib import Path

import pytest
from labcli_helpers import REPO_ROOT, run_cli
from test_labcli_reference import (
    RUN_ID,
    FakeClock,
    ScriptedProvider,
    _make_driver,
    _make_xapk,
    _provision_request,
)
from tools.adb_bridge.bridge import AdbBridge
from tools.evidence_cli.jsonio import load as jsonio_load
from tools.lab_cli.drivers import ExecutionRequest
from tools.lab_cli.e2b_live import E2bLiveDriver
from tools.lab_cli.reference_live import (
    ONB_MAX_ROUNDS,
    ONB_ROUND_SETTLE_S,
    _Native,
    _onb_clickable_nodes,
    _onb_round_action,
)
from tools.lab_cli.scenarios import resolve_scenario
from tools.lab_cli.steps import plan_steps

from lab.providers.types import CaptureKind

STAMP = "20260925T140000Z"

#: The 20260925T180736Z-S002-live UnknownTargetError, verbatim (the
#: first onboarding interaction — after the identical S001 chain had
#: succeeded end-to-end).
S002_UNKNOWN_TARGET_ERROR = (
    "unknown semantic target 'next_button' (looked in app scope "
    "'com.intsig.camscanner' and global; known ids there: "
    "permission_allow, permission_allow_this_time, permission_deny)")

#: The verbatim 48-byte S001 evidence garbage (the capture-flap
#: artifact — the hardened capture's failure class).
S001_EVIDENCE_GARBAGE = "cat: /sdcard/window_dump.xml: Permission denied\n"


def _node(text: str = "", desc: str = "", clickable: bool = True,
          bounds: str = "[0,0][1080,2280]") -> str:
    return (f'<node text="{text}" content-desc="{desc}" '
            f'clickable="{"true" if clickable else "false"}" '
            f'bounds="{bounds}"/>')


def _dump(*nodes: str) -> str:
    return ('<?xml version="1.0" encoding="UTF-8" standalone="yes" ?>'
            f'<hierarchy rotation="0">{"".join(nodes)}</hierarchy>')


#: API-30 runtime permission dialog (the registered global selectors'
#: texts): the affirmative first-class, the deny button present too.
PERMISSION_DUMP = _dump(
    _node("Allow CamScanner to take pictures and record video?",
          clickable=False, bounds="[84,1140][996,1206]"),
    _node("While using the app", clickable=True,
          bounds="[420,1404][660,1504]"),          # center (540,1454)
    _node("Don't allow", clickable=True,
          bounds="[420,1530][660,1630]"),
)

#: Onboarding page with the affirmative next control.
NEXT_DUMP = _dump(
    _node("Welcome to CamScanner", clickable=False,
          bounds="[84,300][996,400]"),
    _node("Next", clickable=True,
          bounds="[880,2150][1080,2270]"),         # center (980,2210)
)

#: The main screen after onboarding: clickable controls, but NO
#: onboarding affirmative and NO excluded trap — completion.
CLEAN_DUMP = _dump(
    _node("CamScanner", clickable=False, bounds="[84,120][500,200]"),
    _node(desc="Camera", clickable=True,
          bounds="[470,2060][610,2200]"),         # center (540,2130)
)

#: Onboarding page whose only actionable control is the skip link.
SKIP_DUMP = _dump(
    _node("3 steps to better scans", clickable=False,
          bounds="[84,200][996,300]"),
    _node("Skip", clickable=True,
          bounds="[840,90][1040,190]"),           # center (940,140)
)

#: The monetization trap: the ONLY clickable control, never tapped.
TRAP_DUMP = _dump(
    _node("Upgrade to Premium", clickable=True,
          bounds="[100,2000][980,2140]"),         # center (540,2070)
)


def _bridge(provider: ScriptedProvider, env_id: str = "e2b-fake01",
            targets: str | Path | None = None) -> AdbBridge:
    return AdbBridge(provider, env_id, default_timeout_s=120.0,
                     targets=targets)


# ---------------------------------------------------------- steps mapping

def test_s002_complete_onboarding_plans_discovery_verb():
    """The S002 scenario's complete-onboarding step plans the composite
    onboarding_complete verb — NOT the pre-010F tap_semantic
    next_button (the deterministic UnknownTargetError class)."""
    scenario = resolve_scenario("S002", REPO_ROOT / "lab" / "scenarios")
    plans = plan_steps(scenario.steps, None)
    assert [p.step.action for p in plans] == ["launch", "complete-onboarding"]
    onboarding = plans[1]
    assert [c.verb for c in onboarding.calls] == ["onboarding_complete"]
    assert onboarding.calls[0].params == {}
    # NOT a registry tap — the id cannot exist in the reference scope
    assert not any(c.verb == "tap_semantic" for c in onboarding.calls)
    assert "next_button" not in str(onboarding.calls)


@pytest.mark.parametrize("action", [
    "accept-document", "accept", "confirm", "confirm-delete", "done",
])
def test_document_affirmatives_keep_next_button_tap(action):
    """The OTHER actions of the pre-010F if-group KEEP the next_button
    tap — the design contract legitimately applies to the document-flow
    affirmatives; only complete-onboarding is re-mapped."""
    plans = plan_steps([action], None)
    calls = plans[0].calls
    assert [c.verb for c in calls] == ["tap_semantic"]
    assert calls[0].params == {"target": "next_button"}
    assert calls[0].target == "next_button"


def test_plan_cli_s002_renders_onboarding_discovery_verb():
    """The CLI plan line renders the composite verb (and the corpus
    still resolves every step — no mapping-table hole)."""
    proc = run_cli(["run", "S002", "--plan"])
    assert proc.returncode == 0, proc.stdout + proc.stderr
    out = proc.stdout
    assert "plan: S002 onboarding status=UNKNOWN" in out
    assert "step 01 launch → launch(app=<app>)" in out
    assert "step 02 complete-onboarding → onboarding_complete()" in out
    # the pre-010F form is gone from the plan
    assert ("step 02 complete-onboarding → "
            "tap_semantic(target=next_button)") not in out


# ------------------------------------------------------- discovery loop

def test_onboarding_discovery_full_path_permission_next_complete():
    """The mandated full path on scripted dumps: round-1 permission
    dialog → allow tapped at the bounds-center; round-2 "Next" →
    tapped; round-3 clean main screen → ok. Tap coordinates ARE the
    bounds-centers; one emitted line per round; TCG-paced settles."""
    provider = ScriptedProvider()
    provider.ui_xmls = [PERMISSION_DUMP, NEXT_DUMP, CLEAN_DUMP]
    driver, clock = _make_driver(provider)
    bridge = _bridge(provider)
    lines: list[str] = []
    native = _Native(provider, "e2b-fake01", "", "")
    ok = driver._onboarding_complete(bridge, native, "com.intsig.camscanner",
                                     120, lines.append)
    assert ok is True
    # the taps are EXACTLY the bounds-centers of the matched nodes
    assert [(a.x, a.y) for a in provider.interactions] == [
        (540, 1454), (980, 2210)]
    # the emitted per-round lines
    assert any("onboarding round 1: permission granted at (540,1454)"
               in line for line in lines)
    assert any("onboarding round 2: control 'Next' tapped at (980,2210)"
               in line for line in lines)
    assert any("onboarding round 3: no actionable control "
               "— onboarding complete" in line for line in lines)
    # one settle after each acting round (the completion round: none)
    assert clock.slept == [ONB_ROUND_SETTLE_S, ONB_ROUND_SETTLE_S]
    # the deny button was never touched (permission affirmative won)
    assert not any(a.x == 540 and a.y == 1580 for a in provider.interactions)


def test_onboarding_discovery_skip_path():
    """Skip-path: dumps whose only actionable control is "Skip" →
    tapped → completes on the next (clean) round."""
    provider = ScriptedProvider()
    provider.ui_xmls = [SKIP_DUMP, CLEAN_DUMP]
    driver, clock = _make_driver(provider)
    bridge = _bridge(provider)
    lines: list[str] = []
    native = _Native(provider, "e2b-fake01", "", "")
    ok = driver._onboarding_complete(bridge, native, "com.intsig.camscanner",
                                     120, lines.append)
    assert ok is True
    assert [(a.x, a.y) for a in provider.interactions] == [(940, 140)]
    assert any("round 1: control 'Skip' tapped at (940,140)"
               in line for line in lines)
    assert any("round 2: no actionable control — onboarding complete"
               in line for line in lines)
    assert clock.slept == [ONB_ROUND_SETTLE_S]


def test_onboarding_discovery_trap_exclusion_honest_fail():
    """Trap exclusion: a dump whose only clickable is "Upgrade to
    Premium" → NOT tapped (the hard exclusion set) → the loop exhausts
    its bounded rounds → the honest False. A present-but-refused trap
    is NOT completion."""
    provider = ScriptedProvider()
    provider.ui_xmls = [TRAP_DUMP]              # repeats every round
    driver, clock = _make_driver(provider)
    bridge = _bridge(provider)
    lines: list[str] = []
    native = _Native(provider, "e2b-fake01", "", "")
    ok = driver._onboarding_complete(bridge, native, "com.intsig.camscanner",
                                     120, lines.append)
    assert ok is False
    assert provider.interactions == []         # NEVER tapped
    # every round refused — the exclusion line per round, 12 rounds
    refusals = [line for line in lines if "excluded — not tapped" in line]
    assert len(refusals) == ONB_MAX_ROUNDS
    assert any("'Upgrade to Premium'" in line for line in refusals)
    # and the honest exhaustion line
    assert any(f"discovery exhausted {ONB_MAX_ROUNDS} rounds "
               "without completing" in line for line in lines)
    # the rounds were dump-driven (one fresh dump per round)
    ui_captures = [op for op in provider.ops
                   if op[0] == "capture" and op[1] == "ui_hierarchy"]
    assert len(ui_captures) == ONB_MAX_ROUNDS
    assert clock.slept == [ONB_ROUND_SETTLE_S] * ONB_MAX_ROUNDS


def test_onboarding_discovery_capture_failure_settles_and_continues():
    """Capture-failure tolerance: a hardened capture that keeps raising
    (the persistent-garbage class) is caught per round — the round
    counts, the ladder settles and continues — and the exhaustion is
    the honest False, never a crash."""
    from tools.lab_cli.reference_live import onboarding_discovery_loop

    class _FailingDumpProvider(ScriptedProvider):
        def capture(self, env_id, kind, timeout=None):
            if kind is CaptureKind.ui_hierarchy:
                raise RuntimeError(
                    "ui hierarchy dump failed: " + S001_EVIDENCE_GARBAGE)
            return super().capture(env_id, kind, timeout)

    provider = _FailingDumpProvider()
    driver, clock = _make_driver(provider)
    bridge = _bridge(provider)
    lines: list[str] = []
    native = _Native(provider, "e2b-fake01", "", "")
    ok = driver._onboarding_complete(bridge, native, "com.intsig.camscanner",
                                     120, lines.append)
    assert ok is False
    assert provider.interactions == []
    failures = [line for line in lines if "ui dump unavailable" in line]
    assert len(failures) == ONB_MAX_ROUNDS
    assert any("RuntimeError: ui hierarchy dump failed" in line
               for line in failures)
    assert any(f"discovery exhausted {ONB_MAX_ROUNDS} rounds" in line
               for line in lines)
    assert clock.slept == [ONB_ROUND_SETTLE_S] * ONB_MAX_ROUNDS
    # the shared ladder is the SAME implementation both drivers run
    assert onboarding_discovery_loop.__module__ == "tools.lab_cli.reference_live"


# -------------------------------------------------------- pattern sets

def test_onb_pattern_sets_semantics():
    """The pattern sets as implemented: permission EXACT texts beat
    affirmative discovery (the deny button never matches); excluded
    traps are skipped in mixed dumps (an affirmative elsewhere still
    wins); matching is case-insensitive over BOTH text= and
    content-desc=; first match in document order; a clean screen is
    completion."""
    # permission precedes affirmative discovery even when the deny
    # button (an "allow" substring holder) comes first in document
    # order
    mixed = _onb_clickable_nodes(_dump(
        _node("Don't allow", clickable=True, bounds="[420,1530][660,1630]"),
        _node("While using the app", clickable=True,
              bounds="[420,1404][660,1504]"),
    ))
    assert _onb_round_action(mixed) == ("permission", "While using the app",
                                        (540, 1454))
    # a trap is skipped; the affirmative AFTER it wins
    mixed = _onb_clickable_nodes(_dump(
        _node("Upgrade to Premium", clickable=True,
              bounds="[100,2000][980,2140]"),
        _node("Continue", clickable=True, bounds="[880,2150][1080,2270]"),
    ))
    assert _onb_round_action(mixed) == ("affirmative", "Continue",
                                        (980, 2210))
    # case-insensitive substring over text= …
    assert _onb_round_action(_onb_clickable_nodes(_dump(
        _node("GET STARTED", clickable=True, bounds="[0,0][100,100]"),
    ))) == ("affirmative", "GET STARTED", (50, 50))
    # … and over content-desc= (text empty)
    assert _onb_round_action(_onb_clickable_nodes(_dump(
        _node(desc="Let's go", clickable=True, bounds="[0,0][100,100]"),
    ))) == ("affirmative", "Let's go", (50, 50))
    # document order: the first affirmative wins
    assert _onb_round_action(_onb_clickable_nodes(_dump(
        _node("Done", clickable=True, bounds="[0,0][100,100]"),
        _node("Finish", clickable=True, bounds="[0,100][100,200]"),
    ))) == ("affirmative", "Done", (50, 50))
    # non-clickable nodes are never scanned
    assert _onb_round_action(_onb_clickable_nodes(_dump(
        _node("Next", clickable=False, bounds="[0,0][100,100]"),
    ))) == ("", "", None)
    # the clean screen: completion
    assert _onb_round_action(_onb_clickable_nodes(CLEAN_DUMP)) == \
        ("", "", None)
    # only traps present: refused, never completion
    assert _onb_round_action(_onb_clickable_nodes(TRAP_DUMP)) == \
        ("excluded", "Upgrade to Premium", (540, 2070))
    # an unparseable dump scans as nothing (the round counts, no crash)
    assert _onb_clickable_nodes(S001_EVIDENCE_GARBAGE) == []


# -------------------------------------------------------- end-to-end

def test_execute_s002_onboarding_discovery_end_to_end(monkeypatch,
                                                       tmp_path):
    """The reference driver's execute() dispatch: S002 (launch →
    complete-onboarding) runs the discovery ladder after the proven
    launch machinery — the step evidence pair lands, the run-metadata
    action trace records the ok step, the run is ok. The pre-010F
    death (the UnknownTargetError at the FIRST onboarding interaction)
    cannot recur: no registry lookup is involved."""
    monkeypatch.setenv("E2B_API_KEY", "placeholder-not-a-credential")
    monkeypatch.delenv("CAMSCAN_APK_URL", raising=False)
    xapk = _make_xapk(tmp_path / "CamScanner_7.25.5.xapk")
    provider = ScriptedProvider()
    provider.on("cat /root/install.out", "Success\nEXIT_0\n")
    # ui dumps in consumption order: the ANR ladder's (no Wait button —
    # no dialog), the step-01 evidence capture, the three discovery
    # rounds (permission → Next → clean), the step-02 evidence capture
    provider.ui_xmls = [
        '<hierarchy rotation="0"><node text="CamScanner"/></hierarchy>',
        '<hierarchy rotation="0"><node text="CamScanner"/></hierarchy>',
        PERMISSION_DUMP, NEXT_DUMP, CLEAN_DUMP,
        '<hierarchy rotation="0"><node text="CamScanner Home"/></hierarchy>',
    ]
    driver, _clock = _make_driver(provider, apk=xapk)
    lines: list[str] = []
    handle = driver.provision(_provision_request(lines, apk=xapk,
                                                 scenario_id="S002"))

    scenario = resolve_scenario("S002", REPO_ROOT / "lab" / "scenarios")
    plans = plan_steps(scenario.steps, None)
    stage = tmp_path / "stage"
    request = ExecutionRequest(
        handle=handle, run_id=RUN_ID, subject="reference",
        scenario=scenario, step_plans=plans, stage_dir=stage,
        fixtures=[], started_at="2026-09-25T00:00:00Z",
        finished_at="2026-09-25T00:05:00Z", emit=lines.append)
    result = driver.execute(handle, request)

    assert result.ok is True, result.reason
    assert result.problems == []
    assert result.steps_executed == 2
    # the discovery ladder's taps — the ONLY interactions (the launch
    # ladder drives adb directly; the ANR dialog was absent)
    assert [(a.x, a.y) for a in provider.interactions] == [
        (540, 1454), (980, 2210)]
    assert any("onboarding round 1: permission granted at (540,1454)"
               in line for line in lines)
    assert any("onboarding round 2: control 'Next' tapped at (980,2210)"
               in line for line in lines)
    assert any("onboarding round 3: no actionable control "
               "— onboarding complete" in line for line in lines)
    # NO registry tap was ever attempted — no UnknownTargetError class
    assert not any("tap_semantic" in op[1] for op in provider.ops
                   if op[0] == "interact")

    # per-step evidence pairs (the existing observe machinery)
    subject = stage / "reference"
    assert (subject / "screenshots" / "01-launch.png").read_bytes() == \
        provider.screenshot_bytes
    assert (subject / "screenshots" / "02-complete-onboarding.png"
            ).is_file()
    assert "<hierarchy" in (subject / "ui" / "01-launch.xml").read_text(
        encoding="utf-8")
    assert "<hierarchy" in (subject / "ui" / "02-complete-onboarding.xml"
                            ).read_text(encoding="utf-8")

    # run-metadata: the onboarding step recorded ok
    doc = jsonio_load(stage / "run-metadata.json")
    assert doc["scenario"] == "onboarding"
    assert doc["action_trace"] == [
        {"t_ms": 0, "action": "launch", "target": "", "result": "ok"},
        {"t_ms": 1400, "action": "complete-onboarding", "target": "",
         "result": "ok"},
    ]
    assert doc["application"]["version_name"] == "7.25.5.2609020000"


# ------------------------------------------------------------- e2b-live

def test_e2b_live_onboarding_registry_miss_falls_back_to_discovery(
        tmp_path):
    """e2b_live fallback: a registry miss (scripted
    UnknownTargetError — a registry without next_button in ANY scope)
    falls into the SAME dump-first discovery loop, which completes on
    the same dump sequence (permission → Next → clean)."""
    provider = ScriptedProvider()
    provider.ui_xmls = [PERMISSION_DUMP, NEXT_DUMP, CLEAN_DUMP]
    registry = tmp_path / "targets-miss.yaml"
    registry.write_text(
        "schema: 1\nprofile:\n  device: pixel_4\ntargets: {}\napps: {}\n",
        encoding="utf-8")
    bridge = _bridge(provider, targets=registry)
    clock = FakeClock()
    driver = E2bLiveDriver(sleep=clock.sleep)
    scenario = resolve_scenario("S002", REPO_ROOT / "lab" / "scenarios")
    plans = plan_steps(scenario.steps, None)
    lines: list[str] = []
    ok = driver._invoke_plan(bridge, plans[1], "org.payswap.camscan",
                             120, lines.append)
    assert ok is True
    assert [(a.x, a.y) for a in provider.interactions] == [
        (540, 1454), (980, 2210)]
    assert any("implementation: onboarding round 1: permission granted "
               "at (540,1454)" in line for line in lines)
    assert any("implementation: onboarding round 2: control 'Next' "
               "tapped at (980,2210)" in line for line in lines)
    assert any("implementation: onboarding round 3: no actionable "
               "control — onboarding complete" in line for line in lines)
    assert clock.slept == [ONB_ROUND_SETTLE_S, ONB_ROUND_SETTLE_S]


def test_e2b_live_onboarding_registry_first_design_contract_tap():
    """Implementation-app semantics, the ORDER pin: with the default
    registry the design-contract tap runs FIRST — next_button resolves
    through its registered coordinate fallback (980, 2210) — and the
    discovery loop never runs."""
    provider = ScriptedProvider()
    bridge = _bridge(provider)                 # the real targets.yaml
    driver = E2bLiveDriver(sleep=lambda _s: None)
    scenario = resolve_scenario("S002", REPO_ROOT / "lab" / "scenarios")
    plans = plan_steps(scenario.steps, None)
    lines: list[str] = []
    ok = driver._invoke_plan(bridge, plans[1], "org.payswap.camscan",
                             120, lines.append)
    assert ok is True
    assert [(a.x, a.y) for a in provider.interactions] == [(980, 2210)]
    assert lines == []                          # no discovery rounds ran


# ------------------------------------------------------ recording driver

def test_recording_driver_renders_onboarding_complete_verb(tmp_path):
    """recording.py needed NO change: a plan containing
    onboarding_complete renders generically — the full paired
    recording run (reference + implementation) reconciles to PASS and
    the would-be call line names the composite verb for BOTH
    subjects."""
    runs = tmp_path / "runs"
    gaps = tmp_path / "gaps"
    proc = run_cli(["run", "S002", "--driver", "recording",
                    "--runs-dir", str(runs), "--gaps-dir", str(gaps),
                    "--stamp", STAMP])
    assert proc.returncode == 0, proc.stdout + proc.stderr
    out = proc.stdout
    assert ("reference: step 02 complete-onboarding → would: "
            "onboarding_complete()") in out
    assert ("implementation: step 02 complete-onboarding → would: "
            "onboarding_complete()") in out
    run_dir = runs / f"{STAMP}-S002-recording"
    assert (run_dir / "reference" / "ui" / "02-complete-onboarding.xml"
            ).is_file()
    assert (run_dir / "implementation" / "ui" /
            "02-complete-onboarding.xml").is_file()
    verdict = jsonio_load(run_dir / "reconciliation" / "verdict.json")
    assert verdict["verdict"] == "PASS"
    assert verdict["scenario"] == "onboarding"
