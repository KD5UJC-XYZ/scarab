#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Provision reviewed offline inputs and retain an exact mandatory-case acceptance receipt."""
from __future__ import annotations

import argparse
import hashlib
import gzip
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import signal
import uuid
import subprocess
import tarfile
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET


class Rejected(ValueError):
    """A closed candidate admission or evidence check failed."""


INPUT_KINDS = {
    "SCARAB_MAINNET_GENESIS_DIR": "directory", "SCARAB_K1_DB": "directory",
    "SCARAB_NATIVE_K1_DB": "directory",
    "HASKELL_STATE_FILE": "file", "SCARAB_MEMPACK_TABLES": "file",
    "SCARAB_MEMPACK_CHECKPOINT": "directory", "SCARAB_IMPORT_FIXTURE": "directory",
    "SCARAB_EXPORT_DB": "directory", "SCARAB_EXPORT_FIXTURE": "directory",
    "SCARAB_MITHRIL_DB": "directory", "SCARAB_MITHRIL_GENESIS": "directory",
    "SCARAB_PLUTUS_CAPTURE": "directory", "SCARAB_PLUTUS_BASELINE": "directory",
    "SCARAB_PREVIEW_CAPTURE": "directory", "SCARAB_PREVIEW_SNAPSHOT": "file",
    "SCARAB_PREVIEW_CHECKPOINT": "directory", "SCARAB_PREVIEW_GENESIS": "directory",
    "SCARAB_PREVIEW_REFERENCE": "directory",
}
OUTPUTS = {
    "SCARAB_IMPORT_DB": "import-db", "SCARAB_EXPORT_OUT": "export",
    "SCARAB_MITHRIL_DUMP": "mithril-dump", "SCARAB_PLUTUS_OUTPUT": "plutus",
    "SCARAB_PREVIEW_OUTPUT": "preview",
}
TABLE_SHA256 = "62ee546d9cf0c47f95444c213fb1bd55509a29a3f08cf8a9e9afa22bbaa5c2ed"
LIMITS = {"maxBytes": 100 * 1024**3, "maxUnpackedBytes": 500 * 1024**3, "maxEntries": 200000}


def require(condition, reason):
    if not condition:
        raise Rejected(reason)


def sha256(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def load_json(path):
    def unique(pairs):
        result = {}
        for key, value in pairs:
            require(key not in result, f"duplicate JSON field: {key}")
            result[key] = value
        return result
    return json.loads(Path(path).read_text(), object_pairs_hook=unique)


def relative(value):
    require(isinstance(value, str) and re.fullmatch(r"[A-Za-z0-9_./-]+", value), "unsafe path characters")
    parts = value.split("/")
    require(all(part not in ("", ".", "..") for part in parts), f"noncanonical path: {value}")
    path = PurePosixPath(value)
    require(not path.is_absolute(), f"absolute path: {value}")
    return path


def validate_manifest(manifest):
    require(manifest.get("schemaVersion") == 1, "unsupported input manifest schema")
    require(manifest.get("status") == "reviewed", "external dataset identities are unavailable/unreviewed")
    require(isinstance(manifest.get("provenance"), str) and manifest["provenance"].strip(), "missing dataset provenance")
    artifact = manifest.get("artifact", {})
    pin = artifact.get("sha256")
    require(isinstance(pin, str) and re.fullmatch(r"[0-9a-f]{64}", pin) and len(set(pin)) > 1, "missing artifact SHA256 identity")
    kind = artifact.get("kind", "https-archive")
    require(kind in ("https-archive", "local-inventory"), "unsupported artifact kind")
    if kind == "https-archive":
        url = urllib.parse.urlsplit(artifact.get("url") or "")
        require(url.scheme == "https" and url.hostname and not (url.username or url.password or url.query or url.fragment), "artifact URL must be credential-free HTTPS")
    else:
        require(artifact.get("url") is None, "local inventory cannot supply a download URL")
        relative(artifact.get("inventory"))
    require(type(manifest.get("nativeK1SecondaryCount")) is int and manifest["nativeK1SecondaryCount"] > 0, "missing pinned native K1 secondary count")
    for key, cap in LIMITS.items():
        require(type(artifact.get(key)) is int and 0 < artifact[key] <= cap, f"invalid {key} bound")
    env = manifest.get("environment", {})
    require(set(env) == set(INPUT_KINDS), "input environment mapping differs from approved keys")
    for key, spec in env.items():
        require(set(spec) == {"path", "kind"} and spec["kind"] == INPUT_KINDS[key], f"invalid input mapping: {key}")
        relative(spec["path"])
    return artifact


class HttpsRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, url):
        target = urllib.parse.urlsplit(url)
        require(target.scheme == "https" and not (target.username or target.password), "insecure artifact redirect")
        return super().redirect_request(request, response, code, message, headers, url)


def open_https(url):
    return urllib.request.build_opener(HttpsRedirect()).open(url, timeout=60)


def download(artifact, target):
    digest = hashlib.sha256()
    total = 0
    with open_https(artifact["url"]) as response, target.open("xb") as out:
        require(urllib.parse.urlsplit(response.url).scheme == "https", "insecure artifact redirect")
        for block in iter(lambda: response.read(1024 * 1024), b""):
            total += len(block)
            require(total <= artifact["maxBytes"], "compressed artifact exceeds bound")
            digest.update(block)
            out.write(block)
    require(total > 0 and digest.hexdigest() == artifact["sha256"], "artifact SHA256 mismatch")


def extract(archive, root, artifact, environment):
    require(not root.exists(), "input extraction destination already exists")
    root.mkdir()
    seen = set()
    payload = 0
    expanded = 0
    directories = [relative(spec["path"]) for spec in environment.values() if spec["kind"] == "directory"]
    files = [relative(spec["path"]) for spec in environment.values() if spec["kind"] == "file"]
    table = relative(environment["SCARAB_MEMPACK_TABLES"]["path"])
    files.extend(table.parent / name for name in ("state", "meta"))
    # Parse each fixed-size header with the public parser before reading any member payload.
    # TarFile iteration would consume PAX/GNU extension payloads before exposing a member.
    with gzip.open(archive, "rb") as source:
        def take(count):
            nonlocal expanded
            block = source.read(count)
            expanded += len(block)
            require(expanded <= artifact["maxUnpackedBytes"], "unpacked archive exceeds bound")
            require(len(block) == count, "truncated archive")
            return block
        while True:
            header = take(tarfile.BLOCKSIZE)
            if header == bytes(tarfile.BLOCKSIZE):
                require(take(tarfile.BLOCKSIZE) == bytes(tarfile.BLOCKSIZE), "invalid tar end marker")
                tail = source.read(10241)
                expanded += len(tail)
                require(expanded <= artifact["maxUnpackedBytes"], "unpacked archive exceeds bound")
                require(len(tail) <= 10240 and not any(tail), "excessive/nonzero trailing tar padding")
                break
            member = tarfile.TarInfo.frombuf(header, "utf-8", "strict")
            require(member.type in (tarfile.REGTYPE, tarfile.AREGTYPE, tarfile.DIRTYPE), "archive must use regular USTAR files/directories without extended headers")
            require(header[257:265] == tarfile.POSIX_MAGIC, "archive is not USTAR")
            name = member.name.rstrip("/") if member.isdir() else member.name
            path = relative(name)
            allowed = any(path == base or base in path.parents for base in directories) or path in files
            parent = member.isdir() and any(path in base.parents for base in directories + files)
            require(allowed or parent, f"archive entry outside approved inputs: {name}")
            require(path.suffix not in (".skey", ".key"), f"signing-key file is not a candidate input: {name}")
            require(str(path) not in seen, f"duplicate archive entry: {name}")
            seen.add(str(path))
            require(len(seen) <= artifact["maxEntries"], "archive entry count exceeds bound")
            require(member.size >= 0, f"negative archive member size: {name}")
            require(not member.isdir() or member.size == 0, "directory contains a payload")
            payload += member.size
            require(expanded + member.size <= artifact["maxUnpackedBytes"], "unpacked archive exceeds bound")
            target = root.joinpath(*path.parts)
            target.parent.mkdir(parents=True, exist_ok=True)
            if member.isdir():
                target.mkdir(exist_ok=True)
            else:
                with target.open("xb") as out:
                    remaining = member.size
                    while remaining:
                        block = take(min(1024 * 1024, remaining))
                        out.write(block)
                        remaining -= len(block)
                padding = take((-member.size) % tarfile.BLOCKSIZE)
                require(not any(padding), "nonzero tar member padding")
                target.chmod(0o444)
    require(payload > 0, "empty input archive")
    for path in sorted(root.rglob("*"), key=lambda p: len(p.parts), reverse=True):
        if path.is_dir():
            path.chmod(0o555)
    root.chmod(0o555)
    return {"entries": len(seen), "payloadBytes": payload, "unpackedBytes": expanded}


def mapped_inputs(manifest, root):
    result = {}
    for key, spec in manifest["environment"].items():
        path = root.joinpath(*relative(spec["path"]).parts)
        require(path.is_file() if spec["kind"] == "file" else path.is_dir(), f"missing input: {key}")
        require(path.stat().st_size > 0 if path.is_file() else any(path.iterdir()), f"empty input: {key}")
        result[key] = path
    return result


def verify_local_inputs(root, artifact, inventory_path, environment):
    """Verify a pinned complete local tree; Docker mounts it read-only, without extraction/copies."""
    require(root.is_dir() and not root.is_symlink(), "local input root must be a real directory")
    require(inventory_path.stat().st_size <= artifact["maxBytes"], "local inventory exceeds bound")
    require(sha256(inventory_path) == artifact["sha256"], "local inventory SHA256 mismatch")
    document = load_json(inventory_path)
    require(document.get("schemaVersion") == 1, "unsupported local inventory schema")
    directories = [relative(spec["path"]) for spec in environment.values() if spec["kind"] == "directory"]
    files = [relative(spec["path"]) for spec in environment.values() if spec["kind"] == "file"]
    table = relative(environment["SCARAB_MEMPACK_TABLES"]["path"])
    files.extend(table.parent / name for name in ("state", "meta"))
    expected = {}
    for row in document.get("files", []):
        path = str(relative(row["path"]))
        member = PurePosixPath(path)
        require(any(member == base or base in member.parents for base in directories) or member in files, f"local input outside approved mappings: {path}")
        require(path not in expected, "duplicate local inventory path")
        require(type(row["bytes"]) is int and row["bytes"] >= 0, "invalid local file size")
        require(isinstance(row["sha256"], str) and re.fullmatch(r"[0-9a-f]{64}", row["sha256"]), "invalid local file identity")
        expected[path] = row
    require(expected and len(expected) <= artifact["maxEntries"], "empty/oversized local inventory")
    actual = {}
    entries = 0
    for path in root.rglob("*"):
        entries += 1
        require(entries <= artifact["maxEntries"], "local input entry count exceeds bound")
        require(not path.is_symlink(), "local input contains a symlink")
        require(path.is_file() or path.is_dir(), "local input contains a nonregular entry")
        if path.is_file():
            name = str(path.relative_to(root))
            require(path.suffix not in (".skey", ".key"), "local input contains a signing-key file")
            require(name in expected, f"unexpected local input: {name}")
            actual[name] = path
    require(actual.keys() == expected.keys(), "missing local input files")
    payload = sum(path.stat().st_size for path in actual.values())
    require(payload <= artifact["maxUnpackedBytes"], "local input payload exceeds bound")
    for name, path in sorted(actual.items()):
        row = expected[name]
        require(path.stat().st_size == row["bytes"] and sha256(path) == row["sha256"], f"local input identity mismatch: {name}")
    return {"entries": entries, "payloadBytes": payload, "files": len(actual), "inventorySha256": artifact["sha256"]}


def native_input_checks(env, native_count):
    tables = env["SCARAB_MEMPACK_TABLES"]
    require(sha256(tables) == TABLE_SHA256, "wrong pinned preprod tables")
    for sibling in ("state", "meta"):
        require((tables.parent / sibling).is_file(), f"tables missing sibling {sibling}")
    require((env["SCARAB_MAINNET_GENESIS_DIR"] / "byron-genesis.json").is_file(), "missing mainnet Byron genesis")
    k1 = env["SCARAB_K1_DB"]
    require((k1 / "immutable").is_dir() and (k1 / "snapshots").is_dir(), "incomplete K1 corpus")
    native = env["SCARAB_NATIVE_K1_DB"] / "immutable"
    require(native.is_dir(), "missing native K1 immutable directory")
    require(len(list(native.glob("*.secondary"))) == native_count, "native K1 secondary inventory differs from reviewed count")
    reference = env["SCARAB_PREVIEW_REFERENCE"]
    require((reference / "index.jsonl").is_file(), "missing native reference index")
    require((reference / "index.jsonl").stat().st_size > 0, "empty native reference index")
    stem = "snapshot-00000000000136511997-5300868"
    base = env["SCARAB_PLUTUS_BASELINE"]
    require((base / "snapshots" / f"{stem}.bin").is_file(), "missing original Plutus baseline snapshot")
    require((base / "utxo/checkpoints" / stem).is_dir(), "missing original Plutus baseline checkpoint")


def identities(cases):
    required = cases.get("required", [])
    require(cases.get("schemaVersion") == 1 and len(required) == 12, "expected exactly 12 mandatory cases")
    required_ids = {(row["suite"], row["name"]) for row in required}
    extra_ids = {(row["suite"], row["name"]) for row in cases["additionalPassing"]}
    require(len(required_ids) == 12 and len(extra_ids) == len(cases["additionalPassing"]), "duplicate declared case identity")
    require(not required_ids & extra_ids, "mandatory case also declared as extra")
    require(all(row["project"] in ("node", "storage") for row in required), "unapproved test project")
    require(all(suite.startswith("xyz.kd5ujc.scarab.") and "Bench" not in suite for suite, _ in required_ids | extra_ids), "unapproved suite identity")
    return required_ids, extra_ids


def verify_reports(paths, cases):
    required, extras = identities(cases)
    expected = required | extras
    seen = set()
    hashes = {}
    for path in paths:
        body = Path(path).read_bytes()
        require(b"<!DOCTYPE" not in body and b"<!ENTITY" not in body, "DTD/entity in JUnit report")
        document = ET.fromstring(body)
        require(document.tag == "testsuite", "expected actual sbt/MUnit testsuite report root")
        require(not any(node.tag in ("skipped", "error", "failure") for node in document.iter()), "JUnit contains a nonpassing result")
        for suite in document.iter("testsuite"):
            rows = list(suite.iter("testcase"))
            for field in ("errors", "failures", "skipped"):
                require(int(suite.get(field, "0")) == 0, f"JUnit suite reported {field}: {path}")
            require(int(suite.get("tests", "-1")) == len(rows), "JUnit case count mismatch")
            for case in rows:
                identity = (case.get("classname"), case.get("name"))
                require(identity in expected, f"unexpected case: {identity}")
                require(identity not in seen, f"duplicate reported case: {identity}")
                require(not any(child.tag in ("skipped", "error", "failure") for child in case.iter()), f"case did not pass: {identity}")
                seen.add(identity)
        require(Path(path).name not in hashes, "duplicate JUnit filename")
        hashes[Path(path).name] = hashlib.sha256(body).hexdigest()
    require(seen == expected, f"missing reported cases: {sorted(expected - seen)}")
    return {"mandatoryPassed": len(required), "additionalPassed": len(extras), "reports": hashes}


def save_reports(checkout, output):
    saved = output / "junit"
    saved.mkdir(exist_ok=True)
    reports = []
    for project in ("storage", "node"):
        destination = saved / project
        destination.mkdir(exist_ok=True)
        for path in sorted((checkout / project / "target/test-reports").glob("*.xml")):
            shutil.copyfile(path, destination / path.name)
            reports.append(destination / path.name)
    return reports


def resource_check():
    memory = int(subprocess.check_output(["docker", "info", "--format", "{{.MemTotal}}"], text=True).strip())
    require(memory >= 24 * 1024**3, "Docker daemon has less than 24 GiB memory")


def replay_receipt(path):
    value = load_json(path)
    required = value.get("referenceTargetsRequired")
    compared = value.get("referenceBodyComparisons")
    require(type(required) is int and type(compared) is int and required > 0 and compared == required, "missing complete native body comparisons")
    require(type(value.get("blocks")) is int and value["blocks"] > 0, "replay reported no original blocks")
    return {"sha256": sha256(path), "referenceTargetsRequired": required, "referenceBodyComparisons": compared}


def runtime_receipt(path):
    observations = re.findall(r"candidate-runtime project=(storage|node) maxHeapBytes=(\d+) processors=(\d+) workingDirectory=(\S+)", Path(path).read_text())
    require(len(observations) == 2 and {row[0] for row in observations} == {"storage", "node"}, "missing/duplicate fork runtime observations")
    result = {}
    for project, heap, processors, directory in observations:
        require(int(heap) >= 16 * 1024**3 and int(processors) == 4 and directory == "/work", "candidate fork ignored heap/CPU/working-directory configuration")
        result[project] = {"maxHeapBytes": int(heap), "processors": int(processors), "workingDirectory": directory}
    return result


def sbt_commands(cases):
    identities(cases)
    commands = []
    for project in ("storage", "node"):
        commands.extend((f"set {project} / Test / fork := true", f'set {project} / Test / javaOptions ++= Seq("-Xmx16g", "-XX:ActiveProcessorCount=4")'))
        # Storage's build definition explicitly selects InProcess for ordinary suites, so
        # setting Test/fork alone does not select the requested heap or process isolation.
        commands.append(f'set {project} / Test / testGrouping := Seq(Tests.Group("candidate-{project}", ({project} / Test / definedTests).value, Tests.SubProcess(({project} / Test / forkOptions).value.withWorkingDirectory(Some((LocalRootProject / baseDirectory).value)))))')
        suites = sorted({row["suite"] for row in cases["required"] if row["project"] == project})
        require(all(re.fullmatch(r"[A-Za-z0-9_.]+", suite) for suite in suites), "unsafe sbt suite")
        commands.append(f"{project}/testOnly " + " ".join(suites))
    return commands


def run(manifest_path, cases_path, checkout, output, local_inputs=None):
    require(output != checkout and checkout not in output.parents, "candidate output must be outside the source checkout")
    require(not output.exists(), "candidate output must be a fresh directory")
    output.mkdir(parents=True)
    (output / ".candidate-owned.json").write_text(json.dumps({"path": str(output)}))
    source = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=checkout, text=True).strip()
    try:
        manifest_hash = sha256(manifest_path)
        cases_hash = sha256(cases_path)
        manifest = load_json(manifest_path)
        artifact = validate_manifest(manifest)
        cases = load_json(cases_path)
        identities(cases)
        require(not subprocess.check_output(["git", "status", "--porcelain"], cwd=checkout).strip(), "candidate checkout is not clean")
        local = artifact.get("kind") == "local-inventory"
        require(local == (local_inputs is not None), "local input mode requires an explicit local input root")
        require(shutil.disk_usage(output).free >= (1 if local else 2) * artifact["maxUnpackedBytes"], "insufficient scratch disk for inputs and outputs")
        resource_check()
        archive = output / "inputs.tar"
        if local:
            input_root = local_inputs
            inventory_path = manifest_path.parent.joinpath(*relative(artifact["inventory"]).parts)
            require(input_root != output and input_root not in output.parents, "output overlaps local input root")
            inventory = verify_local_inputs(input_root, artifact, inventory_path, manifest["environment"])
        else:
            download(artifact, archive)
            input_root = output / "inputs"
            inventory = extract(archive, input_root, artifact, manifest["environment"])
        env = mapped_inputs(manifest, input_root)
        native_input_checks(env, manifest["nativeK1SecondaryCount"])
        scratch = output / "results"
        scratch.mkdir()
        for project in ("node", "storage"):
            reports = checkout / project / "target/test-reports"
            if reports.exists():
                shutil.rmtree(reports)
        subprocess.run(["docker", "build", "-f", "docker/jdk.Dockerfile", "-t", "scarab-candidate-jdk:local", "."], cwd=checkout, check=True)
        image = subprocess.check_output(["docker", "image", "inspect", "--format", "{{.Id}}", "scarab-candidate-jdk:local"], text=True).strip()
        container = "scarab-candidate-" + uuid.uuid4().hex
        command = ["docker", "run", "--rm", "--name", container, "--memory", "24g", "--cpus", "4", "--user", f"{os.getuid()}:{os.getgid()}",
                   "-v", f"{checkout}:/work", "-v", f"{input_root}:/candidate/inputs:ro",
                   "-v", f"{scratch}:/candidate/results", "-w", "/work", "-e", "HOME=/work/.cache/home", "-e", "SBT_GLOBAL_BASE=/tmp/sbt-global"]
        actual_env = {key: "/candidate/inputs/" + manifest["environment"][key]["path"] for key in env}
        actual_env.update({key: "/candidate/results/" + value for key, value in OUTPUTS.items()})
        actual_env.update({"SCARAB_CANDIDATE_RUNTIME": "1", "SCARAB_PREVIEW_WORKERS": "2,4", "SCARAB_PREVIEW_ROUNDS": "1", "SCARAB_PREVIEW_SCHEDULE": "Comparison"})
        common = Path(subprocess.check_output(["git", "rev-parse", "--path-format=absolute", "--git-common-dir"], cwd=checkout, text=True).strip())
        cache = common.parent / ".cache"
        if cache.is_dir():
            command.extend(("-v", f"{cache}:/work/.cache"))
        for key, value in sorted(actual_env.items()):
            command.extend(("-e", f"{key}={value}"))
        command.extend((image, "bash", "scripts/sbtw", *sbt_commands(cases)))
        try:
            with (output / "sbt.log").open("wb") as log:
                subprocess.run(command, cwd=checkout, stdout=log, stderr=subprocess.STDOUT, check=True)
        finally:
            subprocess.run(["docker", "rm", "--force", container], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False)
            paths = save_reports(checkout, output)
        junit = verify_reports(paths, cases)
        require(sha256(manifest_path) == manifest_hash and sha256(cases_path) == cases_hash, "candidate manifest changed during execution")
        require(subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=checkout, text=True).strip() == source, "candidate source commit changed during execution")
        require(not subprocess.check_output(["git", "status", "--porcelain"], cwd=checkout).strip(), "candidate source changed during execution")
        if local:
            require(verify_local_inputs(input_root, artifact, inventory_path, manifest["environment"]) == inventory, "local inputs changed during execution")
        receipt = {"schemaVersion": 1, "status": "passed", "sourceCommit": source,
                   "manifestSha256": manifest_hash, "casesSha256": cases_hash,
                   "artifactSha256": artifact["sha256"] if local else sha256(archive), "artifactKind": artifact.get("kind", "https-archive"), "image": image, "inputInventory": inventory,
                   "environment": actual_env, "junit": junit,
                   "testRuntime": runtime_receipt(output / "sbt.log"),
                   "nativeReplay": replay_receipt(scratch / "preview/summary.json")}
        (output / "receipt.json").write_text(json.dumps(receipt, indent=2, sort_keys=True) + "\n")
    except Exception as error:
        (output / "failure.json").write_text(json.dumps({"status": "failed", "sourceCommit": source, "reason": str(error)}, indent=2) + "\n")
        raise


def cleanup(output):
    require(load_json(output / ".candidate-owned.json") == {"path": str(output)}, "not an owned candidate output")
    for name in ("inputs", "results"):
        root = output / name
        if root.exists():
            require(root.is_dir() and not root.is_symlink(), "unsafe cleanup root")
            for parent, dirs, _ in os.walk(root, followlinks=False):
                os.chmod(parent, 0o700)
                dirs[:] = [name for name in dirs if not (Path(parent) / name).is_symlink()]
            shutil.rmtree(root)
    (output / "inputs.tar").unlink(missing_ok=True)


def interrupted(_signal, _frame):
    raise InterruptedError("candidate job interrupted")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, default=Path("scripts/candidate_acceptance/inputs.json"))
    parser.add_argument("--cases", type=Path, default=Path("scripts/candidate_acceptance/cases.json"))
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--local-inputs", type=Path, help="read-only original input tree matching a reviewed local inventory")
    parser.add_argument("--cleanup", action="store_true", help="remove owned large data after evidence upload")
    args = parser.parse_args()
    signal.signal(signal.SIGTERM, interrupted)
    signal.signal(signal.SIGINT, interrupted)
    try:
        if args.cleanup:
            cleanup(args.output.resolve())
        else:
            run(args.manifest.resolve(), args.cases.resolve(), Path.cwd().resolve(), args.output.resolve(), args.local_inputs.absolute() if args.local_inputs else None)
    except (Rejected, OSError, ValueError, tarfile.TarError, subprocess.CalledProcessError) as error:
        parser.exit(2, f"candidate acceptance refused: {error}\n")


if __name__ == "__main__":
    main()
