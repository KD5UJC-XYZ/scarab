# SPDX-License-Identifier: Apache-2.0
"""Replay every original byte input through the installed native strict Aeson decoder."""
import argparse
import gzip
import json
from pathlib import Path
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--image", default="scarab-plutus176-context-reference:local")
    parser.add_argument("--name", default="scarab-native-json-reference")
    args = parser.parse_args()
    here = Path(__file__).resolve().parent
    corpus = here.parents[2] / "fixtures/reference/native-json/cases.jsonl.gz"
    cases = [json.loads(line) for line in gzip.decompress(corpus.read_bytes()).decode().split("\n") if line]
    expected = [{"name": case["name"], **case["native"]} for case in cases]
    with tempfile.TemporaryDirectory(prefix="scarab-native-json-") as directory:
        inputs = Path(directory) / "inputs.jsonl"
        inputs.write_text("".join(json.dumps({"name": case["name"], "inputHex": case["inputHex"]}) + "\n"
                                  for case in cases))
        command = ["docker", "run", "--rm", "--name", args.name, "--network", "none",
                   "--cpus", "2", "--memory", "4g", "--tmpfs", "/build:exec",
                   "-v", f"{here}:/probe:ro", "-v", f"{inputs}:/inputs.jsonl:ro",
                   "--entrypoint", "sh", args.image, "-c",
                   "cabal exec -- ghc -Wall -Werror -package aeson -package scientific "
                   "-package base16-bytestring -outputdir /build /probe/Main.hs -o /build/oracle >&2 "
                   "&& /build/oracle < /inputs.jsonl"]
        result = subprocess.run(command, capture_output=True, text=True)
        if result.returncode:
            raise SystemExit(result.stderr)
        # Only LF delimits records: valid JSON strings may contain literal U+0085/U+2028.
        actual = [json.loads(line) for line in result.stdout.split("\n") if line]
    if actual != expected:
        raise SystemExit("native strict JSON replay differs from captured values/refusals")
    print(f"All {len(cases)} native strict JSON cases passed")


if __name__ == "__main__":
    main()
