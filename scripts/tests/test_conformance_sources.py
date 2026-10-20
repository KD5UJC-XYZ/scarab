# SPDX-License-Identifier: Apache-2.0
"""Source drift must not silently become a generator or compatibility pass."""

import importlib.util
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch


SCRIPT = Path(__file__).resolve().parents[1] / "check_conformance_sources.py"
SPEC = importlib.util.spec_from_file_location("conformance_sources", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


class ConformanceSourcesTest(unittest.TestCase):
    def setUp(self):
        self.pin = "1" * 40
        self.head = "2" * 40
        self.source = MODULE.Source("sample", "example/repo", self.pin, ("tests/",))
        self.calls = []

    def fetcher(self, before, after, *, truncated=False):
        def fetch(path):
            self.calls.append(path)
            if path.endswith("/commits/HEAD"):
                return {"sha": self.head}
            commit = path.split("/trees/", 1)[1].split("?", 1)[0]
            tree = before if commit == self.pin else after
            return {"truncated": truncated, "tree": [
                {"type": "blob", "path": name, "sha": sha} for name, sha in tree.items()]}
        return fetch

    def test_detects_added_removed_and_changed_generators(self):
        fetch = self.fetcher({"tests/changed": "a", "tests/deleted": "b"},
                             {"tests/changed": "c", "tests/added": "d"})
        result = MODULE.inspect(self.source, fetch)
        self.assertEqual(result["status"], "changed")
        self.assertEqual(result["added"], ["tests/added"])
        self.assertEqual(result["removed"], ["tests/deleted"])
        self.assertEqual(result["changed"], ["tests/changed"])
        self.assertEqual(result["pin"], self.pin)
        self.assertEqual(result["head"], self.head)
        self.assertTrue(all("/HEAD?" not in path for path in self.calls))

    def test_unrelated_source_drift_is_not_generator_drift(self):
        result = MODULE.inspect(self.source, self.fetcher(
            {"tests/same": "a", "runtime/file": "b"},
            {"tests/same": "a", "runtime/file": "c"}))
        self.assertEqual(result["status"], "unchanged")

    def test_truncated_or_missing_watched_trees_are_errors(self):
        with self.assertRaisesRegex(ValueError, "incomplete"):
            MODULE.inspect(self.source, self.fetcher({"tests/file": "a"}, {}, truncated=True))
        with self.assertRaisesRegex(ValueError, "no files"):
            MODULE.inspect(self.source, self.fetcher({"runtime/file": "a"}, {}))

    def test_upstream_failure_is_not_unchanged(self):
        def failed(_):
            raise OSError("upstream unavailable")
        with self.assertRaises(OSError):
            MODULE.inspect(self.source, failed)

    def test_formal_artifacts_are_compared_with_artifacts_not_source_branch(self):
        source = MODULE.Source("formal", "example/formal", self.pin, ("hs/",), "master-artifacts")
        def fetch(path):
            if path.endswith("/commits/master-artifacts"):
                return {"sha": self.pin}
            if "/trees/" in path:
                return {"tree": [{"type": "blob", "path": "hs/Wrapper.hs", "sha": "a"}]}
            self.fail(f"unexpected request: {path}")
        self.assertEqual(MODULE.inspect(source, fetch)["status"], "unchanged")

    def test_pins_come_from_existing_reference_evidence(self):
        rows = MODULE.sources(SCRIPT.parent.parent)
        self.assertEqual({row.name for row in rows},
                         {"ledger", "network", "rewards", "formal", "constrained", "plutus"})
        for row in rows:
            self.assertRegex(row.commit, r"^[0-9a-f]{40}$")

    def test_branch_names_cannot_replace_pins(self):
        source = MODULE.Source("sample", "example/repo", "main", ("tests/",))
        with self.assertRaisesRegex(ValueError, "complete commit"):
            MODULE.inspect(source, self.fetcher({}, {}))

    def test_failed_observation_writes_error_artifact_and_returns_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "report.json"
            summary = Path(directory) / "summary.md"
            argv = [str(SCRIPT), "--output", str(output), "--summary", str(summary)]
            with patch.object(sys, "argv", argv), \
                    patch.object(MODULE, "sources", return_value=[self.source]), \
                    patch.object(MODULE, "inspect", side_effect=OSError("upstream unavailable")), \
                    patch("builtins.print"):
                self.assertEqual(MODULE.main(), 1)
            report = MODULE.json.loads(output.read_text())
            self.assertEqual(report["sources"][0]["status"], "error")
            self.assertEqual(report["sources"][0]["trackedRef"], "HEAD")
            self.assertIn("error", summary.read_text())


if __name__ == "__main__":
    unittest.main()
