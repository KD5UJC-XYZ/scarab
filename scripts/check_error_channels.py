#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Reject declared String-valued Scala Either channels across project sources.

This deliberately small source guard resolves ordinary aliases and imports, not Scala's
complete type system. Opaque aliases are domain types, so their String representation is
not expanded. Inferred Left values, type lambdas, implicit conversions, and path-dependent
or match types require compiler/AST analysis and review; passing is not a semantic proof.
Comments and literals (including generated-code templates) are ignored.
"""
from __future__ import annotations

import re
import subprocess
import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import Mapping


@dataclass(frozen=True)
class Token:
    text: str
    line: int
    column: int = 0


@dataclass(frozen=True)
class Type:
    name: str
    args: tuple["Type", ...] = ()


@dataclass(frozen=True)
class Scope:
    start: int
    end: int
    name: str
    parameters: tuple[str, ...] = ()


@dataclass
class Source:
    ts: list[Token]
    package: str
    scopes: list[Scope] = field(default_factory=list)
    imports: dict[str, dict[str, str]] = field(default_factory=dict)
    wildcards: dict[str, list[str]] = field(default_factory=dict)

    def owner(self, at: int) -> str:
        scopes = [s for s in self.scopes if s.name and s.start < at < s.end]
        return max(scopes, key=lambda s: s.start).name if scopes else self.package

    def parameters(self, at: int) -> dict[str, Type]:
        return {p: Type("$parameter:" + p)
                for s in self.scopes if s.start < at < s.end for p in s.parameters}


@dataclass(frozen=True)
class Alias:
    source: Source
    owner: str
    parameters: tuple[str, ...]
    body: Type | None


def tokens(source: str) -> list[Token]:
    result: list[Token] = []
    at = 0
    line = 1
    line_start = 0
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
        elif source[at] == "'" and (match := re.match(r"'(?:\\.|[^'\\\n])'", source[at:])):
            at += len(match[0])
        elif source[at] == "`":
            end = source.find("`", at + 1)
            if end < 0:
                at += 1
            else:
                result.append(Token(source[at + 1:end], line, at - line_start))
                at = end + 1
        elif match := re.match(r"[A-Za-z_$][\w$]*", source[at:]):
            result.append(Token(match[0], line, at - line_start))
            at += len(match[0])
        else:
            if not source[at].isspace():
                result.append(Token(source[at], line, at - line_start))
            at += 1
        skipped = source[start:at]
        line += skipped.count("\n")
        if "\n" in skipped:
            line_start = start + skipped.rfind("\n") + 1
    return result


def qualified(ts: list[Token], at: int) -> tuple[str, int] | None:
    if at >= len(ts) or not re.fullmatch(r"[\w$]+", ts[at].text):
        return None
    name = ts[at].text
    at += 1
    while at + 1 < len(ts) and ts[at].text == "." and re.fullmatch(r"[\w$]+", ts[at + 1].text):
        name += "." + ts[at + 1].text
        at += 2
    return name.removeprefix("_root_."), at


def parse_type(ts: list[Token], at: int) -> tuple[Type, int] | None:
    """Parse named applications, parenthesized/tuple types and ordinary arrows/unions."""
    if at >= len(ts):
        return None
    if ts[at].text == "(":
        at += 1
        args: list[Type] = []
        while at < len(ts) and ts[at].text != ")":
            parsed = parse_type(ts, at)
            if parsed is None:
                return None
            value, at = parsed
            args.append(value)
            if at < len(ts) and ts[at].text == ",":
                at += 1
            elif at >= len(ts) or ts[at].text != ")":
                return None
        if at >= len(ts):
            return None
        at += 1
        value = args[0] if len(args) == 1 else Type("$tuple", tuple(args))
    else:
        parsed_name = qualified(ts, at)
        if parsed_name is None:
            return None
        name, at = parsed_name
        args = []
        if at < len(ts) and ts[at].text == "[":
            at += 1
            while at < len(ts) and ts[at].text != "]":
                parsed = parse_type(ts, at)
                if parsed is None:
                    return None
                argument, at = parsed
                args.append(argument)
                if at < len(ts) and ts[at].text == ",":
                    at += 1
                elif at >= len(ts) or ts[at].text != "]":
                    return None
            if at >= len(ts):
                return None
            at += 1
        value = Type(name, tuple(args))
    if at < len(ts) and ts[at].text in {"|", "&"}:
        operator = ts[at].text
        right = parse_type(ts, at + 1)
        if right:
            return Type("$" + operator, (value, right[0])), right[1]
    if at + 1 < len(ts) and ts[at].text == "=" and ts[at + 1].text == ">":
        right = parse_type(ts, at + 2)
        if right:
            return Type("$function", (value, right[0])), right[1]
    return value, at


def parameters(ts: list[Token], at: int) -> tuple[tuple[str, ...], int]:
    if at >= len(ts) or ts[at].text != "[":
        return (), at
    depth = 1
    expect_name = True
    result: list[str] = []
    at += 1
    while at < len(ts) and depth:
        text = ts[at].text
        if depth == 1 and expect_name and re.fullmatch(r"[\w$]+", text):
            result.append(text)
            expect_name = False
        if text == "[":
            depth += 1
        elif text == "]":
            depth -= 1
        elif text == "," and depth == 1:
            expect_name = True
        at += 1
    return tuple(result), at


def scope_end(ts: list[Token], start: int) -> int:
    """Find braces when present, otherwise the declaration's indentation region."""
    line = ts[start].line
    column = ts[start].column
    # `opaque type` starts two columns after opaque; use its declaration's leading column.
    same_line = [t.column for t in ts[:start + 1] if t.line == line]
    column = min(same_line, default=column)
    brace = next((i for i in range(start + 1, len(ts))
                  if ts[i].line == line and ts[i].text == "{"), None)
    boundary = next((i for i in range(start + 1, len(ts))
                     if ts[i].line == line and ts[i].text in {";", "}"}), None)
    if boundary is not None and (brace is None or boundary < brace):
        return boundary
    if brace is not None:
        depth = 1
        for i in range(brace + 1, len(ts)):
            depth += (ts[i].text == "{") - (ts[i].text == "}")
            if depth == 0:
                return i
    for i in range(start + 1, len(ts)):
        if ts[i].line > line and ts[i].column <= column:
            return i
    return len(ts)


def parents(owner: str):
    while owner:
        yield owner
        owner = owner.rpartition(".")[0]
    yield ""


def make_source(text: str) -> Source:
    ts = tokens(text)
    package = ""
    for i, token in enumerate(ts):
        if token.text == "package" and i + 1 < len(ts) and ts[i + 1].text != "object":
            name = qualified(ts, i + 1)
            if name:
                package = ".".join(filter(None, (package, name[0])))
    source = Source(ts, package)
    for i, token in enumerate(ts):
        if token.text in {"object", "class", "trait", "enum", "def", "type"} and i + 1 < len(ts):
            params, after_params = parameters(ts, i + 2)
            end = scope_end(ts, i)
            if token.text == "type" and after_params < len(ts) and ts[after_params].text == "=":
                body = parse_type(ts, after_params + 1)
                if body:
                    end = body[1]
            name = ""
            if token.text in {"object", "class", "trait", "enum"}:
                name = ".".join(filter(None, (source.owner(i), ts[i + 1].text)))
            elif token.text == "def":
                # Local aliases cannot escape a method or leak to a sibling method.
                name = ".".join(filter(None, (source.owner(i), f"$local${ts[i + 1].text}${i}")))
            source.scopes.append(Scope(i, end, name, params))
    for i, token in enumerate(ts):
        if token.text != "import":
            continue
        imported = qualified(ts, i + 1)
        if imported is None:
            continue
        prefix, j = imported
        owner = source.owner(i)
        names = source.imports.setdefault(owner, {})
        wildcard = source.wildcards.setdefault(owner, [])
        if prefix.endswith("._"):
            wildcard.append(prefix[:-2])
        elif j + 1 < len(ts) and ts[j].text == "." and ts[j + 1].text == "{":
            j += 2
            while j < len(ts) and ts[j].text != "}":
                original = ts[j].text
                if original in {"*", "_"}:
                    wildcard.append(prefix)
                    j += 1
                    continue
                local = original
                j += 1
                if j < len(ts) and ts[j].text == "as":
                    local = ts[j + 1].text
                    j += 2
                elif j + 2 < len(ts) and ts[j].text == "=" and ts[j + 1].text == ">":
                    local = ts[j + 2].text
                    j += 3
                if local != "_":
                    names[local] = prefix + "." + original
                if j < len(ts) and ts[j].text == ",":
                    j += 1
        elif j + 1 < len(ts) and ts[j].text == "." and ts[j + 1].text in {"*", "_"}:
            wildcard.append(prefix)
        else:
            local = prefix.rsplit(".", 1)[-1]
            if j + 1 < len(ts) and ts[j].text == "as":
                local = ts[j + 1].text
            elif j + 2 < len(ts) and ts[j].text == "=" and ts[j + 1].text == ">":
                local = ts[j + 2].text
            if local != "_":
                names[local] = prefix
    return source


EITHERS = {"Either", "scala.Either", "scala.util.Either", "scala.package.Either"}
STRINGS = {"String", "Predef.String", "scala.Predef.String", "java.lang.String"}


def project_violations(texts: Mapping[str, str]) -> dict[str, list[int]]:
    sources = {path: make_source(text) for path, text in texts.items()}
    aliases: dict[str, Alias] = {}
    for source in sources.values():
        ts = source.ts
        for i, token in enumerate(ts):
            if token.text != "type" or i + 2 >= len(ts):
                continue
            owner = source.owner(i)
            params, at = parameters(ts, i + 2)
            if at >= len(ts) or ts[at].text != "=":
                continue
            body = parse_type(ts, at + 1)
            opaque = i > 0 and ts[i - 1].text == "opaque"
            if body or opaque:
                if body and body[1] < len(ts) and ts[body[1]].text == "match":
                    body = (Type("$unsupported-match"), body[1])
                name = ".".join(filter(None, (owner, ts[i + 1].text)))
                aliases[name] = Alias(source, owner, params, None if opaque else body[0])

    def resolve(value: Type, source: Source, owner: str, bindings: dict[str, Type],
                seen: frozenset[str] = frozenset()) -> Type:
        if value.name in bindings:
            return bindings[value.name]
        args = tuple(resolve(a, source, owner, bindings, seen) for a in value.args)
        name = value.name
        candidates: list[str] = []
        for scope in parents(owner):
            imported = source.imports.get(scope, {}).get(name.split(".")[0])
            if imported:
                candidates.append(imported + name[len(name.split(".")[0]):])
            candidates.append(".".join(filter(None, (scope, name))))
            candidates.extend(prefix + "." + name for prefix in source.wildcards.get(scope, ()))
        target = next((c for c in candidates if c in aliases), None)
        if target is not None:
            if target in seen:
                return Type("$cycle:" + target)
            alias = aliases[target]
            if alias.body is None:
                return Type("$opaque:" + target, args)
            env = {p: a for p, a in zip(alias.parameters, args)}
            env.update({p: Type("$parameter:" + p) for p in alias.parameters if p not in env})
            body = alias.body
            if not alias.parameters and not body.args:
                body = Type(body.name, args)
            return resolve(body, alias.source, alias.owner, env, seen | {target})
        imported = next((c for c in candidates if c in EITHERS | STRINGS), None)
        return Type(imported or name, args)

    def string_channel(value: Type) -> bool:
        if value.name in STRINGS:
            return True
        return value.name in {"$|", "$&"} and any(string_channel(a) for a in value.args)

    result: dict[str, list[int]] = {}
    for path, source in sources.items():
        found: set[int] = set()
        ts = source.ts
        for i, token in enumerate(ts):
            parsed = parse_type(ts, i)
            if parsed is None:
                # Unknown/refined right types must not hide a parseable left type.
                head = qualified(ts, i)
                if head is None or head[1] >= len(ts) or ts[head[1]].text != "[":
                    continue
                left = parse_type(ts, head[1] + 1)
                if left is None:
                    continue
                parsed = (Type(head[0], (left[0], Type("$unknown"))), head[1])
            value = resolve(parsed[0], source, source.owner(i), source.parameters(i))
            if value.name in EITHERS and value.args and string_channel(value.args[0]):
                found.add(token.line)
        if found:
            result[path] = sorted(found)
    return result


def violations(source: str) -> list[int]:
    return project_violations({"<input>": source}).get("<input>", [])


def main() -> int:
    root = Path(__file__).resolve().parent.parent
    paths = subprocess.check_output(["git", "ls-files", "-z", "--", "*.scala"], cwd=root).split(b"\0")
    sources = {raw.decode(): (root / raw.decode()).read_text(encoding="utf-8") for raw in paths if raw}
    found = project_violations(sources)
    for path, lines in found.items():
        for line in lines:
            print(f"{path}:{line}: String-valued Either; use a domain error ADT", file=sys.stderr)
    return int(bool(found))


if __name__ == "__main__":
    raise SystemExit(main())
