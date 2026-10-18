# SPDX-License-Identifier: Apache-2.0
"""Offline Byron header decoder and extracted registration-predicate reference checks."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile


ROOT = Path(__file__).resolve().parents[3]
SOURCE = Path(__file__).resolve().parent
FIXTURES = ROOT / "fixtures/byron-version"


def command(args, **kwargs):
    return subprocess.run(args, text=True, capture_output=True, **kwargs)


def image_id(image):
    return command(["docker", "image", "inspect", "--format", "{{.Id}}", image], check=True).stdout.strip()


def run(reference, haskell, output):
    source = (SOURCE / "Main.hs").read_text()
    extraction = json.loads((SOURCE / "extraction.json").read_text())
    excerpts = [
        source[source.index("-- | Communication protocol version"):source.index("\n-- Unchanged ledger", source.index("data ProtocolVersion"))].rstrip() + "\n",
        source[source.index("pvCanFollow ::"):source.index("\ntype Components")].rstrip() + "\n",
    ]
    for excerpt, recorded in zip(excerpts, extraction["excerpts"], strict=True):
        assert hashlib.sha256(excerpt.encode()).hexdigest() == recorded["sha256"], recorded["path"]
    manifest = json.loads((FIXTURES / "headers/manifest.json").read_text())
    transitions = json.loads((FIXTURES / "registration.json").read_text())["cases"]
    with tempfile.TemporaryDirectory(prefix="scarab-byron-oracle-") as temporary:
        work = Path(temporary)
        decoder = []
        for case in manifest["cases"]:
            raw = bytes.fromhex((FIXTURES / "headers" / case["file"]).read_text())
            assert hashlib.sha256(raw).hexdigest() == case["sha256"], case["name"]
            name = case["name"] + ".block"
            (work / name).write_bytes(raw)
            result = command([
                "docker", "run", "--rm", "--network", "none", "-v", f"{work}:/oracle:ro",
                reference, "cardano-cli", "byron", "miscellaneous", "validate-cbor",
                "--byron-block", "EpochSlots {unEpochSlots = 21600}", "--filepath", f"/oracle/{name}",
            ])
            actual = {"name": case["name"], "exitCode": result.returncode,
                      "stdout": result.stdout, "stderr": result.stderr}
            assert actual == case["reference"], actual
            decoder.append(actual)
        tuples = [str((tuple(row["adopted"]), tuple(row["proposed"]))).replace(" ", "")
                  for row in transitions]
        (work / "transitions.txt").write_text("\n".join(tuples) + "\n")
        compiled = command([
            "docker", "run", "--rm", "--network", "none", "-v", f"{SOURCE}:/src:ro",
            "--user", f"{os.getuid()}:{os.getgid()}",
            "-v", f"{work}:/oracle", "-w", "/oracle", haskell,
            "ghc", "-Wall", "-Werror", "-O2", "-outputdir", "/oracle/build",
            "-o", "/oracle/pv-can-follow", "/src/Main.hs",
        ], check=True)
        evaluated = command([
            "docker", "run", "--rm", "--network", "none", "-i", "-v", f"{work}:/oracle:ro",
            haskell, "/oracle/pv-can-follow",
        ], input=(work / "transitions.txt").read_text(), check=True)
        expected = [str(row["accepted"]).lower() for row in transitions]
        assert evaluated.stdout.splitlines() == expected, evaluated.stdout
        report = {"referenceImageId": image_id(reference), "haskellImageId": image_id(haskell),
                  "mainSha256": hashlib.sha256((SOURCE / "Main.hs").read_bytes()).hexdigest(),
                  "decoder": decoder, "registrationVerdicts": expected,
                  "compileExitCode": compiled.returncode, "predicateExitCode": evaluated.returncode}
        output.write_text(json.dumps(report, indent=2) + "\n")
        print(f"Matched {len(decoder)} full-block decoder and {len(expected)} predicate-excerpt cases")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--reference-image", default="cardano-reference-11.1.3:local")
    parser.add_argument("--haskell-image", default="haskell:9.10.3-slim-bookworm")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    run(args.reference_image, args.haskell_image, args.output)
