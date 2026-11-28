#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Fail when a JVM that runs Scarab's code could start without the JIT workaround of #1521.

`scripts/jit_workaround.py` holds the options (`OPTIONS`). This check reads text; it runs nothing.

1. **Every place that starts a JVM is classified.** Each tracked file outside the excluded trees (documents,
   fixtures, changelog fragments, data files and the scripts' own unit tests, whose `java` is a stand-in) is read
   line by line, comments left out, for the forms that start a JVM: in shell, Dockerfile, workflow and Nix files a
   `java` followed by an option, an expansion or an argument file, a `bin/java`, or `exec "$java"`; in Python, Scala
   and sbt files a `"java"` argument followed by another, `--entrypoint java`, a `bin/java` or `"bin", "java"` path,
   and every `javaOptions` or `withRunJVMOptions`. A file with such a line must be in `LAUNCHERS`, with as many lines
   as it records, so a new launcher, or a new launch in a known file, fails here until it is classified.
2. **Every launcher that runs Scarab's code carries the options**, checked file by file: the copies in `build.sbt`,
   `scripts/sbtw`, `scripts/scarab-jvm` and the Antithesis node script equal `OPTIONS` in order; the Python
   launchers take them from `jit_workaround`; the release image, `candidate_oci` and the Nix apps start the node
   through `scripts/scarab-jvm`; no sbt setting replaces `javaOptions`, and every forked group's `runJVMOptions`
   include `jdk25Flags`, but for the groups of `JIT_DEFAULT_GROUPS` (the miscompilation's own regression test), which
   alone may take `jdk25FlagsJitDefaults`. An entry without a check records why it is left alone.
3. **Every documented command that starts Scarab's code carries them**: in the documents of `DOCUMENTS`, a command of
   a fenced block (continued lines joined) that starts `java` on an `xyz.kd5ujc.scarab.` class or a `-jar` names the
   options in order, or starts through `scarab-jvm`.
"""
from __future__ import annotations

import argparse
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
SHELL_START = re.compile(r"""(?:^|[\s;&|(`'"])(?:exec\s+)?java\s+(?:-|"|\$|@)|bin/java\b|exec\s+"\$java\"""")
CODE_START = re.compile(r"""["']java["']\s*,\s*(?:\*|f?["']-|\w)|--entrypoint[ =]java\b|bin/java\b|"bin",\s*"java"|"""
                        r"""\bjavaOptions\b|\bwithRunJVMOptions\b""")
CODE_SUFFIXES = (".py", ".scala", ".sbt", ".java")
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


# The forked test groups that run on the JIT's defaults on purpose (`jdk25FlagsJitDefaults`), by a suite they name ->
# why. A group may use it only if its `testGrouping` names one of these suites.
JIT_DEFAULT_GROUPS: dict[str, str] = {
    "MainnetCollateralJitSuite": "the regression test of the miscompilation itself (#1521): with the workaround it "
                                 "would pass even if the miscompiled code came back, so it runs on the JIT's defaults",
}
FLAGS = re.compile(r"\bjdk25Flags\b")
DEFAULTS = re.compile(r"\bjdk25FlagsJitDefaults\b")
RUN_OPTIONS = re.compile(r"withRunJVMOptions\(([^()]*(?:\([^()]*\)[^()]*)*)\)")


def check_build_sbt(text: str) -> list[str]:
    found = []

    def line(offset: int) -> int:
        return text.count("\n", 0, offset) + 1

    literal = re.search(r"^val jitWorkaround = Seq\(([^)]*)\)$", text, re.M)
    if literal is None or tuple(re.findall(r'"([^"]*)"', literal.group(1))) != OPTIONS:
        found.append(f"`val jitWorkaround = Seq(...)` is not {OPTIONS}")
    defaults = re.search(r"^val jdk25FlagsJitDefaults = Seq\([^)]*\)$", text, re.M)
    flags = re.search(r"^val jdk25Flags = jdk25FlagsJitDefaults \+\+ jitWorkaround$", text, re.M)
    if defaults is None or flags is None:
        found.append("`jdk25Flags` is not `jdk25FlagsJitDefaults ++ jitWorkaround`")
    if "ThisBuild / Test / javaOptions ++= jdk25Flags" not in text:
        found.append("`ThisBuild / Test / javaOptions ++= jdk25Flags` is missing: the forked tests lose the options")
    for match in re.finditer(r"\bjavaOptions\s*:=", text):
        found.append(f"line {line(match.start())}: `javaOptions :=` replaces the options")
    definitions = {match.start() for match in (defaults, flags) if match is not None}
    for match in DEFAULTS.finditer(text):
        start = text.rfind("\n", 0, match.start()) + 1
        if start in definitions or text[start:match.start()].lstrip().startswith("//"):
            continue
        grouping = text[text.rfind("testGrouping", 0, match.start()):match.start()]
        if "testGrouping" not in grouping or not any(suite in grouping for suite in JIT_DEFAULT_GROUPS):
            found.append(f"line {line(match.start())}: `jdk25FlagsJitDefaults` outside a group of JIT_DEFAULT_GROUPS: "
                         "only the miscompilation's regression test runs without the workaround")
    for match in RUN_OPTIONS.finditer(text):
        argument = match.group(1).strip()
        if re.fullmatch(r"\w+", argument):
            named = re.findall(rf"\bval {re.escape(argument)} = (.*)$", text[:match.start()], re.M)
            argument = named[-1] if named else ""
        if not FLAGS.search(argument) and not DEFAULTS.search(argument):
            found.append(f"line {line(match.start())}: `withRunJVMOptions` without jdk25Flags")
    forked_runs = len(re.findall(r"Compile / run / fork := true", text))
    if forked_runs != text.count("Compile / run / javaOptions ++= jdk25Flags"):
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
    """`launches` lines of the file start a JVM (point 1); `check` holds the covered ones to the options, and `note`
    says what is covered and what is left alone, and why."""
    launches: int
    check: Callable[[str], list[str]] | None
    note: str


SMOKE = ("a one-shot check of the built artifact (its resources, its native library, `--help`) in a JVM that exits "
         "at once: no ledger rule runs, and the node's own start is checked through the image's entry point")
LAUNCHERS: dict[str, Launcher] = {
    "build.sbt": Launcher(5, check_build_sbt,
                          "the forked test JVMs (`ThisBuild / Test / javaOptions`, the heap-bound groups' "
                          "`runJVMOptions`, fuzz's appended options) and `node / run`, all through jdk25Flags; only the "
                          "groups of JIT_DEFAULT_GROUPS run on the JIT's defaults"),
    "scripts/sbtw": Launcher(1, check_sbtw, "the build JVM: compilation, the in-process test groups, `runMain`"),
    "scripts/scarab-jvm": Launcher(1, check_scarab_jvm, "the release image's and the Nix apps' node, in either mode"),
    "antithesis/pool/scarab/scarab-node.sh": Launcher(1, check_literal_launches, "the Antithesis trial's node"),
    "scripts/devnet/cluster.py": Launcher(1, check_python_launcher, "every devnet JVM (`scala_container`)"),
    "scripts/conformance/run.py": Launcher(2, through_launcher(r'"java", \*scenario\.jvm, '),
                                           "the conformance tools, with `scenarios.JDK_FLAGS`; the other line is "
                                           "the image's `java -version` probe, which runs no Scarab code"),
    "docker/candidate.Dockerfile": Launcher(1, check_dockerfile, "the release image: its entry point is the launcher"),
    "scripts/candidate_oci.py": Launcher(1, through_launcher(r"^ENTRYPOINT = \['/' \+ LAUNCHER_PATH, "),
                                         "the entry point the release image is verified against"),
    "scripts/nix-network": Launcher(1, through_launcher(r'^exec "\$BASH" -p "\$source_dir/scripts/scarab-jvm" '),
                                    "the Nix network apps, through the launcher"),
    "scripts/candidate_acceptance.py": Launcher(1, check_appends_only,
                                                "its forked suites append to the build's `Test / javaOptions`"),
    "scripts/build_release_candidate.py": Launcher(2, None, SMOKE),
    "scripts/reproducible_build.py": Launcher(3, None, SMOKE),
    "scripts/verify_published_release.py": Launcher(4, None, SMOKE),
    "scripts/preflight_doctor.py": Launcher(1, None, "the JDK image's `java -version`, which runs no Scarab code"),
    "scripts/secret_timing.py": Launcher(1, None,
                                         "the secret-path timing measurement, whose JVM (`-XX:+UseSerialGC -Xbatch`) "
                                         "is part of its recorded method (docs/running/secret-timing.md); it times "
                                         "key code and produces no ledger result"),
    "models/src/test/scala/xyz/kd5ujc/scarab/models/RuntimeCauseSuite.scala": Launcher(
        1, None, "a probe JVM with pinned stack and heap that folds a synthetic error chain; no ledger code runs"),
}
# The Python launchers whose options come from jit_workaround, beyond those that start the JVM themselves.
OPTION_SOURCES: dict[str, Callable[[str], list[str]]] = {"scripts/conformance/scenarios.py": check_python_launcher}


def launches(path: str, text: str) -> int:
    code = path.endswith(CODE_SUFFIXES)
    pattern = CODE_START if code else SHELL_START
    # Every form of the pattern holds one of these words, and most files hold none.
    if not any(word in text for word in (CODE_WORDS if code else ("java",))):
        return 0
    return sum(1 for line in text.splitlines() if not COMMENT.match(line) and pattern.search(line))


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
        count = launches(path, text)
        entry = registry.get(path)
        if count and entry is None:
            found.append(f"{path}: starts a JVM on {count} line(s) and is not classified in {THIS}'s LAUNCHERS: give "
                         f"it {SPELLED} (scripts/jit_workaround.py, #1521) or record why it is left alone")
        elif entry is not None and count != entry.launches:
            found.append(f"{path}: {count} line(s) start a JVM, {THIS} records {entry.launches}: classify the change")
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
        print(f"JIT workaround (#1521): {len(LAUNCHERS)} recorded JVM launchers classified, "
              f"{sum(1 for entry in LAUNCHERS.values() if entry.check)} checked for {SPELLED}")
    return 1 if found else 0


if __name__ == "__main__":
    sys.exit(main())
