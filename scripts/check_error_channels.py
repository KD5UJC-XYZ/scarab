#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Reject String-valued Scala Either channels, including multiline types and aliases.

This is a source guard, not a semantic proof about domain error design. The Scala
compiler checks the closed error contracts; review checks that cases retain causes.
Comments and string literals (including generated-code templates) are ignored.
"""
from __future__ import annotations

import re
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class Token:
    text: str
    line: int


@dataclass(frozen=True)
class Type:
    name: str
    args: tuple["Type", ...] = ()


def tokens(source: str) -> list[Token]:
    result: list[Token] = []
    at = 0
    line = 1
    while at < len(source):
        start = at
        if source.startswith("//", at):
            end = source.find("\n", at)
            at = len(source) if end < 0 else end
        elif source.startswith("/*", at):
            at += 2
            depth = 1
            while at < len(source) and depth:
                if source.startswith("/*", at):
                    depth += 1
                    at += 2
                elif source.startswith("*/", at):
                    depth -= 1
                    at += 2
                else:
                    at += 1
        elif source.startswith('"""', at):
            end = source.find('"""', at + 3)
            at = len(source) if end < 0 else end + 3
        elif source[at] == '"':
            at += 1
            while at < len(source):
                if source[at] == "\\":
                    at += 2
                elif source[at] == '"':
                    at += 1
                    break
                else:
                    at += 1
        elif source[at] == "`":
            end = source.find("`", at + 1)
            if end < 0:
                at += 1
            else:
                result.append(Token(source[at + 1:end], line))
                at = end + 1
        elif match := re.match(r"[A-Za-z_$][\w$]*", source[at:]):
            result.append(Token(match[0], line))
            at += len(match[0])
        else:
            if not source[at].isspace():
                result.append(Token(source[at], line))
            at += 1
        line += source[start:at].count("\n")
    return result


def parse_type(ts: list[Token], at: int) -> tuple[Type, int] | None:
    if at >= len(ts) or not re.fullmatch(r"[\w$]+", ts[at].text):
        return None
    name = ts[at].text
    at += 1
    while at + 1 < len(ts) and ts[at].text == ".":
        name += "." + ts[at + 1].text
        at += 2
    args: list[Type] = []
    if at < len(ts) and ts[at].text == "[":
        at += 1
        while at < len(ts) and ts[at].text != "]":
            parsed = parse_type(ts, at)
            if parsed is None:
                return None
            value, at = parsed
            args.append(value)
            if at < len(ts) and ts[at].text == ",":
                at += 1
            elif at >= len(ts) or ts[at].text != "]":
                return None
        if at >= len(ts):
            return None
        at += 1
    return Type(name.removeprefix("_root_."), tuple(args)), at


def violations(source: str) -> list[int]:
    ts = tokens(source)
    aliases: dict[str, tuple[tuple[str, ...], Type]] = {}
    # Resolve imports of String/Either under a local name, in either Scala spelling.
    for i, token in enumerate(ts):
        if token.text in {"String", "Either"} and i + 2 < len(ts):
            j = i + 1
            if ts[j].text == "as":
                aliases[ts[j + 1].text] = ((), Type(token.text))
            elif ts[j].text == "=" and ts[j + 1].text == ">" and j + 2 < len(ts):
                aliases[ts[j + 2].text] = ((), Type(token.text))
    for i, token in enumerate(ts):
        if token.text != "type" or i + 2 >= len(ts):
            continue
        name = ts[i + 1].text
        j = i + 2
        params: list[str] = []
        if ts[j].text == "[":
            j += 1
            while j < len(ts) and ts[j].text != "]":
                if re.fullmatch(r"[\w$]+", ts[j].text):
                    params.append(ts[j].text)
                j += 1
            j += 1
        if j < len(ts) and ts[j].text == "=":
            parsed = parse_type(ts, j + 1)
            if parsed:
                aliases[name] = (tuple(params), parsed[0])

    def resolve(value: Type, seen: frozenset[str] = frozenset()) -> Type:
        if value.name in aliases and value.name not in seen:
            params, body = aliases[value.name]
            env = dict(zip(params, value.args))

            def substitute(t: Type) -> Type:
                return env.get(t.name, Type(t.name, tuple(substitute(a) for a in t.args)))

            expanded = substitute(body)
            if not params and not expanded.args:
                expanded = Type(expanded.name, value.args)
            return resolve(expanded, seen | {value.name})
        return Type(value.name, tuple(resolve(a, seen) for a in value.args))

    found = set()
    for i, token in enumerate(ts):
        parsed = parse_type(ts, i)
        if parsed is None:
            # The right type may be a tuple/function/refinement. Inspect the left
            # independently; its validity must not depend on parsing the right.
            j = i + 1
            name = token.text
            while j + 1 < len(ts) and ts[j].text == ".":
                name += "." + ts[j + 1].text
                j += 2
            left = parse_type(ts, j + 1) if j < len(ts) and ts[j].text == "[" else None
            if left is None:
                continue
            parsed = (Type(name, (left[0], Type("_"))), j)
        t = resolve(parsed[0])
        if t.name in {"Either", "scala.Either", "scala.util.Either", "scala.package.Either"} and t.args:
            left = t.args[0]
            if left.name in {"String", "Predef.String", "scala.Predef.String", "java.lang.String"}:
                found.add(token.line)
    return sorted(found)


def main() -> int:
    root = Path(__file__).resolve().parent.parent
    paths = subprocess.check_output(["git", "ls-files", "-z", "--", "*.scala"], cwd=root).split(b"\0")
    failed = False
    for raw in paths:
        if not raw:
            continue
        path = Path(raw.decode())
        for line in violations((root / path).read_text(encoding="utf-8")):
            print(f"{path}:{line}: String-valued Either; use a domain error ADT", file=sys.stderr)
            failed = True
    return int(failed)


if __name__ == "__main__":
    raise SystemExit(main())
