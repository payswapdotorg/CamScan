"""CAMSCAN-010H — init-patience extension + ANR attribution: the
contract tests (hermetic).

The overnight wall, verified by the lead from SIX live S002/S003
attempts (2026-09-26 00:00-04:35 UTC, all honest failures): every
attempt that won the install lottery AND the launch lottery (am
start ok, app process up, top-most com.intsig.camscanner instance)
then hit the SAME wall — the app never becomes dumpable-with-real-
UI. The observed shapes, verbatim emit lines:

- "ANR Wait dismissed at (540,1244)" repeatedly — the launch-phase
  ladder's 3 rounds + fallback, then the onboarding loop's in-loop
  rounds 1-4 — and the ANR dialog RE-APPEARS within seconds of each
  Wait dismissal;
- "ui dump unavailable (RuntimeError: ui hierarchy dump failed: )"
  for the remaining rounds (uiautomator itself strained);
- S003's tap-label fallback fired live and reported honestly: "no
  label match and the dump shows the ANR dialog — the app is hung
  (isn't responding), not missing the control".

VLM-verified nuance (the lead's analysis of the S001 screenshot
01-launch.png): the launch-time ANR dialog was the PIXEL LAUNCHER's
("Pixel Launcher isn't responding") with the CamScanner splash logo
behind it — system-wide strain under TCG, not only the app; the
21:08 S002-1 dump (pre-010G) showed the APP's own ANR ("CamScanner
isn't responding"). BOTH shapes occur.

THE THEORY under test: CamScanner 7.25.5's first-run init under TCG
(8 vCPU shared, software emulation) needs FAR more wall-clock
patience than the 010F budgets (12 rounds x 8 s ~= 2-4 min) — each
Wait dismissal gives the blocked threads more time, and the init may
complete given sustained patience inside the sandbox's ~3600 s
lifetime. Pinned here (no network, no e2b SDK, no credentials — the
FakeClock + ScriptedProvider doubles from test_labcli_reference, the
REAL AdbBridge, the real default targets.yaml):

- PART A — ATTRIBUTION: dump_anr_subject parses BOTH live shapes
  ("Pixel Launcher isn't responding" / "CamScanner isn't responding",
  raw or typographic apostrophe), None on non-ANR dumps and on
  title-less partial dialogs; the loop's ANR-round emits carry
  subject='…' with behavior UNCHANGED (Wait center tap, never Close
  app); the all-ANR exhaustion line carries the per-subject counts;
- PART B — INIT PATIENCE: the wall budget (ONB_WALL_BUDGET_S,
  FakeClock-injected, counted from the LOOP's start) ends the loop
  with the honest exhaustion line even under the 40-round budget;
  40 ANR rounds without wall-clock expiry exhaust with the subject
  counts; 25 ANR rounds then a real onboarding control still
  COMPLETES (the old 12-round budget would have failed — the
  regression pin for the theory); exactly one bounded init probe per
  5 rounds (max 8), adb-shell-prefixed and truncated;
- PART C — the one-off leading settle: exactly 2 x
  ONB_ROUND_SETTLE_S, applied once (the launch ladder itself is
  untouched — its pins live in test_labcli_reference);
- e2b_live parity: BOTH drivers run the ONE shared ladder (the
  010F/010G placement pattern) — the wall budget and the attribution
  fire identically through the implementation driver's registry-miss
  onboarding path.
"""
from __future__ import annotations

import re

from test_labcli_anr_label import (
    ANR_CLOSE_CENTER,
    ANR_DUMP,
    ANR_WAIT_CENTER,
    _rnode,
)
from test_labcli_onboarding import (
    CLEAN_DUMP,
    NEXT_DUMP,
    _dump,
)
from test_labcli_reference import FakeClock, ScriptedProvider, _make_driver
from tools.adb_bridge.bridge import AdbBridge
from tools.lab_cli import e2b_live, reference_live
from tools.lab_cli.e2b_live import E2bLiveDriver
from tools.lab_cli.reference_live import (
    ANR_WAIT_RES_ID,
    ONB_MAX_ROUNDS,
    ONB_PROBE_EVERY_ROUNDS,
    ONB_PROBE_MAX,
    ONB_PROBE_TRUNC_CHARS,
    ONB_ROUND_SETTLE_S,
    ONB_WALL_BUDGET_S,
    _Native,
    dump_anr_subject,
    dump_shows_anr,
)
from tools.lab_cli.steps import plan_steps

#: The SYSTEM-SIDE ANR shape — the S001 screenshot 01-launch.png as
#: VLM-verified by the lead: the PIXEL LAUNCHER's dialog ("Pixel
#: Launcher isn't responding") with the CamScanner splash behind it.
#: Same aerr button geometry as the app's own dialog (the ids are
#: system-owned, the component name lives in the TITLE).
PIXEL_ANR_DUMP = _dump(
    _rnode("Pixel Launcher isn't responding", clickable=False,
           bounds="[84,780][996,900]"),
    _rnode("Close app", rid="android:id/aerr_close",
           bounds="[148,1194][500,1294]"),     # center (324,1244)
    _rnode("Wait", rid=ANR_WAIT_RES_ID,
           bounds="[580,1194][932,1294]"),     # center (756,1244)
)

#: The typographic-apostrophe variant of the launcher title (the 010G
#: matcher's dot convention must attribute this too).
PIXEL_ANR_TYPO_DUMP = _dump(
    _rnode("Pixel Launcher isn’t responding", clickable=False,
           bounds="[84,780][996,900]"),
    _rnode("Wait", rid=ANR_WAIT_RES_ID,
           bounds="[580,1194][932,1294]"),
)

#: The partially-rendered system-side dialog: title + Close app ONLY
#: (the Wait button has not landed) — an ANR round that cannot
#: dismiss: it settles, counts, and the attribution still names the
#: launcher (never 'Close app').
PIXEL_ANR_NO_WAIT_DUMP = _dump(
    _rnode("Pixel Launcher isn't responding", clickable=False,
           bounds="[84,780][996,900]"),
    _rnode("Close app", rid="android:id/aerr_close",
           bounds="[148,1194][500,1294]"),
)

#: The title-less ANR shape: the aerr_wait id alone (the signature
#: matcher's id member) — present but UNATTRIBUTABLE (subject
#: 'unknown' in the loop, None from the parser). Bounds centered on
#: (540,1244) — the camera-campaign-proven Wait coordinate.
ANR_IDS_ONLY_DUMP = _dump(
    _rnode(rid=ANR_WAIT_RES_ID, bounds="[300,1194][780,1294]"),
)

#: The init-probe's scripted dumpsys window answer: multi-line,
#: longer than ONB_PROBE_TRUNC_CHARS once collapsed — the truncation
#: and one-line pins have real work to do.
FOCUS_LINES = (
    "mCurrentFocus=Window{8f2e u0 com.intsig.camscanner/"
    "com.intsig.camscanner.mainmenu.mainactivity.MainActivity}\n"
    "mFocusedApp=AppWindowToken{12ab token=Token{7c1d "
    "com.intsig.camscanner/"
    "com.intsig.camscanner.mainmenu.mainactivity.MainActivity}}\n"
    + "padding-token-0123456789 " * 8
)


def _bridge(provider: ScriptedProvider, env_id: str = "e2b-fake01",
            targets: str | None = None) -> AdbBridge:
    return AdbBridge(provider, env_id, default_timeout_s=120.0,
                     targets=targets)


class _WallJumpClock(FakeClock):
    """A FakeClock that leaps past the loop's wall budget MID-LOOP:
    after the n-th sleep it jumps ONB_WALL_BUDGET_S + slack forward,
    so the NEXT round-top check sees the budget expired. This is the
    honest shape of the overnight wall — under TCG the wall clock
    runs away from the settle cadence (uiautomator strain, dump
    failures), so the fake clock must be able to outrun the settles
    too (a plain FakeClock at 12 s/round can never reach 900 s inside
    40 rounds)."""

    def __init__(self, jump_after_sleeps: int) -> None:
        super().__init__()
        self._jump_after = jump_after_sleeps
        self._jumped = False

    def sleep(self, seconds: float) -> None:
        super().sleep(seconds)
        if not self._jumped and len(self.slept) >= self._jump_after:
            self._jumped = True
            self.now += ONB_WALL_BUDGET_S + 60.0


def _run_loop(provider: ScriptedProvider, clock: FakeClock) \
        -> tuple[bool, list[str]]:
    """The 010G test idiom: the reference driver's onboarding verb on
    the scripted provider, (ok, emitted lines) back."""
    driver, _ = _make_driver(provider)
    driver._sleep = clock.sleep          # the jump clock drives this run
    driver._monotonic = clock.monotonic
    bridge = _bridge(provider)
    lines: list[str] = []
    native = _Native(provider, "e2b-fake01", "", "")
    ok = driver._onboarding_complete(bridge, native,
                                     "com.intsig.camscanner",
                                     120, lines.append)
    return ok, lines


def _probe_rounds(lines: list[str]) -> list[int]:
    """The round numbers whose checkpoint init probe fired, in order."""
    rounds = [int(m.group(1)) for line in lines
              if (m := re.search(r"round (\d+): init probe", line))]
    return rounds


# ------------------------------------------- part A — ANR attribution

def test_anr_subject_attribution_shapes():
    """The attribution parser as implemented: BOTH live shapes parse —
    the system-side "Pixel Launcher isn't responding" (the S001
    screenshot 01-launch.png, VLM-verified over the CamScanner splash)
    and the app's own "CamScanner isn't responding" (the 21:08
    S002-1 dump, pre-010G) — the typographic apostrophe parses; a
    title-less partial dialog (the aerr ids alone) and every non-ANR
    dump yield None; and the sibling NEVER fires where the signature
    does not."""
    # the two live shapes
    assert dump_anr_subject(PIXEL_ANR_DUMP) == "Pixel Launcher"
    assert dump_anr_subject(ANR_DUMP) == "CamScanner"
    # typographic apostrophe (the 010G matcher's dot convention)
    assert dump_anr_subject(PIXEL_ANR_TYPO_DUMP) == "Pixel Launcher"
    assert dump_anr_subject(_dump(_rnode(
        "CamScanner isn’t responding", clickable=False))) == "CamScanner"
    # any other component parses (never hard-coded to two names)
    assert dump_anr_subject(_dump(_rnode(
        "System UI isn't responding", clickable=False))) == "System UI"
    # a title-less partial dialog: signature present, subject None
    assert dump_shows_anr(ANR_IDS_ONLY_DUMP) is True
    assert dump_anr_subject(ANR_IDS_ONLY_DUMP) is None
    # non-ANR dumps: None (the honest screens, the capture garbage)
    assert dump_anr_subject(CLEAN_DUMP) is None
    assert dump_anr_subject(NEXT_DUMP) is None
    assert dump_anr_subject("cat: /sdcard/window_dump.xml: "
                            "Permission denied\n") is None
    assert dump_anr_subject("") is None
    # the sibling relation: no signature → never a subject
    for dump in (CLEAN_DUMP, NEXT_DUMP, ""):
        assert not dump_shows_anr(dump)
        assert dump_anr_subject(dump) is None


def test_anr_round_emits_carry_subject_behavior_unchanged():
    """PART A.2 — attribution in the emits, behavior IDENTICAL for
    both subjects: [Pixel Launcher ANR, app ANR, un dismissable
    launcher ANR, permission, Next, clean] → the first two rounds
    dismiss Wait at its bounds-center with subject='Pixel Launcher'
    / subject='CamScanner' in the emit; the third (title present,
    Wait button absent) settles with the subject named and NO tap;
    "Close app" is NEVER tapped for EITHER subject; then the normal
    chain completes."""
    provider = ScriptedProvider()
    # [launcher ANR, app ANR, un-dismissable launcher ANR, Next, clean]
    provider.ui_xmls = [PIXEL_ANR_DUMP, ANR_DUMP, PIXEL_ANR_NO_WAIT_DUMP,
                        NEXT_DUMP, CLEAN_DUMP]
    driver, clock = _make_driver(provider)
    bridge = _bridge(provider)
    lines: list[str] = []
    native = _Native(provider, "e2b-fake01", "", "")
    ok = driver._onboarding_complete(bridge, native,
                                     "com.intsig.camscanner",
                                     120, lines.append)
    assert ok is True
    # the taps: Wait (launcher), Wait (app), then Next — Close app
    # NEVER tapped for EITHER subject
    assert [(a.x, a.y) for a in provider.interactions] == [
        ANR_WAIT_CENTER, ANR_WAIT_CENTER, (980, 2210)]
    assert not any((a.x, a.y) == ANR_CLOSE_CENTER
                   for a in provider.interactions)
    # the attributed emits (round 5 is a probe checkpoint too — its
    # probe line is pinned in the cadence test below)
    assert any("round 1: ANR Wait dismissed at (756,1244) "
               "(app recovering, subject='Pixel Launcher')" in line
               for line in lines)
    assert any("round 2: ANR Wait dismissed at (756,1244) "
               "(app recovering, subject='CamScanner')" in line
               for line in lines)
    # the un-dismissable round: subject named, Wait not located, no
    # tap, never 'Close app'
    assert any("round 3: ANR dialog present "
               "(subject='Pixel Launcher') — Wait button not located, "
               "settling (never 'Close app')" in line for line in lines)
    assert any("round 4: control 'Next' tapped at (980,2210)"
               in line for line in lines)
    assert any("round 5: no actionable control — onboarding complete"
               in line for line in lines)
    # settles: the leading settle + rounds 1-4 (the completion round
    # settles nothing)
    assert clock.slept == [2 * ONB_ROUND_SETTLE_S] + \
        [ONB_ROUND_SETTLE_S] * 4


def test_anr_round_unknown_subject_when_title_unparseable():
    """The honest attribution fallback: a dump whose ANR signature is
    id-only (the title has not landed) dismisses Wait with
    subject='unknown' — never a crash, never a guess."""
    provider = ScriptedProvider()
    provider.ui_xmls = [ANR_IDS_ONLY_DUMP, CLEAN_DUMP]
    driver, clock = _make_driver(provider)
    bridge = _bridge(provider)
    lines: list[str] = []
    native = _Native(provider, "e2b-fake01", "", "")
    ok = driver._onboarding_complete(bridge, native,
                                     "com.intsig.camscanner",
                                     120, lines.append)
    assert ok is True
    assert [(a.x, a.y) for a in provider.interactions] == [(540, 1244)]
    assert any("round 1: ANR Wait dismissed at (540,1244) "
               "(app recovering, subject='unknown')" in line
               for line in lines)
    assert any("round 2: no actionable control — onboarding complete"
               in line for line in lines)
    assert clock.slept == [2 * ONB_ROUND_SETTLE_S, ONB_ROUND_SETTLE_S]


# --------------------------------------- part B — budgets + probes

def test_wall_budget_exhaustion_honest_fail():
    """PART B.1 — the WALL budget (FakeClock-injected, counted from
    the LOOP's start) ends the loop with the honest exhaustion line
    EVEN UNDER the 40-round budget: the clock leaps past
    ONB_WALL_BUDGET_S after round 5's settle → round 6's check ends
    the loop: the wall line names the budget and the rounds spent,
    the dedicated ANR line carries the attribution counts (the ANR
    persisted into the exhaustion), and the round-budget line never
    fires."""
    provider = ScriptedProvider()
    provider.ui_xmls = [ANR_DUMP]              # repeats every round
    clock = _WallJumpClock(jump_after_sleeps=6)
    ok, lines = _run_loop(provider, clock)
    assert ok is False
    # five ANR rounds ran (round 6 ended on the wall check, before
    # its dump): five Wait dismissals, never Close
    assert [(a.x, a.y) for a in provider.interactions] == \
        [ANR_WAIT_CENTER] * 5
    # the honest wall-exhaustion line (the budget + the rounds spent)
    assert any(f"wall budget {ONB_WALL_BUDGET_S}s exhausted "
               "after 5 rounds — honest failure" in line
               for line in lines)
    # the ANR persisted into the exhaustion → the dedicated line with
    # the per-subject counts
    assert any("app ANR-looping — budget exhausted, honest fail "
               "(rounds: CamScanner=5)" in line for line in lines)
    # the round budget NEVER fired (5 of 40 rounds) and no completion
    assert not any(f"discovery exhausted {ONB_MAX_ROUNDS} rounds"
                   in line for line in lines)
    assert not any("onboarding complete" in line for line in lines)
    # the checkpoint probe fired exactly once (round 5 — the only
    # 5-multiple among the executed rounds)
    assert _probe_rounds(lines) == [5]
    # the wall budget counts from the LOOP's start: leading settle +
    # five round settles + the jump (900 + 60) is what expired it
    assert len(clock.slept) == 6


def test_wall_budget_exhaustion_without_anr_no_anr_line():
    """PART B.2's negative side: the dedicated ANR line fires ONLY
    when a budget dies while the ANR persists — a wall exhaustion
    with NO ANR round behind it (dump-unavailable rounds, the
    overnight uiautomator-strain shape) emits the wall line alone.
    The failed captures still settle-and-continue and still consume
    the wall budget."""
    from lab.providers.types import CaptureKind

    class _FailingDumpProvider(ScriptedProvider):
        def capture(self, env_id, kind, timeout=None):
            if kind is CaptureKind.ui_hierarchy:
                raise RuntimeError("ui hierarchy dump failed: ")
            return super().capture(env_id, kind, timeout)

    provider = _FailingDumpProvider()
    clock = _WallJumpClock(jump_after_sleeps=6)
    ok, lines = _run_loop(provider, clock)
    assert ok is False
    assert provider.interactions == []        # nothing was ever tapped
    # five dump-unavailable rounds, then the wall line
    failures = [line for line in lines if "ui dump unavailable" in line]
    assert len(failures) == 5
    assert any(f"wall budget {ONB_WALL_BUDGET_S}s exhausted "
               "after 5 rounds — honest failure" in line
               for line in lines)
    # NO ANR line (nothing attributed — the ANR never showed) and no
    # round-budget line
    assert not any("ANR-looping" in line for line in lines)
    assert not any(f"discovery exhausted {ONB_MAX_ROUNDS} rounds"
                   in line for line in lines)


def test_round_budget_exhaustion_subject_counts():
    """PART A.3 + B — all-ANR ROUND exhaustion without wall-clock
    expiry: 40 ANR rounds (26 app + 14 launcher — BOTH overnight
    shapes in one loop) → 40 Wait dismissals, the dedicated line with
    the per-subject counts (deterministic order: count descending),
    and NO wall line (the fake clock only ever reached 504 s)."""
    provider = ScriptedProvider()
    provider.ui_xmls = [ANR_DUMP] * 26 + [PIXEL_ANR_DUMP] * 14
    driver, clock = _make_driver(provider)
    bridge = _bridge(provider)
    lines: list[str] = []
    native = _Native(provider, "e2b-fake01", "", "")
    ok = driver._onboarding_complete(bridge, native,
                                     "com.intsig.camscanner",
                                     120, lines.append)
    assert ok is False
    # every round dismissed Wait (never Close), 26 app + 14 launcher
    assert [(a.x, a.y) for a in provider.interactions] == \
        [ANR_WAIT_CENTER] * ONB_MAX_ROUNDS
    app_rounds = [line for line in lines if "subject='CamScanner'" in line]
    launcher_rounds = [line for line in lines
                       if "subject='Pixel Launcher'" in line]
    assert len(app_rounds) == 26
    assert len(launcher_rounds) == 14
    # the exhaustion line with the per-subject counts
    assert any("app ANR-looping — budget exhausted, honest fail "
               "(rounds: CamScanner=26, Pixel Launcher=14)" in line
               for line in lines)
    # the wall budget never fired (24 + 40 x 12 = 504 s < 900 s)
    assert not any("wall budget" in line for line in lines)
    assert not any("onboarding complete" in line for line in lines)
    assert clock.slept == [2 * ONB_ROUND_SETTLE_S] + \
        [ONB_ROUND_SETTLE_S] * ONB_MAX_ROUNDS


def test_patience_continuity_25_anr_rounds_then_real_control():
    """THE REGRESSION PIN for the patience theory: 25 ANR rounds and
    THEN a real onboarding control — the loop KEEPS GOING through all
    25 ANRs (the old 12-round budget would have exhausted at round
    12 and failed honestly), taps the round-26 control, and completes
    on the round-27 clean dump. An honest PASS when patience wins —
    exactly what the overnight runs never got the budget to observe."""
    provider = ScriptedProvider()
    provider.ui_xmls = [ANR_DUMP] * 25 + [NEXT_DUMP, CLEAN_DUMP]
    driver, clock = _make_driver(provider)
    bridge = _bridge(provider)
    lines: list[str] = []
    native = _Native(provider, "e2b-fake01", "", "")
    ok = driver._onboarding_complete(bridge, native,
                                     "com.intsig.camscanner",
                                     120, lines.append)
    assert ok is True
    dismissals = [line for line in lines if "ANR Wait dismissed" in line]
    assert len(dismissals) == 25            # > the old 12-round budget
    # 25 Wait dismissals then the real control's tap — never Close
    assert [(a.x, a.y) for a in provider.interactions] == \
        [ANR_WAIT_CENTER] * 25 + [(980, 2210)]
    assert not any((a.x, a.y) == ANR_CLOSE_CENTER
                   for a in provider.interactions)
    # the loop KEPT GOING: the real control acted on round 26, the
    # completion verdict on round 27
    assert any("round 26: control 'Next' tapped at (980,2210)"
               in line for line in lines)
    assert any("round 27: no actionable control — onboarding complete"
               in line for line in lines)
    # no exhaustion verdict of either budget
    assert not any("ANR-looping" in line for line in lines)
    assert not any(f"discovery exhausted {ONB_MAX_ROUNDS} rounds"
                   in line for line in lines)
    assert not any("wall budget" in line for line in lines)
    # the checkpoint probes fired on every executed 5-multiple
    assert _probe_rounds(lines) == [5, 10, 15, 20, 25]
    # the wall clock only reached 24 + 26 x 12 = 336 s
    assert clock.slept == [2 * ONB_ROUND_SETTLE_S] + \
        [ONB_ROUND_SETTLE_S] * 26


def test_probe_cadence_bounded_truncated_adb_prefixed():
    """PART B.3 — the per-checkpoint init probes: EXACTLY one probe
    per ONB_PROBE_EVERY_ROUNDS rounds (5, 10, … 40), never more than
    ONB_PROBE_MAX (8) total across the whole 40-round budget; each
    probe is ONE line carrying the dumpsys-window focus lines
    (whitespace-collapsed, truncated to ONB_PROBE_TRUNC_CHARS); and
    the command carries the adb-shell prefix (the 010E lesson — a
    bare Linux-side dumpsys is deterministically 'command not
    found')."""
    provider = ScriptedProvider()
    provider.ui_xmls = [ANR_DUMP]              # the full 40 rounds
    provider.on("dumpsys window", FOCUS_LINES)
    driver, _clock = _make_driver(provider)
    bridge = _bridge(provider)
    lines: list[str] = []
    native = _Native(provider, "e2b-fake01", "", "")
    ok = driver._onboarding_complete(bridge, native,
                                     "com.intsig.camscanner",
                                     120, lines.append)
    assert ok is False                        # all-ANR exhaustion
    # the cadence: one probe per 5 rounds, exactly the 5-multiples of
    # the executed rounds, capped at ONB_PROBE_MAX
    rounds = _probe_rounds(lines)
    assert rounds == [r for r in range(ONB_PROBE_EVERY_ROUNDS,
                                       ONB_MAX_ROUNDS + 1,
                                       ONB_PROBE_EVERY_ROUNDS)]
    assert len(rounds) == 8
    assert len(rounds) <= ONB_PROBE_MAX
    probe_lines = [line for line in lines if "init probe" in line]
    assert len(probe_lines) == len(rounds)
    expected = " ".join(FOCUS_LINES.split())[:ONB_PROBE_TRUNC_CHARS]
    assert len(" ".join(FOCUS_LINES.split())) > ONB_PROBE_TRUNC_CHARS
    assert len(expected) == ONB_PROBE_TRUNC_CHARS
    for line in probe_lines:
        assert "\n" not in line              # ONE line per probe
        assert "init probe — " in line
        payload = line.split("init probe — ", 1)[1]
        assert payload == expected           # collapsed + truncated
        assert "mCurrentFocus=Window{8f2e u0 com.intsig.camscanner/" \
            in payload                       # the focus text landed
    # the 010E lesson: the probe command ran through the adb shell
    assert ("adb shell dumpsys window | grep -E "
            "'mCurrentFocus|mFocusedApp'") in provider.exec_log
    assert not any(cmd.startswith("dumpsys window")
                   for cmd in provider.exec_log)


# --------------------------------------- part C — the leading settle

def test_first_round_double_settle_applied_once():
    """PART C.1 — the one-off leading settle: the app's first breath
    before the first dump is EXACTLY 2 x ONB_ROUND_SETTLE_S, applied
    ONCE — a single-round completion run settles nothing else, and a
    full 40-round exhaustion run carries exactly one doubled entry at
    the head. The settle lives IN THE LOOP (the launch ladder and
    _anr_ladder are untouched — their pins live in
    test_labcli_reference; this is the ONLY launch-adjacent change)."""
    # the single-round completion: the leading settle is the ONLY sleep
    provider = ScriptedProvider()
    provider.ui_xmls = [CLEAN_DUMP]
    driver, clock = _make_driver(provider)
    bridge = _bridge(provider)
    lines: list[str] = []
    native = _Native(provider, "e2b-fake01", "", "")
    ok = driver._onboarding_complete(bridge, native,
                                     "com.intsig.camscanner",
                                     120, lines.append)
    assert ok is True
    assert provider.interactions == []        # nothing to tap
    assert clock.slept == [2 * ONB_ROUND_SETTLE_S]
    # the full-budget exhaustion: ONE doubled entry, then the cadence
    provider = ScriptedProvider()
    provider.ui_xmls = [ANR_DUMP]
    driver, clock = _make_driver(provider)
    bridge = _bridge(provider)
    lines = []
    native = _Native(provider, "e2b-fake01", "", "")
    ok = driver._onboarding_complete(bridge, native,
                                     "com.intsig.camscanner",
                                     120, lines.append)
    assert ok is False
    assert clock.slept[0] == 2 * ONB_ROUND_SETTLE_S
    assert clock.slept.count(2 * ONB_ROUND_SETTLE_S) == 1
    assert clock.slept[1:] == [ONB_ROUND_SETTLE_S] * ONB_MAX_ROUNDS


# --------------------------------------------------------- e2b parity

def test_e2b_live_wall_budget_attribution_parity(tmp_path):
    """The 010F/010G placement pattern holds for 010H: BOTH drivers
    run the ONE shared ladder (module-attribute identity — the wall
    budget and the attribution live in reference_live beside it and
    are consumed by it), and behaviorally: the implementation
    driver's registry-miss onboarding path, driven on the SAME
    FakeClock, produces the SAME wall-budget exhaustion and
    per-subject attribution emits (label 'implementation')."""
    # the shared-object pin: one ladder, one set of constants
    assert e2b_live.onboarding_discovery_loop \
        is reference_live.onboarding_discovery_loop
    assert e2b_live.tap_label_discovery_fallback \
        is reference_live.tap_label_discovery_fallback
    assert reference_live.ONB_WALL_BUDGET_S == ONB_WALL_BUDGET_S
    assert reference_live.dump_anr_subject is dump_anr_subject
    # behavioral parity: the registry-miss onboarding path under the
    # jumping clock — wall exhaustion + attribution, implementation label
    provider = ScriptedProvider()
    provider.ui_xmls = [PIXEL_ANR_DUMP]
    registry = tmp_path / "targets-miss.yaml"
    registry.write_text(
        "schema: 1\nprofile:\n  device: pixel_4\ntargets: {}\napps: {}\n",
        encoding="utf-8")
    bridge = _bridge(provider, targets=registry)
    clock = _WallJumpClock(jump_after_sleeps=6)
    driver = E2bLiveDriver(sleep=clock.sleep, monotonic=clock.monotonic)
    (plan,) = plan_steps(["complete-onboarding"], None)
    lines: list[str] = []
    ok = driver._invoke_plan(bridge, plan, "org.payswap.camscan",
                             120, lines.append)
    assert ok is False
    assert [(a.x, a.y) for a in provider.interactions] == \
        [ANR_WAIT_CENTER] * 5
    assert any(f"implementation: onboarding wall budget "
               f"{ONB_WALL_BUDGET_S}s exhausted after 5 rounds "
               "— honest failure" in line for line in lines)
    assert any("implementation: onboarding app ANR-looping — budget "
               "exhausted, honest fail (rounds: Pixel Launcher=5)"
               in line for line in lines)
    assert not any("onboarding complete" in line for line in lines)
