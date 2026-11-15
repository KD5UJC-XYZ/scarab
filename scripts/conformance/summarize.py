#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Write cbor-outcomes.json and cbor-outcomes.md beside a runner's summary.json.

Usage: python3 -I -B scripts/conformance/summarize.py --out DIR [--previous DIR]
Reports must have passed the existing schema/coverage checks and match their provenance hashes. Mismatches
remain visible informational results, never expected failures. Exit 1 means incomplete or invalid evidence;
exit 0 means the summary was generated, not that all vectors matched. This adds no merge gate.
"""
from __future__ import annotations

import argparse
import gzip
import hashlib
import json
from pathlib import Path
import sys
import zlib

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from conformance import outcomes  # noqa: E402
from conformance.scenarios import NAME, provenance_name  # noqa: E402
from conformance.schema import loads  # noqa: E402

INVALID = (OSError, ValueError, KeyError, TypeError, EOFError, zlib.error)


def explanation(error: Exception) -> str:
    # FileNotFoundError includes the host's absolute scratch path, which is not report evidence.
    return (error.strerror or type(error).__name__) if isinstance(error, OSError) else str(error)


def run_items(directory: Path) -> tuple[dict, list[dict]]:
    run = loads((directory / "summary.json").read_text())
    if not isinstance(run, dict) or not isinstance(run.get("outcomes"), list) \
            or not all(isinstance(item, dict) for item in run["outcomes"]):
        raise outcomes.OutcomeError("run summary is not an object with outcome objects")
    return run, run["outcomes"]


def read_report(directory: Path, item: dict) -> dict:
    name = item.get("report", "")
    match = NAME.fullmatch(name)
    if not match or match["label"] != "cbor-decoding-" + str(item.get("era")):
        raise outcomes.OutcomeError("missing or invalid report name")
    raw = (directory / name).read_bytes()
    provenance = loads((directory / provenance_name(name)).read_text())
    if not isinstance(provenance, dict) or not isinstance(provenance.get("validation"), dict) \
            or not isinstance(provenance.get("scarab"), dict):
        raise outcomes.OutcomeError("provenance is not an object with validation and Scarab identity")
    if provenance.get("validation", {}).get("valid") is not True \
            or provenance.get("sha256") != hashlib.sha256(raw).hexdigest():
        raise outcomes.OutcomeError("report lacks valid coverage/schema evidence or its hash differs")
    report = loads(gzip.decompress(raw).decode("utf-8"))
    outcomes.cbor(report)
    if provenance.get("scarab", {}).get("commit") != report.get("implementation_commit"):
        raise outcomes.OutcomeError("report and provenance name different Scarab commits")
    return report


def collect(directory: Path, previous: Path | None = None) -> list[dict]:
    run, items = run_items(directory)
    old, old_error = None, None
    if previous is not None:
        try:
            _, old = run_items(previous)
        except INVALID as error:
            old_error = f"previous run evidence is unavailable: {explanation(error)}"
    result = []
    seen = set()
    for item in items:
        if item.get("scenario") != "cbor-decoding":
            continue
        era = item.get("era")
        try:
            if era in seen:
                raise outcomes.OutcomeError("duplicate era in run summary")
            if era not in ("conway", "dijkstra"):
                raise outcomes.OutcomeError("unknown CBOR era")
            seen.add(era)
            if item.get("status") != "report":
                detail = item.get("detail") or "scenario did not produce a checked report"
                raise outcomes.OutcomeError(str(detail) + "; " + "; ".join(map(str, item.get("checks", [])[:10])))
            report = read_report(directory, item)
            prior = None
            prior_error = old_error
            if old is not None:
                candidates = [o for o in old if o.get("scenario") == "cbor-decoding"
                              and o.get("era") == era and o.get("status") == "report"]
                if len(candidates) == 1:
                    try:
                        prior = read_report(previous, candidates[0])
                    except INVALID as error:
                        prior_error = f"previous evidence is invalid: {explanation(error)}"
                else:
                    prior_error = "previous run has no unique checked report for this era"
            comparison = outcomes.compare(report, prior)
            if prior_error:
                comparison = {"status": "unavailable", "reason": prior_error}
            result.append({"era": era, **outcomes.cbor(report), "comparison": comparison})
        except INVALID as error:
            result.append({"era": era, "verdict": "incomplete", "reason": explanation(error)})
    if "cbor-decoding" in run.get("requested", []):
        result += [{"era": era, "verdict": "incomplete", "reason": "requested era has no outcome"}
                   for era in ("conway", "dijkstra") if era not in seen]
    return result


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--previous", type=Path)
    args = parser.parse_args(argv)
    try:
        items = collect(args.out, args.previous)
    except INVALID as error:
        items = [{"era": None, "verdict": "incomplete", "reason": explanation(error)}]
    args.out.mkdir(parents=True, exist_ok=True)
    (args.out / "cbor-outcomes.json").write_text(json.dumps({"schemaVersion": 1, "outcomes": items}, indent=2) + "\n")
    (args.out / "cbor-outcomes.md").write_text(outcomes.markdown(items))
    for item in items:
        if item["verdict"] == "mismatches":
            print(f"::warning::CBOR {item['era']}: {item['mismatched']} of {item['total']} samples disagree")
    return int(any(item["verdict"] == "incomplete" for item in items))


if __name__ == "__main__":
    sys.exit(main())
