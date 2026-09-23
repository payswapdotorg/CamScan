"""Shared test helpers: synthetic manifest pairs (deterministic).

Everything is byte-deterministic — no randomness, no wall-clock stamps
— so the determinism tests can compare bytes directly. Manifests match
the EVIDENCE.md contract shape exactly (validated by the evidence-cli
schema validator in the interop test; one test also builds a bundle
through the real ``tools.evidence_cli.bundle_run`` and feeds it to the
comparator).
"""
from __future__ import annotations

import hashlib
from pathlib import Path
from typing import Any

from tools.evidence_cli.jsonio import dump as jsonio_dump

REPO_ROOT = Path(__file__).resolve().parents[3]
SCENARIO_S004 = (REPO_ROOT / "lab" / "scenarios"
                 / "S004-single-document-capture.yaml").read_text(
                     encoding="utf-8")

RUN_ID = "20260923T120000Z-S004-unit01"

#: A matched 5-step trace (all outcomes in the ok class).
DEFAULT_TRACE: list[dict[str, Any]] = [
    {"t_ms": 0, "action": "launch", "target": "",
     "result": "activity-resumed"},
    {"t_ms": 1400, "action": "tap", "target": "scan", "result": "ok"},
    {"t_ms": 3200, "action": "capture", "target": "camera",
     "result": "document-detected"},
    {"t_ms": 9800, "action": "accept-document", "target": "crop-confirm",
     "result": "ok"},
    {"t_ms": 11200, "action": "save", "target": "save-button",
     "result": "saved"},
]

#: Trace with the reference-app-specific premium upsell step (mask demo).
TRACE_WITH_UPSELL: list[dict[str, Any]] = [
    *DEFAULT_TRACE[:4],
    {"t_ms": 12400, "action": "tap", "target": "premium-upsell-dismiss",
     "result": "ok"},
    {"t_ms": 13600, "action": "save", "target": "save-button",
     "result": "saved"},
]


def synth_sha(marker: str) -> str:
    """Deterministic 64-hex sha256 for synthetic content markers."""
    return hashlib.sha256(marker.encode("utf-8")).hexdigest()


def _step_name(step: dict[str, Any], number: int) -> str:
    action = step.get("action") or "step"
    return f"{number:02d}-{action}"


def default_artifacts(trace: list[dict[str, Any]] | None = None,
                      *, shared: bool = True) -> list[dict[str, Any]]:
    """Artifacts covering every step (screenshots + ui), plus the shared
    bundle files. sha256 markers are side-independent when ``shared`` —
    a matched pair then produces no output divergences."""
    trace = DEFAULT_TRACE if trace is None else trace
    prefix = "shared" if shared else None
    entries: list[dict[str, Any]] = []
    for number, step in enumerate(trace, start=1):
        name = _step_name(step, number)
        for category, ext in (("screenshots", ".png"), ("ui", ".xml")):
            path = f"{category}/{name}{ext}"
            marker = f"{prefix}-{path}" if prefix else path
            entries.append({"path": path, "sha256": synth_sha(marker),
                            "bytes": 256})
    entries.append({"path": "logs/logcat.txt",
                    "sha256": synth_sha("logcat"), "bytes": 512})
    entries.append({"path": "logs/app-events.json",
                    "sha256": synth_sha("app-events"), "bytes": 128})
    entries.append({"path": "recordings/screen.mp4",
                    "sha256": synth_sha("screen"), "bytes": 2048})
    for name in ("document.pdf", "page-01.jpg"):
        marker = f"{prefix}-outputs/{name}" if prefix else f"outputs/{name}"
        entries.append({"path": f"outputs/{name}",
                        "sha256": synth_sha(marker), "bytes": 1024})
    entries.sort(key=lambda e: e["path"])
    return entries


def base_manifest(run_id: str, subject: str) -> dict[str, Any]:
    """A schema-valid EVIDENCE.md manifest for one subject."""
    is_reference = subject == "reference"
    return {
        "run_id": run_id,
        "scenario": "single-document-capture",
        "subject": subject,
        "provider": {
            "slug": "e2b",
            "capabilities": {
                "gui": True, "adb": True, "android_emulator": True,
                "android_sdk": True, "android_cli": True,
                "emulator_acceleration": "none", "camera_fixture": True,
                "screenshots": True, "recording": True, "snapshot": True,
                "persistent": False, "android_studio": False,
            },
            "environment_id": f"env-e2b-{'ref' if is_reference else 'impl'}",
        },
        "application": {
            "package": ("com.intsig.camscanner" if is_reference
                        else "org.payswap.camscan"),
            "version_name": "7.25.5" if is_reference else "0.1.0",
            "version_code": 2609020000 if is_reference else 1,
            "installer_sha256": synth_sha(f"installer-{subject}"),
        },
        "device": {
            "model": "Pixel 4 (AVD pixel_4)",
            "android_version": "11",
            "screen": "1080x2280@440dpi",
            "locale": "en-US",
            "timezone": "UTC",
            "permission_baseline": {"android.permission.CAMERA": True},
        },
        "fixtures": [{"id": "clean-a4", "sha256": synth_sha("clean-a4")}],
        "artifacts": default_artifacts(),
        "action_trace": [dict(step) for step in DEFAULT_TRACE],
        "started_at": "2026-09-23T12:00:00Z",
        "finished_at": "2026-09-23T12:01:04Z",
    }


def deep_merge(base: dict[str, Any], overrides: dict[str, Any] | None
               ) -> dict[str, Any]:
    """Recursively apply ``overrides`` onto a copy of ``base`` (dicts
    merge, everything else replaces)."""
    import copy
    out = copy.deepcopy(base)
    if not overrides:
        return out
    for key, value in overrides.items():
        if (key in out and isinstance(out[key], dict)
                and isinstance(value, dict)):
            out[key] = deep_merge(out[key], value)
        else:
            out[key] = copy.deepcopy(value)
    return out


def build_pair(base: Path, *, run_id: str = RUN_ID,
               ref_overrides: dict[str, Any] | None = None,
               impl_overrides: dict[str, Any] | None = None,
               ref_manifest: dict[str, Any] | None = None,
               impl_manifest: dict[str, Any] | None = None,
               with_reference: bool = True,
               with_implementation: bool = True,
               scenario_text: str = SCENARIO_S004) -> Path:
    """Write a synthetic paired run dir under ``base/runs/<run-id>``.

    Full ``ref_manifest``/``impl_manifest`` (pre-built documents) win
    over the ``*_overrides`` deep-merges — for structural edits the
    merge cannot express (e.g. removing a permission key)."""
    run_dir = base / "runs" / run_id
    for side, overrides, manifest, present in (
            ("reference", ref_overrides, ref_manifest, with_reference),
            ("implementation", impl_overrides, impl_manifest,
             with_implementation)):
        if not present:
            continue
        subject_dir = run_dir / side
        subject_dir.mkdir(parents=True, exist_ok=True)
        if manifest is None:
            manifest = deep_merge(base_manifest(run_id, side), overrides)
        jsonio_dump(subject_dir / "manifest.json", manifest)
    (run_dir).mkdir(parents=True, exist_ok=True)
    (run_dir / "scenario.yaml").write_text(scenario_text, encoding="utf-8")
    return run_dir


def compare_pair(base: Path, run_id: str = RUN_ID, *,
                 use_masks: bool = True, masks_dir: Path | None = None,
                 **pair_kwargs: Any):
    """Build a pair + run the comparator (hermetic empty masks dir by
    default, so the tool's shipped masks never leak into unit tests)."""
    from tools.parity_cli.compare import compare_run
    build_pair(base, run_id=run_id, **pair_kwargs)
    if masks_dir is None:
        masks_dir = base / "masks-empty"
        masks_dir.mkdir(exist_ok=True)
    return compare_run(run_id, runs_dir=base / "runs", masks_dir=masks_dir,
                       use_masks=use_masks)


def entry_ids(diff: dict[str, Any]) -> set[str]:
    return {entry["id"] for entry in diff.get("entries") or []}


def entry_map(diff: dict[str, Any]) -> dict[str, dict[str, Any]]:
    return {entry["id"]: entry for entry in diff.get("entries") or []}


# ------------------------------------------------------ ledger fixtures

LEDGER_STATUSES = ["UNKNOWN", "DISCOVERED", "SPECIFIED", "IMPLEMENTED",
                   "PARTIAL", "BLOCKED", "PASS"]

SCENARIO_YAML_S004 = """\
# migrated to DSL v0.1 (typed meta.requires + explicit timeouts)
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
  - crop-stage-visible
  - save-succeeds
  ui:
  - scan-control-visible
  - preview-visible
  state:
  - document-added-to-library
  output:
  - pdf-created
  - pdf-readable
  - one-page-document
meta:
  timeout_seconds: 2400
  step_timeout_seconds: 180
  requires:
    gui: true
    adb: true
    android_emulator: true
  owner: lead
  notes: unit-test scenario copy
"""


def ledger_doc(status: str = "UNKNOWN") -> dict[str, Any]:
    return {
        "$schema": "lab/parity-ledger/ledger.schema.json",
        "updated": "2026-09-21T13:25:00Z",
        "updated_by": "tech-lead",
        "statuses": list(LEDGER_STATUSES),
        "blocked_dependencies": [],
        "resolved_dependencies": [],
        "entries": [{
            "id": "S004",
            "scenario": "single-document-capture",
            "title": "Single-document capture end-to-end",
            "status": status,
            "capability": "single-document-capture",
            "owner": None,
            "last_run": None,
            "evidence": [],
            "notes": "provisional \u2014 pending reference discovery",
        }],
    }


def build_repo(base: Path) -> Path:
    """A minimal repo tree: ledger + scenario + runs/ for ledger tests.
    The ledger is written in the production key order (document order,
    like the committed lab/parity-ledger/ledger.json — ledger-update
    preserves it)."""
    import json
    repo = base / "repo"
    (repo / "lab" / "parity-ledger").mkdir(parents=True, exist_ok=True)
    (repo / "lab" / "scenarios").mkdir(parents=True, exist_ok=True)
    (repo / "lab" / "reconciliation").mkdir(parents=True, exist_ok=True)
    (repo / "lab" / "parity-ledger" / "ledger.json").write_text(
        json.dumps(ledger_doc(), indent=2) + "\n", encoding="utf-8")
    (repo / "lab" / "scenarios" / "S004-single-document-capture.yaml"
     ).write_text(SCENARIO_YAML_S004, encoding="utf-8")
    (repo / "runs").mkdir(exist_ok=True)
    return repo
