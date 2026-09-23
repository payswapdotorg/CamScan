"""The five-dimension comparison engine (CAMSCAN-005).

``compare_run`` turns two evidence bundles (exactly what
``tools/evidence-cli`` produces) into a deterministic ``diff.json``:
a flat, severity-tagged entry list plus per-dimension summaries.

Dimensions (work order a–e, plus the ``bundle`` precondition):

a. **environment** — device model / android version / screen / locale /
   timezone / permission baseline equality (EVIDENCE.md pins these as
   run-pair requirements — divergence is *high*); provider capability
   disagreement is *medium* (SCENARIO-DSL run-pair consistency);
   provider slug difference is *low* (informational).
b. **fixtures** — same fixture ids + sha256 both sides; any difference
   is *critical* (different input documents invalidate the pair).
c. **artifacts** — per-step screenshots (*high* when missing for a
   surviving trace step) and ui dumps (*medium*), detected by numeric
   step prefixes (``screenshots/03-capture.png`` ↔ trace step 3);
   category-count differences are *low*. Manifest-declared only — bulk
   bytes live in R2; the manifest is the durable truth.
d. **actions** — masked, positionally-aligned action_trace: same step
   count (*high*), same action per position (*high*), outcome classes
   per step (implementation failing/denying where the reference
   succeeded is *high*/*critical*; a failed reference observation is
   *medium* — it is not the implementation's fault); an implementation
   launch failure is *critical*.
e. **outputs** — outputs/ files by type + count + sha256. Equality is
   NOT required: sha/count divergence is *low*, a whole missing output
   type on the implementation side is *medium* — outputs never FAIL a
   verdict (recorded, not enforced).

Severity policy → verdict (see :mod:`.verdict`): critical/high ⇒ FAIL,
medium ⇒ PARTIAL, low only ⇒ PASS (output-byte divergence between two
different apps is expected and must not block the PASS the lab's loop
terminates on — pinned by tests, documented in README).
"""
from __future__ import annotations

import re
from pathlib import Path
from typing import Any

from tools.evidence_cli.jsonio import dump as jsonio_dump
from tools.evidence_cli.jsonio import dumps_deterministic

from . import masks as masks_mod
from .load import (
    derive_generated_utc,
    load_bundles,
    resolve_run_dir,
    scenario_of_bundles,
)
from .model import (
    DIMENSION_ORDER,
    DIMENSIONS,
    SEVERITIES,
    Bundle,
    CompareResult,
    DiffEntry,
    classify_result,
    result_literal,
    slug,
    step_label,
)

#: diff.json self-describing format tag.
DIFF_FORMAT = "camscan-parity-diff/1"

#: Artifact categories compared by count in dimension (c) — outputs are
#: dimension (e)'s business (no double reporting).
COUNTED_CATEGORIES = ("screenshots", "recordings", "ui", "logs")

#: Numeric step prefix on per-step artifact names (``03-capture.xml``).
_STEP_PREFIX_RE = re.compile(r"^(\d+)")


# ----------------------------------------------------------------- helpers

def _manifest(bundles: dict[str, Bundle], side: str) -> dict[str, Any]:
    bundle = bundles[side]
    assert bundle.manifest is not None  # caller guarantees ok bundles
    return bundle.manifest


def _evidence(run_id: str) -> dict[str, str]:
    return {side: f"runs/{run_id}/{side}/manifest.json"
            for side in ("reference", "implementation")}


def _entry(run_id: str, entry_id: str, dimension: str, severity: str,
           difference: str, *, path: str = "", reference: Any = None,
           implementation: Any = None, feature: str = "",
           anchor: str = "") -> DiffEntry:
    ev = _evidence(run_id)
    anchor = anchor or path
    return DiffEntry(
        id=entry_id, dimension=dimension, severity=severity,
        difference=difference, path=path or anchor,
        reference=reference, implementation=implementation,
        feature=feature or f"{dimension}-{slug(entry_id.rsplit('-', 1)[0])}",
        evidence_reference=ev["reference"] + (f"#{anchor}" if anchor else ""),
        evidence_implementation=ev["implementation"]
        + (f"#{anchor}" if anchor else ""),
    )


def _step_artifacts(manifest: dict[str, Any],
                    category: str) -> dict[int, str]:
    """Map 1-based step number → artifact path for a step-numbered
    category dir (numeric filename prefix). Non-numbered files are not
    step-associated (they are counted, not step-checked)."""
    out: dict[int, str] = {}
    for art in manifest.get("artifacts") or []:
        if not isinstance(art, dict):
            continue
        path = art.get("path")
        if not isinstance(path, str) or not path.startswith(f"{category}/"):
            continue
        match = _STEP_PREFIX_RE.match(Path(path).name)
        if match:
            out.setdefault(int(match.group(1)), path)
    return out


def _category_counts(manifest: dict[str, Any]) -> dict[str, int]:
    counts = dict.fromkeys(COUNTED_CATEGORIES, 0)
    counts["outputs"] = 0
    for art in manifest.get("artifacts") or []:
        if not isinstance(art, dict):
            continue
        path = art.get("path")
        if not isinstance(path, str) or "/" not in path:
            continue
        category = path.split("/", 1)[0]
        if category in counts:
            counts[category] += 1
    return counts


def _outputs_by_type(manifest: dict[str, Any]) -> dict[str, list[str]]:
    out: dict[str, list[str]] = {}
    for art in manifest.get("artifacts") or []:
        if not isinstance(art, dict):
            continue
        path = art.get("path")
        if not isinstance(path, str) or not path.startswith("outputs/"):
            continue
        ext = Path(path).suffix.lstrip(".").lower() or "none"
        out.setdefault(ext, []).append(
            art.get("sha256") if isinstance(art.get("sha256"), str)
            else "«unrecorded»")
    for shas in out.values():
        shas.sort()
    return out


# ---------------------------------------------------- dimension (bundle)

def _bundle_entries(run_id: str,
                    bundles: dict[str, Bundle]) -> list[DiffEntry]:
    entries: list[DiffEntry] = []
    for side in ("reference", "implementation"):
        bundle = bundles[side]
        if bundle.ok:
            continue
        anchor = f"{side}-bundle"
        if bundle.status == "missing":
            entries.append(_entry(
                run_id, f"bundle-{side}-missing", "bundle", "critical",
                f"{side} bundle missing: runs/{run_id}/{side}/manifest.json "
                "not found — the subject bundle was never produced (or was "
                "not assembled into the paired run dir)",
                path="manifest.json", reference=None, implementation=None,
                feature=f"bundle-{side}", anchor=anchor))
        elif bundle.status == "invalid":
            problems = "; ".join(bundle.problems)
            entries.append(_entry(
                run_id, f"bundle-{side}-invalid", "bundle", "critical",
                f"{side} bundle incomplete: manifest.json fails the "
                f"EVIDENCE.md schema — {problems}",
                path="manifest.json", reference=bundle.problems,
                implementation=bundle.problems,
                feature=f"bundle-{side}", anchor=anchor))
        else:  # no-observation
            entries.append(_entry(
                run_id, f"bundle-{side}-no-observation", "bundle", "critical",
                f"{side} bundle records no capability observation: the "
                "action_trace is empty or contains no successful step",
                path="action_trace", reference=None, implementation=None,
                feature=f"bundle-{side}", anchor=anchor))
    return entries


# ------------------------------------------------- dimension (a) env

_DEVICE_FIELDS = (
    ("model", "device model", "device.model", "env-device-model"),
    ("android_version", "android version", "device.android_version",
     "env-android-version"),
    ("screen", "screen", "device.screen", "env-screen"),
    ("locale", "locale", "device.locale", "env-locale"),
    ("timezone", "timezone", "device.timezone", "env-timezone"),
)


def _compare_environment(run_id: str,
                         bundles: dict[str, Bundle]) -> list[DiffEntry]:
    ref, impl = _manifest(bundles, "reference"), _manifest(bundles,
                                                            "implementation")
    entries: list[DiffEntry] = []
    ref_device = ref.get("device") or {}
    impl_device = impl.get("device") or {}
    for key, label, path, entry_id in _DEVICE_FIELDS:
        ref_value, impl_value = ref_device.get(key), impl_device.get(key)
        if ref_value == impl_value:
            continue
        entries.append(_entry(
            run_id, entry_id, "environment", "high",
            f"device {label} differs: reference {ref_value!r} vs "
            f"implementation {impl_value!r} — run-pair environment parity "
            "is an EVIDENCE.md requirement (same device profile both sides)",
            path=path, reference=ref_value, implementation=impl_value,
            feature=f"environment-{slug(key)}", anchor="device"))
    ref_baseline = (ref_device.get("permission_baseline") or {})
    impl_baseline = (impl_device.get("permission_baseline") or {})
    for permission in sorted(set(ref_baseline) | set(impl_baseline)):
        ref_value = ref_baseline.get(permission)
        impl_value = impl_baseline.get(permission)
        if ref_value == impl_value:
            continue
        entries.append(_entry(
            run_id, f"env-permission-{slug(permission)}", "environment",
            "high",
            f"permission baseline differs for {permission}: reference "
            f"{ref_value!r} vs implementation {impl_value!r} — the runs "
            "started from different permission states; outcomes are not "
            "comparable",
            path="device.permission_baseline", reference=ref_value,
            implementation=impl_value,
            feature=f"environment-permission-{slug(permission)}",
            anchor="device.permission_baseline"))
    ref_provider = ref.get("provider") or {}
    impl_provider = impl.get("provider") or {}
    ref_caps = ref_provider.get("capabilities") or {}
    impl_caps = impl_provider.get("capabilities") or {}
    for cap in sorted(set(ref_caps) | set(impl_caps)):
        ref_value, impl_value = ref_caps.get(cap), impl_caps.get(cap)
        if ref_value == impl_value:
            continue
        entries.append(_entry(
            run_id, f"env-provider-capability-{slug(cap)}", "environment",
            "medium",
            f"provider capability {cap!r} disagrees between the run pair: "
            f"reference {ref_value!r} vs implementation {impl_value!r} "
            "(SCENARIO-DSL run-pair consistency: both sides must execute "
            "on substrate-compatible providers)",
            path="provider.capabilities", reference=ref_value,
            implementation=impl_value,
            feature=f"environment-provider-capability-{slug(cap)}",
            anchor="provider.capabilities"))
    ref_slug, impl_slug = ref_provider.get("slug"), impl_provider.get("slug")
    if ref_slug != impl_slug:
        entries.append(_entry(
            run_id, "env-provider-slug", "environment", "low",
            f"provider slug differs (reference {ref_slug!r}, "
            f"implementation {impl_slug!r}) — informational; capability "
            "agreement is what run-pair consistency requires",
            path="provider.slug", reference=ref_slug,
            implementation=impl_slug, feature="environment-provider-slug",
            anchor="provider.slug"))
    return entries


# ------------------------------------------------ dimension (b) fixtures

def _compare_fixtures(run_id: str,
                      bundles: dict[str, Bundle]) -> list[DiffEntry]:
    ref = {entry.get("id"): entry.get("sha256")
           for entry in _manifest(bundles, "reference").get("fixtures") or []
           if isinstance(entry, dict)}
    impl = {entry.get("id"): entry.get("sha256")
            for entry in _manifest(bundles, "implementation")
            .get("fixtures") or [] if isinstance(entry, dict)}
    entries: list[DiffEntry] = []
    for fid in sorted(set(ref) | set(impl)):
        ref_sha, impl_sha = ref.get(fid), impl.get(fid)
        if ref_sha == impl_sha:
            continue
        if impl_sha is None:
            entries.append(_entry(
                run_id, f"fix-missing-implementation-{slug(str(fid))}",
                "fixtures", "critical",
                f"fixture {fid!r} is declared by the reference run but "
                "missing on the implementation side — the pair did not use "
                "the same input document (EVIDENCE.md: same fixture ids + "
                "sha256 both sides)",
                path="fixtures", reference={"id": fid, "sha256": ref_sha},
                implementation=None,
                feature=f"fixture-{slug(str(fid))}", anchor="fixtures"))
        elif ref_sha is None:
            entries.append(_entry(
                run_id, f"fix-extra-implementation-{slug(str(fid))}",
                "fixtures", "critical",
                f"fixture {fid!r} is declared by the implementation run but "
                "not by the reference — the pair did not use the same input "
                "document (EVIDENCE.md: same fixture ids + sha256 both "
                "sides)",
                path="fixtures", reference=None,
                implementation={"id": fid, "sha256": impl_sha},
                feature=f"fixture-{slug(str(fid))}", anchor="fixtures"))
        else:
            entries.append(_entry(
                run_id, f"fix-sha-{slug(str(fid))}", "fixtures", "critical",
                f"fixture {fid!r} sha256 differs: reference {ref_sha} vs "
                f"implementation {impl_sha} — different input bytes make "
                "the comparison invalid",
                path="fixtures", reference={"id": fid, "sha256": ref_sha},
                implementation={"id": fid, "sha256": impl_sha},
                feature=f"fixture-{slug(str(fid))}", anchor="fixtures"))
    return entries


# ---------------------------------------------- dimension (c) artifacts

def _compare_artifacts(run_id: str, bundles: dict[str, Bundle],
                       ref_masked: list[tuple[int, dict[str, Any]]],
                       impl_masked: list[tuple[int, dict[str, Any]]]
                       ) -> list[DiffEntry]:
    """Per-step evidence coverage for SURVIVING trace steps, numbered
    by their ORIGINAL recorded position (screenshots/ui dumps are
    numbered while the runner records; masking removes requirements,
    never renumbers evidence)."""
    ref, impl = _manifest(bundles, "reference"), _manifest(bundles,
                                                            "implementation")
    entries: list[DiffEntry] = []
    ref_shots = _step_artifacts(ref, "screenshots")
    impl_shots = _step_artifacts(impl, "screenshots")
    ref_ui = _step_artifacts(ref, "ui")
    impl_ui = _step_artifacts(impl, "ui")
    sides = (
        ("reference", ref_masked, ref_shots, ref_ui),
        ("implementation", impl_masked, impl_shots, impl_ui),
    )
    for side, masked, shots, ui in sides:
        for original_index, step in masked:
            number = original_index + 1
            label = step_label(number)
            if number not in shots:
                # values: each side's own artifact for this step (the
                # missing side carries null — evidence-only, no guessing)
                ref_value = ref_shots.get(number)
                impl_value = impl_shots.get(number)
                entries.append(_entry(
                    run_id, f"artifacts-step-{label}-{side}-screenshot",
                    "artifacts", "high",
                    f"no per-step screenshot for recorded step {number} "
                    f"({step.get('action')!r}) on the {side} side — "
                    "the manifest declares no screenshot with that step "
                    "number (EVIDENCE.md: screenshots numbered per step)",
                    path="artifacts", reference=ref_value,
                    implementation=impl_value,
                    feature=f"step-evidence-{label}",
                    anchor=f"artifacts[{side}-step-{number}]"))
            if number not in ui:
                ref_value = ref_ui.get(number)
                impl_value = impl_ui.get(number)
                entries.append(_entry(
                    run_id, f"artifacts-step-{label}-{side}-ui", "artifacts",
                    "medium",
                    f"no ui dump for recorded step {number} "
                    f"({step.get('action')!r}) on the {side} side — "
                    "the manifest declares no ui/*.xml with that step "
                    "number (EVIDENCE.md: ui dumps per step)",
                    path="artifacts", reference=ref_value,
                    implementation=impl_value,
                    feature=f"step-evidence-{label}",
                    anchor=f"artifacts[{side}-step-{number}]"))
    ref_counts = _category_counts(ref)
    impl_counts = _category_counts(impl)
    for category in COUNTED_CATEGORIES:
        if ref_counts[category] == impl_counts[category]:
            continue
        entries.append(_entry(
            run_id, f"artifacts-count-{category}", "artifacts", "low",
            f"{category}/ artifact count differs: reference "
            f"{ref_counts[category]} vs implementation "
            f"{impl_counts[category]} — informational; per-step coverage "
            "is checked above",
            path="artifacts", reference=ref_counts[category],
            implementation=impl_counts[category],
            feature=f"artifact-count-{category}", anchor="artifacts"))
    return entries


# ------------------------------------------------ dimension (d) actions

def _step_outcome_entry(run_id: str, index: int, ref_step: dict[str, Any],
                        impl_step: dict[str, Any]) -> DiffEntry | None:
    """Outcome-class comparison for one aligned non-launch step."""
    ref_class = classify_result(ref_step.get("result"))
    impl_class = classify_result(impl_step.get("result"))
    number = index + 1
    label = step_label(number)
    action = impl_step.get("action") or ref_step.get("action")
    if ref_class == "ok" and impl_class == "ok":
        return None
    if ref_class == impl_class:
        if ref_class == "other":
            if result_literal(ref_step.get("result")) == \
                    result_literal(impl_step.get("result")):
                return None
            severity, why = "medium", "unclassified outcome literals differ"
        elif ref_class == "unrecorded":
            return None
        else:  # both denied / both failed — same outcome, parity holds
            return None
    elif impl_class == "ok":
        # the reference failed/denied: its observation is broken; the
        # implementation is not blamed (medium, comparison partial)
        severity = "medium"
        why = (f"reference outcome '{ref_class}' (observation incomplete) "
               f"vs implementation 'ok'")
    elif ref_class == "ok":
        # implementation failed where the reference succeeded
        severity = "critical" if impl_class == "denied" else "high"
        why = (f"reference outcome 'ok' vs implementation "
               f"'{impl_class}'")
    else:
        severity = "medium"
        why = (f"both outcomes not-ok but different: reference "
               f"'{ref_class}' vs implementation '{impl_class}'")
    return _entry(
        run_id, f"actions-step-{label}-result", "actions", severity,
        f"step {number} ({action!r} on {impl_step.get('target')!r}): {why} "
        f"— reference result {result_literal(ref_step.get('result'))!r}, "
        f"implementation result "
        f"{result_literal(impl_step.get('result'))!r}",
        path=f"action_trace[{index}].result",
        reference=result_literal(ref_step.get("result")),
        implementation=result_literal(impl_step.get("result")),
        feature=f"{slug(str(action))}-step-{label}",
        anchor=f"action_trace[{index}]")


def _compare_actions(run_id: str, bundles: dict[str, Bundle],
                     ref_masked: list[tuple[int, dict[str, Any]]],
                     impl_masked: list[tuple[int, dict[str, Any]]]
                     ) -> list[DiffEntry]:
    """Positional, post-mask action_trace comparison. ``step N`` in the
    entries refers to the ALIGNED position (1-based) — the position the
    two traces are compared at after masking."""
    entries: list[DiffEntry] = []
    ref_steps = [step for _, step in ref_masked]
    impl_steps = [step for _, step in impl_masked]
    if len(ref_masked) != len(impl_masked):
        entries.append(_entry(
            run_id, "actions-step-count", "actions", "high",
            f"step count differs after masking: reference "
            f"{len(ref_steps)} vs implementation {len(impl_steps)} "
            f"(reference actions: {[s.get('action') for s in ref_steps]}; "
            f"implementation actions: "
            f"{[s.get('action') for s in impl_steps]}) — the "
            "documented reference-app-specific steps must be masked via "
            "tools/parity-cli/masks/ to realign the pair",
            path="action_trace",
            reference={"steps": len(ref_steps),
                       "actions": [s.get("action") for s in ref_steps]},
            implementation={"steps": len(impl_steps),
                            "actions": [s.get("action")
                                        for s in impl_steps]},
            feature="action-trace-shape", anchor="action_trace"))
    for index in range(min(len(ref_steps), len(impl_steps))):
        ref_step, impl_step = ref_steps[index], impl_steps[index]
        number = index + 1
        label = step_label(number)
        action = impl_step.get("action")
        if action == "launch":
            impl_class = classify_result(impl_step.get("result"))
            ref_class = classify_result(ref_step.get("result"))
            if impl_class != "ok":
                entries.append(_entry(
                    run_id, "actions-launch-implementation", "actions",
                    "critical",
                    f"implementation failed to launch: step {number} "
                    "result "
                    f"{result_literal(impl_step.get('result'))!r} (class "
                    f"'{impl_class}') — nothing downstream is comparable",
                    path=f"action_trace[{index}].result",
                    reference=result_literal(ref_step.get("result")),
                    implementation=result_literal(impl_step.get("result")),
                    feature="launch", anchor=f"action_trace[{index}]"))
            elif ref_class != "ok":
                entries.append(_entry(
                    run_id, "actions-launch-reference", "actions", "medium",
                    f"reference launch did not succeed: step {number} "
                    f"result {result_literal(ref_step.get('result'))!r} "
                    f"(class '{ref_class}') — reference observation "
                    "incomplete at the entry step",
                    path=f"action_trace[{index}].result",
                    reference=result_literal(ref_step.get("result")),
                    implementation=result_literal(impl_step.get("result")),
                    feature="launch", anchor=f"action_trace[{index}]"))
            continue
        if ref_step.get("action") != impl_step.get("action"):
            entries.append(_entry(
                run_id, f"actions-step-{label}-action", "actions", "high",
                f"step {number} action mismatch: reference "
                f"{ref_step.get('action')!r} vs implementation "
                f"{impl_step.get('action')!r} — the traces are not "
                "step-aligned at this position",
                path=f"action_trace[{index}].action",
                reference=ref_step.get("action"),
                implementation=impl_step.get("action"),
                feature=f"step-{label}-action",
                anchor=f"action_trace[{index}]"))
            continue
        outcome = _step_outcome_entry(run_id, index, ref_step, impl_step)
        if outcome is not None:
            entries.append(outcome)
    return entries


# ------------------------------------------------ dimension (e) outputs

def _compare_outputs(run_id: str,
                     bundles: dict[str, Bundle]) -> list[DiffEntry]:
    ref = _outputs_by_type(_manifest(bundles, "reference"))
    impl = _outputs_by_type(_manifest(bundles, "implementation"))
    entries: list[DiffEntry] = []
    for ext in sorted(set(ref) | set(impl)):
        ref_shas, impl_shas = ref.get(ext, []), impl.get(ext, [])
        if not ref_shas and not impl_shas:
            continue
        if ref_shas and not impl_shas:
            entries.append(_entry(
                run_id, f"outputs-type-{ext}", "outputs", "medium",
                f"reference produces {len(ref_shas)} {ext} output(s) but "
                "the implementation produces none — the output capability "
                "is missing (recorded as divergence; output equality is "
                "not required, presence by type is compared)",
                path="artifacts", reference={"count": len(ref_shas),
                                             "sha256": ref_shas},
                implementation={"count": 0, "sha256": []},
                feature=f"output-{ext}", anchor="artifacts"))
            continue
        if impl_shas and not ref_shas:
            entries.append(_entry(
                run_id, f"outputs-extra-type-{ext}", "outputs", "low",
                f"implementation produces {len(impl_shas)} {ext} output(s) "
                "not produced by the reference — informational (extra "
                "output on the implementation side)",
                path="artifacts", reference={"count": 0, "sha256": []},
                implementation={"count": len(impl_shas),
                                "sha256": impl_shas},
                feature=f"output-{ext}", anchor="artifacts"))
            continue
        if len(ref_shas) != len(impl_shas):
            entries.append(_entry(
                run_id, f"outputs-count-{ext}", "outputs", "low",
                f"{ext} output count differs: reference {len(ref_shas)} vs "
                f"implementation {len(impl_shas)} — recorded, not enforced "
                "(output equality is not required)",
                path="artifacts", reference={"count": len(ref_shas),
                                             "sha256": ref_shas},
                implementation={"count": len(impl_shas),
                                "sha256": impl_shas},
                feature=f"output-{ext}", anchor="artifacts"))
        if sorted(ref_shas) != sorted(impl_shas):
            entries.append(_entry(
                run_id, f"outputs-sha-{ext}", "outputs", "low",
                f"{ext} output bytes differ (sha256): reference-only "
                f"{sorted(set(ref_shas) - set(impl_shas))}, "
                f"implementation-only "
                f"{sorted(set(impl_shas) - set(ref_shas))} — recorded, "
                "not enforced (output equality is not required)",
                path="artifacts", reference=sorted(ref_shas),
                implementation=sorted(impl_shas),
                feature=f"output-{ext}", anchor="artifacts"))
    return entries


# ------------------------------------------------------------- assembly

def _subjects_doc(run_id: str, bundles: dict[str, Bundle]) -> dict:
    subjects: dict[str, Any] = {}
    for side in ("reference", "implementation"):
        bundle = bundles[side]
        doc: dict[str, Any] = {
            "status": bundle.status,
            "manifest": bundle.manifest_rel if bundle.status != "missing"
            else None,
            "problems": bundle.problems,
        }
        manifest = bundle.manifest
        if manifest is not None:
            trace = manifest.get("action_trace") or []
            provider = manifest.get("provider") or {}
            capabilities = provider.get("capabilities") or {}
            acceleration = capabilities.get("emulator_acceleration")
            doc["r2_uploaded"] = any(
                isinstance(art, dict) and art.get("r2_key")
                for art in manifest.get("artifacts") or [])
            doc["observation"] = {
                "steps": len(trace),
                "ok_steps": sum(
                    1 for step in trace if isinstance(step, dict)
                    and classify_result(step.get("result")) == "ok"),
                "started_at": manifest.get("started_at"),
                "finished_at": manifest.get("finished_at"),
            }
            doc["provider"] = {
                "slug": provider.get("slug"),
                "environment_id": provider.get("environment_id"),
                "emulator_acceleration": acceleration
                if acceleration in ("none", "kvm", "hvf") else None,
            }
            application = manifest.get("application") or {}
            doc["application"] = {"package": application.get("package"),
                                  "version_name":
                                      application.get("version_name")}
        subjects[side] = doc
    return subjects


def _dimensions_doc(entries: list[DiffEntry],
                    compared: bool) -> dict[str, Any]:
    out: dict[str, Any] = {}
    for dimension in DIMENSIONS:
        dim_entries = [e for e in entries if e.dimension == dimension]
        counts = dict.fromkeys(SEVERITIES, 0)
        for entry in dim_entries:
            counts[entry.severity] += 1
        out[dimension] = {
            "compared": compared or dimension == "bundle",
            "entries": len(dim_entries),
            **counts,
        }
    return out


def _counts(entries: list[DiffEntry]) -> dict[str, int]:
    counts = dict.fromkeys(SEVERITIES, 0)
    for entry in entries:
        counts[entry.severity] += 1
    return counts


def compare_run(run_id: str, *, repo_root: Path | None = None,
                runs_dir: Path | None = None,
                masks_dir: Path | None = None,
                use_masks: bool = True, check: bool = False,
                ) -> CompareResult:
    """Compare a paired run; see the module docstring for the dimensions.

    Writes ``runs/<run-id>/reconciliation/{diff.json, verdict.json}``
    (atomically, deterministic bytes). ``check=True`` writes nothing and
    reports staleness of the on-disk reconciliation outputs instead.
    """
    run_dir = resolve_run_dir(run_id, repo_root=repo_root,
                               runs_dir=runs_dir)
    bundles = load_bundles(run_dir)
    generated_utc = derive_generated_utc(bundles, run_id)
    scenario = scenario_of_bundles(bundles, run_dir)

    entries: list[DiffEntry] = []
    masks_doc: dict[str, Any] = {
        "enabled": bool(use_masks),
        "rules_considered": 0,
        "applied": [],
        "removed_reference_steps": [],
        "removed_implementation_steps": [],
        "kept_reference_steps": [],
        "kept_implementation_steps": [],
    }
    compared = bundles["reference"].ok and bundles["implementation"].ok
    if compared:
        ref_trace = list(_manifest(bundles, "reference")
                         .get("action_trace") or [])
        impl_trace = list(_manifest(bundles, "implementation")
                          .get("action_trace") or [])
        if use_masks:
            effective_dir = masks_dir if masks_dir is not None \
                else masks_mod.DEFAULT_MASKS_DIR
            rules = masks_mod.load_masks(effective_dir, scenario)
            ref_masked, impl_masked, record = masks_mod.apply_masks(
                ref_trace, impl_trace, rules)
            masks_doc = {
                "enabled": True,
                "rules_considered": record.rules_considered,
                "applied": record.applied,
                "removed_reference_steps": record.removed_reference_steps,
                "removed_implementation_steps":
                    record.removed_implementation_steps,
                "kept_reference_steps": record.kept_reference_steps,
                "kept_implementation_steps":
                    record.kept_implementation_steps,
            }
        else:
            ref_masked = list(enumerate(ref_trace))
            impl_masked = list(enumerate(impl_trace))
        entries.extend(_compare_environment(run_id, bundles))
        entries.extend(_compare_fixtures(run_id, bundles))
        entries.extend(_compare_artifacts(run_id, bundles, ref_masked,
                                          impl_masked))
        entries.extend(_compare_actions(run_id, bundles, ref_masked,
                                        impl_masked))
        entries.extend(_compare_outputs(run_id, bundles))
    entries.extend(_bundle_entries(run_id, bundles))

    entries.sort(key=lambda e: (DIMENSION_ORDER[e.dimension], e.id))
    counts = _counts(entries)
    diff: dict[str, Any] = {
        "format": DIFF_FORMAT,
        "run_id": run_id,
        "scenario": scenario,
        "generated_utc": generated_utc,
        "subjects": _subjects_doc(run_id, bundles),
        "masks": masks_doc,
        "dimensions": _dimensions_doc(entries, compared),
        "entries": [entry.doc() for entry in entries],
        "counts": counts,
    }

    from .verdict import verdict_doc  # local import: avoid a cycle
    verdict = verdict_doc(diff)

    result = CompareResult(run_id=run_id, run_dir=str(run_dir),
                           diff=diff, verdict=verdict)
    reconciliation = run_dir / "reconciliation"
    if check:
        for name, doc in (("diff.json", diff), ("verdict.json", verdict)):
            path = reconciliation / name
            if not path.is_file():
                result.check_problems.append(f"{name} missing")
                continue
            try:
                on_disk = path.read_text(encoding="utf-8")
            except OSError as e:
                result.check_problems.append(f"{name} unreadable ({e})")
                continue
            if on_disk != dumps_deterministic(doc):
                result.check_problems.append(
                    f"{name} stale — re-run compare")
        return result

    reconciliation.mkdir(parents=True, exist_ok=True)
    jsonio_dump(reconciliation / "diff.json", diff)
    jsonio_dump(reconciliation / "verdict.json", verdict)
    result.wrote_diff = True
    result.wrote_verdict = True
    return result
