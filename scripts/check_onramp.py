#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Checks the agent on-ramp: glossary, question index, decisions log, playbooks, templates, start block.

Every cell that points at the code must resolve, so these tables cannot drift from the tree:

  docs/glossary.md          | Term | Meaning | Where defined |
  docs/questions.md         | Question | File and symbol | Decision | Open issue |       (30 to 50 rows)
  docs/decisions.md         | Decision | Where recorded | Invariant it fixes | Reopen condition |
                            and one row per `docs/adr/NNNN-*.md`: linked from "Where recorded", titled as the ADR's
                            first heading
  docs/playbooks/*.md       at most 60 lines; every repo path in a fenced command exists; the scenario after
                            `scripts/devnet.py --name <n>` is a registered sub-command; `README.md` indexes them all
  .github                   the default issue template has the audit shape; the pull request template asks for rows
  AGENTS.md                 a "**Five-minute start**" block of at most 12 lines that names the entry documents

A backticked span in a pointer cell is a path (`dir/file`, `File.scala`, optional `#anchor` for markdown), a symbol
(`Name`, `Type.member`) or a command. A bare file name must be unique among tracked files. A symbol must be defined
(`object`, `class`, `trait`, `enum`, `def`, `val`, `type`, `given`, an enum case, or a Python assignment) in some source
file; when the same cell cites a file, in one of those files, and every member of `Type.member` must occur there. Line
numbers are never cited. Markdown links in a cell must resolve, anchors included.

Run `python3 scripts/check_onramp.py` (the default checks everything). Pure standard library; no network.
"""
from __future__ import annotations

from collections.abc import Sequence
from dataclasses import dataclass
from pathlib import Path
import argparse
import posixpath
import re
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
import check_docs  # noqa: E402
from check_docs import Repo, code_spans, split_cells  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
SOURCE_SUFFIXES = (".scala", ".sbt", ".py", ".java")
QUESTIONS_MIN = 30
QUESTIONS_MAX = 50
PLAYBOOK_MAX_LINES = 60
START_BLOCK_MAX_LINES = 12
START_LABEL = "**Five-minute start**"
START_MENTIONS = ("docs/codemap.md", "scripts/status.py", "docs/roadmap.md", "docs/decisions.md", "docs/playbooks/",
                  "docs/playbooks/run-the-gates.md")
GLOSSARY_HEADER = ("Term", "Meaning", "Where defined")
QUESTIONS_HEADER = ("Question", "File and symbol", "Decision", "Open issue")
DECISIONS_HEADER = ("Decision", "Where recorded", "Invariant it fixes", "Reopen condition")
AUDIT_FIELDS = ("boundary", "finding", "evidence", "acceptance", "removal")
EMPTY = frozenset({"", "-", "—", "none"})

PATH_SPAN = re.compile(r"^(?:[A-Za-z0-9_.@+-][A-Za-z0-9_./@+-]*)(?:#[A-Za-z0-9_-]+)?$")
SYMBOL_SPAN = re.compile(r"^([A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)*)(?:\(\))?$")
LINE_NUMBER = re.compile(r"\.(?:scala|py|sbt|md|json|yml):\d+|#L\d+")
ISSUE_REF = re.compile(r"^#\d+(?:,\s*#\d+)*$")
ISSUE_LINK = re.compile(r"\[#(\d+)\]\(https://github\.com/KD5UJC-XYZ/scarab/issues/\1(?:#[^)]*)?\)")
MD_LINK = re.compile(r"\[[^\]]*\]\(([^)\s]+)\)")
ADR_FILE = re.compile(r"^docs/adr/(\d{4})-[a-z0-9-]+\.md$")
FENCE_OPEN = re.compile(r"^\s*(`{3,})")
SCENARIO = re.compile(r'sub\.add_parser\(\s*"([a-z0-9_-]+)"')


@dataclass(frozen=True)
class Problem:
    path: str
    line: int
    message: str

    def render(self) -> str:
        return f"{self.path}:{self.line}: {self.message}" if self.line else f"{self.path}: {self.message}"


class Index:
    """What the checks look up: files, basenames, and which source file defines which name."""

    def __init__(self, repo: Repo) -> None:
        self.repo = repo
        self.by_basename: dict[str, list[str]] = {}
        for name in sorted(repo.files):
            self.by_basename.setdefault(name.rsplit("/", 1)[-1], []).append(name)
        self.texts = {n: repo.read(n) for n in sorted(repo.files) if n.endswith(SOURCE_SUFFIXES)}
        self.tokens = {n: frozenset(check_docs.IDENT.findall(t)) for n, t in self.texts.items()}
        self._defs: dict[str, list[str]] = {}
        self.anchors: dict[str, frozenset[str]] = {}

    def defining(self, name: str) -> list[str]:
        """Source files that define `name`."""
        if name not in self._defs:
            quoted = re.escape(name)
            scala = re.compile(
                rf"\b(?:object|class|trait|enum|def|val|var|type|given)\s+{quoted}\b"
                rf"|\bcase\s+(?:[A-Za-z0-9_]+\s*,\s*)*{quoted}\b")
            python = re.compile(rf"^{quoted}\s*(?::[^=\n]+)?=", re.MULTILINE)
            self._defs[name] = [n for n, t in self.texts.items()
                                if (n.endswith(".py") and python.search(t)) or scala.search(t)]
        return self._defs[name]

    def anchors_of(self, path: str) -> frozenset[str]:
        if path not in self.anchors:
            self.anchors[path] = check_docs.anchors_of(self.repo.read(path))
        return self.anchors[path]

    def files_under(self, path: str) -> list[str]:
        prefix = path.rstrip("/") + "/"
        return [n for n in self.repo.files if n.startswith(prefix)]


def resolve_path(index: Index, span: str) -> tuple[list[str] | None, str | None]:
    """The tracked files a path span names, or an error. Directories name every file under them."""
    path, _, anchor = span.partition("#")
    if "/" not in path.rstrip("/") and "." in path:
        found = index.by_basename.get(path, [])
        if len(found) != 1:
            return None, f"`{span}` names {len(found)} tracked files; cite a path" if found else f"missing file `{span}`"
        files = found
    else:
        clean = path.rstrip("/")
        if clean in index.repo.files:
            files = [clean]
        elif clean in index.repo.dirs:
            files = index.files_under(clean)
        else:
            return None, f"missing path `{span}`"
    if anchor:
        if not files[0].endswith(".md") or len(files) != 1:
            return None, f"anchor on a non-markdown path `{span}`"
        if anchor.lower() not in index.anchors_of(files[0]):
            return None, f"missing anchor `{span}`"
    return files, None


def resolve_symbol(index: Index, span: str, scope: Sequence[str]) -> str | None:
    """An error for a symbol that does not resolve, else None."""
    match = SYMBOL_SPAN.match(span)
    assert match
    segments = match.group(1).split(".")
    head, rest = segments[0], segments[1:]
    defining = index.defining(head)
    if scope:
        defining = [n for n in defining if n in set(scope)]
        if not defining and not rest:
            return f"`{span}` is not defined in the cited file"
    if not defining:
        return f"symbol `{head}` is not defined in any source file" if not scope else \
            f"`{span}` is not defined in the cited file"
    if all(any(segment not in index.tokens[n] for segment in rest) for n in defining):
        return f"`{span}`: `{'.'.join(rest)}` does not occur where `{head}` is defined"
    return None


def is_path_span(span: str) -> bool:
    if not PATH_SPAN.match(span):
        return False
    path = span.partition("#")[0]
    return "/" in path or "." in path.rsplit("/", 1)[-1] and path.rsplit(".", 1)[-1].lower() in check_docs.FILE_EXTENSIONS


def command_paths(span: str) -> list[str]:
    """Repo path tokens of a command span; flags, placeholders, URLs and absolute paths are not paths."""
    found = []
    for token in span.split():
        if token.startswith(("\"", "'")):
            continue
        if token.startswith(("-", "<", "$", "/", "~", "http")) or "*" in token or "..." in token or "=" in token:
            continue
        found.append(token)
    return found


def check_pointer_cell(index: Index, doc: str, line: int, cell: str, *, scoped: bool) -> list[Problem]:
    """Problems of one pointer cell: its spans (paths, symbols, commands), links and line numbers."""
    out: list[Problem] = []
    if LINE_NUMBER.search(cell):
        out.append(Problem(doc, line, f"line number cited in `{cell[:50]}`"))
    spans = code_spans(cell)
    files: list[str] = []
    symbols: list[str] = []
    for span in spans:
        if span.startswith("-"):
            continue
        if " " in span:
            for token in command_paths(span):
                if token.split("/")[0] in index.repo.top_level and ("/" in token or "." in token):
                    got, error = resolve_path(index, token)
                    if error:
                        out.append(Problem(doc, line, error))
            continue
        if is_path_span(span):
            got, error = resolve_path(index, span)
            if error:
                out.append(Problem(doc, line, error))
            elif got:
                files.extend(got)
        elif SYMBOL_SPAN.match(span):
            symbols.append(span)
        else:
            out.append(Problem(doc, line, f"`{span}` is not a path, a symbol or a command"))
    scope = files if scoped else []
    for span in symbols:
        error = resolve_symbol(index, span, scope)
        if error:
            out.append(Problem(doc, line, error))
    masked = check_docs.mask_code(cell)
    cache: dict[str, frozenset[str]] = {}
    out.extend(Problem(doc, line, f"{v.kind.value}: {v.key}")
               for v in check_docs.check_links(index.repo, doc, [check_docs.Line(line, masked)], cache))
    return out


@dataclass(frozen=True)
class Row:
    line: int
    cells: tuple[str, ...]


def table_rows(text: str, header: Sequence[str]) -> tuple[list[Row], list[str]]:
    """The data rows of every table whose header is `header`, and the header problems."""
    rows: list[Row] = []
    problems: list[str] = []
    in_table = False
    found = False
    for number, raw in enumerate(text.splitlines(), start=1):
        if not raw.lstrip().startswith("|"):
            in_table = False
            continue
        cells = split_cells(raw)
        if tuple(cells) == tuple(header):
            in_table = found = True
            continue
        if not in_table or all(re.fullmatch(r":?-{3,}:?", c) for c in cells):
            continue
        rows.append(Row(number, tuple(cells)))
    if not found:
        problems.append("header `" + " | ".join(header) + "` not found")
    return rows, problems


def read(repo: Repo, path: str) -> str | None:
    return repo.read(path) if path in repo.files else None


def check_table(index: Index, doc: str, header: Sequence[str]) -> tuple[list[Row], list[Problem]]:
    text = read(index.repo, doc)
    if text is None:
        return [], [Problem(doc, 0, "missing")]
    rows, header_problems = table_rows(text, header)
    problems = [Problem(doc, 0, p) for p in header_problems]
    seen: set[str] = set()
    for row in rows:
        if len(row.cells) != len(header):
            problems.append(Problem(doc, row.line, f"{len(row.cells)} cells, expected {len(header)}"))
            continue
        first = row.cells[0].lower()
        if first in seen:
            problems.append(Problem(doc, row.line, f"duplicate row `{row.cells[0][:50]}`"))
        seen.add(first)
        for name, cell in zip(header, row.cells):
            if cell.lower() in EMPTY and not (doc.endswith("questions.md") and name in ("Decision", "Open issue")):
                problems.append(Problem(doc, row.line, f"empty `{name}` in `{row.cells[0][:40]}`"))
    return [r for r in rows if len(r.cells) == len(header)], problems


def check_glossary(index: Index, doc: str = "docs/glossary.md") -> list[Problem]:
    rows, problems = check_table(index, doc, GLOSSARY_HEADER)
    for row in rows:
        term, meaning, where = row.cells
        if "<br" in meaning or "\n" in meaning:
            problems.append(Problem(doc, row.line, f"meaning of `{term[:40]}` is not one line"))
        if not code_spans(where) and not MD_LINK.search(where):
            problems.append(Problem(doc, row.line, f"`{term[:40]}` has no symbol, path or link in `Where defined`"))
        problems.extend(check_pointer_cell(index, doc, row.line, where, scoped=False))
    return problems


def check_questions(index: Index, doc: str = "docs/questions.md") -> list[Problem]:
    rows, problems = check_table(index, doc, QUESTIONS_HEADER)
    if rows and not QUESTIONS_MIN <= len(rows) <= QUESTIONS_MAX:
        problems.append(Problem(doc, 0, f"{len(rows)} questions, expected {QUESTIONS_MIN} to {QUESTIONS_MAX}"))
    for row in rows:
        question, where, decision, issue = row.cells
        if not code_spans(where):
            problems.append(Problem(doc, row.line, f"`{question[:40]}` cites no symbol or path"))
        problems.extend(check_pointer_cell(index, doc, row.line, where, scoped=True))
        problems.extend(check_pointer_cell(index, doc, row.line, decision, scoped=False))
        stripped = ISSUE_LINK.sub(lambda m: "#" + m.group(1), issue)
        if stripped.lower() not in EMPTY and not ISSUE_REF.match(stripped):
            problems.append(Problem(doc, row.line, f"`Open issue` of `{question[:40]}` is not `#N` references"))
    return problems


def adr_headers(repo: Repo) -> dict[str, tuple[str, str]]:
    """ADR number -> (path, title from the first heading)."""
    found: dict[str, tuple[str, str]] = {}
    for name in sorted(repo.files):
        match = ADR_FILE.match(name)
        if not match:
            continue
        heading = next((l for l in repo.read(name).splitlines() if l.startswith("# ")), "")
        title = re.sub(r"^#\s+\d{4}\.\s*", "", heading).strip()
        found[match.group(1)] = (name, title)
    return found


def check_decisions(index: Index, doc: str = "docs/decisions.md") -> list[Problem]:
    rows, problems = check_table(index, doc, DECISIONS_HEADER)
    adrs = adr_headers(index.repo)
    claimed: dict[str, int] = {}
    for row in rows:
        decision, where, _invariant, _reopen = row.cells
        problems.extend(check_pointer_cell(index, doc, row.line, where, scoped=False))
        for target in MD_LINK.findall(where):
            resolved = posixpath.normpath(posixpath.join(posixpath.dirname(doc), target.partition("#")[0]))
            match = ADR_FILE.match(resolved)
            if match and decision.lower() == adrs.get(match.group(1), ("", ""))[1].lower():
                claimed[match.group(1)] = claimed.get(match.group(1), 0) + 1
    for number, (path, title) in adrs.items():
        if claimed.get(number, 0) != 1:
            problems.append(Problem(doc, 0, f"ADR {number} needs exactly one row titled `{title}` linking {path}"))
    return problems


def check_adr_index(index: Index, doc: str = "docs/adr/README.md") -> list[Problem]:
    """The ADR index lists every record, with the record's date."""
    text = read(index.repo, doc)
    if text is None:
        return [Problem(doc, 0, "missing")]
    problems: list[Problem] = []
    for number, (path, _title) in adr_headers(index.repo).items():
        name = posixpath.basename(path)
        row = next((l for l in text.splitlines() if l.startswith(f"| [{number}]({name})")), None)
        if row is None:
            problems.append(Problem(doc, 0, f"ADR {number} is not in the index"))
            continue
        date = re.search(r"\*\*Date\*\*:\s*(\d{4}-\d{2}-\d{2})", index.repo.read(path))
        if date and date.group(1) not in row:
            problems.append(Problem(doc, 0, f"ADR {number}: index date differs from {date.group(1)}"))
    return problems


def fenced_lines(text: str) -> list[tuple[int, str]]:
    lines: list[tuple[int, str]] = []
    fence: str | None = None
    for number, raw in enumerate(text.splitlines(), start=1):
        match = FENCE_OPEN.match(raw)
        if fence is None:
            if match:
                fence = match.group(1)
            continue
        if match and raw.strip().strip("`") == "" and len(match.group(1)) >= len(fence):
            fence = None
        else:
            lines.append((number, raw))
    return lines


def scenarios(repo: Repo) -> frozenset[str]:
    path = "scripts/devnet/cli.py"
    return frozenset(SCENARIO.findall(repo.read(path))) if path in repo.files else frozenset()


def check_playbooks(index: Index, directory: str = "docs/playbooks") -> list[Problem]:
    repo = index.repo
    files = sorted(n for n in repo.files if n.startswith(directory + "/") and n.endswith(".md"))
    if not files:
        return [Problem(directory, 0, "no playbooks")]
    problems: list[Problem] = []
    known = scenarios(repo)
    readme = f"{directory}/README.md"
    for name in files:
        text = repo.read(name)
        count = len(text.splitlines())
        if count >= PLAYBOOK_MAX_LINES:
            problems.append(Problem(name, 0, f"{count} lines, the limit is under {PLAYBOOK_MAX_LINES}"))
        for number, raw in fenced_lines(text):
            for token in command_paths(raw):
                if token.split("/")[0] in repo.top_level and ("/" in token or "." in token):
                    _files, error = resolve_path(index, token.rstrip(";,)"))
                    if error:
                        problems.append(Problem(name, number, error))
            match = re.search(r"scripts/devnet\.py\s+(?:--name\s+\S+\s+)?([a-z0-9_-]+)", raw)
            if match and known and match.group(1) not in known and not match.group(1).startswith("-"):
                problems.append(Problem(name, number, f"`{match.group(1)}` is not a devnet.py sub-command"))
        if name != readme and readme in repo.files and f"({posixpath.basename(name)})" not in repo.read(readme):
            problems.append(Problem(readme, 0, f"does not link {posixpath.basename(name)}"))
    if readme not in repo.files:
        problems.append(Problem(readme, 0, "missing"))
    return problems


def check_templates(index: Index) -> list[Problem]:
    repo = index.repo
    problems: list[Problem] = []
    issue = ".github/ISSUE_TEMPLATE/aud.yml"
    text = read(repo, issue)
    if text is None:
        problems.append(Problem(issue, 0, "missing (the default issue template has the audit shape)"))
    else:
        if "SPDX-License-Identifier" not in text:
            problems.append(Problem(issue, 0, "no SPDX header"))
        for field in AUDIT_FIELDS:
            if not re.search(rf"^\s+id:\s*{field}\s*$", text, re.MULTILINE):
                problems.append(Problem(issue, 0, f"no `id: {field}` field"))
        others = sorted(n for n in repo.files if n.startswith(".github/ISSUE_TEMPLATE/") and n.endswith(".yml")
                        and n not in (issue, ".github/ISSUE_TEMPLATE/config.yml"))
        first = sorted([issue] + others + [".github/ISSUE_TEMPLATE/config.yml"])[0]
        if first != issue:
            problems.append(Problem(issue, 0, f"sorts after {first}; the chooser lists templates by file name"))
    config = read(repo, ".github/ISSUE_TEMPLATE/config.yml")
    if config is None or "security/advisories" not in config:
        problems.append(Problem(".github/ISSUE_TEMPLATE/config.yml", 0, "keeps no security contact link"))
    pr = read(repo, ".github/pull_request_template.md")
    if pr is None or "codemap" not in pr.lower() or "compatibility" not in pr.lower():
        problems.append(Problem(".github/pull_request_template.md", 0, "does not ask for codemap and compatibility rows"))
    return problems


def check_start_block(index: Index, doc: str = "AGENTS.md") -> list[Problem]:
    text = read(index.repo, doc)
    if text is None:
        return [Problem(doc, 0, "missing")]
    lines = text.splitlines()
    head = [i for i, l in enumerate(lines) if l.strip() == START_LABEL]
    if not head:
        return [Problem(doc, 0, f"no `{START_LABEL}` block")]
    problems: list[Problem] = []
    start = head[0]
    if any(l.strip() for l in lines[:start] if not l.startswith("# ")):
        problems.append(Problem(doc, start + 1, "the start block is not at the top"))
    end = start + 1
    while end < len(lines) and (not lines[end].strip() or re.match(r"^\d+\.\s", lines[end])):
        end += 1
    block = lines[start:end]
    while block and not block[-1].strip():
        block.pop()
    if len(block) > START_BLOCK_MAX_LINES:
        problems.append(Problem(doc, start + 1, f"{len(block)} lines, the limit is {START_BLOCK_MAX_LINES}"))
    body = "\n".join(block)
    for needle in START_MENTIONS:
        if needle not in body:
            problems.append(Problem(doc, start + 1, f"does not mention `{needle}`"))
    return problems


CHECKS = {
    "glossary": check_glossary,
    "questions": check_questions,
    "decisions": check_decisions,
    "adr-index": check_adr_index,
    "playbooks": check_playbooks,
    "templates": check_templates,
    "start": check_start_block,
}


def run(repo: Repo, names: Sequence[str]) -> list[Problem]:
    index = Index(repo)
    problems: list[Problem] = []
    for name in names:
        problems.extend(CHECKS[name](index))
    return problems


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--root", type=Path, default=ROOT, help="repository root")
    parser.add_argument("--only", action="append", choices=sorted(CHECKS), help="run only this check (repeatable)")
    args = parser.parse_args(argv)
    repo = Repo.discover(args.root.resolve())
    problems = run(repo, args.only or list(CHECKS))
    for problem in problems:
        print(problem.render())
    if problems:
        print(f"{len(problems)} on-ramp problem(s)", file=sys.stderr)
        return 1
    print("onramp ok: " + ", ".join(args.only or CHECKS))
    return 0


if __name__ == "__main__":
    sys.exit(main())
