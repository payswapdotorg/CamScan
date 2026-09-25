"""CAMSCAN-010G — ANR-aware discovery loops + tap-label discovery
fallback: the contract tests (hermetic).

TWO deterministic blockers from the lead's evidence review of the
FIRST live 010F exposure, both pinned here (no network, no e2b SDK,
no credentials — the FakeClock + ScriptedProvider doubles from
test_labcli_reference, the REAL AdbBridge and the real default
targets.yaml):

1. THE 010F FALSE POSITIVE (run 20260925T210842Z-S002-live, sandbox
   e2b-9927a5e9, 21:08-21:58 UTC; evidence record invalidated by the
   lead in 8e24fad, verbatim dump preserved here): the run walked the
   whole chain (boot, install attempt-3 Success, facts stash, GMS
   restore, launch ladder attempt-3 ok, ANR ladder 3 rounds +
   fallback) and then the onboarding discovery loop's round-1 dump was
   the Android ANR dialog —

       texts: "CamScanner isn't responding", "Close app", "Wait"
       clickables: android:id/aerr_close (text "Close app"),
                   android:id/aerr_wait  (text "Wait")

   — and "Wait"/"Close app" are correctly NOT in the affirmative set,
   so the 010F completion rule fired ("valid dump + no permission +
   no affirmative → complete") on a HUNG app. The ANR ladder runs
   ONCE at launch; under TCG the dialogs RECUR during onboarding.
   Pinned: the loop's ANR rounds (Wait tapped at its bounds-center,
   NEVER Close app), the ANR-exhaustion honest fail, and the
   ANR-can-never-complete rule (even at the last round).

2. THE S003 tap:scan DETERMINISTIC DEATH (found by the lead's code
   review BEFORE it burned a sandbox — S003 was stopped mid-
   provisioning): step 2 is ``tap: scan``; steps.py maps tap →
   tap_semantic("scan"); the app scope com.intsig.camscanner is EMPTY
   by doctrine and the global scope holds the permission ids only →
   GUARANTEED UnknownTargetError. Pinned: the label-discovery
   fallback's full path (fresh dump → label needle → bounds-center
   tap → emit), the needle ladder, the no-match re-raise, the
   registry-first order (registered ids never reach the fallback),
   and the e2b-live parity.
"""
from __future__ import annotations

import pytest
from test_labcli_onboarding import (
    CLEAN_DUMP,
    NEXT_DUMP,
    PERMISSION_DUMP,
    TRAP_DUMP,
    _dump,
    _node,
)
from test_labcli_reference import FakeClock, ScriptedProvider, _make_driver
from tools.adb_bridge.bridge import AdbBridge, UnknownTargetError
from tools.lab_cli.e2b_live import E2bLiveDriver
from tools.lab_cli.reference_live import (
    ANR_WAIT_RES_ID,
    ONB_MAX_ROUNDS,
    ONB_ROUND_SETTLE_S,
    _label_needles,
    _Native,
    _onb_anr_wait_center,
    _onb_clickable_nodes,
    _onb_round_action,
    dump_shows_anr,
)
from tools.lab_cli.steps import plan_steps

#: The S001 48-byte capture-flap garbage (the hardened capture's
#: failure class — must never read as an ANR signature).
S001_EVIDENCE_GARBAGE = "cat: /sdcard/window_dump.xml: Permission denied\n"

#: ANR-dialog button geometry (plausible aerr-dialog placement around
#: the camera-campaign-proven Wait coordinate (540, 1244)): the Close
#: and Wait buttons sit side by side, centers far apart so a wrong-
#: button tap can never alias a right one in the assertions.
ANR_CLOSE_CENTER = (324, 1244)
ANR_WAIT_CENTER = (756, 1244)


def _rnode(text: str = "", desc: str = "", rid: str = "",
           clickable: bool = True,
           bounds: str = "[0,0][1080,2280]") -> str:
    """A dump node WITH a resource-id (the 010F ``_node`` helper plus
    the attribute the aerr buttons carry)."""
    return (f'<node text="{text}" content-desc="{desc}" '
            f'resource-id="{rid}" '
            f'clickable="{"true" if clickable else "false"}" '
            f'bounds="{bounds}"/>')


#: The 20260925T210842Z-S002-live false positive, verbatim shape
#: (title + Close app + Wait, the aerr ids on the buttons).
ANR_DUMP = _dump(
    _rnode("CamScanner isn't responding", clickable=False,
           bounds="[84,780][996,900]"),
    _rnode("Close app", rid="android:id/aerr_close",
           bounds="[148,1194][500,1294]"),     # center (324,1244)
    _rnode("Wait", rid=ANR_WAIT_RES_ID,
           bounds="[580,1194][932,1294]"),     # center (756,1244)
)

#: The partially-rendered ANR variant (title + Wait only, no Close):
#: the purest "dump that is ONLY the ANR dialog" — under the removed
#: ANR check its lone "Wait" clickable reads as completion (the
#: false-positive class), so it pins the last-round rule hard.
ANR_WAIT_ONLY_DUMP = _dump(
    _rnode("CamScanner isn't responding", clickable=False,
           bounds="[84,780][996,900]"),
    _rnode("Wait", rid=ANR_WAIT_RES_ID,
           bounds="[580,1194][932,1294]"),     # center (756,1244)
)

#: The main screen carrying the S003 semantic intent as a LABEL: a
#: clickable "Scan" control (what ``tap: scan`` means on the real
#: screen — content-desc in production shape, text here for the
#: needle's raw rung).
SCAN_DUMP = _dump(
    _rnode("CamScanner", clickable=False, bounds="[84,120][500,200]"),
    _rnode("Scan", clickable=True,
           bounds="[440,2060][640,2160]"),     # center (540,2110)
)


def _bridge(provider: ScriptedProvider, env_id: str = "e2b-fake01",
            targets: str | None = None) -> AdbBridge:
    return AdbBridge(provider, env_id, default_timeout_s=120.0,
                     targets=targets)


def _ui_captures(provider: ScriptedProvider) -> list[tuple[str, str]]:
    """Every ui-hierarchy capture op in provider call order (the
    lab-cli test idiom for "a fresh dump command ran")."""
    return [op for op in provider.ops
            if op[0] == "capture" and op[1] == "ui_hierarchy"]


# ------------------------------------------------- ANR signature matcher

def test_anr_signature_matcher_shapes():
    """The signature as implemented: the "isn't responding" title (raw
    or typographic apostrophe — the dot), the aerr_wait id, the
    aerr_close id (a partially-rendered dialog is STILL an ANR round),
    each ALONE suffices; normal screens, permission dialogs and the
    capture-flap garbage never match."""
    # title alone (typographic apostrophe variance)
    assert dump_shows_anr(_dump(_node("App isn’t responding",
                                      clickable=False))) is True
    # the verbatim false-positive dump
    assert dump_shows_anr(ANR_DUMP) is True
    # aerr_wait id alone (no title text at all)
    assert dump_shows_anr(_dump(_rnode(rid=ANR_WAIT_RES_ID))) is True
    # aerr_close id alone (the Wait button has not landed yet)
    assert dump_shows_anr(_dump(_rnode("Close app",
                                       rid="android:id/aerr_close"))) is True
    # negatives: the honest screens and the garbage capture
    assert dump_shows_anr(ANR_WAIT_ONLY_DUMP) is True
    assert dump_shows_anr(PERMISSION_DUMP) is False
    assert dump_shows_anr(NEXT_DUMP) is False
    assert dump_shows_anr(CLEAN_DUMP) is False
    assert dump_shows_anr(S001_EVIDENCE_GARBAGE) is False
    assert dump_shows_anr("") is False


def test_anr_wait_center_never_close():
    """The Wait-button locator: the aerr_wait resource-id wins, a
    text="Wait" node is the fallback, android:id/aerr_close NEVER
    matches either criterion, and an unlocatable Wait yields None
    (the round settles, never taps blind, never taps Close)."""
    assert _onb_anr_wait_center(ANR_DUMP) == ANR_WAIT_CENTER
    # title + Wait-only (the partially-rendered variant) still lands
    assert _onb_anr_wait_center(ANR_WAIT_ONLY_DUMP) == ANR_WAIT_CENTER
    # text="Wait" fallback (no aerr resource-ids — the _anr_ladder idiom)
    assert _onb_anr_wait_center(_dump(
        _node("Wait", clickable=True, bounds="[100,100][300,200]"),
    )) == (200, 150)
    # Close button alone: never a tap target
    assert _onb_anr_wait_center(_dump(
        _rnode("Close app", rid="android:id/aerr_close",
               bounds="[148,1194][500,1294]"),
    )) is None
    # zero-area Wait node is not tappable → None (settle, never blind-tap)
    assert _onb_anr_wait_center(_dump(
        _rnode("Wait", rid=ANR_WAIT_RES_ID,
               bounds="[756,1244][756,1244]"),
    )) is None
    # unparseable dump → None
    assert _onb_anr_wait_center(S001_EVIDENCE_GARBAGE) is None


def test_close_app_is_hard_excluded_second_layer():
    """The second-layer guard: even through the clickable-node scan
    (the path the loop takes when the ANR check is hypothetically
    removed), the ANR dialog is NOT completion — "close app" is in the
    hard exclusion set, so the round reads as refused, never as
    "no actionable control = complete" (the pre-010G false-positive
    class is dead at BOTH layers)."""
    assert _onb_round_action(_onb_clickable_nodes(ANR_DUMP)) == \
        ("excluded", "Close app", ANR_CLOSE_CENTER)


# ------------------------------------------------- ANR-aware loop rounds

def test_anr_rounds_dismiss_wait_then_complete():
    """The mandated full path: [ANR, ANR, permission, "Next", clean] →
    two ANR Wait dismissals at the Wait bounds-center, then the allow
    tap, then Next, then completion. The ANR taps land on aerr_wait's
    center, NEVER aerr_close's; one emit line per round; TCG-paced
    settles after every acting round."""
    provider = ScriptedProvider()
    provider.ui_xmls = [ANR_DUMP, ANR_DUMP, PERMISSION_DUMP, NEXT_DUMP,
                        CLEAN_DUMP]
    driver, clock = _make_driver(provider)
    bridge = _bridge(provider)
    lines: list[str] = []
    native = _Native(provider, "e2b-fake01", "", "")
    ok = driver._onboarding_complete(bridge, native, "com.intsig.camscanner",
                                     120, lines.append)
    assert ok is True
    # the taps are EXACTLY the bounds-centers: Wait twice (aerr_wait,
    # never aerr_close), then the permission affirmative, then Next
    assert [(a.x, a.y) for a in provider.interactions] == [
        ANR_WAIT_CENTER, ANR_WAIT_CENTER, (540, 1454), (980, 2210)]
    assert not any((a.x, a.y) == ANR_CLOSE_CENTER
                   for a in provider.interactions)
    # the per-round emit lines
    assert any("onboarding round 1: ANR Wait dismissed at (756,1244) "
               "(app recovering)" in line for line in lines)
    assert any("onboarding round 2: ANR Wait dismissed at (756,1244) "
               "(app recovering)" in line for line in lines)
    assert any("onboarding round 3: permission granted at (540,1454)"
               in line for line in lines)
    assert any("onboarding round 4: control 'Next' tapped at (980,2210)"
               in line for line in lines)
    assert any("onboarding round 5: no actionable control "
               "— onboarding complete" in line for line in lines)
    # one settle after each acting round (the completion round: none)
    assert clock.slept == [ONB_ROUND_SETTLE_S] * 4


def test_anr_exhaustion_honest_fail():
    """ANR dialogs persisting through the ENTIRE budget: every round
    dismisses Wait at its center, the loop returns False with the
    dedicated ANR-exhaustion line — the app cannot stay responsive,
    that IS the observation — and "complete" is never emitted."""
    provider = ScriptedProvider()
    provider.ui_xmls = [ANR_DUMP]              # repeats every round
    driver, clock = _make_driver(provider)
    bridge = _bridge(provider)
    lines: list[str] = []
    native = _Native(provider, "e2b-fake01", "", "")
    ok = driver._onboarding_complete(bridge, native, "com.intsig.camscanner",
                                     120, lines.append)
    assert ok is False
    # every round was an ANR round: 12 Wait dismissals at the Wait
    # center, never Close
    assert [(a.x, a.y) for a in provider.interactions] == \
        [ANR_WAIT_CENTER] * ONB_MAX_ROUNDS
    assert not any((a.x, a.y) == ANR_CLOSE_CENTER
                   for a in provider.interactions)
    dismissals = [line for line in lines if "ANR Wait dismissed" in line]
    assert len(dismissals) == ONB_MAX_ROUNDS
    assert any(f"round {ONB_MAX_ROUNDS}: ANR Wait dismissed at (756,1244)"
               in line for line in lines)
    # the dedicated honest-exhaustion line; never a completion verdict
    assert any("app ANR-looping — budget exhausted, honest fail"
               in line for line in lines)
    assert not any("onboarding complete" in line for line in lines)
    assert clock.slept == [ONB_ROUND_SETTLE_S] * ONB_MAX_ROUNDS


def test_anr_can_never_complete_even_at_last_round():
    """The completion-rule pin: a dump that is ONLY the ANR dialog
    must never produce a completion verdict — even at the LAST round
    of the budget. Eleven trap rounds keep the loop honest, and the
    round-12 ANR dialog is dismissed (Wait tapped), never read as
    "no actionable control = complete" (the 010G false-positive
    class)."""
    provider = ScriptedProvider()
    provider.ui_xmls = [TRAP_DUMP] * (ONB_MAX_ROUNDS - 1) + [ANR_WAIT_ONLY_DUMP]
    driver, clock = _make_driver(provider)
    bridge = _bridge(provider)
    lines: list[str] = []
    native = _Native(provider, "e2b-fake01", "", "")
    ok = driver._onboarding_complete(bridge, native, "com.intsig.camscanner",
                                     120, lines.append)
    assert ok is False
    # the ONLY tap is the round-12 Wait dismissal — the traps were
    # never tapped, the ANR round was never completion
    assert [(a.x, a.y) for a in provider.interactions] == [ANR_WAIT_CENTER]
    assert any(f"onboarding round {ONB_MAX_ROUNDS}: ANR Wait dismissed "
               "at (756,1244) (app recovering)" in line for line in lines)
    assert not any("onboarding complete" in line for line in lines)
    # mixed rounds (11 trap + 1 ANR): the generic honest exhaustion
    assert any(f"discovery exhausted {ONB_MAX_ROUNDS} rounds "
               "without completing" in line for line in lines)
    assert clock.slept == [ONB_ROUND_SETTLE_S] * ONB_MAX_ROUNDS


# ------------------------------------------------ tap-label fallback

def test_tap_label_fallback_full_path_reference():
    """The S003 recovery on the reference driver: tap_semantic('scan')
    raises UnknownTargetError (the id is in NO scope of the real
    targets.yaml — the deterministic death), the fallback takes ONE
    fresh dump, finds the clickable text="Scan" control, taps its
    bounds-center, and the step is ok with the mandated emit line."""
    provider = ScriptedProvider()
    provider.ui_xmls = [SCAN_DUMP]
    driver, _clock = _make_driver(provider)
    bridge = _bridge(provider)                # the real targets.yaml
    lines: list[str] = []
    native = _Native(provider, "e2b-fake01", "", "")
    (plan,) = plan_steps(["tap: scan"], None)
    ok, _diag = driver._invoke_plan(bridge, plan, "com.intsig.camscanner",
                                    120, native, lines.append)
    assert ok is True
    assert [(a.x, a.y) for a in provider.interactions] == [(540, 2110)]
    assert any("tap 'scan' by label discovery at (540,2110) "
               "(text='Scan')" in line for line in lines)
    # exactly ONE fresh dump: the failed registry lookup captures
    # nothing (it raises first), the fallback's dump is the only one
    assert len(_ui_captures(provider)) == 1


def test_needle_ladder_token_rung():
    """The needle ladder: id "next_button" with a dump whose label is
    "Next" — the raw and underscores→spaces rungs do not match, the
    token rung "next" does (case-insensitive); and a LATER rung never
    wins over an earlier one whatever the document order."""
    # the ladder as data
    assert _label_needles("next_button") == (
        "next_button", "next button", "next", "button")
    assert _label_needles("scan") == ("scan",)
    # the token rung resolves the "Next" label
    provider = ScriptedProvider()
    provider.ui_xmls = [NEXT_DUMP]            # clickable "Next" (980,2210)
    driver, _clock = _make_driver(provider)
    bridge = _bridge(provider)
    lines: list[str] = []
    native = _Native(provider, "e2b-fake01", "", "")
    (plan,) = plan_steps(["tap: next_button"], None)
    ok, _diag = driver._invoke_plan(bridge, plan, "com.intsig.camscanner",
                                    120, native, lines.append)
    assert ok is True
    assert [(a.x, a.y) for a in provider.interactions] == [(980, 2210)]
    assert any("tap 'next_button' by label discovery at (980,2210) "
               "(text='Next')" in line for line in lines)
    # ladder ORDER beats document order: "next" (rung 3) wins over
    # "button" (rung 4) even when the button-label node comes first
    provider = ScriptedProvider()
    provider.ui_xmls = [_dump(
        _node("Button Options", clickable=True, bounds="[0,0][100,100]"),
        _node("Next", clickable=True, bounds="[0,100][100,200]"),
    )]
    driver, _clock = _make_driver(provider)
    bridge = _bridge(provider)
    lines = []
    native = _Native(provider, "e2b-fake01", "", "")
    ok, _diag = driver._invoke_plan(bridge, plan, "com.intsig.camscanner",
                                    120, native, lines.append)
    assert ok is True
    assert [(a.x, a.y) for a in provider.interactions] == [(50, 150)]


def test_tap_label_fallback_no_match_reraises():
    """No label match either → the UnknownTargetError propagates (the
    step never earns an ok result — honest failure, unchanged
    semantics for genuinely absent controls). The fallback's fresh
    dump DID run (one ui-hierarchy capture) before giving up."""
    provider = ScriptedProvider()
    provider.ui_xmls = [CLEAN_DUMP]           # no "scan" label anywhere
    driver, _clock = _make_driver(provider)
    bridge = _bridge(provider)
    lines: list[str] = []
    native = _Native(provider, "e2b-fake01", "", "")
    (plan,) = plan_steps(["tap: scan"], None)
    with pytest.raises(UnknownTargetError):
        driver._invoke_plan(bridge, plan, "com.intsig.camscanner",
                            120, native, lines.append)
    # the fallback fired and looked: exactly one fresh dump, no tap
    assert len(_ui_captures(provider)) == 1
    assert provider.interactions == []
    assert not any("by label discovery" in line for line in lines)


def test_tap_label_fallback_never_taps_close_app_and_names_anr():
    """The app-killer guard + the honest ANR note: on an ANR-hung dump
    the "Close app" label matches the needle for a hypothetical
    'close' target but is NEVER tapped (it kills the app); with no
    remaining match the original UnknownTargetError re-raises and the
    emit names the real reason — the app is hung, not missing the
    control (the shared ANR signature, PART A's helper)."""
    provider = ScriptedProvider()
    provider.ui_xmls = [ANR_DUMP]
    driver, _clock = _make_driver(provider)
    bridge = _bridge(provider)
    lines: list[str] = []
    native = _Native(provider, "e2b-fake01", "", "")
    (plan,) = plan_steps(["tap: close"], None)
    with pytest.raises(UnknownTargetError):
        driver._invoke_plan(bridge, plan, "com.intsig.camscanner",
                            120, native, lines.append)
    assert provider.interactions == []        # Close app NEVER tapped
    assert not any((a.x, a.y) == ANR_CLOSE_CENTER
                   for a in provider.interactions)
    assert any("no label match and the dump shows the ANR dialog"
               in line for line in lines)
    assert any("hung" in line for line in lines)


def test_registry_first_registered_id_never_fires_fallback():
    """Registry-first pin: a REGISTERED id (next_button in the
    org.payswap.camscan scope — the design contract) resolves through
    the registry (ui-selector attempt, then the registered coordinate
    fallback (980, 2210)) and the label fallback does NOT fire: no
    discovery emit, and no extra dump command beyond the registry's
    own selector-resolution dump."""
    provider = ScriptedProvider()
    provider.ui_xmls = [SCAN_DUMP]            # selector won't match here
    driver, _clock = _make_driver(provider)
    bridge = _bridge(provider)                # the real targets.yaml
    bridge.set_app("org.payswap.camscan")     # the scope that HAS the id
    lines: list[str] = []
    native = _Native(provider, "e2b-fake01", "", "")
    (plan,) = plan_steps(["tap: next_button"], None)
    ok, _diag = driver._invoke_plan(bridge, plan, "org.payswap.camscan",
                                    120, native, lines.append)
    assert ok is True
    # the registered coordinate fallback — the registry's own answer
    assert [(a.x, a.y) for a in provider.interactions] == [(980, 2210)]
    # NO discovery line at all — the fallback did not fire
    assert lines == []
    # exactly ONE ui dump in the provider op log: the registry's own
    # ui-selector resolution inside tap_semantic. A fallback firing
    # would have added a SECOND capture (the fresh dump).
    assert len(_ui_captures(provider)) == 1


# --------------------------------------------------------- e2b parity

def test_e2b_live_label_fallback_parity():
    """The SAME fallback fires on the e2b live driver (the shared
    helper, the 010F placement pattern): UnknownTargetError on
    tap_semantic('scan') → fresh dump → clickable "Scan" tapped at
    its bounds-center → the implementation-labelled emit line → ok."""
    provider = ScriptedProvider()
    provider.ui_xmls = [SCAN_DUMP]
    bridge = _bridge(provider)                # the real targets.yaml
    driver = E2bLiveDriver(sleep=FakeClock().sleep)
    (plan,) = plan_steps(["tap: scan"], None)
    lines: list[str] = []
    ok = driver._invoke_plan(bridge, plan, "org.payswap.camscan",
                             120, lines.append)
    assert ok is True
    assert [(a.x, a.y) for a in provider.interactions] == [(540, 2110)]
    assert any("implementation: tap 'scan' by label discovery at "
               "(540,2110) (text='Scan')" in line for line in lines)
    assert len(_ui_captures(provider)) == 1
