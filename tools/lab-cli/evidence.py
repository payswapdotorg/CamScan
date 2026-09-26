"""Run-dir naming, fixture binding, and the bundle/assemble pipeline
(CAMSCAN-007).

The run-dir contract consumed downstream (EVIDENCE.md run-dir layout
decision, lead 2026-09-23; parity-cli ``load.py`` is the reader-side
truth): no run-root manifest; each subject subtree is a COMPLETE
single-subject bundle — own ``manifest.json`` at the subtree root, own
artifacts, own sidecars; ``scenario.yaml`` at the RUN root.

``tools.evidence_cli.bundle_run`` (the reference manifest writer)
operates on a **single-subject run dir** and writes ``manifest.json`` at
its root — so the assembly is the same shuffle the committed demo pair
documents (``tools/parity-cli/tests/make_demo_pair.py``):

1. the driver writes ``<stage>/<subject>/…`` artifacts +
   ``run-metadata.json`` + ``scenario.yaml`` into a staging dir NAMED
   like the run (``bundle_run`` requires ``run_dir.name == run_id``);
2. ``bundle_run(stage)`` hashes the artifacts, writes sidecars and the
   manifest (deterministic bytes);
3. the subject subtree + its manifest move under the paired run root
   ``runs/<run-id>/<subject>/`` and the scenario copy lands at the run
   root (one copy, shared by both subjects).

Determinism: identical (scenario, run_id, subject, driver) input ⇒
byte-identical trees — timestamps come from the run-id stamp (never the
wall clock), artifact bytes from the driver's deterministic payloads.
"""
from __future__ import annotations

import re
import shutil
from collections.abc import Callable
from datetime import UTC, datetime, timedelta
from pathlib import Path
from typing import Any

from tools.evidence_cli.bundle import bundle_run
from tools.evidence_cli.jsonio import load as jsonio_load
from tools.lab_cli.scenarios import LabCliError, Scenario

#: Run-id stamp prefix (EVIDENCE.md: ``<YYYYMMDDTHHMMSSZ>-S###-<suffix>``).
STAMP_RE = re.compile(r"^\d{8}T\d{6}Z$")

#: ISO-8601 UTC format used by manifest timestamps.
UTC_FORMAT = "%Y-%m-%dT%H:%M:%SZ"

#: Deterministic execution duration baked into recording manifests:
#: fixed settle + per-step budget slice (evidence-derived, never wall clock).
SETTLE_S = 60
PER_STEP_S = 5


def parse_stamp(stamp: str) -> datetime:
    """Parse a ``YYYYMMDDTHHMMSSZ`` stamp (raises LabCliError)."""
    if not STAMP_RE.match(stamp or ""):
        raise LabCliError(
            f"stamp {stamp!r} must match YYYYMMDDTHHMMSSZ (UTC)")
    return datetime.strptime(stamp, "%Y%m%dT%H%M%SZ").replace(
        tzinfo=UTC)


def format_utc(moment: datetime) -> str:
    return moment.astimezone(UTC).strftime(UTC_FORMAT)


def new_run_id(stem: str, suffix: str, stamp: str) -> str:
    """``<stamp>-S###-<suffix>`` (the EVIDENCE.md run-id shape)."""
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]*", suffix or ""):
        raise LabCliError(
            f"run-id suffix {suffix!r} must start alphanumeric "
            "(allowed: A-Za-z0-9._-)")
    return f"{stamp}-{stem}-{suffix}"


def execution_window(stamp: str, n_steps: int) -> tuple[str, str]:
    """Deterministic (started_at, finished_at) for a recording run.

    started = the run-id stamp; finished = stamp + settle + per-step
    slice — evidence-derived (the run-id timestamp), identical for
    identical inputs, never the tool's wall clock.
    """
    start = parse_stamp(stamp)
    end = start + timedelta(seconds=SETTLE_S + PER_STEP_S * max(n_steps, 1))
    return format_utc(start), format_utc(end)


# ------------------------------------------------------------------ fixtures

def fixture_entries(scenario: Scenario, repo_root: Path) \
        -> list[dict[str, str]]:
    """The scenario's pinned fixture bindings ``[{id, sha256}]``.

    Camera/sequence fixture ids resolve against the corpus manifest
    (``lab/fixtures/manifest.json`` — the whitelist); the sha is the
    fixture's first file/frame, exactly the binding the committed demo
    pair records. Scenario-internal references (``open-document(<id>)``
    step args) bind the same way — one entry per distinct id.
    """
    ids: list[str] = []
    fixture = scenario.fixture or {}
    for key in ("camera", "sequence"):
        value = fixture.get(key)
        if isinstance(value, str) and value:
            ids.append(value)
    # step-embedded document references: open-document(clean-a4) etc.
    for raw in scenario.steps:
        text = raw if isinstance(raw, str) else str(raw)
        match = re.match(r"^[a-z-]+-document\(([^)]+)\)", text.strip())
        if match:
            ids.append(match.group(1))
    ordered: list[str] = []
    for fid in ids:
        if fid not in ordered:
            ordered.append(fid)
    if not ordered:
        return []
    corpus_path = Path(repo_root) / "lab" / "fixtures" / "manifest.json"
    if not corpus_path.is_file():
        raise LabCliError(
            f"fixture corpus missing: {corpus_path} (scenario "
            f"{scenario.stem} references fixtures)")
    try:
        corpus = jsonio_load(corpus_path)
    except (OSError, ValueError) as e:
        raise LabCliError(f"fixture corpus unreadable: {e}") from e
    index: dict[str, str] = {}
    for group, files_key in (("documents", "files"), ("sequences",
                                                      "frames")):
        for entry in corpus.get(group, []) or []:
            fid = entry.get("id") if isinstance(entry, dict) else None
            members = (entry.get(files_key) or []) if isinstance(entry, dict) \
                else []
            if isinstance(fid, str) and members:
                first = members[0]
                if isinstance(first, dict) and isinstance(first.get("sha256"),
                                                           str):
                    index[fid] = first["sha256"]
    out: list[dict[str, str]] = []
    for fid in ordered:
        if fid not in index:
            raise LabCliError(
                f"fixture {fid!r} (scenario {scenario.stem}) is not "
                f"declared in {corpus_path}")
        out.append({"id": fid, "sha256": index[fid]})
    return out


# ------------------------------------------------------------ staging

def stage_root(runs_dir: Path, run_id: str) -> Path:
    """Staging root for one run (``<runs_dir>/.staging/<run-id>``)."""
    return Path(runs_dir) / ".staging" / run_id


def stage_dir(runs_dir: Path, run_id: str, subject: str) -> Path:
    """The single-subject staging dir (must be NAMED like the run)."""
    return stage_root(runs_dir, run_id) / subject / run_id


def bundle_stage(stage: Path, repo_root: Path) -> int:
    """Run evidence-cli's bundle on the staged subject dir.

    Returns the artifact count; raises LabCliError on bundle problems
    (fail-closed — no manifest, no run).
    """
    result = bundle_run(stage, fixtures_ref=Path(repo_root))
    if not result.ok:
        raise LabCliError(
            "evidence-cli bundle failed for "
            f"{stage.name}/{result.subject}: " + "; ".join(result.problems))
    return len(result.artifacts)


def assemble_subject(stage: Path, runs_dir: Path, run_id: str,
                     subject: str, scenario_file: Path,
                     emit: Callable[[str], None] = print) -> Path:
    """Move the bundled subject into the paired run-dir layout.

    ``<stage>/<subject>/…`` → ``runs/<run-id>/<subject>/…``;
    ``<stage>/manifest.json`` → ``runs/<run-id>/<subject>/manifest.json``;
    ``scenario.yaml`` → the run root (once, shared by both subjects).
    Returns the run dir.
    """
    run_dir = Path(runs_dir) / run_id
    run_dir.mkdir(parents=True, exist_ok=True)
    src_subject = stage / subject
    dst_subject = run_dir / subject
    if dst_subject.exists():
        raise LabCliError(
            f"run dir already carries a {subject} subtree: {dst_subject}")
    shutil.move(str(src_subject), str(dst_subject))
    shutil.move(str(stage / "manifest.json"),
                str(dst_subject / "manifest.json"))
    root_scenario = run_dir / "scenario.yaml"
    if not root_scenario.exists():
        shutil.copyfile(scenario_file, root_scenario)
    return run_dir


def cleanup_staging(runs_dir: Path, run_id: str) -> None:
    """Remove the staging tree (best-effort, never raises)."""
    root = stage_root(runs_dir, run_id)
    try:
        shutil.rmtree(root, ignore_errors=True)
    except Exception:  # noqa: BLE001, S110 — teardown path, never raises
        pass
    parent = Path(runs_dir) / ".staging"
    try:
        if parent.is_dir() and not any(parent.iterdir()):
            parent.rmdir()
    except OSError:
        pass


def preserve_failed_bundle(runs_dir: Path, run_id: str) -> Path | None:
    """CAMSCAN-010J (part C) — PRESERVE a failed-bundle staging tree.

    Moves ``<runs_dir>/.staging/<run_id>`` to
    ``<runs_dir>/.staging/<run_id>-bundlefailed`` (collision-suffixed
    ``-2``, ``-3`` … when a prior preservation already holds the name)
    and returns the ABSOLUTE path — the run's captures survive for
    lead review. Provenance — the 2026-09-26 09:17:47 UTC live S002
    round: the bundle step rejected a 0-byte artifact
    ("artifacts[0].bytes must be a positive integer, got 0") and the
    failure path's cleanup then DELETED the entire staged tree — the
    round-9 dump and every capture from the run, the EXACT artifacts
    the lead needed to adjudicate the completion verdict, were
    destroyed by the pipeline that failed to bundle them. The
    round-9 dump was unrecoverable; this preservation exists so that
    never recurs. Returns None when there is no staging tree to
    preserve (nothing staged — the caller emits the failure without a
    path rather than promising a preservation that did not happen).
    """
    root = stage_root(runs_dir, run_id)
    if not root.is_dir():
        return None
    parent = Path(runs_dir) / ".staging"
    target = parent / f"{run_id}-bundlefailed"
    suffix = 2
    while target.exists():
        target = parent / f"{run_id}-bundlefailed-{suffix}"
        suffix += 1
    shutil.move(str(root), str(target))
    return target.resolve()


def scenario_copy_for_stage(stage: Path, scenario_file: Path) -> None:
    """Ensure exactly one subject dir exists + place the verbatim
    scenario copy (bundle_run requires it at the stage root)."""
    subjects = [d.name for d in stage.iterdir() if d.is_dir()]
    if subjects != ["reference"] and subjects != ["implementation"]:
        raise LabCliError(
            f"driver must write exactly one subject dir under {stage} "
            f"(found {sorted(subjects)})")
    target = stage / "scenario.yaml"
    if not target.exists():
        shutil.copyfile(scenario_file, target)


def provider_capabilities(report: dict[str, Any]) -> dict[str, Any]:
    """The manifest's ``provider.capabilities`` block from a report:
    the matchable capability keys + optional android_studio metadata,
    never the notes/timestamps."""
    keys = ("gui", "persistent", "android_sdk", "android_cli",
            "android_emulator", "emulator_acceleration", "adb", "gradle",
            "camera_fixture", "screenshots", "recording", "snapshot",
            "android_studio")
    return {k: report[k] for k in keys if k in report}
