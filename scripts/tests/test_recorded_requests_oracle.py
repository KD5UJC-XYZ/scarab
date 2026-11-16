# SPDX-License-Identifier: Apache-2.0
"""Guard the runner of the recorded chain script requests: the retained fixture, the binding of the oracle's
requests to it, and the check of the image before it answers."""
import hashlib
import importlib.util
import json
from pathlib import Path
import shutil
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

    def binding(self, accepted=None, **changes):
        """The image binding of measurements that hold, with `changes` applied (None removes a key)."""
        release = RUN.GENERATED.RELEASES["1.70.0.0"]
        binary = "b" * 64
        measured = {
            f"file:{RUN.IMAGE_HARNESS}": RUN.GENERATED.sha256(ROOT / release["harness"] / "Main.hs"),
            f"file:{RUN.IMAGE_BUILD[0]}": "c" * 64,
            f"file:{RUN.IMAGE_BUILD[1]}": "d" * 64,
            f"file:{RUN.IMAGE_BINARY}": binary,
            "head": release["pin"], "pinned": "unchanged", "build": "current", "built": binary,
        }
        measured = {k: v for k, v in {**measured, **changes}.items() if v is not None}
        with patch.object(RUN, "entrypoint", return_value=[RUN.IMAGE_BINARY]), \
                patch.object(RUN, "measure", return_value=measured):
            return RUN.image_binding(release, "sha256:" + "a" * 64, "test", accepted)

    def test_an_image_that_runs_the_pinned_build_of_the_current_harness_is_bound(self):
        bound = self.binding()
        self.assertEqual(bound["imageId"], "sha256:" + "a" * 64)
        self.assertEqual(bound["imageFilesSha256"][RUN.IMAGE_BINARY], "b" * 64)
        self.binding(accepted={path: bound["imageFilesSha256"][path] for path in RUN.IMAGE_BUILD})

    def test_another_harness_or_a_changed_plutus_is_refused_before_it_answers(self):
        for changes, refusal in (({f"file:{RUN.IMAGE_HARNESS}": "0" * 64}, "stale oracle harness"),
                                 ({"head": "0" * 40}, "plutus is not at"),
                                 ({"pinned": None}, "plutus source differs")):
            with self.assertRaisesRegex(ValueError, refusal):
                self.binding(**changes)

    def test_a_stale_executable_next_to_matching_sources_is_refused(self):
        with self.assertRaisesRegex(ValueError, "stale executable"):
            self.binding(build=None)
        with self.assertRaisesRegex(ValueError, "entry point is not the executable cabal built"):
            self.binding(built="e" * 64)

    def test_an_executable_other_than_the_accepted_one_is_refused(self):
        accepted = {RUN.IMAGE_BUILD[0]: "c" * 64, RUN.IMAGE_BUILD[1]: "d" * 64, RUN.IMAGE_BINARY: "f" * 64}
        with self.assertRaisesRegex(ValueError, "differ from the accepted oracle build"):
            self.binding(accepted=accepted)

    def test_an_image_with_another_entry_point_is_refused(self):
        release = RUN.GENERATED.RELEASES["1.70.0.0"]
        with patch.object(RUN, "entrypoint", return_value=["/bin/sh"]):
            with self.assertRaisesRegex(ValueError, "does not run"):
                RUN.image_binding(release, "sha256:" + "a" * 64, "test", None)

    def test_the_retained_provenance_accepts_one_build(self):
        accepted = RUN.accepted_build(RUN.DIRECTORY)
        self.assertEqual(set(accepted), set(RUN.IMAGE_BUILD))
        provenance = json.loads((RUN.DIRECTORY / "provenance.json").read_text())
        self.assertEqual(provenance["oracleBinarySha256"], accepted[RUN.IMAGE_BINARY])

if __name__ == "__main__":
    unittest.main()
