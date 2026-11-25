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
from conformance_site import ABSENT, benchmark, turbo_report, write_site  # noqa: E402


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
CPU = {"model": "Some CPU @ 2.00GHz", "logical_cpus": 4, "available_cpus": 3}


def turbo_run(directory, items, benches, requested=("uplc-turbo",), status="report"):
    """A run directory holding a uplc-turbo report of `items`, and its inputs with a site whose corpus is
    `benches`; the inputs directory."""
    raw = gzip.compress(json.dumps(turbo_report(items)).encode())
    Path(directory, TURBO).write_bytes(raw)
    Path(directory, TURBO.removesuffix(".json.gz") + ".provenance.json").write_text(json.dumps({
        "report": TURBO, "sha256": hashlib.sha256(raw).hexdigest(), "validation": {"valid": True},
        "scarab": {"commit": "e" * 40, "dirty": False},
        "environment": {"runner": "github-actions", "cpu": CPU, "limits": {"cpus": "3", "memory": "8g"}}}))
    outcome = {"scenario": "uplc-turbo", "era": None, "status": status}
    if status == "report":
        outcome["report"] = TURBO
    Path(directory, "summary.json").write_text(json.dumps({"requested": list(requested), "outcomes": [outcome]}))
    site = Path(directory, "inputs", inputs.load_pins()["site"].directory)
    write_site(site, {"00001-a": ABSENT}, [], benches)
    return Path(directory, "inputs")


def bench(i):
    return f"{i:04d}" + "AB" * 30 + ".flat"


class TurboScoreTest(unittest.TestCase):
    """The uplc-turbo section of the job summary: the score by the site's rule (`score.py`) and the misses by why."""

    def items(self):
        """Of a corpus of 25: one met at 2.1 times its CPU budget, within 200 µs of it; one missed on CPU alone at
        2.05 times; one on memory and CPU; 21 met; the last not reported."""
        return [benchmark(bench(0), 100_000_000, 1000, 210.0, 900.0),
                benchmark(bench(1), 1_000_000_000, 1000, 2_050.0, 900.0),
                benchmark(bench(2), 1_000_000_000, 1_000_000, 2_100.0, 2_500_000.0)] \
            + [benchmark(bench(i), 100_000_000, 1000, 100.0, 1000.0 + i) for i in range(3, 24)]

    def test_the_score_and_the_misses_are_written_for_the_job_summary(self):
        with tempfile.TemporaryDirectory() as tmp:
            given = turbo_run(tmp, self.items(), [bench(i) for i in range(25)])
            self.assertEqual(summarize.main(["--out", tmp, "--inputs", str(given)]), 0)
            scored = json.loads(Path(tmp, "turbo-score.json").read_text())
            page = Path(tmp, "turbo-score.md").read_text()
        self.assertEqual((scored["verdict"], scored["met"], scored["total"]), ("scored", 22, 25))
        self.assertEqual(scored["missed"], {"memory": 1, "CPU alone": 1, "not reported": 1,
                                            "nothing comparable": 0})
        self.assertEqual(scored["missed_on_memory_also_on_cpu"], 1)
        self.assertEqual(scored["within_memory_limit"], 23)
        self.assertEqual((scored["cpu"], scored["runner"]), (CPU, "github-actions"))
        self.assertEqual([(miss["bench"], miss["shortfall"], miss["cpu_ratio"], miss["memory_ratio"])
                          for miss in scored["misses"]],
                         [(bench(1), "CPU alone", 2.05, 0.9), (bench(2), "memory", 2.1, 2.5),
                          (bench(24), "not reported", None, None)])
        self.assertIn("**22 of 25 programs met**", page)
        self.assertIn("| on memory | 1 (1 also on CPU) |", page)
        self.assertIn("| on CPU alone | 1 |", page)
        self.assertIn("Some CPU @ 2.00GHz, 4 logical CPUs, 3 available to the container; container limits 3 CPUs "
                      "and 8g", page)
        rows = [line for line in page.splitlines() if line.startswith("| `")]
        self.assertEqual(rows, [f"| `{bench(2)}` | memory | 2.10 | 2.50 |",
                                f"| `{bench(1)}` | CPU alone | 2.05 | 0.90 |",
                                f"| `{bench(24)}` | not reported | – | – |"], "the worst first")
        self.assertEqual((scored["bytes_per_memory_unit_median"], scored["bytes_per_memory_unit_p95"]), (1.012, 1.023),
                         "24 ratios: 0.9 twice, 1.003 to 1.023, and 2.5; the 95th percentile is the 23rd")
        self.assertIn("median 1.012, 95th percentile 1.023", page)

    def test_the_page_lists_the_worst_misses_and_the_json_all(self):
        items = [benchmark(bench(i), 100_000_000, None, 1000.0 + i) for i in range(summarize.TURBO_ROWS + 5)]
        with tempfile.TemporaryDirectory() as tmp:
            given = turbo_run(tmp, items, [bench(i) for i in range(len(items))])
            self.assertEqual(summarize.main(["--out", tmp, "--inputs", str(given)]), 0)
            scored = json.loads(Path(tmp, "turbo-score.json").read_text())
            page = Path(tmp, "turbo-score.md").read_text()
        self.assertEqual(len(scored["misses"]), summarize.TURBO_ROWS + 5)
        rows = [line for line in page.splitlines() if line.startswith("| `")]
        self.assertEqual(len(rows), summarize.TURBO_ROWS)
        self.assertTrue(rows[0].startswith(f"| `{bench(summarize.TURBO_ROWS + 4)}`"), "the worst first")
        self.assertIn("And 5 more, in `turbo-score.json`.", page)
        self.assertIsNone(scored["bytes_per_memory_unit_median"], "no memory budget, no ratio")

    def test_a_requested_turbo_report_that_is_missing_or_altered_is_incomplete(self):
        with tempfile.TemporaryDirectory() as tmp:
            given = turbo_run(tmp, self.items(), [bench(i) for i in range(25)], status="failed")
            self.assertEqual(summarize.main(["--out", tmp, "--inputs", str(given)]), 1)
            self.assertIn("INCOMPLETE", Path(tmp, "turbo-score.md").read_text())
        with tempfile.TemporaryDirectory() as tmp:
            given = turbo_run(tmp, self.items(), [bench(i) for i in range(25)])
            Path(tmp, TURBO).write_bytes(Path(tmp, TURBO).read_bytes() + b"x")
            self.assertEqual(summarize.main(["--out", tmp, "--inputs", str(given)]), 1)
            self.assertEqual(json.loads(Path(tmp, "turbo-score.json").read_text())["verdict"], "incomplete")
        with tempfile.TemporaryDirectory() as tmp:
            turbo_run(tmp, self.items(), [bench(i) for i in range(25)])
            self.assertEqual(summarize.main(["--out", tmp, "--inputs", str(Path(tmp, "elsewhere"))]), 1,
                             "without the site's corpus there is no score")

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
