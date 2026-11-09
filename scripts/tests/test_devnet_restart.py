# SPDX-License-Identifier: Apache-2.0
"""Restart gate outcomes must retain failures even after successful progress and snapshots."""
import datetime
import io
import json
import pathlib
import sys
import tempfile
import time
import unittest
from contextlib import redirect_stdout
from types import SimpleNamespace
from unittest.mock import patch

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))

from devnet.scenarios import restart


ANCHOR = {"slot": 480, "blockNo": 24, "hash": "aa" * 32}
NEWER = {"slot": 610, "blockNo": 30, "hash": "dd" * 32}
IMMUTABLE_TIP = {"slot": 690, "blockNo": 34, "hash": "bb" * 32}
TIP = {"slot": 790, "blockNo": 39, "hash": "cc" * 32}
LONG_AGO = "2026-01-01T00:00:00Z"


def iso(seconds):
    return datetime.datetime.fromtimestamp(seconds, datetime.timezone.utc).isoformat().replace("+00:00", "Z")


def written(file, anchor, at=LONG_AGO, millis=100):
    return {"event": "snapshotWritten", "at": at, "file": file, "anchor": anchor, "bytes": 8, "millis": millis}


def round_events(round_, restored=("snapshot-1.bin", ANCHOR)):
    """A graceful stop of each run, a periodic snapshot in the first and a replay from `restored` in the second."""
    closed = {"event": "closedDB", "immutableTip": IMMUTABLE_TIP, "tip": TIP}
    if round_ == 1:
        return [{"event": "extended"}, written("snapshot-1.bin", ANCHOR), closed]
    file, anchor = restored
    return [{"event": "snapshotRestored", "file": file, "anchor": anchor},
            {"event": "replayStarted", "from": anchor, "immutableTip": IMMUTABLE_TIP},
            {"event": "replayFinished", "tip": IMMUTABLE_TIP, "replayed": 10}, {"event": "extended"}, closed]


class RestartTranscriptTests(unittest.TestCase):
    def run_gate(self, diagnostics, restored=("snapshot-1.bin", ANCHOR), files=(), at_stop=None):
        """The gate over a scripted run: `files` are snapshot files present from the start, and `at_stop(snapshots,
        now)` runs as the first stop ends and returns more events of that run."""
        with tempfile.TemporaryDirectory(prefix="scarab-restart-transcript-") as temporary:
            root = pathlib.Path(temporary)
            snapshots = root / "db" / "snapshots"
            snapshots.mkdir(parents=True)
            for name in ("snapshot-1.bin", *files):
                (snapshots / name).write_bytes(restart.SNAPSHOT_MAGIC)
            transcript = root / "follower.jsonl"
            rounds = 0
            original = []

            def stopped(_name, _process):
                nonlocal rounds
                rounds += 1
                events = round_events(rounds, restored)
                if rounds == 1 and at_stop is not None:
                    events += at_stop(snapshots, time.time())
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
        self.assertEqual(result["restored"], "snapshot-1.bin")
        self.assertEqual(result["replayed"], 10)

    def test_a_snapshot_written_by_the_stop_fails_the_round(self):
        # begun once the node had the signal
        def stop_snapshot(snapshots, now):
            (snapshots / "snapshot-2.bin").write_bytes(restart.SNAPSHOT_MAGIC)
            return [written("snapshot-2.bin", IMMUTABLE_TIP, at=iso(now + 1.0), millis=50)]
        code, result = self.run_gate({}, restored=("snapshot-2.bin", IMMUTABLE_TIP), at_stop=stop_snapshot)
        self.assertEqual(code, 1)
        self.assertIn("round 1: the stop wrote snapshot-2.bin: only the snapshot policy writes snapshots",
                      result["failed"])

    def test_a_file_with_no_event_written_at_the_stop_fails_the_round(self):
        def unlogged(snapshots, _now):
            (snapshots / "snapshot-2.bin").write_bytes(restart.SNAPSHOT_MAGIC)
            return []
        code, result = self.run_gate({}, restored=("snapshot-2.bin", NEWER), at_stop=unlogged)
        self.assertEqual(code, 1)
        self.assertIn("round 1: the stop wrote snapshot-2.bin: only the snapshot policy writes snapshots",
                      result["failed"])

    def test_a_periodic_snapshot_begun_before_the_signal_and_logged_after_it_is_the_policys(self):
        # renamed after the signal, its event says the write began 5 s before it
        def periodic(snapshots, now):
            (snapshots / "snapshot-2.bin").write_bytes(restart.SNAPSHOT_MAGIC)
            return [written("snapshot-2.bin", NEWER, at=iso(now + 0.5), millis=5000)]
        code, result = self.run_gate({}, restored=("snapshot-2.bin", NEWER), at_stop=periodic)
        self.assertEqual(code, 0, result)
        self.assertEqual(result["failed"], [])
        self.assertEqual(result["restored"], "snapshot-2.bin")

    def test_a_snapshot_cancelled_after_its_rename_is_the_one_the_restart_restores(self):
        # complete on disk before the signal, without the event its cancelled write did not log
        code, result = self.run_gate({}, restored=("snapshot-2.bin", NEWER), files=("snapshot-2.bin",))
        self.assertEqual(code, 0, result)
        self.assertEqual(result["restored"], "snapshot-2.bin")
        code, result = self.run_gate({}, files=("snapshot-2.bin",))
        self.assertEqual(code, 1)
        self.assertEqual(result["failed"],
                         ["the restart restored snapshot-1.bin, not the newest snapshot snapshot-2.bin"])


if __name__ == "__main__":
    unittest.main()
