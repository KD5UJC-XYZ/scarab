#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Export complete native historicity traces from the shared pinned Genesis observer build.

Prepare the source/cache with scripts/oracle/genesis-density/export.py. This command never
substitutes a Scala implementation or drops an outcome from the native observer.
"""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path
import subprocess

HERE = Path(__file__).resolve().parent
PIN = "82ecba329d7d054340bf707d44fe6e9ac27cec40"  # checked against --upstream below
FILES = [
    "ouroboros-consensus/src/ouroboros-consensus/Ouroboros/Consensus/MiniProtocol/ChainSync/Client/HistoricityCheck.hs",
    "ouroboros-consensus/src/ouroboros-consensus/Ouroboros/Consensus/MiniProtocol/ChainSync/Client.hs",
    "ouroboros-consensus-diffusion/src/ouroboros-consensus-diffusion/Ouroboros/Consensus/Node/RethrowPolicy.hs",
    "ouroboros-consensus-diffusion/src/ouroboros-consensus-diffusion/Ouroboros/Consensus/Node/Genesis.hs",
]

def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--container",required=True)
    parser.add_argument("--source",type=Path,required=True)
    parser.add_argument("--upstream",type=Path,required=True)
    parser.add_argument("--probe-dir",type=Path,required=True)
    parser.add_argument("--output-dir",type=Path,required=True)
    args=parser.parse_args()
    commit=subprocess.check_output(["git","-C",str(args.upstream),"rev-parse","HEAD"],text=True).strip()
    if commit != PIN:
        raise ValueError("consensus pin mismatch")
    for name in FILES:
        original=subprocess.check_output(["git","-C",str(args.upstream),"show",f"{commit}:{name}"])
        if (args.source/name).read_bytes()!=original:
            raise ValueError(f"native source differs from pin: {name}")
    if (args.source/"scarab-historicity-observer/Main.hs").read_bytes()!=(HERE/"Main.hs").read_bytes():
        raise ValueError("prepared observer source differs; rebuild it before export")
    prefix=["docker","exec","-w","/probe/source",args.container]
    binary=subprocess.check_output(prefix+["cabal","list-bin","exe:scarab-historicity-observer","--builddir=/probe/builddir"],text=True).strip().splitlines()[-1]
    subprocess.run(prefix+[binary,"/probe/historicity.jsonl"],check=True)
    rows=[json.loads(row) for row in (args.probe_dir/"historicity.jsonl").read_text().splitlines()]
    manifest={"schema":"scarab-native-historicity-v1","sourceCommit":commit,
      "sourceFiles":{name:digest(args.source/name) for name in FILES},
      "observerSha256":digest(HERE/"Main.hs"),"exporterSha256":digest(Path(__file__)),
      "sha256":digest(args.probe_dir/"historicity.jsonl"),"cases":len(rows),
      "errorsSha256":digest(args.probe_dir/"historicity.jsonl.errors.jsonl"),
      "coverage":"all 3 GSM states, both messages, genesis and applied header state, 37h cutoff -1/0/+1ps, future time; all native outcomes retained",
      "image":subprocess.check_output(["docker","inspect",args.container,"--format","{{.Image}}"],text=True).strip(),
      "executableSha256":subprocess.check_output(prefix+["sha256sum",binary],text=True).split()[0],
      "planSha256":digest(args.probe_dir/"builddir/cache/plan.json"),
      "observerCabalSha256":digest(args.source/"ouroboros-consensus.cabal"),
      "nativeSodiumSha256":subprocess.check_output(prefix+["sha256sum","/usr/local/lib/libsodium.a"],text=True).split()[0]}
    args.output_dir.mkdir(parents=True,exist_ok=True)
    (args.output_dir/"historicity.jsonl").write_bytes((args.probe_dir/"historicity.jsonl").read_bytes())
    (args.output_dir/"historicity.jsonl.errors.jsonl").write_bytes((args.probe_dir/"historicity.jsonl.errors.jsonl").read_bytes())
    (args.output_dir/"manifest.json").write_text(json.dumps(manifest,indent=2)+"\n")

if __name__=="__main__":
    main()
