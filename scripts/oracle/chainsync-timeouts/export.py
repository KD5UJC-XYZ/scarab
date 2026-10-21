#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Observe unchanged pinned Cardano ChainSync state deadlines and random-1.3.1 scaling."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import subprocess

PIN = "2fb69829a19b5c04e4fb212bdd78fb63f9d2d776"
HERE = Path(__file__).resolve().parent
SOURCE_FILES = {'cardano-diffusion/protocols/lib/Cardano/Network/Protocol/ChainSync/Codec/TimeLimits.hs': '9a0d1a4e471b5a3a648cbe635813757a6e8736f899e8b4f4c288a0a7f430c61e', 'cardano-diffusion/api/lib/Cardano/Network/PeerSelection/PeerTrustable.hs': 'c40229b9ff474b41367351c25d4cda0b9df9748f80dfc09beba6f06bca55619e', 'ouroboros-network/protocols/lib/Ouroboros/Network/Protocol/ChainSync/Codec.hs': 'ba2c582fa0e4ae02f26c0e9f412da8a6ce3384599a76f55c671f7a916a8c3fb1', 'ouroboros-network/protocols/lib/Ouroboros/Network/Protocol/ChainSync/Type.hs': '10da5113837e7e7276f6be20eec97289e64704f0a6937d707bcefb8af005e955', 'ouroboros-network/api/lib/Ouroboros/Network/Protocol/Limits.hs': '85d22f3f9af5c94c5dc67897860a10a1432a02a4574b6c4ad0c9cc5394b04a36'}


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--reference-build", type=Path, required=True,
                        help="existing native build containing source/ and builddir/; mounted read-only")
    parser.add_argument("--probe-dir", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--image", default="scarab-148-fetch-reference-cache:local")
    parser.add_argument("--container", default="scarab-native-chainsync-timeouts")
    args = parser.parse_args()
    reference = args.reference_build.resolve()
    probe = args.probe_dir.resolve()
    probe.mkdir(parents=True, exist_ok=False)
    for source, expected in SOURCE_FILES.items():
        if sha(reference / "source" / source) != expected:
            raise ValueError(f"reference source does not match the maintained pin: {source}")
    (probe / "Main.hs").write_bytes((HERE / "Main.hs").read_bytes())
    command = ['ghc', '-package-id', 'typed-protocols-1.2.1.0-l-cborg-2c3191b9d59351d4798b8d0a6a8b70d5dad0ec19d607413fa0f86a45b54924e9', '-package-db', '/root/.local/state/cabal/store/ghc-9.10.3-1be3/package.db', '-package-db', '/probe/builddir/packagedb/ghc-9.10.3', '-hide-all-packages', '-package', 'base', '-package-id', 'aeson-2.2.5.0-e1b275ce1b1bb82fc5b92edfc0b257272f0bab3f3562d042f5c7807be2bf93fe', '-package', 'bytestring', '-package', 'containers', '-package-id', 'random-1.3.1-126384cddacb0aa6510d40806ebac2032b3c523eeb82c954bdc3f0003dcc45cf', '-package', 'quiet', '-package', 'nothunks', '-package-id', 'contra-tracer-0.2.1.0-2bf491b9d4bab34147cbbe69bf5b0f426e60fa2bd09ddb675bf3bcc5097cc8ba', '-package', 'monoidal-synchronisation', '-package-id', 'ouroboros-network-1.3.0.0-inplace', '-package-id', 'ouroboros-network-1.3.0.0-inplace-api', '-package-id', 'io-classes-1.8.0.1-81db6f9dc4fa77b4eca2c3d0a055628e2f895bdba382956ab58a9877640ca2d0', '-package-id', 'io-classes-1.8.0.1-l-si-timers-5e6d1bd5dca51400e474c18cd34c890c14330fb1007bfbbdcc06f7bb7760619e', '-package-id', 'io-classes-1.8.0.1-l-strict-stm-df137760a3eaf3e7e1983b3b8d55deca78a4a9cd1c1de4b61964654e219d232c', '-package-id', 'io-sim-1.8.0.1-a0a0aa3c1d5bb8336aad7ae1a5674c39757b3a9b88cfc561e8b8fa91c67d5f82', '-i/probe/source/cardano-diffusion/lib', '-i/probe/source/cardano-diffusion/api/lib', '-XImportQualifiedPost', '-odir', '/observer', '-hidir', '/observer', '/observer/Main.hs', '-o', '/observer/timeouts-observer', '-package-id', 'ouroboros-network-1.3.0.0-inplace-protocols', '-package-id', 'ouroboros-network-1.3.0.0-inplace-framework', '-i/probe/source/cardano-diffusion/protocols/lib']
    # Compiler arguments are fixed; paths are only passed as separate Docker arguments.
    shell = " ".join(command) + " && /observer/timeouts-observer > /observer/decisions.jsonl"
    argv = ["docker", "run", "--rm", "--name", args.container, "--network", "none",
            "--entrypoint", "bash", "-v", f"{reference}:/probe:ro",
            "-v", f"{probe}:/observer", args.image, "-c", shell + "; code=$?; chown -R 1000:1000 /observer; exit $code"]
    with (probe / "build.log").open("w") as log:
        subprocess.run(argv, stdout=log, stderr=subprocess.STDOUT, check=True)
    rows = [json.loads(line) for line in (probe / "decisions.jsonl").read_text().splitlines()]
    if len(rows) != 391 or sum(row["kind"] == "state" for row in rows) != 384 or sum(row["kind"] == "word" for row in rows) != 7:
        raise ValueError("incomplete native deadline output")
    image = subprocess.check_output(["docker", "image", "inspect", args.image,
                                     "--format", "{{.Id}}"], text=True).strip()
    manifest = {
        "schema": "scarab-native-chainsync-timeouts-v1", "sourceCommit": PIN,
        "sourceFiles": SOURCE_FILES,
        "observerSha256": sha(HERE / "Main.hs"), "exporterSha256": sha(Path(__file__)),
        "sha256": sha(probe / "decisions.jsonl"), "cases": len(rows),
        "generatedCases": 0, "boundaryCases": len(rows),
        "inputs": "eight seeds; both trust values and idle settings; twelve active-state entries; seven explicit unsigned Word64 boundaries",
        "boundary": "all native active-state deadlines and RNG transitions retained; native randomR draws cross-checked against uniform Word64 and scaleFloating; Scala client receive states and exact sample conversion compared",
        "image": image, "executableSha256": sha(probe / "timeouts-observer"),
        "planSha256": sha(reference / "builddir/cache/plan.json"),
        "packageIds": [command[i + 1] for i, arg in enumerate(command) if arg == "-package-id"], "command": argv,
    }
    args.output_dir.mkdir(parents=True, exist_ok=True)
    (args.output_dir / "decisions.jsonl").write_bytes((probe / "decisions.jsonl").read_bytes())
    (args.output_dir / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")


if __name__ == "__main__":
    main()
