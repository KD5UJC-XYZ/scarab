# SPDX-License-Identifier: Apache-2.0
"""Replay the genesis pool JSON corpus through the unchanged native StakePoolParams reader."""
import argparse
import json
from pathlib import Path
import subprocess
import tempfile


def replay(here, image, name, inputs):
    with tempfile.TemporaryDirectory(prefix="scarab-genesis-pool-fields-") as directory:
        path = Path(directory) / "inputs.jsonl"
        path.write_text("".join(json.dumps({"name": row["name"], "poolJson": row["poolJson"]}) + "\n"
                                for row in inputs))
        command = ["docker", "run", "--rm", "--name", name, "--network", "none",
                   "--cpus", "2", "--memory", "4g", "--tmpfs", "/build:exec",
                   "-v", f"{here}:/probe:ro", "-v", f"{path}:/inputs.jsonl:ro",
                   "--entrypoint", "sh", image, "-c",
                   "cabal exec -- ghc -Wall -Werror -L/usr/local/lib -optl-Wl,-rpath,/usr/local/lib "
                   "-package cardano-ledger-core -package cardano-ledger-binary -package aeson "
                   "-package base16-bytestring -package text -outputdir /build /probe/Main.hs "
                   "-o /build/oracle >&2 && /build/oracle < /inputs.jsonl"]
        result = subprocess.run(command, capture_output=True, text=True)
        if result.returncode:
            raise SystemExit(result.stderr)
    outputs = [json.loads(line) for line in result.stdout.split("\n") if line]
    # Each row keeps the exact input text; the reference's result follows it.
    return [{"name": row["name"], "poolJson": row["poolJson"],
             **{k: v for k, v in out.items() if k != "name"}}
            for row, out in zip(inputs, outputs, strict=True)]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--image", default="scarab-plutus176-context-reference:local")
    parser.add_argument("--name", default="scarab-genesis-pool-fields")
    parser.add_argument("--capture", type=Path,
                        help="JSON lines of {name, poolJson}: write their native results as the corpus")
    args = parser.parse_args()
    here = Path(__file__).resolve().parent
    corpus = here.parents[2] / "fixtures/reference/genesis-pool-fields/cases.jsonl"
    if args.capture:
        inputs = [json.loads(line) for line in args.capture.read_text().split("\n") if line]
        rows = replay(here, args.image, args.name, inputs)
        corpus.write_text("".join(json.dumps(row, sort_keys=True, ensure_ascii=False) + "\n" for row in rows))
        print(f"Captured {len(rows)} native genesis pool JSON results")
        return
    expected = [json.loads(line) for line in corpus.read_text().split("\n") if line]
    if replay(here, args.image, args.name, expected) != expected:
        raise SystemExit("native genesis pool JSON replay differs from the captured corpus")
    print(f"All {len(expected)} native genesis pool JSON comparisons passed")


if __name__ == "__main__":
    main()
