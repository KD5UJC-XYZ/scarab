#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""The definitions of a Scala source: each with its key, its lines and a digest of its code.

`check_correspondence.py` reads the mirrored areas with this. The reading is lexical, over the code that
`scala_comments.split` gives (comments and the text of literals blanked, every offset and line break kept), so it
needs no build. A line that holds a definition's header starts, after its annotations and modifiers, with one of
`def`, `val`, `var`, `given`, `class`, `trait`, `object`, `enum` or `type` (`case class` and `case object`
included; an enum's `case` is not a definition of its own). The header and every line after it while a bracket it
opened is open, or while lines are blank or indented further, are its extent; the annotations on the lines just
before it are part of it too.

A **definition** is a header at the top level of a file, or a member of a class, trait, object or enum body (or of
an `extension` block in one, or at the top level), that is not `private` or `private[this]` in its owner. A
private member and anything defined inside a `def`, `val`, `var`, `given` or `type` (a local definition, the body
of an anonymous class) belong to the definition around them. Overloads share one key and one digest. Its key is the
keyword and the qualified name, with `xyz.kd5ujc.scarab.` dropped: `.` after a package or an object, `#` after a
class, trait or enum (`def network.selection.Churn.Step#reached`, `object storage.Codec`). An anonymous given is
named after its type, as the compiler names it (`given ledger.Coin.given_Show_Coin`).

Its **digest** is of its own lines: its extent without the extents of the definitions it holds, and with the header
of the `extension` block it is in. Comments are removed, a line's trailing white space and the empty lines are
dropped, and the indentation under the header is kept; the lines inside a multi-line literal are kept as written.
A comment or blank-line edit leaves it as it was; a change to its code, its annotations or a private helper of it
changes it; a change to a member that has a key of its own does not.

Every other line of a source must be a `package`, `import` or `export` clause or an `end` marker at the top level;
a line that no definition holds is reported as **unread**, so that a construct this reader does not know fails
instead of passing unseen.

Pure standard library.
"""
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
import bisect
import hashlib
import re
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
import scala_comments  # noqa: E402

PREFIX = 'xyz.kd5ujc.scarab.'
KEYWORDS = ('def', 'val', 'var', 'given', 'class', 'trait', 'object', 'enum', 'type')
TEMPLATES = frozenset(('class', 'trait', 'object', 'enum'))
# the owners whose members are written with `#`: their members belong to an instance
INSTANCE = frozenset(('class', 'trait', 'enum'))
MODIFIER = re.compile(r'(?:(?:private|protected)(?:\[[\w.]*\])?|final|sealed|abstract|override|implicit|lazy|inline'
                      r'|transparent|open|opaque|infix|erased|case)\s+')
KEYWORD = re.compile(r'(' + '|'.join(KEYWORDS) + r')\b')
PRIVATE = re.compile(r'\bprivate(?:\[this\])?\s')
OPERATOR = r'[!#%&*+\-/:<=>?@\\^|~]+'
NAME = re.compile(r'\s*(`[^`]+`|[A-Za-z_$][\w$]*(?:_' + OPERATOR + r')?|' + OPERATOR + r')')
TOP_LEVEL = re.compile(r'(?:package|import|export|end)\b')
PACKAGE = re.compile(r'package\s+[\w.]+')
DIGEST_LENGTH = 16


@dataclass(frozen=True)
class Definition:
    """`key` is `kind path`; `lines` are its extents as 1-based inclusive (first, last) pairs, one per overload."""
    key: str
    kind: str
    path: str
    lines: tuple[tuple[int, int], ...]
    digest: str

    @property
    def line(self) -> int:
        return self.lines[0][0]


@dataclass(frozen=True)
class Read:
    """The definitions of a source in order, and the 1-based lines that no definition holds."""
    definitions: tuple[Definition, ...]
    unread: tuple[int, ...]


@dataclass
class Node:
    kind: str
    name: str
    first: int
    header: int
    end: int
    indent: int
    private: bool
    parent: 'Node | None'
    keyed: bool = False
    transparent: bool = False


def closing(text: str, i: int) -> int:
    """The index of the bracket that closes the one at `i`, or -1 when it is not closed in `text`."""
    depth = 0
    for j in range(i, len(text)):
        depth += {'(': 1, '[': 1, '{': 1, ')': -1, ']': -1, '}': -1}.get(text[j], 0)
        if depth == 0:
            return j
    return -1


def without_annotations(text: str) -> str | None:
    """`text` without the annotations it starts with, or None when one goes on past the line."""
    while text.startswith('@'):
        match = re.match(r'@[\w.]+', text)
        if match is None:
            return text
        i = match.end()
        while i < len(text) and text[i] in '([':
            j = closing(text, i)
            if j < 0:
                return None
            i = j + 1
        text = text[i:].lstrip()
    return text


def header(text: str) -> tuple[str, bool, str] | None:
    """For a line of code (stripped): (keyword, private, the text after the keyword) when it holds a definition's
    header."""
    rest = without_annotations(text)
    if rest is None:
        return None
    modifiers = []
    while (match := MODIFIER.match(rest)) is not None:
        modifiers.append(match.group())
        rest = rest[match.end():]
    keyword = KEYWORD.match(rest)
    if keyword is None:
        return None
    kind = keyword.group(1)
    if 'case ' in modifiers and kind not in ('class', 'object'):
        return None
    return kind, any(PRIVATE.match(m) for m in modifiers), rest[keyword.end():]


def top_level(text: str, stop: str) -> int:
    """The index in `text` of the first `stop` character outside brackets, or -1."""
    depth = 0
    for i, c in enumerate(text):
        if depth == 0 and c in stop:
            return i
        depth += {'(': 1, '[': 1, '{': 1, ')': -1, ']': -1, '}': -1}.get(c, 0)
    return -1


def given_name(rest: str) -> str:
    """The name of a given from the text after `given`: its own, or one made from its type as the compiler makes it
    (`given_Show_Coin`)."""
    text = re.split(r'\s+with\b', rest, maxsplit=1)[0]
    equals = top_level(text, '=')
    text = (text if equals < 0 else text[:equals]).strip()
    colon = top_level(text, ':')
    if colon >= 0:
        before = text[:colon].strip()
        named = re.match(r'(`[^`]+`|[A-Za-z_$][\w$]*)', before)
        if named is not None and not before.startswith(('[', '(')):
            return named.group(1)
        text = text[colon + 1:]
    words = re.findall(r'[A-Za-z_$][\w$]*', text)
    return 'given_' + '_'.join(words) if words else 'given'


def term_name(kind: str, rest: str) -> str:
    if kind == 'given':
        return given_name(rest)
    stripped = rest.lstrip()
    if kind in ('val', 'var') and stripped.startswith('('):
        end = closing(stripped, 0)
        return re.sub(r'\s+', '', stripped[:end + 1] if end >= 0 else stripped)
    match = NAME.match(rest)
    return match.group(1) if match else '?'


def literal_lines(source: str, literals: tuple[tuple[int, int], ...]) -> set[int]:
    """The 0-based lines that start inside a literal: the lines after the first of a multi-line literal."""
    starts = [0] + [m.end() for m in re.finditer('\n', source)]
    inside: set[int] = set()
    for start, end in literals:
        first = bisect.bisect_right(starts, start)
        last = bisect.bisect_right(starts, max(start, end - 1)) - 1
        inside.update(range(first, last + 1))
    return inside


def inline_definition(code: str) -> bool:
    """Whether a line of code holds a definition keyword after a `{` or `;` outside parentheses and brackets."""
    depth, opened = 0, False
    for match in re.finditer(r'[(\[\])]|[{;]|\b(?:' + '|'.join(KEYWORDS) + r')\b', code):
        token = match.group()
        if token in '([':
            depth += 1
        elif token in ')]':
            depth -= 1
        elif token in '{;':
            opened = opened or depth == 0
        elif opened and depth == 0:
            return True
    return False


def member_after_semicolon(code: str, depth: int, level: int) -> bool:
    """Whether a line of code, starting at bracket depth `depth`, holds a definition keyword after a `;`, both at
    depth `level` or less: back at the level of a member's header."""
    armed = False
    for match in re.finditer(r'[(\[{]|[)\]}]|;|\b(?:' + '|'.join(KEYWORDS) + r')\b', code):
        token = match.group()
        if token in '([{':
            depth += 1
        elif token in ')]}':
            depth -= 1
        elif token == ';':
            armed = armed or depth <= level
        elif armed and depth <= level:
            return True
    return False


def normalized_line(text: str, literal: bytearray, start: int) -> str:
    """A line of code without comments: the text of literals as written, its indentation kept, and elsewhere each
    run of white space one space, with none at the line's end."""
    out: list[str] = []
    leading, space = True, False
    for k, c in enumerate(text):
        if literal[start + k]:
            if space:
                out.append(' ')
            out.append(c)
            leading, space = False, False
        elif c in ' \t':
            if leading:
                out.append(c)
            else:
                space = True
        else:
            if space:
                out.append(' ')
            out.append(c)
            leading, space = False, False
    return ''.join(out)


def without_comments(source: str, comments: tuple[scala_comments.Comment, ...]) -> str:
    pieces, at = [], 0
    for comment in comments:
        pieces.append(source[at:comment.start])
        pieces.append(re.sub(r'[^\n]', ' ', source[comment.start:comment.end]))
        at = comment.end
    pieces.append(source[at:])
    return ''.join(pieces)


def read(source: str) -> Read:
    split = scala_comments.split(source)
    code_lines = split.code.split('\n')
    text_lines = without_comments(source, split.comments).split('\n')
    in_literal = literal_lines(source, split.literals)
    literal = bytearray(len(source) + 1)
    for start, end in split.literals:
        literal[start:end] = b'\x01' * (end - start)
    starts = [0] + [m.end() for m in re.finditer('\n', source)]
    n = len(code_lines)
    blank = [not code_lines[i].strip() or i in in_literal for i in range(n)]
    indent = [len(line) - len(line.lstrip()) for line in code_lines]
    depth, depths = 0, []
    for line in code_lines:
        depths.append(depth)
        depth += sum({'(': 1, '[': 1, '{': 1, ')': -1, ']': -1, '}': -1}.get(c, 0) for c in line)

    def extent_end(at: int) -> int:
        """The 0-based line after the extent of the header at `at`."""
        for j in range(at + 1, n):
            if not blank[j] and depths[j] <= depths[at] and indent[j] <= indent[at]:
                end = j
                break
        else:
            end = n
        while end > at + 1 and blank[end - 1] and end - 1 not in in_literal:
            end -= 1
        return end

    def annotations_from(at: int) -> int:
        """The first line of the annotations written on the lines just before the header at `at`."""
        first = at
        j = at - 1
        while j >= 0 and not blank[j] and indent[j] >= indent[at]:
            if code_lines[j].strip().startswith('@') and indent[j] == indent[at]:
                first = j
            elif not (depths[j + 1] > depths[at] or indent[j] > indent[at]):
                break
            j -= 1
        return first

    packages = [m.group(1) for m in re.finditer(r'^package\s+([\w.]+)\s*$', split.code, re.MULTILINE)]
    package = '.'.join(packages)
    package = package[len(PREFIX):] if package.startswith(PREFIX) else package.removeprefix(PREFIX[:-1])

    nodes: list[Node] = []
    stack: list[Node] = []
    for i in range(n):
        if blank[i]:
            continue
        stripped = code_lines[i].strip()
        found = header(stripped)
        extension = found is None and re.match(r'extension\b', stripped) is not None
        if found is None and not extension:
            continue
        while stack and stack[-1].end <= i:
            stack.pop()
        parent = stack[-1] if stack else None
        node = Node(kind='extension', name='', first=i, header=i, end=extent_end(i), indent=indent[i],
                    private=False, parent=parent, transparent=True)
        if found is not None:
            kind, private, rest = found
            node = Node(kind=kind, name=term_name(kind, rest), first=annotations_from(i), header=i,
                        end=extent_end(i), indent=indent[i], private=private, parent=parent)
        # an extension written on one line with its method: `extension (x: A) def f = ...`
        if extension and (one := re.search(r'\)\s*(def\s.*)$', stripped)) is not None:
            if (inline := header(one.group(1))) is not None:
                node.transparent = False
                node.kind, node.private, rest = inline
                node.name = term_name(node.kind, rest)
        nodes.append(node)
        stack.append(node)

    def owner(node: Node) -> Node | None:
        """The nearest enclosing node that is not an extension block."""
        up = node.parent
        while up is not None and up.transparent:
            up = up.parent
        return up

    def member_of(node: Node) -> bool:
        """Whether `node` is at the top level, or directly in the body of a keyed class, trait, object or enum
        (an extension block in it included)."""
        holder = owner(node)
        return holder is None or (holder.keyed and holder.kind in TEMPLATES)

    for node in nodes:
        # an extension block is read (holds its own lines) where its methods would be members
        node.keyed = not node.transparent and member_of(node) and (owner(node) is None or not node.private)
        if node.transparent:
            node.private = not member_of(node)
    # an extension block with no keyed method (only private ones) leaves its lines to its owner, so that no line
    # is held by a block whose text no digest reads
    with_methods = {id(node.parent) for node in nodes if node.keyed and node.parent is not None
                    and node.parent.transparent}
    for node in nodes:
        if node.transparent and id(node) not in with_methods:
            node.private = True

    def hidden_definition(scope: Node, i: int) -> bool:
        """Whether line `i`, inside `scope`, defines something where a member would go that has no header line of
        its own: after a `{` or `;` on a line of a class, object, trait, enum or extension body whose members are
        read (`object A { def f = 1 }`), or after a `;` that is back at the level of a member's header
        (`val a = 1; def f = 2`, or `}; def g = 2` after a body in braces, or `\"\"\"; def g` after a literal)."""
        if scope.kind in TEMPLATES or scope.transparent:
            members_read = scope.keyed or (scope.transparent and member_of(scope))
            return members_read and not blank[i] and inline_definition(code_lines[i])
        if not member_of(scope):
            return False
        level = depths[scope.header]
        if i not in in_literal and i != scope.header and indent[i] > scope.indent:
            return False
        return member_after_semicolon(code_lines[i], depths[i], level)

    def path(node: Node) -> str:
        chain = []
        at: Node | None = node
        while at is not None:
            chain.append(at)
            at = owner(at)
        out = package
        for at in reversed(chain):
            holder = owner(at)
            separator = '#' if holder is not None and holder.kind in INSTANCE else '.'
            out = at.name if not out else out + separator + at.name
        return out

    # own lines: each line goes to the innermost keyed node or extension block around it
    holder_of: list[Node | None] = [None] * n
    for node in nodes:
        if node.keyed or (node.transparent and not node.private):
            for j in range(node.first, node.end):
                holder_of[j] = node

    # the innermost header around each line, keyed or not: the scope a definition written on it would belong to
    scope_of: list[Node | None] = [None] * n
    for node in nodes:
        for j in range(node.first, node.end):
            scope_of[j] = node

    unread = []
    clause_end = 0
    for i in range(n):
        if blank[i] and i not in in_literal:
            continue
        scope = scope_of[i]
        if scope is not None:
            if hidden_definition(scope, i):
                unread.append(i + 1)
            continue
        if blank[i]:
            continue
        if i < clause_end:
            if ';' in code_lines[i]:
                unread.append(i + 1)
            continue
        stripped = code_lines[i].strip()
        if TOP_LEVEL.match(stripped) and ';' not in stripped and (
                not stripped.startswith('package') or PACKAGE.fullmatch(stripped)):
            # a clause over several lines (`import a.{\n  B,\n  C\n}`) is read whole
            clause_end = extent_end(i)
        else:
            unread.append(i + 1)

    def own_text(node: Node) -> list[str]:
        out = []
        base = node.indent
        blocks = []
        up = node.parent
        while up is not None and up.transparent:
            blocks.append(up)
            up = up.parent
        lines = [j for b in reversed(blocks) for j in range(b.first, b.end) if holder_of[j] is b]
        lines += [j for j in range(node.first, node.end) if holder_of[j] is node]
        for j in lines:
            text = text_lines[j]
            if j in in_literal:
                out.append(normalized_line(text, literal, starts[j]))
                continue
            text = normalized_line(text, literal, starts[j])
            if not text.strip():
                continue
            cut = min(base, len(text) - len(text.lstrip()))
            out.append(text[cut:])
        return out

    merged: dict[str, tuple[Node, list[tuple[int, int]], list[str]]] = {}
    for node in nodes:
        if not node.keyed:
            continue
        key = f'{node.kind} {path(node)}'
        span = (node.first + 1, node.end)
        if key in merged:
            first, spans, text = merged[key]
            spans.append(span)
            text.extend(own_text(node))
        else:
            merged[key] = (node, [span], own_text(node))
    definitions = tuple(
        Definition(key, node.kind, key.split(' ', 1)[1], tuple(spans),
                   hashlib.sha256('\n'.join(text).encode()).hexdigest()[:DIGEST_LENGTH])
        for key, (node, spans, text) in merged.items())
    return Read(definitions, tuple(unread))
