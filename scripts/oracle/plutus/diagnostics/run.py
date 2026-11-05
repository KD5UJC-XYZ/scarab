#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Replay the pinned native Pretty instances; reject stale source, pins or executable."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile

from inputs import inputs

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
CORPUS = ROOT / "fixtures/reference/plutus-diagnostics"
BASE = "scarab-plutus-ctx-matrix:local"
BASE_ID = "sha256:345960f221ef0e5dd5d6b2db8f085d832212ffa91374b5c54113eb1af5a4a7f5"
IMAGE = "scarab-781-diagnostics:local"


def execute(*args, **kwargs):
    return subprocess.run(args, check=True, capture_output=True, **kwargs).stdout


def sha(data):
    return hashlib.sha256(data).hexdigest()


def image_id(image):
    return execute("docker", "image", "inspect", image, "--format", "{{.Id}}", text=True).strip()


def binding(image):
    files = {HERE / "Main.hs": "/oracle/context-matrix/harness/Main.hs",
             HERE.parent / "context-matrix/cabal.project": "/oracle/context-matrix/cabal.project",
             HERE.parent / "context-matrix/cabal.project.freeze": "/oracle/context-matrix/cabal.project.freeze"}
    for local, embedded in files.items():
        observed = execute("docker", "run", "--rm", "--read-only", "--network", "none",
                           "--entrypoint", "cat", image, embedded)
        if observed != local.read_bytes():
            raise ValueError(f"stale native oracle source/pins: {local.name}")
    binary = execute("docker", "run", "--rm", "--read-only", "--network", "none",
                     "--entrypoint", "sha256sum", image, "/usr/local/bin/scarab-diagnostics-oracle", text=True)
    return {"imageId": image, "oracleBinarySha256": binary.split()[0],
            "harnessSha256": {str(p.relative_to(ROOT)): sha(p.read_bytes())
                              for p in (*files, HERE / "Dockerfile", HERE / "inputs.py", HERE / "run.py")}}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--build", action="store_true")
    parser.add_argument("--record", action="store_true", help="record new native observations and identities")
    args = parser.parse_args()
    if args.build:
        if image_id(BASE) != BASE_ID:
            raise ValueError("native base identity changed; verify the context-matrix build first")
        with tempfile.TemporaryDirectory(prefix="scarab-781-image-") as scratch:
            receipt = Path(scratch) / "image.id"
            subprocess.run(["docker", "build", "-f", str(HERE / "Dockerfile"), "--iidfile", str(receipt),
                            "-t", IMAGE, str(ROOT)], check=True)
            immutable = receipt.read_text().strip()
            if image_id(IMAGE) != immutable:
                raise ValueError("built image and tagged image differ")
    image = image_id(IMAGE)
    identity = binding(image)
    encoded = json.dumps(inputs(), indent=2).encode() + b"\n"
    original = execute("docker", "run", "--rm", "--read-only", "--network", "none", "-i", image, input=encoded)
    observed = (json.dumps(json.loads(original), indent=2) + "\n").encode()
    if args.record:
        provenance = {"schemaVersion": 1, "date": "2026-11-04",
                      "scope": "Complete Pretty context/argument/data text for the finite synthetic typed inputs; no transaction-admission or general CEK-error claim",
                      "ledgerSourceRevision": "f649f9751074d2ab3de033fc3912f29c9862c1f5",
                      "plutusSourceCommit": "507cab8cf6192e5143f12be5b2d3005a2928dc6d",
                      "oracleCompiler": "GHC9.10.3", "plutusPackage": "1.70.0.0",
                      "prettyprinterPackage": "1.7.2", "observations": len(inputs()),
                      "inputSha256": sha(encoded), "outputSha256": sha(observed), **identity}
        (CORPUS / "contexts.json").write_bytes(observed)
        (CORPUS / "provenance.json").write_text(json.dumps(provenance, indent=2) + "\n")
    else:
        accepted = json.loads((CORPUS / "provenance.json").read_text())
        for key, value in identity.items():
            if accepted[key] != value:
                raise ValueError(f"native oracle identity differs: {key}")
        if sha(encoded) != accepted["inputSha256"] or sha(observed) != accepted["outputSha256"]:
            raise ValueError("native input/output differs from accepted observation")
        if observed != (CORPUS / "contexts.json").read_bytes():
            raise ValueError("native replay differs from committed observations")
    print(f"{len(inputs())} complete native diagnostic observations verified")


if __name__ == "__main__":
    main()
