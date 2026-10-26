# SPDX-License-Identifier: Apache-2.0
"""Replay exact raw bytes/chunks through the unmodified native json-stream parser."""
import argparse
from dataclasses import dataclass
import gzip
import json
from pathlib import Path
import subprocess
import tempfile


@dataclass(frozen=True)
class NumberToken:
    text: str


def decode(line):
    return json.loads(line, parse_float=NumberToken, parse_int=NumberToken)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--image", default="scarab-plutus176-context-reference:local")
    parser.add_argument("--name", default="scarab-injection-json-reference")
    args = parser.parse_args()
    here = Path(__file__).resolve().parent
    corpus = here.parents[2] / "fixtures/reference/injection-json/cases.jsonl.gz"
    cases = [decode(line) for line in gzip.decompress(corpus.read_bytes()).decode().split("\n") if line]
    expected = [{"name": case["name"], "trace": case["native"]} for case in cases]
    with tempfile.TemporaryDirectory(prefix="scarab-injection-json-") as directory:
        inputs = Path(directory) / "inputs.jsonl"
        inputs.write_text("".join(json.dumps({"name": case["name"], "inputHex": case["inputHex"],
                                             "chunkSize": int(case["chunkSize"].text)}) + "\n"
                                  for case in cases))
        command = ["docker", "run", "--rm", "--name", args.name, "--network", "none",
                   "--cpus", "2", "--memory", "4g", "--tmpfs", "/build:exec",
                   "-v", f"{here}:/probe:ro", "-v", f"{inputs}:/inputs.jsonl:ro",
                   "--entrypoint", "sh", args.image, "-c",
                   "cabal exec -- ghc -Wall -Werror -package aeson -package scientific -package json-stream "
                   "-package base16-bytestring -outputdir /build /probe/Main.hs -o /build/oracle >&2 "
                   "&& /build/oracle < /inputs.jsonl"]
        result = subprocess.run(command, capture_output=True, text=True)
        if result.returncode:
            raise SystemExit(result.stderr)
        actual = [decode(line) for line in result.stdout.split("\n") if line]
    if actual != expected:
        raise SystemExit("native injection JSON replay differs from captured traces")
    print(f"All {len(cases)} native injection JSON traces passed")


if __name__ == "__main__":
    main()
