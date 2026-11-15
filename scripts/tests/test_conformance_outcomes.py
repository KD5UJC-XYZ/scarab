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
from conformance import outcomes, summarize  # noqa: E402


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


if __name__ == "__main__":
    unittest.main()
