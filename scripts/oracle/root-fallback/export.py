#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Observe the unchanged pinned root-provider classification and public fallback."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import subprocess

PIN = "2fb69829a19b5c04e4fb212bdd78fb63f9d2d776"
HERE = Path(__file__).resolve().parent
SOURCE_FILES = {'ouroboros-network/lib/Ouroboros/Network/PeerSelection/PeerSelectionActions.hs': '80258287da0119f81897dd7a70e64d575b25e4763573bee0e69c1adac078536d', 'ouroboros-network/lib/Ouroboros/Network/PeerSelection/RootPeersDNS/PublicRootPeers.hs': '89d8d0335930bfb1ab2ef05931b7a55aefbd0a0e86ababb1b3cf8375805306a7', 'ouroboros-network/lib/Ouroboros/Network/PeerSelection/PublicRootPeers.hs': '4a7edac4c95f70484b89eb25e8b65530becadaa96cca8af357d31f49298aa1ba'}


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--reference-build", type=Path, required=True,
                        help="existing native build containing source/ and builddir/; mounted read-only")
    parser.add_argument("--probe-dir", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--image", default="scarab-148-fetch-reference-cache:local")
    parser.add_argument("--container", default="scarab-native-root-fallback")
    args = parser.parse_args()
    reference = args.reference_build.resolve()
    probe = args.probe_dir.resolve()
    probe.mkdir(parents=True, exist_ok=False)
    for source, expected in SOURCE_FILES.items():
        if sha(reference / "source" / source) != expected:
            raise ValueError(f"reference source does not match the maintained pin: {source}")
    (probe / "Main.hs").write_bytes((HERE / "Main.hs").read_bytes())
    command = ['ghc', '-package-db', '/root/.local/state/cabal/store/ghc-9.10.3-1be3/package.db', '-package-db', '/probe/builddir/packagedb/ghc-9.10.3', '-hide-all-packages', '-package', 'base', '-package', 'iproute', '-package', 'network', '-package-id', 'aeson-2.2.5.0-e1b275ce1b1bb82fc5b92edfc0b257272f0bab3f3562d042f5c7807be2bf93fe', '-package', 'bytestring', '-package', 'containers', '-package', 'random', '-package', 'quiet', '-package', 'nothunks', '-package-id', 'contra-tracer-0.2.1.0-2bf491b9d4bab34147cbbe69bf5b0f426e60fa2bd09ddb675bf3bcc5097cc8ba', '-package', 'monoidal-synchronisation', '-package-id', 'ouroboros-network-1.3.0.0-inplace', '-package-id', 'ouroboros-network-1.3.0.0-inplace-api', '-package-id', 'io-classes-1.8.0.1-81db6f9dc4fa77b4eca2c3d0a055628e2f895bdba382956ab58a9877640ca2d0', '-package-id', 'io-classes-1.8.0.1-l-si-timers-5e6d1bd5dca51400e474c18cd34c890c14330fb1007bfbbdcc06f7bb7760619e', '-package-id', 'io-classes-1.8.0.1-l-strict-stm-df137760a3eaf3e7e1983b3b8d55deca78a4a9cd1c1de4b61964654e219d232c', '-package-id', 'io-sim-1.8.0.1-a0a0aa3c1d5bb8336aad7ae1a5674c39757b3a9b88cfc561e8b8fa91c67d5f82', '-i/probe/source/cardano-diffusion/lib', '-i/probe/source/cardano-diffusion/api/lib', '-XImportQualifiedPost', '-odir', '/observer', '-hidir', '/observer', '/observer/Main.hs', '-o', '/observer/root-fallback-observer']
    # Compiler arguments are fixed; paths are only passed as separate Docker arguments.
    shell = " ".join(command) + " && /observer/root-fallback-observer > /observer/decisions.jsonl"
    argv = ["docker", "run", "--rm", "--name", args.container, "--network", "none",
            "--entrypoint", "bash", "-v", f"{reference}:/probe:ro",
            "-v", f"{probe}:/observer", args.image, "-c", shell + "; code=$?; chown -R 1000:1000 /observer; exit $code"]
    with (probe / "build.log").open("w") as log:
        subprocess.run(argv, stdout=log, stderr=subprocess.STDOUT, check=True)
    rows = [json.loads(line) for line in (probe / "decisions.jsonl").read_text().splitlines()]
    if len(rows) != 9:
        raise ValueError("incomplete native fallback output")
    image = subprocess.check_output(["docker", "image", "inspect", args.image,
                                     "--format", "{{.Id}}"], text=True).strip()
    manifest = {
        "schema": "scarab-native-root-fallback-v1", "sourceCommit": PIN,
        "sourceFiles": SOURCE_FILES,
        "observerSha256": sha(HERE / "Main.hs"), "exporterSha256": sha(Path(__file__)),
        "sha256": sha(probe / "decisions.jsonl"), "cases": len(rows),
        "generatedCases": 0, "boundaryCases": len(rows),
        "inputs": "explicit Big ledger request; ledger unavailable/empty/populated; requested counts0/1/4; two literal-IP public roots with distinct advertise flags",
        "boundary": "actual requestPublicRootPeersImpl with constant resolver and no DNS lookups; ledger/big-ledger/ordinary partitions and advertise flags compared through production BigLedgerRoots.fromLedger; native TTL retained for provider evidence",
        "image": image, "executableSha256": sha(probe / "root-fallback-observer"),
        "planSha256": sha(reference / "builddir/cache/plan.json"),
        "packageIds": [command[i + 1] for i, arg in enumerate(command) if arg == "-package-id"], "command": argv,
    }
    args.output_dir.mkdir(parents=True, exist_ok=True)
    (args.output_dir / "decisions.jsonl").write_bytes((probe / "decisions.jsonl").read_bytes())
    (args.output_dir / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")


if __name__ == "__main__":
    main()
