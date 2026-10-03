"""``reconcile`` subcommand tests (CAMSCAN-PROD-012, additive).

Hermetic: synthetic scenario trees + ledgers in tmp_path; plus two
tests against the REAL repo tree (S019-S021 validate clean; reports
deterministic). No network, no device, no wall-clock stamps.
"""
from __future__ import annotations

import json
from pathlib import Path

import pytest
from tools.parity_cli.main import main
from tools.parity_cli.model import ParityCliError
from tools.parity_cli.reconcile import reconcile

REPO_ROOT = Path(__file__).resolve().parents[3]

VALID_SCENARIO = """\
# authored at DSL v0.1 (typed meta.requires + explicit timeouts)
id: single-document-capture
title: Single-document capture end-to-end
status: UNKNOWN
preconditions:
- fresh-install
- camera-permission-granted
fixture:
  camera: clean-a4
steps:
- launch
- 'tap: scan'
- capture
- accept-document
- save
assertions:
  behavior:
  - document-detected
  - save-succeeds
  ui:
  - scan-control-visible
  state:
  - document-added-to-library
  output:
  - pdf-created
  - one-page-document
meta:
  timeout_seconds: 2400
  step_timeout_seconds: 180
  requires:
    gui: true
    adb: true
    android_emulator: true
    android_sdk: true
    android_cli: true
    emulator_acceleration:
      allowed:
      - none
    camera_fixture: true
  owner: lead
  notes: unit-test scenario copy
"""

VALID_LEDGER = {
    "entries": [
        {
            "id": "S004",
            "scenario": "single-document-capture",
            "title": "Single-document capture end-to-end",
            "status": "UNKNOWN",
        }
    ]
}


def _build_repo(base: Path, *, scenario_files: dict[str, str] | None = None,
                ledger: dict | None = None) -> tuple[Path, Path, Path]:
    scenarios = base / "scenarios"
    scenarios.mkdir(parents=True, exist_ok=True)
    files = scenario_files if scenario_files is not None else {
        "S004-single-document-capture.yaml": VALID_SCENARIO,
    }
    for name, text in files.items():
        (scenarios / name).write_text(text, encoding="utf-8")
    ledger_path = base / "ledger.json"
    ledger_path.write_text(
        json.dumps(ledger if ledger is not None else VALID_LEDGER, indent=2),
        encoding="utf-8")
    report_dir = base / "reports"
    report_dir.mkdir(exist_ok=True)
    return scenarios, ledger_path, report_dir


def _errors_for(base: Path, files: dict[str, str]) -> list[dict[str, str]]:
    scenarios, ledger_path, _ = _build_repo(base, scenario_files=files)
    return reconcile(scenarios, ledger_path).validation_errors


def _drop_key_block(body: str, key: str) -> str:
    """Drop a top-level mapping key and its indented block."""
    lines = body.split("\n")
    kept: list[str] = []
    skipping = False
    for line in lines:
        if not skipping and line.startswith(key + ":"):
            skipping = True
            continue
        if skipping:
            if line.startswith((" ", "-")) or line == "":
                continue
            skipping = False
        kept.append(line)
    return "\n".join(kept)


# ------------------------------------------------------ real repo tree

def test_real_tree_s019_to_s024_validate_clean() -> None:
    outcome = reconcile(
        REPO_ROOT / "lab" / "scenarios",
        REPO_ROOT / "lab" / "parity-ledger" / "ledger.json",
    )
    assert outcome.validation_errors == []
    gap_ids = [gap["id"] for gap in outcome.scenario_without_entry]
    assert gap_ids == ["S019", "S020", "S021", "S022", "S023", "S024"]
    gap_scenarios = [gap["scenario"] for gap in outcome.scenario_without_entry]
    assert gap_scenarios == ["id-card-scan", "business-card-scan",
                             "book-spread-scan", "local-backup-restore",
                             "export-office-docx", "print-document"]
    assert outcome.entry_without_scenario == []
    assert outcome.status_disagreements == []
    assert outcome.scenario_count == 24
    assert outcome.ledger_entry_count == 18


def test_real_tree_reports_are_deterministic() -> None:
    first = reconcile(
        REPO_ROOT / "lab" / "scenarios",
        REPO_ROOT / "lab" / "parity-ledger" / "ledger.json",
    )
    second = reconcile(
        REPO_ROOT / "lab" / "scenarios",
        REPO_ROOT / "lab" / "parity-ledger" / "ledger.json",
    )
    assert first.report_json_text() == second.report_json_text()
    assert first.report_markdown() == second.report_markdown()


# ------------------------------------------------------ schema validation

def test_valid_scenario_has_no_errors(tmp_path: Path) -> None:
    assert _errors_for(tmp_path, {
        "S004-single-document-capture.yaml": VALID_SCENARIO,
    }) == []


@pytest.mark.parametrize("key", [
    "id", "title", "status", "preconditions", "fixture", "steps",
    "assertions", "meta",
])
def test_each_required_field_missing_is_an_error(tmp_path: Path,
                                                 key: str) -> None:
    errors = _errors_for(tmp_path, {
        "S004-single-document-capture.yaml": _drop_key_block(
            VALID_SCENARIO, key),
    })
    assert errors, "expected an error for missing " + key
    assert any("missing REQUIRED keys" in e["problem"] for e in errors)


def test_bad_file_stem_is_an_error(tmp_path: Path) -> None:
    errors = _errors_for(tmp_path, {"notastem.yaml": VALID_SCENARIO})
    assert any("stem must be S###" in e["problem"] for e in errors)


def test_id_stem_mismatch_is_an_error(tmp_path: Path) -> None:
    errors = _errors_for(tmp_path, {
        "S004-other-scenario-name.yaml": VALID_SCENARIO,
    })
    assert any("id/stem mismatch" in e["problem"] for e in errors)


def test_bad_status_enum_is_an_error(tmp_path: Path) -> None:
    errors = _errors_for(tmp_path, {
        "S004-single-document-capture.yaml":
            VALID_SCENARIO.replace("status: UNKNOWN", "status: DONE"),
    })
    assert any("status must be one of" in e["problem"] for e in errors)


def test_low_timeout_is_an_error(tmp_path: Path) -> None:
    errors = _errors_for(tmp_path, {
        "S004-single-document-capture.yaml":
            VALID_SCENARIO.replace("timeout_seconds: 2400",
                                   "timeout_seconds: 59"),
    })
    assert any("timeout_seconds must be an integer >= 60"
               in e["problem"] for e in errors)


def test_low_step_timeout_is_an_error(tmp_path: Path) -> None:
    errors = _errors_for(tmp_path, {
        "S004-single-document-capture.yaml":
            VALID_SCENARIO.replace("step_timeout_seconds: 180",
                                   "step_timeout_seconds: 29"),
    })
    assert any("step_timeout_seconds must be an integer >= 30"
               in e["problem"] for e in errors)


def test_string_timeout_is_an_error(tmp_path: Path) -> None:
    errors = _errors_for(tmp_path, {
        "S004-single-document-capture.yaml":
            VALID_SCENARIO.replace("timeout_seconds: 2400",
                                   "timeout_seconds: '2400'"),
    })
    assert any("timeout_seconds must be an integer"
               in e["problem"] for e in errors)


def test_missing_requires_is_an_error(tmp_path: Path) -> None:
    body = VALID_SCENARIO.replace(
        "  requires:\n    gui: true\n    adb: true\n"
        "    android_emulator: true\n    android_sdk: true\n"
        "    android_cli: true\n    emulator_acceleration:\n"
        "      allowed:\n      - none\n    camera_fixture: true\n",
        "")
    errors = _errors_for(tmp_path, {
        "S004-single-document-capture.yaml": body,
    })
    assert any("meta.requires must be a typed mapping" in e["problem"]
               for e in errors)


def test_untyped_requires_is_an_error(tmp_path: Path) -> None:
    body = VALID_SCENARIO.replace(
        "  requires:\n    gui: true\n    adb: true\n"
        "    android_emulator: true\n    android_sdk: true\n"
        "    android_cli: true\n    emulator_acceleration:\n"
        "      allowed:\n      - none\n    camera_fixture: true\n",
        "  requires:\n  - gui\n  - adb\n")
    errors = _errors_for(tmp_path, {
        "S004-single-document-capture.yaml": body,
    })
    assert any("meta.requires must be a typed mapping" in e["problem"]
               for e in errors)


def test_requires_missing_required_key_is_an_error(tmp_path: Path) -> None:
    body = VALID_SCENARIO.replace("    adb: true\n", "")
    errors = _errors_for(tmp_path, {
        "S004-single-document-capture.yaml": body,
    })
    assert any("meta.requires missing required keys: adb" in e["problem"]
               for e in errors)


def test_requires_unknown_capability_is_an_error(tmp_path: Path) -> None:
    body = VALID_SCENARIO.replace(
        "    camera_fixture: true\n",
        "    camera_fixture: true\n    quantum_link: true\n")
    errors = _errors_for(tmp_path, {
        "S004-single-document-capture.yaml": body,
    })
    assert any("unknown capability key" in e["problem"] for e in errors)


def test_requires_android_studio_is_rejected(tmp_path: Path) -> None:
    body = VALID_SCENARIO.replace(
        "    camera_fixture: true\n",
        "    camera_fixture: true\n    android_studio: true\n")
    errors = _errors_for(tmp_path, {
        "S004-single-document-capture.yaml": body,
    })
    assert any("android_studio" in e["problem"] for e in errors)


def test_requires_non_boolean_capability_is_an_error(tmp_path: Path) -> None:
    body = VALID_SCENARIO.replace("    gui: true\n", "    gui: 'yes'\n")
    errors = _errors_for(tmp_path, {
        "S004-single-document-capture.yaml": body,
    })
    assert any("meta.requires.gui must be a boolean" in e["problem"]
               for e in errors)


def test_requires_bad_acceleration_value_is_an_error(tmp_path: Path) -> None:
    body = VALID_SCENARIO.replace("      - none\n", "      - software\n")
    errors = _errors_for(tmp_path, {
        "S004-single-document-capture.yaml": body,
    })
    assert any("emulator_acceleration" in e["problem"] for e in errors)


def test_requires_acceleration_not_a_mapping_is_an_error(
        tmp_path: Path) -> None:
    body = VALID_SCENARIO.replace(
        "    emulator_acceleration:\n      allowed:\n      - none\n",
        "    emulator_acceleration: none\n")
    errors = _errors_for(tmp_path, {
        "S004-single-document-capture.yaml": body,
    })
    assert any("emulator_acceleration" in e["problem"] for e in errors)


def test_yaml_parse_error_is_a_validation_error(tmp_path: Path) -> None:
    errors = _errors_for(tmp_path, {
        "S004-single-document-capture.yaml": "id: [unclosed\n",
    })
    assert any("YAML parse error" in e["problem"] for e in errors)


def test_duplicate_scenario_numbers_are_errors(tmp_path: Path) -> None:
    other = VALID_SCENARIO.replace(
        "id: single-document-capture", "id: another-capture")
    errors = _errors_for(tmp_path, {
        "S004-single-document-capture.yaml": VALID_SCENARIO,
        "S004-another-capture.yaml": other,
    })
    assert any("duplicate scenario number S004" in e["problem"]
               for e in errors)


# ------------------------------------------------------ gap detection

def test_scenario_without_ledger_entry_is_reported(tmp_path: Path) -> None:
    scenarios, ledger_path, _ = _build_repo(tmp_path)
    outcome = reconcile(scenarios, ledger_path)
    # The default synthetic repo matches S004 exactly: no gap.
    assert outcome.scenario_without_entry == []
    extra = VALID_SCENARIO.replace(
        "id: single-document-capture", "id: id-card-scan")
    (scenarios / "S019-id-card-scan.yaml").write_text(extra, encoding="utf-8")
    outcome = reconcile(scenarios, ledger_path)
    assert [gap["id"] for gap in outcome.scenario_without_entry] == ["S019"]
    assert outcome.scenario_without_entry[0]["scenario"] == "id-card-scan"


def test_ledger_entry_without_scenario_is_reported(tmp_path: Path) -> None:
    ledger = {
        "entries": list(VALID_LEDGER["entries"]) + [{
            "id": "S099", "scenario": "ghost-scenario",
            "title": "Ghost", "status": "UNKNOWN",
        }]
    }
    scenarios, ledger_path, _ = _build_repo(tmp_path, ledger=ledger)
    outcome = reconcile(scenarios, ledger_path)
    assert [gap["id"] for gap in outcome.entry_without_scenario] == ["S099"]
    assert outcome.entry_without_scenario[0]["scenario"] == "ghost-scenario"


def test_status_disagreement_is_reported(tmp_path: Path) -> None:
    ledger = {
        "entries": [{
            "id": "S004", "scenario": "single-document-capture",
            "title": "Single-document capture end-to-end",
            "status": "PASS",
        }]
    }
    scenarios, ledger_path, _ = _build_repo(tmp_path, ledger=ledger)
    outcome = reconcile(scenarios, ledger_path)
    assert len(outcome.status_disagreements) == 1
    gap = outcome.status_disagreements[0]
    assert gap["yaml_status"] == "UNKNOWN"
    assert gap["ledger_status"] == "PASS"
    assert gap["file"] == "S004-single-document-capture.yaml"


# ------------------------------------------------------ determinism + CLI

def test_two_runs_produce_byte_identical_reports(tmp_path: Path) -> None:
    scenarios, ledger_path, _report_dir = _build_repo(tmp_path)
    first = reconcile(scenarios, ledger_path)
    second = reconcile(scenarios, ledger_path)
    assert first.report_json_text() == second.report_json_text()
    assert first.report_markdown() == second.report_markdown()


def test_cli_writes_both_reports(tmp_path: Path,
                                 capsys: pytest.CaptureFixture) -> None:
    scenarios, ledger_path, report_dir = _build_repo(tmp_path)
    code = main(["reconcile", "--scenarios-dir", str(scenarios),
                 "--ledger", str(ledger_path),
                 "--report-dir", str(report_dir)])
    assert code == 0
    out = capsys.readouterr().out
    assert "wrote reconcile-report.json" in out
    assert "wrote reconcile-report.md" in out
    assert (report_dir / "reconcile-report.json").is_file()
    assert (report_dir / "reconcile-report.md").is_file()


def test_cli_check_fresh_reports_exit_zero(tmp_path: Path) -> None:
    scenarios, ledger_path, report_dir = _build_repo(tmp_path)
    assert main(["reconcile", "--scenarios-dir", str(scenarios),
                 "--ledger", str(ledger_path),
                 "--report-dir", str(report_dir)]) == 0
    assert main(["reconcile", "--scenarios-dir", str(scenarios),
                 "--ledger", str(ledger_path),
                 "--report-dir", str(report_dir), "--check"]) == 0


def test_cli_check_stale_reports_exit_one(tmp_path: Path) -> None:
    scenarios, ledger_path, report_dir = _build_repo(tmp_path)
    assert main(["reconcile", "--scenarios-dir", str(scenarios),
                 "--ledger", str(ledger_path),
                 "--report-dir", str(report_dir)]) == 0
    report = report_dir / "reconcile-report.json"
    original = report.read_text(encoding="utf-8")
    report.write_text("{}\n", encoding="utf-8")
    code = main(["reconcile", "--scenarios-dir", str(scenarios),
                 "--ledger", str(ledger_path),
                 "--report-dir", str(report_dir), "--check"])
    assert code == 1
    # check never repaired the file
    assert report.read_text(encoding="utf-8") == "{}\n"
    assert original != "{}\n"


def test_cli_check_missing_reports_exit_one(tmp_path: Path) -> None:
    scenarios, ledger_path, report_dir = _build_repo(tmp_path)
    code = main(["reconcile", "--scenarios-dir", str(scenarios),
                 "--ledger", str(ledger_path),
                 "--report-dir", str(report_dir), "--check"])
    assert code == 1


def test_cli_check_with_validation_error_exits_one(tmp_path: Path) -> None:
    scenarios, ledger_path, report_dir = _build_repo(
        tmp_path, scenario_files={
            "S004-single-document-capture.yaml":
                VALID_SCENARIO.replace("status: UNKNOWN", "status: DONE"),
        })
    assert main(["reconcile", "--scenarios-dir", str(scenarios),
                 "--ledger", str(ledger_path),
                 "--report-dir", str(report_dir)]) == 1
    assert main(["reconcile", "--scenarios-dir", str(scenarios),
                 "--ledger", str(ledger_path),
                 "--report-dir", str(report_dir), "--check"]) == 1


def test_cli_write_mode_with_validation_error_prints_stderr(
        tmp_path: Path, capsys: pytest.CaptureFixture) -> None:
    scenarios, ledger_path, report_dir = _build_repo(
        tmp_path, scenario_files={
            "S004-single-document-capture.yaml":
                VALID_SCENARIO.replace("timeout_seconds: 2400",
                                       "timeout_seconds: 10"),
        })
    code = main(["reconcile", "--scenarios-dir", str(scenarios),
                 "--ledger", str(ledger_path),
                 "--report-dir", str(report_dir)])
    assert code == 1
    err = capsys.readouterr().err
    assert "timeout_seconds" in err
    # reports still document the errors
    report = json.loads(
        (report_dir / "reconcile-report.json").read_text(encoding="utf-8"))
    assert report["summary"]["validation_errors"] >= 1


def test_cli_gaps_alone_exit_zero(tmp_path: Path,
                                  capsys: pytest.CaptureFixture) -> None:
    extra = VALID_SCENARIO.replace(
        "id: single-document-capture", "id: id-card-scan")
    scenarios, ledger_path, report_dir = _build_repo(
        tmp_path, scenario_files={
            "S004-single-document-capture.yaml": VALID_SCENARIO,
            "S019-id-card-scan.yaml": extra,
        })
    code = main(["reconcile", "--scenarios-dir", str(scenarios),
                 "--ledger", str(ledger_path),
                 "--report-dir", str(report_dir)])
    assert code == 0
    out = capsys.readouterr().out
    assert "scenario without entry: 1" in out


def test_ledger_is_never_mutated(tmp_path: Path) -> None:
    scenarios, ledger_path, report_dir = _build_repo(tmp_path)
    before = ledger_path.read_bytes()
    main(["reconcile", "--scenarios-dir", str(scenarios),
          "--ledger", str(ledger_path), "--report-dir", str(report_dir)])
    main(["reconcile", "--scenarios-dir", str(scenarios),
          "--ledger", str(ledger_path), "--report-dir", str(report_dir),
          "--check"])
    assert ledger_path.read_bytes() == before


def test_scenario_files_are_never_mutated(tmp_path: Path) -> None:
    scenarios, ledger_path, report_dir = _build_repo(tmp_path)
    before = {
        p.name: p.read_bytes() for p in sorted(scenarios.glob("*.yaml"))}
    main(["reconcile", "--scenarios-dir", str(scenarios),
          "--ledger", str(ledger_path), "--report-dir", str(report_dir)])
    after = {
        p.name: p.read_bytes() for p in sorted(scenarios.glob("*.yaml"))}
    assert before == after


def test_missing_scenarios_dir_is_operational_error(
        tmp_path: Path) -> None:
    ledger_path = tmp_path / "ledger.json"
    ledger_path.write_text(json.dumps(VALID_LEDGER), encoding="utf-8")
    with pytest.raises(ParityCliError):
        reconcile(tmp_path / "nope", ledger_path)
    code = main(["reconcile", "--scenarios-dir", str(tmp_path / "nope"),
                 "--ledger", str(ledger_path),
                 "--report-dir", str(tmp_path)])
    assert code == 1


def test_unreadable_ledger_is_operational_error(tmp_path: Path) -> None:
    scenarios, _, report_dir = _build_repo(tmp_path)
    bad = tmp_path / "bad-ledger.json"
    bad.write_text("{not json", encoding="utf-8")
    with pytest.raises(ParityCliError):
        reconcile(scenarios, bad)
    code = main(["reconcile", "--scenarios-dir", str(scenarios),
                 "--ledger", str(bad), "--report-dir", str(report_dir)])
    assert code == 1


# ------------------------------------------------------ report structure

def test_report_json_gaps_sorted_by_id(tmp_path: Path) -> None:
    extra_a = VALID_SCENARIO.replace(
        "id: single-document-capture", "id: book-spread-scan")
    extra_b = VALID_SCENARIO.replace(
        "id: single-document-capture", "id: id-card-scan")
    scenarios, ledger_path, _ = _build_repo(
        tmp_path, scenario_files={
            "S004-single-document-capture.yaml": VALID_SCENARIO,
            "S021-book-spread-scan.yaml": extra_a,
            "S019-id-card-scan.yaml": extra_b,
        })
    outcome = reconcile(scenarios, ledger_path)
    ids = [gap["id"] for gap in outcome.scenario_without_entry]
    assert ids == ["S019", "S021"]


def test_report_markdown_has_sections(tmp_path: Path) -> None:
    scenarios, ledger_path, _ = _build_repo(tmp_path)
    outcome = reconcile(scenarios, ledger_path)
    md = outcome.report_markdown()
    assert md.startswith("# CamScan parity reconciliation report")
    assert "## Summary" in md
    assert "## Validation errors" in md
    assert "## Scenario without ledger entry" in md
    assert "## Ledger entry without scenario file" in md
    assert "## Status disagreements" in md
    assert md.endswith("\n")


def test_report_json_schema_fields(tmp_path: Path) -> None:
    scenarios, ledger_path, _ = _build_repo(tmp_path)
    report = reconcile(scenarios, ledger_path).report_json()
    assert report["schema"] == "camscan-reconcile-report/0.1"
    assert set(report["gaps"]) == {
        "scenario_without_entry", "entry_without_scenario",
        "status_disagreements"}
    assert set(report["summary"]) == {
        "scenarios", "ledger_entries", "validation_errors",
        "scenario_without_entry", "entry_without_scenario",
        "status_disagreements"}
