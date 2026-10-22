# SPDX-License-Identifier: Apache-2.0
"""Restart gate outcomes must retain failures even after successful progress and snapshots."""
import io
import json
import pathlib
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from types import SimpleNamespace
from unittest.mock import patch

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))

from devnet.scenarios import restart


class RestartTranscriptTests(unittest.TestCase):
    def run_gate(self, diagnostics):
        with tempfile.TemporaryDirectory(prefix="scarab-restart-transcript-") as temporary:
            root = pathlib.Path(temporary)
            snapshots = root / "db" / "snapshots"
            snapshots.mkdir(parents=True)
            (snapshots / "snapshot-1.bin").write_bytes(restart.SNAPSHOT_MAGIC)
            transcript = root / "follower.jsonl"
            rounds = 0
            original = []

            def stopped(_name, _process):
                nonlocal rounds
                rounds += 1
                events = [{"event": "extended"}, {"event": "shutdownSnapshot"}]
                if rounds == 2:
                    events.append({"event": "snapshotRestored"})
                lines = [json.dumps(event) for event in events] + diagnostics.get(rounds, [])
                text = "\n".join(lines) + "\n"
                original.append(text)
                with transcript.open("a") as output:
                    output.write(text)
                return 1.0

            output = io.StringIO()
            with patch.object(restart, "data_root", return_value=root), \
                    patch.object(restart, "up"), \
                    patch.object(restart, "_start", return_value=object()), \
                    patch.object(restart, "_wait_for", return_value=True), \
                    patch.object(restart, "_stop", side_effect=stopped), \
                    patch.object(restart.time, "sleep"), redirect_stdout(output):
                with self.assertRaises(SystemExit) as exit_:
                    restart.restart(SimpleNamespace(name="test", seconds=0))
            self.assertEqual(transcript.read_text(), "".join(original))
            self.assertEqual(rounds, 2)
            return exit_.exception.code, json.loads(output.getvalue())

    def test_validation_error_cannot_be_hidden_by_progress_and_a_restored_snapshot(self):
        error = json.dumps({"event": "error", "message": "invalid original block proof"})
        code, result = self.run_gate({1: [error]})
        self.assertEqual(code, 1)
        self.assertEqual(result["failed"], ["round 1: follower error: invalid original block proof"])
        self.assertEqual(result["stopSeconds"], [1.0, 1.0])

    def test_unexpected_output_fails_the_affected_round_without_losing_the_transcript(self):
        code, result = self.run_gate({2: ["uncaught worker exception"]})
        self.assertEqual(code, 1)
        self.assertEqual(result["failed"], ["round 2: unexpected follower output: uncaught worker exception"])

    def test_expected_startup_and_starvation_diagnostics_match_follower_policy(self):
        code, result = self.run_gate({
            1: ["SLF4J: No providers were found."],
            2: ["Your app's responsiveness to a new asynchronous event exceeded the threshold."],
        })
        self.assertEqual(code, 0)
        self.assertEqual(result["failed"], [])


if __name__ == "__main__":
    unittest.main()
