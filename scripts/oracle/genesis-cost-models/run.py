# SPDX-License-Identifier: Apache-2.0
"""Replay native parameter cost-model upgrades on the original era-capture genesis."""
import argparse
import json
from pathlib import Path
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--image", default="sha256:0d7d3971a5ff78c8a6786459d191f39b166d69a4d71a6eb310c9d8338d6e035e")
    parser.add_argument("--name", default="scarab-genesis-cost-model-proof")
    args = parser.parse_args()
    here = Path(__file__).resolve().parent
    root = here.parents[2]
    expected = json.loads((root / "fixtures/ntc-golden/byron-cost-models/native.json").read_text())
    command = ["docker", "run", "--rm", "--name", args.name, "--network", "none",
               "--cpus", "2", "--memory", "4g", "--tmpfs", "/build:exec",
               "-v", f"{here}:/probe:ro", "-v", f"{root / 'fixtures/eras/genesis'}:/genesis:ro",
               "--entrypoint", "sh", args.image, "-c",
               "cabal exec -- ghc -Wall -Werror -L/usr/local/lib -optl-Wl,-rpath,/usr/local/lib "
               "-package cardano-ledger-core -package cardano-ledger-mary -package cardano-ledger-alonzo "
               "-package cardano-ledger-babbage -package cardano-ledger-conway -package cardano-ledger-binary "
               "-package aeson -package base16-bytestring -package microlens "
               "-outputdir /build /probe/Main.hs -o /build/proof >&2 && /build/proof"]
    result = subprocess.run(command, capture_output=True, text=True)
    if result.returncode:
        raise SystemExit(result.stderr)
    if json.loads(result.stdout) != expected:
        raise SystemExit("native genesis cost-model upgrade differs from the captured result")
    print("All three native era cost-model maps and Conway CBOR match")


if __name__ == "__main__":
    main()
