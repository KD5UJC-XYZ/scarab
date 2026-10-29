#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Documentation drift check that ratchets.

Checked files: `docs/**/*.md`, `README.md`, `AGENTS.md`, `CONTRIBUTING.md`, and for FRAGMENT_LINK only the changelog
fragments `changes/*.md` (a fragment records a change as it was, so it may name what has since been removed). Fenced
code blocks are never inspected. Every finding is a `Violation` of one `Kind`:

  BROKEN_LINK       a relative markdown link whose file is missing
  BROKEN_ANCHOR     a link to a heading (`file.md#slug`, `#slug`) that does not exist (GitHub slug rules, ATX
                    headings and explicit `<a id=...>` anchors only)
  MISSING_PATH      a backticked repo path that does not exist. Only spans whose first segment is a tracked
                    top-level entry are checked (`node/src/...`, `scripts/x.py`; never globs, `...`, `<placeholders>`,
                    absolute paths or upstream Haskell paths), plus bare `Name.scala` / `name.py` / `name.sbt` spans,
                    which must match the basename of some tracked file
  MISSING_SYMBOL    a backticked `Type.member` (or `Type.member()` / `a.b.C`) whose segments do not all occur as
                    identifiers in one single Scala/sbt/Python/Java source file. Heuristic, deliberately narrow: the
                    first segment must be CamelCase (a type) or the `xyz.kd5ujc.` package root, spans that end in a
                    file extension are skipped, and upstream Haskell module paths (`Cardano.Ledger.X`, anything
                    starting with an `UPSTREAM_ROOTS` word, or three or more capitalised segments) are skipped
  LINE_CITATION     `File.scala:123`, `path/File.py:10-20` or a link ending `#L12` into a repo file. Line numbers rot;
                    these are counted and ratcheted, never fixed by this tool
  FILE_SIZE         a file above the per-file byte budget (measure: bytes). Report-only
  TABLE_CELL        a table cell above the per-cell character budget (measure: characters). Report-only
  UNREGISTERED_PIN  (only when `docs/data/reference-pins.json` exists) a cited upstream version (`cardano-node 11.1.3`,
                    `Scalus 1.3.0`, ...) or 7-40 hex commit id (in a GitHub commit/tree/blob URL, or on a line that
                    names an upstream repository) that no registered pin covers
  PIN_REGISTRY      a registry defect: a dependent path that does not exist, or a dependent that no longer mentions
                    the pin's version or revision
  FRAGMENT_LINK     a relative link in a changelog fragment (`changes/*.md` other than its README): a fragment is read
                    from `changes/`, from `CHANGELOG.md` and from release notes, so its links are absolute URLs
                    (pinned to a revision for a historical reference), as docs/changelog-preparation.md says
  STALE_CURRENT     a version called latest or current that is not the current one: a watched name with a registered
                    version (`Scalus 1.3.0 (the latest release)`), or a ledger snapshot magic other than the newest
                    one of `PersistedFormat.scala` (`SCRBSNP5`), within a few words of `latest`, `current`,
                    `currently` or `now`. History that names an older version without calling it current is not
                    reported
  ADR_UNINDEXED     (only when `docs/adr/README.md` exists) a decision record `docs/adr/NNNN-*.md` that the ADR index
                    does not link, so a reader starting from the index cannot find it

Blocking and report-only rules. FILE_SIZE and TABLE_CELL are size rules: unrelated changes legitimately append a few
words to a long table cell, so they are printed as warnings and never change the exit code, and the baseline does not
list them. Every other kind is a correctness rule and blocks.

Ratchet (blocking kinds only): `docs/data/docs-check-baseline.json` lists today's violations (identity = kind, path,
key; line numbers are not part of an identity so that edits above a finding do not move it). The check fails when

  * a violation is not in the baseline or occurs more often than baselined, or
  * the baseline holds an entry that no longer occurs or occurs less often.

`--write-baseline` rewrites the baseline to the current state: that is how fixed violations leave it. It is also the
only way to accept a new violation, which a reviewer sees as a baseline diff; do not do that to silence a check.
The size budgets (`maxFileBytes`, `maxCellChars`) live in the baseline; `--max-file-bytes` / `--max-cell-chars` override
them and `--write-baseline` stores the effective values. Entries of report-only kinds in an older baseline are ignored.

Pure standard library; no sbt, no network.
"""
from __future__ import annotations

from collections import Counter
from collections.abc import Iterable, Mapping, Sequence
from dataclasses import dataclass, field
from enum import Enum
import argparse
import json
import posixpath
import re
import subprocess
import sys
from pathlib import Path
from urllib.parse import unquote

ROOT = Path(__file__).resolve().parents[1]
BASELINE_PATH = "docs/data/docs-check-baseline.json"
ADR_INDEX_PATH = "docs/adr/README.md"
ADR_RECORD = re.compile(r"^docs/adr/\d{4}-[^/]+\.md$")
PINS_PATH = "docs/data/reference-pins.json"
PERSISTED_FORMAT_PATH = "ledger/src/main/scala/xyz/kd5ujc/scarab/ledger/PersistedFormat.scala"
FRAGMENTS_DIR = "changes/"
FRAGMENTS_README = "changes/README.md"
ROOT_FILES = ("README.md", "AGENTS.md", "CONTRIBUTING.md")
SOURCE_SUFFIXES = (".scala", ".sbt", ".py", ".java")
DEFAULT_MAX_FILE_BYTES = 60_000
DEFAULT_MAX_CELL_CHARS = 600
CELL_KEY_CHARS = 60
SCHEMA_VERSION = 1

# First segments that mark an upstream (Haskell or external) module path rather than a Scarab symbol.
UPSTREAM_ROOTS = frozenset({
    "Cardano", "Ouroboros", "Data", "Control", "Test", "GHC", "Plutus", "PlutusCore", "PlutusLedgerApi", "Codec",
    "System", "Network", "Haskell", "UntypedPlutusCore", "Shelley", "Byron", "Alonzo", "Babbage", "Conway", "Mary",
    "Allegra", "Dijkstra", "Prelude", "Agda", "Foreign", "Numeric", "Text", "Crypto", "Java", "Scala", "Cabal",
})
FILE_EXTENSIONS = frozenset({
    "scala", "md", "json", "py", "sbt", "sh", "yml", "yaml", "tsv", "cddl", "hs", "txt", "toml", "conf", "lock",
    "patch", "log", "dot", "png", "svg", "java", "cbor", "csv", "html", "js", "tar", "gz", "sql", "properties",
    "dockerfile", "so", "jar", "nix", "cabal", "lagda", "gv", "proto", "xml", "pdf",
})
# Branch names (`release/alpha`, ...) share a first segment with the tracked `release/` directory but are not paths.
class BranchName(str, Enum):
    RELEASE_ALPHA = "release/alpha"
    RELEASE_BETA = "release/beta"
    RELEASE_PREPROD = "release/preprod"
    RELEASE_PROD = "release/prod"


BRANCH_NAMES = frozenset(b.value for b in BranchName)
# Names whose cited versions must be registered. A registry entry adds its own name and aliases.
WATCHED_VERSION_NAMES = ("cardano-node", "Scalus", "Mithril", "cuddle", "blst-java", "blst", "plutus-core")
# Repository names that make a nearby bare commit id a citation of an upstream revision.
UPSTREAM_REPOS = (
    "cardano-node", "cardano-ledger", "ouroboros-network", "ouroboros-consensus", "plutus", "formal-ledger-specifications",
    "constrained-generators", "cardano-blueprint", "mithril", "blst", "scalus", "cardano-cls", "cuddle",
)


class Kind(str, Enum):
    BROKEN_LINK = "broken-link"
    BROKEN_ANCHOR = "broken-anchor"
    MISSING_PATH = "missing-path"
    MISSING_SYMBOL = "missing-symbol"
    LINE_CITATION = "line-citation"
    FILE_SIZE = "file-size"
    TABLE_CELL = "table-cell"
    UNREGISTERED_PIN = "unregistered-pin"
    PIN_REGISTRY = "pin-registry"
    ADR_UNINDEXED = "adr-unindexed"
    FRAGMENT_LINK = "fragment-link"
    STALE_CURRENT = "stale-current"

    @property
    def blocking(self) -> bool:
        return self not in REPORT_ONLY


# Size rules print warnings and never fail the check; see the module docstring.
REPORT_ONLY = frozenset({Kind.FILE_SIZE, Kind.TABLE_CELL})


@dataclass(frozen=True)
class Violation:
    kind: Kind
    path: str
    key: str
    line: int = 0
    measure: int | None = None

    @property
    def identity(self) -> tuple[Kind, str, str]:
        return (self.kind, self.path, self.key)

    def render(self) -> str:
        where = f"{self.path}:{self.line}" if self.line else self.path
        size = f" ({self.measure})" if self.measure is not None else ""
        return f"{where}: {self.kind.value}: {self.key}{size}"


@dataclass(frozen=True)
class Budgets:
    max_file_bytes: int = DEFAULT_MAX_FILE_BYTES
    max_cell_chars: int = DEFAULT_MAX_CELL_CHARS


@dataclass(frozen=True)
class Pin:
    name: str
    aliases: tuple[str, ...]
    version: str | None
    revision: str | None
    dependents: tuple[str, ...]


class PinRegistryError(ValueError):
    """`reference-pins.json` is not a valid registry."""


@dataclass
class Repo:
    """The tracked files under `root` (`git ls-files`, or a directory walk outside a work tree)."""
    root: Path
    files: frozenset[str]
    dirs: frozenset[str] = field(init=False)
    top_level: frozenset[str] = field(init=False)
    basenames: frozenset[str] = field(init=False)

    def __post_init__(self) -> None:
        dirs = {""}
        for name in self.files:
            parts = name.split("/")
            for i in range(1, len(parts)):
                dirs.add("/".join(parts[:i]))
        self.dirs = frozenset(dirs)
        self.top_level = frozenset(name.split("/")[0] for name in self.files)
        self.basenames = frozenset(name.rsplit("/", 1)[-1] for name in self.files)

    @staticmethod
    def discover(root: Path) -> "Repo":
        try:
            out = subprocess.run(["git", "-C", str(root), "ls-files", "-z"], check=True, capture_output=True).stdout
            names = [n.decode() for n in out.split(b"\0") if n]
            if names and (root / names[0]).exists():
                return Repo(root, frozenset(n for n in names if (root / n).exists()))
        except (OSError, subprocess.CalledProcessError):
            pass
        found = (p.relative_to(root).as_posix() for p in root.rglob("*") if p.is_file() and ".git" not in p.parts)
        return Repo(root, frozenset(found))

    def exists(self, path: str) -> bool:
        return path in self.files or path in self.dirs

    def doc_files(self) -> list[str]:
        return sorted(n for n in self.files
                      if n in ROOT_FILES or (n.startswith("docs/") and n.endswith(".md")) or n == FRAGMENTS_README)

    def fragment_files(self) -> list[str]:
        return sorted(n for n in self.files
                      if self.is_fragment(n))

    def is_fragment(self, path: str) -> bool:
        return path.startswith(FRAGMENTS_DIR) and path.endswith(".md") and path != FRAGMENTS_README

    def read(self, path: str) -> str:
        return (self.root / path).read_text(encoding="utf-8", errors="replace")


# ---------------------------------------------------------------------------------------------------------------
# Markdown scanning

FENCE = re.compile(r"^\s{0,3}(`{3,}|~{3,})")
CODE_SPAN = re.compile(r"(`+)(.+?)(?<!`)\1(?!`)")
INLINE_LINK = re.compile(r"!?\[(?:[^\]\\]|\\.)*\]\(\s*(<[^>]*>|[^)\s]+)(?:\s+(?:\"[^\"]*\"|'[^']*'))?\s*\)")
REF_DEFINITION = re.compile(r"^\s{0,3}\[[^\]]+\]:\s*(<[^>]*>|\S+)")
SCHEME = re.compile(r"^[A-Za-z][A-Za-z0-9+.-]*:")
HEADING = re.compile(r"^\s{0,3}(#{1,6})\s+(.*?)\s*$")
EXPLICIT_ANCHOR = re.compile(r"""<a\s[^>]*?(?:id|name)\s*=\s*["']([^"']+)["']""", re.IGNORECASE)


@dataclass(frozen=True)
class Line:
    number: int
    text: str


def prose_lines(text: str) -> list[Line]:
    """The lines outside fenced code blocks."""
    lines: list[Line] = []
    fence: str | None = None
    for number, raw in enumerate(text.splitlines(), start=1):
        match = FENCE.match(raw)
        if fence is None:
            if match:
                fence = match.group(1)[0] * len(match.group(1))
                continue
            lines.append(Line(number, raw))
        elif match and match.group(1)[0] == fence[0] and len(match.group(1)) >= len(fence) \
                and not raw.strip().strip(fence[0]):
            fence = None
    return lines


def mask_code(text: str) -> str:
    """`text` with each code span replaced by same-length filler, so links inside code are not links."""
    return CODE_SPAN.sub(lambda m: "x" * len(m.group(0)), text)


def code_spans(text: str) -> list[str]:
    return [m.group(2).strip() for m in CODE_SPAN.finditer(text)]


def heading_slug_text(heading: str) -> str:
    text = re.sub(r"!\[([^\]]*)\]\([^)]*\)", r"\1", heading)
    text = re.sub(r"\[([^\]]*)\]\([^)]*\)", r"\1", text)
    text = re.sub(r"\[([^\]]*)\]\[[^\]]*\]", r"\1", text)
    text = re.sub(r"<[^>]+>", "", text)
    text = text.replace("`", "")
    text = re.sub(r"(?<!\w)_+(.+?)_+(?!\w)", r"\1", text)
    text = re.sub(r"\s+#+\s*$", "", text)
    return text


def github_slug(heading: str) -> str:
    text = heading_slug_text(heading).strip().lower()
    return re.sub(r"[^\w\- ]", "", text).replace(" ", "-")


def anchors_of(text: str) -> frozenset[str]:
    """Heading slugs (with GitHub's `-1`, `-2` suffix for repeats) and explicit anchors of one markdown file."""
    seen: Counter[str] = Counter()
    found: set[str] = set()
    for line in prose_lines(text):
        found.update(m.group(1) for m in EXPLICIT_ANCHOR.finditer(line.text))
        heading = HEADING.match(line.text)
        if heading:
            slug = github_slug(heading.group(2))
            found.add(slug if seen[slug] == 0 else f"{slug}-{seen[slug]}")
            seen[slug] += 1
    return frozenset(found)


# ---------------------------------------------------------------------------------------------------------------
# Individual checks

LINE_CITE = re.compile(
    r"(?<![\w/.-])((?:[A-Za-z0-9_.@+-]+/)*[A-Za-z0-9_.@+-]+\.(?:scala|py|sbt|json|md|ya?ml|tsv|cddl|sh|conf|java))"
    r"(?::(\d+)(?:-\d+)?|#L(\d+)(?:-L?\d+)?)(?![\w])")


def resolve_repo_file(repo: Repo, doc: str, cited: str) -> str | None:
    """The repo path a citation names (root-relative, document-relative, or a unique-by-name bare file), if any."""
    for base in ("", posixpath.dirname(doc)):
        candidate = posixpath.normpath(posixpath.join(base, cited))
        if candidate in repo.files:
            return candidate
    if "/" not in cited and cited in repo.basenames:
        return cited
    return None


def link_targets(line: Line) -> list[str]:
    """The targets of the inline links and the reference definition on `line`, outside code spans."""
    masked = mask_code(line.text)
    raws = [m.group(1) for m in INLINE_LINK.finditer(masked)]
    ref = REF_DEFINITION.match(masked)
    if ref:
        raws.append(ref.group(1))
    return [raw[1:-1] if raw.startswith("<") else raw for raw in raws]


def check_links(repo: Repo, doc: str, lines: Sequence[Line], anchor_cache: dict[str, frozenset[str]]) -> list[Violation]:
    out: list[Violation] = []
    own = posixpath.dirname(doc)
    fragment = repo.is_fragment(doc)
    for line in lines:
        for target in link_targets(line):
            if not target or SCHEME.match(target) or target.startswith("//"):
                continue
            if fragment:
                out.append(Violation(Kind.FRAGMENT_LINK, doc, target, line.number))
                continue
            path_part, _, anchor = target.partition("#")
            path_part = unquote(path_part)
            anchor = unquote(anchor)
            if path_part == "":
                resolved = doc
            else:
                joined = path_part[1:] if path_part.startswith("/") else posixpath.join(own, path_part)
                resolved = posixpath.normpath(joined)
                if resolved.startswith("..") or not repo.exists(resolved):
                    out.append(Violation(Kind.BROKEN_LINK, doc, target, line.number))
                    continue
            if anchor and resolved.endswith(".md") and resolved in repo.files and not re.fullmatch(r"L\d+(-L?\d+)?", anchor):
                if resolved not in anchor_cache:
                    anchor_cache[resolved] = anchors_of(repo.read(resolved))
                if anchor.lower() not in anchor_cache[resolved]:
                    out.append(Violation(Kind.BROKEN_ANCHOR, doc, f"{resolved}#{anchor}" if resolved != doc else f"#{anchor}",
                                         line.number))
    return out


SPAN_PATH = re.compile(r"^(\.{1,2}/)?[A-Za-z0-9_.@+-][A-Za-z0-9_./@+-]*$")
SPAN_SUFFIX = re.compile(r"(?::\d+(?:-\d+)?|#L\d+(?:-L?\d+)?)$")
BARE_FILE = re.compile(r"^[A-Za-z_][A-Za-z0-9_.-]*\.(?:scala|py|sbt)$")


def check_paths(repo: Repo, doc: str, lines: Sequence[Line]) -> list[Violation]:
    out: list[Violation] = []
    for line in lines:
        for span in code_spans(line.text):
            bare = SPAN_SUFFIX.sub("", span)
            if BARE_FILE.match(bare):
                if bare not in repo.basenames:
                    out.append(Violation(Kind.MISSING_PATH, doc, bare, line.number))
                continue
            if "/" not in bare or "..." in bare or "//" in bare or not SPAN_PATH.match(bare):
                continue
            relative = bare.startswith(("./", "../"))
            if not relative and bare.split("/")[0] not in repo.top_level:
                continue
            clean = bare.rstrip("/")
            if clean in BRANCH_NAMES:
                continue
            candidates = [posixpath.normpath(posixpath.join(posixpath.dirname(doc), clean))]
            if not relative:
                candidates.insert(0, posixpath.normpath(clean))
            if not any(repo.exists(c) for c in candidates):
                out.append(Violation(Kind.MISSING_PATH, doc, bare, line.number))
    return out


def check_line_citations(repo: Repo, doc: str, lines: Sequence[Line]) -> list[Violation]:
    out: list[Violation] = []
    for line in lines:
        for match in LINE_CITE.finditer(line.text):
            if resolve_repo_file(repo, doc, match.group(1)) is not None:
                number = match.group(2) or match.group(3)
                out.append(Violation(Kind.LINE_CITATION, doc, f"{match.group(1)}:{number}", line.number))
    return out


SYMBOL = re.compile(r"^((?:[A-Z][A-Za-z0-9_]*|xyz\.kd5ujc\.[a-z0-9_.]*[A-Za-z0-9_])(?:\.[A-Za-z_][A-Za-z0-9_]*)+)(?:\(.*\))?$")
IDENT = re.compile(r"[A-Za-z_][A-Za-z0-9_]*")


def is_symbol_candidate(span: str) -> list[str] | None:
    """The identifier segments of `span` when it is a checkable `Type.member` citation, else None."""
    match = SYMBOL.match(span)
    if not match:
        return None
    segments = match.group(1).split(".")
    if segments[-1].lower() in FILE_EXTENSIONS or segments[0] in UPSTREAM_ROOTS:
        return None
    if sum(1 for s in segments if s[:1].isupper()) >= 3 and segments[0][:1].isupper() \
            and all(s[:1].isupper() for s in segments[:3]):
        return None
    return segments


def source_token_sets(repo: Repo) -> list[frozenset[str]]:
    return [frozenset(IDENT.findall(repo.read(n))) for n in sorted(repo.files) if n.endswith(SOURCE_SUFFIXES)]


def check_symbols(doc: str, lines: Sequence[Line], tokens: Sequence[frozenset[str]]) -> list[Violation]:
    out: list[Violation] = []
    for line in lines:
        for span in code_spans(line.text):
            segments = is_symbol_candidate(span)
            if segments is None:
                continue
            needed = set(segments)
            if not any(needed <= file_tokens for file_tokens in tokens):
                out.append(Violation(Kind.MISSING_SYMBOL, doc, ".".join(segments), line.number))
    return out


def check_size(doc: str, text: str, lines: Sequence[Line], budgets: Budgets) -> list[Violation]:
    out: list[Violation] = []
    size = len(text.encode("utf-8"))
    if size > budgets.max_file_bytes:
        out.append(Violation(Kind.FILE_SIZE, doc, "", measure=size))
    for line in lines:
        stripped = line.text.strip()
        if not stripped.startswith("|"):
            continue
        for cell in split_cells(stripped):
            if len(cell) > budgets.max_cell_chars:
                out.append(Violation(Kind.TABLE_CELL, doc, cell[:CELL_KEY_CHARS], line.number, len(cell)))
    return out


def split_cells(row: str) -> list[str]:
    """Cells of a table row; a `|` inside a code span or escaped as `\\|` does not split."""
    cells: list[str] = []
    current: list[str] = []
    in_code = False
    i = 0
    body = row.strip()
    body = body[1:] if body.startswith("|") else body
    while i < len(body):
        ch = body[i]
        if ch == "\\" and i + 1 < len(body):
            current.append(body[i:i + 2])
            i += 2
            continue
        if ch == "`":
            in_code = not in_code
        if ch == "|" and not in_code:
            cells.append("".join(current).strip())
            current = []
        else:
            current.append(ch)
        i += 1
    if "".join(current).strip():
        cells.append("".join(current).strip())
    return cells


# ---------------------------------------------------------------------------------------------------------------
# Reference pins

COMMIT_URL = re.compile(r"github\.com/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)/(?:commit|tree|blob)/([0-9a-f]{7,40})\b")
# How far before a commit id (in characters) an upstream repository name still claims it.
PIN_PROXIMITY = 40
HEX_ID = re.compile(r"(?<![0-9A-Za-z/_.-])([0-9a-f]{7,40})(?![0-9A-Za-z_-])")


def load_pins(repo: Repo) -> list[Pin] | None:
    """The registry, or None when `docs/data/reference-pins.json` does not exist."""
    if PINS_PATH not in repo.files:
        return None
    try:
        data = json.loads(repo.read(PINS_PATH))
        pins = [Pin(name=p["name"], aliases=tuple(p.get("aliases", ())), version=p.get("version"),
                    revision=p.get("revision"), dependents=tuple(p.get("dependents", ())))
                for p in data["pins"]]
    except (ValueError, KeyError, TypeError) as err:
        raise PinRegistryError(f"{PINS_PATH}: {err!r}") from err
    for pin in pins:
        if pin.version is None and pin.revision is None:
            raise PinRegistryError(f"{PINS_PATH}: pin {pin.name} has neither version nor revision")
        if pin.revision is not None and not re.fullmatch(r"[0-9a-f]{40}", pin.revision):
            raise PinRegistryError(f"{PINS_PATH}: pin {pin.name} revision is not a 40-digit commit id")
    return pins


def check_pin_registry(repo: Repo, pins: Sequence[Pin]) -> list[Violation]:
    out: list[Violation] = []
    for pin in pins:
        label = f"{pin.name} {pin.version or ''} {pin.revision[:8] if pin.revision else ''}".strip()
        if not pin.dependents:
            out.append(Violation(Kind.PIN_REGISTRY, PINS_PATH, f"{label}: no dependents"))
        for dependent in pin.dependents:
            if dependent not in repo.files:
                out.append(Violation(Kind.PIN_REGISTRY, PINS_PATH, f"{label}: dependent {dependent} does not exist"))
                continue
            text = repo.read(dependent)
            needles = [n for n in (pin.version, pin.revision, pin.revision and pin.revision[:8]) if n]
            if not any(n in text for n in needles):
                out.append(Violation(Kind.PIN_REGISTRY, PINS_PATH, f"{label}: dependent {dependent} does not mention it"))
    return out


def check_pin_citations(doc: str, lines: Sequence[Line], pins: Sequence[Pin]) -> list[Violation]:
    out: list[Violation] = []
    names = {n for pin in pins for n in (pin.name, *pin.aliases)} | set(WATCHED_VERSION_NAMES)
    version_cite = re.compile(
        r"(?<![\w-])(" + "|".join(sorted(map(re.escape, names), key=len, reverse=True)) +
        r")[ `=@-]{1,2}v?(\d+\.\d+(?:\.\d+){0,2})(?![\w.]*\w)(?!\.\d)", re.IGNORECASE)
    revisions = [p.revision for p in pins if p.revision]
    versions = {(n.lower(), p.version) for p in pins if p.version for n in (p.name, *p.aliases)}
    repos = [r for r in UPSTREAM_REPOS]
    for line in lines:
        text = line.text
        for match in version_cite.finditer(text):
            name, version = match.group(1).lower(), match.group(2)
            if (name, version) not in versions:
                out.append(Violation(Kind.UNREGISTERED_PIN, doc, f"{name} {version}", line.number))
        covered: set[str] = set()
        for match in COMMIT_URL.finditer(text):
            covered.add(match.group(3))
            if not any(rev.startswith(match.group(3)) for rev in revisions):
                out.append(Violation(Kind.UNREGISTERED_PIN, doc, f"{match.group(2)}@{match.group(3)}", line.number))
        lowered = text.lower()
        named = [r for r in repos if r in lowered]
        if not named:
            continue
        for match in HEX_ID.finditer(text):
            digest = match.group(1)
            if digest in covered or not re.search(r"\d", digest) or not re.search(r"[a-f]", digest):
                continue
            # A commit id belongs to the upstream repository named just before it; other ids on the
            # line (this repository's commits in an evidence row) are not upstream citations.
            preceding = lowered[max(0, match.start() - PIN_PROXIMITY):match.start()]
            near = [r for r in named if r in preceding]
            if near and not any(rev.startswith(digest) for rev in revisions):
                nearest = max(near, key=lambda r: preceding.rfind(r))
                out.append(Violation(Kind.UNREGISTERED_PIN, doc, f"{nearest}@{digest}", line.number))
    return out


# A currency word this many characters or fewer from a citation calls the cited version current.
CURRENT_PROXIMITY = 30
CURRENT_WORD = re.compile(r"\b(?:latest|current|currently|now)\b", re.IGNORECASE)
SNAPSHOT_MAGIC = re.compile(r"\bSCRBSNP(\d+)\b")
SNAPSHOT_HISTORY = re.compile(r'FormatVersion\.V\d+\s*->\s*"SCRBSNP(\d+)"')


def current_snapshot_magic(repo: Repo) -> int | None:
    """The newest `SCRBSNP<n>` of the snapshot kind's history, or None when the register is not in the tree."""
    if PERSISTED_FORMAT_PATH not in repo.files:
        return None
    numbers = [int(n) for n in SNAPSHOT_HISTORY.findall(repo.read(PERSISTED_FORMAT_PATH))]
    return max(numbers) if numbers else None


def called_current(text: str, start: int, end: int) -> bool:
    return CURRENT_WORD.search(text[max(0, start - CURRENT_PROXIMITY):end + CURRENT_PROXIMITY]) is not None


def check_current_claims(doc: str, lines: Sequence[Line], pins: Sequence[Pin] | None, magic: int | None) -> list[Violation]:
    out: list[Violation] = []
    registered: dict[str, set[str]] = {}
    for pin in pins or ():
        if pin.version:
            for name in (pin.name, *pin.aliases):
                registered.setdefault(name.lower(), set()).add(pin.version)
    version_cite = re.compile(
        r"(?<![\w-])(" + "|".join(sorted(map(re.escape, registered), key=len, reverse=True)) +
        r")[ `=@-]{1,2}v?(\d+\.\d+(?:\.\d+){0,2})(?![\w.]*\w)(?!\.\d)", re.IGNORECASE) if registered else None
    for line in lines:
        text = line.text
        if version_cite is not None:
            for match in version_cite.finditer(text):
                name, version = match.group(1).lower(), match.group(2)
                if version not in registered[name] and called_current(text, match.start(), match.end()):
                    out.append(Violation(Kind.STALE_CURRENT, doc, f"{name} {version}", line.number))
        if magic is not None:
            for match in SNAPSHOT_MAGIC.finditer(text):
                if int(match.group(1)) != magic and called_current(text, match.start(), match.end()):
                    out.append(Violation(Kind.STALE_CURRENT, doc, f"SCRBSNP{match.group(1)}", line.number))
    return out


def check_adr_index(repo: Repo) -> list[Violation]:
    """Every decision record is linked from the ADR index."""
    if ADR_INDEX_PATH not in repo.files:
        return []
    index_dir = posixpath.dirname(ADR_INDEX_PATH)
    linked = set()
    for line in prose_lines(repo.read(ADR_INDEX_PATH)):
        for raw in link_targets(line):
            target = unquote(raw.split("#", 1)[0])
            if target and not SCHEME.match(target) and not target.startswith("//"):
                linked.add(posixpath.normpath(posixpath.join(index_dir, target)))
    return [Violation(Kind.ADR_UNINDEXED, ADR_INDEX_PATH, name)
            for name in sorted(repo.files) if ADR_RECORD.match(name) and name not in linked]


# ---------------------------------------------------------------------------------------------------------------
# Driver

def collect(repo: Repo, budgets: Budgets) -> list[Violation]:
    pins = load_pins(repo)
    magic = current_snapshot_magic(repo)
    tokens = source_token_sets(repo)
    anchor_cache: dict[str, frozenset[str]] = {}
    found: list[Violation] = []
    for doc in repo.doc_files():
        text = repo.read(doc)
        lines = prose_lines(text)
        found += check_links(repo, doc, lines, anchor_cache)
        found += check_paths(repo, doc, lines)
        found += check_symbols(doc, lines, tokens)
        found += check_line_citations(repo, doc, lines)
        found += check_size(doc, text, lines, budgets)
        if pins is not None:
            found += check_pin_citations(doc, lines, pins)
        found += check_current_claims(doc, lines, pins, magic)
    for fragment in repo.fragment_files():
        found += check_links(repo, fragment, prose_lines(repo.read(fragment)), anchor_cache)
    if pins is not None:
        found += check_pin_registry(repo, pins)
    found += check_adr_index(repo)
    return found


@dataclass(frozen=True)
class Entry:
    count: int


def summarise(violations: Iterable[Violation]) -> dict[tuple[Kind, str, str], Entry]:
    """The ratcheted state: occurrence counts of the blocking violations."""
    counts: Counter[tuple[Kind, str, str]] = Counter(v.identity for v in violations if v.kind.blocking)
    return {ident: Entry(n) for ident, n in counts.items()}


@dataclass(frozen=True)
class Baseline:
    budgets: Budgets
    entries: Mapping[tuple[Kind, str, str], Entry]

    @staticmethod
    def empty() -> "Baseline":
        return Baseline(Budgets(), {})

    @staticmethod
    def parse(text: str) -> "Baseline":
        data = json.loads(text)
        if data.get("schemaVersion") != SCHEMA_VERSION:
            raise ValueError(f"unsupported baseline schemaVersion {data.get('schemaVersion')!r}")
        budgets = Budgets(data["budgets"]["maxFileBytes"], data["budgets"]["maxCellChars"])
        entries = {(Kind(e["kind"]), e["path"], e["key"]): Entry(e["count"])
                   for e in data["entries"] if Kind(e["kind"]).blocking}
        return Baseline(budgets, entries)

    def render(self) -> str:
        rows = []
        for (kind, path, key), entry in sorted(self.entries.items(), key=lambda kv: (kv[0][1], kv[0][0].value, kv[0][2])):
            rows.append({"kind": kind.value, "path": path, "key": key, "count": entry.count})
        doc = {"schemaVersion": SCHEMA_VERSION,
               "budgets": {"maxFileBytes": self.budgets.max_file_bytes, "maxCellChars": self.budgets.max_cell_chars},
               "entries": rows}
        return json.dumps(doc, indent=1, ensure_ascii=False) + "\n"


@dataclass(frozen=True)
class Ratchet:
    new: tuple[str, ...]
    stale: tuple[str, ...]

    @property
    def ok(self) -> bool:
        return not self.new and not self.stale


def ratchet(current: Mapping[tuple[Kind, str, str], Entry], baseline: Mapping[tuple[Kind, str, str], Entry]) -> Ratchet:
    new: list[str] = []
    stale: list[str] = []

    def label(ident: tuple[Kind, str, str]) -> str:
        kind, path, key = ident
        return f"{path}: {kind.value}: {key}".rstrip(": ")

    for ident, now in sorted(current.items(), key=lambda kv: (kv[0][1], kv[0][0].value, kv[0][2])):
        was = baseline.get(ident)
        if was is None:
            new.append(label(ident) + (f" x{now.count}" if now.count > 1 else ""))
        elif now.count > was.count:
            new.append(f"{label(ident)}: {now.count} occurrences, baseline allows {was.count}")
    for ident, was in sorted(baseline.items(), key=lambda kv: (kv[0][1], kv[0][0].value, kv[0][2])):
        now = current.get(ident)
        if now is None:
            stale.append(f"{label(ident)}: no longer occurs")
        elif now.count < was.count:
            stale.append(f"{label(ident)}: {now.count} occurrence(s), baseline records {was.count}")
    return Ratchet(tuple(new), tuple(stale))


def counts_by_kind(entries: Mapping[tuple[Kind, str, str], Entry]) -> dict[Kind, int]:
    totals: Counter[Kind] = Counter()
    for (kind, _, _), entry in entries.items():
        totals[kind] += entry.count
    return {kind: totals[kind] for kind in Kind if totals[kind]}


def warnings_of(violations: Iterable[Violation]) -> list[str]:
    """The report-only findings as warning lines, sorted by path and line."""
    advisory = [v for v in violations if not v.kind.blocking]
    return [f"warning: {v.render()}" for v in sorted(advisory, key=lambda v: (v.path, v.line, v.kind.value))]


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--baseline", default=BASELINE_PATH, help="baseline path relative to the root")
    parser.add_argument("--write-baseline", action="store_true", help="rewrite the baseline to the current violations")
    parser.add_argument("--max-file-bytes", type=int)
    parser.add_argument("--max-cell-chars", type=int)
    parser.add_argument("--list", action="store_true", help="print every current blocking violation")
    args = parser.parse_args(argv)

    repo = Repo.discover(args.root.resolve())
    baseline_file = args.root.resolve() / args.baseline
    baseline = Baseline.parse(baseline_file.read_text(encoding="utf-8")) if baseline_file.exists() else Baseline.empty()
    budgets = Budgets(args.max_file_bytes or baseline.budgets.max_file_bytes,
                      args.max_cell_chars or baseline.budgets.max_cell_chars)
    try:
        violations = collect(repo, budgets)
    except PinRegistryError as err:
        print(f"invalid pin registry: {err}")
        return 1
    current = summarise(violations)
    if args.list:
        for v in sorted((v for v in violations if v.kind.blocking), key=lambda v: (v.path, v.line, v.kind.value)):
            print(v.render())
    warnings = warnings_of(violations)
    if warnings:
        print(f"size warnings (report-only; budgets {budgets.max_file_bytes} bytes per file, {budgets.max_cell_chars} "
              f"characters per table cell):")
        print("\n".join(f"  {row}" for row in warnings))
    if args.write_baseline:
        baseline_file.parent.mkdir(parents=True, exist_ok=True)
        baseline_file.write_text(Baseline(budgets, current).render(), encoding="utf-8")
        print(f"wrote {args.baseline}: {sum(e.count for e in current.values())} violations in {len(current)} entries")
        for kind, n in counts_by_kind(current).items():
            print(f"  {kind.value}: {n}")
        return 0
    result = ratchet(current, baseline.entries)
    totals = ", ".join(f"{k.value} {n}" for k, n in counts_by_kind(current).items()) or "none"
    if result.ok:
        print(f"docs check passes ({len(repo.doc_files())} files; baselined violations: {totals}; "
              f"size warnings: {len(warnings)})")
        return 0
    if result.new:
        print("new documentation violations (fix them; the baseline only shrinks, so do not add entries with --write-baseline):")
        print("\n".join(f"  {row}" for row in result.new))
    if result.stale:
        print("baseline entries that no longer occur (fixed violations: run `python3 scripts/check_docs.py --write-baseline`\n"
              "and commit the shrunken baseline):")
        print("\n".join(f"  {row}" for row in result.stale))
    return 1


if __name__ == "__main__":
    sys.exit(main())
