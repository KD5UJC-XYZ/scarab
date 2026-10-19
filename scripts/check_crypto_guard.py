#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Guard the "variable-time, public inputs only" crypto contract (AUD2-27, #219).

The Java math of the ECVRF draft-03 implementation (`Draft03Math`, `Ed25519Point`,
`Draft03Math.java:4-6`) is variable-time by declaration and must only ever be referenced from
inside the `xyz.kd5ujc.scarab.crypto.vrf` package, in the regular source tree. A reference from
anywhere else would let a secret scalar reach variable-time arithmetic and must fail CI.

The check verifies, for every Scala/Java source that mentions the classes:
- the file sits under `<module>/src/{main,test}/{scala,java}/xyz/kd5ujc/scarab/crypto/vrf/`, and
- its first code line declares `package xyz.kd5ujc.scarab.crypto.vrf`.

A matching directory name is not enough: a file with the right path but another package
declaration (or no declaration) is refused, and so is a file whose package is right but whose
path is outside the source tree. This is a containment rule for the variable-time classes, not
a data-flow proof: that secret scalars never reach them also rests on the review in #219 and on
the timing probe (`TimingProbeMain`).

Usage: python3 scripts/check_crypto_guard.py [repo-root]
Exit 0 when every reference is contained; 1 with the offending files listed.
"""

import re
import sys
from pathlib import Path

NEEDLES = ("Draft03Math", "Ed25519Point")
PACKAGE = "xyz.kd5ujc.scarab.crypto.vrf"
EXCLUDE_DIRS = {"target", ".git", ".cache", "fixtures"}
ROOTS = ("main", "test")
LANGS = ("scala", "java")
# The package path relative to the language directory.
PACKAGE_PARTS = ("xyz", "kd5ujc", "scarab", "crypto", "vrf")

PACKAGE_RE = re.compile(r"^\s*package\s+([\w.]+)")


def package_of(path: Path):
    """The package declared by the first code line, or None."""
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except OSError:
        return None
    for line in lines:
        stripped = line.strip()
        if not stripped or stripped.startswith(("//", "/*", "*")):
            continue
        match = PACKAGE_RE.match(stripped)
        return match.group(1) if match else None
    return None


def contained(path: Path) -> bool:
    parts = path.parts
    if "src" not in parts:
        return False
    i = parts.index("src")
    if i + 8 > len(parts):
        return False
    if parts[i + 1] not in ROOTS or parts[i + 2] not in LANGS:
        return False
    if parts[i + 3 : i + 8] != PACKAGE_PARTS:
        return False
    return package_of(path) == PACKAGE


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
        if any(needle in text for needle in NEEDLES) and not contained(path):
            yield path


def main(argv):
    root = Path(argv[1] if len(argv) > 1 else ".")
    found = sorted(violations(root))
    if found:
        for path in found:
            print(f"outside the vrf package: {path}")
        print(
            "Draft03Math/Ed25519Point are variable-time (public inputs only): references must "
            "stay inside xyz.kd5ujc.scarab.crypto.vrf, in the regular source tree."
        )
        return 1
    print("crypto guard: every Draft03Math/Ed25519Point reference is inside the vrf package")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
