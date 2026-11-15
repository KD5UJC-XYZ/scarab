# SPDX-License-Identifier: Apache-2.0
"""`scripts/conformance/score.py`: the reports of `latest/` scored as the node-diversity-conformance site scores
them, on a synthetic checkout of the site and synthetic reports (`conformance_site`)."""
import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
sys.path.insert(0, str(Path(__file__).resolve().parent))
from conformance import inputs, score  # noqa: E402
from conformance_site import (ABSENT, benchmark, cbor_report, ledger_report, turbo_report, uplc_report,  # noqa: E402
                              vector, write_report, write_site)

# The kinds of a ledger-rules fixture's `expected`, and the result each expects.
LEDGER_KINDS = ((ABSENT, "pass"), ("Pass", "pass"), ({"decoding_failure": True}, "DecodingFailure"),
                ({"predicate": "ValueNotConservedUTxO"}, "ValueNotConservedUTxO"), ({"predicate": ""}, "failed"),
                ({}, "failed"))
CBOR_CATEGORIES = ("valid", "manual-valid", "manual-invalid", "invalid-zap-1", "invalid-zap-12")


def cbor_results(total, failing):
    """`total` samples over every category, the first `failing` of them failed."""
    return {f"rule{n % 13}/{CBOR_CATEGORIES[n % len(CBOR_CATEGORIES)]}/{n:05d}.input.cbor":
            "decoding: unexpected end of input" if n < failing else True for n in range(total)}


def turbo_failing(i):
    """A benchmark that fails: over twice its CPU budget and 200 µs beyond it, over twice its memory budget and
    320 KiB beyond it, or with neither dimension comparable."""
    bench = f"{i:04d}.flat"
    return (benchmark(bench, 100_000_000, 1000, 1000.0, 1500.0), benchmark(bench, 100_000_000, 1000, 150.0, 400_000.0),
            benchmark(bench, cpu_us=150.0, mem_bytes=1500.0))[i % 3]


def turbo_passing(i):
    """A benchmark that passes: within twice its budgets, at exactly twice, beyond twice but within 200 µs or
    320 KiB, or with only its CPU comparable."""
    bench = f"{i:04d}.flat"
    return (benchmark(bench, 100_000_000, 1000, 150.0, 1500.0), benchmark(bench, 100_000_000, 1000, 200.0, 2000.0),
            benchmark(bench, 100_000_000, 1000, 250.0, 1500.0), benchmark(bench, 100_000_000, 1000, 150.0, 300_000.0),
            benchmark(bench, budget_cpu=100_000_000, cpu_us=150.0, mem_bytes=1500.0))[i % 5]


def published(root):
    """A site and a `latest/` with the counts of Scarab's reports of 2026-11-15, every rule taking part: cbor-decoding
    Conway 43,276 of 43,434 and Dijkstra 34,049 of 40,437, ledger-rules 368 of 368, uplc-conformance 1,063 of 1,064
    and uplc-turbo 3,660 of 3,997."""
    ledger = {f"{i:05d}-fixture": LEDGER_KINDS[i % len(LEDGER_KINDS)][0] for i in range(1, 369)}
    expectations = [vector("shared", "(program 1.0.0 (con integer 0))", {"cpu": 1000, "mem": 100}, protocol="10.0"),
                    vector("shared", "(program 1.0.0 (con integer 1))", None, protocol="11.0")]
    expectations += [vector(f"vector_{i}", f"(program 1.0.0 (con integer {i}))",
                            {"cpu": 1000 + i, "mem": 100 + i} if i % 2 == 0 else None) for i in range(2, 1064)]
    benchmarks = [f"{i:04d}.flat" for i in range(3997)]
    site = score.Site.read(write_site(root / "site", ledger, expectations, benchmarks))

    latest = root / "latest"
    write_report(latest, "cbor-decoding-conway.json.gz", cbor_report(cbor_results(43_434, 158)))
    write_report(latest, "cbor-decoding-dijkstra.json.gz", cbor_report(cbor_results(40_437, 6_388), "12.0"))
    # Every fixture's expected result, some under a longer id; fixture 3 first with a result a later one replaces;
    # and an id the corpus lacks.
    results = [("00003", "failed")]
    results += [(f"{i:05d}" + ("-long-id" if i % 7 == 0 else ""), LEDGER_KINDS[i % len(LEDGER_KINDS)][1])
                for i in range(1, 369)]
    write_report(latest, "ledger-rules.json.gz", ledger_report(results + [("99999", "pass")]))
    # Results as the pretty-printer lays them out, budgets as objects or text, the shared id's two protocol versions
    # in the other order, and vector_10 with another budget.
    items = [vector("shared", "(program 1.0.0\n  (con integer 1)\n)", {"cpu": 5, "mem": 5}, protocol="11.0"),
             vector("shared", "(program 1.0.0 (con integer 0))", "({ cpu: 1000\n| mem: 100 })", protocol="10.0")]
    for i in range(2, 1064):
        budget = {"cpu": 1000 + i, "mem": 100 + i} if i % 2 == 0 else {"cpu": 1, "mem": 1}
        items.append(vector(f"vector_{i}", f"(program 1.0.0\n  (con integer {i})\n)",
                            {"cpu": 0, "mem": 0} if i == 10
                            else f"({{ cpu: {budget['cpu']}\n| mem: {budget['mem']} }})" if i % 4 == 2 else budget))
    write_report(latest, "uplc-conformance.json.gz", uplc_report(items))
    # Benchmarks 0 to 4 unreported, 5 to 336 failing, the rest passing, and one the corpus lacks.
    write_report(latest, "uplc-turbo.json.gz", turbo_report(
        [turbo_failing(i) for i in range(5, 337)] + [turbo_passing(i) for i in range(337, 3997)]
        + [benchmark("absent.flat", 100_000_000, 1000, 150.0, 1500.0)]))
    return latest, site


def table(text):
    return [line for line in text.splitlines() if line.startswith("| ") and not line.startswith("| Scenario")]


class PublishedCountsTest(unittest.TestCase):
    def test_the_published_reports_counts_and_overall_score(self):
        with tempfile.TemporaryDirectory() as tmp:
            latest, site = published(Path(tmp))
            results = score.results(latest, site)
            text = score.markdown(results)
            self.assertEqual(text, score.markdown(score.results(latest, site)), "the same latest/, the same text")
        self.assertEqual([(row.scenario, row.era, row.score, row.scored) for row in results.rows], [
            ("cbor-decoding", "conway", score.Score(43_276, 43_434), True),
            ("cbor-decoding", "dijkstra", score.Score(34_049, 40_437), False),
            ("ledger-rules", None, score.Score(368, 368), True),
            ("uplc-conformance", None, score.Score(1_063, 1_064), True),
            ("uplc-turbo", None, score.Score(3_660, 3_997), True),
        ])
        # The site's catalog gives Scarab exactly this overall ratio for these counts.
        self.assertEqual(results.overall, 0.9777773030669765)
        self.assertEqual(round(results.overall, 4), 0.9778)
        self.assertEqual(table(text), [
            "| `cbor-decoding`, Conway | 43,276 | 43,434 | 99.63% | yes |",
            "| `cbor-decoding`, Dijkstra | 34,049 | 40,437 | 84.20% | no |",
            "| `ledger-rules` | 368 | 368 | 100% | yes |",
            "| `uplc-conformance` | 1,063 | 1,064 | 99.90% | yes |",
            "| `uplc-turbo` | 3,660 | 3,997 | 91.56% | yes |",
            "| **Overall** | | | **97.77%** | |",
        ])
        self.assertIn(f"tree/{score.SITE_COMMIT}", text)
        self.assertIn("mean of the ratios of the site's 4 scenarios", text)


def item(bench="a.flat", **fields):
    return benchmark(bench, **fields)


class TurboTest(unittest.TestCase):
    """9dc68a75 scenarios/uplc-turbo/build.py: within twice the budget or within 200 µs (CPU) or 320 KiB (memory)."""

    def met(self, **fields):
        return score.benchmark_met(item(**fields))

    def test_cpu_within_twice_the_budget_or_200_us(self):
        self.assertTrue(self.met(budget_cpu=100_000_000, cpu_us=200.0), "exactly twice the budget passes")
        self.assertFalse(self.met(budget_cpu=1_000_000_000, cpu_us=2001.0), "beyond twice and 1001 µs over fails")
        self.assertTrue(self.met(budget_cpu=100_000_000, cpu_us=300.0), "beyond twice but 200 µs over passes")
        self.assertFalse(self.met(budget_cpu=100_000_000, cpu_us=300.5), "beyond twice and 200.5 µs over fails")

    def test_memory_within_twice_the_budget_or_320_kib(self):
        self.assertTrue(self.met(budget_mem=1_000_000, mem_bytes=2_000_000.0), "exactly twice the budget passes")
        self.assertFalse(self.met(budget_mem=1_000_000, mem_bytes=2_000_001.0), "beyond twice and 1 MB over fails")
        self.assertTrue(self.met(budget_mem=1000, mem_bytes=1000 + 320 * 1024), "beyond twice but 320 KiB over passes")
        self.assertFalse(self.met(budget_mem=1000, mem_bytes=1001 + 320 * 1024), "beyond twice and 320 KiB + 1 fails")

    def test_both_dimensions_must_pass_and_one_must_be_comparable(self):
        self.assertFalse(self.met(budget_cpu=100_000_000, cpu_us=150.0, budget_mem=1000, mem_bytes=400_000.0),
                         "the memory fails it")
        self.assertTrue(self.met(budget_cpu=100_000_000, cpu_us=150.0, mem_bytes=400_000.0),
                        "without a memory budget the CPU decides")
        self.assertFalse(self.met(cpu_us=150.0, mem_bytes=1500.0), "without budgets nothing is comparable")
        self.assertFalse(self.met(budget_cpu=0, cpu_us=150.0, budget_mem=0, mem_bytes=1.0), "a zero budget is none")
        self.assertFalse(score.benchmark_met(None), "an unreported benchmark fails")

    def test_a_boolean_or_non_finite_value_is_not_a_number(self):
        self.assertFalse(score.benchmark_met({"bench": "a.flat", "budget_cpu": 100_000_000, "cpu_us": True}))
        self.assertFalse(score.benchmark_met({"bench": "a.flat", "budget_mem": 1000, "mem_bytes": float("inf")}))

    def test_the_corpus_sets_the_total_and_the_last_item_of_a_name_counts(self):
        report = turbo_report([item("a.flat", budget_cpu=100_000_000, cpu_us=150.0),
                               item("a.flat", budget_cpu=100_000_000, cpu_us=5000.0),
                               item("b.flat", budget_cpu=100_000_000, cpu_us=5000.0),
                               item("b.flat", budget_cpu=100_000_000, cpu_us=150.0),
                               item("x.flat", budget_cpu=100_000_000, cpu_us=150.0), "not an object"])
        self.assertEqual(score.turbo(report, ("a.flat", "b.flat", "c.flat")), score.Score(1, 3))


class UplcTest(unittest.TestCase):
    """9dc68a75 scenarios/uplc-conformance/build.py:37-76."""

    def test_whitespace_and_budget_text_are_read_as_the_site_reads_them(self):
        expected = (vector("a", '(program 1.0.0 [ (con string "a  b") , (con unit ()) ])', {"cpu": 7, "mem": -2}),)
        reported = vector("a", '(program 1.0.0\n  [(con string "a  b"),\n   (con unit ())]\n)', "cpu: 7 | mem: -2")
        self.assertEqual(score.uplc(uplc_report([reported]), expected), score.Score(1, 1))
        spaced = dict(reported, evaluation='(program 1.0.0 [(con string "a b"), (con unit ())])')
        self.assertEqual(score.uplc(uplc_report([spaced]), expected), score.Score(0, 1),
                         "whitespace inside a string constant counts")

    def test_a_budget_counts_only_where_one_is_expected(self):
        expected = (vector("a", "ok", None), vector("b", "ok", {"cpu": 1, "mem": 2}))
        report = uplc_report([vector("a", "ok", {"cpu": 9, "mem": 9}), vector("b", "ok", {"cpu": 1, "mem": 2, "x": 0})])
        self.assertEqual(score.uplc(report, expected), score.Score(1, 2), "an object budget must be the same object")

    def test_versions_must_agree_and_each_reported_vector_is_used_once(self):
        expected = (vector("a", "one", None, protocol="10.0"), vector("a", "two", None, protocol="11.0"),
                    vector("b", "ok", None, plutus="V2"), vector("c", "ok", None))
        report = uplc_report([vector("a", "two", None, protocol="11.0"), vector("a", "one", None, protocol="10.0"),
                              vector("b", "ok", None, plutus="V3")])
        self.assertEqual(score.uplc(report, expected), score.Score(2, 4),
                         "a's two vectors meet, b's Plutus version differs and c is not reported")
        once = uplc_report([vector("a", "one", None, protocol="10.0")])
        self.assertEqual(score.uplc(once, expected[:2]), score.Score(1, 2), "one reported vector meets one expectation")


class LedgerTest(unittest.TestCase):
    """9dc68a75 scenarios/ledger-rules/build.py:46-60 and 93-107."""

    def test_expected_results(self):
        for expected, result in LEDGER_KINDS[1:] + (("pass", "failed"), (None, "failed")):
            self.assertEqual(score.expected_result(expected), result, expected)

    def test_fixtures_by_their_first_five_characters_the_last_result_counting(self):
        with tempfile.TemporaryDirectory() as tmp:
            site = score.Site.read(write_site(Path(tmp), {"00001-a": ABSENT, "00002-b": {"predicate": "P"},
                                                          "00003-c": ABSENT}, [], ["a.flat"]))
        self.assertEqual(site.ledger, {"00001": "pass", "00002": "P", "00003": "pass"})
        report = ledger_report([("00001-long", "pass"), ("00002", "pass"), ("00002", "P"), ("00004", "pass")])
        self.assertEqual(score.ledger(report, site.ledger), score.Score(2, 3), "00003 has no result")


class CborTest(unittest.TestCase):
    """9dc68a75 scripts/build_common.py:256-306 and scenarios/cbor-decoding/build.py:104-115."""

    def test_every_sample_counts_and_a_true_outcome_is_met(self):
        report = cbor_report({"r/valid/1": True, "r/manual-invalid/2": "rejected: no", "r/invalid-zap-3/x/3": True})
        self.assertEqual(score.cbor(report, "conway"), score.Score(2, 3))
        self.assertEqual(score.cbor(cbor_report({}), "conway"), score.Score(0, 0))

    def test_what_the_site_cannot_read(self):
        cases = {
            "an unknown category": cbor_report({"r/other/1": True}),
            "a short sample path": cbor_report({"r/valid": True}),
            "an outcome neither true nor a message": cbor_report({"r/valid/1": False}),
            "another era": cbor_report({"r/valid/1": True}, "12.0"),
            "no known era": cbor_report({"r/valid/1": True}, "9.0"),
            "a missing field": {key: value for key, value in cbor_report({}).items() if key != "dataset_commit"},
            "another version": dict(cbor_report({}), version="3"),
        }
        for case, report in cases.items():
            with self.assertRaises(score.Unreadable, msg=case):
                score.cbor(report, "conway")
        with self.assertRaises(score.ScoreError):
            score.cbor(dict(cbor_report({}), version="1"), "conway")


class ResultsTest(unittest.TestCase):
    def site(self, root):
        return score.Site.read(write_site(root / "site", {"00001-a": ABSENT}, [vector("v", "ok", None)], ["a.flat"]))

    def test_a_missing_or_unreadable_scenario_counts_zero(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            write_report(root / "latest", "ledger-rules.json.gz", ledger_report([("00001", "pass")]))
            (root / "latest" / "uplc-conformance.json.gz").write_bytes(b"\x1f\x8b not gzip")
            write_report(root / "latest", "uplc-turbo.json.gz", dict(turbo_report([]), scenario="uplc-conformance"))
            results = score.results(root / "latest", self.site(root))
        self.assertEqual([(row.scenario, row.era, row.score, row.absence) for row in results.rows], [
            ("cbor-decoding", "conway", None, "no report"), ("cbor-decoding", "dijkstra", None, "no report"),
            ("ledger-rules", None, score.Score(1, 1), None), ("uplc-conformance", None, None, "unreadable"),
            ("uplc-turbo", None, None, "unreadable")])
        self.assertEqual(results.overall, 0.25)
        self.assertIn("| `uplc-conformance` | | | unreadable | yes |", score.markdown(results))

    def test_without_a_readable_conway_report_the_dijkstra_one_is_scored(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            write_report(root / "latest", "cbor-decoding-dijkstra.json.gz", cbor_report({"r/valid/1": True}, "12.0"))
            write_report(root / "latest", "cbor-decoding-conway.json.gz", cbor_report({"r/valid/1": True}, "12.0"))
            results = score.results(root / "latest", self.site(root))
        self.assertEqual([(row.era, row.absence, row.scored) for row in results.rows[:2]],
                         [("conway", "unreadable", False), ("dijkstra", None, True)])
        self.assertEqual(results.overall, 0.25)

    def test_the_overall_score_adds_one_quarter_at_a_time(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            write_report(root / "latest", "cbor-decoding-conway.json.gz",
                         cbor_report({f"r/valid/{n}": True if n else "rejected: no" for n in range(3)}))
            write_report(root / "latest", "ledger-rules.json.gz", ledger_report([("00001", "pass")]))
            write_report(root / "latest", "uplc-conformance.json.gz", uplc_report([vector("v", "ok", None)]))
            results = score.results(root / "latest", self.site(root))
        self.assertEqual(results.overall, 0.0 + (2 / 3) / 4 + 1 / 4 + 1 / 4 + 0 / 4)


class SiteTest(unittest.TestCase):
    def test_the_pinned_site_is_the_one_whose_scoring_is_mirrored(self):
        self.assertEqual(inputs.load_pins()["site"].spec["commit"], score.SITE_COMMIT,
                         "pins.json moved the site: read its scoring at the new commit and update score.py")

    def test_a_site_without_what_its_scoring_reads_is_refused(self):
        with tempfile.TemporaryDirectory() as tmp:
            site = write_site(Path(tmp), {"00001-a": ABSENT}, [], ["a.flat", "a.flat"])
            with self.assertRaises(score.ScoreError):
                score.Site.read(site)
            (site / "scenarios" / "uplc-turbo" / "schema.json").write_text(json.dumps({}))
            with self.assertRaises(score.ScoreError):
                score.Site.read(site)
        with tempfile.TemporaryDirectory() as tmp:
            site = write_site(Path(tmp), {"1-short": ABSENT}, [], ["a.flat"])
            with self.assertRaises(score.ScoreError):
                score.Site.read(site)


class PercentTest(unittest.TestCase):
    def test_ratios_are_cut_to_two_decimals_and_only_one_is_whole(self):
        self.assertEqual([score.percent(r) for r in (1.0, 0.99999, 0.9777773030669765, 0.5, 0.0)],
                         ["100%", "99.99%", "97.77%", "50.00%", "0.00%"])


if __name__ == "__main__":
    unittest.main()
