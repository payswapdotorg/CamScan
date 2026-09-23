#!/usr/bin/env python3
"""evidence-cli — CamScan parity evidence bundling + R2 pipeline.

CAMSCAN-006 (Worker 3). Subcommands:

    bundle  <run-dir> [--check]    validate/normalize a run dir to the
                                   EVIDENCE.md layout; (re)build its
                                   manifest.json + output sidecars
    upload  <run-dir>              PUT all artifacts + manifest to R2
                                   (runs/<run-id>/<subject>/<path>),
                                   HEAD-verify each object, write
                                   r2-manifest.json
    verify  <manifest.json>       re-derive integrity from disk: schema,
                                   hashes, sidecars, layout, fixtures,
                                   R2 sizes (when credentials exist)

Common: ``--fixtures <path>`` pins the fixture corpus (repo root,
``lab/fixtures/`` dir or its ``manifest.json``); otherwise
``CAMSCAN_FIXTURES_DIR`` or a walk up from the run dir. R2 credentials
come ONLY from the environment: R2_ACCESS_KEY_ID, R2_SECRET_ACCESS_KEY,
R2_ENDPOINT, R2_BUCKET. Exit codes: 0 ok, 1 failure, 2 usage.
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

_REPO_ROOT = Path(__file__).resolve().parents[2]
if str(_REPO_ROOT) not in sys.path:  # script-mode bootstrap
    sys.path.insert(0, str(_REPO_ROOT))

from tools.evidence_cli.api import (
    EvidenceCliError,
    bundle_run,
    upload_run,
    verify_manifest,
)
from tools.evidence_cli.integrity import format_table


def _add_fixtures_arg(parser: argparse.ArgumentParser) -> None:
    parser.add_argument(
        "--fixtures", metavar="PATH", default=None,
        help="fixture corpus location: repo root, lab/fixtures/ dir, or "
             "its manifest.json (default: CAMSCAN_FIXTURES_DIR, then a "
             "walk up from the run dir)")


def cmd_bundle(args: argparse.Namespace) -> int:
    result = bundle_run(Path(args.run_dir), check=args.check,
                        fixtures_ref=Path(args.fixtures)
                        if args.fixtures else None)
    for repair in result.repairs:
        print(f"repair: {repair}")
    if not result.ok:
        label = "bundle check FAILED (re-bundle required)" if args.check \
            else "bundle FAILED"
        print(f"{label}:", file=sys.stderr)
        for problem in result.problems:
            print(f"  - {problem}", file=sys.stderr)
        return 1
    if args.check:
        print(f"bundle check ok: {result.run_id} [{result.subject}] "
              f"{len(result.artifacts)} artifacts — manifest.json and "
              "sidecars up to date")
    else:
        print(f"bundle ok: {result.run_id} [{result.subject}] "
              f"{len(result.artifacts)} artifacts "
              f"({result.total_bytes} bytes) -> manifest.json"
              + (f"; wrote {len(result.wrote_sidecars)} sidecar(s)"
                 if result.wrote_sidecars else ""))
    return 0


def cmd_upload(args: argparse.Namespace) -> int:
    result = upload_run(Path(args.run_dir),
                        fixtures_ref=Path(args.fixtures)
                        if args.fixtures else None)
    for obj in result.objects:
        print(f"uploaded {obj['key']} ({obj['bytes']} bytes, "
              "head-verified)")
    print(f"uploaded {result.manifest_key} ({result.manifest_bytes} "
          "bytes, head-verified)")
    print(f"upload ok: {len(result.objects)} objects + manifest -> "
          f"bucket {result.bucket!r}; r2-manifest.json written "
          f"({len(result.objects)} verified)")
    return 0


def cmd_verify(args: argparse.Namespace) -> int:
    result = verify_manifest(Path(args.manifest),
                             fixtures_ref=Path(args.fixtures)
                             if args.fixtures else None)
    if result.rows:
        print(format_table(result.rows))
    if not result.remote_checked:
        print("note: R2 credentials not set — remote checks skipped")
    if not result.ok:
        print("VERIFY FAILED:", file=sys.stderr)
        for problem in result.problems:
            print(f"  - {problem}", file=sys.stderr)
        return 1
    print(f"verify ok: {len(result.rows)} artifact(s) — schema valid, "
          f"hashes/sidecars/layout/fixtures consistent"
          + (", R2 sizes verified" if result.remote_checked else ""))
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        prog="evidence-cli",
        description="CamScan parity evidence bundle + R2 pipeline "
                    "(CAMSCAN-006; contract: lab/evidence/EVIDENCE.md)")
    sub = parser.add_subparsers(dest="command", required=True)

    p_bundle = sub.add_parser(
        "bundle", help="validate/normalize a run dir; build manifest.json")
    p_bundle.add_argument("run_dir", help="the runs/<run-id> directory")
    p_bundle.add_argument("--check", action="store_true",
                           help="gate only: fail if the on-disk bundle is "
                                "stale; write nothing")
    _add_fixtures_arg(p_bundle)
    p_bundle.set_defaults(func=cmd_bundle)

    p_upload = sub.add_parser(
        "upload", help="upload a bundled run dir to R2 (credentials: env)")
    p_upload.add_argument("run_dir", help="the runs/<run-id> directory")
    _add_fixtures_arg(p_upload)
    p_upload.set_defaults(func=cmd_upload)

    p_verify = sub.add_parser(
        "verify", help="re-verify a bundle from its manifest.json")
    p_verify.add_argument("manifest", help="path to the manifest.json")
    _add_fixtures_arg(p_verify)
    p_verify.set_defaults(func=cmd_verify)

    args = parser.parse_args(argv)
    try:
        return int(args.func(args))
    except EvidenceCliError as e:
        print(f"evidence-cli: {e}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
