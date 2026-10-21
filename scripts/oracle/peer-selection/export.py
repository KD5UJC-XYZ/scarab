#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Observe unchanged pinned native selection policies using existing read-only packages."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import subprocess

PIN = "2fb69829a19b5c04e4fb212bdd78fb63f9d2d776"
POLICY = "ouroboros-network/lib/Ouroboros/Network/Diffusion/Policies.hs"
POLICY_SHA256 = "376faf7a081ea7d37f6d5c7b71f4838170182e92a96bdccb6b54715ce827a68f"
HERE = Path(__file__).resolve().parent
PACKAGES = [
    "io-classes-1.8.0.1-81db6f9dc4fa77b4eca2c3d0a055628e2f895bdba382956ab58a9877640ca2d0",
    "io-classes-1.8.0.1-l-strict-stm-df137760a3eaf3e7e1983b3b8d55deca78a4a9cd1c1de4b61964654e219d232c",
    "ouroboros-network-1.3.0.0-inplace",
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
    parser.add_argument("--container", default="scarab-149-native-policy")
    args = parser.parse_args()
    reference = args.reference_build.resolve()
    probe = args.probe_dir.resolve()
    probe.mkdir(parents=True, exist_ok=False)
    if sha(reference / "source" / POLICY) != POLICY_SHA256:
        raise ValueError("reference policy source does not match the maintained pin")
    (probe / "Main.hs").write_bytes((HERE / "Main.hs").read_bytes())
    command = ["ghc", "-package-db", "/root/.local/state/cabal/store/ghc-9.10.3-1be3/package.db",
               "-package-db", "/probe/builddir/packagedb/ghc-9.10.3", "-package", "aeson",
               "-package", "random"]
    for package in PACKAGES:
        command.extend(["-package-id", package])
    command.extend(["-XImportQualifiedPost", "-odir", "/observer", "-hidir", "/observer",
                    "/observer/Main.hs", "-o", "/observer/policy-observer"])
    # These arguments are constants, not user-controlled shell fragments.
    shell = " ".join(command) + " && /observer/policy-observer > /observer/decisions.jsonl"
    argv = ["docker", "run", "--rm", "--name", args.container, "--network", "none",
            "--entrypoint", "bash", "-v", f"{reference}:/probe:ro",
            "-v", f"{probe}:/observer", args.image, "-c", shell]
    with (probe / "build.log").open("w") as log:
        subprocess.run(argv, stdout=log, stderr=subprocess.STDOUT, check=True)
    rows = [json.loads(line) for line in (probe / "decisions.jsonl").read_text().splitlines()]
    if len(rows) != 912:
        raise ValueError("incomplete native decision output")
    image = subprocess.check_output(["docker", "image", "inspect", args.image,
                                     "--format", "{{.Id}}"], text=True).strip()
    manifest = {
        "schema": "scarab-native-peer-selection-policy-v1", "sourceCommit": PIN,
        "sourceFiles": {POLICY: POLICY_SHA256},
        "observerSha256": sha(HERE / "Main.hs"), "exporterSha256": sha(Path(__file__)),
        "sha256": sha(probe / "decisions.jsonl"), "cases": len(rows),
        "generatedCases": 0, "boundaryCases": len(rows),
        "inputs": "explicit seeds149001..149016, size0/1/2/5/12, tepid none/all/even, distinct n0/1/half/all/over",
        "boundary": "actual simplePeerSelectionPolicy picks and mkHotDemotionPolicy; actual addRand words from identical reset native StdGen",
        "image": image, "executableSha256": sha(probe / "policy-observer"),
        "planSha256": sha(reference / "builddir/cache/plan.json"),
        "packageIds": PACKAGES, "command": argv,
    }
    args.output_dir.mkdir(parents=True, exist_ok=True)
    (args.output_dir / "decisions.jsonl").write_bytes((probe / "decisions.jsonl").read_bytes())
    (args.output_dir / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")


if __name__ == "__main__":
    main()
