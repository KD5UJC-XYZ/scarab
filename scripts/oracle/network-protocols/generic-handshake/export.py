#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Observe original generic Handshake generators and properties in an isolated source copy."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[3]))
from repo_paths import relative_argv  # noqa: E402

PIN = "2fb69829a19b5c04e4fb212bdd78fb63f9d2d776"
MODULE = "ouroboros-network/protocols/tests-lib/Ouroboros/Network/Protocol/Handshake/Test.hs"
HERE = Path(__file__).resolve().parent
SCHEMAS = {
    "codec": (48, 9, 4, ["seed", "size", "state", "originalCbor", "prop_codec_Handshake", "prop_codec_splits2_Handshake", "prop_codec_splits3_Handshake", "prop_codec_cbor", "prop_codec_valid_cbor"]),
    "refuse": (24, 4, 3, ["seed", "size", "originalCbor", "prop_codec_RefuseReason"]),
    "behavior": (48, 19, 9, ["seed", "size", "clientMapCbor", "serverMapCbor", "originalClientData", "originalServerData", "serverSelection", "clientSelection", "asymmetricServerSelection", "prop_connect", "prop_channel_ST", "prop_channel_IO", "prop_pipe_IO", "prop_channel_asymmetric_ST", "prop_channel_asymmetric_IO", "prop_pipe_asymmetric_IO", "prop_channel_simultaneous_open_ST", "prop_channel_simultaneous_open_IO", "prop_channel_simultaneous_open_SimNet"]),
    "probes": (9, 8, None, ["seed", "sourceVersion", "targetVersion", "originalInt", "originalAnd", "originalOr", "remoteMapCbor", "selection"]),
    "symmetry": (24, 12, None, ["seed", "size", "localInt", "localAnd", "localOr", "remoteInt", "remoteAnd", "remoteOr", "prop_acceptable_symmetric_VersionData", "originalValidClientData", "originalValidServerData", "prop_acceptOrRefuse_symmetric_VersionData"]),
}


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def validate(name: str, raw: bytes) -> dict:
    count, width, first_verdict, columns = SCHEMAS[name]
    rows = [line.split("\t") for line in raw.decode().splitlines()]
    if len(rows) != count or any(len(row) != width for row in rows):
        raise ValueError(f"invalid {name} export count/schema")
    seeds = {"codec": 490301, "refuse": 490401, "behavior": 490501, "symmetry": 490601, "probes": 490701}
    for i, row in enumerate(rows):
        if int(row[0]) != seeds[name] + i or (name != "probes" and int(row[1]) != 8 + i % 13):
            raise ValueError(f"invalid deterministic input identity for {name}/{i}")
        for column in ([8, 11] if name == "symmetry" else [] if name == "probes" else range(first_verdict, width)):
            if row[column] not in ("pass", "upstream-failure"):
                raise ValueError(f"invalid original verdict in {name}/{i}")
    indexes = [8, 11] if name == "symmetry" else [] if name == "probes" else range(first_verdict, width)
    if name == "probes" and any(int(row[1]) != i // 3 or int(row[2]) != i % 3 for i, row in enumerate(rows)):
        raise ValueError("invalid controlled cross-version probe identity")
    return {
        "columns": columns,
        "rows": count,
        "upstreamOutcomes": {
            columns[column]: {outcome: sum(row[column] == outcome for row in rows)
                              for outcome in ("pass", "upstream-failure")}
            for column in indexes
        },
    }


def manifests(work: Path, output: Path, image: str, image_id: str, commands: list[dict]) -> None:
    output.mkdir(parents=True, exist_ok=True)
    proof = json.loads((work / "dependency-closure.json").read_text())
    if not proof["sameTargetClosure"]:
        raise ValueError("reference dependency closure differs")
    (output / "dependency-closure.json").write_text(json.dumps(proof, indent=2) + "\n")
    executable = next((work / "narrow-builddir/build").glob("*/ghc-*/ouroboros-network-*/t/protocols-tests/build/protocols-tests/protocols-tests"))
    for name in SCHEMAS:
        source = work / f"generic-{name}.tsv"
        meta = validate(name, source.read_bytes())
        dest = output / f"{name}.tsv"
        dest.write_bytes(source.read_bytes())
        manifest = {
            "fixture": dest.name, "fixtureSha256": sha(dest), **meta,
            "upstream": {"repository": "IntersectMBO/ouroboros-network", "commit": PIN,
                         "source": MODULE, "originalSourceSha256": sha(work / "original-module.hs"),
                         "observedSourceSha256": sha(work / "source" / MODULE),
                         "observerPatch": "scripts/oracle/network-protocols/generic-handshake/observer.patch",
                         "observerPatchSha256": sha(HERE / "observer.patch")},
            "build": {"image": image, "imageId": image_id,
                      "projectSha256": sha(HERE / "reference.project"),
                      "selectedPlanSha256": sha(work / "narrow-builddir/cache/plan.json"),
                      "fullProjectPlanSha256": sha(work / "full-plan/cache/plan.json"),
                      "sameTargetClosure": True, "targetDependencyUnits": proof["selectedUnitCount"],
                      "executableSha256": sha(executable), "commands": commands},
            "adapter": {"suite": "xyz.kd5ujc.scarab.protocol.GenericHandshakeUpstreamSuite",
                        "scope": "Original mock versions 0/1/2 and version-dependent data; production bounded parser, wire codecs and highest-common selector. Cardano policy remains separate.",
                        "limitations": ["Bounded deterministic samples, not whole-property completion.",
                                        "ST/IO/pipe/SimNet original verdicts are retained independently. Scala checks wire/selection contracts, not interpreter, transport or scheduler equivalence.",
                                        "The asymmetric server recognizes only version 1; original 0/2 entries are skipped rather than remapped.",
                                        "Generic generator-consistency laws are Haskell test infrastructure; they do not require Scala QuickCheck generation/shrinking.",
                                        "Local bounded resource/refusal-arity policies remain stricter than upstream."]},
        }
        (output / f"{name}.tsv.manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--work", type=Path, required=True, help="Fresh private directory outside the checkout")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--image", default="scarab-v02-49-protocols-cache:peer-acceptance-20261019")
    parser.add_argument("--container", default="scarab-generic-handshake-oracle")
    parser.add_argument("--build-cache", type=Path, help="Optional copied Cabal build directory; never mutated")
    args = parser.parse_args()
    if subprocess.check_output(["git", "-C", str(args.source), "rev-parse", "HEAD"], text=True).strip() != PIN:
        parser.error("source revision differs from pinned upstream")
    if subprocess.check_output(["git", "-C", str(args.source), "diff", "--name-only", "HEAD"], text=True).strip():
        parser.error("source must have no tracked modifications")
    if args.work.resolve().is_relative_to(args.source.resolve()):
        parser.error("private work directory must be outside the upstream source")
    args.work.mkdir(parents=True, exist_ok=False)
    commands: list[dict] = []

    def run(argv: list[str], cwd: Path | None = None) -> str:
        log = args.work / f"command-{len(commands)}.log"
        with log.open("w") as stream:
            result = subprocess.run(argv, cwd=cwd, stdout=stream, stderr=subprocess.STDOUT, check=False)
        commands.append({"argv": relative_argv(argv), "returncode": result.returncode, "logSha256": sha(log)})
        if result.returncode:
            raise RuntimeError(f"reference command failed ({result.returncode}); see {log}")
        return log.read_text().strip()

    shutil.copytree(args.source, args.work / "source", ignore=shutil.ignore_patterns(".git", "dist-newstyle", "dist"))
    shutil.copyfile(args.source / MODULE, args.work / "original-module.hs")
    shutil.copyfile(HERE / "reference.project", args.work / "protocols-tests.project")
    if args.build_cache:
        shutil.copytree(args.build_cache, args.work / "narrow-builddir")
    run(["patch", "--batch", "-p1", "-i", str(HERE / "observer.patch")], args.work / "source")
    image_id = run(["docker", "image", "inspect", args.image, "--format", "{{.Id}}"])
    run(["docker", "run", "-d", "--name", args.container, "-v", f"{args.work.resolve() / 'source'}:/source",
         "-v", f"{args.work.resolve()}:/probe", "--entrypoint", "tail", args.image, "-f", "/dev/null"])
    try:
        base = ["docker", "exec", "-w", "/source", args.container]
        run(base + ["cabal", "build", "--project-file=/probe/protocols-tests.project", "ouroboros-network:protocols-tests", "--builddir=/probe/narrow-builddir", "--jobs=2"])
        run(base + ["cabal", "build", "ouroboros-network:protocols-tests", "--project-file=/source/cabal.project", "--builddir=/probe/full-plan", "--enable-tests", "--dry-run", "--offline"])
        run([sys.executable, str(HERE.parent / "compare_cabal_plans.py"), str(args.work / "full-plan/cache/plan.json"), str(args.work / "narrow-builddir/cache/plan.json"), "--output", str(args.work / "dependency-closure.json")])
        executable = next((args.work / "narrow-builddir/build").glob("*/ghc-*/ouroboros-network-*/t/protocols-tests/build/protocols-tests/protocols-tests"))
        inside = "/probe/" + str(executable.relative_to(args.work))
        run(["docker", "exec", "-w", "/source", "-e", "SCARAB_GENERIC_HANDSHAKE_OUTPUT=/probe/generic", args.container, inside, "--pattern", "scarab generic export"])
        manifests(args.work, args.output, args.image, image_id, commands)
    finally:
        subprocess.run(["docker", "rm", "-f", args.container], check=True)
        (args.work / "commands.json").write_text(json.dumps(commands, indent=2) + "\n")


if __name__ == "__main__":
    main()
