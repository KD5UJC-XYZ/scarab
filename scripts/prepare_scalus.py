#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Build the SHA-pinned Scalus CEK hooks in an isolated, immutable Maven repository.

Called by sbtw inside its JDK environment. No published dependency is overwritten.
The source archive and completed repository are reusable dependency caches; staging
source and compiler outputs are removed after publication or failure.
"""
from __future__ import annotations

import argparse
import contextlib
import enum
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
import urllib.request
import zipfile
import xml.etree.ElementTree as ET

SOURCE_ROOT = Path("/tmp")

INPUTS = ("patches/scalus/manifest.json", "patches/scalus/cek-hooks.patch", "scripts/prepare_scalus.py")


class Problem(enum.Enum):
    HASH = "pinned input or cached artifact hash differs"
    PATCH = "patch does not exactly match the pinned source"
    OFFLINE = "offline dependency cache is incomplete"
    TOOLCHAIN = "the JDK differs from the pinned build toolchain"
    BUILD = "the upstream dependency build failed"
    PUBLICATION = "the upstream publication is not the expected artifact"
    CACHE = "the immutable dependency cache is invalid"


class BootstrapError(Exception):
    def __init__(self, kind: Problem, path: Path | str, detail: str = ""):
        self.kind, self.path, self.detail = kind, str(path), detail
        super().__init__(f"{kind.value}: {path}" + (f" ({detail})" if detail else ""))


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def check_hash(path: Path, expected: str) -> None:
    if not path.is_file() or sha(path.read_bytes()) != expected:
        raise BootstrapError(Problem.HASH, path)


def input_digest(root: Path) -> str:
    """Same framed SHA256 as project/ScalusPatch.scala; helper changes invalidate cache."""
    digest = hashlib.sha256()
    for name in INPUTS:
        data = (root / name).read_bytes()
        digest.update(len(data).to_bytes(8, "big"))
        digest.update(data)
    return digest.hexdigest()


def apply_patch(source: bytes, patch: str, relative: str) -> bytes:
    """One file, exact line/context matching, no offset search, fuzz, or shell patch tool."""
    original = source.decode().splitlines(keepends=True)
    lines = patch.splitlines(keepends=True)
    if lines[:2] != [f"--- a/{relative}\n", f"+++ b/{relative}\n"]:
        raise BootstrapError(Problem.PATCH, relative, "unexpected file header")
    result, at, cursor = [], 0, 2
    while cursor < len(lines):
        match = re.fullmatch(r"@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@.*\n", lines[cursor])
        if match is None:
            raise BootstrapError(Problem.PATCH, relative, "invalid hunk header")
        start, old_count, new_start, new_count = (int(match[1]) - 1, int(match[2] or 1),
                                                int(match[3]) - 1, int(match[4] or 1))
        if start < at or start > len(original):
            raise BootstrapError(Problem.PATCH, relative, "overlapping or out-of-range hunk")
        result.extend(original[at:start])
        if len(result) != new_start:
            raise BootstrapError(Problem.PATCH, relative, "new hunk offset differs")
        at, removed, added = start, 0, 0
        cursor += 1
        while cursor < len(lines) and not lines[cursor].startswith("@@ "):
            line = lines[cursor]
            if line[:1] not in (" ", "+", "-"):
                raise BootstrapError(Problem.PATCH, relative, "unsupported hunk line")
            if line[0] in " -":
                if at >= len(original) or original[at] != line[1:]:
                    raise BootstrapError(Problem.PATCH, relative, "source context differs")
                at += 1
                removed += 1
            if line[0] in " +":
                result.append(line[1:])
                added += 1
            cursor += 1
        if (removed, added) != (old_count, new_count):
            raise BootstrapError(Problem.PATCH, relative, "hunk counts differ")
    return "".join(result + original[at:]).encode()


def download(url: str, destination: Path, expected: str, offline: bool) -> None:
    if destination.exists():
        check_hash(destination, expected)
        return
    if offline:
        raise BootstrapError(Problem.OFFLINE, destination)
    destination.parent.mkdir(parents=True, exist_ok=True)
    descriptor, name = tempfile.mkstemp(dir=destination.parent, prefix="download-")
    temporary = Path(name)
    try:
        with os.fdopen(descriptor, "wb") as output, urllib.request.urlopen(url, timeout=60) as response:
            shutil.copyfileobj(response, output)
        check_hash(temporary, expected)
        temporary.replace(destination)
    finally:
        temporary.unlink(missing_ok=True)


def check_jdk(manifest: dict) -> None:
    result = subprocess.run(["java", "-XshowSettings:properties", "-version"],
                            capture_output=True, text=True, check=False)
    properties = dict(re.findall(r"^\s*(java\.(?:version|vendor|runtime.version)) = (.+)$",
                                 result.stderr, re.MULTILINE))
    expected = {"java.version": manifest["javaVersion"], "java.vendor": manifest["javaVendor"],
                "java.runtime.version": manifest["javaRuntimeVersion"]}
    if result.returncode or properties != expected:
        raise BootstrapError(Problem.TOOLCHAIN, "java", json.dumps(properties, sort_keys=True))


def normalize_jar(path: Path) -> None:
    """Stable ZIP metadata; preserve every class/resource byte and reject signed inputs."""
    temporary = path.with_suffix(".normalized")
    try:
        with zipfile.ZipFile(path) as source, zipfile.ZipFile(temporary, "w", zipfile.ZIP_STORED) as output:
            names = source.namelist()
            if len(names) != len(set(names)):
                raise BootstrapError(Problem.PUBLICATION, path, "duplicate ZIP members")
            for name in sorted(names):
                if name.upper().startswith("META-INF/") and name.upper().endswith((".SF", ".RSA", ".DSA")):
                    raise BootstrapError(Problem.PUBLICATION, path, "signed ZIP cannot be normalized")
                info = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
                info.create_system = 3
                info.external_attr = (0o40755 if name.endswith("/") else 0o100644) << 16
                output.writestr(info, source.read(name))
        temporary.replace(path)
    finally:
        temporary.unlink(missing_ok=True)


def original_artifact(cache: Path, pin: dict, offline: bool) -> Path:
    # Reuse the original Coursier input when available; never rewrite a shared download.
    relative = pin["url"].removeprefix("https://")
    original = cache / "coursier/https" / relative
    if original.exists():
        check_hash(original, pin["sha256"])
        return original
    cached = cache / "patched-scalus/downloads" / Path(relative).name
    download(pin["url"], cached, pin["sha256"], offline)
    return cached


def compose_jar(original: Path, compiled: Path, members: list[str]) -> dict:
    """One jar, exact CEK allowlist; all other released member bytes remain unchanged."""
    family = re.compile(r"scalus/uplc/eval/CekMachine(?:\$[^/]*)?\.(?:class|tasty)")
    allowed = set(members)
    temporary = compiled.with_suffix(".composed")
    try:
        with zipfile.ZipFile(original) as base, zipfile.ZipFile(compiled) as built:
            for archive in (base, built):
                names = archive.namelist()
                if len(names) != len(set(names)) or {n for n in names if family.fullmatch(n)} != allowed:
                    raise BootstrapError(Problem.PUBLICATION, compiled, "CEK compilation family differs from exact allowlist")
            with zipfile.ZipFile(temporary, "w", zipfile.ZIP_STORED) as output:
                for name in base.namelist():
                    output.writestr(name, built.read(name) if name in allowed else base.read(name))
            replacements = {name: {"original": sha(base.read(name)), "patched": sha(built.read(name))}
                            for name in sorted(allowed)}
        temporary.replace(compiled)
        normalize_jar(compiled)
        with zipfile.ZipFile(original) as base, zipfile.ZipFile(compiled) as final:
            if set(base.namelist()) != set(final.namelist()):
                raise BootstrapError(Problem.PUBLICATION, compiled, "released member inventory changed")
            if any(base.read(n) != final.read(n) for n in base.namelist() if n not in allowed):
                raise BootstrapError(Problem.PUBLICATION, compiled, "unlisted released member changed")
        return replacements
    finally:
        temporary.unlink(missing_ok=True)


def compose_pom(original: Path, output: Path, version: str) -> None:
    namespace = "http://maven.apache.org/POM/4.0.0"
    root = ET.fromstring(original.read_bytes())
    entry = root.find("{" + namespace + "}version")
    if entry is None or entry.text != "1.4.0":
        raise BootstrapError(Problem.PUBLICATION, original, "unexpected released version")
    entry.text = version
    ET.register_namespace("", namespace)
    output.write_bytes(ET.tostring(root, encoding="utf-8", xml_declaration=True) + b"\n")


def validate_pom(path: Path, version: str) -> None:
    root = ET.fromstring(path.read_bytes())
    ns = {"m": "http://maven.apache.org/POM/4.0.0"}
    actual = tuple(root.findtext(f"m:{key}", namespaces=ns) for key in ("groupId", "artifactId", "version"))
    if actual != ("org.scalus", "scalus_3", version):
        raise BootstrapError(Problem.PUBLICATION, path, repr(actual))
    crypto = [d.findtext("m:version", namespaces=ns) for d in root.findall("m:dependencies/m:dependency", ns)
              if d.findtext("m:artifactId", namespaces=ns) == "scalus-crypto-jni"]
    if crypto != ["0.1.0"]:
        raise BootstrapError(Problem.PUBLICATION, path, "native crypto dependency changed")


def validate_cache(entry: Path, digest: str, version: str) -> dict:
    receipt_path = entry / "receipt.json"
    try:
        receipt = json.loads(receipt_path.read_text())
    except (OSError, ValueError) as error:
        raise BootstrapError(Problem.CACHE, receipt_path) from error
    expected_base = f"repository/org/scalus/scalus_3/{version}/scalus_3-{version}"
    expected_files = {expected_base + ".jar", expected_base + ".pom"}
    if (receipt.get("inputDigest") != digest or receipt.get("version") != version
            or set(receipt.get("files", {})) != expected_files):
        raise BootstrapError(Problem.CACHE, receipt_path, "identity or inventory differs")
    for name, expected in receipt["files"].items():
        check_hash(entry / name, expected)
    validate_pom(entry / (expected_base + ".pom"), version)
    return receipt


def sbt_string(value: str) -> str:
    return json.dumps(value)


@contextlib.contextmanager
def fixed_source(digest: str):
    """Scalus's compiler embeds absolute source paths: use one stable, exclusively owned path."""
    path = SOURCE_ROOT / ("scarab-scalus-source-" + digest)
    with Path(str(path) + ".lock").open("a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        if path.exists():
            raise BootstrapError(Problem.CACHE, path, "source directory already exists; ownership must be checked")
        path.mkdir()
        try:
            yield path
        finally:
            shutil.rmtree(path)


def build(root: Path, cache: Path, launcher: Path, staging: Path, manifest: dict,
          digest: str, version: str, processors: int, offline: bool) -> None:
    with fixed_source(digest) as source_root:
        build_at(root, cache, launcher, staging, source_root, manifest, digest, version, processors, offline)


def build_at(root: Path, cache: Path, launcher: Path, staging: Path, source_root: Path,
             manifest: dict, digest: str, version: str, processors: int, offline: bool) -> None:
    archive = cache / "patched-scalus/downloads" / (manifest["commit"] + ".tar.gz")
    download(manifest["archiveUrl"], archive, manifest["archiveSha256"], offline)
    with tarfile.open(archive) as bundle:
        bundle.extractall(source_root, filter="data")
    source = source_root / ("scalus-" + manifest["commit"])
    if (source / "project/build.properties").read_text().strip() != "sbt.version=" + manifest["sbtVersion"]:
        raise BootstrapError(Problem.TOOLCHAIN, source / "project/build.properties")
    target = source / manifest["sourcePath"]
    check_hash(target, manifest["sourceSha256"])
    patch = root / "patches/scalus/cek-hooks.patch"
    check_hash(patch, manifest["patchSha256"])
    target.write_bytes(apply_patch(target.read_bytes(), patch.read_text(), manifest["sourcePath"]))
    patched_hash = sha(target.read_bytes())
    repository = staging / "repository"
    # Upstream sbt-ci-release may override publication defaults: make the exact file repository
    # and release coordinate explicit, then omit docs/source artifacts from this runtime package.
    settings = [
        f'set ThisBuild / version := {sbt_string(version)}',
        f'set ThisBuild / scalaVersion := {sbt_string(manifest["scalaVersion"])}',
        f'set scalus.jvm / publishTo := Some(Resolver.file("scarab-pinned", file({sbt_string(str(repository))}))(Resolver.mavenStylePatterns))',
        'set scalus.jvm / publishMavenStyle := true',
        'set scalus.jvm / Compile / packageDoc / publishArtifact := false',
        'set scalus.jvm / Compile / packageSrc / publishArtifact := false',
        'scalusJVM/publish',
    ]
    if offline:
        settings.insert(0, 'set ThisBuild / offline := true')
    command = ["java", f"-XX:ActiveProcessorCount={processors}", "-Xms256m", "-Xmx3g", "-Xss8m",
               "-Dsbt.server.autostart=false", "-Dsbt.supershell=false",
               f"-Dsbt.global.base={staging / 'global'}", f"-Dsbt.ivy.home={cache / 'ivy'}",
               f"-Dsbt.boot.directory={cache / 'boot'}", "-Dsbt.override.build.repos=true",
               f"-Dsbt.repository.config={root / 'project/repositories'}", "-jar", str(launcher), *settings]
    environment = dict(os.environ, COURSIER_CACHE=str(cache / "coursier"),
                       SOURCE_DATE_EPOCH=str(manifest["sourceDateEpoch"]))
    print(f"Building pinned Scalus {version}", file=sys.stderr, flush=True)
    result = subprocess.run(command, cwd=source, env=environment, stdout=sys.stderr, check=False)
    if result.returncode:
        raise BootstrapError(Problem.BUILD, source, f"exit {result.returncode}")
    base = repository / "org/scalus/scalus_3" / version / ("scalus_3-" + version)
    jar, pom = Path(str(base) + ".jar"), Path(str(base) + ".pom")
    if not jar.is_file() or not pom.is_file():
        raise BootstrapError(Problem.PUBLICATION, base, "jar or POM missing")
    validate_pom(pom, version)
    base_jar = original_artifact(cache, manifest["baseJar"], offline)
    base_pom = original_artifact(cache, manifest["basePom"], offline)
    replacements = compose_jar(base_jar, jar, manifest["replacementMembers"])
    compose_pom(base_pom, pom, version)
    validate_pom(pom, version)
    files = {str(p.relative_to(staging)): sha(p.read_bytes()) for p in (jar, pom)}
    for path in (jar, pom):
        for suffix, algorithm in ((".sha1", hashlib.sha1), (".md5", hashlib.md5)):
            Path(str(path) + suffix).write_text(algorithm(path.read_bytes()).hexdigest())
    (staging / "receipt.json").write_text(json.dumps({
        "format": 1, "inputDigest": digest, "version": version,
        "sourceCommit": manifest["commit"], "archiveSha256": manifest["archiveSha256"],
        "patchSha256": manifest["patchSha256"], "patchedSourceSha256": patched_hash,
        "baseJarSha256": manifest["baseJar"]["sha256"], "basePomSha256": manifest["basePom"]["sha256"],
        "replacements": replacements,
        "javaRuntimeVersion": manifest["javaRuntimeVersion"], "scalaVersion": manifest["scalaVersion"],
        "sbtVersion": manifest["sbtVersion"], "files": files,
    }, indent=2, sort_keys=True) + "\n")
    shutil.rmtree(staging / "global", ignore_errors=True)


def prepare(root: Path, cache: Path, launcher: Path, processors: int, offline: bool) -> Path:
    manifest = json.loads((root / INPUTS[0]).read_text())
    check_hash(root / INPUTS[1], manifest["patchSha256"])
    if manifest["sourceRoot"] != str(SOURCE_ROOT):
        raise BootstrapError(Problem.TOOLCHAIN, SOURCE_ROOT, "fixed source root differs")
    check_jdk(manifest)
    digest = input_digest(root)
    version = manifest["version"] + "-scarab-" + digest
    parent = cache / "patched-scalus"
    parent.mkdir(parents=True, exist_ok=True)
    entry = parent / digest
    with (parent / (digest + ".lock")).open("a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        if entry.exists():
            validate_cache(entry, digest, version)
        else:
            if offline:
                raise BootstrapError(Problem.OFFLINE, entry)
            with tempfile.TemporaryDirectory(prefix=digest + "-staging-", dir=parent) as directory:
                staging = Path(directory)
                build(root, cache, launcher, staging, manifest, digest, version, processors, offline)
                validate_cache(staging, digest, version)
                staging.rename(entry)
    repositories = entry / "repositories"
    expected = ((root / "project/repositories").read_text().rstrip() +
                "\nscarab-pinned-scalus: " + (entry / "repository").as_uri() + "\n")
    # The configuration names its final location and is not part of the published artifact.
    if not repositories.exists() or repositories.read_text() != expected:
        descriptor, name = tempfile.mkstemp(prefix="repositories-", dir=entry)
        with os.fdopen(descriptor, "w") as output:
            output.write(expected)
        Path(name).replace(repositories)
    return repositories


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cache", type=Path, required=True)
    parser.add_argument("--launcher", type=Path, required=True)
    parser.add_argument("--processors", type=int, default=4)
    parser.add_argument("--offline", action="store_true")
    args = parser.parse_args()
    if args.processors < 1:
        parser.error("--processors must be positive")
    root = Path(__file__).resolve().parent.parent
    try:
        print(prepare(root, args.cache.resolve(), args.launcher.resolve(), args.processors, args.offline))
    except BootstrapError as error:
        print(error, file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
