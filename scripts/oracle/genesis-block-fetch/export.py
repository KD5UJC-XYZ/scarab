#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Observe pinned native BlockFetch decisions offline; preserve generated inputs unchanged."""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import shutil

PIN = "2fb69829a19b5c04e4fb212bdd78fb63f9d2d776"
HERE = Path(__file__).resolve().parent
SOURCE_FILES = [
    "ouroboros-network/lib/Ouroboros/Network/BlockFetch/Decision.hs",
    "ouroboros-network/lib/Ouroboros/Network/BlockFetch/Decision/Genesis.hs",
    "ouroboros-network/lib/Ouroboros/Network/BlockFetch/DeltaQ.hs",
    "ouroboros-network/lib/Ouroboros/Network/DeltaQ.hs",
    "ouroboros-network/tests/lib/Test/Ouroboros/Network/BlockFetch.hs",
    "ouroboros-network/api/tests-lib/Ouroboros/Network/Mock/ChainGenerators.hs",
]
CABAL = '''\nexecutable scarab-genesis-fetch-observer
  hs-source-dirs: scarab-genesis-fetch-observer
  main-is: Main.hs
  build-depends:
    base, aeson, bytestring, containers, QuickCheck, contra-tracer,
    io-classes:{io-classes, si-timers},
    ouroboros-network:{ouroboros-network, api, api-tests-lib, framework, protocols, ouroboros-network-tests-lib}
  default-language: Haskell2010
'''

def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cache-build", type=Path)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--probe-dir", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--image", default="scarab-148-fetch-reference-cache:local")
    parser.add_argument("--allow-download", action="store_true", help="allow Cabal to fetch missing pinned dependencies")
    parser.add_argument("--container", default="scarab-33-genesis-fetch-reference")
    args = parser.parse_args()
    probe = args.probe_dir.resolve()
    probe.mkdir(parents=True, exist_ok=True)
    commands = []
    def run(argv):
        log = probe / f"command-{len(commands)}.log"
        with log.open("w") as output:
            result = subprocess.run(argv, stdout=output, stderr=subprocess.STDOUT, text=True)
        commands.append({"argv": argv, "returncode": result.returncode, "logSha256": sha(log)})
        (probe / "commands.json").write_text(json.dumps(commands, indent=2) + "\n")
        if result.returncode:
            raise RuntimeError(f"command failed: {log}")
        return log.read_text().strip()
    if run(["git", "-C", str(args.source), "rev-parse", "HEAD"]) != PIN:
        raise ValueError("source pin mismatch")
    source = probe / "source"
    source.mkdir()
    archive = probe / "source.tar"
    run(["git", "-C", str(args.source), "archive", "--format=tar", "-o", str(archive), PIN])
    run(["tar", "-xf", str(archive), "-C", str(source)])
    archive.unlink()
    target = source / "ouroboros-network/scarab-genesis-fetch-observer"
    target.mkdir()
    (target / "Main.hs").write_bytes((HERE / "Main.hs").read_bytes())
    (target / "Shared.hs").write_bytes((HERE / "Shared.hs").read_bytes())
    cabal = source / "ouroboros-network/ouroboros-network.cabal"
    with cabal.open("a") as out:
        out.write(CABAL)
    if args.cache_build:
        shutil.copytree(args.cache_build, probe / "builddir")
    created = False
    try:
        run(["docker", "create", "--network=" + ("bridge" if args.allow_download else "none"), "--name", args.container, "--entrypoint", "tail",
             "-v", f"{source}:/source", "-v", f"{probe}:/probe", "-w", "/source", args.image, "-f", "/dev/null"])
        created = True
        run(["docker", "start", args.container])
        base = ["docker", "exec", "-w", "/source", args.container]
        run(base + ["cabal", "build", *([] if args.allow_download else ["--offline"]), "ouroboros-network:exe:scarab-genesis-fetch-observer", "--builddir=/probe/builddir", "--jobs=2"])
        binary = run(base + ["cabal", "list-bin", "ouroboros-network:exe:scarab-genesis-fetch-observer", "--builddir=/probe/builddir"]).splitlines()[-1]
        run(base + [binary, "/probe/decisions.jsonl"])
        rows = [json.loads(line) for line in (probe / "decisions.jsonl").read_text().splitlines()]
        manifest = {"schema": "scarab-native-genesis-fetch-v1", "sourceCommit": PIN,
                    "sourceFiles": {p: sha(source / p) for p in SOURCE_FILES},
                    "observerSha256": sha(HERE / "Main.hs"), "exporterSha256": sha(Path(__file__)),
                    "sha256": sha(probe / "decisions.jsonl"), "cases": len(rows), "generatedCases": sum(row["label"].startswith("generated-") for row in rows),
                    "boundaryCases": sum(not row["label"].startswith("generated-") for row in rows),
                    "sharedSha256": sha(HERE / "Shared.hs"),
                    
                    "generator": "unchanged Arbitrary TestChainFork and PeerGSVT; seed 330001+ordinal, size20+ordinal mod20; Genesis mode",
                    "boundary": "native fetchDecisionsGenesisM and preprocessing inputs",
                    "hashRepresentation": "lossless signed Int XOR sign-bit, big-endian 8-byte hex; preserves native hash ordering",
                    "image": run(["docker", "inspect", args.container, "--format", "{{.Image}}"]),
                    "executableSha256": run(base + ["sha256sum", binary]).split()[0],
                    "planSha256": sha(probe / "builddir/cache/plan.json")}
        args.output_dir.mkdir(parents=True, exist_ok=True)
        (args.output_dir / "decisions.jsonl").write_bytes((probe / "decisions.jsonl").read_bytes())
        
        (args.output_dir / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    finally:
        if created:
            run(["docker", "rm", "-f", args.container])

if __name__ == "__main__":
    main()
