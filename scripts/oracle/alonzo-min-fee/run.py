# SPDX-License-Identifier: Apache-2.0
"""Compute the minimum fee of the cardano-node issue 4228 transaction with the released ledger.

Compiles `Main.hs` in the context-reference image (released cardano-ledger-alonzo of the 11.1.3 build)
with no network and reads `fixtures/reference/alonzo-min-fee/hex-block-node-issue-4228.cbor`. It prints
what the ledger computed and, with `--check`, fails unless that is the `reference` record of the corpus's
`provenance.json`.

    python3 scripts/oracle/alonzo-min-fee/run.py --check
"""
import argparse
import json
from pathlib import Path
import subprocess

LABEL = "xyz.kd5ujc.scarab"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--image", default="scarab-plutus176-context-reference:local")
    parser.add_argument("--name", default="scarab-alonzo-min-fee")
    parser.add_argument("--owner", default="alonzo-min-fee", help="the owner label of the container")
    parser.add_argument("--check", action="store_true", help="compare with the recorded reference values")
    args = parser.parse_args()
    here = Path(__file__).resolve().parent
    root = here.parents[2]
    corpus = root / "fixtures/reference/alonzo-min-fee"
    command = ["docker", "run", "--rm", "--name", args.name, "--network", "none",
               "--label", f"{LABEL}.kind=oracle", "--label", f"{LABEL}.owner={args.owner}",
               "--cpus", "2", "--memory", "4g", "--tmpfs", "/build:exec",
               "-v", f"{here}:/probe:ro", "-v", f"{corpus}:/corpus:ro",
               "--entrypoint", "sh", args.image, "-c",
               "cabal exec -- ghc -Wall -Werror -L/usr/local/lib -optl-Wl,-rpath,/usr/local/lib "
               "-package cardano-ledger-alonzo -package cardano-ledger-core -package cardano-ledger-binary "
               "-package cardano-crypto-class -package cborg -package aeson -package base16-bytestring "
               "-package microlens -outputdir /build /probe/Main.hs -o /build/oracle >&2 "
               "&& /build/oracle /corpus/hex-block-node-issue-4228.cbor"]
    result = subprocess.run(command, capture_output=True, text=True)
    if result.returncode:
        raise SystemExit(result.stderr)
    actual = json.loads(result.stdout)
    print(json.dumps(actual, sort_keys=True))
    if args.check:
        expected = json.loads((corpus / "provenance.json").read_text())["reference"]
        if actual != expected:
            raise SystemExit(f"the released ledger computed {actual}, the corpus records {expected}")
        print("the released ledger computes the recorded minimum fee")


if __name__ == "__main__":
    main()
