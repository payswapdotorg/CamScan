"""CAMSCAN-010I — scenario-wall vs sandbox-lifetime budget
reconciliation: the contract tests (hermetic).

The 2026-09-26 S002 live round (05:19-07:37 UTC, two attempts, both
operational failures) exposed a DETERMINISTIC conflict between the
four budgets:

- the S002 scenario wall (meta.timeout_seconds=1800) fired BEFORE the
  010H onboarding wall's honest-exhaustion line could carry its
  complete evidence — "meta.timeout_seconds exceeded — BLOCKED
  (timeout), never silently truncated" while the loop still had
  budget (attempt 2: install 1 Success; ladder attempt 1 broken-pipe
  exit 224 "Failure calling service activity: Broken pipe", attempt 2
  zombie ("Complete" exit 0, no Status line), attempt 3 "am start ok
  (Status: ok)");
- the slow-path total (boot ~600 + env-ready worst ~1500 + wall 1800
  = 3900+) exceeded the E2B Hobby total-lifetime cap — the sandbox
  died mid-flight (attempt 1: install 4 attempts — broken pipe x2,
  zombie outcome-window, Success; launch attempt 1 "Can't find
  service: activity" exit 20, attempt 2 "Error: Activity class
  {...MainActivity} does not exist" + pm-path=''; onboarding rounds
  5-35 ALL "RUN_ERROR: The sandbox was not found" — the loop hammered
  a corpse for 30 rounds; then "process-poll cut at age 3727s —
  reserving 480s for the evidence phases", 127 s PAST the 3600 s cap,
  death forensics all empty, exit -1).

Pinned here (no network, no e2b SDK, no credentials — the FakeClock +
ScriptedProvider doubles from test_labcli_reference, the REAL
AdbBridge, the real scenario corpus):

- PART A — the invariant pair, ONE margin arithmetic everywhere:
  (i)  the S002 wall COVERS the driver's internal budgets
       (LAUNCH_LADDER_WALL_WORST_S + ONB_WALL_BUDGET_S +
       EVIDENCE_TAIL_RESERVE_S + WALL_MARGIN_S = 450 + 900 + 480 + 90
       = 1920 — the wall is re-pegged 1800 → 1920);
  (ii) the sandbox lifetime SURVIVES the wall plus its honest end
       (ENV_READY_WORST_S + wall + DESTROY_MARGIN_S = 1500 + 1920 +
       60 = 3480 <= 3600). S003's pin is the (i)-class lower bound
       ("1920 <= 2400", the wall UNCHANGED — its (ii) shortfall is
       pinned as a documented fact, not hidden); S001 pins the
       launch-only formula (1020 <= 1800, unchanged); S004 is EXEMPT
       (its wall never executes live — the camera-fixture NO-OP).
- PART B — the install cap is LITERALLY 3 (lottery economics: the
  TCG broken-pipe install failures are sandbox-correlated, the
  fresh-sandbox outer retry is the independent draw): the 3rd failure
  aborts honestly, no 4th attempt ever runs.
- PART C — the death-abort: a round whose dump (error, body, or
  raising capture) or init-probe result carries a
  SANDBOX_DEATH_MARKERS signature aborts the loop AT ONCE with the
  verbatim line ("round n: SANDBOX DEATH — aborting (never hammer a
  dead sandbox)") — ZERO settles after the death round (the FakeClock
  proof); dump-unavailable rounds WITHOUT the signature keep the 010H
  settle-and-continue doctrine; the tap-label fallback's one dump
  gets the same check (emit + honest re-raise, never a tap); BOTH
  drivers share the loop (the 010F/G/H placement pattern).
- PART D — the governor's birth-anchored engagement: the process
  poll cuts at E2B_TOTAL_LIFETIME_CAP_S − LAUNCH_PROC_BUDGET_RESERVE_S
  − DESTROY_MARGIN_S = 3060 s TRUE sandbox age (the epoch is
  _Native.sandbox_t0 — pinned: t0+3060 cuts, t0+3000 does not, a
  round-top at t0+3599 still engages before the cap).

The 010H continuity pins (25 ANR rounds then a real control; the
wall-budget exhaustion line under the 40-round budget) stay green in
test_labcli_anr_patience.py — the death-abort and the governor
re-peg leave those paths untouched, and the full suite re-run is the
regression gate.
"""
from __future__ import annotations

import pytest
from labcli_helpers import REPO_ROOT
from test_labcli_anr_label import ANR_DUMP, ANR_WAIT_CENTER
from test_labcli_onboarding import CLEAN_DUMP
from test_labcli_reference import (
    FakeClock,
    ScriptedProvider,
    _make_driver,
    _make_xapk,
    _provision_request,
)
from tools.adb_bridge.bridge import AdbBridge, UnknownTargetError
from tools.lab_cli import e2b_live, reference_live
from tools.lab_cli.e2b_live import E2bLiveDriver
from tools.lab_cli.reference_live import (
    DESTROY_MARGIN_S,
    E2B_TOTAL_LIFETIME_CAP_S,
    ENV_READY_WORST_S,
    EVIDENCE_TAIL_RESERVE_S,
    INSTALL_MAX_ATTEMPTS,
    LAUNCH_LADDER_WALL_WORST_S,
    LAUNCH_PROC_BUDGET_RESERVE_S,
    ONB_MAX_ROUNDS,
    ONB_ROUND_SETTLE_S,
    ONB_WALL_BUDGET_S,
    WALL_MARGIN_S,
    ReferenceDriver,
    _Native,
    tap_label_discovery_fallback,
)
from tools.lab_cli.scenarios import LabCliError, resolve_scenario
from tools.lab_cli.steps import plan_steps

from lab.providers.e2b.bootstrap import ADB
from lab.providers.types import CaptureKind, CommandResult

#: The verbatim live death signature (the 2026-09-26 S002 attempt-1
#: shape: onboarding rounds 5-35 all served this text).
DEATH_TEXT = "RUN_ERROR: The sandbox was not found"

#: The reconciled wall peg — the formula sum, pinned as a literal so
#: a revert of EITHER side (the yaml or any constant) reddens.
S002_WALL_PEG_S = 1920

#: The governor engagement — cap − reserve − destroy margin, pinned
#: as a literal for the same reason.
ENGAGEMENT_AGE_S = 3060


def _scenario(stem: str):
    return resolve_scenario(stem, REPO_ROOT / "lab" / "scenarios")


def _bridge(provider: ScriptedProvider, env_id: str = "e2b-fake01",
            targets=None) -> AdbBridge:
    return AdbBridge(provider, env_id, default_timeout_s=120.0,
                     targets=targets)


# ------------------------------------------------- part A — the invariants

def test_s002_wall_repegged_to_the_reconciled_formula():
    """The S002 re-peg (the work order's corpus change — the ONLY one):
    the wall equals the formula sum (i) and the lifetime survives the
    wall plus its honest end (ii). The old 1800 wall failed (i) by
    exactly the evidence-tail + margin it starved (1800 < 1920) — the
    010H honest-exhaustion line can now fire with its complete
    evidence INSIDE the wall."""
    scenario = _scenario("S002")
    formula = (LAUNCH_LADDER_WALL_WORST_S + ONB_WALL_BUDGET_S
               + EVIDENCE_TAIL_RESERVE_S + WALL_MARGIN_S)
    # the constants side: 450 + 900 + 480 + 90 = 1920
    assert (LAUNCH_LADDER_WALL_WORST_S, ONB_WALL_BUDGET_S,
            EVIDENCE_TAIL_RESERVE_S, WALL_MARGIN_S) == (450, 900, 480, 90)
    assert formula == S002_WALL_PEG_S == 1920
    # the corpus side: the wall is re-pegged 1800 → 1920 (a revert of
    # the yaml reddens HERE, not on the live substrate)
    assert scenario.timeout_seconds == 1920
    # (i) the wall COVERS the driver's internal budgets
    assert scenario.timeout_seconds >= formula
    # (ii) the sandbox lifetime SURVIVES the wall + its honest end
    # (teardown's stop + destroy complete inside the cap)
    assert (ENV_READY_WORST_S + scenario.timeout_seconds
            + DESTROY_MARGIN_S) <= E2B_TOTAL_LIFETIME_CAP_S
    assert ENV_READY_WORST_S + S002_WALL_PEG_S + DESTROY_MARGIN_S == 3480


def test_s003_wall_lower_bound_the_work_orders_pin():
    """S003's pin (the work order's own words: "S003: 1920 <= 2400"):
    the (i)-class lower bound holds on S003's UNCHANGED wall. The
    honest shortfall is pinned, not hidden: the (ii) side does NOT
    bind for S003 (1500 + 2400 + 60 = 3960 > 3600) — a slow-path S003
    run cannot reach its own wall's exhaustion inside ONE sandbox
    lifetime. Re-pegging S003's wall is out of 010I scope (the S002
    yaml is the only corpus change the work order allows); for that
    class the lifetime governor (3060 s engagement, part D) and the
    death-abort (part C) own the honest termination."""
    scenario = _scenario("S003")
    formula = (LAUNCH_LADDER_WALL_WORST_S + ONB_WALL_BUDGET_S
               + EVIDENCE_TAIL_RESERVE_S + WALL_MARGIN_S)
    assert scenario.timeout_seconds == 2400            # UNCHANGED
    assert formula <= scenario.timeout_seconds          # "1920 <= 2400"
    # the documented shortfall — a future re-peg that fixes it must
    # flip this pin consciously
    assert (ENV_READY_WORST_S + scenario.timeout_seconds
            + DESTROY_MARGIN_S) > E2B_TOTAL_LIFETIME_CAP_S


def test_s001_launch_only_wall_unchanged():
    """S001 (application-launch — no onboarding step): the launch-only
    formula (ladder + evidence tail + margin = 450 + 480 + 90 = 1020)
    pins the lower bound; the wall itself is UNCHANGED at 1800 (the
    work order's "1020 <= 1800")."""
    scenario = _scenario("S001")
    launch_only = (LAUNCH_LADDER_WALL_WORST_S + EVIDENCE_TAIL_RESERVE_S
                   + WALL_MARGIN_S)
    assert launch_only == 1020
    assert scenario.timeout_seconds == 1800            # UNCHANGED
    assert launch_only <= scenario.timeout_seconds


def test_s004_exempt_its_wall_never_runs_live():
    """S004's EXEMPTION, documented mechanically: the camera fixture
    (clean-a4) makes the scheduler NO-OP single-document-capture on
    BOTH provider records (the camera_fixture requirement is
    unsatisfied — pinned by test_plan_s004_no_capable_provider in
    test_labcli_plan.py), so no live driver ever enforces
    meta.timeout_seconds for S004. The wall stays 2400 for the
    recording driver's substrate-free replay; the reconciliation
    invariants do not apply to a wall that never executes."""
    scenario = _scenario("S004")
    assert scenario.fixture.get("camera") == "clean-a4"   # the NO-OP cause
    assert scenario.timeout_seconds == 2400               # untouched


# ----------------------------------------------- part B — the install cap

def test_install_cap_is_three_attempts(monkeypatch, tmp_path):
    """PART B — the cap is LITERALLY 3 (lottery economics: within one
    sandbox the TCG broken-pipe install failures are CORRELATED — the
    2026-09-26 attempt 1 burned 4 in-sandbox attempts ≈ 25 min while
    attempt 2's fresh sandbox won on attempt 1 — so the FRESH-SANDBOX
    outer retry is the independent draw, and 3 bounded attempts span
    one full burst cycle while staying reconcilable with
    ENV_READY_WORST_S). The existing exhausted-attempts test follows
    the CONSTANT; this pin redds a revert to 8 by naming the number.
    """
    monkeypatch.setenv("E2B_API_KEY", "placeholder-not-a-credential")
    monkeypatch.delenv("CAMSCAN_APK_URL", raising=False)
    xapk = _make_xapk(tmp_path / "CamScanner_7.25.5.xapk")
    provider = ScriptedProvider()
    # every attempt fails fast with the package-service-down verdict
    provider.on("cat /root/install.out",
                "Can't find service: package\nEXIT_1\n")
    driver, _clock = _make_driver(provider, apk=xapk)
    with pytest.raises(LabCliError) as excinfo:
        driver.provision(_provision_request([], apk=xapk))
    # the honest attempt-counted abort, UNCHANGED shape — just earlier
    assert "after 3 bounded attempts" in str(excinfo.value)
    # the 3rd failure aborted: exactly THREE install launches, THREE
    # diagnostics blocks, and NO 4th attempt of any kind
    assert reference_live.INSTALL_MAX_ATTEMPTS == 3
    assert INSTALL_MAX_ATTEMPTS == 3
    assert len([c for c in provider.exec_log
                if "install-multiple" in c]) == 3
    assert len([c for c in provider.exec_log
                if "install attempt 3 diagnostics" in c]) == 1
    assert not any("install attempt 4" in c for c in provider.exec_log)
    # the clean abort destroyed the paid sandbox
    assert ("destroy", "e2b-fake01") in provider.ops


# ------------------------------------------- part C — the death-abort

class _DeadSandboxProvider(ScriptedProvider):
    """Every ui-hierarchy capture serves the expired-sandbox
    rejection — the 2026-09-26 S002 attempt-1 shape (rounds 5-35 all
    "RUN_ERROR: The sandbox was not found"; the bridge's ui_dump
    catches the raising capture into VerbResult.error, so the loop
    sees it on the not-ok path)."""

    def capture(self, env_id, kind, timeout=None):
        if kind is CaptureKind.ui_hierarchy:
            raise RuntimeError(DEATH_TEXT)
        return super().capture(env_id, kind, timeout)


class _DiesMidLoopProvider(ScriptedProvider):
    """A LIVING loop for `die_after` ui dumps, then the corpse's
    rejection — the attempt-1 shape exactly: real ANR rounds, then
    the sandbox dies mid-loop and every read serves the marker."""

    def __init__(self, die_after: int) -> None:
        super().__init__()
        self._die_after = die_after

    def capture(self, env_id, kind, timeout=None):
        if (kind is CaptureKind.ui_hierarchy
                and sum(1 for c in self.captures
                        if c is CaptureKind.ui_hierarchy)
                >= self._die_after):
            raise RuntimeError(DEATH_TEXT)
        return super().capture(env_id, kind, timeout)


class _BlindThenRecoveringProvider(ScriptedProvider):
    """The NON-death unavailable shape (the 010H overnight
    uiautomator-strain class: "ui hierarchy dump failed: " — NO
    death marker) for the first `blind_rounds` ui-dump CALLS, then a
    real clean dump: the settle-and-continue doctrine must survive
    the death-abort's arrival. (Call-counted, not capture-counted —
    a raising capture never reaches the base class's bookkeeping.)"""

    def __init__(self, blind_rounds: int) -> None:
        super().__init__()
        self._blind_rounds = blind_rounds
        self._ui_calls = 0

    def capture(self, env_id, kind, timeout=None):
        if kind is CaptureKind.ui_hierarchy:
            self._ui_calls += 1
            if self._ui_calls <= self._blind_rounds:
                raise RuntimeError("ui hierarchy dump failed: ")
        return super().capture(env_id, kind, timeout)


def _run_loop(provider: ScriptedProvider, clock: FakeClock) \
        -> tuple[bool, list[str]]:
    """The 010H test idiom: the reference driver's onboarding verb on
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


def test_onboarding_death_abort_first_round_zero_settles():
    """THE attempt-1 shape, met honestly: the FIRST round's dump
    serves the death signature → the verbatim death line, return
    False (the operational-failure shape), and — the FakeClock proof
    — ZERO settles beyond the one-off leading settle (the old
    behavior settled 12 s per round on a corpse for 30 rounds)."""
    provider = _DeadSandboxProvider()
    clock = FakeClock()
    ok, lines = _run_loop(provider, clock)
    assert ok is False
    # the verbatim death line
    assert any("onboarding round 1: SANDBOX DEATH — aborting "
               "(never hammer a dead sandbox)" in line for line in lines)
    # the OLD hammering line never fired, and no budget-exhaustion
    # verdict ran (the abort owns the termination)
    assert not any("ui dump unavailable" in line for line in lines)
    assert not any("wall budget" in line for line in lines)
    assert not any(f"discovery exhausted {ONB_MAX_ROUNDS}" in line
                   for line in lines)
    assert not any("onboarding complete" in line for line in lines)
    # ZERO settles after the death round: only the leading settle ran
    assert clock.slept == [2 * ONB_ROUND_SETTLE_S]
    # nothing was ever tapped on the corpse
    assert provider.interactions == []


def test_onboarding_death_abort_mid_loop_after_real_rounds():
    """Two REAL ANR rounds, then the sandbox dies mid-loop: the death
    abort fires on round 3 (the round whose observation is the
    corpse's answer) — the two Wait dismissals stand, the third round
    settles NOTHING, and the loop terminates as the operational
    failure."""
    provider = _DiesMidLoopProvider(die_after=2)
    provider.ui_xmls = [ANR_DUMP, ANR_DUMP]
    clock = FakeClock()
    ok, lines = _run_loop(provider, clock)
    assert ok is False
    # the two real rounds dismissed Wait; the corpse was never tapped
    assert [(a.x, a.y) for a in provider.interactions] == \
        [ANR_WAIT_CENTER] * 2
    assert any("round 1: ANR Wait dismissed" in line for line in lines)
    assert any("round 2: ANR Wait dismissed" in line for line in lines)
    # round 3's dump is the corpse's answer → the verbatim death line
    assert any("onboarding round 3: SANDBOX DEATH — aborting "
               "(never hammer a dead sandbox)" in line for line in lines)
    assert not any("round 3: ui dump unavailable" in line
                   for line in lines)
    # the settles: leading + rounds 1-2 ONLY (round 3 aborted first)
    assert clock.slept == [2 * ONB_ROUND_SETTLE_S,
                           ONB_ROUND_SETTLE_S, ONB_ROUND_SETTLE_S]


def test_onboarding_death_abort_init_probe_result():
    """The PROBE channel: round 5's checkpoint init probe comes back
    with the death signature (a corpse serves every read the same
    rejection — the probe included) → the probe line (the evidence of
    what the round saw) then the verbatim death line, abort BEFORE
    the round's dump, zero settles for the death round."""
    provider = ScriptedProvider()
    provider.ui_xmls = [ANR_DUMP]              # rounds 1-4: real ANRs
    provider.on("dumpsys window",
                CommandResult(-1, "", DEATH_TEXT, 1, "probe"))
    clock = FakeClock()
    ok, lines = _run_loop(provider, clock)
    assert ok is False
    # four real ANR rounds stood
    assert [(a.x, a.y) for a in provider.interactions] == \
        [ANR_WAIT_CENTER] * 4
    # round 5: the probe observation, then the verdict
    assert any("round 5: init probe — "
               f"{DEATH_TEXT}" in line for line in lines)
    assert any("onboarding round 5: SANDBOX DEATH — aborting "
               "(never hammer a dead sandbox)" in line for line in lines)
    # round 5 never dumped and never settled
    assert not any("round 5: ANR" in line for line in lines)
    assert clock.slept == [2 * ONB_ROUND_SETTLE_S] + \
        [ONB_ROUND_SETTLE_S] * 4


def test_onboarding_unavailable_without_signature_settles_continues():
    """The continuity boundary: a failed dump WITHOUT the death
    signature (the 010H overnight uiautomator-strain shape —
    "ui hierarchy dump failed: ") keeps the settle-and-continue
    doctrine: the round settles, consumes budget, and the loop goes
    on to complete on the recovering dump. The death-abort fires on
    the SIGNATURE, never on unavailability."""
    provider = _BlindThenRecoveringProvider(blind_rounds=2)
    provider.ui_xmls = [CLEAN_DUMP]
    clock = FakeClock()
    ok, lines = _run_loop(provider, clock)
    assert ok is True
    # two unavailable rounds (settled, continued) then completion
    failures = [line for line in lines if "ui dump unavailable" in line]
    assert len(failures) == 2
    assert any("ui hierarchy dump failed" in line for line in failures)
    assert any("round 3: no actionable control — onboarding complete"
               in line for line in lines)
    assert not any("SANDBOX DEATH" in line for line in lines)
    # the settles: leading + the two unavailable rounds (the
    # completion round settles nothing)
    assert clock.slept == [2 * ONB_ROUND_SETTLE_S,
                           ONB_ROUND_SETTLE_S, ONB_ROUND_SETTLE_S]


def test_tap_label_fallback_death_note_never_taps():
    """The 010G tap-label fallback's one dump gets the same check
    (the 010I shared-mechanism doctrine): a corpse's rejection on the
    fallback's dump → the death line on the diag channel, then the
    original UnknownTargetError re-raises (the honest step failure) —
    NEVER a tap on a corpse's garbage."""
    provider = _DeadSandboxProvider()
    bridge = _bridge(provider)
    lines: list[str] = []

    class _MissRegistry:
        """The registry stub the error's message-builder needs (ids
        only — the message is never asserted verbatim)."""

        @staticmethod
        def ids(app):
            return []

    error = UnknownTargetError("scan", "com.intsig.camscanner",
                               _MissRegistry())
    with pytest.raises(UnknownTargetError) as excinfo:
        tap_label_discovery_fallback(bridge, "scan", error,
                                     step_timeout=120,
                                     emit=lines.append, label="reference")
    assert excinfo.value is error          # the ORIGINAL error, re-raised
    assert provider.interactions == []     # never tapped the corpse
    assert any("tap 'scan' by label discovery: SANDBOX DEATH — "
               "aborting (never hammer a dead sandbox)" in line
               for line in lines)


def test_e2b_live_death_abort_parity(tmp_path):
    """The 010F/G/H placement pattern holds for 010I: BOTH drivers
    run the ONE shared ladder (module-attribute identity — the
    death-abort lives in reference_live beside it and is consumed by
    it), and behaviorally: the implementation driver's registry-miss
    onboarding path aborts on the death signature with the
    implementation-labelled verbatim line."""
    # the shared-object pin: one ladder, one death-abort
    assert e2b_live.onboarding_discovery_loop \
        is reference_live.onboarding_discovery_loop
    assert e2b_live.tap_label_discovery_fallback \
        is reference_live.tap_label_discovery_fallback
    # behavioral parity: the registry-miss onboarding path against a
    # dead sandbox aborts with the implementation label
    provider = _DeadSandboxProvider()
    registry = tmp_path / "targets-miss.yaml"
    registry.write_text(
        "schema: 1\nprofile:\n  device: pixel_4\ntargets: {}\napps: {}\n",
        encoding="utf-8")
    bridge = _bridge(provider, targets=registry)
    clock = FakeClock()
    driver = E2bLiveDriver(sleep=clock.sleep, monotonic=clock.monotonic)
    (plan,) = plan_steps(["complete-onboarding"], None)
    lines: list[str] = []
    ok = driver._invoke_plan(bridge, plan, "org.payswap.camscan",
                             120, lines.append)
    assert ok is False
    assert any("implementation: onboarding round 1: SANDBOX DEATH — "
               "aborting (never hammer a dead sandbox)" in line
               for line in lines)
    # the zero-extra-settle proof through the second driver too
    assert clock.slept == [2 * ONB_ROUND_SETTLE_S]


# ------------------------------------- part D — the governor engagement

def test_governor_engagement_arithmetic():
    """The reconciliation arithmetic, pinned: the poll engages at
    cap − reserve − destroy margin = 3060 s TRUE age (strictly inside
    the cap — never past 3600), the S002 formula sums to 1920, and
    the evidence reserve is ONE number (the alias discipline: the
    wall formula cites LAUNCH_PROC_BUDGET_RESERVE_S through
    EVIDENCE_TAIL_RESERVE_S, never a second 480)."""
    engagement = (E2B_TOTAL_LIFETIME_CAP_S - LAUNCH_PROC_BUDGET_RESERVE_S
                  - DESTROY_MARGIN_S)
    assert engagement == ENGAGEMENT_AGE_S == 3060
    # the engagement is strictly inside the cap: from any round-top
    # at age >= 3060 the governor cuts BEFORE another round runs, and
    # 3600 − 3060 = 540 s of absorption remain for the stalled rounds
    # of a dying transport (the 3727 s live cut overshoot was 127 s)
    assert engagement < E2B_TOTAL_LIFETIME_CAP_S
    assert DESTROY_MARGIN_S == 60
    # the alias discipline: ONE reserve number
    assert EVIDENCE_TAIL_RESERVE_S == LAUNCH_PROC_BUDGET_RESERVE_S == 480
    # the wall formula and the engagement cite the same family
    assert (LAUNCH_LADDER_WALL_WORST_S + ONB_WALL_BUDGET_S
            + EVIDENCE_TAIL_RESERVE_S + WALL_MARGIN_S) == 1920


def _poll(driver: ReferenceDriver, provider: ScriptedProvider,
          native: _Native) -> tuple[bool, list[str], list[str]]:
    lines: list[str] = []
    diag: list[str] = []
    ok = driver._launch_process_poll(
        provider, "e2b-fake01", ADB, "", diag.append, lines.append,
        budget_remaining_s=lambda: driver._sandbox_budget_remaining_s(
            native))
    return ok, lines, diag


def test_process_poll_engagement_epoch_pins():
    """PART D — the epoch pins: the cut's age is the TRUE age from
    the sandbox's BIRTH (_Native.sandbox_t0), and the engagement is
    3060 s: a round-top at t0+3000 RUNS (no cut), a round-top at
    t0+3060 CUTS (exactly at the engagement — the reserve AND the
    destroy margin both defended), and a round-top at t0+3599 (one
    breath before the cap) STILL engages — the governor never lets a
    round start past the engagement, and the engagement itself can
    never exceed the cap."""
    # t0+3000 — budget 600 s: the round RUNS and lands the process
    provider = ScriptedProvider()
    driver, clock = _make_driver(provider)
    native = _Native(provider, "e2b-fake01", ADB, "")
    native.sandbox_t0 = clock.now - 3000.0
    ok, lines, diag = _poll(driver, provider, native)
    assert ok is True                       # the ps read found the process
    assert any("app process up" in line for line in diag)
    assert not any("process-poll cut at age" in line for line in lines)

    # t0+3060 — budget 540 s = reserve + destroy: the cut engages
    # EXACTLY at the point, before the round's sleep/read
    provider = ScriptedProvider()
    driver, clock = _make_driver(provider)
    native = _Native(provider, "e2b-fake01", ADB, "")
    native.sandbox_t0 = clock.now - float(ENGAGEMENT_AGE_S)
    ok, lines, diag = _poll(driver, provider, native)
    assert ok is False                      # the honest unconfirmed fail
    assert any(f"process-poll cut at age {ENGAGEMENT_AGE_S}s"
               in line for line in lines)
    # the cut line names BOTH defended budgets (the next postmortem
    # is a read, not an inference)
    assert any(f"reserving {LAUNCH_PROC_BUDGET_RESERVE_S}s for the "
               f"evidence phases + {DESTROY_MARGIN_S}s destroy margin"
               in line for line in lines)
    assert any(f"E2B Hobby total-lifetime cap {E2B_TOTAL_LIFETIME_CAP_S}s"
               in line for line in lines)
    # ZERO ps rounds ran (the cut fired before round 1's sleep/read)
    assert not any("grep com.intsig.camscanner" in c
                   for c in provider.exec_log)

    # t0+3599 — one breath before the cap: the engagement STILL
    # fires (never past 3600: from any age >= 3060 the next round-top
    # cuts, and 3060 < 3600 by the arithmetic pin above)
    provider = ScriptedProvider()
    driver, clock = _make_driver(provider)
    native = _Native(provider, "e2b-fake01", ADB, "")
    native.sandbox_t0 = clock.now - 3599.0
    ok, lines, diag = _poll(driver, provider, native)
    assert ok is False
    assert any("process-poll cut at age 3599s" in line
               for line in lines)
    assert not any("grep com.intsig.camscanner" in c
                   for c in provider.exec_log)
