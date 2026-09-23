"""Dimension (a) environment parity + dimension (b) fixture parity."""
from __future__ import annotations

from pathlib import Path

from helpers import compare_pair, entry_map, synth_sha


def test_environment_identical_no_entries(tmp_path: Path) -> None:
    diff = compare_pair(tmp_path).diff
    assert diff["dimensions"]["environment"]["entries"] == 0


def test_device_model_diverges_high(tmp_path: Path) -> None:
    diff = compare_pair(tmp_path, impl_overrides={
        "device": {"model": "Pixel 6"}}).diff
    entry = entry_map(diff)["env-device-model"]
    assert entry["severity"] == "high"
    assert entry["dimension"] == "environment"
    assert entry["reference"] == "Pixel 4 (AVD pixel_4)"
    assert entry["implementation"] == "Pixel 6"
    assert entry["path"] == "device.model"


def test_locale_diverges_high(tmp_path: Path) -> None:
    diff = compare_pair(tmp_path, impl_overrides={
        "device": {"locale": "fr-FR"}}).diff
    assert entry_map(diff)["env-locale"]["severity"] == "high"


def test_timezone_diverges_high(tmp_path: Path) -> None:
    diff = compare_pair(tmp_path, impl_overrides={
        "device": {"timezone": "Europe/Paris"}}).diff
    assert entry_map(diff)["env-timezone"]["severity"] == "high"


def test_android_version_diverges_high(tmp_path: Path) -> None:
    diff = compare_pair(tmp_path, impl_overrides={
        "device": {"android_version": "13"}}).diff
    assert entry_map(diff)["env-android-version"]["severity"] == "high"


def test_screen_diverges_high(tmp_path: Path) -> None:
    diff = compare_pair(tmp_path, impl_overrides={
        "device": {"screen": "1080x2400@420dpi"}}).diff
    assert entry_map(diff)["env-screen"]["severity"] == "high"


def test_permission_baseline_flip_high(tmp_path: Path) -> None:
    diff = compare_pair(tmp_path, ref_overrides={
        "device": {"permission_baseline":
                   {"android.permission.CAMERA": False}}}).diff
    entry = entry_map(diff)["env-permission-android-permission-camera"]
    assert entry["severity"] == "high"
    assert entry["reference"] is False
    assert entry["implementation"] is True


def test_permission_missing_on_implementation_high(tmp_path: Path) -> None:
    from helpers import RUN_ID, base_manifest
    impl = base_manifest(RUN_ID, "implementation")
    impl["device"]["permission_baseline"] = {}
    diff = compare_pair(tmp_path, impl_manifest=impl).diff
    entry = entry_map(diff)["env-permission-android-permission-camera"]
    assert entry["severity"] == "high"
    assert entry["reference"] is True
    assert entry["implementation"] is None


def test_provider_capability_disagreement_medium(tmp_path: Path) -> None:
    diff = compare_pair(tmp_path, impl_overrides={
        "provider": {"capabilities": {"camera_fixture": False}}}).diff
    entry = entry_map(diff)["env-provider-capability-camera-fixture"]
    assert entry["severity"] == "medium"
    assert entry["dimension"] == "environment"


def test_provider_slug_difference_low(tmp_path: Path) -> None:
    diff = compare_pair(tmp_path, impl_overrides={
        "provider": {"slug": "gcp"}}).diff
    assert entry_map(diff)["env-provider-slug"]["severity"] == "low"


def test_env_divergence_is_fail(tmp_path: Path) -> None:
    result = compare_pair(tmp_path, impl_overrides={
        "device": {"locale": "fr-FR"}})
    assert result.verdict["verdict"] == "FAIL"


def test_fixtures_identical_no_entries(tmp_path: Path) -> None:
    diff = compare_pair(tmp_path).diff
    assert diff["dimensions"]["fixtures"]["entries"] == 0


def test_fixture_sha_diverges_critical(tmp_path: Path) -> None:
    diff = compare_pair(tmp_path, impl_overrides={
        "fixtures": [{"id": "clean-a4", "sha256":
                      "f" * 64}]}).diff
    entry = entry_map(diff)["fix-sha-clean-a4"]
    assert entry["severity"] == "critical"
    assert entry["dimension"] == "fixtures"
    assert entry["implementation"]["sha256"] == "f" * 64


def test_fixture_missing_on_implementation_critical(tmp_path: Path) -> None:
    diff = compare_pair(tmp_path, impl_overrides={"fixtures": []}).diff
    entry = entry_map(diff)["fix-missing-implementation-clean-a4"]
    assert entry["severity"] == "critical"
    assert entry["reference"]["id"] == "clean-a4"


def test_fixture_extra_on_implementation_critical(tmp_path: Path) -> None:
    diff = compare_pair(tmp_path, impl_overrides={
        "fixtures": [{"id": "clean-a4", "sha256": synth_sha("clean-a4")},
                     {"id": "receipt", "sha256": synth_sha("receipt")}]}).diff
    assert entry_map(diff)["fix-extra-implementation-receipt"][
        "severity"] == "critical"


def test_fixture_divergence_is_fail(tmp_path: Path) -> None:
    result = compare_pair(tmp_path, impl_overrides={
        "fixtures": [{"id": "clean-a4", "sha256": "0" * 64}]})
    assert result.verdict["verdict"] == "FAIL"
    assert result.verdict["counts"]["critical"] >= 1
