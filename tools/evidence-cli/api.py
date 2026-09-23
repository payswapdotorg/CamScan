"""Public facade for ``tools/evidence-cli`` (CAMSCAN-006).

Programmatic entry points; these are the names re-exported at the
``tools.evidence_cli`` alias level by ``tools/__init__.py``:

    from tools.evidence_cli.api import bundle_run, upload_run, \\
        verify_manifest, R2Store, EvidenceCliError

CLI usage lives in :mod:`.main` (``python3 tools/evidence-cli/main.py``).
"""
from __future__ import annotations

from .bundle import BundleResult, bundle_run
from .schema import EvidenceCliError
from .store import R2Store
from .upload import UploadResult, upload_run
from .verify import VerifyResult, verify_manifest

__all__ = [
    "BundleResult",
    "EvidenceCliError",
    "R2Store",
    "UploadResult",
    "VerifyResult",
    "bundle_run",
    "upload_run",
    "verify_manifest",
]
