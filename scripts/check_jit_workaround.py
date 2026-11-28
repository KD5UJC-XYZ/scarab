#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Fail when a JVM that runs Scarab's code could start without the JIT workaround of #1521.

`scripts/jit_workaround.py` holds the options (`OPTIONS`). This check reads text; it runs nothing.

1. **Every place that starts a JVM is classified.** Each tracked file outside the excluded trees (documents,
   fixtures, changelog fragments, data files and the scripts' own unit tests, whose `java` is a stand-in) is read for
   the statements that start a JVM, comments left out: in shell, Dockerfile, workflow and Nix files a command
   (continued lines joined) with a `java` followed by an option, an expansion or an argument file, a `bin/java`,
   `exec "$java"` or an exec-form `["java", ...]`; in Python, read as a syntax tree, a list or tuple in which `"java"`
   (or a path ending in `/bin/java`) is followed by an option or an unpacked list, or follows `--entrypoint`, a string
   that is itself a `java -...` command, and a string naming `javaOptions` or `withRunJVMOptions`; in Scala and sbt
   files a line with a `"java"` argument, `--entrypoint java`, a `bin/java` or `"bin", "java"` path, `javaOptions` or
   `withRunJVMOptions`. A file with such a statement must be in `LAUNCHERS`, with as many as it records, so a new
   launcher, or a new launch in a known file, fails here until it is classified.
2. **Every launcher carries the options**, checked file by file: the copies in `build.sbt`, `scripts/sbtw`,
   `scripts/scarab-jvm`, the Antithesis node script and `RuntimeCauseSuite` equal `OPTIONS` in order; in a Python
   launcher every launch but the recorded exempt ones names `*jit_workaround.OPTIONS` or a recorded list built from
   it; the release image, `candidate_oci` and the Nix apps start the node through `scripts/scarab-jvm`; no sbt
   setting replaces `javaOptions`, and every forked group's `runJVMOptions` include `jdk25Flags`, but for the
   regression test of `JIT_DEFAULT_GROUPS`, whose `testGrouping` must fork it with `jdk25FlagsJitDefaults`. An exempt
   launch records why it is left alone.
3. **Every documented command that starts Scarab's code carries them**: in the documents of `DOCUMENTS`, a command of
   a fenced block (continued lines joined) that starts `java` on an `xyz.kd5ujc.scarab.` class or a `-jar` names the
   options in order, or starts through `scarab-jvm`.
"""
from __future__ import annotations

import argparse
import ast
from collections.abc import Callable, Iterable
from dataclasses import dataclass
import json
from pathlib import Path
import re
import subprocess
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
import jit_workaround  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
OPTIONS = jit_workaround.OPTIONS
SPELLED = " ".join(OPTIONS)
EXCLUDED = re.compile(r"^(?:docs/|fixtures/|changes/|licenses/|scripts/tests/)|\.(?:md|json|jsonl|lock|cbor|png|svg)$")
THIS = "scripts/check_jit_workaround.py"
COMMENT = re.compile(r"^\s*(?:#|\*|//|/\*)")
SHELL_START = re.compile(r"""(?:^|[\s;&|(`'"])(?:exec\s+)?java\s+(?:-|"|\$|@)|bin/java\b|exec\s+"\$java"|"""
                         r"""\[\s*["'](?:\S*/)?java["']""")
CODE_START = re.compile(r"""["']java["']\s*(?:,|\)|$)|--entrypoint[ =]java\b|bin/java\b|"bin",\s*"java"|"""
                        r"""\bjavaOptions\b|\bwithRunJVMOptions\b""")
PYTHON_COMMAND = re.compile(r"^\s*(?:exec\s+)?(?:\S*/)?java\s+-")
SBT_OPTIONS = re.compile(r"\bjavaOptions\b|\bwithRunJVMOptions\b")
CODE_SUFFIXES = (".scala", ".sbt", ".java")
CODE_WORDS = ('"java"', "'java'", "bin/java", "javaOptions", "withRunJVMOptions")
DOCUMENTS = ("README.md", "CONTRIBUTING.md", "docs/running.md", "docs/running/*.md", "docs/nix.md",
             "docs/reproducible-builds.md", "docs/leios.md")
FENCE = re.compile(r"^\s*(```|~~~)")
DOC_START = re.compile(r"""(?:^|[\s;&|(`'"])(?:exec\s+)?(?:\S*/)?java\s+(?:-|"|\$|@)""")


def contiguous(options: Iterable[str]) -> bool:
    """Whether `OPTIONS` appear in `options` one after the other, in order."""
    seen = list(options)
    return any(tuple(seen[i:i + len(OPTIONS)]) == OPTIONS for i in range(len(seen)))


def logical_lines(text: str) -> list[str]:
    """The lines of `text` with backslash-continued lines joined."""
    out, pending = [], ""
    for line in text.splitlines():
        if line.rstrip().endswith("\\"):
            pending += line.rstrip()[:-1] + " "
        else:
            out.append(pending + line)
            pending = ""
    if pending:
        out.append(pending)
    return out


# The forked test groups that run on the JIT's defaults on purpose, by the suite they fork -> why. The
# `testGrouping` that names such a suite (as the string `".<suite>"`, comments left out) forks it with
# `jdk25FlagsJitDefaults`, and no other group may take that list.
JIT_DEFAULT_GROUPS: dict[str, str] = {
    "MainnetCollateralJitSuite": "the regression test of the miscompilation itself (#1521): with the workaround it "
                                 "would pass even if the miscompiled code came back, so it runs on the JIT's defaults",
}
FLAGS = re.compile(r"\bjdk25Flags\b|\bjitWorkaround\b")
DEFAULTS = re.compile(r"\bjdk25FlagsJitDefaults\b")
RUN_OPTIONS = re.compile(r"withRunJVMOptions\(([^()]*(?:\([^()]*\)[^()]*)*)\)")
GROUPING = re.compile(r"testGrouping\s*:=\s*\{")


def without_line_comments(text: str) -> str:
    """`text` with each `//` comment blanked, its length kept, so offsets still count lines."""
    return re.sub(r"//[^\n]*", lambda match: " " * len(match.group(0)), text)


def blocks(text: str) -> list[tuple[int, int]]:
    """The spans of the `testGrouping := { ... }` blocks of `text`, braces balanced."""
    spans = []
    for match in GROUPING.finditer(text):
        depth, index = 0, match.end() - 1
        while index < len(text):
            depth += {"{": 1, "}": -1}.get(text[index], 0)
            if depth == 0:
                break
            index += 1
        spans.append((match.start(), index + 1))
    return spans


def check_build_sbt(text: str) -> list[str]:
    found = []
    code = without_line_comments(text)

    def line(offset: int) -> int:
        return code.count("\n", 0, offset) + 1

    literal = re.search(r"^val jitWorkaround = Seq\(([^)]*)\)$", code, re.M)
    if literal is None or tuple(re.findall(r'"([^"]*)"', literal.group(1))) != OPTIONS:
        found.append(f"`val jitWorkaround = Seq(...)` is not {OPTIONS}")
    defaults = re.search(r"^val jdk25FlagsJitDefaults = Seq\([^)]*\)$", code, re.M)
    flags = re.search(r"^val jdk25Flags = jdk25FlagsJitDefaults \+\+ jitWorkaround$", code, re.M)
    if defaults is None or flags is None:
        found.append("`jdk25Flags` is not `jdk25FlagsJitDefaults ++ jitWorkaround`")
    if "ThisBuild / Test / javaOptions ++= jdk25Flags" not in code:
        found.append("`ThisBuild / Test / javaOptions ++= jdk25Flags` is missing: the forked tests lose the options")
    for match in re.finditer(r"\bjavaOptions\s*:=", code):
        found.append(f"line {line(match.start())}: `javaOptions :=` replaces the options")
    spans = blocks(code)
    definitions = {match.start() for match in (defaults, flags) if match is not None}
    for match in DEFAULTS.finditer(code):
        if code.rfind("\n", 0, match.start()) + 1 not in definitions and not any(
                start <= match.start() < end for start, end in spans):
            found.append(f"line {line(match.start())}: `jdk25FlagsJitDefaults` outside a test grouping of "
                         "JIT_DEFAULT_GROUPS: only the miscompilation's regression test runs without the workaround")

    def options(match: re.Match, scope: str) -> str:
        argument = match.group(1).strip()
        if re.fullmatch(r"\w+", argument):
            named = re.findall(rf"\bval {re.escape(argument)} = (.*)$", scope[:match.start()], re.M)
            return named[-1] if named else ""
        return argument

    inside = set()
    for start, end in spans:
        block = code[start:end]
        suites = [suite for suite in JIT_DEFAULT_GROUPS if f'".{suite}"' in block]
        forks = [(match, options(match, block)) for match in RUN_OPTIONS.finditer(block)]
        inside |= {start + match.start() for match, _ in forks}
        on_defaults = [match for match, given in forks if DEFAULTS.search(given) and not FLAGS.search(given)]
        for match, given in forks:
            if not FLAGS.search(given) and not (suites and match in on_defaults):
                found.append(f"line {line(start + match.start())}: `withRunJVMOptions` without jdk25Flags")
        if suites and not on_defaults:
            found.append(f"line {line(start)}: the group of {', '.join(suites)} runs with the JIT workaround, so it "
                         "tests nothing: fork it with `jdk25FlagsJitDefaults` alone (JIT_DEFAULT_GROUPS)")
        if suites and len(on_defaults) > len(suites):
            found.append(f"line {line(start)}: more groups fork with `jdk25FlagsJitDefaults` than the "
                         "JIT_DEFAULT_GROUPS suites this grouping names")
    for match in RUN_OPTIONS.finditer(code):
        if match.start() not in inside and not FLAGS.search(options(match, code)):
            found.append(f"line {line(match.start())}: `withRunJVMOptions` without jdk25Flags")
    forked_runs = len(re.findall(r"Compile / run / fork := true", code))
    if forked_runs != code.count("Compile / run / javaOptions ++= jdk25Flags"):
        found.append("a `Compile / run / fork := true` without `Compile / run / javaOptions ++= jdk25Flags`")
    return found


def check_sbtw(text: str) -> list[str]:
    array = re.search(r"^jvm_options=\(([^)]*)\)$", text, re.M)
    found = []
    if array is None or not contiguous(array.group(1).split()):
        found.append(f"`jvm_options` does not hold {SPELLED}")
    if not re.search(r'^exec java "\$\{jvm_options\[@\]\}" ', text, re.M):
        found.append('the build JVM does not start with `exec java "${jvm_options[@]}"`')
    return found


def check_scarab_jvm(text: str) -> list[str]:
    found = []
    array = re.search(r"^jit=\(([^)]*)\)$", text, re.M)
    if array is None or tuple(array.group(1).split()) != OPTIONS:
        found.append(f"`jit=(...)` is not {SPELLED}, at the top level of the script")
    execs = [line for line in text.splitlines() if line.startswith('exec "$java"')]
    if len(execs) != 1 or not re.fullmatch(r'exec "\$java" .*"\$\{jit\[@\]\}" "\$@"', execs[0]):
        found.append('the launcher does not end in one `exec "$java" ... "${jit[@]}" "$@"`')
    return found


def check_literal_launches(text: str) -> list[str]:
    launches = [line for line in logical_lines(text) if not COMMENT.match(line) and SHELL_START.search(line)]
    if not launches:
        return ["no JVM launch found"]
    return [f"`{line.strip()[:80]}...` lacks {SPELLED}" for line in launches if SPELLED not in line]


def check_python_launcher(text: str) -> list[str]:
    found = []
    if not re.search(r"^import jit_workaround$", text, re.M):
        found.append("does not `import jit_workaround`")
    if "*jit_workaround.OPTIONS" not in text:
        found.append("its JVM options do not include `*jit_workaround.OPTIONS`")
    return found


def check_quoted_options(text: str) -> list[str]:
    spelled = r",\s*".join(f'"{re.escape(option)}"' for option in OPTIONS)
    return [] if re.search(spelled, text) else [f"its JVM's arguments do not hold {SPELLED}"]


def through_launcher(pattern: str) -> Callable[[str], list[str]]:
    def check(text: str) -> list[str]:
        return [] if re.search(pattern, text, re.M) else ["does not start the node through scripts/scarab-jvm"]
    return check


def check_dockerfile(text: str) -> list[str]:
    lines = [line for line in text.splitlines() if line.startswith("ENTRYPOINT ")]
    if len(lines) != 1:
        return ["has no single ENTRYPOINT"]
    entrypoint = json.loads(lines[0].removeprefix("ENTRYPOINT "))
    return [] if entrypoint[:1] == ["/opt/scarab/scarab-jvm"] else ["its ENTRYPOINT is not scripts/scarab-jvm"]


def check_appends_only(text: str) -> list[str]:
    return ["`javaOptions :=` replaces the options"] if re.search(r"\bjavaOptions\s*:=", text) else []


@dataclass(frozen=True)
class Launcher:
    """`launches` statements of the file start a JVM (point 1). In a Python file each launch names one of `markers`
    (`*jit_workaround.OPTIONS`, or a list `check` holds to it), but for `exempt` of them; `check` holds the file to
    the options; `note` says what is covered, what is left alone and why."""
    launches: int
    check: Callable[[str], list[str]] | None
    note: str
    markers: tuple[str, ...] = ()
    exempt: int = 0


OWN = ("*jit_workaround.OPTIONS",)
LAUNCHERS: dict[str, Launcher] = {
    "build.sbt": Launcher(5, check_build_sbt,
                          "the forked test JVMs (`ThisBuild / Test / javaOptions`, the heap-bound groups' "
                          "`runJVMOptions`, fuzz's appended options) and `node / run`, all through jdk25Flags; only the "
                          "groups of JIT_DEFAULT_GROUPS run on the JIT's defaults"),
    "scripts/sbtw": Launcher(1, check_sbtw, "the build JVM: compilation, the in-process test groups, `runMain`"),
    "scripts/scarab-jvm": Launcher(1, check_scarab_jvm, "the release image's and the Nix apps' node, in either mode"),
    "antithesis/pool/scarab/scarab-node.sh": Launcher(1, check_literal_launches, "the Antithesis trial's node"),
    "scripts/devnet/cluster.py": Launcher(1, check_python_launcher, "every devnet JVM (`scala_container`)",
                                          markers=("*jvm",)),
    "scripts/conformance/run.py": Launcher(2, None, "the conformance tools, with `scenarios.JDK_FLAGS`; exempt: the "
                                                    "image's `java -version` probe, which runs no Scarab code",
                                           markers=("*scenario.jvm",), exempt=1),
    "docker/candidate.Dockerfile": Launcher(1, check_dockerfile, "the release image: its entry point is the launcher"),
    "scripts/candidate_oci.py": Launcher(1, None, "the entry point the release image is verified against: the launcher",
                                         markers=("LAUNCHER_PATH",)),
    "scripts/nix-network": Launcher(1, through_launcher(r'^exec "\$BASH" -p "\$source_dir/scripts/scarab-jvm" '),
                                    "the Nix network apps, through the launcher"),
    "scripts/candidate_acceptance.py": Launcher(1, check_appends_only,
                                                "its forked suites append to the build's `Test / javaOptions`",
                                                markers=("javaOptions ++=",)),
    "scripts/build_release_candidate.py": Launcher(2, check_python_launcher, "the image's resource and native checks",
                                                   markers=OWN),
    "scripts/reproducible_build.py": Launcher(3, check_python_launcher, "the fat JAR's resource, `--help` and native "
                                                                        "checks", markers=OWN),
    "scripts/verify_published_release.py": Launcher(4, check_python_launcher, "the published JAR's and image's checks",
                                                    markers=OWN),
    "scripts/preflight_doctor.py": Launcher(1, None, "exempt: the JDK image's `java -version`, which runs no Scarab "
                                                     "code", exempt=1),
    "scripts/secret_timing.py": Launcher(1, None,
                                         "exempt: the secret-path timing measurement, whose JVM (`-XX:+UseSerialGC "
                                         "-Xbatch`) is part of its recorded method (docs/running/secret-timing.md); "
                                         "it times key code and gives no ledger result", exempt=1),
    "models/src/test/scala/xyz/kd5ujc/scarab/models/RuntimeCauseSuite.scala": Launcher(
        1, check_quoted_options, "the terminal-depth probe's JVM"),
}
# The Python launchers whose options come from jit_workaround, beyond those that start the JVM themselves.
OPTION_SOURCES: dict[str, Callable[[str], list[str]]] = {"scripts/conformance/scenarios.py": check_python_launcher}


def is_java(node: ast.AST | None) -> bool:
    return (isinstance(node, ast.Constant) and isinstance(node.value, str)
            and (node.value == "java" or node.value.endswith("/bin/java")))


def is_option(node: ast.AST | None) -> bool:
    return isinstance(node, ast.Starred) or (
        isinstance(node, ast.Constant) and isinstance(node.value, str) and node.value.startswith("-"))


def python_sites(text: str) -> list[str]:
    """The statements of a Python file that start a JVM (point 1), each as its source text."""
    try:
        tree = ast.parse(text)
    except SyntaxError:
        return [line for line in text.splitlines() if not COMMENT.match(line) and CODE_START.search(line)]
    docstrings = {id(body[0].value) for node in ast.walk(tree)
                  if isinstance(node, (ast.Module, ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef))
                  and (body := node.body) and isinstance(body[0], ast.Expr) and isinstance(body[0].value, ast.Constant)}
    sites = []
    for node in ast.walk(tree):
        if isinstance(node, (ast.List, ast.Tuple)):
            elements = node.elts
            if any(is_java(element) and (is_option(elements[i + 1] if i + 1 < len(elements) else None)
                                         or (i > 0 and isinstance(elements[i - 1], ast.Constant)
                                             and elements[i - 1].value == "--entrypoint"))
                   for i, element in enumerate(elements)):
                sites.append(ast.unparse(node))
        elif (isinstance(node, ast.Constant) and isinstance(node.value, str) and id(node) not in docstrings
              and (SBT_OPTIONS.search(node.value) or PYTHON_COMMAND.search(node.value))):
            sites.append(node.value)
    return sites


def sites(path: str, text: str) -> list[str]:
    """The statements of `text` that start a JVM (point 1)."""
    if path.endswith(".py"):
        return python_sites(text) if any(word in text for word in CODE_WORDS + ("java ",)) else []
    if path.endswith(CODE_SUFFIXES):
        if not any(word in text for word in CODE_WORDS):
            return []
        return [line for line in text.splitlines() if not COMMENT.match(line) and CODE_START.search(line)]
    if "java" not in text:
        return []
    return [line for line in logical_lines(text) if not COMMENT.match(line) and SHELL_START.search(line)]


def document_problems(path: str, text: str) -> list[str]:
    found, block = [], False
    numbered = list(enumerate(text.splitlines(), start=1))
    start = 0
    pending = ""
    for number, line in numbered:
        if FENCE.match(line):
            block, pending = not block, ""
            continue
        if not block:
            continue
        if not pending:
            start = number
        if line.rstrip().endswith("\\"):
            pending += line.rstrip()[:-1] + " "
            continue
        command, pending = pending + line, ""
        if (DOC_START.search(command) and ("xyz.kd5ujc.scarab." in command or " -jar " in command)
                and SPELLED not in command and "scarab-jvm" not in command):
            found.append(f"{path}:{start}: a documented JVM of Scarab's code without `{SPELLED}` (#1521)")
    return found


def problems(files: Iterable[tuple[str, str]], documents: Iterable[tuple[str, str]],
             registry: dict[str, Launcher] = LAUNCHERS) -> list[str]:
    found = []
    seen = {}
    for path, text in files:
        seen[path] = text
        if path == THIS or EXCLUDED.search(path):
            continue
        launches = sites(path, text)
        entry = registry.get(path)
        if launches and entry is None:
            found.append(f"{path}: starts a JVM in {len(launches)} statement(s) and is not classified in {THIS}'s "
                         f"LAUNCHERS: give it {SPELLED} (scripts/jit_workaround.py, #1521) or record why it is left "
                         "alone")
        elif entry is not None and len(launches) != entry.launches:
            found.append(f"{path}: {len(launches)} statement(s) start a JVM, {THIS} records {entry.launches}: "
                         "classify the change")
        elif entry is not None and path.endswith(".py"):
            bare = [site for site in launches if not any(marker in site for marker in entry.markers)]
            if len(bare) != entry.exempt:
                found.append(f"{path}: {len(bare)} launch(es) without {SPELLED} (by {' or '.join(entry.markers)}), "
                             f"{THIS} exempts {entry.exempt}: " + "; ".join(site[:80] for site in bare))
    for path, entry in registry.items():
        if path not in seen:
            found.append(f"{path}: recorded in {THIS} but not tracked")
        elif entry.check is not None:
            found += [f"{path}: {problem}" for problem in entry.check(seen[path])]
    for path, check in OPTION_SOURCES.items():
        found += [f"{path}: {problem}" for problem in check(seen.get(path, ""))]
    for path, text in documents:
        found += document_problems(path, text)
    return found


def tracked(root: Path) -> list[tuple[str, str]]:
    names = subprocess.run(["git", "-C", str(root), "ls-files", "-z"], check=True, capture_output=True).stdout
    files = []
    for name in names.decode().split("\0"):
        path = root / name
        if name and not EXCLUDED.search(name) and path.is_file() and not path.is_symlink():
            try:
                files.append((name, path.read_text(encoding="utf-8")))
            except UnicodeDecodeError:
                continue
    return files


def documents(root: Path) -> list[tuple[str, str]]:
    paths = sorted({path for pattern in DOCUMENTS for path in root.glob(pattern)})
    return [(path.relative_to(root).as_posix(), path.read_text(encoding="utf-8")) for path in paths]


def main(argv: list[str] | None = None) -> int:
    argparse.ArgumentParser(description=__doc__.splitlines()[0]).parse_args(argv)
    found = problems(tracked(ROOT), documents(ROOT))
    for problem in found:
        print(problem, file=sys.stderr)
    if not found:
        launches = sum(entry.launches for entry in LAUNCHERS.values())
        exempt = sum(entry.exempt for entry in LAUNCHERS.values())
        print(f"JIT workaround (#1521): {launches} JVM launches in {len(LAUNCHERS)} files classified, "
              f"{exempt} exempt with a reason, the rest carrying {SPELLED}")
    return 1 if found else 0


if __name__ == "__main__":
    sys.exit(main())
