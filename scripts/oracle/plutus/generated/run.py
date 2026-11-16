#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Run generated UPLC programs through the pinned Haskell evaluator and record its verdicts."""
import argparse
from collections import Counter
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile

# The pinned evaluator of each plutus release: its image, the parent image it derives from, the
# commit, the harness directory and the retained fixture.
RELEASES = {
    "1.70.0.0": {
        "image": "scarab-plutus-generated-oracle:local",
        "base": "scarab-plutus175-oracle:local",
        "pin": "507cab8cf6192e5143f12be5b2d3005a2928dc6d",
        "harness": "scripts/oracle/plutus/generated",
        "fixtures": "fixtures/reference/uplc-generated",
    },
    "1.71.0.0": {
        "image": "scarab-plutus171-generated-oracle:local",
        "base": "scarab-plutus171-oracle:local",
        "pin": "261766ad1d582bce8c86bad1af7a3b0e5355b21c",
        "harness": "scripts/oracle/plutus/generated-171",
        "fixtures": "fixtures/reference/uplc-generated-171",
    },
}
LABELS = ["--label", "xyz.kd5ujc.scarab.kind=oracle"]


def dump(value):
    return json.dumps(value, separators=(",", ":"), ensure_ascii=False)


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def image_id(image):
    return subprocess.check_output(["docker", "image", "inspect", image, "--format", "{{.Id}}"], text=True).strip()


def execute(image, requests, raw, owner):
    with tempfile.TemporaryFile(mode="w+") as inputs:
        for request in requests:
            inputs.write(dump(request) + "\n")
        inputs.seek(0)
        with raw.open("w") as results:
            command = ["docker", "run", "--rm", "-i", *LABELS, "--label", f"xyz.kd5ujc.scarab.owner={owner}", image]
            subprocess.run(command, stdin=inputs, stdout=results, check=True)
    rows = [json.loads(line) for line in raw.read_text().splitlines()]
    if len(rows) != len(requests):
        raise ValueError(f"{len(rows)} results for {len(requests)} requests")
    for request, row in zip(requests, rows):
        if "toolError" in row or row.get("id") != request["id"]:
            raise ValueError((request["id"], row))
    return rows


def verdict(row):
    ref = row["reference"]
    out = {"id": row["id"], "admitted": ref["admitted"], "stage": ref["stage"]}
    for key in ("class", "error"):
        if key in ref:
            out[key] = ref[key]
    if "spent" in ref:
        out["cpu"] = ref["spent"]["cpu"]
        out["memory"] = ref["spent"]["memory"]
    if "logs" in ref:
        out["logs"] = ref["logs"]
    return out


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--release", choices=sorted(RELEASES), default="1.70.0.0")
    parser.add_argument("--fixtures", type=Path)
    parser.add_argument("--evidence", type=Path, required=True)
    parser.add_argument("--owner", default="uplc-generated")
    args = parser.parse_args()
    release = RELEASES[args.release]
    if args.fixtures is None:
        args.fixtures = Path(release["fixtures"])
    args.evidence.mkdir(parents=True, exist_ok=True)
    corpus = json.loads((args.fixtures / "corpus.json").read_text())
    models = [json.loads(line) for line in (args.fixtures / "models.jsonl").read_text().splitlines()]
    rows = [json.loads(line) for line in (args.fixtures / "requests.jsonl").read_text().splitlines()]
    requests = [{**row, "costModel": models[row["model"]]["costModel"]} for row in rows]
    results = execute(release["image"], requests, args.evidence / "raw.jsonl", args.owner)
    verdicts = [verdict(row) for row in results]
    (args.fixtures / "verdicts.jsonl").write_text("".join(dump(v) + "\n" for v in verdicts))
    root = Path(__file__).resolve().parents[4]
    stages = Counter(v["stage"] for v in verdicts)
    errors = Counter(f'{v["stage"]}/{v.get("class", "-")}/{v.get("error", "-")}' for v in verdicts)
    harness = [root / release["harness"] / name for name in ("Main.hs", "Dockerfile")]
    manifest = {
        "plutusCommit": release["pin"],
        "sourceCommit": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip(),
        "oracleImage": image_id(release["image"]),
        "oracleBaseImage": image_id(release["base"]),
        "generator": corpus,
        "requests": len(requests),
        "profiles": len(models),
        "stages": dict(sorted(stages.items())),
        "errors": dict(sorted(errors.items())),
        "withLogs": sum(bool(v.get("logs")) for v in verdicts),
        "withArguments": sum(bool(r["arguments"]) for r in rows),
        "sha256": {name: sha256(args.fixtures / name) for name in ("corpus.json", "models.jsonl", "requests.jsonl", "verdicts.jsonl")},
        "toolSha256": {str(p.relative_to(root)): sha256(p) for p in harness},
        "rawSha256": sha256(args.evidence / "raw.jsonl"),
        "dateUtc": datetime.now(timezone.utc).isoformat(),
    }
    (args.fixtures / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    provenance = {
        "schemaVersion": 1,
        "scope": "Admission, stage, error class and constructor, budget spent (on success and on failure) and logs of "
                 "generated UPLC scripts under deserialiseScript and evaluateScriptRestricting; synthetic scripts, "
                 "not chain scripts",
        "plutusSourceCommit": release["pin"],
        "measuredAt": manifest["dateUtc"][:10],
        "network": "none",
        "requests": len(requests),
        "generator": corpus,
        "imageId": manifest["oracleImage"],
        "baseImageId": manifest["oracleBaseImage"],
        "harnessSha256": manifest["toolSha256"][f'{release["harness"]}/Main.hs'],
        "requestsSha256": manifest["sha256"]["requests.jsonl"],
        "verdictsSha256": manifest["sha256"]["verdicts.jsonl"],
        "modelsSha256": manifest["sha256"]["models.jsonl"],
    }
    (args.fixtures / "provenance.json").write_text(json.dumps(provenance, indent=1) + "\n")
    print(json.dumps({k: manifest[k] for k in ("requests", "stages", "errors", "withLogs", "withArguments")}, indent=2))


if __name__ == "__main__":
    main()
