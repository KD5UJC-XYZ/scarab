#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Generate the frozen reviewer evidence packet (O5, #349) for one committed revision.

    evidence_packet.py --revision <tag-or-commit> --output <fresh-dir> [--candidate <dir>] [--check]

The revision is read from git objects (`ls-tree`/`cat-file`), never from the working tree, so an
uncommitted edit or the checkout running the generator cannot leak into the packet. The packet is
the input list of docs/security/external-review.md ("Inputs requested when an audit candidate
exists"): the immutable revision and a module/source map, the gate commands with the image and
recipe identities they run in, the pinned reference revisions, the fixture and coverage
inventories, the optional-data requirements, and the SBOM/CycloneDX inventories of a verified
release candidate (`build_release_candidate.py` output) when one is supplied. Recorded evidence
(compatibility.md and the receipts under docs/data) is referenced by digest only: it belongs to its
recorded source revisions and is not a run of this candidate. Nothing is built, run, fetched,
signed or uploaded.

Output is byte-reproducible: sorted JSON, sorted members, a plain tar with fixed member metadata, no
clock, host or environment values. `--check` generates twice from separate materializations and
fails unless the archives are identical. Every member, and the decoded strings of every JSON
member, passes the local-path guard of `check_local_paths.py`, a check for this machine's paths and
host name, and the pinned gitleaks scan of `check_secret_scan.py`; a match fails the run, it is
never redacted, and a scanner that cannot run leaves the `secret-scan` gate incomplete. A missing input (no candidate, a stale inventory, unreadable pins) is an
explicit incomplete gate, and the run fails unless `--allow-incomplete` is given.
"""
from __future__ import annotations

import argparse
import ast
from dataclasses import dataclass
import enum
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import socket
import subprocess
import sys
import tarfile
import tempfile
import tomllib

import candidate_manifest
import check_conformance_sources
import check_local_paths
import check_secret_scan
import check_test_coverage

SCHEMA = "scarab-reviewer-evidence-packet-v1"
GENERATOR = Path(__file__).resolve()
REPO = GENERATOR.parents[1]
# The generator's own inputs: older revisions are interpreted with this code, so the packet names it.
GENERATOR_FILES = ("scripts/evidence_packet.py", "scripts/check_test_coverage.py",
                   "scripts/check_conformance_sources.py", "scripts/check_local_paths.py",
                   "scripts/check_secret_scan.py", "scripts/candidate_manifest.py", ".gitleaks.toml")
RECORDED_EVIDENCE = ("docs/compatibility.md", "docs/data/compatibility.json", "docs/data/external-acceptance-294.json",
                     "docs/data/upstream-source-review-20261020.json",
                     "docs/data/expanded-source-watch-review-20261020.json", "docs/reference-image.md",
                     "docs/performance.md", "docs/data/performance-log.json", "docs/security/threat-model.md",
                     "docs/security/external-review.md", "scripts/candidate_acceptance/README.md")
PIN_ROOTS = ("docker/", ".github/workflows/", "scripts/", "build.sbt", "project/")
IMAGE_PIN = re.compile(rb"([a-z0-9][a-z0-9./_-]*(?::[A-Za-z0-9._-]+)?)@sha256:([0-9a-f]{64})")
ACTION_PIN = re.compile(rb"uses:\s*([A-Za-z0-9_.-]+/[A-Za-z0-9_./-]+)@([0-9a-f]{40})")
SCARAB_ENV = re.compile(rb'"(SCARAB_[A-Z0-9_]+)"')
MODULE = re.compile(r'lazy val (\w+) = scarab\("(\w+)"(?:, "([^"]+)")?\)')
REFERENCE_NODE_PINS = ("BASE", "ARCHIVE_NAME", "ARCHIVE_SHA256", "ARCHIVE_SIZE", "ARCHIVE_URL", "BINARY_HASHES")
COMMIT_ID = re.compile(r"[0-9a-f]{40}")
SHA256_HEX = re.compile(r"[0-9a-f]{64}")
IMAGE_ID = re.compile(r"sha256:[0-9a-f]{64}")
PINNED_IMAGE = re.compile(r"[a-z0-9][a-z0-9./_-]*(?::[A-Za-z0-9._-]+)?@sha256:[0-9a-f]{64}")
REPOSITORY = re.compile(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")
TAR_MODE = 0o644
EXCLUSIONS = ("Security-sensitive reproductions, exploit details and private captures are not part of "
              "this packet; they are exchanged privately under SECURITY.md. Large or private inputs "
              "are named by the variables that select them and by their recorded digests, never embedded.")
SCOPE = ("Inventory of one committed revision. Recorded evidence belongs to its recorded source "
         "revisions and is not a run of this candidate. Gate commands are listed, not executed; "
         "their results must come from runs of this revision.")


class PacketError(ValueError):
    """A packet that cannot be produced as requested; the subclass names the reason."""


class UnsafeOutput(PacketError):
    """The output directory exists or lies inside the source checkout."""


class ProvenanceLeak(PacketError):
    """A member records a machine-local path or the host name."""


class SecretFound(ProvenanceLeak):
    """The pinned secret scanner reported a finding in a member."""


class UndecodableMember(PacketError):
    """A JSON member cannot be decoded, so its string values cannot be checked."""


class CandidateMismatch(PacketError):
    """The supplied release candidate is invalid or built from another revision."""


class NotReproducible(PacketError):
    """Two generations of the same revision produced different archives."""


class Incomplete(PacketError):
    """One or more gates are not passed and incompleteness was not allowed."""


class Gate(enum.Enum):
    SOURCE = "source-map"
    FIXTURES = "fixture-inventory"
    COVERAGE = "coverage-register"
    REFERENCES = "reference-pins"
    SBOM = "release-candidate-sbom"
    SECRETS = "secret-scan"


class Outcome(enum.Enum):
    PASSED = "passed"
    FAILED = "failed"
    NOT_SUPPLIED = "not-supplied"
    UNAVAILABLE = "scanner-unavailable"


class Family(enum.Enum):
    UNIT = "unit-property-and-scalafix"
    FUZZ = "fuzz"
    SCRIPTS = "script-tests"
    FIXTURES = "fixture-identities"
    PROVENANCE = "provenance-guards"
    SECRETS = "secret-scan"
    DEVNET = "devnet-scenarios"
    CDDL = "cddl-crosscheck"
    NATIVE = "native-identity"
    CANDIDATE = "release-candidate"
    PACKET = "evidence-packet"


class Environment(enum.Enum):
    JDK_IMAGE = "docker/jdk.Dockerfile image"
    HOST_PYTHON = "host python3 (stdlib only)"
    PINNED_VENV = "host python3 venv from scripts/release/requirements.txt"
    DOCKER = "host python3 driving pinned Docker images"
    DEVNET = "devnet: docker/jdk.Dockerfile image and the pinned reference image"


@dataclass(frozen=True)
class Command:
    """`probe` is the stable part of `command` that its defining workflow or CONTRIBUTING.md contains."""
    family: Family
    command: str
    environment: Environment
    probe: str


def commands(commit: str) -> list[Command]:
    """The gate commands of CONTRIBUTING.md and the workflows, one or more per evidence family."""
    scenarios = ["e2e", "relay --seconds 300", "restart", "pool", "kes-rotate", "ntc"]
    return [
        Command(Family.UNIT, "bash scripts/sbtw check", Environment.JDK_IMAGE, "scripts/sbtw check"),
        Command(Family.FUZZ, 'bash scripts/sbtw "fuzz 100000 1 4"', Environment.JDK_IMAGE, "fuzz 100000 1 4"),
        Command(Family.SCRIPTS, "python3 -m unittest discover -s scripts/tests", Environment.PINNED_VENV,
                "unittest discover -s scripts/tests"),
        Command(Family.FIXTURES, "python3 scripts/check_test_coverage.py", Environment.HOST_PYTHON,
                "scripts/check_test_coverage.py"),
        Command(Family.PROVENANCE, "python3 scripts/check_local_paths.py", Environment.HOST_PYTHON,
                "scripts/check_local_paths.py"),
        Command(Family.PROVENANCE, "python3 scripts/check_error_channels.py", Environment.HOST_PYTHON,
                "scripts/check_error_channels.py"),
        Command(Family.SECRETS, "python3 scripts/check_secret_scan.py", Environment.DOCKER,
                "scripts/check_secret_scan.py"),
        Command(Family.DEVNET, "python3 scripts/prepare_reference_image.py --download", Environment.DOCKER,
                "scripts/prepare_reference_image.py --download"),
        *[Command(Family.DEVNET, f"python3 scripts/devnet.py --name <name> {scenario}", Environment.DEVNET,
                  "scripts/devnet.py --name") for scenario in scenarios],
        Command(Family.CDDL, "python3 scripts/cddl_crosscheck.py run --count 40", Environment.DOCKER,
                "scripts/cddl_crosscheck.py run --count 40"),
        Command(Family.NATIVE, "python3 scripts/check_native_identity.py <jblst-or-assembly-jar>",
                Environment.HOST_PYTHON, "scripts/check_native_identity.py"),
        Command(Family.CANDIDATE, "python3 scripts/build_release_candidate.py --check --image <jdk-image> "
                f"--cache .cache --output <fresh-dir> --expected-commit {commit}", Environment.DOCKER,
                "scripts/build_release_candidate.py"),
        Command(Family.PACKET, f"python3 scripts/evidence_packet.py --revision {commit} --output <fresh-dir> "
                "--candidate <candidate-dir> --check", Environment.HOST_PYTHON, "scripts/evidence_packet.py"),
    ]


def runs_in_jdk_image(command: Command) -> bool:
    return command.environment in {Environment.JDK_IMAGE, Environment.DEVNET} or command.family is Family.CANDIDATE


def defined_in(root: Path, records: list[dict], command: Command) -> list[str]:
    """The revision's workflows and CONTRIBUTING.md that state the command; empty is reported, not hidden."""
    probe = command.probe.encode()
    return [r["path"] for r in records
            if (r["path"].startswith(".github/workflows/") or r["path"] == "CONTRIBUTING.md")
            and probe in (root / r["path"]).read_bytes()]


def git(repo: Path, *args: str, stdin: bytes | None = None) -> bytes:
    return subprocess.run(["git", "-C", str(repo), *args], input=stdin, check=True,
                          capture_output=True).stdout


def sha256(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def canonical(value) -> bytes:
    return (json.dumps(value, sort_keys=True, indent=2, ensure_ascii=False) + "\n").encode("utf-8")


def resolve(repo: Path, revision: str) -> dict:
    """The commit, tree and, when `revision` names a tag, the tag object; never a moving branch name."""
    commit = git(repo, "rev-parse", "--verify", "--end-of-options", revision + "^{commit}").decode().strip()
    tree = git(repo, "rev-parse", "--verify", "--end-of-options", commit + "^{tree}").decode().strip()
    # Epoch seconds: `%cI` renders UTC differently across git versions.
    date = int(git(repo, "show", "-s", "--format=%ct", commit).decode().strip())
    name = revision.removeprefix("refs/tags/")
    tag = None
    found = subprocess.run(["git", "-C", str(repo), "rev-parse", "--verify", "--quiet", "--end-of-options",
                            "refs/tags/" + name], capture_output=True)
    if found.returncode == 0:
        tag_object = found.stdout.decode().strip()
        kind = git(repo, "cat-file", "-t", tag_object).decode().strip()
        tag = dict(name=name, object=tag_object, annotated=kind == "tag")
    return dict(commit=commit, tree=tree, committerTime=date, tag=tag)


def materialize(repo: Path, commit: str, destination: Path) -> tuple[list[dict], list[dict]]:
    """Write every blob of `commit` under `destination`; return file records and non-file entries."""
    listing = git(repo, "ls-tree", "-r", "-z", "--full-tree", commit)
    blobs, others = [], []
    for row in filter(None, listing.split(b"\0")):
        meta, path = row.split(b"\t", 1)
        mode, kind, oid = meta.decode().split()
        name = path.decode("utf-8")
        if kind == "blob" and mode in {"100644", "100755"}:
            blobs.append((name, mode, oid))
        else:
            others.append(dict(path=name, mode=mode, object=oid))
    output = git(repo, "cat-file", "--batch", stdin="".join(oid + "\n" for _, _, oid in blobs).encode())
    records, offset = [], 0
    for name, mode, oid in blobs:
        header_end = output.index(b"\n", offset)
        header_oid, _, size = output[offset:header_end].decode().split()
        if header_oid != oid:
            raise PacketError(f"git object stream out of order at {name}")
        start = header_end + 1
        raw = output[start:start + int(size)]
        offset = start + int(size) + 1
        target = destination / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(raw)
        records.append(dict(path=name, mode=mode, sha256=sha256(raw), size=len(raw)))
    return sorted(records, key=lambda r: r["path"]), sorted(others, key=lambda r: r["path"])


def tree_digest(records: list[dict]) -> str:
    return sha256("".join(f"{r['sha256']}  {r['path']}\n" for r in records).encode())


def depends_on(block: str, names: dict[str, str]) -> list[str]:
    start = block.find(".dependsOn(")
    if start < 0:
        return []
    depth, end = 0, start + len(".dependsOn")
    for end in range(start + len(".dependsOn"), len(block)):
        depth += {"(": 1, ")": -1}.get(block[end], 0)
        if depth == 0:
            break
    words = re.findall(r"\b(\w+)\b", block[start + len(".dependsOn("):end])
    return sorted({names[word] for word in words if word in names})


def source_map(root: Path, records: list[dict], others: list[dict]) -> dict:
    """Modules from build.sbt's `scarab(id, dir)` declarations, then every other top-level area."""
    build = (root / "build.sbt").read_text(encoding="utf-8") if (root / "build.sbt").is_file() else ""
    matches = list(MODULE.finditer(build))
    names = {m[1]: m[2] for m in matches}
    modules, claimed = [], set()
    for index, match in enumerate(matches):
        end = matches[index + 1].start() if index + 1 < len(matches) else len(build)
        following = build[match.end():end].split("\nlazy val ", 1)[0]
        directory = match[3] or match[2]
        files = [r for r in records if r["path"].startswith(directory + "/")]
        claimed.update(r["path"] for r in files)
        def count(prefix):
            return sum(r["path"].startswith(f"{directory}/src/{prefix}/") for r in files)
        modules.append(dict(id=match[2], directory=directory, dependsOn=depends_on(following, names),
                            mainFiles=count("main"), testFiles=count("test"), files=len(files),
                            digest=tree_digest(files)))
    areas = {}
    for record in records:
        if record["path"] not in claimed:
            top = record["path"].split("/", 1)[0] if "/" in record["path"] else "."
            areas.setdefault(top, []).append(record)
    return dict(modules=sorted(modules, key=lambda m: m["id"]),
                areas=[dict(area=area, files=len(rows), digest=tree_digest(rows))
                       for area, rows in sorted(areas.items())],
                nonFileEntries=others, files=len(records), digest=tree_digest(records))


def text_files(records: list[dict], prefixes: tuple[str, ...]) -> list[dict]:
    return [r for r in records if r["path"].startswith(prefixes) and not r["path"].startswith("scripts/tests/")]


def pins(root: Path, records: list[dict]) -> dict:
    """Digest-pinned images and actions, recipe digests and the sbt launcher, as the revision states them."""
    images, actions = {}, {}
    for record in text_files(records, PIN_ROOTS):
        raw = (root / record["path"]).read_bytes()
        for match in IMAGE_PIN.finditer(raw):
            key = (match[1].decode(), "sha256:" + match[2].decode())
            images.setdefault(key, set()).add(record["path"])
        if record["path"].startswith(".github/workflows/"):
            for match in ACTION_PIN.finditer(raw):
                actions.setdefault((match[1].decode(), match[2].decode()), set()).add(record["path"])
    recipes = []
    for record in records:
        if record["path"].startswith("docker/") and record["path"].endswith("Dockerfile"):
            lines = (root / record["path"]).read_text(encoding="utf-8").splitlines()
            recipes.append(dict(path=record["path"], sha256=record["sha256"],
                                bases=[line.split()[1] for line in lines if line.startswith("FROM ")]))
    launcher = {}
    if (root / "scripts/sbtw").is_file():
        text = (root / "scripts/sbtw").read_text(encoding="utf-8")
        for key in ("version", "sha"):
            found = re.search(rf"^{key}=(\S+)$", text, re.MULTILINE)
            launcher[key] = found[1] if found else None
    return dict(images=[dict(reference=name, digest=digest, files=sorted(files))
                        for (name, digest), files in sorted(images.items())],
                actions=[dict(action=name, commit=commit, files=sorted(files))
                         for (name, commit), files in sorted(actions.items())],
                recipes=recipes, sbtLauncher=launcher)


def module_constants(text: str) -> dict:
    """Top-level literal assignments, with `+` over earlier ones (`URL = PREFIX + NAME`); nothing runs."""
    def value(node, known):
        if isinstance(node, ast.Name) and node.id in known:
            return known[node.id]
        if isinstance(node, ast.BinOp) and isinstance(node.op, ast.Add):
            return value(node.left, known) + value(node.right, known)
        return ast.literal_eval(node)
    known = {}
    for statement in ast.parse(text).body:
        if (isinstance(statement, ast.Assign) and len(statement.targets) == 1
                and isinstance(statement.targets[0], ast.Name)):
            try:
                known[statement.targets[0].id] = value(statement.value, known)
            except (ValueError, TypeError, RecursionError):
                continue
    return known


def references(root: Path) -> tuple[Outcome, dict]:
    """Pinned upstream revisions (no network access) and the reference node release pins."""
    try:
        upstream = [dict(name=s.name, repository=s.repository, commit=s.commit, trackedRef=s.tracked_ref,
                         watchedPrefixes=sorted(s.prefixes))
                    for s in check_conformance_sources.sources(root)]
    except (OSError, ValueError, KeyError, TypeError) as error:
        return Outcome.FAILED, dict(upstream=[], error=type(error).__name__)
    script = root / "scripts/prepare_reference_image.py"
    constants = module_constants(script.read_text(encoding="utf-8")) if script.is_file() else {}
    node = {name: constants.get(name) for name in REFERENCE_NODE_PINS}
    invalid = invalid_references(upstream, node)
    outcome = Outcome.FAILED if invalid else Outcome.PASSED
    return outcome, dict(upstream=sorted(upstream, key=lambda u: u["name"]), referenceNode=node, invalid=invalid)


def invalid_references(upstream: list[dict], node: dict) -> list[str]:
    """Each missing or malformed pin by name; an empty list is the only passing result."""
    def text(value, pattern):
        return isinstance(value, str) and pattern.fullmatch(value) is not None
    rows = [] if upstream else ["upstream"]
    for source in upstream:
        if not text(source["commit"], COMMIT_ID):
            rows.append(f"upstream.{source['name']}.commit")
        if not text(source["repository"], REPOSITORY):
            rows.append(f"upstream.{source['name']}.repository")
        if not source["watchedPrefixes"] or not all(isinstance(p, str) and p for p in source["watchedPrefixes"]):
            rows.append(f"upstream.{source['name']}.watchedPrefixes")
    name, url, size, hashes = node["ARCHIVE_NAME"], node["ARCHIVE_URL"], node["ARCHIVE_SIZE"], node["BINARY_HASHES"]
    checks = {
        "BASE": text(node["BASE"], PINNED_IMAGE),
        "ARCHIVE_NAME": isinstance(name, str) and bool(name) and "/" not in name,
        "ARCHIVE_SHA256": text(node["ARCHIVE_SHA256"], SHA256_HEX),
        "ARCHIVE_SIZE": type(size) is int and size > 0,
        "ARCHIVE_URL": (isinstance(url, str) and url.startswith("https://") and isinstance(name, str)
                        and bool(name) and url.endswith("/" + name)),
        "BINARY_HASHES": (isinstance(hashes, dict) and bool(hashes)
                          and all(isinstance(k, str) and k and text(v, SHA256_HEX) for k, v in hashes.items())),
    }
    return sorted(rows) + [f"referenceNode.{field}" for field, ok in checks.items() if not ok]


def fixtures(root: Path, records: list[dict]) -> tuple[Outcome, dict]:
    """The recomputed inventory must equal the committed one, which is named by digest, not copied.

    The committed file's path+digest rows are allowlisted for the secret scanner at that path only
    (`.gitleaks.toml`); a copy elsewhere would be reported, and the reviewer has its bytes from the commit.
    """
    committed = next((r for r in records if r["path"] == check_test_coverage.INVENTORY), None)
    summary = dict(committed=check_test_coverage.INVENTORY, sha256=committed and committed["sha256"])
    try:
        computed = check_test_coverage.fixture_inventory(root)
        check_test_coverage.validate_inventory(root, check_test_coverage.read_json(root / check_test_coverage.INVENTORY))
    except (OSError, ValueError, KeyError, TypeError) as error:
        return Outcome.FAILED, dict(summary, matchesCommitted=False, error=type(error).__name__)
    return Outcome.PASSED, dict(summary, matchesCommitted=True, corpora=computed["corpora"],
                                files=len(computed["files"]))


def coverage(root: Path) -> tuple[Outcome, dict, bytes | None]:
    path = root / check_test_coverage.REGISTER
    try:
        register = check_test_coverage.read_json(path)
        contracts = check_test_coverage.validate_register(root, register)
    except (OSError, ValueError, KeyError, TypeError) as error:
        return Outcome.FAILED, dict(register=check_test_coverage.REGISTER, error=type(error).__name__), None
    open_rows = [dict(id=row["id"], classification=row["classification"], status=row["status"],
                      tracking=row["tracking"], candidateRequired=row["candidateRequired"])
                 for row in register["entries"] if row["status"] != "covered"]
    return Outcome.PASSED, dict(register=check_test_coverage.REGISTER, contracts=contracts,
                                notCovered=sorted(open_rows, key=lambda r: r["id"]),
                                candidateRequiredUnresolved=sorted(r["id"] for r in open_rows
                                                                   if r["candidateRequired"])), path.read_bytes()


def optional_data(root: Path, records: list[dict]) -> list[dict]:
    """`SCARAB_*` variables read by test sources: each selects data that the packet does not embed."""
    readers = {}
    for record in records:
        parts = PurePosixPath(record["path"]).parts
        if len(parts) > 3 and parts[1:3] == ("src", "test") and record["path"].endswith((".scala", ".java")):
            for match in SCARAB_ENV.finditer((root / record["path"]).read_bytes()):
                readers.setdefault(match[1].decode(), set()).add(record["path"])
    return [dict(variable=name, readBy=sorted(files)) for name, files in sorted(readers.items())]


def recorded(records: list[dict]) -> list[dict]:
    """Digest the evidence index, register and pages from the committed source inventory."""
    by_path = {r["path"]: r for r in records}
    pages = sorted(path for path in by_path
                   if path.startswith(("docs/compatibility/", "docs/performance/")) and path.endswith(".md"))
    return [dict(path=path, sha256=by_path[path]["sha256"])
            for path in (*RECORDED_EVIDENCE, *pages) if path in by_path]


def candidate(directory: Path | None, commit: str) -> tuple[Outcome, dict, dict[str, bytes]]:
    """Validated release-candidate SBOMs, copied; artifacts and other evidence by digest only."""
    if directory is None:
        return Outcome.NOT_SUPPLIED, dict(status=Outcome.NOT_SUPPLIED.value), {}
    root = directory.resolve()
    try:
        manifest = candidate_manifest.validate(root)
    except (OSError, ValueError, KeyError, TypeError) as error:
        raise CandidateMismatch(f"release candidate does not validate: {error}") from error
    if manifest["source"]["buildIdentity"]["gitCommit"] != commit:
        raise CandidateMismatch("release candidate was built from a different commit")
    toolchain = next(e["file"] for e in manifest["evidence"] if e["kind"] == "toolchain")
    jdk_image = candidate_manifest.read_json(root / toolchain["path"]).get("jdk", {}).get("jdkImage")
    if not isinstance(jdk_image, str) or IMAGE_ID.fullmatch(jdk_image) is None:
        raise CandidateMismatch("release candidate toolchain does not record an immutable JDK image ID")
    copies = {"sbom/candidate.json": (root / "candidate.json").read_bytes()}
    subjects = []
    for subject in sorted(manifest["subjects"], key=lambda s: s["kind"]):
        bom = subject["bom"]["file"]
        copies["sbom/" + PurePosixPath(bom["path"]).name] = (root / bom["path"]).read_bytes()
        subjects.append(dict(name=subject["name"], kind=subject["kind"], sha256=subject["file"]["sha256"],
                             size=subject["file"]["size"], bom=dict(bom, subject=subject["bom"]["subject"])))
    return Outcome.PASSED, dict(status=Outcome.PASSED.value, buildIdentity=manifest["source"]["buildIdentity"],
                                image=manifest["image"], assurance=manifest["assurance"], subjects=subjects,
                                jdkImage=dict(id=jdk_image, receipt=toolchain),
                                evidence=sorted(manifest["evidence"], key=lambda e: e["kind"])), copies


def forbidden_tokens(extra: tuple[str, ...] = ()) -> list[bytes]:
    """This machine's checkout and home paths, `extra` paths and the host name, as they would leak.

    The generic system temporary directory is not machine-specific and is not listed; the unique
    scratch and output directories of a run are passed in `extra`.
    """
    paths = {str(REPO), str(Path.home()), *extra}
    tokens = [p.encode() for p in paths if len(p.strip("/")) > 1]
    host = socket.gethostname().split(".", 1)[0]
    if len(host) > 2:
        tokens.append(host.encode())
    return sorted(set(tokens))


def decoded_strings(path: str, raw: bytes) -> bytes:
    """Every key and string value of a JSON member, decoded and one per line, so escapes hide nothing.

    An explicit stack keeps the walk independent of the member's nesting depth (ADR 0011).
    """
    try:
        stack = [json.loads(raw)]
    except (ValueError, UnicodeDecodeError) as error:
        raise UndecodableMember(f"{path}: not decodable JSON ({type(error).__name__})") from error
    rows = []
    while stack:
        value = stack.pop()
        if isinstance(value, dict):
            rows.extend(value)
            stack.extend(value.values())
        elif isinstance(value, list):
            stack.extend(value)
        elif isinstance(value, str):
            rows.append(value)
    return "\n".join(rows).encode("utf-8", "surrogatepass")


def views(members: dict[str, bytes]) -> list[tuple[str, bytes]]:
    """The original bytes of each member and, for JSON members, their decoded strings."""
    rows = []
    for path, raw in sorted(members.items()):
        rows.append((path, raw))
        if path.endswith(".json"):
            rows.append((path + " (decoded strings)", decoded_strings(path, raw)))
    return rows


def guard(members: dict[str, bytes], tokens: list[bytes]) -> None:
    """Fail on a home path, a listed local path or the host name in any member's bytes or decoded strings."""
    shown = views(members)
    rows = check_local_paths.findings(shown, allowed={})
    for path, raw in shown:
        for token in tokens:
            if re.search(rb"(?<![A-Za-z0-9_])" + re.escape(token) + rb"(?![A-Za-z0-9_])", raw, re.IGNORECASE):
                rows.append(f"{path}: records a machine-local path or the host name")
    if rows:
        raise ProvenanceLeak("packet members record machine-local values:\n" + "\n".join(rows))


SCAN_NAME = "scan/{index:06d}.txt"


def allowlist_paths(config: dict) -> list[str]:
    """Every `paths` pattern of the global and per-rule allowlists of a parsed gitleaks config."""
    lists = []
    for owner in [config, *config.get("rules", [])]:
        single = owner.get("allowlist")
        lists.extend([single] if isinstance(single, dict) else [])
        lists.extend(a for a in owner.get("allowlists", []) if isinstance(a, dict))
    return [pattern for allow in lists for pattern in allow.get("paths", [])]


def path_rules(config: dict) -> list[tuple[str, str]]:
    """Rules that detect by file path alone, such as `cardano-signing-key-file` (`*.skey`)."""
    return [(rule.get("id", ""), rule["path"]) for rule in config.get("rules", [])
            if isinstance(rule.get("path"), str) and not rule.get("regex")]


def scan_layout(members: dict[str, bytes], config: dict) -> dict[str, tuple[str, bytes]] | None:
    """Neutral scan file name -> (member view, bytes); None when the config could allowlist a scan path.

    Members are scanned under neutral names, not their packet paths, so no path allowlist of the
    repository config (which names repository files such as the committed fixture inventory) can
    exempt packet content. Path-only rules are applied to the real member paths here instead. A
    pattern Python cannot compile, an `[extend] path`, or a match on a neutral name means the scan
    cannot vouch for the packet.
    """
    if config.get("extend", {}).get("path"):
        return None
    shown = views(members)
    layout = {SCAN_NAME.format(index=index): view for index, view in enumerate(shown)}
    try:
        patterns = [re.compile(pattern) for pattern in allowlist_paths(config)]
        detectors = [(rule, re.compile(pattern)) for rule, pattern in path_rules(config)]
    except re.error:
        return None
    if any(pattern.search(name) for pattern in patterns for name in layout):
        return None
    hits = sorted(f"{path}: {rule}" for rule, pattern in detectors for path in members if pattern.search(path))
    if hits:
        raise SecretFound("secret scanner path rules match packet members:\n" + "\n".join(hits))
    return layout


def gitleaks(members: dict[str, bytes], workdir: Path) -> Outcome:
    """Scan the members and their decoded strings with CI's pinned gitleaks image and rules.

    A finding raises `SecretFound`; a scanner that cannot run or reports inconsistently is
    `UNAVAILABLE`, an incomplete gate, never a pass. The image is the one `check_secret_scan.py` pins.
    `--ignore-gitleaks-allow` keeps an inline `gitleaks:allow` marker in packet content from
    suppressing its own finding, and neutral scan names keep path allowlists from applying.
    """
    config_path = REPO / ".gitleaks.toml"
    try:
        layout = scan_layout(members, tomllib.loads(config_path.read_text(encoding="utf-8")))
    except (OSError, tomllib.TOMLDecodeError):
        return Outcome.UNAVAILABLE
    if layout is None:
        return Outcome.UNAVAILABLE
    tree = workdir / "members"
    for name, (_, content) in layout.items():
        target = tree / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(content)
    try:
        done = subprocess.run(
            ["docker", "run", "--rm", "--network", "none", "--user", f"{os.getuid()}:{os.getgid()}",
             "-e", "HOME=/tmp", "-v", f"{workdir}:/scan", "-v", f"{config_path}:/config.toml:ro",
             "-w", "/scan/members", check_secret_scan.IMAGE, "dir", ".", "--config", "/config.toml",
             "--ignore-gitleaks-allow", "--no-banner", "--redact", "--exit-code", "1",
             "--report-format", "json", "--report-path", "/scan/report.json"], capture_output=True)
    except OSError:
        return Outcome.UNAVAILABLE
    report = workdir / "report.json"
    try:
        findings = json.loads(report.read_text(encoding="utf-8")) if report.is_file() else None
    except ValueError:
        findings = None
    if not isinstance(findings, list):
        return Outcome.UNAVAILABLE
    if done.returncode == 0 and not findings:
        return Outcome.PASSED
    if done.returncode == 1 and findings:
        rows = sorted(f"{layout.get(str(row.get('File')), (row.get('File'),))[0]}:{row.get('StartLine')}: "
                      f"{row.get('RuleID')}" for row in findings)
        raise SecretFound("secret scanner findings (redacted):\n" + "\n".join(rows))
    return Outcome.UNAVAILABLE


def generate(repo: Path, revision: str, candidate_dir: Path | None, scratch: Path, tokens: list[bytes],
             scanner) -> dict[str, bytes]:
    """All members, guarded and secret-scanned; `scanner(members, workdir)` returns the scan outcome."""
    identity = resolve(repo, revision)
    root = scratch / "tree"
    records, others = materialize(repo, identity["commit"], root)
    gates = {}
    members = {}
    sources = source_map(root, records, others)
    gates[Gate.SOURCE] = Outcome.PASSED if sources["modules"] else Outcome.FAILED
    members["packet/source-map.json"] = canonical(sources)
    members["packet/source-files.json"] = canonical(dict(commit=identity["commit"], files=records))
    gates[Gate.FIXTURES], fixture_summary = fixtures(root, records)
    gates[Gate.COVERAGE], coverage_summary, register = coverage(root)
    if register is not None:
        members["packet/coverage/test-coverage-register.json"] = register
    gates[Gate.REFERENCES], reference_pins = references(root)
    gates[Gate.SBOM], sbom, copies = candidate(candidate_dir, identity["commit"])
    members.update({"packet/" + name: raw for name, raw in copies.items()})
    jdk_image = sbom.get("jdkImage")
    members["packet/commands.json"] = canonical(dict(
        commands=[dict(family=c.family.value, command=c.command, environment=c.environment.value,
                       definedIn=defined_in(root, records, c),
                       jdkImageId=jdk_image["id"] if jdk_image and runs_in_jdk_image(c) else None)
                  for c in commands(identity["commit"])],
        jdkImage=jdk_image, images=pins(root, records)))
    members["packet/references.json"] = canonical(reference_pins)
    members["packet/inventories.json"] = canonical(dict(
        fixtures=fixture_summary, coverage=coverage_summary, optionalData=optional_data(root, records),
        recordedEvidence=dict(scope="Belongs to its recorded source revisions; not a run of this candidate.",
                              files=recorded(records)),
        candidateSbom=sbom))
    guard(members, tokens)
    gates[Gate.SECRETS] = scanner(members, scratch / "scan-members")
    generator = [dict(path=name, sha256=sha256((REPO / name).read_bytes())) for name in GENERATOR_FILES]
    manifest = dict(schema=SCHEMA, revision=identity, scope=SCOPE, exclusions=EXCLUSIONS, generator=generator,
                    gates=[dict(gate=gate.value, outcome=outcome.value) for gate, outcome in
                           sorted(gates.items(), key=lambda item: item[0].value)],
                    complete=all(outcome is Outcome.PASSED for outcome in gates.values()),
                    members=[dict(path=path, sha256=sha256(raw), size=len(raw)) for path, raw in sorted(members.items())])
    final = {"packet/manifest.json": canonical(manifest)}
    guard(final, tokens)
    if gates[Gate.SECRETS] is Outcome.PASSED and scanner(final, scratch / "scan-manifest") is not Outcome.PASSED:
        raise SecretFound("the secret scanner did not pass the manifest it had already passed the members for")
    return members | final


def archive(members: dict[str, bytes]) -> bytes:
    """A plain ustar archive with sorted members and fixed metadata: no clock, owner or host values."""
    stream = io.BytesIO()
    with tarfile.open(fileobj=stream, mode="w", format=tarfile.USTAR_FORMAT) as tar:
        for path, raw in sorted(members.items()):
            info = tarfile.TarInfo(path)
            info.size, info.mode, info.mtime = len(raw), TAR_MODE, 0
            info.uid = info.gid = 0
            info.uname = info.gname = ""
            tar.addfile(info, io.BytesIO(raw))
    return stream.getvalue()


def fresh_output(output: Path, repo: Path) -> Path:
    target = output.resolve()
    if any(target.is_relative_to(checkout.resolve()) for checkout in (repo, REPO)):
        raise UnsafeOutput("packet output must be outside the source checkout")
    if target.exists():
        raise UnsafeOutput("packet output must be a fresh directory")
    return target


def build(repo: Path, revision: str, output: Path, candidate_dir: Path | None, check: bool,
          allow_incomplete: bool, scanner=None) -> tuple[Path, str, bool]:
    """`scanner` defaults to `gitleaks`; tests substitute a controlled outcome."""
    scanner = scanner or gitleaks
    target = fresh_output(output, repo)
    tokens = forbidden_tokens((str(target), str(repo.resolve())))
    runs = []
    for _ in range(2 if check else 1):
        with tempfile.TemporaryDirectory(prefix="evidence-packet-") as scratch:
            members = generate(repo, revision, candidate_dir, Path(scratch), tokens + [scratch.encode()], scanner)
            runs.append((members, archive(members)))
    if check and runs[0][1] != runs[1][1]:
        raise NotReproducible("two generations of the same revision produced different archives")
    members, packed = runs[0]
    manifest = json.loads(members["packet/manifest.json"])
    if not manifest["complete"] and not allow_incomplete:
        failed = [g["gate"] for g in manifest["gates"] if g["outcome"] != Outcome.PASSED.value]
        raise Incomplete(f"incomplete gates: {', '.join(failed)} (use --allow-incomplete to keep the packet)")
    target.mkdir(parents=True)
    for path, raw in members.items():
        destination = target / path
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(raw)
    name = f"reviewer-evidence-{manifest['revision']['commit']}.tar"
    (target / name).write_bytes(packed)
    digest = sha256(packed)
    (target / "SHA256SUMS").write_text(f"{digest}  {name}\n{sha256(members['packet/manifest.json'])}  "
                                       "packet/manifest.json\n", encoding="utf-8")
    return target / name, digest, manifest["complete"]


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--revision", required=True, help="tag or commit; branch names resolve once to a commit")
    parser.add_argument("--output", required=True, type=Path, help="fresh directory outside the checkout")
    parser.add_argument("--candidate", type=Path, help="verified build_release_candidate.py output of the same commit")
    parser.add_argument("--repo", type=Path, default=REPO)
    parser.add_argument("--check", action="store_true", help="generate twice and require identical archives")
    parser.add_argument("--allow-incomplete", action="store_true",
                        help="keep a packet whose gates are not all passed; it stays labelled incomplete")
    args = parser.parse_args(argv)
    try:
        path, digest, complete = build(args.repo, args.revision, args.output, args.candidate, args.check,
                                       args.allow_incomplete)
    except (PacketError, subprocess.CalledProcessError) as error:
        print(f"evidence packet failed: {error}", file=sys.stderr)
        return 1
    print(f"{'complete' if complete else 'INCOMPLETE'} evidence packet {digest}  {path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
