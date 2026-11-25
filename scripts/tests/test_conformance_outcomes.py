# SPDX-License-Identifier: Apache-2.0
"""Conformance is a vector verdict, not the report generator's exit status."""
import copy
import gzip
import hashlib
import json
import os
from pathlib import Path
import sys
import subprocess
import tempfile
import textwrap
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
sys.path.insert(0, str(Path(__file__).resolve().parent))
from conformance import inputs, outcomes, summarize  # noqa: E402
from conformance_site import ABSENT, CPU, benchmark, turbo_report, verify, write_pair, write_site  # noqa: E402


def report(**changes):
    return {"version": "2", "dataset_commit": "a" * 40, "implementation_commit": "b" * 40,
            "protocol_version": "11.0", "results": {"body/valid/a.input.cbor": True,
                "body/invalid-zap-1/b.input.cbor": "decode: succeeded when expected to fail"}, **changes}


def write(directory, data):
    name = "scarab-20261115-cbor-decoding-conway.json.gz"
    raw = gzip.compress(json.dumps(data).encode())
    Path(directory, name).write_bytes(raw)
    Path(directory, name.removesuffix(".json.gz") + ".provenance.json").write_text(json.dumps({
        "sha256": hashlib.sha256(raw).hexdigest(), "validation": {"valid": True},
        "scarab": {"commit": data["implementation_commit"]}}))
    Path(directory, "summary.json").write_text(json.dumps({"outcomes": [
        {"scenario": "cbor-decoding", "era": "conway", "status": "report", "report": name}]}))
    return Path(directory, name)


class CborOutcomesTest(unittest.TestCase):
    def test_failure_is_counted_even_in_successfully_generated_report(self):
        actual = outcomes.cbor(report())
        self.assertEqual(actual["verdict"], "mismatches")
        self.assertEqual((actual["matched"], actual["mismatched"], actual["total"]), (1, 1, 2))
        self.assertEqual(actual["by_rule"], {"body": {"matched": 1, "mismatched": 1}})

    def test_only_literal_true_passes_and_missing_results_are_incomplete(self):
        for verdict in (False, 1, None, {}, ""):
            with self.subTest(verdict=verdict), self.assertRaises(outcomes.OutcomeError):
                outcomes.cbor(report(results={"body/valid/a.input.cbor": verdict}))
        for results in ({}, None, []):
            with self.assertRaises(outcomes.OutcomeError):
                outcomes.cbor(report(results=results))

    def test_scope_changes_do_not_look_like_zero_new_disagreements(self):
        before = report()
        for changes in ({"dataset_commit": "c" * 40}, {"protocol_version": "12.0"},
                        {"results": {"other/valid/a.input.cbor": True}}):
            result = outcomes.compare(report(**changes), before)
            self.assertEqual(result["status"], "incomparable")
            self.assertNotIn("new_mismatches", result)
        self.assertEqual(outcomes.compare(report(), None)["status"], "unavailable")

    def test_new_and_resolved_disagreements_are_by_sample_not_failure_text(self):
        before = report()
        after = copy.deepcopy(before)
        after["results"] = {"body/valid/a.input.cbor": "encode: differs",
                            "body/invalid-zap-1/b.input.cbor": True}
        actual = outcomes.compare(after, before)
        self.assertEqual(actual["new_mismatches"], ["body/valid/a.input.cbor"])
        self.assertEqual(actual["resolved_mismatches"], ["body/invalid-zap-1/b.input.cbor"])

    def test_real_compressed_report_is_verified_and_failed_vectors_remain_visible(self):
        with tempfile.TemporaryDirectory() as tmp:
            write(tmp, report())
            self.assertEqual(summarize.main(["--out", tmp]), 0, "generation succeeds; the verdict still fails")
            result = json.loads(Path(tmp, "cbor-outcomes.json").read_text())["outcomes"][0]
            self.assertEqual(result["verdict"], "mismatches")
            page = Path(tmp, "cbor-outcomes.md").read_text()
            self.assertIn("MISMATCHES: 1 mismatched / 2", page)
            self.assertIn("**unknown**", page)

    def test_tampered_or_missing_report_never_establishes_a_pass(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = write(tmp, report())
            path.write_bytes(path.read_bytes() + b"tampered")
            self.assertEqual(summarize.main(["--out", tmp]), 1)
            self.assertIn("INCOMPLETE", Path(tmp, "cbor-outcomes.md").read_text())
            path.unlink()
            self.assertEqual(summarize.main(["--out", tmp]), 1)

    def test_skipped_era_and_missing_runner_summary_are_incomplete(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.assertEqual(summarize.main(["--out", tmp]), 1)
            Path(tmp, "summary.json").write_text(json.dumps({"outcomes": [
                {"scenario": "cbor-decoding", "era": "dijkstra", "status": "skipped", "detail": "no tool"}]}))
            self.assertEqual(summarize.collect(Path(tmp))[0]["verdict"], "incomplete")

    def test_previous_report_requires_matching_hashes_too(self):
        with tempfile.TemporaryDirectory() as now, tempfile.TemporaryDirectory() as old:
            write(now, report())
            path = write(old, report())
            self.assertEqual(summarize.collect(Path(now), Path(old))[0]["comparison"]["status"], "compared")
            path.write_bytes(b"wrong")
            result = summarize.collect(Path(now), Path(old))[0]
            self.assertEqual(result["verdict"], "mismatches")
            self.assertEqual(result["comparison"]["status"], "unavailable")

    def test_missing_or_malformed_previous_run_keeps_current_verdict(self):
        with tempfile.TemporaryDirectory() as now, tempfile.TemporaryDirectory() as old:
            write(now, report())
            for malformed in (None, "[]", '{}', '{"outcomes":[1]}'):
                if malformed is not None:
                    Path(old, "summary.json").write_text(malformed)
                result = summarize.collect(Path(now), Path(old))[0]
                self.assertEqual(result["verdict"], "mismatches")
                self.assertEqual(result["comparison"]["status"], "unavailable")

    def test_invalid_evidence_shapes_still_write_an_incomplete_page(self):
        with tempfile.TemporaryDirectory() as tmp:
            for malformed in ('[]', '{}', '{"outcomes":[1]}'):
                Path(tmp, "summary.json").write_text(malformed)
                self.assertEqual(summarize.main(["--out", tmp]), 1)
                self.assertIn("INCOMPLETE", Path(tmp, "cbor-outcomes.md").read_text())
            write(tmp, report())
            path = next(Path(tmp).glob("*.provenance.json"))
            path.write_text('[]')
            self.assertEqual(summarize.main(["--out", tmp]), 1)

    def test_requested_but_absent_era_is_incomplete(self):
        with tempfile.TemporaryDirectory() as tmp:
            write(tmp, report())
            path = Path(tmp, "summary.json")
            data = json.loads(path.read_text())
            data["requested"] = ["cbor-decoding"]
            path.write_text(json.dumps(data))
            items = summarize.collect(Path(tmp))
            self.assertEqual(items[1], {"era": "dijkstra", "verdict": "incomplete",
                                       "reason": "requested era has no outcome"})

    def test_workflow_keeps_other_scenario_results_and_does_not_gate_publication_on_skips(self):
        workflow = (Path(__file__).resolve().parents[2] / '.github/workflows/conformance.yml').read_text()
        self.assertIn('cat "$RUNNER_TEMP/conformance/summary.json"', workflow)
        self.assertIn('if ! python3 -I -B scripts/conformance/summarize.py', workflow)
        self.assertNotIn('exit "$result"', workflow)

    def test_summary_shell_preserves_other_results_when_summarizer_cannot_write_a_page(self):
        workflow = (Path(__file__).resolve().parents[2] / '.github/workflows/conformance.yml').read_text()
        step = workflow.split('      - name: Summary\n', 1)[1].split('\n      - name:', 1)[0]
        script = textwrap.dedent(step.split('        run: |\n', 1)[1]).strip()
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root/'conformance').mkdir()
            (root/'conformance/summary.json').write_text('{"outcomes":[{"scenario":"ledger-rules"}]}')
            (root/'python3').write_text('#!/bin/sh\nexit 1\n')
            (root/'python3').chmod(0o755)
            env = {**os.environ, 'PATH': str(root)+os.pathsep+os.environ['PATH'],
                   'RUNNER_TEMP': tmp, 'GITHUB_STEP_SUMMARY': str(root/'page.md')}
            result = subprocess.run(['/bin/bash', '-eo', 'pipefail', '-c', script], env=env, capture_output=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn('ledger-rules', (root/'page.md').read_text())
            self.assertIn('INCOMPLETE', (root/'page.md').read_text())

    def test_nightly_detector_selects_cbor_without_enabling_publication(self):
        workflow = (Path(__file__).resolve().parents[2] / '.github/workflows/conformance.yml').read_text()
        self.assertIn("- cron: '17 8 * * *'", workflow)
        self.assertIn("CBOR_DECODING: ${{ github.event_name == 'schedule' || inputs.cbor-decoding }}", workflow)
        self.assertIn('if: ${{ inputs.publish }}', workflow)
        self.assertIn('--event schedule --status success', workflow)
        self.assertIn('--previous "$RUNNER_TEMP/conformance-previous"', workflow)


TURBO = "scarab-20261125-uplc-turbo.json.gz"
OTHER_CPU = {"model": "Intel(R) Xeon(R) Platinum 8370C CPU @ 2.80GHz"}


def turbo_run(directory, items, benches, requested=("uplc-turbo",), status="report", cpu=None):
    """A run directory holding a uplc-turbo report of `items` and its provenance as run.py writes them, and its inputs
    with a verified site whose corpus is `benches`; the inputs directory."""
    write_pair(directory, TURBO, turbo_report(items), cpu)
    outcome = {"scenario": "uplc-turbo", "era": None, "status": status}
    if status == "report":
        outcome["report"] = TURBO
    Path(directory, "summary.json").write_text(json.dumps({"requested": list(requested), "outcomes": [outcome]}))
    site = Path(directory, "inputs", inputs.load_pins()["site"].directory)
    write_site(site, {"00001-a": ABSENT}, [], benches)
    return verify(Path(directory, "inputs"), site)


def bench(i):
    return f"{i:04d}" + "AB" * 30 + ".flat"


def scored(directory, given):
    code = summarize.main(["--out", str(directory), "--inputs", str(given)])
    return code, json.loads(Path(directory, "turbo-score.json").read_text()), \
        Path(directory, "turbo-score.md").read_text()


class TurboScoreTest(unittest.TestCase):
    """The uplc-turbo section of the job summary: the score by the site's rule (`score.py`) and the misses by why,
    of a report checked with its provenance and against the verified site (`evidence.py`)."""

    def items(self):
        """Of a corpus of 25: one met at 2.1 times its CPU budget, within 200 µs of it; one missed on CPU alone at
        2.05 times; one on memory and CPU; 21 met; the last not reported."""
        return [benchmark(bench(0), 100_000_000, 1000, 210.0, 900.0),
                benchmark(bench(1), 1_000_000_000, 1000, 2_050.0, 900.0),
                benchmark(bench(2), 1_000_000_000, 1_000_000, 2_100.0, 2_500_000.0)] \
            + [benchmark(bench(i), 100_000_000, 1000, 100.0, 1000.0 + i) for i in range(3, 24)]

    def test_the_score_and_the_misses_are_written_for_the_job_summary(self):
        with tempfile.TemporaryDirectory() as tmp:
            code, summary, page = scored(tmp, turbo_run(tmp, self.items(), [bench(i) for i in range(25)]))
        self.assertEqual(code, 0)
        self.assertEqual((summary["verdict"], summary["met"], summary["total"]), ("scored", 22, 25))
        self.assertEqual(summary["missed"], {"memory": 1, "CPU alone": 1, "not reported": 1,
                                             "nothing comparable": 0})
        self.assertEqual(summary["missed_on_memory_also_on_cpu"], 1)
        self.assertEqual(summary["within_memory_limit"], 23)
        self.assertEqual((summary["cpu"], summary["runner"], summary["on_reference_cpu"]),
                         (CPU, "github-actions", True))
        self.assertEqual([(miss["bench"], miss["shortfall"], miss["cpu_ratio"], miss["memory_ratio"])
                          for miss in summary["misses"]],
                         [(bench(1), "CPU alone", 2.05, 0.9), (bench(2), "memory", 2.1, 2.5),
                          (bench(24), "not reported", None, None)])
        self.assertEqual((summary["bytes_per_memory_unit_median"], summary["bytes_per_memory_unit_p95"]),
                         (1.012, 1.023), "24 ratios: 0.9 twice, 1.003 to 1.023, and 2.5; the 95th percentile is the 23rd")
        self.assertIn("median 1.012, 95th percentile 1.023", page)
        self.assertIn("**22 of 25 programs met**", page)
        self.assertNotIn("Not publishable", page)
        self.assertIn("| on memory | 1 (1 also on CPU) |", page)
        self.assertIn("| on CPU alone | 1 |", page)
        self.assertIn(f"CPU {CPU['model']}, 4 logical CPUs, `nproc` 3 and a quota of 3.0 in the container; "
                      "container limits 3 CPUs and 8g", page)
        rows = [line for line in page.splitlines() if line.startswith("| `")]
        self.assertEqual(rows, [f"| `{bench(2)}` | memory | 2.10 | 2.50 |",
                                f"| `{bench(1)}` | CPU alone | 2.05 | 0.90 |",
                                f"| `{bench(24)}` | not reported | – | – |"], "the worst first")

    def test_the_worst_are_ranked_by_the_dimensions_that_fail_them(self):
        # A passing dimension's ratio counts for nothing: c is 2.2 times over on CPU and within on memory; d is 2.1
        # times over on memory, and within on CPU although 150 times its budget (inside 200 µs of it), which ranked
        # it first when both ratios counted.
        items = [benchmark("c.flat", 1_000_000_000, 1000, 2_200.0, 900.0),
                 benchmark("d.flat", 1_000_000, 1_000_000, 150.0, 2_100_000.0)]
        with tempfile.TemporaryDirectory() as tmp:
            code, summary, page = scored(tmp, turbo_run(tmp, items, ["c.flat", "d.flat"]))
        self.assertEqual([(miss["bench"], miss["shortfall"], miss["failing_ratio"], miss["cpu_ratio"])
                          for miss in summary["misses"]],
                         [("c.flat", "CPU alone", 2.2, 2.2), ("d.flat", "memory", 2.1, 150.0)])
        rows = [line.split(" | ")[0] for line in page.splitlines() if line.startswith("| `")]
        self.assertEqual(rows, ["| `c.flat`", "| `d.flat`"])

    def test_the_page_lists_the_worst_misses_and_the_json_all(self):
        items = [benchmark(bench(i), 100_000_000, None, 1000.0 + i) for i in range(summarize.TURBO_ROWS + 5)]
        with tempfile.TemporaryDirectory() as tmp:
            code, summary, page = scored(tmp, turbo_run(tmp, items, [bench(i) for i in range(len(items))]))
        self.assertEqual(code, 0)
        self.assertEqual(len(summary["misses"]), summarize.TURBO_ROWS + 5)
        rows = [line for line in page.splitlines() if line.startswith("| `")]
        self.assertEqual(len(rows), summarize.TURBO_ROWS)
        self.assertTrue(rows[0].startswith(f"| `{bench(summarize.TURBO_ROWS + 4)}`"), "the worst first")
        self.assertIn("And 5 more, in `turbo-score.json`.", page)
        self.assertIsNone(summary["bytes_per_memory_unit_median"], "no memory budget, no ratio")

    def test_a_name_that_is_not_plain_is_escaped_outside_a_code_span(self):
        self.assertEqual(summarize._code("a-B_0.flat"), "`a-B_0.flat`")
        self.assertEqual(summarize._code("a`b|c<d&e"), "a&#96;b&#124;c&lt;d&amp;e")
        items = [benchmark("x|`y`.flat", 1_000_000_000, 1000, 5_000.0, 900.0)]
        with tempfile.TemporaryDirectory() as tmp:
            _, _, page = scored(tmp, turbo_run(tmp, items, ["x|`y`.flat"]))
        self.assertIn("| x&#124;&#96;y&#96;.flat | CPU alone |", page)

    def test_a_report_of_another_cpu_is_scored_and_marked_not_publishable(self):
        with tempfile.TemporaryDirectory() as tmp:
            code, summary, page = scored(tmp, turbo_run(tmp, self.items(), [bench(i) for i in range(25)],
                                                        cpu=OTHER_CPU))
        self.assertEqual((code, summary["verdict"], summary["on_reference_cpu"]), (0, "scored", False))
        self.assertIn(f"**Not publishable: measured on {OTHER_CPU['model']}, not the reference CPU "
                      "Intel(R) Xeon(R) 6975P-C.**", page)

    def test_a_runner_off_the_reference_cpu_is_named_and_stays_green(self):
        with tempfile.TemporaryDirectory() as tmp:
            given = turbo_run(tmp, [], [bench(0)], status="not-reference-cpu")
            Path(tmp, TURBO).unlink()
            summary_path = Path(tmp, "summary.json")
            run_summary = json.loads(summary_path.read_text())
            run_summary["outcomes"][0]["detail"] = "this runner's CPU is Another CPU, not the reference X"
            summary_path.write_text(json.dumps(run_summary))
            code, summary, page = scored(tmp, given)
        self.assertEqual((code, summary["verdict"]), (0, "not-reference-cpu"), "not a failure")
        self.assertIn("**NOT MEASURED: this runner's CPU is not the reference.**", page)
        self.assertIn("this runner's CPU is Another CPU, not the reference X", page)

    def test_evidence_that_does_not_hold_together_is_incomplete(self):
        cases = {}
        with tempfile.TemporaryDirectory() as tmp:
            given = turbo_run(tmp, self.items(), [bench(i) for i in range(25)], status="failed")
            cases["the scenario failed"] = scored(tmp, given)
        with tempfile.TemporaryDirectory() as tmp:
            given = turbo_run(tmp, self.items(), [bench(i) for i in range(25)])
            Path(tmp, TURBO).write_bytes(Path(tmp, TURBO).read_bytes() + b"x")
            cases["the report altered"] = scored(tmp, given)
        with tempfile.TemporaryDirectory() as tmp:
            given = turbo_run(tmp, self.items(), [bench(i) for i in range(25)])
            report = json.loads(gzip.decompress(Path(tmp, TURBO).read_bytes()))
            raw = json.dumps(dict(report, version="experimental")).encode()
            data = gzip.compress(raw)
            Path(tmp, TURBO).write_bytes(data)
            record_path = Path(tmp, TURBO.removesuffix(".json.gz") + ".provenance.json")
            record = json.loads(record_path.read_text())
            record.update(sha256=hashlib.sha256(data).hexdigest(), bytes=len(data),
                          json_sha256=hashlib.sha256(raw).hexdigest())
            record_path.write_text(json.dumps(record))
            cases["an experimental report, its provenance re-hashed"] = scored(tmp, given)
        with tempfile.TemporaryDirectory() as tmp:
            given = turbo_run(tmp, self.items(), [bench(i) for i in range(25)])
            schema = Path(given, inputs.load_pins()["site"].directory, "scenarios", "uplc-turbo", "schema.json")
            schema.write_text(schema.read_text().replace(bench(24), "another.flat"))
            cases["a corpus its receipt does not verify"] = scored(tmp, given)
        with tempfile.TemporaryDirectory() as tmp:
            turbo_run(tmp, self.items(), [bench(i) for i in range(25)])
            cases["no site"] = scored(tmp, Path(tmp, "elsewhere"))
        for case, (code, summary, page) in cases.items():
            with self.subTest(case):
                self.assertEqual((code, summary["verdict"]), (1, "incomplete"))
                self.assertIn("INCOMPLETE", page)
        self.assertIn("site's scoring cannot read it", cases["an experimental report, its provenance re-hashed"][1]
                      ["reason"])
        self.assertIn("not the one its receipt verified", cases["a corpus its receipt does not verify"][1]["reason"])

    def test_a_run_without_turbo_writes_no_turbo_page(self):
        with tempfile.TemporaryDirectory() as tmp:
            write(tmp, report())
            self.assertEqual(summarize.main(["--out", tmp]), 0)
            self.assertFalse(Path(tmp, "turbo-score.md").exists())
            self.assertIsNone(summarize.turbo(Path(tmp), Path(tmp, "inputs")))


class WorkflowTest(unittest.TestCase):
    def workflow(self):
        return (Path(__file__).resolve().parents[2] / '.github/workflows/conformance.yml').read_text()

    def test_turbo_is_an_ordinary_scenario_of_the_workflow_and_its_nightly_run(self):
        workflow = self.workflow()
        self.assertIn("UPLC_TURBO: ${{ github.event_name == 'schedule' || inputs.uplc-turbo }}", workflow)
        self.assertIn('if [ "$UPLC_TURBO" = true ]; then scenarios+=(uplc-turbo); fi', workflow)
        self.assertEqual(workflow.count("python3 -I -B scripts/conformance/run.py"), 1,
                         "one run, one --out, one artifact")
        self.assertNotIn("--allocation-only", workflow)
        self.assertNotIn("uplc-turbo-memory", workflow)
        inputs = workflow.split("    inputs:\n", 1)[1].split("\npermissions:", 1)[0]
        self.assertIn("      uplc-turbo:\n", inputs)
        self.assertIn("${{ runner.temp }}/conformance/turbo-score.md", workflow)
        self.assertIn('cat "$RUNNER_TEMP/conformance/turbo-score.md" >> "$GITHUB_STEP_SUMMARY"', workflow)
        self.assertIn('--inputs "$RUNNER_TEMP/conformance-inputs" --previous "$RUNNER_TEMP/conformance-previous"',
                      workflow)


if __name__ == "__main__":
    unittest.main()
