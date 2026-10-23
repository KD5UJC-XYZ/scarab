# SPDX-License-Identifier: Apache-2.0
"""Successful comparisons cannot conceal unexpected follower output in the soak gate."""
import io
import json
import pathlib
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from types import SimpleNamespace
from unittest.mock import Mock, patch

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))

from devnet.scenarios import soak


class SoakTranscriptTests(unittest.TestCase):
    def run_gate(self, diagnostics, comparison=None):
        with tempfile.TemporaryDirectory(prefix="scarab-soak-transcript-") as temporary:
            root = pathlib.Path(temporary)
            transcript = root / "follower.jsonl"
            original = json.dumps({"event": "extended"}) + "\n" + "\n".join(diagnostics) + "\n"
            transcript.write_text(original)
            streams = []

            def launch(_command, stdout=None, **_kwargs):
                if hasattr(stdout, "write"):
                    streams.append(stdout)
                    stdout.write(json.dumps({"submitted": 1}) + "\n")
                    stdout.flush()
                process = Mock()
                process.poll.return_value = None
                process.wait.return_value = 0
                return process

            output = io.StringIO()
            try:
                with patch.object(soak, "data_root", return_value=root), \
                        patch.object(soak, "up"), \
                        patch.object(soak, "docker"), \
                        patch.object(soak, "own_build", return_value=False), \
                        patch.object(soak.subprocess, "Popen", side_effect=launch), \
                        patch.object(soak.time, "monotonic", side_effect=[0, 0, 2]), \
                        patch.object(soak.time, "sleep"), \
                        patch.object(soak, "compare_or_error", return_value=comparison or {"matched": True}), \
                        redirect_stdout(output):
                    with self.assertRaises(SystemExit) as exit_:
                        soak.soak(SimpleNamespace(name="test", seconds=1, every=1, seed=0))
            finally:
                for stream in streams:
                    stream.close()
            summary = json.loads((root / "summary.json").read_text())
            self.assertEqual(transcript.read_text(), original)
            if comparison is None:
                self.assertEqual(summary["comparisons"], 1)
                self.assertEqual(summary["matched"], 1)
            self.assertEqual(summary["blocksApplied"], 1)
            self.assertTrue(summary["followerAliveAtEnd"])
            return exit_.exception.code, summary, output.getvalue()

    def test_unexpected_output_fails_despite_successful_live_comparisons(self):
        code, summary, output = self.run_gate(["uncaught worker exception"])
        self.assertEqual(code, 1)
        self.assertEqual(summary["unparsedFollowerOutput"], ["uncaught worker exception"])
        self.assertIn("unexpected follower output: uncaught worker exception", output)

    def test_expected_diagnostics_use_the_existing_follower_policy(self):
        code, summary, _ = self.run_gate([
            "SLF4J: No providers were found.",
            "Your app's responsiveness to a new asynchronous event exceeded the threshold.",
        ])
        self.assertEqual(code, 0)
        self.assertEqual(summary["unparsedFollowerOutput"], [])

    def test_a_comparison_error_fails_the_gate(self):
        code, summary, output = self.run_gate([], comparison={"error": "reference queries failed at the chosen block"})
        self.assertEqual(code, 1)
        self.assertEqual(summary["comparisonErrors"], 1)
        self.assertIn("1 ledger comparison errors", output)


if __name__ == "__main__":
    unittest.main()
