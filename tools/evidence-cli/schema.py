"""Hand-rolled ``lab/evidence/EVIDENCE.md`` manifest schema validator.

The CAMSCAN-006 work order pins the deliverable to stdlib + boto3, so the
manifest contract is validated by a purpose-built checker instead of
``jsonschema`` (the lead's ``lab-cli/validate.py`` keeps that dependency on
the CI side; this tool must run anywhere Python runs).

Validated contract (EVIDENCE.md, "manifest.json contract"):

- exactly the eleven top-level keys — ``run_id, scenario, subject,
  provider, application, device, fixtures, artifacts, action_trace,
  started_at, finished_at`` (artifact entries may additionally carry the
  ``r2_key`` that EVIDENCE.md's rules section requires the manifest to
  record once objects are in R2);
- ``run_id``: ``<YYYYMMDDTHHMMSSZ>-S###-<suffix>`` and equal to the run
  directory name (checked by bundle/verify, which know the directory);
- ``subject``: ``reference`` | ``implementation`` — one subject per run
  dir (single-subject bundles; see README);
- ``artifacts[]``: subject-relative POSIX paths under a known category
  dir (``screenshots/ recordings/ ui/ logs/ outputs/``), 64-lowercase-hex
  ``sha256``, positive integer ``bytes``; ``r2_key`` when present must be
  exactly ``runs/<run-id>/<subject>/<path>``;
- ``empty_captures`` (CAMSCAN-010J, OPTIONAL): the size==0 subject-tree
  files excluded from ``artifacts[]`` — each entry ``{"path", "bytes"}``
  with the same subject-relative path shape and ``bytes`` exactly 0;
  presence is recorded, never fatal (the positive-bytes contract above
  stays for REAL artifacts);
- timestamps: ISO-8601 with timezone, ``finished_at >= started_at``.

``validate_manifest`` accumulates problems and returns them — it never
raises on bad data (raise-worthy conditions live in :class:`EvidenceCliError`
callers).
"""
from __future__ import annotations

import re
from datetime import datetime
from typing import Any

#: Subject values (EVIDENCE.md).
SUBJECTS: tuple[str, ...] = ("reference", "implementation")

#: Artifact category directories (EVIDENCE.md bundle layout).
CATEGORY_DIRS: tuple[str, ...] = ("screenshots", "recordings", "ui",
                                  "logs", "outputs")

#: run_id shape: 20260921T103000Z-S004-<suffix>
RUN_ID_RE = re.compile(r"^\d{8}T\d{6}Z-S\d{3}-[A-Za-z0-9][A-Za-z0-9._-]*$")

#: 64 lowercase hex (EVIDENCE.md + fixture.schema.json pin this shape).
HEX64_RE = re.compile(r"^[a-f0-9]{64}$")

#: device.screen shape, e.g. "1080x2280@440dpi".
SCREEN_RE = re.compile(r"^\d+x\d+@\d+dpi$")

#: kebab-case scenario id (scenario files: S###-<id>.yaml).
KEBAB_RE = re.compile(r"^[a-z0-9]+(?:-[a-z0-9]+)*$")

#: Every top-level key of the manifest contract.
TOP_LEVEL_KEYS = ("run_id", "scenario", "subject", "provider",
                   "application", "device", "fixtures", "artifacts",
                   "action_trace", "started_at", "finished_at")

#: CAMSCAN-010J — the OPTIONAL empty-capture record: top-level keys
#: permitted beyond the required eleven. ``empty_captures`` carries
#: ``[{"path": …, "bytes": 0}]`` — the size==0 subject-tree files the
#: bundle EXCLUDED from ``artifacts[]`` (a legitimately-empty capture:
#: a recorded gap, never evidence, never fatal). Optional and absent
#: when the tree carries none (schema-valid both ways).
OPTIONAL_TOP_LEVEL_KEYS = ("empty_captures",)

_PROVIDER_KEYS = ("slug", "capabilities", "environment_id")
_APPLICATION_KEYS = ("package", "version_name", "version_code",
                     "installer_sha256")
_DEVICE_KEYS = ("model", "android_version", "screen", "locale", "timezone",
                "permission_baseline")
_TRACE_KEYS = ("t_ms", "action", "target", "result")
_TRACE_REQUIRED = ("t_ms", "action")


class EvidenceCliError(Exception):
    """Operational failure (bad invocation, unreachable corpus, R2 error).

    Data-level problems are accumulated as problem strings; this exception
    carries failures that abort the current command outright.
    """


def _is_int(value: Any) -> bool:
    return isinstance(value, int) and not isinstance(value, bool)


def _is_scalar(value: Any) -> bool:
    return isinstance(value, (bool, int, float, str))


def _check_str(problems: list[str], label: str, value: Any,
               *, nonempty: bool = True) -> None:
    if not isinstance(value, str):
        problems.append(f"{label}: expected a string, got {type(value).__name__}")
    elif nonempty and not value:
        problems.append(f"{label}: must not be empty")


def _check_keys(problems: list[str], label: str, obj: Any,
                required: tuple[str, ...],
                allowed: tuple[str, ...] | None = None) -> None:
    """Check ``obj`` has all ``required`` keys and no keys outside
    ``allowed`` (defaults to ``required`` — additionalProperties: false)."""
    if not isinstance(obj, dict):
        problems.append(f"{label}: expected an object, got {type(obj).__name__}")
        return
    missing = [k for k in required if k not in obj]
    permitted = allowed if allowed is not None else required
    unknown = [k for k in obj if k not in permitted]
    if missing:
        problems.append(f"{label}: missing keys {missing}")
    if unknown:
        problems.append(f"{label}: unknown keys {unknown} (allowed: {list(permitted)})")


def _parse_ts(value: Any) -> datetime | None:
    if not isinstance(value, str):
        return None
    try:
        parsed = datetime.fromisoformat(value)
    except ValueError:
        return None
    return parsed if parsed.tzinfo is not None else None


def check_artifact_path(path: Any) -> str | None:
    """Return the problem with ``path`` (subject-relative POSIX), or None."""
    if not isinstance(path, str) or not path:
        return "artifact path must be a non-empty string"
    if path.startswith(("/", "./")):
        return f"artifact path {path!r} must be subject-relative (no leading / or ./)"
    if "\\" in path or "\x00" in path:
        return f"artifact path {path!r} must use POSIX separators"
    if any(ch.isspace() for ch in path):
        return f"artifact path {path!r} must not contain whitespace"
    segments = path.split("/")
    if any(seg in ("", ".", "..") for seg in segments):
        return f"artifact path {path!r} has empty or dot segments"
    if segments[0] not in CATEGORY_DIRS:
        return (f"artifact path {path!r} must start with one of the category "
                f"dirs {list(CATEGORY_DIRS)}")
    if path.endswith(".sha256"):
        return (f"artifact path {path!r} must not be a sidecar "
                f"(sidecars are derived, not manifest artifacts)")
    return None


def validate_manifest(doc: Any) -> list[str]:
    """Validate a parsed manifest against the EVIDENCE.md contract."""
    problems: list[str] = []
    if not isinstance(doc, dict):
        return ["manifest: expected a JSON object at top level"]

    _check_keys(problems, "manifest", doc, TOP_LEVEL_KEYS,
                allowed=TOP_LEVEL_KEYS + OPTIONAL_TOP_LEVEL_KEYS)

    run_id = doc.get("run_id")
    _check_str(problems, "run_id", run_id)
    if isinstance(run_id, str) and not RUN_ID_RE.match(run_id):
        problems.append(f"run_id {run_id!r} must match "
                        "<YYYYMMDDTHHMMSSZ>-S###-<suffix>")

    scenario = doc.get("scenario")
    _check_str(problems, "scenario", scenario)
    if isinstance(scenario, str) and not KEBAB_RE.match(scenario):
        problems.append(f"scenario {scenario!r} must be the kebab-case "
                        "scenario id (e.g. 'single-document-capture')")

    subject = doc.get("subject")
    _check_str(problems, "subject", subject)
    if isinstance(subject, str) and subject not in SUBJECTS:
        problems.append(f"subject {subject!r} must be one of {list(SUBJECTS)}")

    provider = doc.get("provider")
    _check_keys(problems, "provider", provider, _PROVIDER_KEYS)
    if isinstance(provider, dict):
        _check_str(problems, "provider.slug", provider.get("slug"))
        _check_str(problems, "provider.environment_id",
                   provider.get("environment_id"))
        caps = provider.get("capabilities")
        if not isinstance(caps, dict) or not caps:
            problems.append("provider.capabilities: expected a non-empty mapping")
        else:
            for key, value in caps.items():
                if not isinstance(key, str) or not key:
                    problems.append("provider.capabilities: keys must be strings")
                elif not _is_scalar(value):
                    problems.append(f"provider.capabilities[{key}]: "
                                    "values must be JSON scalars")

    application = doc.get("application")
    _check_keys(problems, "application", application, _APPLICATION_KEYS)
    if isinstance(application, dict):
        _check_str(problems, "application.package",
                   application.get("package"))
        _check_str(problems, "application.version_name",
                   application.get("version_name"))
        code = application.get("version_code")
        if not _is_int(code) or code < 0:
            problems.append(f"application.version_code must be a non-negative "
                            f"integer, got {code!r}")
        installer = application.get("installer_sha256")
        _check_str(problems, "application.installer_sha256", installer)
        if isinstance(installer, str) and not HEX64_RE.match(installer):
            problems.append("application.installer_sha256 must be 64 "
                            "lowercase hex chars")

    device = doc.get("device")
    _check_keys(problems, "device", device, _DEVICE_KEYS)
    if isinstance(device, dict):
        for key in ("model", "android_version", "locale", "timezone"):
            _check_str(problems, f"device.{key}", device.get(key))
        screen = device.get("screen")
        _check_str(problems, "device.screen", screen)
        if isinstance(screen, str) and not SCREEN_RE.match(screen):
            problems.append(f"device.screen {screen!r} must match WxH@dpi "
                            "(e.g. '1080x2280@440dpi')")
        baseline = device.get("permission_baseline")
        if not isinstance(baseline, dict):
            problems.append("device.permission_baseline: expected a mapping "
                            "(permission -> granted)")
        else:
            for key, value in baseline.items():
                if not isinstance(key, str) or not key:
                    problems.append("device.permission_baseline: keys must "
                                    "be permission strings")
                elif not _is_scalar(value):
                    problems.append(f"device.permission_baseline[{key}]: "
                                    "values must be JSON scalars")

    fixtures = doc.get("fixtures")
    if not isinstance(fixtures, list):
        problems.append("fixtures: expected a list of {id, sha256} entries")
    else:
        seen: set[str] = set()
        for i, entry in enumerate(fixtures):
            label = f"fixtures[{i}]"
            _check_keys(problems, label, entry, ("id", "sha256"))
            if not isinstance(entry, dict):
                continue
            fid = entry.get("id")
            _check_str(problems, f"{label}.id", fid)
            if isinstance(fid, str):
                if fid in seen:
                    problems.append(f"{label}.id {fid!r}: duplicate fixture id")
                seen.add(fid)
            fhash = entry.get("sha256")
            _check_str(problems, f"{label}.sha256", fhash)
            if isinstance(fhash, str) and not HEX64_RE.match(fhash):
                problems.append(f"{label}.sha256 must be 64 lowercase hex chars")

    artifacts = doc.get("artifacts")
    if not isinstance(artifacts, list):
        problems.append("artifacts: expected a list")
    elif not artifacts:
        problems.append("artifacts: must not be empty (a run with no "
                        "artifacts is not evidence)")
    else:
        seen_paths: set[str] = set()
        for i, entry in enumerate(artifacts):
            label = f"artifacts[{i}]"
            if not isinstance(entry, dict):
                problems.append(f"{label}: expected an object, "
                                f"got {type(entry).__name__}")
                continue
            path = entry.get("path")
            path_problem = check_artifact_path(path)
            if path_problem:
                problems.append(f"{label}.path: {path_problem}")
            elif path in seen_paths:
                problems.append(f"{label}.path {path!r}: duplicate artifact path")
            else:
                seen_paths.add(path)
            _check_keys(problems, label, entry,
                        ("path", "sha256", "bytes"),
                        allowed=("path", "sha256", "bytes", "r2_key"))
            ahash = entry.get("sha256")
            _check_str(problems, f"{label}.sha256", ahash)
            if isinstance(ahash, str) and not HEX64_RE.match(ahash):
                problems.append(f"{label}.sha256 must be 64 lowercase hex chars")
            nbytes = entry.get("bytes")
            if not _is_int(nbytes) or nbytes <= 0:
                problems.append(f"{label}.bytes must be a positive integer, "
                                f"got {nbytes!r}")
            r2_key = entry.get("r2_key")
            if r2_key is not None:
                _check_str(problems, f"{label}.r2_key", r2_key)
                if (isinstance(r2_key, str) and isinstance(path, str)
                        and isinstance(run_id, str) and subject in SUBJECTS):
                    expected = f"runs/{run_id}/{subject}/{path}"
                    if r2_key != expected:
                        problems.append(
                            f"{label}.r2_key must be exactly {expected!r} "
                            f"(got {r2_key!r})")

    empty_caps = doc.get("empty_captures")
    if empty_caps is not None:
        # CAMSCAN-010J: the optional empty-capture record — a list of
        # {path, bytes} entries naming the size==0 files the bundle
        # excluded from artifacts[] (presence recorded, never fatal;
        # the positive-bytes contract stays for real artifacts).
        if not isinstance(empty_caps, list):
            problems.append("empty_captures: expected a list of "
                            "{path, bytes} entries")
        else:
            listed_artifact_paths: set = set()
            if isinstance(artifacts, list):
                listed_artifact_paths = {
                    e.get("path") for e in artifacts
                    if isinstance(e, dict)}
            seen_empty: set[str] = set()
            for i, entry in enumerate(empty_caps):
                label = f"empty_captures[{i}]"
                _check_keys(problems, label, entry, ("path", "bytes"))
                if not isinstance(entry, dict):
                    continue
                epath = entry.get("path")
                epath_problem = check_artifact_path(epath)
                if epath_problem:
                    problems.append(f"{label}.path: {epath_problem}")
                elif epath in listed_artifact_paths:
                    problems.append(f"{label}.path {epath!r}: already "
                                    "listed as an artifact")
                elif epath in seen_empty:
                    problems.append(f"{label}.path {epath!r}: duplicate "
                                    "empty-capture path")
                else:
                    seen_empty.add(epath)
                ebytes = entry.get("bytes")
                if not _is_int(ebytes) or ebytes != 0:
                    problems.append(f"{label}.bytes must be exactly 0 for "
                                    f"an empty capture, got {ebytes!r}")

    trace = doc.get("action_trace")
    if not isinstance(trace, list):
        problems.append("action_trace: expected a list")
    else:
        for i, entry in enumerate(trace):
            label = f"action_trace[{i}]"
            _check_keys(problems, label, entry, _TRACE_KEYS)
            if not isinstance(entry, dict):
                continue
            t_ms = entry.get("t_ms")
            if not _is_int(t_ms) or t_ms < 0:
                problems.append(f"{label}.t_ms must be a non-negative "
                                f"integer, got {t_ms!r}")
            _check_str(problems, f"{label}.action", entry.get("action"))
            for optional in ("target", "result"):
                if optional in entry:
                    _check_str(problems, f"{label}.{optional}",
                               entry.get(optional), nonempty=False)

    started = _parse_ts(doc.get("started_at"))
    finished = _parse_ts(doc.get("finished_at"))
    for key in ("started_at", "finished_at"):
        value = doc.get(key)
        if not isinstance(value, str) or _parse_ts(value) is None:
            problems.append(f"{key}: must be an ISO-8601 timestamp with "
                            f"timezone, got {value!r}")
    if started is not None and finished is not None and finished < started:
        problems.append("finished_at is before started_at")

    return problems
