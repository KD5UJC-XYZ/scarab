#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Generate an informational health page from CI runs, their JUnit artifacts and checked-in baselines.

Run with GH_TOKEN and `--repository owner/name --out DIR`. API access is GET-only. No issues, settings or code
are changed. Missing/expired artifacts and incomplete API history are unknown coverage, never zero skips.
Failure-to-recovery intervals describe the latest recorded attempt of each push run, not exact main-red time.
"""
from __future__ import annotations

import argparse
from collections import Counter
from datetime import datetime, timedelta, timezone
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
from xml.etree import ElementTree

ROOT = Path(__file__).resolve().parents[1]
BASELINES = ("imperative", "pinned-citation", "test-sleep", "parameter-comment", "docs-check", "correspondence")
FAILURES = {"failure", "timed_out", "startup_failure"}


class EvidenceError(ValueError):
    """A source cannot establish the requested measurement."""


def gh(*args: str) -> str:
    result = subprocess.run(["gh", *args], capture_output=True, text=True, timeout=120)
    if result.returncode:
        raise EvidenceError(result.stderr.strip() or "GitHub read failed")
    return result.stdout


def api(repository: str, endpoint: str):
    return json.loads(gh("api", f"repos/{repository}/{endpoint}"))


def timestamp(value: str) -> datetime:
    return datetime.fromisoformat(value.replace("Z", "+00:00"))


def main_runs(repository: str, since: datetime) -> tuple[list[dict], bool]:
    runs = []
    for page in range(1, 11):
        data = api(repository, f"actions/workflows/ci.yml/runs?branch=main&event=push&per_page=100&page={page}")
        batch = data["workflow_runs"]
        runs += [run for run in batch if timestamp(run["created_at"]) >= since]
        if len(batch) < 100 or any(timestamp(run["created_at"]) < since for run in batch):
            return runs, True
    return runs, False


def failures(runs: list[dict], complete: bool) -> dict:
    ordered = sorted(runs, key=lambda run: (run["created_at"], run["id"]))
    intervals = []
    for index, run in enumerate(ordered):
        if run.get("conclusion") not in FAILURES:
            continue
        recovered = next((r for r in ordered[index + 1:] if r.get("conclusion") == "success"), None)
        minutes = None
        if recovered:
            span = (timestamp(recovered["updated_at"]) - timestamp(run["updated_at"])).total_seconds() / 60
            minutes = round(span, 1) if span >= 0 else None
        intervals.append({"failed_run": run["html_url"], "commit": run["head_sha"],
                          "failed_at": run["updated_at"], "recovery_run": recovered["html_url"] if recovered else None,
                          "recovery_minutes": minutes})
    unknown = sum(run.get("conclusion") not in FAILURES | {"success"} for run in ordered)
    return {"history_complete": complete, "push_runs": len(runs), "failed_push_runs": len(intervals),
            "unknown_runs": unknown, "latest_attempt_only": True, "intervals": intervals}


def skipped_reports(root: Path, plan: dict) -> dict:
    expected = {(module, suite) for shard in plan["shards"] for module, suite in shard["suites"]}
    if not expected:
        raise EvidenceError("test plan names no suites")
    seen: Counter = Counter()
    skipped, tests, suites = 0, 0, []
    for path in sorted(root.rglob("TEST-*.xml")):
        if path.stat().st_size > 10 * 1024 * 1024:
            raise EvidenceError("JUnit report exceeds the 10 MiB per-suite limit")
        tree = ElementTree.parse(path).getroot()
        module, suite = path.parent.name, path.name[len("TEST-"):-len(".xml")]
        if tree.tag != "testsuite" or tree.get("name") != suite or (module, suite) not in expected:
            raise EvidenceError(f"JUnit report {module}/{suite} is not a suite from the selected run's plan")
        seen[(module, suite)] += 1
        count = tree.get("tests", "")
        reported = tree.get("skipped")
        children = sum(case.find("skipped") is not None for case in tree.findall("testcase"))
        if not count.isdigit() or (reported is not None and not reported.isdigit()):
            raise EvidenceError("JUnit test/skip counts are invalid")
        omitted = int(reported) if reported is not None else children
        if reported is not None and omitted != children:
            raise EvidenceError("JUnit skip count and skipped test cases differ")
        skipped += omitted
        tests += int(count)
        if omitted:
            suites.append({"module": module, "suite": suite, "skipped": omitted})
    if set(seen) != expected or any(count != 1 for count in seen.values()):
        raise EvidenceError(f"JUnit coverage is incomplete or repeated: {len(seen)} of {len(expected)} planned suites")
    return {"status": "available", "tests": tests, "skipped_testcases": skipped,
            "suites_with_skips": suites, "planned_suites": len(expected)}


def skips(repository: str, runs: list[dict]) -> dict:
    passed = sorted((run for run in runs if run.get("conclusion") == "success"),
                    key=lambda run: (run["created_at"], run["id"]), reverse=True)
    if not passed:
        return {"status": "unknown", "reason": "no successful main CI run in the window"}
    run = passed[0]
    try:
        with tempfile.TemporaryDirectory(prefix="scarab-health-reports-") as tmp:
            directory = Path(tmp)
            gh("run", "download", str(run["id"]), "--repo", repository,
               "--pattern", "scala-test-reports-*", "--dir", str(directory / "reports"))
            gh("run", "download", str(run["id"]), "--repo", repository,
               "--name", "scala-test-plan", "--dir", str(directory / "plan"))
            plan = json.loads((directory / "plan/test-plan.json").read_text())
            result = skipped_reports(directory / "reports", plan)
        return {**result, "run": run["html_url"], "commit": run["head_sha"], "observed_at": run["updated_at"]}
    except (OSError, ValueError, KeyError, TypeError, ElementTree.ParseError, subprocess.TimeoutExpired) as error:
        return {"status": "unknown", "reason": str(error), "run": run["html_url"], "commit": run["head_sha"]}


def numeric_counts(value) -> int:
    if type(value) is int and value >= 0:
        return value
    if isinstance(value, dict):
        return sum(numeric_counts(v) for v in value.values())
    raise EvidenceError("baseline contains an unexpected count")


def baselines(root: Path) -> dict:
    result = {}
    for name in BASELINES:
        path = root / "docs/data" / f"{name}-baseline.json"
        try:
            data = json.loads(path.read_text())
            if name == "correspondence":
                parts = {area: len(keys) for area, keys in data["areas"].items()}
                result[name] = {"count": sum(parts.values()), "unit": "definitions pending mapping review", "by_area": parts}
            elif name == "docs-check":
                parts = Counter()
                for entry in data["entries"]:
                    parts[entry["kind"]] += entry["count"]
                result[name] = {"count": sum(parts.values()), "unit": "unresolved occurrences", "by_kind": dict(parts)}
            else:
                result[name] = {"count": numeric_counts(data["files"]), "unit": "baselined occurrences"}
        except (OSError, ValueError, KeyError, TypeError) as error:
            result[name] = {"count": None, "reason": str(error)}
    return result


def safe(value) -> str:
    return str(value).replace("&", "&amp;").replace("<", "&lt;").replace("|", "&#124;").replace("\n", " ")


def markdown(data: dict, previous: dict | None = None) -> str:
    lines = ["# Project health", "", f"Collected {data['collected_at']}; source `{data['source_commit']}`.", "",
             "Informational observations; missing evidence is unknown. No merge requirements are changed.", "",
             "## Main CI", ""]
    ci = data["main_ci"]
    if ci.get("status") == "unknown":
        lines += [f"Unknown: {safe(ci['reason'])}"]
    else:
        lines += [f"{ci['failed_push_runs']} failed / {ci['push_runs']} push runs; "
                  f"{ci['unknown_runs']} pending, cancelled or otherwise unclassified.", "",
                  f"History complete for the requested window: **{ci['history_complete']}**.", "",
                  "These are whole CI workflow conclusions for pushes created within the window, not required-check "
                  "verdicts or the branch's health at the window start. Intervals use the API's recorded update times "
                  "for completed runs. They overlap; do not sum them as main-red time. Out-of-order completion gives "
                  "an unknown interval. Reruns expose only their latest attempt; an earlier failure may be hidden.", "",
                  "| Failed run | Recovery | Minutes |", "|---|---|---:|"]
        for interval in ci["intervals"]:
            recovery = f"[run]({interval['recovery_run']})" if interval["recovery_run"] else "not observed"
            minutes = interval["recovery_minutes"] if interval["recovery_minutes"] is not None else "unknown"
            lines.append(f"| [run]({interval['failed_run']}) | {recovery} | {minutes} |")
    lines += ["", "## Skipped coverage", ""]
    skip = data["skips"]
    if skip["status"] == "available":
        lines += [f"{skip['skipped_testcases']} skipped test cases across {len(skip['suites_with_skips'])} suites; "
                  f"{skip['tests']} reported tests, {skip['planned_suites']} planned suites accounted for.", "",
                  f"Source: [CI run]({skip['run']}), commit `{skip['commit']}`, completed {skip['observed_at']}.", "",
                  "| Module | Suite | Skipped cases |", "|---|---|---:|"]
        for suite in skip["suites_with_skips"]:
            lines.append(f"| {safe(suite['module'])} | {safe(suite['suite'])} | {suite['skipped']} |")
    else:
        lines += [f"**Unknown**: {safe(skip['reason'])}"]
    lines += ["", "Only skips recorded in JUnit are counted. Missing real-data coverage is not exercised by this page.", "",
              "## Baselines", "", "Counts are occurrences or definitions, not JSON lines or verified behavior.", "",
              ]
    prior = data.get("previous_snapshot", {"status": "unavailable", "reason": "no previous snapshot supplied"})
    if prior["status"] == "available":
        lines += [f"Previous snapshot: {prior['collected_at']}, source `{prior['source_commit']}`.", ""]
    else:
        lines += [f"Previous snapshot: **unknown** ({safe(prior['reason'])}).", ""]
    lines += ["| Baseline | Count | Unit | Change since previous snapshot |", "|---|---:|---|---:|"]
    for name, item in data["baselines"].items():
        before = (previous or {}).get("baselines", {}).get(name, {}).get("count")
        delta = f"{item['count'] - before:+d}" if type(before) is int and type(item['count']) is int else "unknown"
        count = item["count"] if item["count"] is not None else "unknown"
        lines.append(f"| {name} | {count} | {item.get('unit', 'unavailable')} | {delta} |")
    lines += ["", "Baseline details and source identities are retained in health.json.", ""]
    return "\n".join(lines)


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", default=os.environ.get("GITHUB_REPOSITORY", "KD5UJC-XYZ/scarab"))
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--days", type=int, choices=range(1, 31), default=7)
    parser.add_argument("--previous", type=Path)
    args = parser.parse_args(argv)
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", args.repository):
        parser.error("repository must be owner/name")
    now = datetime.now(timezone.utc)
    source = subprocess.run(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True, capture_output=True, check=True).stdout.strip()
    since = now - timedelta(days=args.days)
    data = {"schemaVersion": 1, "repository": args.repository, "source_commit": source,
            "collected_at": now.isoformat(), "window_start": since.isoformat(), "baselines": baselines(ROOT)}
    try:
        runs, complete = main_runs(args.repository, since)
        data["main_ci"] = failures(runs, complete)
        data["skips"] = skips(args.repository, runs)
    except (OSError, ValueError, KeyError, TypeError, subprocess.TimeoutExpired) as error:
        data["main_ci"] = {"status": "unknown", "reason": str(error)}
        data["skips"] = {"status": "unknown", "reason": "CI history unavailable"}
    previous = None
    data["previous_snapshot"] = {"status": "unavailable", "reason": "no previous snapshot supplied"}
    if args.previous:
        try:
            candidate = json.loads(args.previous.read_text())
            if not isinstance(candidate, dict) or candidate.get("schemaVersion") != 1 \
                    or candidate.get("repository") != args.repository \
                    or not re.fullmatch(r"[0-9a-f]{40}", candidate.get("source_commit", "")) \
                    or not isinstance(candidate.get("baselines"), dict) \
                    or not all(isinstance(v, dict) and (v.get("count") is None
                               or (type(v.get("count")) is int and v["count"] >= 0))
                               for v in candidate["baselines"].values()) \
                    or not isinstance(candidate.get("collected_at"), str) \
                    or timestamp(candidate["collected_at"]) > now:
                raise EvidenceError("previous snapshot schema, repository, counts or identity do not match")
            previous = candidate
            data["previous_snapshot"] = {"status": "available", "collected_at": candidate["collected_at"],
                                         "source_commit": candidate["source_commit"]}
        except (OSError, ValueError, KeyError, TypeError) as error:
            data["previous_snapshot"] = {"status": "unavailable", "reason": str(error)}
    args.out.mkdir(parents=True, exist_ok=True)
    (args.out / "health.json").write_text(json.dumps(data, indent=2) + "\n")
    (args.out / "health.md").write_text(markdown(data, previous))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
