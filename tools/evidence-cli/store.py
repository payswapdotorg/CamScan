"""Cloudflare R2 object store access (boto3, credentials env-only).

Credential rules (SECURITY.md — binding):

- the *only* credential source is the environment:
  ``R2_ACCESS_KEY_ID``, ``R2_SECRET_ACCESS_KEY``, ``R2_ENDPOINT``,
  ``R2_BUCKET`` (all four required, none defaulted);
- credentials never appear in manifests, logs, error text or ``repr``;
  exception messages are scrubbed through :meth:`R2Store.sanitize`
  before they can surface;
- ``boto3`` is imported lazily so importing this module (and running the
  non-R2 code paths, e.g. unit tests) works without it installed.

Client shape: signature v4, path-style addressing (Cloudflare R2's
account-endpoint convention). The store is endpoint-agnostic — tests
point it at an AWS-shaped endpoint so ``moto`` can intercept.
"""
from __future__ import annotations

import os
from pathlib import Path
from typing import Any

from .schema import EvidenceCliError

#: The four environment variables (names only — values never logged).
CREDENTIAL_ENV_VARS: tuple[str, ...] = (
    "R2_ACCESS_KEY_ID", "R2_SECRET_ACCESS_KEY", "R2_ENDPOINT", "R2_BUCKET",
)

_REDACTED = "***REDACTED***"


class R2Store:
    """Minimal verified-write S3/R2 client for the evidence pipeline."""

    def __init__(self, access_key_id: str, secret_access_key: str,
                 endpoint: str, bucket: str) -> None:
        self._access_key_id = access_key_id
        self._secret = secret_access_key
        self._endpoint = endpoint
        self._bucket = bucket
        self._client: Any = None

    # ------------------------------------------------------------- factory
    @staticmethod
    def creds_present() -> bool:
        """True when all four credential env vars are set + non-empty."""
        return all(os.environ.get(name) for name in CREDENTIAL_ENV_VARS)

    @classmethod
    def from_env(cls) -> R2Store:
        """Build from the environment or fail naming the missing vars."""
        missing = [name for name in CREDENTIAL_ENV_VARS
                   if not os.environ.get(name)]
        if missing:
            raise EvidenceCliError(
                "missing R2 credential environment variables: "
                + ", ".join(missing)
                + " — credentials are read ONLY from the environment and "
                  "are never written to manifests or logs")
        return cls(
            os.environ["R2_ACCESS_KEY_ID"],
            os.environ["R2_SECRET_ACCESS_KEY"],
            os.environ["R2_ENDPOINT"].strip(),
            os.environ["R2_BUCKET"].strip(),
        )

    # ------------------------------------------------------------ hygiene
    def sanitize(self, text: str) -> str:
        """Scrub credential values (key id + secret) from any text."""
        for secret in (self._secret, self._access_key_id):
            if secret:
                text = text.replace(secret, _REDACTED)
        return text

    def __repr__(self) -> str:  # pragma: no cover - trivial
        return (f"R2Store(bucket={self._bucket!r}, "
                f"endpoint={self._endpoint!r}, "
                f"access_key_id={_REDACTED}, "
                f"secret_access_key={_REDACTED})")

    @property
    def bucket(self) -> str:
        return self._bucket

    # ------------------------------------------------------------- client
    def _get_client(self) -> Any:
        if self._client is None:
            try:
                import boto3  # lazy: only R2 paths need it
                from botocore.config import Config
            except ImportError as e:  # pragma: no cover - env-dependent
                raise EvidenceCliError(
                    "boto3 is required for R2 access "
                    "(pip install boto3)") from e
            self._client = boto3.client(
                "s3",
                endpoint_url=self._endpoint,
                aws_access_key_id=self._access_key_id,
                aws_secret_access_key=self._secret,
                config=Config(signature_version="s3v4",
                             s3={"addressing_style": "path"}),
            )
        return self._client

    # -------------------------------------------------------------- verbs
    def head(self, key: str) -> dict[str, Any]:
        """``{"exists": bool, "size": int | None}`` for one object key."""
        try:
            resp = self._get_client().head_object(
                Bucket=self._bucket, Key=key)
            return {"exists": True, "size": int(resp["ContentLength"])}
        except Exception as e:
            if _is_not_found(e):
                return {"exists": False, "size": None}
            raise EvidenceCliError(self.sanitize(
                f"R2 HEAD {key} failed: {e!r}")) from e

    def put_bytes(self, key: str, data: bytes) -> None:
        try:
            self._get_client().put_object(Bucket=self._bucket, Key=key,
                                          Body=data)
        except Exception as e:
            raise EvidenceCliError(self.sanitize(
                f"R2 PUT {key} failed: {e!r}")) from e

    def put_file(self, key: str, path) -> None:
        self.put_bytes(key, Path(path).read_bytes())


def _is_not_found(exc: Exception) -> bool:
    """True for S3 404-class client errors (never swallows auth errors)."""
    response = getattr(exc, "response", None)
    if not isinstance(response, dict):
        return False
    code = response.get("Error", {}).get("Code")
    return code in ("404", "NoSuchKey", "NotFound")
