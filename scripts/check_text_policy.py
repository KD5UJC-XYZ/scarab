#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Fail when a ledger reader reaches for `Value.Text` itself instead of reading text through `CborRead`.

Text has one policy: `instance DecCBOR Text` reads a definite-length string (`decodeString`,
`libs/cardano-ledger-binary/src/Cardano/Ledger/Binary/Decoding/DecCBOR.hs:356-358`, cardano-ledger
f649f975), and `CborRead.text` is that rule in this tree — it refuses an indefinite (chunked) string,
whose head is `0x7f`. A reader that mentions the constructor on its own bypasses the policy: the relay
name reader had to write a second refusal by hand (#435), and a reader that never reaches `CborRead` at
all — one that matches a map itself and never looks at its keys (#552) — cannot be fixed by any argument
to it. The rule is therefore a guard at the call site, not a parameter (#562).

The check reads `ledger/src/main/**/*.scala` only, which is the scope the issue sets: outside that module
text is read by the Byron decoders (their own bytes and decoder version), the metadata reader and Plutus
`Data`, each of which has its own rule. `CborRead.scala` is where the rule lives, so it is not a
violation; the three readers in `ALLOWED` mention the constructor deliberately and say why.

Every mention counts, not only a constructor call: `case t: Value.Text => t.v` is a type test that reads
the text with no parenthesis, so a bare check for `Value.Text(` would leave the obvious bypass open.
Comments, string and character literals are blanked before the match, with newlines kept, so a scaladoc
that quotes the constructor is documentation and not a read. Two forms stay out of reach and the guard
does not claim them: a mention inside a string interpolation is blanked with the string, and a reference
reached only through a wildcard import (`import Value.*; Text(v)`) names no `Value.Text` at all.

An `ALLOWED` entry must carry its reason and a live match: an entry with no reason, or one whose file no
longer mentions the constructor, fails the check, so the list stays short as the issue asks.

Usage: python3 scripts/check_text_policy.py [repo-root]
Exit 0 when every mention is the owning reader or an allowlisted one; 1 with `path:line` for each mention
outside them, and with each allowlist entry that carries no reason or no live match.
"""
from __future__ import annotations

from collections.abc import Iterable, Mapping
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
# The module the issue scopes, and the one file that owns the rule.
MODULE = "ledger/src/main"
OWNER = "ledger/src/main/scala/xyz/kd5ujc/scarab/ledger/CborRead.scala"

# Readers that mention `Value.Text` on purpose, each because the text policy does not apply there. Keep
# this short: an entry claims this reader is not reading a block-body text field. An entry without a
# reason is a failure, and so is one whose file no longer mentions the constructor.
ALLOWED: Mapping[str, str] = {
    "ledger/src/main/scala/xyz/kd5ujc/scarab/ledger/AuxiliaryScripts.scala":
        "the metadata reader: `decodeMetadatum` reads a chunked text as the joined text and a definite "
        "one as itself (Metadata.hs:153-222), so the chunked form is legal here and the policy's "
        "refusal would be wrong",
    "ledger/src/main/scala/xyz/kd5ujc/scarab/ledger/Semantic.scala":
        "names the joined value for an opaque view of a node (a map key, a parameter value); it reads "
        "text to say what a value is, not as a field of a block body",
    "ledger/src/main/scala/xyz/kd5ujc/scarab/ledger/ForgeProblems.scala":
        "`KeyShape.of` names the shape of a malformed map key and discards its contents "
        "(`Value.Text(_)`), so no text is read at all",
}

SITE = re.compile(r"Value\.Text\b")
CHAR = re.compile(r"'(?:\\.|[^'\\\n])'")


def blanked(text: str) -> str:
    """`text` with every character but a newline replaced by a space, so offsets and line numbers hold."""
    return "".join("\n" if char == "\n" else " " for char in text)


def code(source: str) -> str:
    """`source` with comments and string and character literals blanked, newlines kept.

    A scaladoc that quotes `Value.Text` is documentation, and a string literal is not a read; blanking
    rather than deleting keeps the line of every remaining match. A string interpolation is blanked with
    the string that contains it, which the module docstring states as a limit of this guard.
    """
    out: list[str] = []
    at, end = 0, len(source)
    while at < end:
        if source.startswith("//", at):
            line_end = source.find("\n", at)
            line_end = end if line_end < 0 else line_end
            out.append(blanked(source[at:line_end]))
            at = line_end
        elif source.startswith("/*", at):
            start, depth = at, 0
            while at < end:
                if source.startswith("/*", at):
                    depth += 1
                    at += 2
                elif source.startswith("*/", at):
                    depth -= 1
                    at += 2
                    if depth == 0:
                        break
                else:
                    at += 1
            out.append(blanked(source[start:at]))
        elif source.startswith('"""', at):
            close = source.find('"""', at + 3)
            close = end if close < 0 else close + 3
            out.append(blanked(source[at:close]))
            at = close
        elif source[at] == '"':
            close = at + 1
            while close < end:
                if source[close] == "\\":
                    close += 2
                elif source[close] == '"':
                    close += 1
                    break
                elif source[close] == "\n":
                    break
                else:
                    close += 1
            out.append(blanked(source[at:close]))
            at = close
        elif source[at] == "'" and (literal := CHAR.match(source, at)):
            out.append(blanked(literal.group()))
            at = literal.end()
        else:
            out.append(source[at])
            at += 1
    return "".join(out)


def reads(
    files: Iterable[tuple[str, bytes]],
    owner: str = OWNER,
) -> dict[str, list[int]]:
    """The line of every `Value.Text` mention in the module's sources other than `owner`, by path.

    Allowlisted files are included: their mentions are what a stale allowlist entry is measured against.
    """
    found: dict[str, list[int]] = {}
    for path, content in files:
        if not path.endswith(".scala") or not path.startswith(MODULE + "/") or path == owner:
            continue
        text = code(content.decode("utf-8", errors="replace"))
        found[path] = [text.count("\n", 0, match.start()) + 1 for match in SITE.finditer(text)]
    return found


def findings(
    files: Iterable[tuple[str, bytes]],
    allowed: Mapping[str, str] = ALLOWED,
    owner: str = OWNER,
) -> list[str]:
    """`path:line: Value.Text` for each mention outside the owning reader and the allowlist."""
    return [
        f"{path}:{line}: Value.Text"
        for path, lines in sorted(reads(files, owner).items())
        if not allowed.get(path)
        for line in lines
    ]


def unexplained(allowed: Mapping[str, str] = ALLOWED) -> list[str]:
    """Allowlist entries with no reason."""
    return [path for path, reason in allowed.items() if not (reason or "").strip()]


def sources(root: Path) -> Iterable[tuple[str, bytes]]:
    base = root / MODULE
    for path in sorted(base.rglob("*.scala")):
        if "target" in path.parts:
            continue
        yield path.relative_to(root).as_posix(), path.read_bytes()


def main() -> int:
    root = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else ROOT
    files = list(sources(root))
    scanned = {path for path, _ in files}
    found = reads(files)
    rows = findings(files)
    missing = [path for path in ALLOWED if path not in scanned]
    stale = [path for path in ALLOWED if path in found and not found[path]]
    empty = unexplained()
    if not scanned:
        scopes = [f"{MODULE}: no Scala sources found under it"]
    elif OWNER not in scanned:
        scopes.append(f"{OWNER}: the owning reader is not among the module's sources")
    else:
        scopes = []
    if rows:
        print("Value.Text mentions outside the owning reader (read text with CborRead.text, or carry the")
        print("head test in CborRead so a reader with its own errors can translate it):")
        print("\n".join(rows))
    if empty:
        print("allowlist entries without a reason (say why the text policy does not apply):")
        print("\n".join(empty))
    if missing or stale:
        print("allowlist entries without a live match (drop them, so the list stays short):")
        print("\n".join(missing + stale))
    if scopes:
        print("the guard did not cover what it claims (a pass here would be vacuous):")
        print("\n".join(scopes))
    if rows or empty or missing or stale or scopes:
        return 1
    print(f"text is read through CborRead in {MODULE}: {len(scanned)} sources, no Value.Text mention "
          f"outside it and its {len(ALLOWED)} allowlisted readers")
    return 0


if __name__ == "__main__":
    sys.exit(main())
