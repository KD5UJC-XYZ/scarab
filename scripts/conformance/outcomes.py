# SPDX-License-Identifier: Apache-2.0
"""CBOR report verdicts, distinct from successful report generation. No input is omitted or waived."""
from __future__ import annotations

from collections import Counter
from pathlib import PurePosixPath
import re


class OutcomeError(ValueError):
    """A report cannot supply a complete, comparable CBOR verdict."""


def cbor(report: dict) -> dict:
    if not isinstance(report, dict):
        raise OutcomeError("CBOR report is not an object")
    if report.get("version") != "2":
        raise OutcomeError("CBOR outcomes require a version 2 report")
    for field in ("dataset_commit", "implementation_commit", "protocol_version"):
        if not isinstance(report.get(field), str) or not report[field]:
            raise OutcomeError(f"missing {field}")
    if any(not re.fullmatch(r"[0-9a-f]{40}", report[field])
           for field in ("dataset_commit", "implementation_commit")) \
            or not re.fullmatch(r"[0-9]+\.[0-9]+", report["protocol_version"]):
        raise OutcomeError("invalid source identity or protocol version")
    results = report.get("results")
    if not isinstance(results, dict) or not results:
        raise OutcomeError("CBOR results are missing or empty")
    rules: dict[str, Counter] = {}
    mismatches = []
    for sample, verdict in sorted(results.items()):
        path = PurePosixPath(sample)
        if path.is_absolute() or ".." in path.parts or len(path.parts) < 3:
            raise OutcomeError(f"invalid sample path: {sample!r}")
        if verdict is not True and not (isinstance(verdict, str) and verdict.strip()):
            raise OutcomeError(f"{sample}: outcome is neither true nor a failure description")
        counts = rules.setdefault(path.parts[0], Counter(matched=0, mismatched=0))
        counts["matched" if verdict is True else "mismatched"] += 1
        if verdict is not True:
            mismatches.append(sample)
    return {"verdict": "mismatches" if mismatches else "matched", "total": len(results),
            "matched": len(results) - len(mismatches), "mismatched": len(mismatches),
            "by_rule": {name: dict(counts) for name, counts in sorted(rules.items())},
            "dataset_commit": report["dataset_commit"], "implementation_commit": report["implementation_commit"],
            "protocol_version": report["protocol_version"], "mismatch_samples": mismatches}


def compare(current: dict, previous: dict | None) -> dict:
    """Only identical corpus/protocol/sample scopes can identify new disagreements."""
    if previous is None:
        return {"status": "unavailable", "reason": "no previous report supplied"}
    cbor(previous)
    if any(current.get(key) != previous.get(key) for key in ("version", "dataset_commit", "protocol_version")) \
            or set(current["results"]) != set(previous["results"]):
        return {"status": "incomparable", "reason": "dataset, protocol, report version or sample scope changed"}
    new, resolved = [], []
    for sample, verdict in sorted(current["results"].items()):
        before = previous["results"][sample]
        if before is True and verdict is not True:
            new.append(sample)
        elif before is not True and verdict is True:
            resolved.append(sample)
    return {"status": "compared", "previous_commit": previous["implementation_commit"],
            "new_mismatches": new, "resolved_mismatches": resolved}


def markdown(summaries: list[dict]) -> str:
    lines = ["# CBOR conformance outcomes", "",
             "Report generation and conformance are separate results. Every disagreement remains in the report.", ""]
    if not summaries:
        lines += ["No checked CBOR results are present; CBOR conformance was not evaluated.", ""]
    for item in summaries:
        era = item.get("era") if item.get("era") in ("conway", "dijkstra") else "unknown"
        lines += [f"## {era}", ""]
        if item["verdict"] == "incomplete":
            lines += ["**INCOMPLETE: no conformance pass can be established.**", "",
                      str(item["reason"]).replace("\n", " ").replace("<", "&lt;"), ""]
            continue
        lines += [f"**{item['verdict'].upper()}: {item['mismatched']} mismatched / {item['total']} samples.**", "",
                  f"Scarab: `{item['implementation_commit']}`; dataset: `{item['dataset_commit']}`; "
                  f"protocol: `{item['protocol_version']}`.", "",
                  "| Rule | Matched | Mismatched |", "|---|---:|---:|"]
        for rule, counts in item["by_rule"].items():
            safe = rule.replace("|", "&#124;").replace("<", "&lt;").replace("`", "&#96;")
            lines.append(f"| {safe} | {counts['matched']} | {counts['mismatched']} |")
        comparison = item["comparison"]
        lines += [""]
        if comparison["status"] == "compared":
            lines += [f"Compared with `{comparison['previous_commit']}`: "
                      f"{len(comparison['new_mismatches'])} new, {len(comparison['resolved_mismatches'])} resolved."]
        else:
            lines += [f"New disagreements: **unknown** ({comparison['reason']})."]
        lines += [""]
    return "\n".join(lines)
