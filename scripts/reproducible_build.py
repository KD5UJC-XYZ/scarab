#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Export an immutable revision and run sbt's clean artifact comparison in one JDK image."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import tempfile
import uuid
import zipfile


MANIFEST = ".reproducible-source.json"


def sha256(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def compare(first, second):
    if sha256(first) == sha256(second):
        return
    with zipfile.ZipFile(first) as left, zipfile.ZipFile(second) as right:
        names = sorted(set(left.namelist()) | set(right.namelist()))
        changed = [name for name in names if name not in left.namelist()
                   or name not in right.namelist() or left.read(name) != right.read(name)]
    detail = ", ".join(changed[:20]) or "ZIP metadata or entry ordering"
    raise ValueError(f"artifacts differ: {detail}")


def copy_source(source, destination, manifest):
    destination.mkdir(parents=True)
    for name, expected in manifest["files"].items():
        original = source / name
        if original.is_symlink() or not original.is_file() or sha256(original) != expected:
            raise ValueError(f"frozen source changed or is not a regular file: {name}")
        target = destination / name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(original, target)
    metadata = destination / ".git"
    metadata.mkdir()
    (metadata / "HEAD").write_text(manifest["gitCommit"] + "\n", encoding="utf-8")


def resource_directories(runtime, classpath):
    source = runtime / "ResourceDirectoryCheck.java"
    source.write_text("""// SPDX-License-Identifier: Apache-2.0
class ResourceDirectoryCheck {
  public static void main(String[] args) {
    final String[][] paths = {
      {"xyz/kd5ujc/scarab/node/", "Main.class"},
      {"supranational/blst/Linux/amd64/", "libblst.so"}
    };
    for (final String[] path : paths) {
      final ClassLoader loader = ClassLoader.getSystemClassLoader();
      final java.net.URL directory = loader.getResource(path[0]);
      final java.net.URL member = loader.getResource(path[0] + path[1]);
      if (directory == null || member == null ||
          !member.toExternalForm().startsWith(directory.toExternalForm())) {
        throw new IllegalStateException("directory resource disagrees with member: " + path[0]);
      }
    }
  }
}
""", encoding="utf-8")
    result = subprocess.run(["java", "-cp", classpath, source.name], cwd=runtime,
                            text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    if result.returncode:
        raise ValueError(f"packaged directory-resource check failed: {result.stdout}")


def inside_jdk():
    source = Path.cwd()
    manifest = json.loads((source / MANIFEST).read_text(encoding="utf-8"))
    if not manifest["files"] or len(manifest["gitCommit"]) != 40:
        raise ValueError("a frozen git source export is required; use the host entrypoint")
    output = Path(os.environ["SCARAB_REPRO_OUTPUT"])
    output.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="scarab-repro-") as scratch:
        locations = [Path(scratch) / "first", Path(scratch) / "different" / "depth" / "second"]
        artifacts = []
        for index, location in enumerate(locations, 1):
            copy_source(source, location, manifest)
            if any(location.rglob("target")):
                raise ValueError("clean source export unexpectedly contains target outputs")
            log = output / f"build-{index}.log"
            env = dict(os.environ, SBT_GLOBAL_BASE=f"/tmp/sbt-repro-{index}")
            print(f"Clean build {index}/2: {location}; log {log}", flush=True)
            tasks = (["check"] if index == 1 and os.environ.get("SCARAB_REPRO_CHECK") == "1" else [])
            with log.open("w", encoding="utf-8") as stream:
                result = subprocess.run(["bash", "scripts/sbtw", *tasks, "node/releaseArchive"],
                                        cwd=location, env=env, stdout=stream, stderr=subprocess.STDOUT)
            if result.returncode:
                print("\n".join(log.read_text(encoding="utf-8").splitlines()[-80:]), flush=True)
                raise ValueError(f"clean build {index} failed with exit {result.returncode}")
            produced = list((location / "node/target/release").glob("*.zip"))
            if len(produced) != 1:
                raise ValueError(f"expected one runtime archive, found {produced}")
            artifact = output / f"build-{index}.zip"
            shutil.copyfile(produced[0], artifact)
            runtime = location / "packaged-runtime"
            with zipfile.ZipFile(artifact) as archive:
                archive.extractall(runtime)
            classpath = (runtime / "classpath.txt").read_text(encoding="utf-8").strip()
            if any(Path(entry).is_absolute() or not (runtime / entry).is_file()
                   for entry in classpath.split(":")):
                raise ValueError("runtime classpath contains an absolute or missing entry")
            resource_directories(runtime, classpath)
            smoke = subprocess.run(["sh", "scarab", "--help"], cwd=runtime,
                                   text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
            if smoke.returncode != 2 or "usage: follow" not in smoke.stdout:
                raise ValueError(f"packaged launcher smoke check failed: {smoke.stdout}")
            native = subprocess.run(["java", "-cp", classpath,
                                     "xyz.kd5ujc.scarab.node.tools.BlstCheckMain"], cwd=runtime,
                                    text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
            if native.returncode or not any(
                    line.startswith("{") and json.loads(line).get("ok")
                    and json.loads(line).get("library") == "portable"
                    for line in native.stdout.splitlines()):
                raise ValueError(f"packaged native-resource check failed: {native.stdout}")
            print(f"Build {index}: directory, launcher and portable native-resource smoke checks passed", flush=True)
            artifacts.append(artifact)
            print(f"Build {index}: {sha256(artifact)}", flush=True)
        compare(*artifacts)
        print(f"PASS: two clean builds of {manifest['gitCommit']} have identical bytes", flush=True)


def export_source(repo, revision, output):
    commit = subprocess.check_output(
        ["git", "rev-parse", "--verify", "--end-of-options", revision + "^{commit}"],
        cwd=repo, text=True).strip()
    archive = output.parent / "source.tar"
    with archive.open("wb") as stream:
        subprocess.run(["git", "archive", "--format=tar", commit], cwd=repo, stdout=stream, check=True)
    output.mkdir()
    with tarfile.open(archive) as tree:
        tree.extractall(output, filter="data")
    files = {str(path.relative_to(output)): sha256(path)
             for path in sorted(output.rglob("*")) if path.is_file()}
    manifest = {"gitCommit": commit, "files": files}
    (output / MANIFEST).write_text(json.dumps(manifest, sort_keys=True), encoding="utf-8")
    (output / ".git").mkdir()
    (output / ".git/HEAD").write_text(commit + "\n", encoding="utf-8")
    return commit


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--revision", default="HEAD", help="immutable commit to export (default HEAD)")
    parser.add_argument("--image", default="scarab-jdk:local", help="JDK image; resolved to its image ID")
    parser.add_argument("--cache", type=Path, default=Path(os.environ.get("SCARAB_CACHE_DIR", ".cache")))
    parser.add_argument("--output", type=Path, help="fresh directory for two artifacts and build logs")
    parser.add_argument("--check", action="store_true", help="also run the full check gate in the first clean build")
    parser.add_argument("--inside-jdk", action="store_true", help=argparse.SUPPRESS)
    args = parser.parse_args()
    if args.inside_jdk:
        inside_jdk()
        return
    repo = Path(__file__).resolve().parent.parent
    output = (args.output or Path(tempfile.gettempdir()) / f"scarab-repro-{uuid.uuid4().hex}").resolve()
    output.mkdir(parents=True, exist_ok=False)
    cache = args.cache.resolve()
    cache.mkdir(parents=True, exist_ok=True)
    image = subprocess.check_output(
        ["docker", "image", "inspect", args.image, "--format", "{{.Id}}"], text=True).strip()
    with tempfile.TemporaryDirectory(prefix="scarab-frozen-source-") as scratch:
        source = Path(scratch) / "source"
        commit = export_source(repo, args.revision, source)
        print(f"Source {commit}; JDK image {image}; output {output}", flush=True)
        (output / "build-environment.json").write_text(
            json.dumps({"gitCommit": commit, "jdkImage": image}, sort_keys=True) + "\n", encoding="utf-8")
        subprocess.run([
            "docker", "run", "--rm", "--name", f"scarab-repro-{uuid.uuid4().hex[:12]}",
            "--user", f"{os.getuid()}:{os.getgid()}", "-v", f"{source}:/source",
            "-v", f"{cache}:/cache", "-v", f"{output}:/output", "-w", "/source",
            "-e", "HOME=/cache/home", "-e", "SCARAB_CACHE_DIR=/cache",
            "-e", "SBT_GLOBAL_BASE=/tmp/sbt-repro-orchestrator", "-e", "SCARAB_REPRO_OUTPUT=/output",
            "-e", "TZ=UTC", "-e", "LC_ALL=C.UTF-8",
            "-e", f"SCARAB_REPRO_CHECK={int(args.check)}", image,
            "bash", "scripts/sbtw", "reproducibleCheck"
        ], check=True)


if __name__ == "__main__":
    main()
