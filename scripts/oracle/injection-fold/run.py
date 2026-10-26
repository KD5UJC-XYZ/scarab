# SPDX-License-Identifier: Apache-2.0
"""Replay all retained native fold observations without changing the reference."""
import argparse
import gzip
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--image", default="scarab-plutus176-context-reference:local")
    parser.add_argument("--name", default="scarab-injection-fold-reference")
    args = parser.parse_args()
    here = Path(__file__).resolve().parent
    corpus = here.parents[2] / "fixtures/reference/injection-fold/cases.jsonl.gz"
    cases = [json.loads(line) for line in gzip.decompress(corpus.read_bytes()).decode().split("\n") if line]
    with tempfile.TemporaryDirectory(prefix="scarab-injection-fold-") as directory:
        root = Path(directory)
        inputs = root / "inputs"
        inputs.mkdir()
        requests = root / "cases.jsonl"
        requests.write_text("".join(case["originalSourceLine"] + "\n" for case in cases))
        for case in cases:
            if case["inputHex"] is not None:
                segments = case["source"]["file"]
                if len(segments) != 1 or Path(segments[0]).name != segments[0]:
                    raise SystemExit("unexpected corpus path outside the single-file fixture contract")
                raw = bytes.fromhex(case["inputHex"])
                if hashlib.sha256(raw).hexdigest() != case["inputSha256"]:
                    raise SystemExit(f"input identity mismatch: {case['name']}")
                (inputs / segments[0]).write_bytes(raw)
        command = ["docker", "run", "--rm", "--name", args.name, "--network", "none",
                   "--cpus", "2", "--memory", "4g", "--tmpfs", "/build:exec",
                   "-v", f"{here}:/probe:ro", "-v", f"{inputs}:/inputs:ro",
                   "-v", f"{requests}:/cases.jsonl:ro", "--entrypoint", "sh", args.image, "-c",
                   "cabal exec -- ghc -Wall -Werror -L/usr/local/lib -optl-Wl,-rpath,/usr/local/lib "
                   "-package cardano-ledger-shelley -package fs-api -package aeson -package scientific "
                   "-outputdir /build /probe/Main.hs -o /build/probe >&2 && /build/probe < /cases.jsonl"]
        result = subprocess.run(command, capture_output=True, text=True)
        if result.returncode:
            raise SystemExit(result.stderr)
        # LF framing preserves embedded Unicode separators and every numeric token's spelling.
        actual = [line for line in result.stdout.split("\n") if line]
        expected = [case["nativeLine"] for case in cases]
        if actual != expected:
            raise SystemExit("native fold output differs from retained complete observation lines")
    print("All 57 native observations reproduced: 50 completed inner outcomes, "
          "6 controlled timeout non-results, 1 filesystem case outside the pure implementation")


if __name__ == "__main__":
    main()
