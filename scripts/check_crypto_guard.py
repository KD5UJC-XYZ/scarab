#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Guard the "variable-time, public inputs only" crypto contract (AUD-27, #219).

The Java math of the ECVRF draft-03 implementation (`Draft03Math`, `Ed25519Point`,
`Draft03Math.java:4-6`) is variable-time by declaration and must only ever be referenced from
inside the `xyz.kd5ujc.scarab.crypto.vrf` package (the prover, the verifier and the diagnostic
probe). A reference from anywhere else would let a secret scalar reach variable-time arithmetic
and must fail CI.

Usage: python3 scripts/check_crypto_guard.py [repo-root]
Exit 0 when every reference is inside the vrf package; 1 with the offending files listed.
"""

import sys
from pathlib import Path

NEEDLES = ("Draft03Math", "Ed25519Point")
EXCLUDE_DIRS = {"target", ".git", ".cache", "fixtures"}
# The vrf package path segment, in both main and test source trees.
ALLOWED_SEGMENT = ("scarab", "crypto", "vrf")


def sources(root: Path):
    for path in root.rglob("*"):
        if any(part in EXCLUDE_DIRS for part in path.parts):
            continue
        if path.is_file() and path.suffix in (".scala", ".java"):
            yield path


def violations(root: Path):
    for path in sources(root):
        if not path.is_file():
            continue
        try:
            text = path.read_text(encoding="utf-8")
        except OSError:
            continue
        if any(needle in text for needle in NEEDLES):
            parts = path.parts
            if not any(
                parts[i : i + 3] == ALLOWED_SEGMENT for i in range(len(parts) - 2)
            ):
                yield path


def main(argv):
    root = Path(argv[1] if len(argv) > 1 else ".")
    found = sorted(violations(root))
    if found:
        for path in found:
            print(f"outside the vrf package: {path}")
        print(
            "Draft03Math/Ed25519Point are variable-time (public inputs only): "
            "references must stay inside xyz.kd5ujc.scarab.crypto.vrf."
        )
        return 1
    print("crypto guard: every Draft03Math/Ed25519Point reference is inside the vrf package")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
