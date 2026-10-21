#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Observe unchanged pinned Genesis code and generators inside an isolated native build."""
from __future__ import annotations
import argparse
import hashlib
import gzip
import json
import os
import sys
from pathlib import Path
import shutil
import subprocess

PIN = "82ecba329d7d054340bf707d44fe6e9ac27cec40"
HERE = Path(__file__).resolve().parent
FILES = [
    "ouroboros-consensus/src/ouroboros-consensus/Ouroboros/Consensus/Genesis/Governor.hs",
    "ouroboros-consensus/src/ouroboros-consensus/Ouroboros/Consensus/MiniProtocol/ChainSync/Client/HistoricityCheck.hs",
    "ouroboros-consensus-diffusion/src/ouroboros-consensus-diffusion/Ouroboros/Consensus/Node/RethrowPolicy.hs",
    "ouroboros-consensus/test/consensus-test/Test/Consensus/MiniProtocol/ChainSync/CSJ.hs",
    "ouroboros-consensus/src/ouroboros-consensus/Ouroboros/Consensus/Storage/ChainDB/Impl/ChainSel.hs",
    "ouroboros-consensus/src/ouroboros-consensus/Ouroboros/Consensus/Util/LeakyBucket.hs",
    "ouroboros-consensus/src/unstable-consensus-testlib/Test/Util/TestBlock.hs",
    "ouroboros-consensus/src/ouroboros-consensus/Ouroboros/Consensus/MiniProtocol/ChainSync/Client/Jumping.hs",
    "ouroboros-consensus/src/ouroboros-consensus/Ouroboros/Consensus/MiniProtocol/ChainSync/Client/State.hs",
    "ouroboros-consensus-diffusion/test/consensus-test/Test/Consensus/Genesis/Tests/LoE.hs",
    "ouroboros-consensus-diffusion/test/consensus-test/Test/Consensus/Genesis/Tests/DensityDisconnect.hs",
    "ouroboros-consensus-diffusion/test/consensus-test/Test/Consensus/Genesis/Tests/LoP.hs",
    "ouroboros-consensus-diffusion/test/consensus-test/Test/Consensus/Genesis/Tests/CSJ.hs",
    "ouroboros-consensus-diffusion/test/consensus-test/Test/Consensus/Genesis/Tests/Uniform.hs",
    "ouroboros-consensus-diffusion/test/consensus-test/Test/Consensus/Genesis/Tests/LongRangeAttack.hs",
    "ouroboros-consensus-diffusion/test/consensus-test/Test/Consensus/Genesis/Setup/GenChains.hs",
]
BASIC = '''\nexecutable {name}\n  import: common-test\n  ld-options: -Wl,--whole-archive /usr/local/lib/libsodium.a -Wl,--no-whole-archive\n  hs-source-dirs: scarab-genesis-observer\n  main-is: {main}\n  build-depends:\n    base, aeson, bytestring, containers, QuickCheck, time,\n    cardano-ledger-core, cardano-slotting, cardano-strict-containers, io-sim,\n    io-classes:{{io-classes, si-timers, strict-stm}},\n    ouroboros-network:{{api, api-tests-lib}},\n    ouroboros-consensus:{{ouroboros-consensus, unstable-consensus-testlib}}\n'''

def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()

def append_observers(source: Path) -> None:
    cabal = source / "ouroboros-consensus.cabal"
    original = cabal.read_text()
    target = source / "scarab-genesis-observer"
    target.mkdir(exist_ok=True)
    for file in ["Main.hs", "MainBucket.hs", "MainLoE.hs", "MainHistoryWindow.hs"]:
        shutil.copyfile(HERE / file, target / file)
    added = BASIC.format(name="scarab-genesis-density", main="Main.hs")
    added += BASIC.format(name="scarab-genesis-bucket", main="MainBucket.hs")
    added += BASIC.format(name="scarab-genesis-history-window", main="MainHistoryWindow.hs").replace(
        "base, aeson, bytestring, containers, QuickCheck, time,",
        "base, aeson, bytestring, containers, QuickCheck, time, sop-core, sop-extras,"
    )
    # Reuse the exact upstream simulator source set and its declared dependencies.
    suite = original.split("test-suite consensus-diffusion-test\n", 1)[1].split("\nlibrary cardano", 1)[0]
    suite = suite.replace("  type: exitcode-stdio-1.0\n", "")
    suite = suite.replace("  import: common-test\n", "  import: common-test\n  ld-options: -Wl,--whole-archive /usr/local/lib/libsodium.a -Wl,--no-whole-archive\n")
    suite = suite.replace("  main-is: Main.hs", "  main-is: MainLoE.hs")
    suite = suite.replace("  hs-source-dirs: ouroboros-consensus-diffusion/test/consensus-test", "  hs-source-dirs: scarab-genesis-observer ouroboros-consensus-diffusion/test/consensus-test")
    suite = suite.replace("  build-depends:\n", "  build-depends:\n    aeson,\n", 1)
    added += "\nexecutable scarab-genesis-loe\n" + suite
    csj = source / "scarab-csj-observer"
    csj.mkdir(exist_ok=True)
    shutil.copyfile(HERE.parent / "genesis-chain-sync/Main.hs", csj / "Main.hs")
    added += """\nexecutable scarab-csj-observer
  import: common-test
  ld-options: -Wl,--whole-archive /usr/local/lib/libsodium.a -Wl,--no-whole-archive
  hs-source-dirs: scarab-csj-observer
  main-is: Main.hs
  build-depends:
    base, aeson, bytestring, containers, QuickCheck, contra-tracer,
    cardano-strict-containers, strict-sop-core,
    ouroboros-consensus:{ouroboros-consensus,unstable-consensus-testlib},
    ouroboros-network:{api,api-tests-lib}
"""
    history = source / "scarab-historicity-observer"
    history.mkdir(exist_ok=True)
    shutil.copyfile(HERE.parent / "genesis-historicity/Main.hs", history / "Main.hs")
    added += """\nexecutable scarab-historicity-observer
  import: common-test
  ld-options: -Wl,--whole-archive /usr/local/lib/libsodium.a -Wl,--no-whole-archive
  hs-source-dirs: scarab-historicity-observer
  main-is: Main.hs
  build-depends:
    base, aeson, bytestring, time,
    ouroboros-consensus:{ouroboros-consensus,diffusion,unstable-consensus-testlib},
    ouroboros-network:{api,api-tests-lib,framework,ouroboros-network}
"""
    cabal.write_text(original + added)

def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--probe-dir", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--image", default="scarab-v02-49-protocols-cache:peer-acceptance-20261019")
    parser.add_argument("--cache-build", type=Path)
    parser.add_argument("--allow-download", action="store_true")
    parser.add_argument("--container", default="scarab-33-genesis-reference")
    args = parser.parse_args()
    probe = args.probe_dir.resolve()
    probe.mkdir(parents=True, exist_ok=True)
    commands: list[dict] = []
    def run(argv: list[str]) -> str:
        log = probe / f"command-{len(commands)}.log"
        with log.open("w") as out:
            result = subprocess.run(argv, stdout=out, stderr=subprocess.STDOUT, text=True, check=False)
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
    append_observers(source)
    if args.cache_build:
        shutil.copytree(args.cache_build, probe / "builddir")
    created = False
    try:
        run(["docker", "create", "--network=" + ("bridge" if args.allow_download else "none"), "--name", args.container,
             "--entrypoint", "tail", "-v", f"{probe}:/probe", "-w", "/probe/source", args.image, "-f", "/dev/null"])
        created = True
        run(["docker", "start", args.container])
        base = ["docker", "exec", args.container]
        # Build configuration comes from the immutable reference cache; production function bodies
        # and generators remain byte-for-byte identical to the source pin.
        run(base + ["sh", "-c", "sed 's/packages: harness/packages: ./' /oracle/project/cabal.project > /probe/source/cabal.project; printf '\\npackage blockio\\n  flags: +serialblockio\\n' >> /probe/source/cabal.project"])
        names = {"density": "scarab-genesis-density", "bucket": "scarab-genesis-bucket", "loe": "scarab-genesis-loe", "csj": "scarab-csj-observer", "historicity": "scarab-historicity-observer", "history-window": "scarab-genesis-history-window"}
        run(base + ["cabal", "build", *([] if args.allow_download else ["--offline"]),
                    *[f"exe:{binary}" for binary in names.values()], "--builddir=/probe/builddir", "--jobs=3"])
        executables = {}
        for name in names:
            binary = run(base + ["cabal", "list-bin", f"exe:{names[name]}", "--builddir=/probe/builddir"]).splitlines()[-1]
            run(base + [binary, f"/probe/{name}.jsonl"])
            executables[name] = run(base + ["sha256sum", binary]).split()[0]
        output = args.output_dir
        output.mkdir(parents=True, exist_ok=True)
        manifest = {"schema": "scarab-native-genesis-v1", "sourceCommit": PIN,
                    "sourceFiles": {p: sha(source / p) for p in FILES},
                    "observers": {p.name: sha(p) for p in HERE.glob("*.hs")},
                    "csjObserverSha256": sha(HERE.parent / "genesis-chain-sync/Main.hs"),
                    "historicityObserverSha256": sha(HERE.parent / "genesis-historicity/Main.hs"),
                    "exporterSha256": sha(Path(__file__)),
                    "buildProjectSha256": sha(source / "cabal.project"),
                    "planSha256": sha(probe / "builddir/cache/plan.json"),
                    "image": run(["docker", "inspect", args.container, "--format", "{{.Image}}"]),
                    "executables": executables,"corpora": {}}
        for name in names:
            path = probe / f"{name}.jsonl"
            rows = [json.loads(line) for line in path.read_text().splitlines()]
            destination = output / (path.name + ".gz" if name == "loe" else path.name)
            if name == "csj":
                destination = output.parent / "genesis-chain-sync" / path.name
            elif name == "historicity":
                destination = output.parent / "genesis-historicity" / path.name
            destination.parent.mkdir(parents=True, exist_ok=True)
            key = os.path.relpath(destination,output)
            payload = path.read_bytes()
            destination.write_bytes(gzip.compress(payload, mtime=0) if name == "loe" else payload)
            manifest["corpora"][key] = {"sha256": sha(destination), "cases": len(rows)}
            if name == "loe":
                manifest["corpora"][key]["uncompressedSha256"] = sha(path)
                manifest["corpora"][key]["familyCases"] = {
                    family: sum(row["family"] == family for row in rows)
                    for family in sorted({row["family"] for row in rows})}
                manifest["corpora"][key]["allPropertiesPassed"] = all(row["propertyPassed"] for row in rows)
        # Emit the canonical per-policy manifests through the same source-verifying wrappers.
        for directory in ["genesis-chain-sync", "genesis-historicity"]:
            run([sys.executable, str(HERE.parent / directory / "export.py"),
                 "--container", args.container, "--source", str(source),
                 "--upstream", str(args.source), "--probe-dir", str(probe),
                 "--output-dir", str(output.parent / directory)])
        (output / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    finally:
        if created:
            run(["docker", "rm", "-f", args.container])

if __name__ == "__main__":
    main()
