"""Verdict computation — deterministic, from diff.json alone.

Routing precedence (all rules from the work order; ties resolved
documented-first, pinned by tests):

1. **BLOCKED** — the implementation bundle is missing/incomplete for
   external reasons (``subjects.implementation.status != "ok"``). A
   BLOCKED run is recorded and is never counted as PASS — even when
   the reference side also has problems (implementation precedence).
2. **NOT_OBSERVED** — the reference bundle lacks the capability
   observation (``subjects.reference.status != "ok"``). Never silently
   converted into PASS: the routing checks this before any parity
   counting.
3. **FAIL** — any critical or high divergence.
4. **PARTIAL** — medium divergences, no critical/high.
5. **PASS** — no critical/high/medium divergence. Low-only
   divergences (e.g. output-byte sha differences, which the work order
   explicitly does not require to be equal) are recorded in diff.json
   but do not block PASS — otherwise the reconciliation loop's
   terminal state would be unreachable for two different apps.

``verdict.json`` carries exactly the pinned fields:
``{run_id, scenario, verdict, summary, counts, generated_utc}`` where
``counts`` breaks divergences down by severity. ``generated_utc`` is
evidence-derived (max of both manifests' ``finished_at`` — see
:mod:`.load`), so identical evidence yields byte-identical files.
"""
from __future__ import annotations

from typing import Any

from .model import SEVERITIES, VERDICTS


def compute_verdict(diff: dict[str, Any]) -> str:
    """Route a diff document to its verdict (see module docstring)."""
    subjects = diff.get("subjects") or {}
    impl_status = ((subjects.get("implementation") or {})
                   .get("status"))
    ref_status = ((subjects.get("reference") or {}).get("status"))
    if impl_status is not None and impl_status != "ok":
        return "BLOCKED"
    if ref_status is not None and ref_status != "ok":
        return "NOT_OBSERVED"
    counts = diff.get("counts") or {}
    if counts.get("critical") or counts.get("high"):
        return "FAIL"
    if counts.get("medium"):
        return "PARTIAL"
    return "PASS"


def _bundle_reason(subject: dict[str, Any]) -> str:
    status = subject.get("status")
    if status == "missing":
        return "is missing (no manifest.json under the subject subtree)"
    if status == "invalid":
        problems = subject.get("problems") or []
        detail = problems[0] if problems else "schema problems"
        return f"fails the EVIDENCE.md manifest schema ({detail})"
    if status == "no-observation":
        return ("records no capability observation (empty/failed "
                "action_trace)")
    return "is not usable"


def summarize(verdict: str, diff: dict[str, Any]) -> str:
    """Deterministic one-line summary for verdict.json."""
    counts = diff.get("counts") or {}
    zero = {s: counts.get(s, 0) for s in SEVERITIES}
    subjects = diff.get("subjects") or {}
    if verdict == "BLOCKED":
        reason = _bundle_reason(subjects.get("implementation") or {})
        return (f"implementation bundle {reason} — external blocker "
                "recorded, never counted as PASS")
    if verdict == "NOT_OBSERVED":
        reason = _bundle_reason(subjects.get("reference") or {})
        return (f"reference bundle {reason} — the capability observation "
                "is absent; NOT_OBSERVED is never converted into PASS")
    if verdict == "FAIL":
        return (f"{zero['critical']} critical + {zero['high']} high "
                f"divergence(s) — parity failure")
    if verdict == "PARTIAL":
        return (f"no critical/high divergence; {zero['medium']} medium + "
                f"{zero['low']} low divergence(s) — partial parity")
    return (f"all critical+high parity dimensions equal; {zero['low']} "
            "low divergence(s) recorded (non-blocking)")


def verdict_doc(diff: dict[str, Any]) -> dict[str, Any]:
    """The verdict.json document for a diff (pinned field set)."""
    verdict = compute_verdict(diff)
    return {
        "run_id": diff.get("run_id"),
        "scenario": diff.get("scenario"),
        "verdict": verdict,
        "summary": summarize(verdict, diff),
        "counts": {severity: (diff.get("counts") or {})
                   .get(severity, 0) for severity in SEVERITIES},
        "generated_utc": diff.get("generated_utc"),
    }


__all__ = ["VERDICTS", "compute_verdict", "summarize", "verdict_doc"]
