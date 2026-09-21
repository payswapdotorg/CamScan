"""Provider scheduler — capability matching only, never provider identity.

The parity engine selects providers through this module:

    scenario meta.requires
            |
            v
    capability matching (provider_matches / select_provider)
            |
            v
    eligible provider -> execution

There must be no `if provider == "e2b"` branching in normal scenario execution.
The e2b provider is schedulable purely because its capability report satisfies
scenario requirements; a future Flauz/KVM provider becomes schedulable the same
way by reporting `emulator_acceleration: "kvm"`.

Run-pair consistency (reconciliation invariant): the reference run and the
implementation run of one scenario must execute on providers whose reports
agree on every capability named in that scenario's `meta.requires` — otherwise
timing/rendering-sensitive assertions compare across substrate classes.
`pair_compatible()` enforces this.
"""
from __future__ import annotations

from typing import Any, Optional

from .types import (
    CAPABILITY_KEYS,
    ENUM_CAPABILITIES,
    capability_satisfies,
)


def validate_requirement(cap: str, requirement: Any) -> Optional[str]:
    """Structural validation of one `meta.requires` entry.

    Returns an error string if malformed, else None.
    """
    if cap not in CAPABILITY_KEYS:
        return f"unknown capability {cap!r} (known: {sorted(CAPABILITY_KEYS)})"
    if cap in ENUM_CAPABILITIES:
        if isinstance(requirement, str):
            if requirement not in ENUM_CAPABILITIES[cap]:
                return (f"{cap}: {requirement!r} not in "
                        f"{list(ENUM_CAPABILITIES[cap])}")
            return None
        if isinstance(requirement, dict):
            allowed = requirement.get("allowed")
            if not isinstance(allowed, list) or not allowed:
                return f"{cap}: mapping form requires non-empty 'allowed' list"
            bad = [v for v in allowed if v not in ENUM_CAPABILITIES[cap]]
            if bad:
                return f"{cap}: bad allowed values {bad} (allowed: {list(ENUM_CAPABILITIES[cap])})"
            return None
        return f"{cap}: requirement must be a string or {{allowed: [...]}}"
    if not isinstance(requirement, bool):
        return f"{cap}: boolean capability requires a boolean requirement, got {requirement!r}"
    return None


def validate_requirements(requires: Any) -> list[str]:
    """Validate a whole `meta.requires` mapping; returns error list (empty=ok)."""
    if not isinstance(requires, dict):
        return ["meta.requires must be a typed mapping (capability -> requirement), not a list"]
    errors = []
    for cap, req in requires.items():
        e = validate_requirement(cap, req)
        if e:
            errors.append(e)
    return errors


def validate_report(report: Any) -> list[str]:
    """Structural validation of a provider capability report."""
    if not isinstance(report, dict):
        return ["capability report must be a mapping"]
    errors = []
    if not isinstance(report.get("slug"), str) or not report.get("slug"):
        errors.append("report: missing 'slug'")
    for key in CAPABILITY_KEYS:
        if key not in report:
            errors.append(f"report: missing capability {key!r}")
            continue
        value = report[key]
        if key in ENUM_CAPABILITIES:
            if value not in ENUM_CAPABILITIES[key]:
                errors.append(f"report: {key}={value!r} not in {list(ENUM_CAPABILITIES[key])}")
        elif not isinstance(value, bool):
            errors.append(f"report: {key} must be boolean, got {value!r}")
    return errors


def provider_matches(report: dict[str, Any],
                     requires: dict[str, Any]) -> tuple[bool, list[str]]:
    """Does the provider's capability report satisfy the requirements?

    Returns (eligible, unsatisfied_list). Requirements for capabilities the
    scenario does not name are unconstrained.
    """
    unsatisfied: list[str] = []
    for cap, req in requires.items():
        if cap not in report:
            unsatisfied.append(f"{cap}: provider does not report it")
            continue
        if not capability_satisfies(req, report[cap]):
            unsatisfied.append(f"{cap}: requirement {req!r} not satisfied by {report[cap]!r}")
    return (not unsatisfied, unsatisfied)


def select_provider(requires: dict[str, Any],
                    reports: list[dict[str, Any]]) -> tuple[Optional[dict[str, Any]], dict[str, list[str]]]:
    """Pick an eligible provider from capability reports.

    Returns (chosen_report_or_None, per_provider_unsatisfied). Selection order
    is stable (first eligible in the given order); callers rank reports by
    their own policy (e.g. prefer kvm over none once both exist).
    """
    reasons: dict[str, list[str]] = {}
    for report in reports:
        slug = report.get("slug", "<unknown>")
        ok, unsatisfied = provider_matches(report, requires)
        reasons[slug] = unsatisfied
        if ok:
            return report, reasons
    return None, reasons


def pair_compatible(report_a: dict[str, Any],
                    report_b: dict[str, Any],
                    requires: dict[str, Any]) -> tuple[bool, list[str]]:
    """Reconciliation invariant: both runs of a scenario must agree on every
    capability the scenario names (same acceleration class, same capture
    abilities), so reference/implementation evidence is comparable."""
    mismatches: list[str] = []
    for cap in requires:
        if cap in report_a and cap in report_b and report_a[cap] != report_b[cap]:
            mismatches.append(f"{cap}: {report_a[cap]!r} != {report_b[cap]!r}")
    return (not mismatches, mismatches)
