# SPDX-License-Identifier: Apache-2.0
"""The shape of a report's `.provenance.json`, as `run.py` writes it: `run.py` checks a record against `SCHEMA`
before it writes it, and `publish.py` before it stages it, with the checker of `schema.py`.

`environment.cpu` is the CPU a tool container saw: its model as `/proc/cpuinfo` names it (none on most ARM CPUs),
the machine's logical CPUs (`/proc/cpuinfo`), the CPUs `nproc` in the container reports (the image's `nproc`
counts the container's `--cpus` quota) and that quota as the container's cgroup states it (`cpu.max`; none when it
sets no limit or cannot be read). `failure`, present only then, says why the probe gave no CPU count.
`environment.limits` are the container's `--cpus` and `--memory`.
"""
from __future__ import annotations

from typing import Any

from conformance.schema import validate

COMMIT = "^[0-9a-f]{40}$"
SHA256 = "^[0-9a-f]{64}$"
TEXT_OR_NULL = {"type": ["string", "null"]}
COUNT = {"type": ["integer", "null"], "minimum": 1}
STRINGS = {"type": "array", "items": {"type": "string"}}

SCHEMA: dict[str, Any] = {
    "type": "object",
    "required": ["schemaVersion", "scenario", "era", "scarab", "tool", "inputs", "environment", "timings_seconds",
                 "started_at", "finished_at", "validation", "report", "sha256", "bytes", "json_sha256",
                 "generated_at"],
    "additionalProperties": False,
    "properties": {
        "schemaVersion": {"const": 1},
        "scenario": {"type": "string"},
        "era": TEXT_OR_NULL,
        "scarab": {
            "type": "object", "required": ["repository", "commit", "dirty"], "additionalProperties": False,
            "properties": {"repository": {"type": "string"}, "commit": {"type": "string", "pattern": COMMIT},
                           "dirty": {"type": "boolean"}},
        },
        "tool": {
            "type": "object", "required": ["main", "arguments", "jvm", "exit_status"], "additionalProperties": False,
            "properties": {"main": {"type": "string"}, "arguments": STRINGS, "jvm": STRINGS,
                           "exit_status": {"type": "integer"}},
        },
        "inputs": {"type": "array", "items": {"type": "object"}},
        "environment": {
            "type": "object", "required": ["image", "image_id", "java", "docker", "python", "runner", "cpu", "limits"],
            "additionalProperties": False,
            "properties": {
                "image": {"type": "string"}, "image_id": TEXT_OR_NULL, "java": TEXT_OR_NULL, "docker": TEXT_OR_NULL,
                "python": {"type": "string"}, "runner": {"enum": ["github-actions", "local"]},
                "cpu": {
                    "type": "object", "required": ["model", "logical_cpus", "available_cpus", "quota_cpus"],
                    "additionalProperties": False,
                    "properties": {"model": TEXT_OR_NULL, "logical_cpus": COUNT, "available_cpus": COUNT,
                                   "quota_cpus": {"type": ["number", "null"], "minimum": 0},
                                   "failure": {"type": "string"}},
                },
                "limits": {
                    "type": "object", "required": ["cpus", "memory"], "additionalProperties": False,
                    "properties": {"cpus": {"type": "string", "pattern": r"^[0-9]+(\.[0-9]+)?$"},
                                   "memory": {"type": "string", "pattern": "^[0-9]+[bkmgBKMG]?$"}},
                },
            },
        },
        "timings_seconds": {
            "type": "object", "required": ["build", "fetch", "run"], "additionalProperties": False,
            "properties": {name: {"type": "number", "minimum": 0} for name in ("build", "fetch", "run")},
        },
        "started_at": {"type": "string", "format": "date-time"},
        "finished_at": {"type": "string", "format": "date-time"},
        "validation": {
            "type": "object", "required": ["schema", "valid", "checks"],
            "properties": {"schema": {"type": "string"}, "valid": {"type": "boolean"}, "checks": STRINGS,
                           "site_commit": {"type": "string", "pattern": COMMIT},
                           "schema_errors": {"type": "integer", "minimum": 0},
                           "entries": {"type": ["integer", "null"], "minimum": 0}},
        },
        "report": {"type": "string"},
        "sha256": {"type": "string", "pattern": SHA256},
        "bytes": {"type": "integer", "minimum": 0},
        "json_sha256": {"type": "string", "pattern": SHA256},
        "generated_at": {"oneOf": [{"type": "string", "format": "date-time"}, {"type": "integer", "minimum": 0}]},
    },
}


def problems(record: Any) -> list[str]:
    """The ways `record` is not a provenance as `run.py` writes it; empty when it is one."""
    return validate(record, SCHEMA)


def cpu_model(record: Any) -> str | None:
    """The CPU model a provenance names; None when it names none, or the record is not one."""
    environment = record.get("environment") if isinstance(record, dict) else None
    cpu = environment.get("cpu") if isinstance(environment, dict) else None
    model = cpu.get("model") if isinstance(cpu, dict) else None
    return model if isinstance(model, str) and model else None
