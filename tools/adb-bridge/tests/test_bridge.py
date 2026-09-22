"""Unit tests for tools/adb-bridge (CAMSCAN-002): every verb, target
resolution order, and timeout paths — against the in-memory FakeProvider.
No network, no adb, no real waiting (the only wall time is the handful of
bounded poll intervals asserted below)."""
from __future__ import annotations

import hashlib
import time
from pathlib import Path

import pytest
from lab.providers.types import CaptureKind, CommandResult

from tools.adb_bridge.bridge import (
    DEFAULT_VERB_TIMEOUT_S,
    AdbBridge,
    LabProviderLike,
    TargetRegistry,
    UnknownTargetError,
    VerbResult,
    match_selector,
    parse_selector,
)

from fake_provider import FakeProvider, MinimalProvider

REPO_ROOT = Path(__file__).resolve().parents[3]
SHIPPED_TARGETS = REPO_ROOT / "tools" / "adb-bridge" / "targets.yaml"

PKG = "org.payswap.camscan"
MAIN = "org.payswap.camscan/org.payswap.camscan.MainActivity"

# A realistic uiautomator-shaped dump: placeholder_text (real skeleton id),
# a shutter node, and a system permission-dialog Allow button.
UI_XML = """<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
<hierarchy rotation="0">
  <node index="0" text="" resource-id="" class="android.widget.FrameLayout"
        package="org.payswap.camscan" content-desc="" bounds="[0,0][1080,2280]">
    <node index="1" text="CamScan skeleton"
          resource-id="org.payswap.camscan:id/placeholder_text"
          class="android.widget.TextView" package="org.payswap.camscan"
          content-desc="" bounds="[80,1000][1000,1200]"/>
    <node index="2" text="" resource-id="org.payswap.camscan:id/shutter"
          class="android.widget.Button" package="org.payswap.camscan"
          content-desc="capture" bounds="[440,1780][640,1980]"/>
    <node index="3" text="While using the app" resource-id="android:id/button1"
          class="android.widget.Button" package="com.android.permissioncontroller"
          content-desc="Allow" bounds="[620,1400][1000,1480]"/>
  </node>
</hierarchy>"""

REGISTRY_YAML = """
schema: 1
profile:
  device: pixel_4
  resolution: 1080x2280
targets:
  permission_allow:
    selector: text="While using the app"
    x: 540
    y: 1454
  coord_only:
    x: 111
    y: 222
  selector_only:
    selector: resource-id="org.payswap.camscan:id/nope"
  both_prefer_selector:
    selector: resource-id="org.payswap.camscan:id/shutter"
    x: 7
    y: 9
apps:
  org.payswap.camscan:
    targets:
      shutter_button:
        selector: resource-id="org.payswap.camscan:id/shutter"
        x: 540
        y: 1880
      both_prefer_selector:
        selector: resource-id="org.payswap.camscan:id/shutter"
        x: 701
        y: 902
      permission_allow:
        x: 123
        y: 456
"""


@pytest.fixture()
def registry(tmp_path: Path) -> TargetRegistry:
    path = tmp_path / "targets.yaml"
    path.write_text(REGISTRY_YAML, encoding="utf-8")
    return TargetRegistry(path)


@pytest.fixture()
def provider() -> FakeProvider:
    return FakeProvider(ui_xml=UI_XML,
                        logcat_text="C/cam: one\nC/ui: two\nD/dbg: three\n",
                        screenshot_bytes=b"\x89PNG-fake-bytes",
                        focus=f"{PKG}/.MainActivity",
                        resumed=MAIN,
                        boot_completed=True)


@pytest.fixture()
def bridge(provider: FakeProvider, registry: TargetRegistry,
           tmp_path: Path) -> AdbBridge:
    return AdbBridge(provider, "env-test", targets=registry,
                     workdir=tmp_path / "work")


# --------------------------------------------------------------- registry


def test_shipped_registry_loads_and_has_example_ids():
    reg = TargetRegistry(SHIPPED_TARGETS)
    assert reg.schema == 1
    for target_id in ("permission_allow", "permission_deny"):
        assert target_id in reg.ids()
    app_ids = reg.ids("org.payswap.camscan")
    for target_id in ("shutter_button", "next_button", "gallery_tile_1",
                      "root_placeholder", "save_button"):
        assert target_id in app_ids


def test_registry_unknown_selector_key_raises(tmp_path: Path):
    path = tmp_path / "bad.yaml"
    path.write_text("targets:\n  x:\n    selector: 'bogus=\"1\"'\n    x: 1\n    y: 1\n")
    with pytest.raises(ValueError, match="unknown selector key"):
        TargetRegistry(path)


def test_registry_unparseable_selector_raises(tmp_path: Path):
    path = tmp_path / "bad.yaml"
    path.write_text("targets:\n  x:\n    selector: nonsense\n    x: 1\n    y: 1\n")
    with pytest.raises(ValueError, match="unparseable selector"):
        TargetRegistry(path)


def test_registry_half_coordinates_raise(tmp_path: Path):
    path = tmp_path / "bad.yaml"
    path.write_text("targets:\n  x:\n    selector: text=\"a\"\n    x: 5\n")
    with pytest.raises(ValueError, match="x and y must be given together"):
        TargetRegistry(path)


def test_registry_target_needs_selector_or_coordinates(tmp_path: Path):
    path = tmp_path / "bad.yaml"
    path.write_text("targets:\n  x:\n    note: nothing actionable\n")
    with pytest.raises(ValueError, match="needs a ui-selector"):
        TargetRegistry(path)


def test_registry_app_scope_overrides_global(registry: TargetRegistry):
    entry = registry.get("permission_allow", PKG)
    assert (entry.x, entry.y) == (123, 456)          # app scope wins
    assert registry.get("permission_allow").x == 540  # global still intact


def test_registry_global_fallback_when_app_scope_lacks_id(registry: TargetRegistry):
    assert registry.get("coord_only", PKG).x == 111  # app has no coord_only


def test_registry_unknown_target_raises_with_known_ids(registry: TargetRegistry):
    with pytest.raises(UnknownTargetError, match="no_such_target"):
        registry.get("no_such_target", PKG)
    with pytest.raises(KeyError):                     # it is a KeyError
        registry.get("no_such_target")


def test_parse_selector_conjunction_and_aliases():
    terms = parse_selector('id="a:id/b" text-contains="Scan" desc="d" class-name="android.widget.Button"')
    assert terms == (("resource-id", "a:id/b"), ("text_contains", "Scan"),
                     ("content-desc", "d"), ("class", "android.widget.Button"))
    assert parse_selector("") == ()


def test_match_selector_returns_center_and_node_info():
    node = match_selector(UI_XML, (("resource-id", "org.payswap.camscan:id/shutter"),))
    assert node is not None
    assert (node["x"], node["y"]) == (540, 1880)      # center of [440,1780][640,1980]
    assert node["resource-id"] == "org.payswap.camscan:id/shutter"


def test_match_selector_zero_area_node_is_skipped():
    xml = ('<hierarchy><node text="ok" resource-id="a" bounds="[5,5][5,5]"/>'
           '<node text="ok" resource-id="a" bounds="[0,0][100,100]"/></hierarchy>')
    node = match_selector(xml, (("resource-id", "a"),))
    assert node is not None and (node["x"], node["y"]) == (50, 50)


def test_match_selector_unparseable_xml_returns_none():
    assert match_selector("not xml at all <", (("text", "x"),)) is None


def test_fake_provider_satisfies_provider_protocol(provider: FakeProvider):
    assert isinstance(provider, LabProviderLike)


# ------------------------------------------------------------------ launch


def test_launch_with_activity_am_start_wait(bridge: AdbBridge, provider: FakeProvider):
    result = bridge.launch(PKG, "MainActivity")
    assert result.ok, result.error
    assert provider.executes == [
        f"adb shell am start -W -n {PKG}/.MainActivity"]
    assert result.detail["launch_status"] == "ok"
    assert result.detail["launch_state"] == "COLD"
    assert result.detail["total_time_ms"] == 850
    assert bridge.current_app == PKG              # launch scopes target lookups


@pytest.mark.parametrize("activity,component", [
    (".MainActivity", f"{PKG}/.MainActivity"),
    ("org.payswap.camscan.Settings", f"{PKG}/org.payswap.camscan.Settings"),
    (f"{PKG}/.MainActivity", f"{PKG}/.MainActivity"),   # already a component
])
def test_launch_activity_component_forms(bridge: AdbBridge, provider: FakeProvider,
                                         activity, component):
    assert bridge.launch(PKG, activity).ok
    assert provider.executes[-1] == f"adb shell am start -W -n {component}"


def test_launch_without_activity_uses_monkey(bridge: AdbBridge, provider: FakeProvider):
    result = bridge.launch(PKG)
    assert result.ok, result.error
    assert "monkey -p org.payswap.camscan" in provider.executes[0]
    assert "android.intent.category.LAUNCHER" in provider.executes[0]


def test_launch_am_start_status_error_fails(bridge: AdbBridge, provider: FakeProvider):
    provider.execute_hook = lambda cmd, timeout: CommandResult(
        0, "Status: error\nError: Activity class does not exist.\n", "", 10, cmd)
    result = bridge.launch(PKG, "Missing")
    assert not result.ok
    assert result.detail["launch_status"] == "error"
    assert "am start failed" in result.error


def test_launch_monkey_no_activities_fails(bridge: AdbBridge, provider: FakeProvider):
    provider.execute_hook = lambda cmd, timeout: CommandResult(
        0, "** No activities found ** \n", "", 10, cmd)   # monkey exits 0 anyway
    result = bridge.launch(PKG)
    assert not result.ok                        # must not trust the exit code


def test_launch_sets_app_scope_for_targets(bridge: AdbBridge, registry: TargetRegistry):
    bridge.launch(PKG)
    assert registry.get("shutter_button", PKG)      # scope now resolvable
    assert bridge.current_app == PKG


# ------------------------------------------------------- force_stop / perms


def test_force_stop(bridge: AdbBridge, provider: FakeProvider):
    result = bridge.force_stop(PKG)
    assert result.ok, result.error
    assert provider.executes == [f"adb shell am force-stop {PKG}"]


def test_grant_and_revoke(bridge: AdbBridge, provider: FakeProvider):
    assert bridge.grant(PKG, "android.permission.CAMERA").ok
    assert bridge.revoke(PKG, "android.permission.CAMERA").ok
    assert provider.executes == [
        f"adb shell pm grant {PKG} android.permission.CAMERA",
        f"adb shell pm revoke {PKG} android.permission.CAMERA"]


def test_grant_denial_is_structured_failure(bridge: AdbBridge, provider: FakeProvider):
    provider.pm_failures = {(PKG, "android.permission.CAMERA")}
    result = bridge.grant(PKG, "android.permission.CAMERA")
    assert not result.ok
    assert result.detail["exit_code"] == 1
    assert "not a runtime permission" in result.error


# ------------------------------------------------------- interaction verbs


def test_interaction_verbs_map_to_typed_specs(bridge: AdbBridge, provider: FakeProvider):
    assert bridge.tap(5, 9).ok
    assert bridge.swipe(1, 2, 3, 4, 250).ok
    assert bridge.type_text("hello world").ok
    assert bridge.key("enter").ok
    assert bridge.key("KEYCODE_DPAD_CENTER").ok
    assert bridge.back().ok
    assert bridge.home().ok
    assert bridge.wake().ok
    tap, swipe, text, key1, key2, back, home, wake = provider.interactions
    assert (tap.kind, tap.x, tap.y) == ("tap", 5, 9)
    assert (swipe.kind, swipe.x, swipe.y, swipe.x2, swipe.y2, swipe.duration_ms) == \
        ("swipe", 1, 2, 3, 4, 250)
    assert (text.kind, text.text) == ("type", "hello%sworld")   # adb space escape
    assert (key1.kind, key1.keycode) == ("key", "KEYCODE_ENTER")
    assert (key2.kind, key2.keycode) == ("key", "KEYCODE_DPAD_CENTER")
    assert (back.kind, back.button) == ("press", "back")
    assert (home.kind, home.button) == ("press", "home")
    assert wake.kind == "wake"


def test_type_text_preserves_original_and_records_encoding(bridge: AdbBridge):
    result = bridge.type_text("hello world")
    assert result.ok
    assert result.detail["text"] == "hello world"
    assert result.detail["encoded"] == "hello%sworld"


def test_type_text_empty_is_a_noop(bridge: AdbBridge, provider: FakeProvider):
    result = bridge.type_text("")
    assert result.ok and result.detail["no_op"]
    assert provider.interactions == []            # no provider call at all


def test_interact_failure_is_structured_not_raised(bridge: AdbBridge, provider: FakeProvider):
    provider.fail_interact_kinds = {"tap"}
    result = bridge.tap(1, 1)
    assert not result.ok
    assert "simulated failure" in result.error


# ------------------------------------------------- tap_semantic + resolution


def test_tap_semantic_selector_match_beats_registered_coordinates(
        bridge: AdbBridge, provider: FakeProvider):
    # both_prefer_selector has selector AND coords; the dump has the node at
    # center (540, 1880) — resolution order demands the dump wins over (701, 902)
    bridge.launch(PKG)
    result = bridge.tap_semantic("both_prefer_selector")
    assert result.ok, result.error
    assert result.detail["source"] == "ui-selector"
    assert (result.detail["resolved_x"], result.detail["resolved_y"]) == (540, 1880)
    tap = provider.interactions[-1]
    assert (tap.x, tap.y) == (540, 1880)
    assert tap.label == "both_prefer_selector"     # semantic label for the trace


def test_tap_semantic_auto_fetches_dump_when_cache_empty(
        bridge: AdbBridge, provider: FakeProvider):
    bridge.launch(PKG)
    assert bridge.latest_ui_dump is None
    result = bridge.tap_semantic("shutter_button")
    assert result.ok and result.detail["source"] == "ui-selector"
    assert provider.captures == [CaptureKind.ui_hierarchy]   # auto-fetch happened


def test_tap_semantic_reuses_latest_dump(bridge: AdbBridge, provider: FakeProvider):
    bridge.launch(PKG)
    bridge.ui_dump()
    assert len(provider.captures) == 1
    assert bridge.tap_semantic("shutter_button").ok
    assert len(provider.captures) == 1            # cache hit — no new capture


def test_tap_semantic_refresh_forces_new_dump(bridge: AdbBridge, provider: FakeProvider):
    bridge.launch(PKG)
    bridge.ui_dump()
    assert bridge.tap_semantic("shutter_button", refresh=True).ok
    assert len(provider.captures) == 2


def test_tap_semantic_dump_max_age_triggers_refresh(bridge: AdbBridge,
                                                    provider: FakeProvider,
                                                    registry: TargetRegistry,
                                                    tmp_path: Path):
    bridge2 = AdbBridge(provider, "env-test", targets=registry,
                        workdir=tmp_path / "work2", dump_max_age_s=60.0)
    bridge2.launch(PKG)
    bridge2.ui_dump()
    assert len(provider.captures) == 1
    bridge2._dump_ts = time.monotonic() - 999      # age the cache beyond the bound
    assert bridge2.tap_semantic("shutter_button").ok
    assert len(provider.captures) == 2             # staleness evicted → refresh


def test_tap_semantic_falls_back_to_coordinates_when_selector_misses(
        bridge: AdbBridge, provider: FakeProvider):
    bridge.launch(PKG)
    # selector_only has no coordinates and never matches; both_prefer_selector
    # matches. Use a registry entry whose selector misses but coords exist:
    provider.ui_xml = "<hierarchy/>"               # nothing matches now
    result = bridge.tap_semantic("both_prefer_selector")
    assert result.ok
    assert result.detail["source"] == "coordinates"
    assert (result.detail["resolved_x"], result.detail["resolved_y"]) == (701, 902)
    assert "not matched" in result.detail["fallback_reason"]


def test_tap_semantic_coordinates_only_target(bridge: AdbBridge, provider: FakeProvider):
    result = bridge.tap_semantic("coord_only")
    assert result.ok
    assert result.detail["source"] == "coordinates"
    assert (provider.interactions[-1].x, provider.interactions[-1].y) == (111, 222)


def test_tap_semantic_selector_only_unmatched_is_structured_failure(
        bridge: AdbBridge, provider: FakeProvider):
    result = bridge.tap_semantic("selector_only")
    assert not result.ok
    assert "no coordinate fallback" in result.error
    assert provider.interactions == []            # nothing was tapped


def test_tap_semantic_unknown_target_raises(bridge: AdbBridge):
    with pytest.raises(UnknownTargetError, match="made_up_button"):
        bridge.tap_semantic("made_up_button")


def test_tap_semantic_app_scope_beats_global(bridge: AdbBridge, provider: FakeProvider):
    bridge.launch(PKG)                             # app scope = org.payswap.camscan
    assert bridge.tap_semantic("permission_allow").ok
    assert (provider.interactions[-1].x, provider.interactions[-1].y) == (123, 456)
    # and without the app scope the same id resolves globally to the selector
    bridge.set_app(None)
    assert bridge.tap_semantic("permission_allow").ok
    assert (provider.interactions[-1].x, provider.interactions[-1].y) == (810, 1440)


def test_tap_semantic_unparseable_dump_falls_back_to_coordinates(
        bridge: AdbBridge, provider: FakeProvider):
    provider.ui_xml = "<<<broken"
    bridge.launch(PKG)
    result = bridge.tap_semantic("shutter_button")
    assert result.ok and result.detail["source"] == "coordinates"
    assert (result.detail["resolved_x"], result.detail["resolved_y"]) == (540, 1880)


def test_tap_semantic_dump_failure_falls_back_with_reason(
        bridge: AdbBridge, provider: FakeProvider):
    provider.fail_captures = {CaptureKind.ui_hierarchy}
    bridge.launch(PKG)
    result = bridge.tap_semantic("shutter_button")
    assert result.ok and result.detail["source"] == "coordinates"
    assert "ui dump unavailable" in result.detail["fallback_reason"]


# ---------------------------------------------------------- capture verbs


def test_screenshot_returns_bytes_with_verified_sha256(bridge: AdbBridge,
                                                        provider: FakeProvider):
    result = bridge.screenshot()
    assert result.ok, result.error
    assert result.data == b"\x89PNG-fake-bytes"
    assert result.detail["sha256"] == hashlib.sha256(result.data).hexdigest()
    assert provider.captures == [CaptureKind.screenshot]
    assert Path(result.detail["local_path"]).is_file()
    # bytes crossed via provider transfer (pull), provider-neutrally
    assert provider.transfers[-1][0] == "pull"


def test_screenshot_integrity_mismatch_fails(tmp_path: Path, registry: TargetRegistry):
    class Tampering(FakeProvider):
        def capture(self, env_id, kind, timeout=None):
            artifact = super().capture(env_id, kind, timeout)
            artifact.sha256 = "dead" * 16          # provider lies
            return artifact

    provider = Tampering(ui_xml=UI_XML, screenshot_bytes=b"png")
    bridge = AdbBridge(provider, "env-t", targets=registry, workdir=tmp_path / "w")
    result = bridge.screenshot()
    assert not result.ok
    assert "integrity mismatch" in result.error


def test_screenshot_capture_failure_is_structured(bridge: AdbBridge, provider: FakeProvider):
    provider.fail_captures = {CaptureKind.screenshot}
    result = bridge.screenshot()
    assert not result.ok and "simulated failure" in result.error


def test_ui_dump_returns_xml_and_populates_cache(bridge: AdbBridge, provider: FakeProvider):
    result = bridge.ui_dump()
    assert result.ok and result.text == UI_XML
    assert bridge.latest_ui_dump == UI_XML
    assert provider.captures == [CaptureKind.ui_hierarchy]


def test_logcat_tail_and_filter(bridge: AdbBridge):
    result = bridge.logcat(tail=2)
    assert result.ok and result.text == "C/ui: two\nD/dbg: three"
    result = bridge.logcat(filter="C/")
    assert result.text == "C/cam: one\nC/ui: two"
    result = bridge.logcat(filter=["C/cam", "D/"])
    assert result.text == "C/cam: one\nD/dbg: three"
    result = bridge.logcat(filter="C/", tail=3)
    assert result.detail["total_lines"] == 3
    assert result.detail["kept_lines"] == 2


def test_logcat_negative_tail_raises(bridge: AdbBridge):
    with pytest.raises(ValueError, match="tail must be"):
        bridge.logcat(tail=-1)


# ---------------------------------------------------------- install/push/pull


def test_install_pushes_then_installs(bridge: AdbBridge, provider: FakeProvider,
                                      tmp_path: Path):
    apk = tmp_path / "app-debug.apk"
    apk.write_bytes(b"PK-apk-bytes")
    result = bridge.install(apk, timeout=600)
    assert result.ok, result.error
    assert provider.transfers[0][0] == "push"     # local → env staging
    assert provider.transfers[0][2] == "/tmp/camscan-bridge/app-debug.apk"
    assert provider.executes == ["adb install -r -t /tmp/camscan-bridge/app-debug.apk"]
    assert result.detail["bytes"] == len(b"PK-apk-bytes")
    assert result.detail["timeout_s"] == 600.0


def test_install_failure_is_structured(bridge: AdbBridge, provider: FakeProvider,
                                       tmp_path: Path):
    apk = tmp_path / "broken.apk"
    apk.write_bytes(b"nope")
    provider.install_fails = True
    result = bridge.install(apk)
    assert not result.ok
    assert "INSTALL_FAILED" in result.error


def test_install_missing_local_file_raises(bridge: AdbBridge, tmp_path: Path):
    with pytest.raises(FileNotFoundError):
        bridge.install(tmp_path / "ghost.apk")


def test_push_reaches_the_device_filesystem(bridge: AdbBridge, provider: FakeProvider,
                                            tmp_path: Path):
    poster = tmp_path / "poster1.png"
    poster.write_bytes(b"poster-bytes")
    result = bridge.push(poster, "/sdcard/poster1.png")
    assert result.ok, result.error
    assert provider.device_files["/sdcard/poster1.png"] == b"poster-bytes"
    assert provider.transfers[-1][0] == "push"    # hop 1: local → env staging


def test_pull_brings_device_file_to_local(bridge: AdbBridge, provider: FakeProvider,
                                          tmp_path: Path):
    provider.device_files["/sdcard/Documents/doc.pdf"] = b"%PDF-1.4-fake"
    dst = tmp_path / "pulled" / "doc.pdf"
    result = bridge.pull("/sdcard/Documents/doc.pdf", dst)
    assert result.ok, result.error
    assert dst.read_bytes() == b"%PDF-1.4-fake"
    assert result.detail["bytes"] == len(b"%PDF-1.4-fake")


def test_pull_missing_device_file_fails(bridge: AdbBridge, tmp_path: Path):
    result = bridge.pull("/sdcard/nothing.txt", tmp_path / "nothing.txt")
    assert not result.ok
    assert "adb pull failed" in result.error


def test_push_missing_local_file_raises(bridge: AdbBridge, tmp_path: Path):
    with pytest.raises(FileNotFoundError):
        bridge.push(tmp_path / "ghost.bin", "/sdcard/ghost.bin")


# ---------------------------------------------------------- waiting bounded


def test_wait_for_immediate_success_costs_one_probe(bridge: AdbBridge):
    result = bridge.wait_for(lambda: True, timeout=5, poll=0.01)
    assert result.ok and result.detail["polls"] == 1


def test_wait_for_polls_until_true(bridge: AdbBridge):
    calls = {"n": 0}

    def becomes_true() -> bool:
        calls["n"] += 1
        return calls["n"] >= 3

    result = bridge.wait_for(becomes_true, timeout=5, poll=0.01)
    assert result.ok and result.detail["polls"] == 3


def test_wait_for_is_bounded_on_timeout(bridge: AdbBridge):
    t0 = time.monotonic()
    result = bridge.wait_for(lambda: False, timeout=0.2, poll=0.05)
    elapsed = time.monotonic() - t0
    assert not result.ok
    assert "not met within 0.2s" in result.error
    assert result.detail["polls"] >= 2
    assert 0.15 <= elapsed < 2.0                  # bounded, never unbounded sleep


def test_wait_for_records_predicate_exceptions(bridge: AdbBridge):
    def exploding() -> bool:
        raise RuntimeError("dumpsys exploded")

    result = bridge.wait_for(exploding, timeout=0.1, poll=0.02)
    assert not result.ok
    assert "dumpsys exploded" in result.detail["last_error"]


def test_wait_for_rejects_non_positive_poll(bridge: AdbBridge):
    with pytest.raises(ValueError, match="poll must be > 0"):
        bridge.wait_for(lambda: True, timeout=1, poll=0)


# ------------------------------------------------------------- predicates


def test_predicate_activity_resumed(bridge: AdbBridge):
    assert bridge.wait_for(bridge.activity_resumed("MainActivity"),
                           timeout=5, poll=0.01).ok
    assert bridge.wait_for(bridge.activity_resumed("SettingsActivity"),
                           timeout=0.1, poll=0.02).ok is False


def test_predicate_text_visible(bridge: AdbBridge, provider: FakeProvider):
    assert bridge.wait_for(bridge.text_visible("CamScan skeleton"),
                           timeout=5, poll=0.01).ok
    result = bridge.wait_for(bridge.text_visible("Absent"),
                             timeout=0.1, poll=0.02)
    assert not result.ok


def test_predicate_text_visible_records_probe_errors(bridge: AdbBridge,
                                                     provider: FakeProvider):
    provider.fail_captures = {CaptureKind.ui_hierarchy}
    predicate = bridge.text_visible("anything")
    result = bridge.wait_for(predicate, timeout=0.1, poll=0.02)
    assert not result.ok
    assert "simulated failure" in predicate.last_error
    assert "simulated failure" in result.detail["last_error"]  # surfaced by wait_for


def test_predicate_package_foreground(bridge: AdbBridge):
    assert bridge.wait_for(bridge.package_foreground(PKG),
                           timeout=5, poll=0.01).ok
    assert bridge.wait_for(bridge.package_foreground("com.other.app"),
                           timeout=0.1, poll=0.02).ok is False


def test_wait_idle_satisfied_by_stable_focus(bridge: AdbBridge):
    result = bridge.wait_idle(timeout=5, poll=0.01)
    assert result.ok, result.error
    assert result.verb == "wait_idle"
    assert result.detail["polls"] == 2            # two equal focus readings


def test_wait_idle_not_satisfied_by_changing_focus(bridge: AdbBridge,
                                                   provider: FakeProvider):
    provider.focus_sequence = ["app/One", "app/Two"]   # cycles forever
    result = bridge.wait_idle(timeout=0.2, poll=0.02)
    assert not result.ok
    assert result.detail["polls"] >= 2


def test_wait_idle_blocked_when_boot_incomplete(bridge: AdbBridge,
                                                provider: FakeProvider):
    provider.boot_completed = False
    result = bridge.wait_idle(timeout=0.15, poll=0.02)
    assert not result.ok


def test_wait_idle_rejects_stable_polls_below_two(bridge: AdbBridge):
    with pytest.raises(ValueError, match="stable_polls"):
        bridge.wait_idle(stable_polls=1)


# ---------------------------------------------------------------- timeouts


def test_default_verb_timeout_is_tcg_scale_and_passed_down(bridge: AdbBridge,
                                                           provider: FakeProvider):
    assert bridge.default_timeout_s == DEFAULT_VERB_TIMEOUT_S == 180.0
    assert bridge.tap(1, 2).ok
    assert provider.interaction_timeouts == [180.0]
    assert bridge.launch(PKG, "MainActivity").ok
    assert provider.execute_timeouts[-1] == 180.0
    assert bridge.screenshot().ok
    assert provider.capture_timeouts == [180.0]


def test_per_verb_timeout_overrides_default(bridge: AdbBridge, provider: FakeProvider):
    assert bridge.tap(1, 2, timeout=42).ok
    assert provider.interaction_timeouts == [42.0]
    assert bridge.launch(PKG, "MainActivity", timeout=250).ok
    assert provider.execute_timeouts[-1] == 250.0
    assert bridge.screenshot(timeout=99).ok
    assert provider.capture_timeouts[-1] == 99.0


def test_execute_timeout_is_structured_failure(bridge: AdbBridge, provider: FakeProvider):
    provider.execute_timeout_substrings = {"am start"}   # simulated provider timeout
    result = bridge.launch(PKG, "MainActivity")
    assert not result.ok
    assert result.detail["exit_code"] == -1


def test_interact_timeout_by_budget_latency_model(bridge: AdbBridge,
                                                  provider: FakeProvider):
    provider.interact_latency_s = 10.0           # needs ≥ 10 s budget
    result = bridge.tap(1, 1, timeout=5)
    assert not result.ok and "simulated timeout" in result.error
    assert bridge.tap(1, 1, timeout=60).ok


def test_execute_timeout_by_budget_latency_model(bridge: AdbBridge,
                                                 provider: FakeProvider):
    provider.execute_latency_s = 30.0
    result = bridge.force_stop(PKG, timeout=20)
    assert not result.ok
    assert result.detail["exit_code"] == -1
    assert bridge.force_stop(PKG, timeout=60).ok


# ------------------------------------------------------------------ traces


def test_verbs_append_semantic_events_to_provider_trace(bridge: AdbBridge):
    bridge.launch(PKG, "MainActivity")
    bridge.tap_semantic("shutter_button")
    bridge.screenshot()
    bridge.wait_for(lambda: True, timeout=1, poll=0.01)
    kinds_labels = [(e.kind, e.label) for e in bridge.provider.trace_events]
    assert ("lifecycle", f"launch:{PKG}") in kinds_labels
    assert ("capture", "screenshot") in kinds_labels
    assert any(k == "execute" and l.startswith("wait_for:") for k, l in kinds_labels)


def test_tap_semantic_trace_carries_semantic_label(bridge: AdbBridge,
                                                   provider: FakeProvider):
    bridge.launch(PKG)
    bridge.tap_semantic("shutter_button")
    labels = [e.label for e in provider.trace_events]
    assert "tap_semantic:shutter_button" in labels


def test_trace_events_carry_verb_outcomes(bridge: AdbBridge, provider: FakeProvider):
    provider.fail_interact_kinds = {"tap"}
    bridge.tap(1, 1)
    event = provider.trace_events[-1]
    assert event.kind == "interact" and event.detail["ok"] is False


# ------------------------------------------------- provider-neutral tolerance


def test_minimal_provider_without_trace_hook_or_timeouts(tmp_path: Path,
                                                         registry: TargetRegistry):
    provider = MinimalProvider()
    bridge = AdbBridge(provider, "env-min", targets=registry,
                       workdir=tmp_path / "w")
    assert bridge.tap(3, 4).ok                    # interact without timeout kwarg
    result = bridge.launch(PKG, "MainActivity")   # execute keeps the timeout kwarg
    assert result.ok, result.error
    shot = bridge.screenshot()                    # capture without timeout kwarg
    assert shot.ok and shot.data == b"\x89PNG-fake"
    assert provider.interactions[0].x == 3        # everything was recorded
    assert all(isinstance(t, float) for _cmd, t in provider.executes)
    assert not hasattr(provider, "_trace")        # no trace hook to append to —
    bridge.tap(5, 5)                              # …and that must not crash


def test_adb_prefix_is_configuration(tmp_path: Path, registry: TargetRegistry):
    provider = FakeProvider()
    bridge = AdbBridge(provider, "e", targets=registry, workdir=tmp_path / "w",
                       adb="/opt/android-sdk/platform-tools/adb")
    bridge.force_stop(PKG)
    assert provider.executes[0].startswith("/opt/android-sdk/platform-tools/adb ")


def test_adb_prefix_discovered_from_provider_attribute(tmp_path: Path,
                                                       registry: TargetRegistry):
    class AdvertiseAdb(FakeProvider):
        adb_command = "/usr/local/bin/adb"

    provider = AdvertiseAdb()
    bridge = AdbBridge(provider, "e", targets=registry, workdir=tmp_path / "w")
    bridge.force_stop(PKG)
    assert provider.executes[0].startswith("/usr/local/bin/adb ")


# ------------------------------------------------------------------- misc


def test_verb_result_shape(bridge: AdbBridge):
    result = bridge.tap(1, 2)
    assert isinstance(result, VerbResult)
    assert isinstance(result.ok, bool)
    assert isinstance(result.detail, dict)
    assert result.duration_ms >= 0
    assert result.error == ""
    assert "VerbResult(verb='tap', ok=True" in repr(result)


def test_cleanup_removes_workdir(bridge: AdbBridge):
    bridge.screenshot()
    workdir = bridge.workdir
    assert workdir.exists()
    bridge.cleanup()
    assert not workdir.exists()


def test_bridge_rejects_bad_constructor_budgets(provider: FakeProvider,
                                                registry: TargetRegistry,
                                                tmp_path: Path):
    with pytest.raises(ValueError, match="default_timeout_s"):
        AdbBridge(provider, "e", targets=registry, workdir=tmp_path / "w",
                  default_timeout_s=0)
    with pytest.raises(ValueError, match="poll_s"):
        AdbBridge(provider, "e", targets=registry, workdir=tmp_path / "w",
                  poll_s=-1)


def test_dash_dir_is_importable_as_underscore_package():
    import tools
    import tools.adb_bridge as pkg
    assert pkg.AdbBridge is AdbBridge
    assert (Path(tools.__file__).parent / "adb-bridge").is_dir()
