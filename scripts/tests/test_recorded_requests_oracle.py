# SPDX-License-Identifier: Apache-2.0
"""Guard the runner of the recorded chain script requests: the retained fixture, the binding of the oracle's
requests to it, and the check of the image before it answers."""
import hashlib
import importlib.util
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("recorded_run", ROOT / "scripts/oracle/plutus/recorded/run.py")
RUN = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RUN)


class RecordedRequestsOracleTests(unittest.TestCase):
    def test_the_retained_fixture_is_bound_to_its_files_and_sources(self):
        requests, verdicts = RUN.validate(RUN.DIRECTORY)
        self.assertGreaterEqual(len(requests), 100)
        self.assertEqual({r["language"] for r in requests}, {1, 2, 3})
        self.assertIn("success", {v["stage"] for v in verdicts})

    def test_a_changed_verdict_fails_validation(self):
        with tempfile.TemporaryDirectory() as scratch:
            copy = Path(scratch) / "fixture"
            shutil.copytree(RUN.DIRECTORY, copy)
            rows = (copy / "verdicts.jsonl").read_text().splitlines()
            first = json.loads(rows[0])
            first["cpu"] = first.get("cpu", 0) + 1
            rows[0] = json.dumps(first)
            (copy / "verdicts.jsonl").write_text("\n".join(rows) + "\n")
            with self.assertRaisesRegex(ValueError, "changed fixture file: verdicts.jsonl"):
                RUN.validate(copy)

    def test_an_oracle_request_unlike_the_retained_one_is_refused(self):
        argument = "d87980"
        row = {"id": "a", "case": "c", "model": 0, "language": 2, "protocol": 8, "cpu": 1, "memory": 2,
               "scriptSha256": hashlib.sha256(bytes.fromhex("4e4d01")).hexdigest(),
               "argumentsSha256": [hashlib.sha256(bytes.fromhex(argument)).hexdigest()]}
        request = {**{k: row[k] for k in ("id", "case", "model", "language", "protocol", "cpu", "memory")},
                   "script": "4e4d01", "arguments": [argument], "verbose": True}
        RUN.bind([row], [request])
        for changed in ({"arguments": ["d87a80"]}, {"script": "4e4d02"}, {"cpu": 3}):
            with self.assertRaisesRegex(ValueError, "not the retained one"):
                RUN.bind([row], [{**request, **changed}])
        with self.assertRaisesRegex(ValueError, "1 retained"):
            RUN.bind([row], [])

    def test_an_image_with_another_harness_is_refused_before_it_answers(self):
        release = RUN.GENERATED.RELEASES["1.70.0.0"]
        stale = subprocess.CompletedProcess([], 0, stdout=f"{'0' * 64}\n{release['pin']}\nunchanged\n")
        with patch.object(RUN.subprocess, "run", return_value=stale):
            with self.assertRaisesRegex(ValueError, "not the build of the current harness"):
                RUN.check_image(release, "test")
        current = RUN.GENERATED.sha256(ROOT / release["harness"] / "Main.hs")
        accepted = subprocess.CompletedProcess([], 0, stdout=f"{current}\n{release['pin']}\nunchanged\n")
        with patch.object(RUN.subprocess, "run", return_value=accepted):
            RUN.check_image(release, "test")


if __name__ == "__main__":
    unittest.main()
