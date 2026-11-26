# SPDX-License-Identifier: Apache-2.0
"""A report file and its provenance checked together as `run.py` wrote them: before `publish.py` stages the report's
bytes, and before `summarize.py` scores them.

`problems` lists every way the pair is not one: the provenance has the shape `run.py` writes (`provenance.py`) and
names this file, its size and its sha256; the bytes are gzipped standard JSON whose sha256 the provenance names; the
report and the provenance agree on the scenario, the era, the time the report was generated (which dates its name),
the Scarab commit where the report names one, and, for a report of timings, the CPU; and, given the pinned site, the
report is valid against the scenario's schema there and the site's scoring can read it (`score.py`), which refuses a
report of another version, such as an `experimental` one.

These checks give consistency, not authenticity: they show that a report is the one its provenance describes and
that the site will take it, not that its measurements were made as the provenance says. Anyone who can write the two
files can make them agree.
"""
from __future__ import annotations

import gzip
import hashlib
import json
import zlib
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from conformance import provenance, score
from conformance.scenarios import NAME, SCENARIOS, Scenario, label, report_date
from conformance.schema import UnsupportedSchema, loads, validate

# Each report name's `<scenario>[-<era>]`, with its scenario and era.
LABELS: dict[str, tuple[Scenario, str | None]] = {label(scenario.id, era): (scenario, era)
                                                   for scenario in SCENARIOS.values() for era in scenario.eras}
# A date no report name carries, for a `generated_at` that names none.
NO_DATE = datetime(1, 1, 1, tzinfo=timezone.utc)


class EvidenceError(ValueError):
    """A report and its provenance that are not a pair as `run.py` writes them."""

    def __init__(self, name: str, found: list[str]):
        super().__init__(f"{name}: " + "; ".join(found))
        self.problems = found


@dataclass(frozen=True)
class Pinned:
    """The pinned site's checkout: the scenarios' schemas, and what its scoring reads."""
    root: Path
    scoring: score.Site

    @staticmethod
    def read(root: Path) -> Pinned:
        return Pinned(root, score.Site.read(root))

    def schema(self, scenario: Scenario) -> Any:
        try:
            return json.loads((self.root / scenario.schema).read_text(encoding="utf-8"))
        except (OSError, ValueError) as error:
            raise score.ScoreError(f"the site's {scenario.schema} cannot be read: {error}") from error


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _against_site(report: dict, scenario: Scenario, era: str | None, pinned: Pinned) -> list[str]:
    try:
        errors = validate(report, pinned.schema(scenario))
    except (UnsupportedSchema, score.ScoreError) as error:
        return [f"it cannot be checked against the site's schema: {error}"]
    found = [f"it is not valid against the site's {scenario.schema}: " + "; ".join(errors[:5])] if errors else []
    try:
        score.score_report(report, scenario.id, era, pinned.scoring)
    except (score.Unreadable, score.ScoreError) as error:
        found.append(f"the site's scoring cannot read it: {error}")
    return found


def problems(name: str, data: bytes, record: Any, pinned: Pinned | None = None) -> list[str]:
    """Every way the report file `name`, whose bytes are `data`, and its provenance `record` are not a pair as `run.py`
    writes them; against the site too when `pinned` is given. Empty when they are one."""
    match = NAME.match(name)
    if match is None:
        return ["it is not a report name (scarab-YYYYMMDD-<scenario>[-<era>].json.gz)"]
    scenario, era = LABELS[match["label"]]
    shape = provenance.problems(record)
    if shape:
        return [f"its provenance is not one run.py writes: {'; '.join(shape[:5])}"]
    found = []
    if record["report"] != name:
        found.append(f"its provenance is of {record['report']!r}")
    if (record["scenario"], record["era"]) != (scenario.id, era):
        found.append(f"its provenance is of the scenario {record['scenario']!r} and era {record['era']!r}")
    if record["bytes"] != len(data) or record["sha256"] != sha256(data):
        found.append("its size or sha256 is not the one its provenance records")
    if record["validation"]["valid"] is not True:
        found.append("its provenance records no passed validation")
    try:
        raw = gzip.decompress(data)
        report = loads(raw.decode("utf-8"))
    except (OSError, EOFError, zlib.error, UnicodeDecodeError, ValueError) as error:
        return found + [f"it is not gzipped standard JSON: {error}"]
    if sha256(raw) != record["json_sha256"]:
        found.append("its JSON's sha256 is not the one its provenance records")
    if not isinstance(report, dict):
        return found + ["it is not a JSON object"]
    if "scenario" in report and report["scenario"] != scenario.id:
        found.append(f"it is a report of {report['scenario']!r}, its name and provenance of {scenario.id!r}")
    if report.get("generated_at") != record["generated_at"]:
        found.append("it and its provenance give different times it was generated")
    if report_date(report.get("generated_at"), NO_DATE) != match["date"]:
        found.append("its name's date is not the UTC date it was generated")
    if "implementation_commit" in report and report["implementation_commit"] != record["scarab"]["commit"]:
        found.append("it and its provenance name different Scarab commits")
    if scenario.reference_cpu is not None:
        machine = report.get("machine")
        cpu = machine.get("cpu") if isinstance(machine, dict) else None
        if cpu != provenance.cpu_model(record):
            found.append(f"it names the CPU {cpu!r}, its provenance {provenance.cpu_model(record)!r}")
    if pinned is not None:
        found += _against_site(report, scenario, era, pinned)
    return found


def check(name: str, data: bytes, record: Any, pinned: Pinned | None = None) -> dict:
    """The report in `data`, once `problems` finds none; `EvidenceError` otherwise."""
    found = problems(name, data, record, pinned)
    if found:
        raise EvidenceError(name, found)
    return loads(gzip.decompress(data).decode("utf-8"))
