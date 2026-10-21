#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Observe unchanged pinned native ChainSync pipeline decisions using existing read-only packages."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import shlex

PIN = "2fb69829a19b5c04e4fb212bdd78fb63f9d2d776"
POLICY = "ouroboros-network/protocols/lib/Ouroboros/Network/Protocol/ChainSync/PipelineDecision.hs"
POLICY_SHA256 = "d557b4aad0ed47fe291dea83cfdbd247d0efc0eb562d4b1799c1a8d674fba50d"
HERE = Path(__file__).resolve().parent
PACKAGES = [
    "typed-protocols-1.2.1.0-8de7fe320aa56825a6c63b14c2080cb872731101ee809c2e339f6536e1cc5fd5",
    "ouroboros-network-1.3.0.0-inplace-api (Ouroboros.Network.Block as NativeBlock, Ouroboros.Network.Point as NativePoint)",
    "ouroboros-network-1.3.0.0-inplace-protocols",
]


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--reference-build", type=Path, required=True,
                        help="existing native build containing source/ and builddir/; mounted read-only")
    parser.add_argument("--probe-dir", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--image", default="scarab-148-fetch-reference-cache:local")
    parser.add_argument("--container", default="scarab-chainsync-native-pipeline")
    args = parser.parse_args()
    reference = args.reference_build.resolve()
    probe = args.probe_dir.resolve()
    probe.mkdir(parents=True, exist_ok=False)
    if sha(reference / "source" / POLICY) != POLICY_SHA256:
        raise ValueError("reference pipeline source does not match the maintained pin")
    (probe / "Main.hs").write_bytes((HERE / "Main.hs").read_bytes())
    command = ["ghc", "-package-env", "-", "-hide-all-packages", "-package", "base", "-package-db", "/root/.local/state/cabal/store/ghc-9.10.3-1be3/package.db",
               "-package-db", "/probe/builddir/packagedb/ghc-9.10.3", "-package", "aeson",
               "-package", "bytestring"]
    for package in PACKAGES:
        command.extend(["-package-id", package])
    command.extend(["-XImportQualifiedPost", "-odir", "/observer", "-hidir", "/observer",
                    "/observer/Main.hs", "-o", "/observer/pipeline-observer"])
    # These arguments are constants, not user-controlled shell fragments.
    shell = shlex.join(command) + " && /observer/pipeline-observer > /observer/decisions.jsonl"
    argv = ["docker", "run", "--rm", "--name", args.container, "--network", "none",
            "--entrypoint", "bash", "-v", f"{reference}:/probe:ro",
            "-v", f"{probe}:/observer", args.image, "-c", shell]
    with (probe / "build.log").open("w") as log:
        subprocess.run(argv, stdout=log, stderr=subprocess.STDOUT, check=True)
    rows = [json.loads(line) for line in (probe / "decisions.jsonl").read_text().splitlines()]
    if len(rows) != 7 or sum(len(row["steps"]) for row in rows) != 798:
        raise ValueError("incomplete native decision output")
    image = subprocess.check_output(["docker", "image", "inspect", args.image,
                                     "--format", "{{.Id}}"], text=True).strip()
    manifest = {
        "schema": "scarab-native-chainsync-pipeline-v1", "sourceCommit": PIN,
        "sourceFiles": {POLICY: POLICY_SHA256},
        "observerSha256": sha(HERE / "Main.hs"), "exporterSha256": sha(Path(__file__)),
        "sha256": sha(probe / "decisions.jsonl"), "cases": sum(len(row["steps"]) for row in rows), "sequences": len(rows),
        "generatedCases": 0, "boundaryCases": sum(len(row["steps"]) for row in rows),
        "inputs": "explicit low/high pairs0/0,0/1,1/1,1/2,2/3,10/20,200/300; stateful grids plus origin, server-behind, hysteresis-reset and Word64 overflow controls",
        "boundary": "actual pipelineDecisionLowHighMark/runPipelineDecision; opaque native continuation carried through every input sequence",
        "image": image, "executableSha256": sha(probe / "pipeline-observer"),
        "planSha256": sha(reference / "builddir/cache/plan.json"),
        "packageIds": PACKAGES, "command": argv,
    }
    args.output_dir.mkdir(parents=True, exist_ok=True)
    (args.output_dir / "decisions.jsonl").write_bytes((probe / "decisions.jsonl").read_bytes())
    (args.output_dir / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")


if __name__ == "__main__":
    main()
