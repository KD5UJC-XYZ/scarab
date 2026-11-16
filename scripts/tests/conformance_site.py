# SPDX-License-Identifier: Apache-2.0
"""A synthetic checkout of pragma-org's node-diversity-conformance site, holding only what
`scripts/conformance/score.py` reads from it, and synthetic reports, for the conformance tests."""
import gzip
import json
from pathlib import Path

# A ledger-rules fixture without an `expected` field, which the site reads as `Pass`.
ABSENT = object()
COMMIT = "c" * 40


def write_site(root, ledger, uplc, turbo):
    """The site under `root`: `ledger` maps each fixture's file name stem to its `expected` (or `ABSENT`), `uplc`
    lists the expected vectors and `turbo` the corpus's benchmarks."""
    scenarios = Path(root, "scenarios")
    for scenario, fields in (("cbor-decoding", {}), ("ledger-rules", {}),
                             ("uplc-conformance", {"expectations": "scenarios/uplc-conformance/expectations.json"}),
                             ("uplc-turbo", {"schema": "scenarios/uplc-turbo/schema.json"})):
        (scenarios / scenario).mkdir(parents=True, exist_ok=True)
        (scenarios / scenario / "scenario.json").write_text(json.dumps({"id": scenario, "version": "1", **fields}))
    fixtures = scenarios / "ledger-rules" / "fixtures" / "scenarios"
    fixtures.mkdir(parents=True, exist_ok=True)
    for stem, expected in ledger.items():
        document = {"title": stem, "description": "a fixture", "category": "test"}
        if expected is not ABSENT:
            document["expected"] = expected
        (fixtures / f"{stem}.json").write_text(json.dumps(document))
    (scenarios / "uplc-conformance" / "expectations.json").write_text(
        json.dumps({"version": "1", "scenario": "uplc-conformance", "scenarios": list(uplc)}))
    (scenarios / "uplc-turbo" / "schema.json").write_text(
        json.dumps({"$defs": {"benchmark": {"properties": {"bench": {"type": "string", "enum": list(turbo)}}}}}))
    return Path(root)


def gzipped(report):
    return gzip.compress(json.dumps(report).encode())


def write_report(directory, name, report):
    path = Path(directory, name)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(gzipped(report))
    return path


def cbor_report(results, protocol_version="10.0"):
    return {"version": "2", "generated_at": 1794713358466, "protocol_version": protocol_version,
            "dataset_commit": "d" * 40, "implementation_commit": COMMIT, "results": results}


def ledger_report(results):
    """`results`: pairs of a fixture id and its reported result."""
    return {"version": "1", "scenario": "ledger-rules", "generated_at": "2026-11-15T00:00:00Z",
            "dataset_commit": "a" * 40, "scenarios": [{"id": key, "result": result} for key, result in results]}


def uplc_report(items):
    return {"version": "1", "scenario": "uplc-conformance", "generated_at": "2026-11-15T00:00:00Z",
            "scenarios": list(items)}


def turbo_report(items):
    return {"version": "1", "scenario": "uplc-turbo", "generated_at": "2026-11-15T00:00:00Z",
            "benchmarks": list(items)}


def vector(identifier, evaluation, budget, plutus="V3", protocol="10.0"):
    return {"id": identifier, "plutus_version": plutus, "protocol_version": protocol, "evaluation": evaluation,
            "budget": budget}


def benchmark(bench, budget_cpu=None, budget_mem=None, cpu_us=None, mem_bytes=None):
    """A turbo item; `budget_cpu` is in picoseconds, as reports give it."""
    fields = {"budget_cpu": budget_cpu, "budget_mem": budget_mem, "cpu_us": cpu_us, "mem_bytes": mem_bytes}
    return {"bench": bench, "plutus_version": "V3", "protocol_version": "10.0",
            **{key: value for key, value in fields.items() if value is not None}}
