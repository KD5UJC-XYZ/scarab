#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Reproduce ordinary inline-payload ChainSync/BlockFetch codec observations."""

from __future__ import annotations

import argparse
from collections import Counter
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from repo_paths import relative_argv, repo_relative  # noqa: E402

PIN = "2fb69829a19b5c04e4fb212bdd78fb63f9d2d776"
HERE = Path(__file__).resolve().parent
MODULES = {
    family: f"ouroboros-network/protocols/tests-lib/Ouroboros/Network/Protocol/{family}/Test.hs"
    for family in ("BlockFetch", "ChainSync")
}
SUPPORT_FILES = (
    "ouroboros-network/api/lib/Ouroboros/Network/Block.hs",
    "ouroboros-network/api/tests-lib/Ouroboros/Network/Mock/ConcreteBlock.hs",
    "ouroboros-network/protocols/tests-lib/Test/Ouroboros/Network/Protocol/Utils.hs",
    "ouroboros-network/protocols/lib/Ouroboros/Network/Protocol/BlockFetch/Codec.hs",
    "ouroboros-network/protocols/lib/Ouroboros/Network/Protocol/ChainSync/Codec.hs",
)
PROPERTIES = {
    "BlockFetch": (
        "prop_codec_BlockFetch", "prop_codec_splits2_BlockFetch",
        "prop_codec_splits3_BlockFetch", "prop_codec_cbor_BlockFetch",
        "prop_codec_valid_cbor_BlockFetch",
    ),
    "ChainSync": (
        "prop_codec_ChainSync", "prop_codec_splits2_ChainSync",
        "prop_codec_splits3_ChainSync", "prop_codec_cbor", "prop_codec_valid_cbor",
    ),
}


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def validate(rows: list[list[str]], family: str, count: int, seed: int) -> dict:
    if len(rows) != count:
        raise ValueError(f"{family}: expected {count} original samples, got {len(rows)}")
    for ordinal, row in enumerate(rows):
        if len(row) != 15 or row[0] != f"{family.lower()}-ordinary-codec-v1":
            raise ValueError(f"{family}: bad schema at {ordinal}")
        if row[1:4] != [str(seed + ordinal), str(ordinal), str(8 + ordinal % 9)]:
            raise ValueError(f"{family}: changed original generator identity at {ordinal}")
        if not bytes.fromhex(row[7]) or any(v not in ("pass", "upstream-failure") for v in row[10:]):
            raise ValueError(f"{family}: missing wire or unknown verdict at {ordinal}")
        for item in row[8].split(",") if row[8] else []:
            if not bytes.fromhex(item):
                raise ValueError(f"{family}: empty original field at {ordinal}")
    return {
        "constructors": dict(sorted(Counter(row[6] for row in rows).items())),
        "states": dict(sorted(Counter(row[4] for row in rows).items())),
        "upstreamPropertyOutcomesByProperty": {
            name: {value: sum(row[index] == value for row in rows)
                   for value in ("pass", "upstream-failure")}
            for index, name in enumerate(PROPERTIES[family], start=10)
        },
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--probe-dir", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--image", default="scarab-v02-49-protocols-cache:peer-acceptance-20261019")
    parser.add_argument("--container", default="scarab-49-ordinary-codecs")
    parser.add_argument("--count", type=int, default=64)
    parser.add_argument("--seed", type=int, default=509101)
    parser.add_argument("--cache-build", type=Path)
    parser.add_argument("--reference-plan", type=Path)
    args = parser.parse_args()
    if not 1 <= args.count <= 128:
        parser.error("count must be 1..128")
    source, probe = args.source.resolve(), args.probe_dir.resolve()
    args.output_dir.mkdir(parents=True, exist_ok=True)
    probe.mkdir(parents=True, exist_ok=True)
    commands = []

    def run(argv: list[str]) -> str:
        ordinal = len(commands)
        entry = {"argv": relative_argv(argv), "startedAt": datetime.now(timezone.utc).isoformat()}
        commands.append(entry)
        log = probe / f"command-{ordinal}.log"
        with log.open("w") as stream:
            result = subprocess.run(argv, stdout=stream, stderr=subprocess.STDOUT, text=True)
        entry.update(returncode=result.returncode, log=repo_relative(str(log)),
                     endedAt=datetime.now(timezone.utc).isoformat(), logSha256=sha(log))
        (probe / "commands.json").write_text(json.dumps(commands, indent=2) + "\n")
        if result.returncode:
            raise RuntimeError(f"command failed ({result.returncode}); see {log}")
        return log.read_text().strip()

    if run(["git", "-C", str(source), "rev-parse", "HEAD"]) != PIN:
        raise ValueError("upstream pin mismatch")
    # Copy committed contents, ignoring any unrelated local edits in the source checkout.
    archive = probe / "upstream.tar"
    run(["git", "-C", str(source), "archive", "--format=tar", "-o", str(archive), PIN])
    overlay = probe / "source"
    if overlay.exists():
        raise ValueError("use a fresh probe directory; existing source is never overwritten")
    overlay.mkdir()
    run(["tar", "-xf", str(archive), "-C", str(overlay)])
    archive.unlink()
    patch = HERE / "ordinary-codecs-observer.patch"
    source_hashes = {name: sha(overlay / name) for name in
                     (*MODULES.values(), "ouroboros-network/protocols/tests/Main.hs", *SUPPORT_FILES)}
    run(["patch", "--batch", "-p1", "-d", str(overlay), "-i", str(patch)])
    project = (overlay / "cabal.project").read_text()
    project, replacements = re.subn(
        r"^packages:.*?\n\n", "packages:\n  /source/monoidal-synchronisation\n"
        "  /source/network-mux\n  /source/ouroboros-network\n\n", project, count=1, flags=re.S | re.M)
    if replacements != 1:
        raise ValueError("upstream project packages anchor changed")
    project_file = probe / "protocols-tests.project"
    project_file.write_text(project)
    build = probe / "narrow-builddir"
    if args.cache_build:
        shutil.copytree(args.cache_build, build)
    (probe / "exports").mkdir()
    created = False
    try:
        run(["docker", "create", "--network=none", "--name", args.container,
             "--entrypoint", "tail", "-v", f"{overlay}:/source", "-v", f"{probe}:/probe",
             "-w", "/source", args.image, "-f", "/dev/null"])
        created = True
        run(["docker", "start", args.container])
        image = run(["docker", "inspect", args.container, "--format", "{{.Image}}"])
        base = ["docker", "exec", "-w", "/source", args.container]
        run(base + ["cabal", "build", "--offline", "--project-file=/probe/protocols-tests.project",
                    "ouroboros-network:protocols-tests", "--builddir=/probe/narrow-builddir", "--jobs=1"])
        binary = run(base + ["cabal", "list-bin", "--project-file=/probe/protocols-tests.project",
                            "ouroboros-network:protocols-tests", "--builddir=/probe/narrow-builddir"]).splitlines()[-1]
        run(base + [binary, "--export-ordinary-codecs", "/probe/exports", str(args.count), str(args.seed)])
        executable_sha = run(base + ["sha256sum", binary]).split()[0]
        equivalence = None
        if args.reference_plan:
            target = probe / "target-plan-equivalence.json"
            run(["python3", str(HERE / "compare_cabal_plans.py"), str(args.reference_plan),
                 str(build / "cache/plan.json"), "--output", str(target)])
            equivalence = json.loads(target.read_text())
        for family in MODULES:
            name = f"{family.lower()}-ordinary.tsv"
            exported = probe / "exports" / name
            rows = [line.split("\t") for line in exported.read_text().splitlines()]
            seed = args.seed + (1000 if family == "ChainSync" else 0)
            manifest = {
                "schema": "scarab-upstream-ordinary-codecs-v1", "sourceCommit": PIN,
                "family": family, "originalDomain": "Block" if family == "BlockFetch" else "BlockHeader",
                "codec": "codec (ordinary inline payload, not codecWrapped or codecSerialised)",
                "seed": seed, "count": args.count, "sizeRule": "8 + ordinal mod 9",
                "caseFile": name, "sha256": sha(exported),
                "columns": ["schema", "seed", "ordinal", "size", "state", "sender", "constructor",
                            "originalWireHex", "originalFieldCborHexCommaSeparated", "originalMessageShow",
                            *PROPERTIES[family]],
                "sourceFiles": source_hashes, "observerPatchSha256": sha(patch),
                "patchedSourceFiles": {name: sha(overlay / name) for name in source_hashes},
                "execution": {"image": args.image, "imageDigest": image, "network": "none",
                              "offline": True, "jobs": 1, "executableSha256": executable_sha,
                              "projectSha256": sha(project_file), "planSha256": sha(build / "cache/plan.json")},
                "targetPlanEquivalence": equivalence, "commands": commands.copy(),
                "limits": ["Bounded seeded observations, not entire property families",
                           "Generic framing uses structural CBOR values, not Cardano payload semantics",
                           "Prefix replay accumulates retained fragments; no Haskell interpreter equivalence"],
                **validate(rows, family, args.count, seed),
            }
            shutil.copy2(exported, args.output_dir / name)
            (args.output_dir / f"{name}.manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    finally:
        if created:
            run(["docker", "rm", "-f", args.container])


if __name__ == "__main__":
    main()
