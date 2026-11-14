#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Fail when a check can run on a push to main that its pull request did not run, or a push run on main can be cancelled.

Every workflow under `.github/workflows` is read. For each one that a push to `main` can start:

- it must also run on pull requests to `main` (`pull_request`, whose branch filter admits `main`, whose `types`
  keep `opened`, `synchronize` and `reopened`, and whose path filter, if any, is the push's own);
- every job that can run on such a push must be able to run on such a pull request, under an `if` that is no
  narrower: the condition is evaluated for both events, and it passes when it is true on the pull request, or when
  what is left of it once the event is known is the same expression for both and reads nothing of the event. A
  job's `needs` count: a job runs only if what it needs runs, unless its `if` calls `always()`, `failure()` or
  `cancelled()`. What a job or step that runs differently on the two events produces (`needs.<job>.*`,
  `steps.<id>.*`, by name, by a computed name or as a whole context) depends on the event;
- the same holds for every step of such a job that can run on the push;
- such a job and its steps must not read the event (`github.event_name`, `github.event.*`, `github.ref*`,
  `github.head_ref`, `github.base_ref`, `github.actor`, a `github` field by a computed name, the whole `github`
  context, or `$GITHUB_EVENT_NAME` and the like in a script), nor what a job or step that runs differently
  produced, since the guard cannot compare what a script does with them, and must not call a reusable workflow or
  a local action, which it cannot read. A step that runs differently must not write `$GITHUB_ENV` or `$GITHUB_PATH`
  for the steps after it. What the scripts and actions a step runs do beyond what the workflow shows is beyond the
  guard. A step that the pull request's run skips because an earlier step or job failed is not compared: that run
  is already red;
- the concurrency of such a workflow, and of each job that can run on the push, must neither cancel a push run
  (`cancel-in-progress` false on push) nor let another run take its group: GitHub replaces a waiting run with a
  newer one of the same group even without `cancel-in-progress`, and groups are shared across the repository's
  workflows. So on push the group holds `github.run_id`, no two groups of one run (nor the jobs of one matrix)
  are the same, and no pull request run's group, in any workflow, can be the same text: their fixed text must
  differ where the push run's group and the pull request's group both have it.

A job that only publishes or has another side effect that cannot happen on a pull request is listed in
`SIDE_EFFECT_JOBS` with the reason; it is exempt from the parity rules, but not from the concurrency rule, and no
other job may need it. An entry that no longer exempts anything is a failure.

The YAML is read with a reader for the subset the workflows use (block mappings and sequences, plain and quoted
scalars resolved by the YAML 1.2 core schema, one-line flow sequences and mappings of scalars, literal and folded
block scalars). It refuses anything else (anchors, aliases, tags, nested or multi-line flow collections, multi-line
plain or quoted scalars, duplicate keys, tabs, nesting deeper than MAX_DEPTH) rather than read it wrongly, and so
does the expression reader for an expression outside GitHub's expression syntax. An `if` or a
group the guard cannot decide is a failure, never a pass. Comparisons across types (where the runner converts, a
string to a number in 32 bits among them) and of non-ASCII text are refused rather than modelled; the run id is a
value only the run knows.
"""
from __future__ import annotations

from collections.abc import Callable, Iterable, Mapping, Sequence
from dataclasses import dataclass, replace
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
ENVIRONMENT_FILES = re.compile(r"\bGITHUB_(?:ENV|PATH)\b")
EVENT_VARIABLES = re.compile(r"\bGITHUB_(?:EVENT_NAME|EVENT_PATH|REF|REF_NAME|REF_TYPE|HEAD_REF|BASE_REF|ACTOR|ACTOR_ID"
                             r"|TRIGGERING_ACTOR)\b")
STATUS_FUNCTIONS = frozenset({"success", "failure", "cancelled", "always"})
RUN_ID = "\x00github.run_id\x00"


class ReadError(ValueError):
    """A workflow, or an expression in it, that the guard refuses to read."""


# --- YAML subset ---------------------------------------------------------------------------------------------------

PLAIN_KEY = re.compile(r"[A-Za-z0-9_$][^:#]*?")
# The YAML 1.2 core schema's integers and floats (https://yaml.org/spec/1.2.2/#1032-tag-resolution).
INT = re.compile(r"[-+]?[0-9]+")
OCTAL = re.compile(r"0o[0-7]+")
HEX = re.compile(r"0x[0-9a-fA-F]+")
FLOAT = re.compile(r"[-+]?(?:\.[0-9]+|[0-9]+(?:\.[0-9]*)?)(?:[eE][-+]?[0-9]+)?")
INFINITY = re.compile(r"([-+]?)\.(?:inf|Inf|INF)")
NAN = re.compile(r"\.(?:nan|NaN|NAN)")
# Deeper nesting is refused before it can exhaust the interpreter's stack.
MAX_DEPTH = 32
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
    final_newline = raw[-1] == ""
    if final_newline:
        raw.pop()
    reader = _YamlReader(raw, final_newline)
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
    def __init__(self, raw: list[str], final_newline: bool):
        self.raw = raw
        self.final_newline = final_newline
        self.pos = 0
        self.depth = 0

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
        if self.depth >= MAX_DEPTH:
            raise ReadError(f"line {line.number}: nested more than {MAX_DEPTH} deep")
        self.depth += 1
        value = self.sequence(line.indent) if _is_item(line.text) else self.mapping(line.indent)
        self.depth -= 1
        return value

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
        # The last line of the file has no line break when the file does not end with one.
        unbroken = 1 if self.pos >= len(self.raw) and not self.final_newline else 0
        if content_indent is None:
            return "\n" * (len(lines) - unbroken) if chomp == "+" else ""
        body = [line[content_indent:] if len(line) > content_indent else "" for line in lines]
        while body and body[-1] == "":
            body.pop()
        trailing = len(lines) - len(body)
        if not body:
            return "\n" * (trailing - unbroken) if chomp == "+" else ""
        text = "\n".join(body) if style == "|" else _fold(body)
        breaks = 1 + trailing - unbroken
        if chomp == "-":
            return text
        if chomp == "+":
            return text + "\n" * breaks
        return text + ("\n" if breaks else "")


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
    if OCTAL.fullmatch(plain):
        return int(plain[2:], 8)
    if HEX.fullmatch(plain):
        return int(plain[2:], 16)
    if FLOAT.fullmatch(plain):
        return float(plain)
    if (infinity := INFINITY.fullmatch(plain)) is not None:
        return -math.inf if infinity.group(1) == "-" else math.inf
    if NAN.fullmatch(plain):
        return math.nan
    return plain


# --- GitHub expressions --------------------------------------------------------------------------------------------

TOKEN = re.compile(r"\s*(?:(?P<string>'(?:[^']|'')*')|(?P<number>-?(?:0x[0-9a-fA-F]+|[0-9]+(?:\.[0-9]+)?(?:[eE][-+]?[0-9]+)?))"
                   r"|(?P<name>[A-Za-z_][A-Za-z0-9_-]*)|(?P<op>==|!=|<=|>=|&&|\|\||[<>!().,\[\]]))")


def parse_expression(source: str) -> tuple:
    """The syntax tree of a GitHub expression (without `${{ }}`); `ReadError` outside the syntax or too deep."""
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
    if _depth(tree) > 2 * MAX_DEPTH:
        raise ReadError(f"expression {source!r}: nested more than {2 * MAX_DEPTH} deep")
    return tree


class _ExpressionParser:
    def __init__(self, tokens: list[tuple[str, str]], source: str):
        self.tokens, self.source, self.pos, self.depth = tokens, source, 0, 0

    def peek(self) -> str | None:
        return self.tokens[self.pos][1] if self.pos < len(self.tokens) and self.tokens[self.pos][0] == "op" else None

    def take(self, op: str) -> None:
        if self.peek() != op:
            raise ReadError(f"expression {self.source!r}: expected {op!r}")
        self.pos += 1

    def nested(self, read: Callable[[], tuple]) -> tuple:
        """A sub-expression in parentheses, brackets, arguments or after `!`, within the depth budget."""
        if self.depth >= MAX_DEPTH:
            raise ReadError(f"expression {self.source!r}: nested more than {MAX_DEPTH} deep")
        self.depth += 1
        tree = read()
        self.depth -= 1
        return tree

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
            return ("not", self.nested(self.unary))
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
                index = self.nested(self.disjunction)
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
                    args.append(self.nested(self.disjunction))
                    while self.peek() == ",":
                        self.pos += 1
                        args.append(self.nested(self.disjunction))
                self.take(")")
                return ("call", text.lower(), tuple(args))
            return ("context", text)
        if text == "(":
            node = self.nested(self.disjunction)
            self.take(")")
            return node
        raise ReadError(f"expression {self.source!r}: unexpected {text!r}")


@dataclass(frozen=True)
class Unknown:
    """A value that is not known before the run: `event` is true when it can differ between the two events."""
    tree: tuple
    event: bool


Value = object  # a JSON value, a context marker, or Unknown

# Markers for whole contexts: `github`, `github.event`, and a value of a pull request's own that only the run knows.
GITHUB = ("github",)
PAYLOAD = ("github.event",)
VARIABLE = ("variable",)
MARKERS = (GITHUB, PAYLOAD, VARIABLE)
# VARIABLE turned into text, inside a concurrency group.
PULL_REQUEST_TEXT = "\x00pull request\x00"

SHARED_CONTEXTS = frozenset({"vars", "secrets", "needs", "steps", "env", "matrix", "strategy", "job", "jobs", "runner"})
KNOWN_CONTEXTS = SHARED_CONTEXTS | {"github", "inputs"}


@dataclass(frozen=True)
class Event:
    """What an expression can know of one event.

    `github` and `payload` hold the fields the event fixes; any other is Unknown, or VARIABLE when `variables` is
    set (used for a pull request's concurrency group, whose text is what matters). The jobs and steps named in
    `tainted_needs` and `tainted_steps` ran differently on the two events, so what they produced may differ.
    """
    name: str
    github: Mapping[str, Value]
    payload: Mapping[str, Value]
    variables: bool = False
    tainted_needs: frozenset[str] = frozenset()
    tainted_steps: frozenset[str] = frozenset()


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
        if name == "github":
            return GITHUB
        # The whole of `needs` or `steps` holds what a tainted job or step produced, if there is one.
        return Unknown(tree, bool(event.tainted_needs) if name == "needs" else
                       bool(event.tainted_steps) if name == "steps" else False)
    if kind == "get":
        return _property(tree, evaluate(tree[1], event), evaluate(tree[2], event), event)
    if kind == "not":
        value = evaluate(tree[1], event)
        if _undecided(value):
            return Unknown(("not", _tree(value)), _is_event(value))
        return not _truthy(value)
    if kind in ("and", "or"):
        left = evaluate(tree[1], event)
        if not _undecided(left):
            return left if _truthy(left) == (kind == "or") else evaluate(tree[2], event)
        right = evaluate(tree[2], event)
        return Unknown((kind, _tree(left), _tree(right)), _is_event(left) or _is_event(right))
    if kind == "compare":
        left, right = evaluate(tree[2], event), evaluate(tree[3], event)
        if GITHUB in (left, right) or PAYLOAD in (left, right):
            raise ReadError("a comparison of a whole context is not evaluated")
        if _undecided(left) or _undecided(right) or _symbolic(left) or _symbolic(right):
            return Unknown(("compare", tree[1], _tree(left), _tree(right)),
                           _is_event(left) or _is_event(right) or _symbolic(left) or _symbolic(right))
        return _compare(tree[1], left, right)
    if kind == "call":
        return _call(tree, event)
    raise ReadError(f"cannot evaluate {tree!r}")


def _property(tree: tuple, base: Value, key: Value, event: Event) -> Value:
    if isinstance(base, Unknown) and base.tree in (("context", "needs"), ("context", "steps")) and isinstance(key, str):
        tainted = event.tainted_needs if base.tree == ("context", "needs") else event.tainted_steps
        return Unknown(("get", base.tree, _tree(key)), key in tainted)
    if base == VARIABLE or event.variables and (key == VARIABLE or _symbolic(key)):
        return VARIABLE
    if isinstance(base, Unknown) or _undecided(key) or _symbolic(key):
        return Unknown(("get", _tree(base), _tree(key)), _is_event(base) or _is_event(key))
    if base == GITHUB:
        if key == "event":
            return PAYLOAD
        if key in event.github:
            return event.github[key]
        if key in SHARED_GITHUB_FIELDS:
            return Unknown(tree, False)
        return VARIABLE if event.variables else Unknown(tree, True)
    if base == PAYLOAD:
        if key in event.payload:
            return event.payload[key]
        return VARIABLE if event.variables else Unknown(tree, True)
    if isinstance(base, dict):
        return base.get(key) if isinstance(key, str) else None
    if base is None or isinstance(base, (str, bool, int, float)):
        return None
    raise ReadError(f"cannot evaluate {tree!r}")


def _call(tree: tuple, event: Event) -> Value:
    name, args = tree[1], [evaluate(arg, event) for arg in tree[2]]
    if GITHUB in args or PAYLOAD in args:
        raise ReadError(f"function {name}() of a whole context is not evaluated")
    if name == "always" and not args:
        return True
    if name in STATUS_FUNCTIONS or name == "hashfiles" or any(isinstance(arg, Unknown) for arg in args):
        return Unknown(("call", name, tuple(_tree(arg) for arg in args)), any(_is_event(arg) for arg in args))
    if name == "format" and args and isinstance(args[0], str):
        def argument(match: re.Match[str]) -> str:
            if match.group(1) is None:
                return match.group(0)[0]
            if 1 + int(match.group(1)) >= len(args):
                raise ReadError(f"format() has no argument {match.group(1)}")
            return _string(args[1 + int(match.group(1))])
        return re.sub(r"\{\{|\}\}|\{(\d+)\}", argument, args[0])
    if any(arg == VARIABLE or _symbolic(arg) for arg in args):
        return Unknown(("call", name, tuple(_tree(arg) for arg in args)), True)
    if name in ("contains", "startswith", "endswith") and len(args) == 2:
        haystack, needle = args
        if name == "contains" and isinstance(haystack, list):
            return any(_compare("==", item, needle) for item in haystack)
        haystack, needle = _ascii_case(_string(haystack)), _ascii_case(_string(needle))
        return {"contains": needle in haystack, "startswith": haystack.startswith(needle),
                "endswith": haystack.endswith(needle)}[name]
    raise ReadError(f"function {name}() with these arguments is not evaluated")


def _undecided(value: Value) -> bool:
    """Whether the event leaves the value's truth open (a run id is never empty)."""
    return isinstance(value, Unknown) or value == VARIABLE or (
        isinstance(value, str) and PULL_REQUEST_TEXT in value and value.replace(PULL_REQUEST_TEXT, "") == "")


def _symbolic(value: Value) -> bool:
    """Text holding the run id or a pull request's own value, which only the run knows."""
    return isinstance(value, str) and (PULL_REQUEST_TEXT in value or RUN_ID in value)


def _tree(value: Value) -> tuple:
    if isinstance(value, Unknown):
        return value.tree
    if value in MARKERS:
        return ("context-object", value[0])
    return ("literal", json.dumps(value, sort_keys=True))


def _is_event(value: Value) -> bool:
    return isinstance(value, Unknown) and value.event or value in MARKERS or _symbolic(value)


def _truthy(value: Value) -> bool:
    if isinstance(value, float) and math.isnan(value):
        return False
    return value not in (False, 0, "", None)


def _ascii_case(text: str) -> str:
    """Lower case for ASCII text, where the runner's ordinal comparison ignoring case agrees; other text is refused."""
    if not text.isascii():
        raise ReadError(f"a comparison of non-ASCII text ({text!r}) is not evaluated")
    return text.lower()


def _compare(op: str, left: Value, right: Value) -> bool:
    """Values of one type compared as the runner does; the runner's conversions across types are not modelled, and
    such a comparison is refused."""
    if isinstance(left, str) and isinstance(right, str):
        a, b = _ascii_case(left), _ascii_case(right)
    elif left is None and right is None:
        return op in ("==", "<=", ">=")
    elif isinstance(left, bool) and isinstance(right, bool) or (
            type(left) in (int, float) and type(right) in (int, float)):
        a, b = left, right
    else:
        raise ReadError(f"a comparison of {left!r} and {right!r} (different types) is not evaluated")
    if op == "==":
        return a == b
    if op == "!=":
        return not a == b
    return {"<": a < b, "<=": a <= b, ">": a > b, ">=": a >= b}[op]


def _string(value: Value) -> str:
    if value == VARIABLE:
        return PULL_REQUEST_TEXT
    if value in (GITHUB, PAYLOAD):
        raise ReadError("a whole context as text is not evaluated")
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
    if _undecided(value):
        return None
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
        return tree[1] != VARIABLE[0] or None
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
    """Every string in a workflow value, recursively (the reader bounds the depth)."""
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
    return any(_reads_event_tree(parse_expression(source))
               for source in re.findall(r"\$\{\{(.*?)\}\}", text, flags=re.DOTALL))


def _reads_event_tree(tree: tuple) -> bool:
    """An event field of `github` by name, any field of it by a computed name, or the whole context."""
    nodes = list(_nodes(tree))
    reads = [node for node in nodes if node[0] == "get" and node[1] == ("context", "github")]
    whole = sum(1 for node in nodes if node == ("context", "github")) > len(reads)
    return whole or any(node[2][0] != "literal" or node[2][1] in EVENT_FIELDS for node in reads)


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
    """Every node of a syntax tree, without recursion."""
    stack = [tree]
    while stack:
        node = stack.pop()
        yield node
        stack.extend(_children(node))


def _depth(tree: tuple) -> int:
    deepest, stack = 0, [(tree, 1)]
    while stack:
        node, depth = stack.pop()
        deepest = max(deepest, depth)
        stack.extend((child, depth + 1) for child in _children(node))
    return deepest


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
    """A filter pattern as a regular expression; `**/` and `/**` also match no directory at all."""
    out = ""
    i = 0
    while i < len(pattern):
        if pattern.startswith("**/", i):
            out += "(?:.*/)?"
            i += 3
        elif pattern.startswith("/**", i) and i + 3 == len(pattern):
            out += "(?:/.*)?"
            i += 3
        elif pattern.startswith("**", i):
            out += ".*"
            i += 2
        elif pattern[i] == "*":
            out += "[^/]*"
            i += 1
        elif pattern[i] in "?+[]\\":
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


def compare_condition(tree: tuple, push: Event = PUSH_TO_MAIN, pull: Event = PULL_REQUEST_TO_MAIN) -> Verdict:
    """Whether a condition can hold on the push, and if so whether the pull request's is no narrower."""
    on_push, on_pull = evaluate(tree, push), evaluate(tree, pull)
    if truth(on_push) is False:
        return Verdict(False, True)
    if truth(on_pull) is True:
        return Verdict(True, True)
    return Verdict(True, _same_unknown(on_push, on_pull))


def same_condition(tree: tuple, push: Event, pull: Event) -> bool:
    """Whether a condition holds alike on both events: decided alike, or left to the same run-time values."""
    on_push, on_pull = evaluate(tree, push), evaluate(tree, pull)
    decided = truth(on_push), truth(on_pull)
    if None not in decided:
        return decided[0] == decided[1]
    return _same_unknown(on_push, on_pull)


def _same_unknown(on_push: Value, on_pull: Value) -> bool:
    return (isinstance(on_push, Unknown) and isinstance(on_pull, Unknown) and on_push.tree == on_pull.tree
            and not on_push.event and not on_pull.event)


def can_run(jobs: Mapping[str, dict], event: Event) -> dict[str, bool]:
    """Which jobs can run for the event: their `if` can hold, and what they need can run unless the `if` calls
    always(), failure() or cancelled(), which can hold whatever ran before."""
    result: dict[str, bool] = {}
    for name in _job_order(jobs):
        tree = condition(jobs[name].get("if"))
        possible = truth(evaluate(tree, event)) is not False
        if possible and not _runs_without_needs(tree):
            possible = all(result[need] for need in needs(jobs[name]))
        result[name] = possible
    return result


def _job_order(jobs: Mapping[str, dict]) -> list[str]:
    """The jobs with each after what it needs; `ReadError` for a missing job or a cycle."""
    order: list[str] = []
    placed: set[str] = set()
    for name in jobs:
        for need in needs(jobs[name]):
            if need not in jobs:
                raise ReadError(f"job {name}: `needs` names a missing job {need!r}")
    pending = list(jobs)
    while pending:
        ready = [name for name in pending if all(need in placed for need in needs(jobs[name]))]
        if not ready:
            raise ReadError(f"cycle in `needs` among {', '.join(pending)}")
        order += ready
        placed |= set(ready)
        pending = [name for name in pending if name not in placed]
    return order


def needs(job: Mapping[str, object]) -> list[str]:
    value = job.get("needs") or []
    return [value] if isinstance(value, str) else list(value)


def _runs_without_needs(tree: tuple) -> bool:
    return any(node[0] == "call" and node[1] in ("always", "failure", "cancelled") for node in _nodes(tree))


def _scoped(event: Event, **changes: object) -> Event:
    return replace(event, **changes)


@dataclass(frozen=True)
class Uniformity:
    """Which jobs run alike on both events, so that what they produce (`needs.<job>.*`) is the same."""
    jobs: Mapping[str, bool]

    def needs_scope(self, job: Mapping[str, object]) -> frozenset[str]:
        return frozenset(need for need in needs(job) if not self.jobs[need])


def uniformity(jobs: Mapping[str, dict]) -> Uniformity:
    result: dict[str, bool] = {}
    for name in _job_order(jobs):
        job = jobs[name]
        tainted = frozenset(need for need in needs(job) if not result[need])
        push = _scoped(PUSH_TO_MAIN, tainted_needs=tainted)
        pull = _scoped(PULL_REQUEST_TO_MAIN, tainted_needs=tainted)
        alike = ("uses" not in job and same_condition(condition(job.get("if")), push, pull)
                 and not any(_reads_run_dependent(text, push) for key, value in job.items()
                             if key not in ("if", "concurrency", "steps") for text in expressions(value)))
        result[name] = alike and all(alike_steps(_steps(name, job), push, pull))
    return Uniformity(result)


def _steps(where: str, job: Mapping[str, object]) -> list[dict]:
    steps = job.get("steps") or []
    if not isinstance(steps, list) or not all(isinstance(step, dict) for step in steps):
        raise ReadError(f"{where}: `steps` is not a list of steps")
    return steps


def alike_steps(steps: list[dict], push: Event, pull: Event) -> list[bool]:
    """For each step, whether it runs alike on both events, given the steps before it."""
    result, tainted = [], set()
    for step in steps:
        scoped_push = _scoped(push, tainted_steps=frozenset(tainted))
        alike = (same_condition(condition(step.get("if")), scoped_push, _scoped(pull, tainted_steps=frozenset(tainted)))
                 and not _local_action(step)
                 and not any(_reads_run_dependent(text, scoped_push) for key, value in step.items() if key != "if"
                             for text in expressions(value)))
        result.append(alike)
        if not alike and isinstance(step.get("id"), str):
            tainted.add(step["id"])
    return result


def _reads_run_dependent(text: str, scope: Event) -> bool:
    """Whether the text reads the event, or what a job or step that ran differently on the two events produced."""
    return reads_event(text) or reads_tainted(text, scope)


def reads_tainted(text: str, scope: Event) -> bool:
    """Whether an expression in the text reads `needs` or `steps` of a tainted job or step, by name, by a computed
    name or as a whole."""
    for source in re.findall(r"\$\{\{(.*?)\}\}", text, flags=re.DOTALL):
        nodes = list(_nodes(parse_expression(source)))
        for context, tainted in (("needs", scope.tainted_needs), ("steps", scope.tainted_steps)):
            if not tainted:
                continue
            reads = [node for node in nodes if node[0] == "get" and node[1] == ("context", context)]
            if sum(1 for node in nodes if node == ("context", context)) > len(reads) or any(
                    node[2][0] != "literal" or node[2][1] in tainted for node in reads):
                return True
    return False


def _local_action(step: Mapping[str, object]) -> bool:
    uses = step.get("uses")
    return isinstance(uses, str) and uses.startswith("./")


def workflow_findings(path: str, workflow: object, allowed: Mapping[tuple[str, str], str]) -> tuple[list[str], set[str]]:
    """(findings, ids of the allowlisted jobs that this workflow runs on a push to main) for one workflow."""
    if not isinstance(workflow, dict):
        raise ReadError("the workflow is not a mapping")
    on = triggers(workflow)
    if not pushes_to_main(on):
        return [], set()
    jobs = _jobs(workflow)
    findings = [f"{path}: {problem}" for problem in pull_request_problems(on)]
    findings += [f"{path}: workflow `{key}` reads the event" for key in ("env", "defaults")
                 if any(reads_event(text) for text in expressions(workflow.get(key)))]
    on_push, on_pull = can_run(jobs, PUSH_TO_MAIN), can_run(jobs, PULL_REQUEST_TO_MAIN)
    alike = uniformity(jobs)
    used: set[str] = set()
    for name, job in jobs.items():
        where = f"{path}: job {name}"
        tainted = alike.needs_scope(job)
        push = _scoped(PUSH_TO_MAIN, tainted_needs=tainted)
        pull = _scoped(PULL_REQUEST_TO_MAIN, tainted_needs=tainted)
        findings += environment_findings(where, _steps(where, job), push, pull)
        if not on_push[name]:
            continue
        problems = job_problems(where, job, on_pull[name], push, pull)
        if (path, name) in allowed:
            used.add(name)
            if not problems:
                findings.append(f"{where}: runs on pull requests as on a push; remove it from SIDE_EFFECT_JOBS")
            continue
        findings += [f"{where}: needs {need}, which runs on a push only (SIDE_EFFECT_JOBS)"
                     for need in needs(job) if (path, need) in allowed]
        findings += problems
    return findings, used


def _jobs(workflow: Mapping[str, object]) -> dict[str, dict]:
    jobs = workflow.get("jobs")
    if not isinstance(jobs, dict) or not all(isinstance(job, dict) for job in jobs.values()):
        raise ReadError("`jobs` is not a mapping of jobs")
    return jobs


def job_problems(where: str, job: Mapping[str, object], on_pull: bool, push: Event, pull: Event) -> list[str]:
    """Why a job that can run on a push to main may not run, or may run differently, on a pull request to main."""
    problems = []
    if not on_pull:
        problems.append(f"{where}: runs on a push to main but cannot run on a pull request to main")
    elif not compare_condition(condition(job.get("if")), push, pull).ok:
        problems.append(f"{where}: its `if` may hold on a push to main where it does not on a pull request")
    if "uses" in job:
        problems.append(f"{where}: calls a reusable workflow, which this guard cannot read")
    problems += [f"{where}: `{key}` reads the event, or what ran differently on the two events"
                 for key, value in job.items() if key not in ("if", "concurrency", "steps")
                 and any(_reads_run_dependent(text, push) for text in expressions(value))]
    return problems + step_findings(where, _steps(where, job), push, pull)


def step_findings(where: str, steps: list[dict], push: Event = PUSH_TO_MAIN,
                  pull: Event = PULL_REQUEST_TO_MAIN) -> list[str]:
    findings = []
    alike = alike_steps(steps, push, pull)
    tainted: set[str] = set()
    for number, step in enumerate(steps, start=1):
        scoped_push = _scoped(push, tainted_steps=frozenset(tainted))
        scoped_pull = _scoped(pull, tainted_steps=frozenset(tainted))
        if not alike[number - 1] and isinstance(step.get("id"), str):
            tainted.add(step["id"])
        label = _step_label(where, number, step)
        verdict = compare_condition(condition(step.get("if")), scoped_push, scoped_pull)
        if not verdict.on_push:
            continue
        if not verdict.ok:
            findings.append(f"{label}: its `if` may hold on a push to main where it does not on a pull request")
        if _local_action(step):
            findings.append(f"{label}: uses a local action, which this guard cannot read")
        findings += [f"{label}: `{key}` reads the event, or what ran differently on the two events"
                     for key, value in step.items()
                     if key != "if" and any(_reads_run_dependent(text, scoped_push) for text in expressions(value))]
    return findings


def environment_findings(where: str, steps: list[dict], push: Event, pull: Event) -> list[str]:
    """A step that runs differently on the two events must not set the environment of the steps after it, which a
    script reads unseen."""
    return [f"{_step_label(where, number, step)}: runs differently on the two events and writes $GITHUB_ENV or "
            "$GITHUB_PATH for the steps after it"
            for number, (step, alike) in enumerate(zip(steps, alike_steps(steps, push, pull)), start=1)
            if not alike and number < len(steps) and ENVIRONMENT_FILES.search(json.dumps(step))]


def _step_label(where: str, number: int, step: Mapping[str, object]) -> str:
    return f"{where}, step {number} ({step.get('name') or step.get('uses') or step.get('id') or 'run'})"


# --- Concurrency ---------------------------------------------------------------------------------------------------

@dataclass(frozen=True)
class Group:
    """A concurrency group of a workflow or a job: where it is declared, and its value."""
    where: str
    value: object
    cancel: object


def groups(where: str, concurrency: object) -> list[Group]:
    if concurrency is None:
        return []
    if isinstance(concurrency, str):
        return [Group(where, concurrency, False)]
    if isinstance(concurrency, dict) and "group" in concurrency:
        return [Group(where, concurrency["group"], concurrency.get("cancel-in-progress", False))]
    raise ReadError(f"{where}: `concurrency` has no group")


def push_groups(path: str, workflow: Mapping[str, object]) -> list[tuple[Group, bool]]:
    """The groups a push run on main takes, the workflow's and those of the jobs that can run on the push, each
    with whether it is a job's with a matrix."""
    jobs = _jobs(workflow)
    on_push = can_run(jobs, PUSH_TO_MAIN)
    found = [(group, False) for group in groups(path, workflow.get("concurrency"))]
    for name, job in jobs.items():
        strategy = job.get("strategy")
        matrix = isinstance(strategy, dict) and "matrix" in strategy
        if on_push[name]:
            found += [(group, matrix) for group in groups(f"{path}: job {name}", job.get("concurrency"))]
    return found


def pull_request_groups(path: str, workflow: Mapping[str, object]) -> list[tuple[Group, Event]]:
    """The groups a pull request's run can take, with the event to read them under, for every pull request trigger."""
    on = triggers(workflow)
    jobs = _jobs(workflow)
    found = []
    for name in ("pull_request", "pull_request_target"):
        if name in on:
            event = Event(name, {"event_name": name, "run_id": RUN_ID}, {}, variables=True)
            reachable = can_run(jobs, Event(name, {"event_name": name, "run_id": RUN_ID}, {}))
            declared = groups(path, workflow.get("concurrency")) + [
                group for job_name, job in jobs.items() if reachable[job_name]
                for group in groups(f"{path}: job {job_name}", job.get("concurrency"))]
            found += [(group, event) for group in declared]
    return found


def push_group_problems(group: Group, matrix: bool) -> list[str]:
    """Why a push run's group can cancel it or let a newer run replace it while it waits."""
    problems = []
    cancels = template(group.cancel, PUSH_TO_MAIN)
    if _undecided(cancels) or _truthy(cancels):
        problems.append(f"{group.where}: `cancel-in-progress` can be true on a push to main")
    named = template(group.value, PUSH_TO_MAIN)
    if not isinstance(named, str) or RUN_ID not in named:
        problems.append(f"{group.where}: on a push to main the concurrency group is not unique to the run (put "
                        "github.run_id in it), so a newer run replaces a waiting one")
    elif matrix:
        problems.append(f"{group.where}: every job of its matrix takes the same concurrency group on a push to main")
    return problems


def _pattern(text: str) -> tuple[str, str]:
    """The text a group surely starts and ends with, before its first and after its last run-time part."""
    pieces = re.split(re.escape(RUN_ID) + "|" + re.escape(PULL_REQUEST_TEXT), text)
    return pieces[0], pieces[-1] if len(pieces) > 1 else pieces[0]


def _group_case(text: str) -> str:
    """Group names compare ignoring case; only ASCII names are read, where that is plain lower case."""
    if not text.isascii():
        raise ReadError(f"concurrency group {text!r}: non-ASCII names are not read")
    return text.lower()


def may_collide(push: str, pull: Value, declared: object) -> bool:
    """Whether a pull request's group (`pull`, as evaluated from `declared`) can be the text of the push run's group,
    ignoring case."""
    push = _group_case(push)
    if isinstance(pull, str):
        pull = _group_case(pull)
    if isinstance(declared, str):
        declared = _group_case(declared)
    if isinstance(pull, str) and PULL_REQUEST_TEXT not in pull:
        if RUN_ID in pull:
            # The same run id cannot belong to both runs; the groups differ unless the texts around it can match.
            return pull != push and _ends_overlap(_pattern(push), _pattern(pull))
        return re.fullmatch("[0-9]+".join(map(re.escape, push.split(RUN_ID))), pull) is not None
    if isinstance(pull, str):
        return _ends_overlap(_pattern(push), _pattern(pull))
    if isinstance(declared, str) and "${{" in declared:
        parts = re.split(r"\$\{\{.*?\}\}", declared, flags=re.DOTALL)
        return _ends_overlap(_pattern(push), (parts[0], parts[-1]))
    return True


def _ends_overlap(push: tuple[str, str], pull: tuple[str, str]) -> bool:
    head = min(len(push[0]), len(pull[0]))
    tail = min(len(push[1]), len(pull[1]))
    return push[0][:head] == pull[0][:head] and (tail == 0 or push[1][-tail:] == pull[1][-tail:])


def concurrency_findings(workflows: Sequence[tuple[str, dict]]) -> list[str]:
    """Push runs on main are never cancelled, never share a group within the run, and never share one with a pull
    request's run (groups are shared across the repository's workflows)."""
    findings = []
    pushing = [(path, workflow) for path, workflow in workflows if pushes_to_main(triggers(workflow))]
    pulls = [entry for path, workflow in workflows for entry in pull_request_groups(path, workflow)]
    for path, workflow in pushing:
        texts: dict[str, str] = {}
        for group, matrix in push_groups(path, workflow):
            problems = push_group_problems(group, matrix)
            findings += problems
            if problems:
                continue
            text = template(group.value, PUSH_TO_MAIN)
            if _group_case(text) in texts:
                findings.append(f"{group.where}: shares its concurrency group with {texts[_group_case(text)]} in the "
                                "same run")
            texts[_group_case(text)] = group.where
            for pull_group, event in pulls:
                if may_collide(text, template(pull_group.value, event), pull_group.value):
                    findings.append(f"{group.where}: a {event.name} run's group ({pull_group.where}) may equal its "
                                    "group on a push to main; give them different fixed text")
    return findings


def findings(files: Iterable[tuple[str, str]], allowed: Mapping[tuple[str, str], str] = SIDE_EFFECT_JOBS) -> list[str]:
    """Every violation over the (path, text) workflows, in input order, then the allowlist's own problems."""
    rows: list[str] = []
    used: set[tuple[str, str]] = set()
    readable: list[tuple[str, dict]] = []
    for path, text in files:
        try:
            workflow = read_yaml(text)
            found, jobs = workflow_findings(path, workflow, allowed)
        except ReadError as error:
            rows.append(f"{path}: cannot be read by this guard: {error}")
            continue
        rows += found
        used |= {(path, job) for job in jobs}
        readable.append((path, workflow))
    try:
        rows += concurrency_findings(readable)
    except ReadError as error:
        rows.append(f"concurrency groups cannot be read by this guard: {error}")
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
