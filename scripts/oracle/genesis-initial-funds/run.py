# SPDX-License-Identifier: Apache-2.0
"""Replay the scoped genesis initial-funds corpus through unchanged native Haskell APIs."""
import argparse
import json
from pathlib import Path
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--image", default="scarab-plutus176-context-reference:local")
    parser.add_argument("--name", default="scarab-genesis-initial-funds")
    args = parser.parse_args()
    here = Path(__file__).resolve().parent
    root = here.parents[2]
    expected = [json.loads(line) for line in
                (root / "fixtures/reference/genesis-initial-funds/cases.jsonl").read_text().split("\n") if line]
    with tempfile.TemporaryDirectory(prefix="scarab-genesis-initial-funds-") as directory:
        inputs = Path(directory) / "inputs.jsonl"
        inputs.write_text("".join(json.dumps({key: row[key] for key in ("name", "address", "amount")}) + "\n"
                                  for row in expected))
        command = ["docker", "run", "--rm", "--name", args.name, "--network", "none",
                   "--cpus", "2", "--memory", "4g", "--tmpfs", "/build:exec",
                   "-v", f"{here}:/probe:ro", "-v", f"{inputs}:/inputs.jsonl:ro",
                   "--entrypoint", "sh", args.image, "-c",
                   "cabal exec -- ghc -Wall -Werror -L/usr/local/lib -optl-Wl,-rpath,/usr/local/lib "
                   "-package cardano-ledger-core -package cardano-ledger-shelley -package cardano-ledger-binary -package aeson "
                   "-package base16-bytestring -outputdir /build /probe/Main.hs -o /build/oracle >&2 "
                   "&& /build/oracle < /inputs.jsonl"]
        result = subprocess.run(command, capture_output=True, text=True)
        if result.returncode:
            raise SystemExit(result.stderr)
        actual = [json.loads(line) for line in result.stdout.split("\n") if line]
    if actual != expected:
        raise SystemExit("native genesis initial-funds replay differs from the captured corpus")
    print(f"All {len(expected)} native genesis initial-funds comparisons passed")


if __name__ == "__main__":
    main()
