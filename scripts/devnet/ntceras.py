# SPDX-License-Identifier: Apache-2.0
"""The node-to-client battery at each era step of the `e2e` walk: the same-point rounds of the `ntc` scenario
(`ntcqueries.same_point`), asked of each follower's local socket and of the reference node in every Shelley-based era.

The walk asks for a round after each epoch's transaction batch (`request`), so every era step (an era at one protocol
major version, an epoch at least) gets one; a thread of its own runs one complete round per follower at its tip then,
the followers' at once, so that the walk's timed actions are not delayed and an era of one epoch has the time of its
epoch for all of them. A round's era and protocol major version are those of the
reference's state at its point, and it asks again at `ntcqueries.LEGACY_VERSION` the forms whose encoding the version
changes there.

Each row is judged as `ntcqueries.judge` says. A query both nodes answer must have the same bytes. One only the
reference answers is incomplete while the Scala node does not answer it yet (`implemented` False), never a match.
Both refusing a query that both decoders refuse in the era is agreement, not a compared answer. Anything else fails
the run, in a round that ended early too, whatever later rounds judge; so does a request without a complete round in
`ATTEMPTS` (one the run's end cut short is reported as cancelled instead) and a Shelley-based era with no complete
round.
"""
import concurrent.futures
import queue
import threading
import time
import traceback

from . import compare, ntcqueries
from .ntcqueries import Outcome

ERA_NAMES = {1: "shelley", 2: "allegra", 3: "mary", 4: "alonzo", 5: "babbage", 6: "conway"}
ATTEMPTS = 4  # rounds a request tries, each at a new point, before it is given up
RETRY_PAUSE = 1  # seconds between two attempts of a request
SHORT = 256  # an answer of at most this many bytes that differs is kept in the report as hex
COUNTS = ("matched", "differs", "incomplete", "refusedByBoth", "unexpected")
NAMED = ("incompleteRows", "refusedByBothRows", "answeredWhileMarked")


def row_key(row):
    """The name of a row in a round's report: its label and the version it was asked at."""
    return f"{row.label}@v{row.version}"


def round_report(rnd, dump=None, ended=None):
    """The report of one round (`ntcqueries.Round`): its step (era and protocol major version), every row's outcome
    and sizes, the counts (`compared` is the rows both nodes answered), the rows of each class that is not compared,
    and the failures. A row whose answers differ keeps both in the directory `dump` when it is given, and in the
    report their hex when they are short. `ended`, the `RoundError` of a round that ended early: its rows are judged
    all the same, and the round is `partial`."""
    era, major = ERA_NAMES[rnd.era], rnd.major
    rows, failures = {}, []
    counts = dict.fromkeys(COUNTS, 0)
    named = {k: [] for k in NAMED}
    for row in rnd.rows:
        outcome, verdict = ntcqueries.judge(row)
        key = row_key(row)
        entry = {"outcome": outcome.value, "implemented": row.implemented,
                 "referenceBytes": None if row.reference is None else len(row.reference),
                 "scalaBytes": None if row.scala is None else len(row.scala)}
        counts[outcome.value if outcome.value in COUNTS else "unexpected"] += 1
        if outcome is Outcome.INCOMPLETE:
            named["incompleteRows"].append(key)
        elif outcome is Outcome.REFUSED_BY_BOTH:
            named["refusedByBothRows"].append(key)
        elif outcome is Outcome.MATCHED and not row.implemented:
            named["answeredWhileMarked"].append(key)
        elif outcome is Outcome.DIFFERS:
            entry["firstDifference"] = compare.first_difference(row.reference, row.scala)
            if verdict is not None and verdict.problems:
                entry["problems"] = verdict.problems
            if len(row.reference) <= SHORT and len(row.scala) <= SHORT:
                entry["referenceHex"], entry["scalaHex"] = row.reference.hex(), row.scala.hex()
            if dump is not None:
                dump.mkdir(parents=True, exist_ok=True)
                stem = dump / f"{era}-{major}-{rnd.point[0]}-{row.label.replace('/', '_')}-v{row.version}"
                stem.with_suffix(".reference").write_bytes(row.reference)
                stem.with_suffix(".scala").write_bytes(row.scala)
                entry["kept"] = str(stem)
        if not outcome.passes:
            failures.append(f"{era} (protocol {major}) {key}: {outcome.value}")
        rows[key] = entry
    report = {"era": era, "eraIndex": rnd.era, "protocolMajor": major, "point": compare.point_json(rnd.point),
              "version": rnd.version, "seconds": rnd.seconds, "refusedConnects": rnd.refused_connects,
              "partial": ended is not None,
              "compared": counts["matched"] + counts["differs"], **counts, **named, "rows": rows,
              "failures": failures}
    if ended is not None:
        report["endedOn"] = str(ended)[:300]
    return report


class EraBattery:
    """The battery of the `e2e` walk for its followers (`sites`): `request` after an epoch's batch, then, once the
    thread (`thread`, joined with the load threads) has ended, `report` and `failures` per follower. `stop` is the
    load's stop event: the requests still queued then, and a request it cuts short, are cancelled. The reports only
    grow: a later round never replaces an earlier one."""

    def __init__(self, name, root, sites, stop, log=lambda m: None, rounds=ntcqueries.same_point):
        self.name, self.root, self.sites, self.stop, self.log = name, root, sites, stop, log
        self.rounds = rounds
        self.requests = queue.Queue()
        self.requested = 0
        self.cancelled = []
        self.reports = {site: [] for site in sites}  # every round's report, in order, partial ones too
        self.unjudged = {site: [] for site in sites}  # the requests without a complete round, with their errors
        self.lock = threading.Lock()
        self.thread = threading.Thread(target=self.run, name="ntc-eras", daemon=True)

    def request(self, era):
        """Ask for a round in the walk's era `era` (a name of `util.ERAS`); never blocks."""
        if era in ERA_NAMES.values() and not self.stop.is_set():
            self.requested += 1
            self.requests.put(era)

    def run(self):
        while not self.stop.is_set():
            try:
                era = self.requests.get(timeout=1)
            except queue.Empty:
                continue
            try:
                self.step(era)
            except (Exception, SystemExit) as e:  # a thread ends silently on SystemExit (#591): report it instead
                self.log({"ntcEras": era, "defect": repr(e), "traceback": traceback.format_exc()[-1500:]})
                with self.lock:
                    for site in self.sites:
                        self.unjudged[site].append({"era": era, "protocolMajor": None,
                                                    "attemptErrors": [{"error": repr(e)[:300], "kind": "defect"}]})
        while True:
            try:
                self.cancel(self.requests.get_nowait())
            except queue.Empty:
                return

    def step(self, era):
        """A request made in the walk's era `era`: one complete round for each follower, all at once."""
        if self.stop.is_set():
            self.cancel(era)
            return
        with concurrent.futures.ThreadPoolExecutor(max_workers=len(self.sites)) as pool:
            for future in [pool.submit(self.attempt, site, era) for site in self.sites]:
                future.result()

    def cancel(self, era):
        with self.lock:
            self.cancelled.append(era)

    def attempt(self, site, era):
        """At most `ATTEMPTS` rounds for one follower, each at its tip then, until one is complete."""
        socket = str(site.directory(self.root, "scala-sock") / "node.socket")
        dump = site.directory(self.root, "ntc-eras")
        tag = {} if site.key is None else {"follower": site.key}
        errors = []
        for attempt in range(ATTEMPTS):
            if self.stop.is_set():
                break
            if attempt:
                time.sleep(RETRY_PAUSE)
            try:
                report = round_report(self.rounds(self.name, compare.reference_socket(), socket,
                                                  legacy=ntcqueries.legacy_battery), dump)
            except ntcqueries.Incomparable as e:
                errors.append({"attempt": attempt, "error": repr(e)[:300], "kind": "incomparable"})
                break
            except ntcqueries.RoundError as e:  # its rows are judged, and the request tries again
                partial = round_report(e.round, dump, ended=e)
                with self.lock:
                    self.reports[site].append(partial)
                errors.append({"attempt": attempt, "error": str(e)[:300], "kind": "roundError"})
                self.log({"ntcEras": partial["era"], "protocolMajor": partial["protocolMajor"], **tag,
                          "partial": partial["endedOn"], "failures": partial["failures"][:5]})
                continue
            except (Exception, SystemExit) as e:  # a point lost, a deadline, a closed connection: a new point
                errors.append({"attempt": attempt, "error": repr(e)[:300], "kind": compare.failure_kind(e)})
                continue
            report["attempts"], report["attemptErrors"] = attempt + 1, errors
            if report["era"] != era:
                report["requestedIn"] = era
            with self.lock:
                self.reports[site].append(report)
            self.log({"ntcEras": report["era"], "protocolMajor": report["protocolMajor"], **tag,
                      **{k: report[k] for k in ("compared", *COUNTS)}, "seconds": report["seconds"],
                      "refusedConnects": report["refusedConnects"], "failures": report["failures"][:5]})
            return
        if self.stop.is_set() and (not errors or errors[-1]["kind"] != "incomparable"):
            self.cancel(era)
            return
        with self.lock:
            self.unjudged[site].append({"era": era, "attemptErrors": errors})
        self.log({"ntcEras": era, **tag, "notJudged": errors[-1:]})

    def report(self, site):
        """The follower's rounds, the requests without a complete round, the cancelled ones, and per era the protocol
        versions of its complete rounds (`steps`), their number, counts and rows of each class that is not compared,
        summed."""
        with self.lock:
            reports, unjudged = list(self.reports[site]), list(self.unjudged[site])
        per_era = {}
        for era in ERA_NAMES.values():
            judged = [r for r in reports if r["era"] == era and not r["partial"]]
            per_era[era] = {"steps": sorted({r["protocolMajor"] for r in judged}), "rounds": len(judged),
                            "partialRounds": sum(1 for r in reports if r["era"] == era and r["partial"]),
                            **{k: sum(r[k] for r in judged) for k in ("compared", *COUNTS)},
                            **{k: sorted({row for r in judged for row in r[k]}) for k in NAMED}}
        return {"requested": self.requested, "cancelled": list(self.cancelled), "perEra": per_era,
                "notJudged": unjudged, "rounds": reports}

    def failures(self, site):
        """What fails the run for the follower: a row of any round that does not pass, a request without a complete
        round, and a Shelley-based era with no complete round. Nothing when the walk asked for none (no load or no
        comparisons)."""
        if not self.requested:
            return []
        report = self.report(site)
        out = [f for r in report["rounds"] for f in r["failures"]]
        out += [f"node-to-client battery: a {u['era']} request without a complete round: {u['attemptErrors'][-1:]}"
                for u in report["notJudged"]]
        out += [f"node-to-client battery: no complete round in {era}"
                for era, row in report["perEra"].items() if not row["rounds"]]
        return out


def table(report):
    """The per-era counts of one follower's report, as printed at the end of the run."""
    lines = ["same-point node-to-client battery (compared: both answered; incomplete and refused-by-both rows are "
             "not compared)",
             "era       versions  rounds  compared  matched  differs  incomplete  refusedByBoth  unexpected"]
    for era, row in report["perEra"].items():
        versions = ",".join(str(v) for v in row["steps"]) or "-"
        lines.append(f"{era:<9} {versions:>8}  {row['rounds']:>6}  {row['compared']:>8}  {row['matched']:>7}  "
                     f"{row['differs']:>7}  {row['incomplete']:>10}  {row['refusedByBoth']:>13}  "
                     f"{row['unexpected']:>10}")
    return "\n".join(lines)
