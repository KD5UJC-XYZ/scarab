#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Observe the unchanged pinned Cardano churn governor in native virtual time."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import subprocess

PIN = "2fb69829a19b5c04e4fb212bdd78fb63f9d2d776"
HERE = Path(__file__).resolve().parent
SOURCE_FILES = {'cardano-diffusion/lib/Cardano/Network/PeerSelection/Churn.hs': '9f67aa647ed9c3fb1117598fd4298f55917d475ccd6987bcc7d708bb69cdac5c', 'cardano-diffusion/lib/Cardano/Network/LedgerPeerConsensusInterface.hs': '6d736e8d54836050bfd5d48ce80f263ab7a9a824b1c42e7002acc13128493f45', 'cardano-diffusion/api/lib/Cardano/Network/ConsensusMode.hs': '5276f02e6178dd2ef237049d4325230a1aad8b052e7ed680dd0be4bf0b8cf268', 'cardano-diffusion/api/lib/Cardano/Network/LedgerStateJudgement.hs': '330b7f5fe339bf60166d32925e42eea92db57de2b11157524539df55b3164656', 'cardano-diffusion/api/lib/Cardano/Network/PeerSelection/Bootstrap.hs': '7e87700127d56960436c7439690eaaee19fc945915001348e69d8cb01ea6c184', 'cardano-diffusion/api/lib/Cardano/Network/PeerSelection/LocalRootPeers.hs': '56dbd9209dd8909f6738193bb39d8bbde9548c22f12ce034196d45c3a815f4c4'}


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--reference-build", type=Path, required=True,
                        help="existing native build containing source/ and builddir/; mounted read-only")
    parser.add_argument("--probe-dir", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--image", default="scarab-148-fetch-reference-cache:local")
    parser.add_argument("--container", default="scarab-native-churn")
    args = parser.parse_args()
    reference = args.reference_build.resolve()
    probe = args.probe_dir.resolve()
    probe.mkdir(parents=True, exist_ok=False)
    for source, expected in SOURCE_FILES.items():
        if sha(reference / "source" / source) != expected:
            raise ValueError(f"reference source does not match the maintained pin: {source}")
    (probe / "Main.hs").write_bytes((HERE / "Main.hs").read_bytes())
    command = ['ghc', '-package-db', '/root/.local/state/cabal/store/ghc-9.10.3-1be3/package.db', '-package-db', '/probe/builddir/packagedb/ghc-9.10.3', '-hide-all-packages', '-package', 'base', '-package-id', 'aeson-2.2.5.0-e1b275ce1b1bb82fc5b92edfc0b257272f0bab3f3562d042f5c7807be2bf93fe', '-package', 'bytestring', '-package', 'containers', '-package', 'random', '-package', 'quiet', '-package', 'nothunks', '-package-id', 'contra-tracer-0.2.1.0-2bf491b9d4bab34147cbbe69bf5b0f426e60fa2bd09ddb675bf3bcc5097cc8ba', '-package', 'monoidal-synchronisation', '-package-id', 'ouroboros-network-1.3.0.0-inplace', '-package-id', 'ouroboros-network-1.3.0.0-inplace-api', '-package-id', 'io-classes-1.8.0.1-81db6f9dc4fa77b4eca2c3d0a055628e2f895bdba382956ab58a9877640ca2d0', '-package-id', 'io-classes-1.8.0.1-l-si-timers-5e6d1bd5dca51400e474c18cd34c890c14330fb1007bfbbdcc06f7bb7760619e', '-package-id', 'io-classes-1.8.0.1-l-strict-stm-df137760a3eaf3e7e1983b3b8d55deca78a4a9cd1c1de4b61964654e219d232c', '-package-id', 'io-sim-1.8.0.1-a0a0aa3c1d5bb8336aad7ae1a5674c39757b3a9b88cfc561e8b8fa91c67d5f82', '-i/probe/source/cardano-diffusion/lib', '-i/probe/source/cardano-diffusion/api/lib', '-XImportQualifiedPost', '-odir', '/observer', '-hidir', '/observer', '/observer/Main.hs', '-o', '/observer/churn-observer']
    # Compiler arguments are fixed; paths are only passed as separate Docker arguments.
    shell = " ".join(command) + " && /observer/churn-observer > /observer/decisions.jsonl"
    argv = ["docker", "run", "--rm", "--name", args.container, "--network", "none",
            "--entrypoint", "bash", "-v", f"{reference}:/probe:ro",
            "-v", f"{probe}:/observer", args.image, "-c", shell + "; code=$?; chown -R 1000:1000 /observer; exit $code"]
    with (probe / "build.log").open("w") as log:
        subprocess.run(argv, stdout=log, stderr=subprocess.STDOUT, check=True)
    rows = [json.loads(line) for line in (probe / "decisions.jsonl").read_text().splitlines()]
    if len(rows) != 1152 or any(len(row["steps"]) != 12 for row in rows):
        raise ValueError("incomplete native decision output")
    image = subprocess.check_output(["docker", "image", "inspect", args.image,
                                     "--format", "{{.Id}}"], text=True).strip()
    manifest = {
        "schema": "scarab-native-churn-v1", "sourceCommit": PIN,
        "sourceFiles": SOURCE_FILES,
        "observerSha256": sha(HERE / "Main.hs"), "exporterSha256": sha(Path(__file__)),
        "sha256": sha(probe / "decisions.jsonl"), "cases": len(rows),
        "generatedCases": 0, "boundaryCases": len(rows),
        "inputs": "explicit lawful native mode pairs; both ledger judgements/bootstrap configurations; four local valencies/seeds; three lawful target bases; static or startup-transition inputs",
        "boundary": "unchanged peerChurnGovernor under IOSim: initial/after-head targets, first-cycle raw proposals, signed achieved changes, sanity flags, timeout outcomes, native DiffTime waits and exact binary-rational fuzz",
        "image": image, "executableSha256": sha(probe / "churn-observer"),
        "planSha256": sha(reference / "builddir/cache/plan.json"),
        "packageIds": [command[i + 1] for i, arg in enumerate(command) if arg == "-package-id"], "command": argv,
    }
    args.output_dir.mkdir(parents=True, exist_ok=True)
    (args.output_dir / "decisions.jsonl").write_bytes((probe / "decisions.jsonl").read_bytes())
    (args.output_dir / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")


if __name__ == "__main__":
    main()
