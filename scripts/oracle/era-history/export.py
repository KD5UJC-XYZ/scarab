#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Capture the reference's GetInterpreter answers (#118) from the unchanged pinned ouroboros-consensus.

The program `Main.hs` is built as one more executable of the pinned source, offline, in the
reference build cache image, and run on the repository's preprod genesis files. It writes
`fixtures/reference/era-history/cases.jsonl` and `provenance.json`.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import subprocess
import sys
from pathlib import Path

PIN = "82ecba329d7d054340bf707d44fe6e9ac27cec40"
HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
FIXTURE = ROOT / "fixtures/reference/era-history"
GENESIS = ["fixtures/preprod/genesis/byron-genesis.json", "fixtures/preprod/genesis/shelley-genesis.json"]
IMAGE = "scarab-v02-49-protocols-cache:peer-acceptance-20261019"
EXECUTABLE = "scarab-era-history"
STANZA = f"""
executable {EXECUTABLE}
  import: common-test
  ld-options: -Wl,--whole-archive /usr/local/lib/libsodium.a -Wl,--no-whole-archive
  hs-source-dirs: scarab-era-history
  main-is: Main.hs
  build-depends:
    base, aeson, bytestring, mtl, serialise, sop-core, sop-extras,
    cardano-crypto-wrapper, cardano-ledger-byron, cardano-ledger-shelley, cardano-slotting,
    ouroboros-consensus:{{ouroboros-consensus, cardano}}
"""


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True, help="an ouroboros-consensus checkout holding the pin")
    parser.add_argument("--probe-dir", type=Path, required=True, help="an empty scratch directory")
    parser.add_argument("--container", default="scarab-118-era-history")
    parser.add_argument("--jobs", type=int, default=8)
    parser.add_argument("--keep", action="store_true", help="keep the build container (and its package store)")
    parser.add_argument("--allow-download", action="store_true",
                        help="let cabal fetch the planned packages (pinned by the project's index-state)")
    args = parser.parse_args()
    probe = args.probe_dir.resolve()
    reuse = subprocess.run(["docker", "inspect", args.container], capture_output=True).returncode == 0
    probe.mkdir(parents=True, exist_ok=reuse)

    def run(argv: list[str]) -> str:
        done = subprocess.run(argv, capture_output=True, text=True)
        (probe / "last-command.log").write_text(done.stdout + done.stderr)
        if done.returncode:
            raise RuntimeError(f"{argv[:4]} failed; see {probe / 'last-command.log'}")
        return done.stdout.strip()

    source = probe / "source"
    if reuse:
        # A kept container (`--keep`) of an earlier run: its package store is reused, the source re-extracted.
        subprocess.run(["docker", "exec", args.container, "rm", "-rf", "/probe/source"], check=True)
    source.mkdir()
    archive = probe / "source.tar"
    run(["git", "-C", str(args.source), "archive", "--format=tar", "-o", str(archive), PIN])
    run(["tar", "-xf", str(archive), "-C", str(source)])
    archive.unlink()
    (source / EXECUTABLE).mkdir()
    shutil.copyfile(HERE / "Main.hs", source / EXECUTABLE / "Main.hs")
    cabal = source / "ouroboros-consensus.cabal"
    cabal.write_text(cabal.read_text() + STANZA)
    for i, path in enumerate(GENESIS):
        shutil.copyfile(ROOT / path, probe / f"genesis-{i}.json")

    if not reuse:
        run(["docker", "create", "--network=" + ("bridge" if args.allow_download else "none"),
             "--name", args.container, "--label", "xyz.kd5ujc.scarab.kind=oracle",
             "--label", "xyz.kd5ujc.scarab.owner=oracle-era-history", "--entrypoint", "tail",
             "-v", f"{probe}:/probe", "-w", "/probe/source", IMAGE, "-f", "/dev/null"])
    try:
        run(["docker", "start", args.container])
        base = ["docker", "exec", args.container]
        # The build configuration comes from the reference cache image; the source is the pin unchanged.
        run(base + ["sh", "-c", "sed 's/packages: harness/packages: ./' /oracle/project/cabal.project > cabal.project; "
                    "printf '\\npackage blockio\\n  flags: +serialblockio\\n' >> cabal.project"])
        run(base + ["cabal", "build", *([] if args.allow_download else ["--offline"]), f"exe:{EXECUTABLE}",
                    "--builddir=/probe/builddir", f"--jobs={args.jobs}"])
        binary = run(base + ["cabal", "list-bin", f"exe:{EXECUTABLE}", "--builddir=/probe/builddir"]).splitlines()[-1]
        run(base + [binary, "/probe/genesis-0.json", "/probe/genesis-1.json", "/probe/cases.jsonl"])
        executable = run(base + ["sha256sum", binary]).split()[0]
        image = run(["docker", "inspect", args.container, "--format", "{{.Image}}"])
    finally:
        if not args.keep:
            subprocess.run(["docker", "rm", "-f", args.container], capture_output=True)

    rows = [json.loads(line) for line in (probe / "cases.jsonl").read_text().splitlines() if line]
    FIXTURE.mkdir(parents=True, exist_ok=True)
    (FIXTURE / "cases.jsonl").write_text("".join(json.dumps(r, sort_keys=True) + "\n" for r in rows))
    (FIXTURE / "provenance.json").write_text(json.dumps({
        "schemaVersion": 1,
        "scope": "The reference's GetInterpreter answer (reconstructSummary, mkInterpreter, Serialise) for "
                 "hard-fork states of a chain with the preprod genesis; the era parameters are the production "
                 "byronEraParams and shelleyEraParams of those files",
        "consensusRepository": "IntersectMBO/ouroboros-consensus",
        "consensusSourceCommit": PIN,
        "image": IMAGE,
        "imageId": image,
        "program": "scripts/oracle/era-history/Main.hs",
        "programSha256": sha(HERE / "Main.hs"),
        "executableSha256": executable,
        "planSha256": sha(probe / "builddir/cache/plan.json"),
        "genesis": {path: sha(ROOT / path) for path in GENESIS},
        "cases": len(rows),
    }, indent=2, sort_keys=True) + "\n")
    print(f"recorded {len(rows)} cases")
    return 0


if __name__ == "__main__":
    sys.exit(main())
