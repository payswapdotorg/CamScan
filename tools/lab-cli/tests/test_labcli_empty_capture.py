"""CAMSCAN-010J — empty-capture honesty: the contract tests (hermetic).

Provenance — the lead's live S002 round on post-010I code (2026-09-26
09:17:47 UTC attempt, console sequence verbatim):

    reference: launch attempt 3: am start ok (Status: ok)
    reference: app process up (pid 9254)
    reference: ANR Wait dismissed at (540,1241) [round 1..3] + fallback
    reference: onboarding round 1..8: ANR Wait dismissed at (540,1241)
               (app recovering, subject='CamScanner')
    reference: onboarding round 5: init probe — /bin/bash: line 1:
               adb: command not found          (benign probe flake — PART D)
    reference: onboarding round 9: no actionable control — onboarding
               complete                        (NO PROOF the dump was
               valid — PART A)
    reference: package facts from the install-time stash
               (version_name='7.25.5.2609020000', ...)
    reference: destroyed e2b-8c8733f9 [paid environment released]
    lab-cli: evidence-cli bundle failed for 20260926T091747Z-S002-live/
             reference: artifacts[0].bytes must be a positive integer,
             got 0                              (PART B — pinned in
                                               tools/evidence-cli/tests/
                                               test_empty_captures.py)
    [2026-09-26 10:17:50 UTC] S002 attempt 1: operational failure —
    fresh retry (sandbox cleanly aborted)

… and the run dir was then DELETED by the bundle-failure cleanup
(PART C — the round-9 dump and every capture from the run were
unrecoverable; the preservation fix exists so this never recurs).

The 8e24fad S002-1 precedent (commit message: "the complete-onboarding
verdict was a FALSE POSITIVE — the step's ui dump shows the ANR dialog
which the loop treated as 'no actionable control = complete'") is the
SAME false-positive class ONE LEVEL DEEPER: not ANR-dialog-as-complete,
but EMPTY-DUMP-as-complete. The 010I machinery held (the wall, the
ladder, the deep-patience loop rode the ANR storm for 8 attributed
rounds and the dialog stopped re-appearing at round 9 — patience
plausibly won); the three defects that compounded are what these tests
pin dead:

- PART A — the completion-gate NODE PROOF: an ok-but-empty /
  unparseable / zero-node dump is NOT completion (the round settles
  and continues, consuming budget; the honest exhaustion line remains
  the loop's other exit); a valid dump with a root node and no
  clickable controls IS completion; a dump with a real control still
  taps it (regression). The 010G tap-label fallback audited: it
  shares NO completion verdict shape (only a label tap or the honest
  re-raise).
- PART C — bundle-failure PRESERVATION: the staged tree survives at
  .staging/<run_id>-bundlefailed and the failure emit carries its
  ABSOLUTE path; the taxonomy distinguishes "BUNDLE FAILED" from the
  operational "FAILED —" line; operational aborts keep the existing
  cleanup (regression pin).
- PART D — the init probe's command carries the DRIVER's adb token
  (the bootstrap full path) through the bridge, never a bare "adb"
  that the sandbox PATH cannot resolve (the 010E law).

All hermetic: FakeClock + ScriptedProvider / stub drivers, the REAL
AdbBridge, no network, no e2b SDK, no credentials anywhere.
"""
from __future__ import annotations

import re
from pathlib import Path

import pytest
from labcli_helpers import REPO_ROOT
from test_labcli_onboarding import (
    CLEAN_DUMP,
    NEXT_DUMP,
    _dump,
    _node,
)
from test_labcli_reference import (
    FakeClock,
    ScriptedProvider,
    _make_driver,
    _make_xapk,
    _provision_request,
)
from tools.adb_bridge.bridge import UnknownTargetError
from tools.evidence_cli.jsonio import dumps_deterministic
from tools.lab_cli import run as run_module
from tools.lab_cli.drivers import (
    DriverHandle,
    ExecutionRequest,
    SubjectRunResult,
)
from tools.lab_cli.evidence import preserve_failed_bundle
from tools.lab_cli.reference_live import (
    ONB_MAX_ROUNDS,
    ONB_ROUND_SETTLE_S,
    _Native,
    _onb_dump_node_count,
)
from tools.lab_cli.scenarios import resolve_scenario
from tools.lab_cli.steps import plan_steps

# CAMSCAN-010E pattern: the bootstrap recipe's full-path ADB — the
# exact local provision() threads into _Native.adb and (since 010J)
# the execute-path bridge composes commands with.
from lab.providers.e2b.bootstrap import ADB

#: An ok-but-EMPTY dump body — uiautomator answered with nothing (the
#: strained-mid-recovery window after a long ANR storm; the S002
#: round-9 shape).
EMPTY_DUMP_TEXT = ""

#: A parseable but node-less hierarchy shell — uiautomator's other
#: empty answer ("<hierarchy/>"; the ScriptedProvider's own default).
EMPTY_HIERARCHY_DUMP = "<hierarchy/>"

#: The verbatim S001 capture-flap garbage (the unparseable class).
UNPARSEABLE_DUMP = "cat: /sdcard/window_dump.xml: Permission denied\n"

#: A REAL screen with no actionable control: one non-clickable root
#: node (node proof = 1, clickable nodes = 0) — the valid completion
#: shape per the 010J gate.
ROOT_ONLY_DUMP = _dump(
    _node("CamScanner Home", clickable=False,
          bounds="[84,120][996,220]"),
)


def _bridge(provider: ScriptedProvider):
    from tools.adb_bridge.bridge import AdbBridge

    return AdbBridge(provider, "e2b-fake01", default_timeout_s=120.0)


def _run_loop(provider: ScriptedProvider, clock: FakeClock) \
        -> tuple[bool, list[str]]:
    """The 010G test idiom: the reference driver's onboarding verb on
    the scripted provider, (ok, emitted lines) back."""
    driver, _ = _make_driver(provider)
    driver._sleep = clock.sleep
    driver._monotonic = clock.monotonic
    bridge = _bridge(provider)
    lines: list[str] = []
    native = _Native(provider, "e2b-fake01", "", "")
    ok = driver._onboarding_complete(bridge, native,
                                     "com.intsig.camscanner",
                                     120, lines.append)
    return ok, lines


# ------------------------------------------------- part A — node proof unit

def test_node_count_proof_shapes():
    """The verdict's oracle: an empty body, an empty-hierarchy shell,
    and unparseable garbage all carry ZERO nodes; a single
    non-clickable root node carries ONE (a real screen answered — the
    hierarchy root itself is not a node); a normal dump carries its
    full node count."""
    assert _onb_dump_node_count(EMPTY_DUMP_TEXT) == 0
    assert _onb_dump_node_count(EMPTY_HIERARCHY_DUMP) == 0
    assert _onb_dump_node_count(UNPARSEABLE_DUMP) == 0
    assert _onb_dump_node_count(ROOT_ONLY_DUMP) == 1
    assert _onb_dump_node_count(NEXT_DUMP) == 2
    assert _onb_dump_node_count(CLEAN_DUMP) == 2


# ------------------------------------- part A — the completion gate (loop)

def test_ok_empty_dump_is_not_completion_settles_and_continues():
    """TEST 1 (the S002 round-9 shape): a round whose dump is
    ok-but-EMPTY is NOT completion — the honest no-nodes line fires,
    the round SETTLES (FakeClock proves the settle consumed) and the
    loop CONTINUES; a later valid dump with a root node and no
    clickable controls completes. Pre-010J this exact sequence
    completed on round 1 (zero clickables read as "no actionable
    control")."""
    provider = ScriptedProvider()
    provider.ui_xmls = [EMPTY_DUMP_TEXT, ROOT_ONLY_DUMP]
    clock = FakeClock()
    ok, lines = _run_loop(provider, clock)
    assert ok is True
    # the honest round-1 line (verbatim shape), never a completion
    assert any("onboarding round 1: dump carried no nodes "
               "— not treating as complete" in line for line in lines)
    assert not any("round 1: no actionable control" in line
                   for line in lines)
    # round 2's REAL screen completed
    assert any("onboarding round 2: no actionable control "
               "— onboarding complete" in line for line in lines)
    # nothing was ever tapped
    assert provider.interactions == []
    # the settle ledger: the leading 2x settle, the no-nodes round's
    # settle, and NO settle for the completion round
    assert clock.slept == [2 * ONB_ROUND_SETTLE_S, ONB_ROUND_SETTLE_S]


def test_empty_hierarchy_shell_is_not_completion():
    """The parseable-but-node-less variant (uiautomator's
    "<hierarchy/>" answer) is the SAME non-completion: honest line,
    settle, continue — then a real screen completes."""
    provider = ScriptedProvider()
    provider.ui_xmls = [EMPTY_HIERARCHY_DUMP, ROOT_ONLY_DUMP]
    clock = FakeClock()
    ok, lines = _run_loop(provider, clock)
    assert ok is True
    assert any("onboarding round 1: dump carried no nodes "
               "— not treating as complete" in line for line in lines)
    assert any("onboarding round 2: no actionable control "
               "— onboarding complete" in line for line in lines)
    assert clock.slept == [2 * ONB_ROUND_SETTLE_S, ONB_ROUND_SETTLE_S]


def test_unparseable_garbage_dump_is_not_completion():
    """TEST 2: garbage dump text (the capture-flap class) — the same
    no-completion continuation. Two garbage rounds settle and count,
    the third round's real screen completes."""
    provider = ScriptedProvider()
    provider.ui_xmls = [UNPARSEABLE_DUMP, UNPARSEABLE_DUMP,
                        ROOT_ONLY_DUMP]
    clock = FakeClock()
    ok, lines = _run_loop(provider, clock)
    assert ok is True
    honest = [line for line in lines if "dump carried no nodes" in line]
    assert len(honest) == 2
    assert any("onboarding round 1: dump carried no nodes" in line
               for line in honest)
    assert any("onboarding round 2: dump carried no nodes" in line
               for line in honest)
    assert any("onboarding round 3: no actionable control "
               "— onboarding complete" in line for line in lines)
    assert clock.slept == [2 * ONB_ROUND_SETTLE_S,
                           ONB_ROUND_SETTLE_S, ONB_ROUND_SETTLE_S]


def test_all_empty_dumps_exhaust_honestly_never_complete():
    """The far side of the gate: a loop whose EVERY round serves a
    no-node dump burns the whole round budget and fails with the
    HONEST exhaustion line — "onboarding complete" never fires (the
    budget structure and the exhaustion lines are untouched by 010J;
    the no-nodes rounds consume budget like any other round)."""
    provider = ScriptedProvider()
    provider.ui_xmls = [EMPTY_DUMP_TEXT]      # repeats every round
    clock = FakeClock()
    ok, lines = _run_loop(provider, clock)
    assert ok is False
    honest = [line for line in lines if "dump carried no nodes" in line]
    assert len(honest) == ONB_MAX_ROUNDS
    assert any(f"discovery exhausted {ONB_MAX_ROUNDS} rounds "
               "without completing — honest failure" in line
               for line in lines)
    assert not any("onboarding complete" in line for line in lines)
    # never an ANR verdict (no round carried the signature)
    assert not any("ANR-looping" in line for line in lines)
    # budget consumed like any other round: one settle per round
    assert clock.slept == [2 * ONB_ROUND_SETTLE_S] + \
        [ONB_ROUND_SETTLE_S] * ONB_MAX_ROUNDS


def test_real_control_still_tapped_after_gate_regression():
    """TEST 1 (regression tail): the gate did NOT over-reach — a dump
    with a REAL onboarding control still taps it, and the following
    control-free real screen completes."""
    provider = ScriptedProvider()
    provider.ui_xmls = [EMPTY_DUMP_TEXT, NEXT_DUMP, ROOT_ONLY_DUMP]
    clock = FakeClock()
    ok, lines = _run_loop(provider, clock)
    assert ok is True
    assert [(a.x, a.y) for a in provider.interactions] == [(980, 2210)]
    assert any("onboarding round 2: control 'Next' tapped at (980,2210)"
               in line for line in lines)
    assert any("onboarding round 3: no actionable control "
               "— onboarding complete" in line for line in lines)


def test_anr_rounds_then_empty_then_real_screen():
    """The full live shape, compressed: ANR rounds (Wait dismissed,
    attribution carried — 010H behavior UNCHANGED by the gate), then
    a strained empty dump (NOT completion), then the real control-free
    screen (completion). Patience wins honestly or not at all."""
    from test_labcli_anr_label import ANR_DUMP

    provider = ScriptedProvider()
    provider.ui_xmls = [ANR_DUMP, ANR_DUMP, EMPTY_DUMP_TEXT,
                        ROOT_ONLY_DUMP]
    clock = FakeClock()
    ok, lines = _run_loop(provider, clock)
    assert ok is True
    dismissals = [line for line in lines if "ANR Wait dismissed" in line]
    assert len(dismissals) == 2
    assert any("subject='CamScanner'" in line for line in dismissals)
    assert any("onboarding round 3: dump carried no nodes "
               "— not treating as complete" in line for line in lines)
    assert any("onboarding round 4: no actionable control "
               "— onboarding complete" in line for line in lines)


# --------------------------- part A.2 — the tap-label fallback audit pin

def test_label_fallback_empty_dump_still_reraises(tmp_path):
    """PART A.2's audit conclusion, pinned: the 010G fallback shares
    NO completion verdict shape — on an ok-but-EMPTY dump it neither
    completes nor succeeds: the scan has nothing to needle-match and
    the original UnknownTargetError re-raises (honest failure, never
    a silent success). No node-proof gate is needed there."""
    provider = ScriptedProvider()
    provider.ui_xmls = [EMPTY_DUMP_TEXT]
    driver, _clock = _make_driver(provider)
    bridge = _bridge(provider)
    lines: list[str] = []
    native = _Native(provider, "e2b-fake01", "", "")
    (plan,) = plan_steps(["tap: scan"], None)
    with pytest.raises(UnknownTargetError):
        driver._invoke_plan(bridge, plan, "com.intsig.camscanner",
                            120, native, lines.append)
    assert provider.interactions == []        # nothing was tapped
    assert not any("onboarding complete" in line for line in lines)
    assert not any("label discovery" in line for line in lines)


# ----------------------------- part D — the init-probe adb prefix (shape)

def test_init_probe_carries_driver_adb_prefix_through_execute(
        monkeypatch, tmp_path):
    """PART D (the 010E command-shape pin, through the FULL execute
    path): the round-5 init probe's dumpsys command carries the
    DRIVER's adb token — the bootstrap full path — because the
    execute-path bridge is constructed with adb=native.adb. The live
    S002 round-5 flake ("/bin/bash: line 1: adb: command not found")
    was the bare discovered token, which the sandbox PATH cannot
    resolve; the truncation + cadence bounds are untouched (one
    bounded probe line at the round-5 checkpoint)."""
    monkeypatch.setenv("E2B_API_KEY", "placeholder-not-a-credential")
    monkeypatch.delenv("CAMSCAN_APK_URL", raising=False)
    xapk = _make_xapk(tmp_path / "CamScanner_7.25.5.xapk")
    provider = ScriptedProvider()
    provider.on("cat /root/install.out", "Success\nEXIT_0\n")
    # dump consumption order: the ANR ladder's (no Wait button), the
    # step-01 evidence capture, then the onboarding loop's rounds 1-5
    # (four acting rounds + the round-5 clean screen — the probe fires
    # at the round-5 checkpoint BEFORE the round's dump).
    no_wait = '<hierarchy rotation="0"><node text="CamScanner"/></hierarchy>'
    provider.ui_xmls = [no_wait, no_wait,
                        NEXT_DUMP, NEXT_DUMP, NEXT_DUMP, NEXT_DUMP,
                        CLEAN_DUMP]
    driver, _clock = _make_driver(provider, apk=xapk)
    lines: list[str] = []
    handle = driver.provision(_provision_request(lines, apk=xapk,
                                                 scenario_id="S002"))
    scenario = resolve_scenario("S002", REPO_ROOT / "lab" / "scenarios")
    plans = plan_steps(scenario.steps, None)
    request = ExecutionRequest(
        handle=handle, run_id="20260926T091747Z-S002-live",
        subject="reference", scenario=scenario, step_plans=plans,
        stage_dir=tmp_path / "stage", fixtures=[],
        started_at="2026-09-26T09:17:47Z",
        finished_at="2026-09-26T09:35:00Z", emit=lines.append)
    result = driver.execute(handle, request)
    assert result.ok is True, result.reason
    # the probe FIRED at the round-5 checkpoint (cadence bound intact)
    assert any("onboarding round 5: init probe — " in line
               for line in lines)
    assert len([line for line in lines if "init probe" in line]) == 1
    # THE SHAPE PIN (the 010E test pattern): every dumpsys-window
    # command in the transport log starts with the bootstrap ADB full
    # path + " shell dumpsys window" — never a bare "adb" token
    probe_cmds = [c for c in provider.exec_log if "dumpsys window" in c]
    assert probe_cmds, "the init probe did not run"
    for cmd in probe_cmds:
        assert cmd.startswith(f"{ADB} shell dumpsys window | grep -E ")
    # and the probe is bounded + one line (010H bounds untouched)
    probe_lines = [line for line in lines if "init probe" in line]
    assert len(probe_lines) == 1
    assert "\n" not in probe_lines[0]


# --------------------- part C — bundle-failure preservation (run.py)

#: A deterministic screenshot payload (the recording-driver idiom).
_STUB_PNG = (b"\x89PNG\r\n\x1a\n" + b"camscan-010j-stub-screenshot")

_STUB_XML = ('<?xml version="1.0"?><hierarchy rotation="0">'
             '<node text="CamScanner"/></hierarchy>\n')


def _stub_metadata(run_id: str, subject: str) -> dict:
    """A schema-valid single-subject run-metadata.json for the stub
    drivers (the bundle step's required inputs; the S001 scenario)."""
    return {
        "run_id": run_id,
        "scenario": "application-launch",
        "subject": subject,
        "provider": {
            "slug": "e2b",
            "capabilities": {"gui": True, "adb": True,
                             "android_emulator": True},
            "environment_id": "env-stub",
        },
        "application": {
            "package": "com.intsig.camscanner",
            "version_name": "7.25.5.2609020000",
            "version_code": 2609020000,
            "installer_sha256": "0" * 64,
        },
        "device": {
            "model": "Pixel 4 (AVD pixel_4)",
            "android_version": "11",
            "screen": "1080x2280@440dpi",
            "locale": "en-US",
            "timezone": "UTC",
            "permission_baseline": {},
        },
        "fixtures": [],
        "action_trace": [{"t_ms": 0, "action": "launch", "target": "",
                          "result": "ok"}],
        "started_at": "2026-09-26T09:17:47Z",
        "finished_at": "2026-09-26T09:35:00Z",
    }


class _BadBundleDriver:
    """Hermetic stub: execute() writes a VALID single-subject staging
    tree (subject artifacts + run-metadata.json) plus ONE unexpected
    root entry — the exact class of problem the bundle builder must
    reject (root-layout enforcement), driving the bundle step into
    its failure path with real evidence on disk to preserve."""

    slug = "bad-bundle-stub"

    def __init__(self) -> None:
        self.torn_down = False

    def provision(self, request) -> DriverHandle:
        return DriverHandle(subject=request.subject,
                            provider_slug="e2b",
                            environment_id="env-stub")

    def execute(self, handle, request) -> SubjectRunResult:
        stage = Path(request.stage_dir)
        sub = stage / request.subject
        (sub / "screenshots").mkdir(parents=True, exist_ok=True)
        (sub / "screenshots" / "01-launch.png").write_bytes(_STUB_PNG)
        (sub / "ui").mkdir(parents=True, exist_ok=True)
        (sub / "ui" / "01-launch.xml").write_text(_STUB_XML,
                                                  encoding="utf-8")
        (stage / "run-metadata.json").write_text(
            dumps_deterministic(_stub_metadata(request.run_id,
                                               request.subject)),
            encoding="utf-8")
        # the injected bundle-killer: an unexpected root entry
        (stage / "stray-scratch.txt").write_text(
            "operator scratch (the injected bundle failure)\n",
            encoding="utf-8")
        return SubjectRunResult(subject=request.subject, ok=True,
                                steps_executed=1)

    def teardown(self, handle, error: str = "", emit=print) -> None:
        self.torn_down = True


class _FailingExecuteDriver:
    """Hermetic stub: execute() returns an honest operational failure
    (pre-evidence) — the path whose cleanup MUST stay as it was."""

    slug = "failing-stub"

    def provision(self, request) -> DriverHandle:
        return DriverHandle(subject=request.subject,
                            provider_slug="e2b",
                            environment_id="env-stub")

    def execute(self, handle, request) -> SubjectRunResult:
        return SubjectRunResult.failed(
            request.subject, "step 01 launch failed (simulated)")

    def teardown(self, handle, error: str = "", emit=print) -> None:
        pass


def _run_scenario_with_driver(tmp_path: Path, driver,
                              subject: str = "reference") \
        -> tuple[int, list[str], Path, Path]:
    """run_scenario in-process with resolve_driver patched to the stub
    (hermetic — the recording test idiom, no subprocess)."""
    from tools.lab_cli.run import RunOptions, run_scenario

    runs = tmp_path / "runs"
    lines: list[str] = []
    opts = RunOptions(repo_root=REPO_ROOT, runs_dir=runs,
                      gaps_dir=tmp_path / "gaps", envs=(subject,),
                      stamp="20260926T091747Z", suffix="stub",
                      registry=None, emit=lines.append)
    scenario = resolve_scenario("S001", REPO_ROOT / "lab" / "scenarios")
    original = run_module.resolve_driver
    run_module.resolve_driver = \
        lambda env, slug, kind: (driver, "")
    try:
        code = run_scenario(scenario, opts)
    finally:
        run_module.resolve_driver = original
    return code, lines, runs, runs / ".staging"


def test_bundle_failure_preserves_staged_tree_and_emits_path(tmp_path):
    """TEST 4 (the destroyed-evidence defect): a staged run whose
    bundle FAILS keeps its tree — moved to
    .staging/<run_id>-bundlefailed, every capture intact — and the
    failure emit carries the ABSOLUTE preserved path with the
    distinct BUNDLE FAILED taxonomy (the campaign script greps these
    lines). The live 2026-09-26 09:17:47 UTC S002 round deleted the
    round-9 dump + every capture on exactly this path."""
    driver = _BadBundleDriver()
    code, lines, runs, staging = _run_scenario_with_driver(tmp_path, driver)
    run_id = "20260926T091747Z-S001-stub"
    assert code == 1                       # the run fails operationally
    assert driver.torn_down is True        # teardown still ran
    # the taxonomy: BUNDLE FAILED (distinct from the operational
    # "FAILED —" line), carrying the ABSOLUTE preserved path
    bundle_lines = [line for line in lines if "BUNDLE FAILED" in line]
    assert len(bundle_lines) == 1
    assert "unexpected root entry" in bundle_lines[0]
    assert "evidence-cli bundle failed for" in bundle_lines[0]
    match = re.search(r"preserved at (\S+)", bundle_lines[0])
    assert match, bundle_lines[0]
    preserved = Path(match.group(1))
    assert preserved.is_absolute()
    # the tree SURVIVED at the preserved path: the whole staged run
    # (subject artifacts + metadata + the scenario copy) is intact
    assert preserved == (staging / f"{run_id}-bundlefailed").resolve()
    staged_run = preserved / "reference" / run_id
    assert (staged_run / "reference" / "screenshots" /
            "01-launch.png").read_bytes() == _STUB_PNG
    assert (staged_run / "reference" / "ui" /
            "01-launch.xml").read_text(encoding="utf-8") == _STUB_XML
    assert (staged_run / "run-metadata.json").is_file()
    assert (staged_run / "scenario.yaml").is_file()
    # the live staging root is gone (moved, not copied); the .staging
    # parent survives BECAUSE the preserved tree lives in it
    assert not (staging / run_id).exists()
    assert staging.is_dir()
    # no run dir was assembled (only successful bundling assembles)
    assert not (runs / run_id).exists()
    # the operational-abort emit never fired
    assert not any(line.strip().startswith("reference: FAILED —")
                   for line in lines)


def test_operational_abort_still_cleans_staging(tmp_path):
    """TEST 4 (regression pin): the operational abort (execute fails
    pre-evidence) keeps the EXISTING behavior byte-for-byte — the
    "FAILED — <reason>" emit, the staging cleanup, no preserved tree,
    no BUNDLE FAILED line."""
    driver = _FailingExecuteDriver()
    code, lines, runs, staging = _run_scenario_with_driver(tmp_path, driver)
    assert code == 1
    assert any("  reference: FAILED — "
               "step 01 launch failed (simulated)" in line
               for line in lines)
    assert not any("BUNDLE FAILED" in line for line in lines)
    # the staging tree is CLEANED — nothing preserved, .staging gone
    assert not staging.exists()
    assert list(runs.glob("*-bundlefailed*")) == []
    assert not (runs / "20260926T091747Z-S001-stub").exists()


def test_preserve_failed_bundle_collision_suffix(tmp_path):
    """The preservation path collision rule: a SECOND failed bundle
    under the same run_id never overwrites the first preserved tree —
    the suffix ladder (-bundlefailed-2, -3, …) keeps every
    preservation distinct."""
    runs = tmp_path / "runs"
    run_id = "20260926T091747Z-S001-stub"
    stage = runs / ".staging" / run_id / "reference" / run_id
    stage.mkdir(parents=True)
    (stage / "run-metadata.json").write_text("{}", encoding="utf-8")
    first = preserve_failed_bundle(runs, run_id)
    assert first is not None and first.is_dir()
    # a fresh staging tree for the same run_id, failed again
    stage = runs / ".staging" / run_id / "reference" / run_id
    stage.mkdir(parents=True)
    (stage / "run-metadata.json").write_text("{}", encoding="utf-8")
    second = preserve_failed_bundle(runs, run_id)
    assert second is not None and second.is_dir()
    assert second != first
    assert second.name == f"{run_id}-bundlefailed-2"
    assert first.is_dir()                   # the first tree untouched
    # nothing to preserve → None (never a promised path that is not
    # there)
    assert preserve_failed_bundle(runs, run_id) is None
