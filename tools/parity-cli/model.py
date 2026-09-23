"""Vocabulary and result types for ``tools/parity-cli`` (CAMSCAN-005).

The comparator consumes exactly what ``tools/evidence-cli`` produces
(``lab/evidence/EVIDENCE.md`` manifest contract) — the manifest vocabulary
is imported from :mod:`tools.evidence_cli.schema`, never forked. This
module owns the parity-side vocabulary only:

- **severities** — the four GAP-FORMAT.md levels;
- **dimensions** — the work order's five comparison dimensions (a–e) plus
  the ``bundle`` precondition dimension used when a subject bundle itself
  is missing/incomplete (recorded as a critical entry; the verdict router
  turns that into BLOCKED/NOT_OBSERVED rather than FAIL);
- **verdicts** — PASS | PARTIAL | FAIL | BLOCKED | NOT_OBSERVED
  (mirrors ``lab/parity-ledger/ledger.schema.json``'s ``last_run.verdict``
  enum);
- **outcome classes** — action_trace ``result`` literals are grouped into
  ok/denied/failed classes so two runners' different-but-equivalent
  literals (``saved`` vs ``ok``) compare equal; literals outside the
  vocabulary compare by their literal value (fail-visible, never silently
  assumed ok — the engine never upgrades an unproven outcome).
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any

#: Diff-entry severities (GAP-FORMAT.md vocabulary).
SEVERITIES: tuple[str, ...] = ("critical", "high", "medium", "low")

#: Rank for deterministic severity-ordered reporting (critical first).
SEVERITY_RANK: dict[str, int] = {name: i for i, name in enumerate(SEVERITIES)}

#: Diff dimensions. ``bundle`` is the precondition dimension (subject
#: bundle missing/incomplete); the other five are the work order's a–e.
DIMENSIONS: tuple[str, ...] = ("bundle", "environment", "fixtures",
                               "artifacts", "actions", "outputs")

#: The work order's five comparison dimensions proper (a–e).
COMPARISON_DIMENSIONS: tuple[str, ...] = DIMENSIONS[1:]

#: Dimension sort index (diff entries are ordered bundle → a → e).
DIMENSION_ORDER: dict[str, int] = {name: i for i, name in enumerate(DIMENSIONS)}

#: Verdicts, in routing-precedence order (see :mod:`.verdict`).
VERDICTS: tuple[str, ...] = ("PASS", "PARTIAL", "FAIL", "BLOCKED",
                             "NOT_OBSERVED")

#: Subject-bundle statuses.
#:   ok            — manifest present, schema-valid, observation recorded
#:   missing       — subject dir or manifest.json absent
#:   invalid       — manifest fails the EVIDENCE.md schema (problems kept)
#:   no-observation— valid manifest, but the action_trace records no
#:                   successful step (nothing was observed)
BUNDLE_STATUSES: tuple[str, ...] = ("ok", "missing", "invalid",
                                    "no-observation")

#: ``action_trace[].result`` literals meaning the step succeeded
#: (runner-captured outcomes — vocabulary observed in the lab's runners
#: and reference observation notes; extensible, never invented).
_RESULT_OK: frozenset[str] = frozenset({
    "ok", "success", "succeeded", "done", "completed",
    "activity-resumed", "resumed", "visible", "appeared",
    "granted", "permission-granted", "saved", "document-detected",
    "detected", "opened", "exported", "shared", "deleted", "rotated",
    "enhanced", "applied", "accepted", "confirmed", "added",
})

#: Literals meaning a permission was denied.
_RESULT_DENIED: frozenset[str] = frozenset({
    "denied", "permission-denied", "not-granted", "blocked-permission",
})

#: Literals meaning the step failed.
_RESULT_FAILED: frozenset[str] = frozenset({
    "failed", "failure", "error", "crash", "crashed", "anr",
    "timeout", "timed-out", "not-found", "not-visible", "absent",
    "missing", "not-detected", "cancelled", "canceled", "rejected",
})


class ParityCliError(Exception):
    """Operational failure (bad invocation, unreadable run dir, broken
    mask file). Data-level problems accumulate inside the diff instead —
    the comparator records what it saw; it does not abort on it."""


def classify_result(result: Any) -> str:
    """Classify an ``action_trace`` result literal.

    Returns ``"ok" | "denied" | "failed" | "unrecorded" | "other"``.
    ``None``/empty (schema-optional field) → ``unrecorded``; unknown
    non-empty literals → ``"other"`` (compared by literal value — an
    unclassifiable outcome is never silently treated as success).
    """
    if not isinstance(result, str) or not result.strip():
        return "unrecorded"
    literal = result.strip().lower()
    if literal in _RESULT_OK:
        return "ok"
    if literal in _RESULT_DENIED:
        return "denied"
    if literal in _RESULT_FAILED:
        return "failed"
    return "other"


def result_literal(result: Any) -> str:
    """The comparable form of a result: stripped literal, or a marker."""
    if not isinstance(result, str) or not result.strip():
        return "«unrecorded»"
    return result.strip()


@dataclass
class DiffEntry:
    """One structured divergence (or bundle problem) in ``diff.json``.

    ``id`` is stable and unique within the diff (sorted into the entries
    list by ``(dimension, id)``); ``feature`` is the gap-report grouping
    key (one gap yaml per feature, per GAP-FORMAT.md).
    """
    id: str
    dimension: str
    severity: str
    difference: str
    path: str = ""
    reference: Any = None
    implementation: Any = None
    feature: str = ""
    evidence_reference: str = ""
    evidence_implementation: str = ""

    def doc(self) -> dict[str, Any]:
        out: dict[str, Any] = {
            "id": self.id,
            "dimension": self.dimension,
            "severity": self.severity,
            "difference": self.difference,
        }
        if self.path:
            out["path"] = self.path
        # values are always present (null = no evidence recorded on
        # that side) — a missing key would be indistinguishable from an
        # absent side
        out["reference"] = self.reference
        out["implementation"] = self.implementation
        if self.feature:
            out["feature"] = self.feature
        evidence: dict[str, str] = {}
        if self.evidence_reference:
            evidence["reference"] = self.evidence_reference
        if self.evidence_implementation:
            evidence["implementation"] = self.evidence_implementation
        if evidence:
            out["evidence"] = evidence
        return out


@dataclass
class Bundle:
    """One subject side of a run: manifest + derived bundle status."""
    side: str
    status: str = "missing"
    manifest: dict[str, Any] | None = None
    problems: list[str] = field(default_factory=list)
    manifest_rel: str = ""

    @property
    def ok(self) -> bool:
        return self.status == "ok"

    @property
    def manifest_path(self) -> str:
        return self.manifest_rel


@dataclass
class CompareResult:
    """Outcome of ``compare`` — the diff + verdict documents (written
    to ``runs/<run-id>/reconciliation/`` by the CLI)."""
    run_id: str
    run_dir: str = ""
    diff: dict[str, Any] = field(default_factory=dict)
    verdict: dict[str, Any] = field(default_factory=dict)
    wrote_diff: bool = False
    wrote_verdict: bool = False
    check_problems: list[str] = field(default_factory=list)

    @property
    def ok(self) -> bool:
        return not self.check_problems


def slug(value: str) -> str:
    """Deterministic slug for ids: lowercase, non-alphanumeric → ``-``."""
    out: list[str] = []
    for ch in value.strip().lower():
        if ch.isalnum():
            out.append(ch)
        elif out and out[-1] != "-":
            out.append("-")
    return "".join(out).strip("-") or "x"


def step_label(n: int) -> str:
    """1-based zero-padded step label (``03``) for stable ids/sorting."""
    return f"{n:02d}"
