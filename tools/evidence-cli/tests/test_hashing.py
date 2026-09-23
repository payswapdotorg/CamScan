"""hashing: streamed sha256 correctness."""
from __future__ import annotations

import hashlib

from tools.evidence_cli.hashing import sha256_bytes, sha256_file

#: The classic FIPS 180-2 test vector.
_ABC = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"


def test_sha256_bytes_known_vector():
    assert sha256_bytes(b"abc") == _ABC


def test_sha256_file_matches_hashlib_and_reports_size(tmp_path):
    data = bytes(range(256)) * (5 * 1024)  # > 1 MiB chunk boundary
    path = tmp_path / "big.bin"
    path.write_bytes(data)
    digest, size = sha256_file(path)
    assert digest == hashlib.sha256(data).hexdigest()
    assert size == len(data)


def test_sha256_file_empty(tmp_path):
    path = tmp_path / "empty.bin"
    path.write_bytes(b"")
    digest, size = sha256_file(path)
    assert digest == hashlib.sha256(b"").hexdigest()
    assert size == 0
