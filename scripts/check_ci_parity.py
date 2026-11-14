#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Fail when a check can run on a push to main that its pull request did not run, or a push run on main can be cancelled.

Every workflow under `.github/workflows` is read. For each one that a push to `main` can start:

- it must also run on pull requests to `main` (`pull_request`, whose branch filter admits `main`, whose `types`
  keep `opened`, `synchronize` and `reopened`, and whose path filter, if any, is the push's own);
- every job that can run on such a push must be able to run on such a pull request, under an `if` that is no
  narrower: the condition is evaluated for both events, and it passes when it is true on the pull request, or when
  what is left of it once the event is known is the same expression for both and reads nothing of the event. A
  job's `needs` count: a job runs only if what it needs runs, unless its `if` asks for `always()` or `cancelled()`;
- the same holds for every step of such a job that can run on the push;
- such a job and its steps must not read the event (`github.event_name`, `github.event.*`, `github.ref*`,
  `github.head_ref`, `github.base_ref`, `github.actor`, or `$GITHUB_EVENT_NAME` and the like in a script), since the
  guard cannot compare what a script does with it, and must not call a reusable workflow or a local action, which it
  cannot read. What the scripts and actions a step runs do with the event is beyond it;
- the workflow's `concurrency`, and that of each job that can run on the push, must neither cancel a push run
  (`cancel-in-progress` false on push) nor share its group with another run: GitHub replaces a waiting run with a
  newer one of the same group even without `cancel-in-progress`, so the group must hold `github.run_id` on push.

A job that only publishes or has another side effect that cannot happen on a pull request is listed in
`SIDE_EFFECT_JOBS` with the reason; it is exempt from the parity rules, but not from the concurrency rule, and no
other job may need it. An entry that no longer exempts anything is a failure.

The YAML is read with a reader for the subset the workflows use (block mappings and sequences, plain and quoted
scalars, one-line flow sequences and mappings of scalars, literal and folded block scalars). It refuses anything else
(anchors, aliases, tags, nested or multi-line flow collections, multi-line plain or quoted scalars, duplicate keys,
tabs) rather than read it wrongly, and so
does the expression reader for an expression outside GitHub's expression syntax. An `if` the guard cannot decide
is a failure, never a pass.
"""
from __future__ import annotations

from collections.abc import Callable, Iterable, Mapping, Sequence
from dataclasses import dataclass
import json
import math
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
WORKFLOWS = ".github/workflows"

# (workflow path, job id) -> why the job cannot run on a pull request: side effects only. Empty while no job on a
# push to main needs it; an entry without a reason, or one that exempts nothing, is a failure.
SIDE_EFFECT_JOBS: Mapping[tuple[str, str], str] = {}

DEFAULT_PULL_REQUEST_TYPES = frozenset({"opened", "synchronize", "reopened"})
# The `github` fields that say which event started the run, or that differ between a pull request's run and the
# push of its merge (who pushed); a job or step that can run on the push must not read them.
EVENT_FIELDS = frozenset({"event_name", "event", "event_path", "ref", "ref_name", "ref_type", "head_ref", "base_ref",
                          "actor", "actor_id", "triggering_actor"})
# The `github` fields that are the same for both runs. In an `if`, any other field the event does not fix is taken
# to differ between them, so a condition on it is never judged the same for both.
SHARED_GITHUB_FIELDS = frozenset({"repository", "repository_id", "repository_owner", "repository_owner_id",
                                  "server_url", "api_url", "graphql_url", "workflow", "job", "token", "workspace",
                                  "action", "action_path", "retention_days"})
EVENT_VARIABLES = re.compile(r"\bGITHUB_(?:EVENT_NAME|EVENT_PATH|REF|REF_NAME|REF_TYPE|HEAD_REF|BASE_REF)\b")
STATUS_FUNCTIONS = frozenset({"success", "failure", "cancelled", "always"})
RUN_ID = "\x00github.run_id\x00"


class ReadError(ValueError):
    """A workflow, or an expression in it, that the guard refuses to read."""


# --- YAML subset ---------------------------------------------------------------------------------------------------

PLAIN_KEY = re.compile(r"[A-Za-z0-9_$][^:#]*?")
INT = re.compile(r"[-+]?[0-9]+")
FLOAT = re.compile(r"[-+]?(?:\.[0-9]+|[0-9]+\.[0-9]*)(?:[eE][-+]?[0-9]+)?")
DOUBLE_ESCAPES = {'"': '"', "\\": "\\", "/": "/", "n": "\n", "t": "\t", "r": "\r"}


@dataclass
class _Line:
    number: int
    indent: int
    text: str


def read_yaml(text: str) -> object:
    """The value of a YAML document in the subset the workflows use; `ReadError` for anything outside it."""
    if "\x00" in text:
        raise ReadError("NUL byte in the file")
    raw = text.split("\n")
    if raw[-1] == "":
        raw.pop()
    reader = _YamlReader(raw)
    first = reader.peek()
    if first is None:
        return None
    if first.indent != 0:
        raise ReadError(f"line {first.number}: the document must start at column 0")
    value = reader.block()
    rest = reader.peek()
    if rest is not None:
        raise ReadError(f"line {rest.number}: unexpected content")
    return value


class _YamlReader:
    def __init__(self, raw: list[str]):
        self.raw = raw
        self.pos = 0

    def peek(self) -> _Line | None:
        """The next line holding content, skipping blank and comment lines; the position is left on it."""
        while self.pos < len(self.raw):
            line = self.raw[self.pos]
            stripped = line.lstrip(" ")
            if stripped == "" or stripped.startswith("#"):
                self.pos += 1
                continue
            indent = len(line) - len(stripped)
            if stripped.startswith("\t"):
                raise ReadError(f"line {self.pos + 1}: tab in indentation")
            if indent == 0 and (stripped.startswith("---") or stripped.startswith("...") or stripped.startswith("%")):
                raise ReadError(f"line {self.pos + 1}: document markers and directives are not read")
            return _Line(self.pos + 1, indent, stripped.rstrip())
        return None

    def block(self) -> object:
        """The block that starts on the next content line, at that line's indentation."""
        line = self.peek()
        if line is None:
            return None
        return self.sequence(line.indent) if _is_item(line.text) else self.mapping(line.indent)

    def sequence(self, indent: int) -> list[object]:
        items: list[object] = []
        while (line := self.peek()) is not None and line.indent == indent and _is_item(line.text):
            rest = line.text[1:].lstrip(" ")
            if rest == "" or rest.startswith("#"):
                self.pos += 1
                items.append(self.nested(indent))
                continue
            column = indent + (len(line.text) - len(rest))
            if _is_item(rest) or _mapping_key(rest) is not None:
                # The item's content starts a block of its own at its column: read it as if it began the line there.
                self.raw[self.pos] = " " * column + rest
                items.append(self.block())
                continue
            self.pos += 1
            items.append(self.scalar_value(rest, indent, line.number))
        line = self.peek()
        if line is not None and line.indent > indent:
            raise ReadError(f"line {line.number}: unexpected indentation")
        return items

    def mapping(self, indent: int) -> dict[str, object]:
        result: dict[str, object] = {}
        while (line := self.peek()) is not None and line.indent == indent and not _is_item(line.text):
            entry = _mapping_key(line.text)
            if entry is None:
                raise ReadError(f"line {line.number}: expected `key: value`")
            key, rest = entry
            if key in result:
                raise ReadError(f"line {line.number}: duplicate key {key!r}")
            self.pos += 1
            if rest == "" or rest.startswith("#"):
                result[key] = self.nested(indent, compact_sequence=True)
            else:
                result[key] = self.scalar_value(rest, indent, line.number)
        line = self.peek()
        if line is not None and line.indent > indent:
            raise ReadError(f"line {line.number}: unexpected indentation")
        return result

    def nested(self, indent: int, compact_sequence: bool = False) -> object:
        """The block under a key or `-` with nothing after it: more indented, a sequence at the key's column, or null."""
        line = self.peek()
        if line is None:
            return None
        if line.indent > indent:
            return self.block()
        if compact_sequence and line.indent == indent and _is_item(line.text):
            return self.sequence(indent)
        return None

    def scalar_value(self, text: str, indent: int, number: int) -> object:
        if text[0] in "|>":
            return self.block_scalar(text, indent, number)
        value = _inline(text, number)
        line = self.peek()
        if line is not None and line.indent > indent:
            raise ReadError(f"line {line.number}: a scalar continued on the next line is not read")
        return value

    def block_scalar(self, header: str, indent: int, number: int) -> str:
        match = re.fullmatch(r"([|>])([-+]?)(\s+#.*)?", header)
        if match is None:
            raise ReadError(f"line {number}: block scalar header {header!r} is not read (indentation indicators are not)")
        style, chomp = match.group(1), match.group(2)
        lines: list[str] = []
        content_indent: int | None = None
        while self.pos < len(self.raw):
            raw = self.raw[self.pos]
            if raw.strip(" ") == "":
                lines.append(raw)
                self.pos += 1
                continue
            current = len(raw) - len(raw.lstrip(" "))
            if content_indent is None:
                if current <= indent:
                    break
                content_indent = current
            elif current < content_indent:
                break
            lines.append(raw)
            self.pos += 1
        if content_indent is None:
            return ""
        body = [line[content_indent:] if len(line) > content_indent else "" for line in lines]
        while body and body[-1] == "":
            body.pop()
        trailing = len(lines) - len(body)
        if not body:
            return ""
        text = "\n".join(body) if style == "|" else _fold(body)
        if chomp == "-":
            return text
        if chomp == "+":
            return text + "\n" * (1 + trailing)
        return text + "\n"


def _is_item(text: str) -> bool:
    return text == "-" or text.startswith("- ")


def _fold(lines: list[str]) -> str:
    """Folded block scalar content: a single break between two plain lines is a space, other breaks are kept."""
    out = ""
    previous: str | None = None
    breaks = 0
    for line in lines:
        if line == "":
            breaks += 1
            continue
        if previous is None:
            out = "\n" * breaks + line
        else:
            more = line[0] in " \t" or previous[0] in " \t"
            breaks += 1
            separator = "\n" * breaks if more else (" " if breaks == 1 else "\n" * (breaks - 1))
            out += separator + line
        previous, breaks = line, 0
    return out


def _mapping_key(text: str) -> tuple[str, str] | None:
    """(key, rest after `: `) when the text is a mapping entry; None when it is a scalar or a flow collection."""
    if text[0] in "[{":
        return None
    if text[0] in "'\"":
        key, end = _quoted(text, 0, None)
        rest = text[end:]
        if rest == ":" or rest.startswith(": "):
            return key, rest[1:].strip(" ")
        return None
    content = _strip_comment(text)
    match = re.match(r"(.*?):(?: |$)", content)
    if match is None:
        return None
    key = match.group(1)
    if PLAIN_KEY.fullmatch(key) is None:
        if key and key[0] in "?&*!|>%@`{[,]}":
            raise ReadError(f"key {key!r} is not read")
        return None
    return key.rstrip(" "), text[match.end():].strip(" ")


def _strip_comment(text: str) -> str:
    match = re.search(r"(?:^|\s)#", text)
    return (text[:match.start()] if match else text).rstrip(" ")


def _quoted(text: str, start: int, number: int | None) -> tuple[str, int]:
    """The value of the quoted scalar at `start` and the index after its closing quote; it must close on its line."""
    quote = text[start]
    out = []
    i = start + 1
    while i < len(text):
        char = text[i]
        if quote == "'" and char == "'":
            if text[i + 1:i + 2] == "'":
                out.append("'")
                i += 2
                continue
            return "".join(out), i + 1
        if quote == '"' and char == '"':
            return "".join(out), i + 1
        if quote == '"' and char == "\\":
            escape = text[i + 1:i + 2]
            if escape in DOUBLE_ESCAPES:
                out.append(DOUBLE_ESCAPES[escape])
                i += 2
                continue
            if escape == "u" and re.fullmatch(r"[0-9A-Fa-f]{4}", text[i + 2:i + 6]) and text[i + 2:i + 6] != "0000":
                out.append(chr(int(text[i + 2:i + 6], 16)))
                i += 6
                continue
            raise ReadError(f"line {number}: escape \\{escape} is not read")
        out.append(char)
        i += 1
    raise ReadError(f"line {number}: a quoted scalar must close on its line")


def _inline(text: str, number: int) -> object:
    first = text[0]
    if first in "'\"":
        value, end = _quoted(text, 0, number)
        if _strip_comment(text[end:]) != "":
            raise ReadError(f"line {number}: text after a quoted scalar")
        return value
    if first == "[":
        return _flow_sequence(_strip_comment(text), number)
    if first == "{":
        return _flow_mapping(_strip_comment(text), number)
    if first in "&*!%@`|>":
        raise ReadError(f"line {number}: {first!r} (anchors, aliases, tags, reserved) is not read")
    plain = _strip_comment(text)
    if plain.startswith(("- ", "? ")) or ": " in plain or plain.endswith(":"):
        raise ReadError(f"line {number}: plain scalar {plain!r} is not read")
    return _resolve(plain)


def _flow_sequence(text: str, number: int) -> list[object]:
    if not text.endswith("]"):
        raise ReadError(f"line {number}: a flow sequence must close on its line")
    return [_flow_scalar(item, number) for item in _flow_items(text[1:-1], number)]


def _flow_mapping(text: str, number: int) -> dict[str, object]:
    if not text.endswith("}"):
        raise ReadError(f"line {number}: a flow mapping must close on its line")
    result: dict[str, object] = {}
    for item in _flow_items(text[1:-1], number):
        entry = _mapping_key(item)
        if entry is None or entry[1] == "":
            raise ReadError(f"line {number}: flow mapping entry {item!r} is not read")
        key, value = entry
        if key in result:
            raise ReadError(f"line {number}: duplicate key {key!r}")
        result[key] = _flow_scalar(value, number)
    return result


def _flow_items(body: str, number: int) -> list[str]:
    """The comma-separated items of a one-line flow collection, quotes respected; nested collections are refused."""
    items, start, i = [], 0, 0
    while i < len(body):
        if body[i] in "'\"":
            i = _quoted(body, i, number)[1]
            continue
        if body[i] in "[]{}":
            raise ReadError(f"line {number}: nested flow collections are not read")
        if body[i] == ",":
            items.append(body[start:i].strip(" "))
            start = i + 1
        i += 1
    last = body[start:].strip(" ")
    if last:
        items.append(last)
    if any(item == "" for item in items):
        raise ReadError(f"line {number}: empty item in a flow collection")
    return items


def _flow_scalar(text: str, number: int) -> object:
    if text[0] in "'\"":
        value, end = _quoted(text, 0, number)
        if text[end:].strip(" "):
            raise ReadError(f"line {number}: text after a quoted scalar")
        return value
    if text[0] in "#&*!|>%@`?-" and text != "-" and not re.match(r"-[0-9.]", text) or ": " in text or text.endswith(":"):
        raise ReadError(f"line {number}: flow item {text!r} is not read")
    return _resolve(text)


def _resolve(plain: str) -> object:
    """A plain scalar's value under the YAML 1.2 core schema."""
    if plain in ("", "~", "null", "Null", "NULL"):
        return None
    if plain in ("true", "True", "TRUE"):
        return True
    if plain in ("false", "False", "FALSE"):
        return False
    if INT.fullmatch(plain):
        return int(plain)
    if FLOAT.fullmatch(plain):
        return float(plain)
    return plain


# --- GitHub expressions --------------------------------------------------------------------------------------------

TOKEN = re.compile(r"\s*(?:(?P<string>'(?:[^']|'')*')|(?P<number>-?(?:0x[0-9a-fA-F]+|[0-9]+(?:\.[0-9]+)?(?:[eE][-+]?[0-9]+)?))"
                   r"|(?P<name>[A-Za-z_][A-Za-z0-9_-]*)|(?P<op>==|!=|<=|>=|&&|\|\||[<>!().,\[\]]))")


def parse_expression(source: str) -> tuple:
    """The syntax tree of a GitHub expression (without `${{ }}`); `ReadError` outside the syntax."""
    tokens = []
    i = 0
    while i < len(source):
        if source[i:].strip() == "":
            break
        match = TOKEN.match(source, i)
        if match is None:
            raise ReadError(f"expression {source!r}: cannot read at {source[i:]!r}")
        kind = match.lastgroup
        tokens.append((kind, match.group(kind)))
        i = match.end()
    parser = _ExpressionParser(tokens, source)
    tree = parser.disjunction()
    if parser.pos != len(tokens):
        raise ReadError(f"expression {source!r}: unexpected {tokens[parser.pos][1]!r}")
    return tree


class _ExpressionParser:
    def __init__(self, tokens: list[tuple[str, str]], source: str):
        self.tokens, self.source, self.pos = tokens, source, 0

    def peek(self) -> str | None:
        return self.tokens[self.pos][1] if self.pos < len(self.tokens) and self.tokens[self.pos][0] == "op" else None

    def take(self, op: str) -> None:
        if self.peek() != op:
            raise ReadError(f"expression {self.source!r}: expected {op!r}")
        self.pos += 1

    def disjunction(self) -> tuple:
        left = self.conjunction()
        while self.peek() == "||":
            self.pos += 1
            left = ("or", left, self.conjunction())
        return left

    def conjunction(self) -> tuple:
        left = self.equality()
        while self.peek() == "&&":
            self.pos += 1
            left = ("and", left, self.equality())
        return left

    def equality(self) -> tuple:
        left = self.comparison()
        while self.peek() in ("==", "!="):
            op = self.peek()
            self.pos += 1
            left = ("compare", op, left, self.comparison())
        return left

    def comparison(self) -> tuple:
        left = self.unary()
        while self.peek() in ("<", "<=", ">", ">="):
            op = self.peek()
            self.pos += 1
            left = ("compare", op, left, self.unary())
        return left

    def unary(self) -> tuple:
        if self.peek() == "!":
            self.pos += 1
            return ("not", self.unary())
        return self.postfix()

    def postfix(self) -> tuple:
        node = self.primary()
        while True:
            op = self.peek()
            if op == ".":
                self.pos += 1
                if self.pos >= len(self.tokens) or self.tokens[self.pos][0] != "name":
                    raise ReadError(f"expression {self.source!r}: expected a property name")
                node = ("get", node, ("literal", self.tokens[self.pos][1]))
                self.pos += 1
            elif op == "[":
                self.pos += 1
                index = self.disjunction()
                self.take("]")
                node = ("get", node, index)
            else:
                return node

    def primary(self) -> tuple:
        if self.pos >= len(self.tokens):
            raise ReadError(f"expression {self.source!r}: unexpected end")
        kind, text = self.tokens[self.pos]
        self.pos += 1
        if kind == "string":
            return ("literal", text[1:-1].replace("''", "'"))
        if kind == "number":
            return ("literal", int(text, 16) if text.lower().startswith(("0x", "-0x")) else float(text))
        if kind == "name":
            if text in ("true", "false"):
                return ("literal", text == "true")
            if text == "null":
                return ("literal", None)
            if self.peek() == "(":
                self.pos += 1
                args = []
                if self.peek() != ")":
                    args.append(self.disjunction())
                    while self.peek() == ",":
                        self.pos += 1
                        args.append(self.disjunction())
                self.take(")")
                return ("call", text.lower(), tuple(args))
            return ("context", text)
        if text == "(":
            node = self.disjunction()
            self.take(")")
            return node
        raise ReadError(f"expression {self.source!r}: unexpected {text!r}")


@dataclass(frozen=True)
class Unknown:
    """A value that is not known before the run: `event` is true when it differs between the two events."""
    tree: tuple
    event: bool


Value = object  # a JSON value, or Unknown

SHARED_CONTEXTS = frozenset({"vars", "secrets", "needs", "steps", "env", "matrix", "strategy", "job", "jobs", "runner"})
KNOWN_CONTEXTS = SHARED_CONTEXTS | {"github", "inputs"}


@dataclass(frozen=True)
class Event:
    """What is known of `github` for one event: fields given, the event payload's own entries, other fields unknown."""
    name: str
    github: Mapping[str, Value]
    payload: Mapping[str, Value]


PUSH_TO_MAIN = Event("push", {"event_name": "push", "ref": "refs/heads/main", "ref_name": "main",
                              "ref_type": "branch", "base_ref": "", "head_ref": "", "run_id": RUN_ID},
                     {"pull_request": None})
PULL_REQUEST_TO_MAIN = Event("pull_request", {"event_name": "pull_request", "base_ref": "main", "run_id": RUN_ID}, {})


def evaluate(tree: tuple, event: Event) -> Value:
    """The value of the expression for the event, folded as far as the event decides it."""
    kind = tree[0]
    if kind == "literal":
        return tree[1]
    if kind == "context":
        name = tree[1]
        if name not in KNOWN_CONTEXTS:
            raise ReadError(f"unknown context {name!r}")
        if name == "inputs":
            return {}
        return Unknown(tree, False) if name in SHARED_CONTEXTS else ("github",)
    if kind == "get":
        base = evaluate(tree[1], event)
        key = evaluate(tree[2], event)
        return _property(tree, base, key, event)
    if kind == "not":
        value = evaluate(tree[1], event)
        return Unknown(("not", _tree(value)), _is_event(value)) if isinstance(value, Unknown) else not _truthy(value)
    if kind in ("and", "or"):
        left = evaluate(tree[1], event)
        if not isinstance(left, Unknown):
            if _truthy(left) == (kind == "or"):
                return left
            return evaluate(tree[2], event)
        right = evaluate(tree[2], event)
        return Unknown((kind, _tree(left), _tree(right)), _is_event(left) or _is_event(right))
    if kind == "compare":
        left, right = evaluate(tree[2], event), evaluate(tree[3], event)
        if isinstance(left, Unknown) or isinstance(right, Unknown):
            return Unknown(("compare", tree[1], _tree(left), _tree(right)), _is_event(left) or _is_event(right))
        if any(side in (("github",), ("github.event",)) for side in (left, right)):
            raise ReadError("a comparison of a whole context is not evaluated")
        return _compare(tree[1], left, right)
    if kind == "call":
        return _call(tree, event)
    raise ReadError(f"cannot evaluate {tree!r}")


def _property(tree: tuple, base: Value, key: Value, event: Event) -> Value:
    if isinstance(base, Unknown) or isinstance(key, Unknown):
        return Unknown(("get", _tree(base), _tree(key)), _is_event(base) or _is_event(key))
    if base == ("github",):
        if key == "event":
            return ("github.event",)
        if key in event.github:
            return event.github[key]
        return Unknown(tree, key not in SHARED_GITHUB_FIELDS)
    if base == ("github.event",):
        if key in event.payload:
            return event.payload[key]
        return Unknown(tree, True)
    if isinstance(base, dict):
        return base.get(key) if isinstance(key, str) else None
    if base is None or isinstance(base, (str, bool, int, float)):
        return None
    raise ReadError(f"cannot evaluate {tree!r}")


def _call(tree: tuple, event: Event) -> Value:
    name, args = tree[1], [evaluate(arg, event) for arg in tree[2]]
    if any(arg in (("github",), ("github.event",)) for arg in args):
        raise ReadError(f"function {name}() of a whole context is not evaluated")
    if name in STATUS_FUNCTIONS or name == "hashfiles":
        return Unknown(("call", name, tuple(_tree(arg) for arg in args)), any(_is_event(arg) for arg in args))
    if any(isinstance(arg, Unknown) for arg in args):
        return Unknown(("call", name, tuple(_tree(arg) for arg in args)), any(_is_event(arg) for arg in args))
    if name == "format" and args and isinstance(args[0], str):
        def argument(match: re.Match[str]) -> str:
            if match.group(1) is None:
                return match.group(0)[0]
            if 1 + int(match.group(1)) >= len(args):
                raise ReadError(f"format() has no argument {match.group(1)}")
            return _string(args[1 + int(match.group(1))])
        return re.sub(r"\{\{|\}\}|\{(\d+)\}", argument, args[0])
    if name in ("contains", "startswith", "endswith") and len(args) == 2:
        haystack, needle = args
        if name == "contains" and isinstance(haystack, list):
            return any(_compare("==", item, needle) for item in haystack)
        haystack, needle = _string(haystack).casefold(), _string(needle).casefold()
        return {"contains": needle in haystack, "startswith": haystack.startswith(needle),
                "endswith": haystack.endswith(needle)}[name]
    raise ReadError(f"function {name}() with these arguments is not evaluated")


def _tree(value: Value) -> tuple:
    if isinstance(value, Unknown):
        return value.tree
    if value in (("github",), ("github.event",)):
        return ("context-object", value[0])
    return ("literal", json.dumps(value, sort_keys=True))


def _is_event(value: Value) -> bool:
    return isinstance(value, Unknown) and value.event or value == ("github.event",)


def _truthy(value: Value) -> bool:
    if isinstance(value, float) and math.isnan(value):
        return False
    return value not in (False, 0, "", None)


def _number(value: Value) -> float:
    if value is None:
        return 0.0
    if isinstance(value, bool):
        return 1.0 if value else 0.0
    if isinstance(value, (int, float)):
        return float(value)
    if isinstance(value, str):
        text = value.strip()
        if text == "":
            return 0.0
        try:
            return float(int(text, 16)) if text.lower().startswith("0x") else float(json.loads(text))
        except (ValueError, TypeError):
            return math.nan
    return math.nan


def _compare(op: str, left: Value, right: Value) -> bool:
    if isinstance(left, str) and isinstance(right, str):
        a, b = left.casefold(), right.casefold()
    elif type(left) is type(right) and not isinstance(left, (dict, list, tuple)):
        a, b = left, right
    else:
        a, b = _number(left), _number(right)
    if op == "==":
        return a == b
    if op == "!=":
        return not a == b
    return {"<": a < b, "<=": a <= b, ">": a > b, ">=": a >= b}[op]


def _string(value: Value) -> str:
    if value is None:
        return ""
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, float) and value.is_integer():
        return str(int(value))
    if isinstance(value, (dict, list)):
        return json.dumps(value)
    return str(value)


def truth(value: Value) -> bool | None:
    """True or False when the event decides an `if`, None when the run does."""
    if isinstance(value, Unknown):
        return _kleene(value.tree)
    return _truthy(value)


def _kleene(tree: tuple) -> bool | None:
    if tree[0] == "literal":
        return _truthy(json.loads(tree[1]))
    if tree[0] == "not":
        inner = _kleene(tree[1])
        return None if inner is None else not inner
    if tree[0] in ("and", "or"):
        left, right = _kleene(tree[1]), _kleene(tree[2])
        decisive = tree[0] == "or"
        if decisive in (left, right):
            return decisive
        return (not decisive) if left is not None and right is not None else None
    if tree[0] == "context-object":
        return True
    return None


def condition(text: object) -> tuple:
    """The syntax tree of an `if`; absent is `success()`."""
    if text is None:
        return ("call", "success", ())
    if isinstance(text, bool):
        return ("literal", text)
    if not isinstance(text, str):
        raise ReadError(f"`if: {text!r}` is not read")
    stripped = text.strip()
    match = re.fullmatch(r"\$\{\{(.*)\}\}", stripped, re.DOTALL)
    if match is not None and "${{" not in match.group(1) and "}}" not in match.group(1):
        return parse_expression(match.group(1))
    if "${{" in stripped:
        raise ReadError(f"`if: {text}` mixes text and an expression")
    return parse_expression(stripped)


def template(value: object, event: Event) -> Value:
    """A workflow value that may hold `${{ }}`: one expression alone keeps its type, otherwise text."""
    if not isinstance(value, str):
        return value
    parts = re.split(r"\$\{\{(.*?)\}\}", value, flags=re.DOTALL)
    if len(parts) == 3 and parts[0].strip() == "" and parts[2].strip() == "":
        return evaluate(parse_expression(parts[1]), event)
    values = [part if i % 2 == 0 else evaluate(parse_expression(part), event) for i, part in enumerate(parts)]
    if any(isinstance(v, Unknown) for v in values):
        return Unknown(("template", value), any(_is_event(v) for v in values))
    return "".join(_string(v) for v in values)


def expressions(value: object) -> Iterable[str]:
    """Every string in a workflow value, recursively."""
    if isinstance(value, str):
        yield value
    elif isinstance(value, dict):
        for item in value.values():
            yield from expressions(item)
    elif isinstance(value, list):
        for item in value:
            yield from expressions(item)


def reads_event(text: str) -> bool:
    """Whether the text reads which event started the run, in an expression or as a runner variable."""
    if EVENT_VARIABLES.search(text):
        return True
    for source in re.findall(r"\$\{\{(.*?)\}\}", text, flags=re.DOTALL):
        if _reads_event_tree(parse_expression(source)):
            return True
    return False


def _reads_event_tree(tree: tuple) -> bool:
    return any(node[0] == "get" and node[1] == ("context", "github") and node[2][0] == "literal"
               and node[2][1] in EVENT_FIELDS for node in _nodes(tree))


def _children(tree: tuple) -> tuple:
    """The sub-expressions of an expression's syntax tree."""
    kind = tree[0]
    if kind in ("literal", "context"):
        return ()
    if kind == "call":
        return tree[2]
    if kind == "compare":
        return tree[2:]
    return tree[1:]


def _nodes(tree: tuple) -> Iterable[tuple]:
    """Every node of a syntax tree, without recursion (an expression's depth comes from the file)."""
    stack = [tree]
    while stack:
        node = stack.pop()
        yield node
        stack.extend(_children(node))


# --- Triggers ------------------------------------------------------------------------------------------------------

def branch_matches(patterns: Sequence[object], branch: str) -> bool:
    """GitHub's branch filter: patterns in order, `!` excludes; `*` stays within a path segment, `**` does not."""
    matched = False
    for pattern in patterns:
        if not isinstance(pattern, str):
            raise ReadError(f"branch pattern {pattern!r} is not read")
        negated = pattern.startswith("!")
        body = pattern[1:] if negated else pattern
        if re.fullmatch(_glob(body), branch):
            matched = not negated
    return matched


def _glob(pattern: str) -> str:
    out = ""
    i = 0
    while i < len(pattern):
        if pattern.startswith("**", i):
            out += ".*"
            i += 2
        elif pattern[i] == "*":
            out += "[^/]*"
            i += 1
        elif pattern[i] in "?+[]":
            raise ReadError(f"branch pattern {pattern!r}: {pattern[i]!r} is not read")
        else:
            out += re.escape(pattern[i])
            i += 1
    return out


def triggers(workflow: Mapping[str, object]) -> dict[str, object]:
    on = workflow.get("on")
    if isinstance(on, str):
        return {on: None}
    if isinstance(on, list):
        return {str(name): None for name in on}
    if isinstance(on, dict):
        return dict(on)
    raise ReadError("`on` is missing or not read")


def pushes_to_main(on: Mapping[str, object]) -> bool:
    if "push" not in on:
        return False
    push = on["push"] or {}
    if not isinstance(push, dict):
        raise ReadError("`on.push` is not read")
    if "branches" in push:
        return branch_matches(push["branches"], "main")
    if "branches-ignore" in push:
        return not branch_matches(push["branches-ignore"], "main")
    return "tags" not in push and "tags-ignore" not in push


def pull_request_problems(on: Mapping[str, object]) -> list[str]:
    """Why pull requests to main do not run whenever a push to main does; empty when they do."""
    if "pull_request" not in on:
        return ["runs on a push to main but not on pull requests (`pull_request`)"]
    pull = on["pull_request"] or {}
    push = on["push"] or {}
    if not isinstance(pull, dict):
        raise ReadError("`on.pull_request` is not read")
    problems = []
    if "branches" in pull and not branch_matches(pull["branches"], "main"):
        problems.append("its `pull_request` branch filter leaves out main")
    if "branches-ignore" in pull and branch_matches(pull["branches-ignore"], "main"):
        problems.append("its `pull_request` branches-ignore leaves out main")
    types = pull.get("types")
    if "types" in pull and not DEFAULT_PULL_REQUEST_TYPES <= set([types] if isinstance(types, str) else types or ()):
        problems.append("its `pull_request` types leave out one of opened, synchronize, reopened")
    for key in ("paths", "paths-ignore"):
        if key in pull and pull.get(key) != push.get(key):
            problems.append(f"its `pull_request` {key} filter is not the push's own")
    return problems


# --- Rules ---------------------------------------------------------------------------------------------------------

@dataclass(frozen=True)
class Verdict:
    on_push: bool
    ok: bool


def compare_condition(tree: tuple) -> Verdict:
    """Whether a condition can hold on the push, and if so whether the pull request's is no narrower."""
    push, pull = evaluate(tree, PUSH_TO_MAIN), evaluate(tree, PULL_REQUEST_TO_MAIN)
    if truth(push) is False:
        return Verdict(False, True)
    if truth(pull) is True:
        return Verdict(True, True)
    same = isinstance(push, Unknown) and isinstance(pull, Unknown) and push.tree == pull.tree
    return Verdict(True, same and not push.event and not pull.event)


def can_run(jobs: Mapping[str, dict], event: Event) -> dict[str, bool]:
    """Which jobs can run for the event: their `if` can hold and what they need can run, unless the `if` overrides."""
    result: dict[str, bool] = {}
    visiting: set[str] = set()

    def visit(name: str) -> bool:
        if name in result:
            return result[name]
        if name in visiting:
            raise ReadError(f"job {name}: cycle in `needs`")
        if name not in jobs:
            raise ReadError(f"`needs` names a missing job {name!r}")
        visiting.add(name)
        tree = condition(jobs[name].get("if"))
        possible = truth(evaluate(tree, event)) is not False
        if possible and not _runs_without_needs(tree, event):
            possible = all(visit(need) for need in needs(jobs[name]))
        result[name] = possible
        visiting.discard(name)
        return possible

    for name in jobs:
        visit(name)
    return result


def needs(job: Mapping[str, object]) -> list[str]:
    value = job.get("needs") or []
    return [value] if isinstance(value, str) else list(value)


def _runs_without_needs(tree: tuple, event: Event) -> bool:
    """Whether the `if` can hold when a needed job was skipped: success() and failure() false, always() true."""
    if not _calls_status(tree):
        return False
    skipped = _substitute(tree, {"success": False, "failure": False, "cancelled": False, "always": True})
    return truth(evaluate(skipped, event)) is not False


def _calls_status(tree: tuple) -> bool:
    return any(node[0] == "call" and node[1] in STATUS_FUNCTIONS for node in _nodes(tree))


def _substitute(tree: tuple, values: Mapping[str, bool]) -> tuple:
    kind = tree[0]
    if kind == "call" and tree[1] in values and not tree[2]:
        return ("literal", values[tree[1]])
    if kind == "call":
        return ("call", tree[1], tuple(_substitute(arg, values) for arg in tree[2]))
    if kind == "compare":
        return ("compare", tree[1], _substitute(tree[2], values), _substitute(tree[3], values))
    if kind in ("literal", "context"):
        return tree
    return (kind, *(_substitute(child, values) for child in tree[1:]))


def concurrency_problems(where: str, value: object) -> list[str]:
    """Why a `concurrency` can cancel or replace a push run on main; empty when it cannot."""
    if value is None:
        return []
    group, cancel = (value, False) if isinstance(value, str) else (None, None)
    if isinstance(value, dict):
        group, cancel = value.get("group"), value.get("cancel-in-progress", False)
    if group is None:
        raise ReadError(f"{where}: `concurrency` has no group")
    problems = []
    cancels = template(cancel, PUSH_TO_MAIN)
    if isinstance(cancels, Unknown) or _truthy(cancels):
        problems.append(f"{where}: `cancel-in-progress` can be true on a push to main")
    named = template(group, PUSH_TO_MAIN)
    if not isinstance(named, str) or RUN_ID not in named:
        problems.append(f"{where}: on a push to main the concurrency group is not unique to the run (put github.run_id "
                        "in it), so a newer run replaces a waiting one")
    return problems


def workflow_findings(path: str, workflow: object, allowed: Mapping[tuple[str, str], str]) -> tuple[list[str], set[str]]:
    """(findings, ids of the allowlisted jobs that this workflow runs on a push to main) for one workflow."""
    if not isinstance(workflow, dict):
        raise ReadError("the workflow is not a mapping")
    on = triggers(workflow)
    if not pushes_to_main(on):
        return [], set()
    jobs = workflow.get("jobs")
    if not isinstance(jobs, dict) or not all(isinstance(job, dict) for job in jobs.values()):
        raise ReadError("`jobs` is not a mapping of jobs")
    findings = [f"{path}: {problem}" for problem in pull_request_problems(on)]
    findings += concurrency_problems(path, workflow.get("concurrency"))
    findings += [f"{path}: workflow `{key}` reads the event" for key in ("env", "defaults")
                 if any(reads_event(text) for text in expressions(workflow.get(key)))]
    on_push, on_pull = can_run(jobs, PUSH_TO_MAIN), can_run(jobs, PULL_REQUEST_TO_MAIN)
    used: set[str] = set()
    for name, job in jobs.items():
        if not on_push[name]:
            continue
        where = f"{path}: job {name}"
        findings += concurrency_problems(where, job.get("concurrency"))
        problems = job_problems(where, job, on_pull[name])
        if (path, name) in allowed:
            used.add(name)
            if not problems:
                findings.append(f"{where}: runs on pull requests as on a push; remove it from SIDE_EFFECT_JOBS")
            continue
        findings += [f"{where}: needs {need}, which runs on a push only (SIDE_EFFECT_JOBS)"
                     for need in needs(job) if (path, need) in allowed]
        findings += problems
    return findings, used


def job_problems(where: str, job: Mapping[str, object], on_pull: bool) -> list[str]:
    """Why a job that can run on a push to main may not run, or may run differently, on a pull request to main."""
    problems = []
    if not on_pull:
        problems.append(f"{where}: runs on a push to main but cannot run on a pull request to main")
    elif not compare_condition(condition(job.get("if"))).ok:
        problems.append(f"{where}: its `if` may hold on a push to main where it does not on a pull request")
    if "uses" in job:
        problems.append(f"{where}: calls a reusable workflow, which this guard cannot read")
    problems += [f"{where}: `{key}` reads the event" for key, value in job.items()
                 if key not in ("if", "concurrency", "steps") and any(reads_event(t) for t in expressions(value))]
    return problems + step_findings(where, job.get("steps") or [])


def step_findings(where: str, steps: object) -> list[str]:
    if not isinstance(steps, list) or not all(isinstance(step, dict) for step in steps):
        raise ReadError(f"{where}: `steps` is not a list of steps")
    findings = []
    for number, step in enumerate(steps, start=1):
        label = f"{where}, step {number} ({step.get('name') or step.get('uses') or step.get('id') or 'run'})"
        verdict = compare_condition(condition(step.get("if")))
        if not verdict.on_push:
            continue
        if not verdict.ok:
            findings.append(f"{label}: its `if` may hold on a push to main where it does not on a pull request")
        uses = step.get("uses")
        if isinstance(uses, str) and uses.startswith("./"):
            findings.append(f"{label}: uses a local action, which this guard cannot read")
        findings += [f"{label}: `{key}` reads the event" for key, value in step.items()
                     if key != "if" and any(reads_event(text) for text in expressions(value))]
    return findings


def findings(files: Iterable[tuple[str, str]], allowed: Mapping[tuple[str, str], str] = SIDE_EFFECT_JOBS) -> list[str]:
    """Every violation over the (path, text) workflows, in input order, then the allowlist's own problems."""
    rows: list[str] = []
    used: set[tuple[str, str]] = set()
    for path, text in files:
        try:
            found, jobs = workflow_findings(path, read_yaml(text), allowed)
        except ReadError as error:
            rows.append(f"{path}: cannot be read by this guard: {error}")
            continue
        rows += found
        used |= {(path, job) for job in jobs}
    for (path, job), reason in allowed.items():
        if not isinstance(reason, str) or not reason.strip():
            rows.append(f"SIDE_EFFECT_JOBS[{path}, {job}]: give the reason")
        if (path, job) not in used:
            rows.append(f"SIDE_EFFECT_JOBS[{path}, {job}]: names no job that runs on a push to main; remove it")
    return rows


def workflow_files(root: Path) -> list[tuple[str, str]]:
    directory = root / WORKFLOWS
    paths = sorted(list(directory.glob("*.yml")) + list(directory.glob("*.yaml")))
    return [(path.relative_to(root).as_posix(), path.read_text(encoding="utf-8")) for path in paths]


def main(argv: Sequence[str] | None = None, root: Path = ROOT,
         allowed: Mapping[tuple[str, str], str] = SIDE_EFFECT_JOBS, out: Callable[[str], None] = print) -> int:
    if argv:
        out("usage: check_ci_parity.py (no arguments)")
        return 2
    files = workflow_files(root)
    rows = findings(files, allowed)
    for row in rows:
        out(row)
    if rows:
        out(f"ci parity: {len(rows)} problem(s); a check on a push to main must also run on its pull request "
            "(scripts/check_ci_parity.py)")
        return 1
    out(f"ci parity: {len(files)} workflows read, push runs on main match their pull requests")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
