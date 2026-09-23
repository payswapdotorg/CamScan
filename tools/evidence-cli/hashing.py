"""Streamed SHA-256 hashing (stdlib only).

Artifacts are hashed in fixed-size chunks so multi-hundred-megabyte
recordings never load fully into memory. Hex digests are lowercase
(matching the corpus manifest + EVIDENCE.md examples).
"""
from __future__ import annotations

import hashlib
from pathlib import Path

#: Chunk size for streamed hashing (1 MiB).
CHUNK_BYTES = 1 << 20


def sha256_bytes(data: bytes) -> str:
    """Lowercase hex SHA-256 of an in-memory buffer."""
    return hashlib.sha256(data).hexdigest()


def sha256_file(path: Path) -> tuple[str, int]:
    """Streamed ``(sha256_hex, size_bytes)`` of a file."""
    digest = hashlib.sha256()
    size = 0
    with open(path, "rb") as fh:
        while True:
            chunk = fh.read(CHUNK_BYTES)
            if not chunk:
                break
            digest.update(chunk)
            size += len(chunk)
    return digest.hexdigest(), size
