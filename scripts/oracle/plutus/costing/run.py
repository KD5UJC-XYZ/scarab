#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Execute independently recorded ledger-API costs, then exact and one-unit-low budgets."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import subprocess
import tempfile

IMAGE = "scarab-plutus-costing-oracle:local"
PIN = "507cab8cf6192e5143f12be5b2d3005a2928dc6d"


def dump(value):
    return json.dumps(value, separators=(",", ":"))


def execute(requests, output):
    with tempfile.TemporaryFile(mode="w+") as inputs:
        for request in requests:
            inputs.write(dump(request) + "\n")
        inputs.seek(0)
        with output.open("w") as results:
            subprocess.run(["docker", "run", "--rm", "-i", "--name", "scarab-plutus-cost-oracle", IMAGE], stdin=inputs, stdout=results, check=True)
    rows = [json.loads(line) for line in output.read_text().splitlines()]
    for row in rows:
        if "toolError" in row:
            raise ValueError(row)
    return rows


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fixtures", type=Path, default=Path("fixtures/reference/plutus-costing"))
    parser.add_argument("--evidence", type=Path, required=True)
    args = parser.parse_args()
    args.evidence.mkdir(parents=True, exist_ok=True)
    scripts = [json.loads(line) for line in (args.fixtures / "scripts.jsonl").read_text().splitlines()]
    models = [json.loads(line) for line in (args.fixtures / "models.jsonl").read_text().splitlines()]
    requests = [{"id": f"{i}:{j}", "script": script["script"], **{k: model[k] for k in ("language", "protocol", "costModel")}} for i, script in enumerate(scripts) for j, model in enumerate(models)]
    results = execute(requests, args.evidence / "raw-counting.jsonl")
    assert len(results) == len(requests)
    fixtures = []
    boundaries = []
    for request, result in zip(requests, results):
        assert request["id"] == result["id"]
        ref = result["reference"]
        row = {"id": request["id"], "admitted": ref["admitted"], "stage": ref["stage"]}
        if ref["stage"] == "success":
            match = re.fullmatch(r"ExBudget \{exBudgetCPU = ExCPU ([0-9]+), exBudgetMemory = ExMemory ([0-9]+)\}", ref["detail"])
            if match is None:
                raise ValueError(ref["detail"])
            cpu, memory = map(int, match.groups())
            row.update(cpu=cpu, memory=memory)
            for kind, cpu_limit, memory_limit in (("exact", cpu, memory), ("cpu-low", cpu - 1, memory), ("memory-low", cpu, memory - 1)):
                boundaries.append({**request, "id": request["id"] + ":" + kind, "cpu": cpu_limit, "memory": memory_limit})
        fixtures.append(row)
    boundary_results = execute(boundaries, args.evidence / "raw-boundaries.jsonl")
    assert len(boundaries) == len(boundary_results)
    for request, result in zip(boundaries, boundary_results):
        assert request["id"] == result["id"]
        expected = "success" if request["id"].endswith(":exact") else "budget"
        if result["reference"]["stage"] != expected:
            raise ValueError((request["id"], expected, result))
    (args.fixtures / "verdicts.jsonl").write_text("".join(dump(row) + "\n" for row in fixtures))
    manifest = {"plutusCommit": PIN, "sourceCommit": subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip(), "oracleImage": subprocess.check_output(["docker", "image", "inspect", IMAGE, "--format", "{{.Id}}"], text=True).strip(), "scripts": len(scripts), "upstreamScripts": sum(s["id"].startswith("upstream/") for s in scripts), "profiles": len(models), "observations": len(fixtures), "successes": sum(r["stage"] == "success" for r in fixtures), "boundaryObservations": len(boundaries), "sha256": {name: hashlib.sha256((args.fixtures / name).read_bytes()).hexdigest() for name in ("scripts.jsonl", "models.jsonl", "verdicts.jsonl")}}
    root = Path(__file__).resolve().parents[4]
    manifest["dateUtc"] = datetime.now(timezone.utc).isoformat()
    manifest["oracleBaseImage"] = "sha256:e061568cd4971962854e85f26910eb5094ff1c416567a547feb3ba6eff59c5da"
    manifest["toolSha256"] = {str(path.relative_to(root)): hashlib.sha256(path.read_bytes()).hexdigest() for path in (Path(__file__).resolve().with_name("Main.hs"), root / "plutus/src/test/scala/xyz/kd5ujc/scarab/plutus/BuiltinCostingCorpus.scala")}
    manifest["rawSha256"] = {path.name: hashlib.sha256(path.read_bytes()).hexdigest() for path in args.evidence.glob("raw-*.jsonl")}
    resources = root / "plutus/src/test/resources/plutus-1.70.0.0"
    def shapes(value):
        if isinstance(value, dict):
            return sorted(set(([value["type"]] if "type" in value else []) + [shape for child in value.values() for shape in shapes(child)]))
        return []
    shape_inventory = {letter: {name: {dimension: shapes(value[dimension]) for dimension in ("cpu", "memory")} for name, value in json.loads((resources / f"builtinCostModel{letter}.json").read_text()).items()} for letter in "ABCDE"}
    (args.fixtures / "cost-shapes.json").write_text(json.dumps(shape_inventory, indent=2) + "\n")
    manifest["sha256"]["cost-shapes.json"] = hashlib.sha256((args.fixtures / "cost-shapes.json").read_bytes()).hexdigest()
    (args.fixtures / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print(json.dumps(manifest, indent=2), flush=True)


if __name__ == "__main__":
    main()
