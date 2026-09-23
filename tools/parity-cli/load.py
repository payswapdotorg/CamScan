"""Run-dir + manifest loading for ``tools/parity-cli``.

Locates a paired run dir (``runs/<run-id>/`` per the EVIDENCE.md
run-dir layout decision, lead 2026-09-23): each subject subtree
(``reference/``, ``implementation/``) is a complete single-subject
bundle whose ``manifest.json`` is exactly what ``tools/evidence-cli``
writes. Bundles are loaded strictly (duplicate JSON keys rejected via
``tools.evidence_cli.jsonio``) and validated with the reference
validator (:func:`tools.evidence_cli.schema.validate_manifest`) — the
manifest vocabulary is reused, never forked.

Bundle status rules (evidence-only, fail-closed):

- subject dir or ``manifest.json`` absent → ``missing``;
- unreadable/unparseable JSON or schema problems → ``invalid``;
- valid manifest whose ``action_trace`` records no successful step →
  ``no-observation`` (nothing was observed — for the reference side
  this is exactly the NOT_OBSERVED trigger);
- otherwise → ``ok``.

Also here: the shallow scenario.yaml reader (stdlib-only; the DSL's
flat ``id/title/status`` + ``assertions`` block) and the deterministic
``generated_utc`` derivation — max of both manifests' ``finished_at``
(fallbacks: the run-id timestamp, then epoch), never the tool's wall
clock, so identical evidence yields byte-identical outputs.
"""
from __future__ import annotations

import re
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from tools.evidence_cli.jsonio import load as jsonio_load
from tools.evidence_cli.schema import RUN_ID_RE, validate_manifest

from .model import Bundle, ParityCliError, classify_result

#: Run-dir root-entry names the comparator knows about (everything else
#: is ignored — root-layout enforcement is evidence-cli bundle's job).
KNOWN_ROOT_ENTRIES = frozenset({
    "scenario.yaml", "manifest.json", "run-metadata.json",
    "r2-manifest.json", "reference", "implementation", "reconciliation",
})

#: Run-id timestamp prefix, e.g. ``20260923T120000Z``.
_RUN_TS_RE = re.compile(r"^(\d{8})T(\d{6})Z")

#: Canonical UTC stamp format for generated_utc / ledger stamps.
UTC_FORMAT = "%Y-%m-%dT%H:%M:%SZ"


def repo_root_from_script() -> Path:
    """The repo root containing ``tools/parity-cli`` (script location)."""
    return Path(__file__).resolve().parents[2]


def resolve_run_dir(run_id: str, *, repo_root: Path | None = None,
                    runs_dir: Path | None = None) -> Path:
    """Locate ``runs/<run-id>``; raise if the run dir itself is absent.

    Resolution order: explicit ``runs_dir``, else ``<repo_root>/runs``
    (``repo_root`` defaults to the script's repo root).
    """
    if runs_dir is not None:
        base = Path(runs_dir)
    else:
        root = Path(repo_root) if repo_root else repo_root_from_script()
        base = root / "runs"
    run_dir = base / run_id
    if not run_dir.is_dir():
        raise ParityCliError(
            f"run dir not found: {run_dir} (expected "
            f"runs/<run-id>/reference/manifest.json + "
            f"runs/<run-id>/implementation/manifest.json)")
    return run_dir


def load_bundle(run_dir: Path, side: str) -> Bundle:
    """Load + classify one subject bundle (never raises on data)."""
    manifest_path = run_dir / side / "manifest.json"
    bundle = Bundle(side=side, status="missing",
                    manifest_rel=f"runs/{run_dir.name}/{side}/manifest.json")
    if not run_dir.is_dir() or not manifest_path.is_file():
        return bundle
    try:
        doc = jsonio_load(manifest_path)
    except (OSError, ValueError) as e:
        bundle.status = "invalid"
        bundle.problems.append(f"manifest.json unreadable/unparseable: {e}")
        return bundle
    if not isinstance(doc, dict):
        bundle.status = "invalid"
        bundle.problems.append("manifest.json: expected a JSON object")
        return bundle
    problems = validate_manifest(doc)
    if problems:
        bundle.status = "invalid"
        bundle.problems.extend(problems)
        return bundle
    trace = doc.get("action_trace") or []
    ok_steps = sum(1 for step in trace
                   if isinstance(step, dict)
                   and classify_result(step.get("result")) == "ok")
    if not trace or ok_steps == 0:
        bundle.status = "no-observation"
        bundle.manifest = doc
        return bundle
    bundle.status = "ok"
    bundle.manifest = doc
    return bundle


def load_bundles(run_dir: Path) -> dict[str, Bundle]:
    """Load both subject bundles (reference first — deterministic)."""
    return {side: load_bundle(run_dir, side)
            for side in ("reference", "implementation")}


# --------------------------------------------------------------- scenario

def parse_scenario_yaml(path: Path) -> dict[str, Any]:
    """Shallow, stdlib-only parse of a scenario DSL file.

    Extracts the top-level ``id``, ``title``, ``status`` scalars and the
    ``assertions`` block (behavior/ui/state/output lists). Comments and
    every other section are ignored — this mirrors
    ``tools/evidence_cli.bundle._scenario_id_lines``'s shallow-scan
    approach (the DSL files are flat, uniform, lead-owned).
    """
    text = Path(path).read_text(encoding="utf-8")
    fields: dict[str, str] = {}
    assertions: dict[str, list[str]] = {}
    section: str | None = None
    subsection: str | None = None
    for raw in text.splitlines():
        stripped = raw.strip()
        if not stripped or stripped.startswith("#"):
            continue
        indent = len(raw) - len(raw.lstrip(" "))
        if indent == 0:
            key, _, value = stripped.partition(":")
            key = key.strip()
            value = value.strip()
            section = key
            subsection = None
            if key in ("id", "title", "status") and value:
                fields.setdefault(key, value.strip("'\""))
            continue
        if section == "assertions":
            key, _, value = stripped.partition(":")
            key = key.strip()
            if not value and key in ("behavior", "ui", "state", "output"):
                subsection = key
                assertions.setdefault(subsection, [])
                continue
            if subsection and stripped.startswith("- "):
                item = stripped[2:].strip().strip("'\"")
                if item:
                    assertions[subsection].append(item)
    return {"id": fields.get("id", ""),
            "title": fields.get("title", ""),
            "status": fields.get("status", ""),
            "assertions": assertions}


def scenario_assertions(scenario_fields: dict[str, Any]) -> list[str]:
    """Flattened assertion ids in DSL order (behavior, ui, state, output)."""
    assertions = scenario_fields.get("assertions") or {}
    out: list[str] = []
    for key in ("behavior", "ui", "state", "output"):
        out.extend(assertions.get(key) or [])
    return out


def scenario_fields_for_run(run_dir: Path) -> dict[str, Any]:
    """Parse ``runs/<run-id>/scenario.yaml`` (EVIDENCE.md: verbatim copy
    at the run root). Missing file → empty fields (compare does not
    require it; ``gap`` does and fails closed on its own)."""
    path = run_dir / "scenario.yaml"
    if not path.is_file():
        return {"id": "", "title": "", "status": "", "assertions": {}}
    return parse_scenario_yaml(path)


# ------------------------------------------------------------ timestamps

def _canonical_utc(dt: datetime) -> str:
    return dt.astimezone(timezone.utc).strftime(UTC_FORMAT)


def _parse_stamp(value: Any) -> datetime | None:
    if not isinstance(value, str) or not value.strip():
        return None
    try:
        parsed = datetime.fromisoformat(value.strip())
    except ValueError:
        return None
    if parsed.tzinfo is None:
        return None
    return parsed


def derive_generated_utc(bundles: dict[str, Bundle],
                         run_id: str) -> str:
    """Deterministic ``generated_utc`` — evidence-derived, never the
    tool's wall clock (identical evidence ⇒ identical bytes).

    Chain: max(``finished_at`` of both loadable manifests) → the
    run-id's ``YYYYMMDDTHHMMSSZ`` prefix → epoch.
    """
    stamps: list[datetime] = []
    for side in ("reference", "implementation"):
        bundle = bundles.get(side)
        if bundle is None or bundle.manifest is None:
            continue
        parsed = _parse_stamp(bundle.manifest.get("finished_at"))
        if parsed is None:
            parsed = _parse_stamp(bundle.manifest.get("started_at"))
        if parsed is not None:
            stamps.append(parsed)
    if stamps:
        return _canonical_utc(max(stamps))
    match = _RUN_TS_RE.match(run_id)
    if match:
        date_part, time_part = match.groups()
        try:
            parsed = datetime.strptime(
                f"{date_part}T{time_part}", "%Y%m%dT%H%M%S"
            ).replace(tzinfo=timezone.utc)
            return _canonical_utc(parsed)
        except ValueError:
            pass
    return "1970-01-01T00:00:00Z"


def scenario_of_bundles(bundles: dict[str, Bundle],
                        run_dir: Path) -> str:
    """The scenario id: reference manifest → implementation manifest →
    scenario.yaml → empty (both sides unloadable)."""
    for side in ("reference", "implementation"):
        bundle = bundles.get(side)
        if bundle is not None and bundle.manifest is not None:
            scenario = bundle.manifest.get("scenario")
            if isinstance(scenario, str) and scenario:
                return scenario
    fields = scenario_fields_for_run(run_dir)
    return fields.get("id", "")


def run_number(run_id: str) -> str:
    """The ``S###`` number encoded in a compliant run id."""
    match = RUN_ID_RE.match(run_id)
    if not match:
        raise ParityCliError(
            f"run id {run_id!r} does not match "
            "<YYYYMMDDTHHMMSSZ>-S###-<suffix> — cannot derive the "
            "scenario number for the ledger/gap stems")
    return run_id.split("-", 2)[1]
