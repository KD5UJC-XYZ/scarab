# SPDX-License-Identifier: Apache-2.0
"""A synthetic checkout of pragma-org's node-diversity-conformance site, holding only what
`scripts/conformance/score.py` and `evidence.py` read from it, synthetic reports, and reports written with a provenance
as `run.py` writes them (`write_pair`), for the conformance tests."""
import gzip
import hashlib
import json
from pathlib import Path

from conformance import inputs
from conformance.evidence import LABELS
from conformance.scenarios import NAME, TURBO_REFERENCE_CPU, compressed_bytes, provenance_name

# A ledger-rules fixture without an `expected` field, which the site reads as `Pass`.
ABSENT = object()
COMMIT = "c" * 40
# A schema for each scenario that checks what tells a report of the scenario from another: its version and scenario.
SCHEMAS = {"cbor-decoding": {"type": "object", "properties": {"version": {"const": "2"}}}}
SCHEMAS.update({scenario: {"type": "object", "required": ["version", "scenario"],
                           "properties": {"version": {"const": "1"}, "scenario": {"const": scenario}}}
                for scenario in ("ledger-rules", "uplc-conformance")})


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
    for scenario, schema in SCHEMAS.items():
        (scenarios / scenario / "schema.json").write_text(json.dumps(schema))
    (scenarios / "uplc-turbo" / "schema.json").write_text(json.dumps({
        "type": "object", "required": ["version", "scenario"],
        "properties": {"version": {"const": "1"}, "scenario": {"const": "uplc-turbo"}},
        "$defs": {"benchmark": {"properties": {"bench": {"type": "string", "enum": list(turbo)}}}}}))
    return Path(root)


def verify(root, site):
    """The receipt `inputs.fetch` writes beside the site checkout `site` under `root`, so that it is taken for the
    pinned one; `root`."""
    pin = inputs.load_pins()["site"]
    Path(root, f"{pin.directory}.verified.json").write_text(json.dumps(inputs.receipt(pin, Path(site))))
    return Path(root)


CPU = {"model": TURBO_REFERENCE_CPU, "logical_cpus": 4, "available_cpus": 3, "quota_cpus": 3.0}


def provenance(name, data, raw, report, **changes):
    """The provenance `run.py` writes for the report file `name`: `data` its bytes, `raw` its JSON's, `report` the
    report; `changes` replace whole fields."""
    scenario, era = LABELS[NAME.match(name)["label"]]
    record = {
        "schemaVersion": 1, "scenario": scenario.id, "era": era,
        "scarab": {"repository": "KD5UJC-XYZ/scarab", "commit": COMMIT, "dirty": False},
        "tool": {"main": scenario.main, "arguments": ["/out/report.json"], "jvm": list(scenario.jvm),
                 "exit_status": 0},
        "inputs": [],
        "environment": {"image": "scarab-jdk:local", "image_id": None, "java": None, "docker": None,
                        "python": "3.12.3", "runner": "github-actions", "cpu": dict(CPU),
                        "limits": {"cpus": "3", "memory": "8g"}},
        "timings_seconds": {"build": 1.0, "fetch": 0.5, "run": 2.0},
        "started_at": "2026-11-25T00:00:00Z", "finished_at": "2026-11-25T00:01:00Z",
        "validation": {"schema": scenario.schema, "valid": True, "checks": []},
        "report": name, "sha256": hashlib.sha256(data).hexdigest(), "bytes": len(data),
        "json_sha256": hashlib.sha256(raw).hexdigest(), "generated_at": report.get("generated_at"),
    }
    record.update(changes)
    return record


def report_of(name):
    """A small report of the scenario and era `name` is of, generated on its date."""
    scenario, era = LABELS[NAME.match(name)["label"]]
    if scenario.id == "cbor-decoding":
        report = cbor_report({"r/valid/1": True}, "12.0" if era == "dijkstra" else "10.0")
    elif scenario.id == "ledger-rules":
        report = ledger_report([("00001", "pass")])
    elif scenario.id == "uplc-conformance":
        report = uplc_report([])
    else:
        report = turbo_report([])
    return report


def write_pair(directory, name, report=None, cpu=None, **changes):
    """The report file `name` in `directory`, `report` (by default a small one of its scenario) dated by the name and
    gzipped as `run.py` writes it, with its provenance beside it; for uplc-turbo the report and the provenance name
    the CPU `cpu`, the reference by default. `changes` replace whole fields of the provenance."""
    date = NAME.match(name)["date"]
    report = dict(report if report is not None else report_of(name))
    report["generated_at"] = f"{date[:4]}-{date[4:6]}-{date[6:]}T12:00:00Z"
    cpu = dict(CPU, **(cpu or {}))
    if report.get("scenario") == "uplc-turbo":
        report["machine"] = {"os": "Linux", "architecture": "amd64", "cpu": cpu["model"], "logical_cores": 3}
    raw = json.dumps(report).encode()
    data = compressed_bytes(raw)
    record = provenance(name, data, raw, report, **changes)
    if "environment" not in changes:
        record["environment"]["cpu"] = cpu
    Path(directory).mkdir(parents=True, exist_ok=True)
    path = Path(directory, name)
    path.write_bytes(data)
    Path(directory, provenance_name(name)).write_text(json.dumps(record))
    return path


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
