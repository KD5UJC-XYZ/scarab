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

`VartimeEd25519`, the variable-time arithmetic of Ed25519 verification (#1418), is held tighter:
only its own source, the two verifiers (`StrictEd25519`, `ByronEd25519`) in the main sources of
`crypto`, and the test sources of its package `xyz.kd5ujc.scarab.crypto.witness` may mention it,
each at its path and with that package declared. A new caller, in that package or elsewhere, in
main sources or in `tools`, is refused until it is added to `VERIFY_CALLERS` here.

The secret path (#1418) is held tighter too. The main sources of the secret packages
(`SECRET_PACKAGES`): `xyz.kd5ujc.scarab.crypto.vrf.secret`, which holds the VRF signing key's seed
and every value computed from it, and `xyz.kd5ujc.scarab.crypto.secret`, the working storage
(`Scratch`) those values live in, may name no variable-time routine (`VARIABLE_TIME`: the
`vartime*` operations and `VartimeEd25519`, the NAF recoding, Bouncy Castle's `*Var` routines,
`Ed25519Point`, `Draft03Math`, the verifiers, big-integer arithmetic), no comparison that stops at
the first difference (and no member of `java.util.Arrays` but `fill`), and no call that would share
an array the package erases; and an import there may rename nothing and bring in no member of
`java.util.Arrays`, so that a listed name cannot be called under another (`IMPORT_RULES`).
`Draft03Prover.scala`, which hands the VRF package the hash-to-curve point, may call
`Draft03Math.h2c` (its inputs are the public key and alpha) and nothing else of the variable-time
math. A source is of a secret package when it sits in its directory or when any of its package
clauses, in any form (chained, `:`, braces, nested), names it or a package inside it, in any
module's main sources (`tools` among them). Comments and string literals are not read; the
expressions of an interpolated string are. Test sources are not covered: a suite may check a proof
with the verifier. The check reads names, not calls: a routine reached through code in another
package, by reflection, or under a name the list does not hold is review only.

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

VERIFY_NEEDLE = "VartimeEd25519"
VERIFY_PACKAGE = "xyz.kd5ujc.scarab.crypto.witness"
VERIFY_PARTS = ("xyz", "kd5ujc", "scarab", "crypto", "witness")
# The main sources that may mention VartimeEd25519: (module, language directory, file name).
VERIFY_CALLERS = {
    ("crypto", "java", "VartimeEd25519.java"),
    ("crypto", "scala", "StrictEd25519.scala"),
    ("crypto", "scala", "ByronEd25519.scala"),
}


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


def verify_allowed(path: Path, root: Path) -> bool:
    """Whether a source may mention VartimeEd25519: a listed main source of `crypto`, or a test
    source of the witness package, at its path under `root` and with the package declared."""
    parts = path.relative_to(root).parts
    if "src" not in parts:
        return False
    i = parts.index("src")
    if i < 1 or i + 9 != len(parts):
        return False
    module, root, lang = parts[i - 1], parts[i + 1], parts[i + 2]
    if root not in ROOTS or lang not in LANGS or parts[i + 3 : i + 8] != VERIFY_PARTS:
        return False
    if package_of(path) != VERIFY_PACKAGE:
        return False
    return root == "test" or (module, lang, parts[-1]) in VERIFY_CALLERS


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
        elif VERIFY_NEEDLE in text and not verify_allowed(path, root):
            yield path


def main(argv):
    root = Path(argv[1] if len(argv) > 1 else ".")
    found = sorted(violations(root))
    if found:
        for path in found:
            text = path.read_text(encoding="utf-8")
            if any(needle in text for needle in NEEDLES) and not contained(path):
                print(f"outside the vrf package: {path}")
            else:
                print(f"not a listed caller of {VERIFY_NEEDLE}: {path}")
        print(
            "Draft03Math/Ed25519Point are variable-time (public inputs only): references must "
            "stay inside xyz.kd5ujc.scarab.crypto.vrf, in the regular source tree. "
            "VartimeEd25519 may be named only by StrictEd25519, ByronEd25519 and the test "
            "sources of xyz.kd5ujc.scarab.crypto.witness (VERIFY_CALLERS)."
        )
        return 1
    print(
        "crypto guard: every Draft03Math/Ed25519Point reference is inside the vrf package, and "
        "every VartimeEd25519 reference is one of its listed callers"
    )
    return 0


# The secret packages, each with its path relative to the language directory.
SECRET_PACKAGES = (
    ("xyz.kd5ujc.scarab.crypto.vrf.secret", PACKAGE_PARTS + ("secret",)),
    ("xyz.kd5ujc.scarab.crypto.secret", ("xyz", "kd5ujc", "scarab", "crypto", "secret")),
)
# The prover file in package vrf that hands the secret path its public input: (module, language
# directory, file name).
SECRET_FACADE = ("crypto", "scala", "Draft03Prover.scala")
# What the secret path may not name, each with the reason it gives.
VARIABLE_TIME = (
    (re.compile(r"(?i)vartime"), "a variable-time operation (vartime*, VartimeEd25519)"),
    (re.compile(r"\bnonAdjacentForm\b"), "the NAF recoding of the variable-time multiplication"),
    (re.compile(r"\b[a-z]\w*Var\b"), "a variable-time Bouncy Castle routine (sqrtRatioVar, invVar, ...)"),
    (re.compile(r"\bEd25519Point\b"), "the variable-time point arithmetic and its decoder"),
    (re.compile(r"\b(StrictDraft03|StrictEd25519|ByronEd25519)\b"), "a verifier (variable-time)"),
    (re.compile(r"\bBig(Int|Integer|Decimal)\b"), "variable-time integer arithmetic"),
    (re.compile(r"\b(sameElements|corresponds|contentEquals|deepEquals)\b"), "a comparison that stops at a difference"),
    (re.compile(r"\bArrays\s*\.\s*(?!fill\b)\w+"), "a member of java.util.Arrays other than fill (equals, mismatch, compare, ...)"),
    (re.compile(r"\bBytes\s*\.\s*wrap\b|\bunsafeArray\b"), "a value sharing an array that is erased"),
)
DRAFT03_MATH = re.compile(r"\bDraft03Math\b(?:\s*\.\s*(\w+))?")
FACADE_MATH = {"h2c"}
# A package clause in any of its forms (`package a.b`, `package a.b;`, `package a.b:`, `package a.b {`); `package
# object` is not one.
PACKAGE_CLAUSE = re.compile(r"\bpackage\s+(?!object\b)([A-Za-z_][\w.]*)")
# An import to the end of its line, with a selector in braces however many lines it takes.
IMPORT = re.compile(r"\bimport\s+([^\n;{]*(?:\{[^}]*\})?[^\n;]*)")
# What an import in the secret package may not do: give a name another (`as`, or `=>` in a selector), so that a
# forbidden routine cannot be called under a name the list does not hold; or bring in members of java.util.Arrays,
# which are called as `Arrays.<member>` and so seen.
IMPORT_RULES = (
    (re.compile(r"\bas\b|=>"), "an import that renames (a forbidden name could be called under another)"),
    (re.compile(r"\bArrays\s*\.\s*[\w{*]"), "an import of members of java.util.Arrays"),
)


def code_of(text: str) -> str:
    """`text` without its comments and its string and character literals, each replaced by a space. The
    expressions of an interpolated string (`${...}`, and `$name`) are code and are kept."""
    out = []
    i, n = 0, len(text)
    while i < n:
        if text.startswith("//", i):
            j = text.find("\n", i)
            i = n if j < 0 else j
        elif text.startswith("/*", i):
            depth, i = 1, i + 2
            while i < n and depth:
                if text.startswith("/*", i):
                    depth, i = depth + 1, i + 2
                elif text.startswith("*/", i):
                    depth, i = depth - 1, i + 2
                else:
                    i += 1
        elif text[i] == '"' and i > 0 and (text[i - 1].isalnum() or text[i - 1] == "_"):
            i = interpolated(text, i, out)
        elif text.startswith('"""', i):
            j = text.find('"""', i + 3)
            i = n if j < 0 else j + 3
        elif text[i] in "\"'":
            quote, i = text[i], i + 1
            while i < n and text[i] != quote and text[i] != "\n":
                i += 2 if text[i] == "\\" else 1
            i += 1
        else:
            out.append(text[i])
            i += 1
            continue
        out.append(" ")
    return "".join(out)


def interpolated(text: str, i: int, out: list) -> int:
    """Read the interpolated string that opens at `text[i]`, appending its expressions to `out` as code; the index
    after its closing quote."""
    triple = text.startswith('"""', i)
    close = '"""' if triple else '"'
    i, n = i + len(close), len(text)
    while i < n and not text.startswith(close, i):
        if text.startswith("$$", i):
            i += 2
        elif text.startswith("${", i):
            depth, i = 1, i + 2
            out.append(" ")
            while i < n and depth:
                depth += {"{": 1, "}": -1}.get(text[i], 0)
                if depth:
                    out.append(text[i])
                i += 1
            out.append(" ")
        elif text[i] == "$":
            j = i + 1
            while j < n and (text[j].isalnum() or text[j] == "_"):
                j += 1
            out.append(" " + text[i + 1 : j] + " ")
            i = j
        elif not triple and text[i] == "\n":
            return i
        else:
            i += 2 if (not triple and text[i] == "\\") else 1
    return i + len(close)


def declared_packages(code: str):
    """Every package a source's package clauses name: each clause, and each run of consecutive clauses joined, so
    that chained, nested and block forms all name their full package."""
    names = PACKAGE_CLAUSE.findall(code)
    return {".".join(names[a:b]) for a in range(len(names)) for b in range(a + 1, len(names) + 1)}



def secret_path_violations(root: Path):
    """(path, what it names) for every main source of the secret path that names what it may not."""
    for path in sources(root):
        parts = path.relative_to(root).parts
        if "src" not in parts:
            continue
        i = parts.index("src")
        if i + 3 >= len(parts) or parts[i + 1] != "main" or parts[i + 2] not in LANGS:
            continue
        try:
            text = path.read_text(encoding="utf-8")
        except OSError:
            continue
        code = code_of(text)
        packages = declared_packages(code)
        in_package = any(
            parts[i + 3 : i + 3 + len(secret_parts)] == secret_parts
            or any(p == secret or p.startswith(secret + ".") for p in packages)
            for secret, secret_parts in SECRET_PACKAGES
        )
        facade = i >= 1 and (parts[i - 1], parts[i + 2], parts[-1]) == SECRET_FACADE and PACKAGE in packages
        if not (in_package or facade):
            continue
        for pattern, what in VARIABLE_TIME:
            if pattern.search(code):
                yield path, what
        for match in DRAFT03_MATH.finditer(code):
            if in_package or match.group(1) not in FACADE_MATH:
                yield path, f"Draft03Math.{match.group(1) or ''} (only the prover's Draft03Math.h2c is allowed)"
        if in_package:
            for imported in IMPORT.findall(code):
                for pattern, what in IMPORT_RULES:
                    if pattern.search(imported):
                        yield path, what


def guarded(argv):
    """The containment check, and then the secret path's: 1 if either refuses."""
    secret = sorted(set(secret_path_violations(Path(argv[1] if len(argv) > 1 else "."))))
    for path, what in secret:
        print(f"variable-time name on the secret path: {path}: {what}")
    contained = main(argv)
    if secret:
        print(
            "The secret path (the packages of SECRET_PACKAGES, and Draft03Prover's call into "
            "Draft03Math) names no variable-time routine (VARIABLE_TIME)."
        )
        return 1
    print("crypto guard: the secret path names no variable-time routine")
    return contained


if __name__ == "__main__":
    sys.exit(guarded(sys.argv))
