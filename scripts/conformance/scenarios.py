# SPDX-License-Identifier: Apache-2.0
"""The scenarios of node-diversity-conformance that Scarab reports: the tool that writes each report, its inputs
and arguments, and the checks a report must pass beyond the scenario's schema before it is named for publication.

The tools are the `tools` module's entry points; their Scaladoc documents the arguments used here. The Plutus
scenarios report Scarab's Plutus evaluator, its own (`uplc`).
"""
from __future__ import annotations

import gzip
import io
import json
import re
from collections import Counter
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable

from conformance.schema import date_time

JDK_FLAGS = ("--enable-native-access=ALL-UNNAMED", "--sun-misc-unsafe-memory-access=allow")
# The thread stack sbt's own JVM has (`scripts/sbtw`), where `tools/runMain` runs these tools.
TOOL_JVM = ("-Xss8m", "-Xmx4g", *JDK_FLAGS)
# The JVM the turbo measurements were taken in: a fixed heap, so that its sizing does not move between runs.
TURBO_JVM = ("-Xms4g", "-Xmx4g", *JDK_FLAGS)
# The first major protocol version of each era, as the site reads a CBOR report's era from it.
PROTOCOL_ERAS = ((10, "conway"), (12, "dijkstra"))
# The CPU uplc-turbo's published reports are measured on, as run.py's probe reads its model from /proc/cpuinfo: that
# of the GitHub-hosted runners it is written on. A runner cannot be chosen by CPU, and the score moves with it
# (docs/performance/rewards-and-evaluation.md, section 68), so a runner of another CPU skips the scenario and
# publish.py refuses a report of another CPU. Change it deliberately, in a commit of its own with a measurement,
# when GitHub retires this hardware.
TURBO_REFERENCE_CPU = "Intel(R) Xeon(R) 6975P-C"
FIXTURE_ID = re.compile(r"^(\d{5})-")


@dataclass(frozen=True)
class Context:
    """What a scenario's arguments and checks read: the Scarab commit, and each input's directory on the host
    (`host`) and in the tool's container (`mounted`)."""
    commit: str
    pins: dict
    host: dict[str, Path]
    mounted: dict[str, str]


@dataclass(frozen=True)
class Scenario:
    id: str
    main: str
    source: str
    inputs: tuple[str, ...]
    eras: tuple[str | None, ...]
    jvm: tuple[str, ...]
    arguments: Callable[[Context, str | None, str], list[str]]
    entries: Callable[[dict], int]
    checks: Callable[[dict, Context, str | None], list[str]]
    # For a report of timings, the one CPU its published reports are measured on; None for a report of none.
    reference_cpu: str | None = None

    @property
    def schema(self) -> str:
        """The scenario's schema, relative to the site checkout."""
        return f"scenarios/{self.id}/schema.json"


def _value(value: Any) -> Any:
    """A field as the site compares it, with Python's `==`: the value itself, or, for a list or an object, its
    canonical JSON text. `"10.0"` and `10.0` stay apart."""
    return json.dumps(value, sort_keys=True) if isinstance(value, (list, dict)) else value


def _keys(items: list, key: Callable[[dict], tuple]) -> Counter:
    return Counter(key(item) if isinstance(item, dict) else (None,) for item in items)


def _coverage(reported: Counter, expected: Counter, what: str) -> list[str]:
    """Every expected result exactly once: none missing, none repeated, none the corpus lacks. A key is what the
    site matches a result by."""
    def show(keys):
        return ", ".join("/".join(repr(part) if not isinstance(part, str) else part for part in key)
                         for key in sorted(keys, key=repr)[:10])

    problems = []
    repeated = [key for key, count in reported.items() if key in expected and count > expected[key]]
    if repeated:
        problems.append(f"{len(repeated)} {what} reported more often than expected: {show(repeated)}")
    unexpected = [key for key in reported if key not in expected]
    if unexpected:
        problems.append(f"{len(unexpected)} results name no {what[:-1]} of the corpus: {show(unexpected)}")
    missing = list((expected - reported).elements())
    if missing:
        problems.append(f"{len(missing)} of {sum(expected.values())} {what} have no result: {show(missing)}")
    return problems


def _ledger_key(item: dict) -> tuple:
    """How the site's ledger-rules build reads a result's id: its text, cut to five characters."""
    return (str(item.get("id", ""))[:5],)


def ledger_fixture_ids(site: Path) -> Counter:
    """The fixture ids of the site's ledger-rules corpus, as its build reads them: the first five digits of each
    file name."""
    root = site / "scenarios" / "ledger-rules" / "fixtures" / "scenarios"
    return Counter((match.group(1),) for path in root.glob("*.json") if (match := FIXTURE_ID.match(path.name)))


def _ledger_arguments(context: Context, era: str | None, out: str) -> list[str]:
    site, documents = context.mounted["site"], context.mounted["ledger-rules-documents"]
    return [f"{site}/scenarios/ledger-rules/fixtures/scenarios", documents, f"{out}/report.json",
            "--sidecar", f"{out}/sidecar.json",
            "--dataset-commit", context.pins["ledger-rules-documents"].spec["commit"]]


def _ledger_checks(report: dict, context: Context, era: str | None) -> list[str]:
    problems = _coverage(_keys(report.get("scenarios", []), _ledger_key), ledger_fixture_ids(context.host["site"]),
                         "fixtures")
    pinned = context.pins["ledger-rules-documents"].spec["commit"]
    if report.get("dataset_commit") != pinned:
        problems.append(f"dataset_commit is {report.get('dataset_commit')!r}, not the pinned {pinned}")
    return problems


# The site matches a uplc-conformance result to its expectation by these fields, as they are: an id recurs across
# protocols, and a version given as a number is not the same as one given as text.
UPLC_KEY = ("id", "plutus_version", "protocol_version")


def _uplc_key(item: dict) -> tuple:
    return tuple(_value(item.get(field)) for field in UPLC_KEY)


def uplc_expected(site: Path) -> Counter:
    expectations = json.loads((site / "scenarios" / "uplc-conformance" / "expectations.json").read_text())
    return _keys(expectations["scenarios"], _uplc_key)


def _uplc_arguments(context: Context, era: str | None, out: str) -> list[str]:
    return [context.mounted["uplc-conformance-corpus"],
            f"{context.mounted['site']}/scenarios/uplc-conformance/expectations.json", f"{out}/report.json"]


def _uplc_checks(report: dict, context: Context, era: str | None) -> list[str]:
    items = report.get("scenarios", [])
    problems = _coverage(_keys(items, _uplc_key), uplc_expected(context.host["site"]), "vectors")
    no_verdict = sum(1 for item in items if isinstance(item, dict) and item.get("evaluation") == "no verdict")
    if no_verdict:
        problems.append(f"{no_verdict} vectors have no verdict from the evaluator")
    return problems


def _turbo_arguments(context: Context, era: str | None, out: str) -> list[str]:
    return [context.mounted["uplc-turbo-corpus"], f"{out}/report.json"]


def era_of_protocol(protocol_version: object) -> str | None:
    """The era the site reads from a CBOR report's protocol version, `<major>.<minor>`; None for an unknown one."""
    major = str(protocol_version).split(".")[0]
    if not major.isdigit():
        return None
    eras = [era for first, era in PROTOCOL_ERAS if int(major) >= first]
    return eras[-1] if eras else None


def _cbor_arguments(context: Context, era: str | None, out: str) -> list[str]:
    return [f"{context.mounted['cbor-dataset']}/dataset/{era}", str(era),
            context.pins["cbor-dataset"].spec["commit"], context.commit, f"{out}/report-{era}.json"]


def cbor_samples(dataset: Path, era: str | None) -> Counter:
    """The samples of an era of the pinned dataset, as a report names them: each `.input.cbor` file's path below
    `dataset/<era>`, with forward slashes."""
    root = dataset / "dataset" / str(era)
    return Counter((path.relative_to(root).as_posix(),) for path in root.rglob("*.input.cbor") if path.is_file())


def _cbor_checks(report: dict, context: Context, era: str | None) -> list[str]:
    problems = []
    read = era_of_protocol(report.get("protocol_version"))
    if read != era:
        problems.append(f"protocol_version {report.get('protocol_version')!r} gives the era {read}, not {era}")
    pinned = context.pins["cbor-dataset"].spec["commit"]
    if report.get("dataset_commit") != pinned:
        problems.append(f"dataset_commit is {report.get('dataset_commit')!r}, not the pinned {pinned}")
    results = report.get("results")
    reported = Counter((path,) for path in results) if isinstance(results, dict) else Counter()
    return problems + _coverage(reported, cbor_samples(context.host["cbor-dataset"], era), "samples")


SCENARIOS: dict[str, Scenario] = {scenario.id: scenario for scenario in (
    Scenario("ledger-rules", "xyz.kd5ujc.scarab.ledger.LedgerRulesMain",
             "tools/src/main/scala/xyz/kd5ujc/scarab/ledger/LedgerRulesMain.scala",
             ("site", "ledger-rules-documents"), (None,), TOOL_JVM,
             _ledger_arguments, lambda report: len(report.get("scenarios", [])), _ledger_checks),
    Scenario("uplc-conformance", "xyz.kd5ujc.scarab.plutus.ndc.UplcConformanceMain",
             "tools/src/main/scala/xyz/kd5ujc/scarab/plutus/ndc/UplcConformanceMain.scala",
             ("site", "uplc-conformance-corpus"), (None,), TOOL_JVM,
             _uplc_arguments, lambda report: len(report.get("scenarios", [])), _uplc_checks),
    Scenario("uplc-turbo", "xyz.kd5ujc.scarab.plutus.ndc.UplcTurboMain",
             "tools/src/main/scala/xyz/kd5ujc/scarab/plutus/ndc/UplcTurboMain.scala",
             ("site", "uplc-turbo-corpus"), (None,), TURBO_JVM,
             _turbo_arguments, lambda report: len(report.get("benchmarks", [])), lambda report, context, era: [],
             reference_cpu=TURBO_REFERENCE_CPU),
    Scenario("cbor-decoding", "xyz.kd5ujc.scarab.ledger.conformance.CborDatasetMain",
             "tools/src/main/scala/xyz/kd5ujc/scarab/ledger/conformance/CborDatasetMain.scala",
             ("site", "cbor-dataset"), ("conway", "dijkstra"), TOOL_JVM,
             _cbor_arguments, lambda report: len(report.get("results", {})), _cbor_checks),
)}

def label(scenario: str, era: str | None) -> str:
    """`<scenario>[-<era>]`: what a report name says after its date, and the name of the tool's work directory."""
    return scenario + (f"-{era}" if era else "")


# The report names: one per scenario and era, dated by the report's `generated_at` in UTC.
NAME = re.compile(r"^scarab-(?P<date>\d{8})-(?P<label>"
                  + "|".join(re.escape(label(s.id, era)) for s in SCENARIOS.values() for era in s.eras)
                  + r")\.json\.gz$")


def report_name(date: str, scenario: str, era: str | None) -> str:
    if not re.fullmatch(r"\d{8}", date):
        raise ValueError(f"not a YYYYMMDD date: {date!r}")
    name = f"scarab-{date}-{label(scenario, era)}.json.gz"
    if NAME.match(name) is None:
        raise ValueError(f"{name} is not a report name")
    return name


def provenance_name(report: str) -> str:
    """The provenance file beside a report."""
    return report.removesuffix(".json.gz") + ".provenance.json"


def report_date(generated_at: Any, fallback: datetime) -> str:
    """The UTC date of a report, YYYYMMDD: from its `generated_at` (an RFC 3339 time, or milliseconds since the
    epoch), else from `fallback`."""
    moment = fallback
    if isinstance(generated_at, int) and not isinstance(generated_at, bool) and generated_at >= 0:
        moment = datetime.fromtimestamp(generated_at / 1000, tz=timezone.utc)
    elif isinstance(generated_at, str):
        moment = date_time(generated_at) or fallback
    return moment.astimezone(timezone.utc).strftime("%Y%m%d")


def compressed_bytes(data: bytes) -> bytes:
    """`data` gzipped without a file name or time in the header, so the same report gives the same bytes."""
    with io.BytesIO() as raw:
        with gzip.GzipFile(filename="", mode="wb", fileobj=raw, mtime=0, compresslevel=9) as out:
            out.write(data)
        return raw.getvalue()


def compress(data: bytes, target: Path) -> None:
    """`compressed_bytes` of `data`, written to `target`."""
    target.write_bytes(compressed_bytes(data))
