#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Reproduce complete native diagnostics, binding sources, frozen plan, executable and all bytes."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile

from inputs import all_inputs

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
CORPUS = ROOT / "fixtures/reference/plutus-error-presentation"
BASE = "scarab-plutus-ctx-matrix:local"
BASE_ID = "sha256:345960f221ef0e5dd5d6b2db8f085d832212ffa91374b5c54113eb1af5a4a7f5"
IMAGE = "scarab-796-error-presentation:local"
BINARY = "/usr/local/bin/scarab-error-presentation-oracle"


def sha(data):
    return hashlib.sha256(data).hexdigest()


def encoded(value):
    return (json.dumps(value, indent=2, ensure_ascii=True) + "\n").encode()


def execute(*args, **kwargs):
    process = subprocess.run(args, capture_output=True, **kwargs)
    if process.returncode:
        raise RuntimeError(process.stderr.decode() if isinstance(process.stderr, bytes) else process.stderr)
    return process.stdout


def image_id(image):
    return execute("docker", "image", "inspect", image, "--format", "{{.Id}}", text=True).strip()


def binding(image):
    embedded = {HERE / "Main.hs": "/oracle/context-matrix/harness/Main.hs",
                HERE / "scarab-context-oracle.cabal": "/oracle/context-matrix/harness/scarab-context-oracle.cabal",
                HERE.parent / "context-matrix/cabal.project": "/oracle/context-matrix/cabal.project",
                HERE.parent / "context-matrix/cabal.project.freeze": "/oracle/context-matrix/cabal.project.freeze"}
    for path, remote in embedded.items():
        actual = execute("docker", "run", "--rm", "--read-only", "--network", "none", "--entrypoint", "cat", image, remote)
        if actual != path.read_bytes():
            raise ValueError("stale native observer source or pins: " + path.name)
    binary = execute("docker", "run", "--rm", "--read-only", "--network", "none", "--entrypoint", "sha256sum", image, BINARY, text=True).split()[0]
    sources = [*embedded, HERE / "Dockerfile", HERE / "Dockerfile.dockerignore", HERE / "inputs.py", HERE / "run.py", HERE / "UnicodeProbe.hs", HERE / "unicode.py",
               ROOT / "fixtures/reference/plutus-costing/models.jsonl"]
    return {"imageId": image, "binarySha256": binary,
            "sources": {str(p.relative_to(ROOT)): sha(p.read_bytes()) for p in sources}}


def validate(requests, observations):
    ids = [r["id"] for r in requests]
    if len(ids) != len(set(ids)) or [r["id"] for r in observations] != ids:
        raise ValueError("missing, reordered or duplicate native case identities")
    kinds = {r["kind"] for r in requests}
    if kinds != {"Term", "Universe", "EvaluationError", "HelperError", "Compile", "Evaluate", "Document"}:
        raise ValueError("incomplete native observation boundaries")
    families = {r["subject"]["tag"] for r in observations if r["kind"] == "EvaluationError"}
    if families != {"Cek", "DeBruijn", "Codec", "CostModelParameterMismatch", "InvalidReturnValue"}:
        raise ValueError("incomplete native EvaluationError families")
    profiles = {(r["language"], int(r["protocol"])) for r in requests if r["kind"] == "Evaluate"}
    required = {(1, p) for p in range(5, 12)} | {(2, p) for p in range(7, 12)} | {(3, p) for p in range(9, 12)}
    if profiles != required:
        raise ValueError("incomplete historical legal profiles")
    for request, observed in zip(requests, observations):
        if observed["kind"] != request["kind"]:
            raise ValueError("native observation kind changed")
        if observed["kind"] != "Compile" and observed.get("outcome") != "success":
            for key in ["pretty", "layoutPretty", "layoutSmart"]:
                if not isinstance(observed.get(key), str):
                    raise ValueError("missing full native presentation: " + key)


def verify_files(manifest):
    for name, digest in manifest["files"].items():
        if sha((CORPUS / name).read_bytes()) != digest:
            raise ValueError("native fixture identity changed: " + name)
    requests = json.loads((CORPUS / "requests.json").read_bytes())
    observations = json.loads((CORPUS / "observations.json").read_bytes())
    if requests != all_inputs():
        raise ValueError("native inputs differ from maintained constructor builder")
    validate(requests, observations)
    for name, digest in manifest["sources"].items():
        if sha((ROOT / name).read_bytes()) != digest:
            raise ValueError("native source/input identity changed: " + name)
    return requests, observations


def observe(image, requests):
    return execute("docker", "run", "--rm", "--read-only", "--network", "none", "-i", image, input=encoded(requests))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--build", action="store_true")
    parser.add_argument("--record", action="store_true")
    parser.add_argument("--verify-only", action="store_true")
    args = parser.parse_args()
    if args.verify_only:
        requests, _ = verify_files(json.loads((CORPUS / "manifest.json").read_text()))
        print(f"{len(requests)} complete native observation identities checked")
        return
    if args.build:
        if image_id(BASE) != BASE_ID:
            raise ValueError("native base identity changed; verify context-matrix source build first")
        with tempfile.TemporaryDirectory(prefix="scarab796-oracle-build-") as scratch:
            receipt = Path(scratch) / "image.id"
            subprocess.run(["docker", "build", "--iidfile", str(receipt), "-f", str(HERE / "Dockerfile"), "-t", IMAGE, str(ROOT)], check=True)
            if image_id(IMAGE) != receipt.read_text().strip():
                raise ValueError("fresh native build tag differs from build receipt")
    image = image_id(IMAGE)
    identity = binding(image)
    if args.record:
        catalog = json.loads(observe(image, [dict(id="builtin-catalog", kind="BuiltinCatalog")]))[0]["names"]
        (CORPUS / "builtin-names.json").write_bytes(encoded(catalog))
        requests = all_inputs()
        raw = observe(image, requests)
        repeated = observe(image, requests)
        if raw != repeated:
            raise ValueError("native outputs vary across identical independent processes; retain raw reproducer before proceeding")
        observations = json.loads(raw)
        validate(requests, observations)
        (CORPUS / "requests.json").write_bytes(encoded(requests))
        (CORPUS / "observations.json").write_bytes(encoded(observations))
        files = ["requests.json", "observations.json", "builtin-names.json", "unicode-isprint.json"] + sorted(str(p.relative_to(CORPUS)) for p in (CORPUS / "programs").glob("*.json"))
        manifest = dict(schemaVersion=1, date="2026-11-05", scope="Native API and direct native constructor/helper controls; official-node wire evidence is recorded separately without normalization.",
                        plutusRevision="507cab8cf6192e5143f12be5b2d3005a2928dc6d", plutusPackage="1.70.0.0", prettyprinterPackage="1.7.2", compiler="GHC9.10.3", cabal="3.14.1.1", baseImage=BASE_ID,
                        cases=len(requests), independentIdenticalProcesses=2, rawOutputSha256=sha(raw),
                        files={f: sha((CORPUS / f).read_bytes()) for f in files}, **identity)
        (CORPUS / "manifest.json").write_bytes(encoded(manifest))
    else:
        manifest = json.loads((CORPUS / "manifest.json").read_text())
        requests, accepted = verify_files(manifest)
        for key in ["binarySha256", "sources"]:
            if identity[key] != manifest[key]:
                raise ValueError("native executable/source identity differs: " + key)
        raw = observe(image, requests)
        if json.loads(raw) != accepted or sha(raw) != manifest["rawOutputSha256"]:
            raise ValueError("complete native outputs differ from accepted fixture")
    print(f"{len(requests)} complete native observations reproduced")


if __name__ == "__main__":
    main()
