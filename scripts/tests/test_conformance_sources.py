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

    def test_a_valid_root_cannot_hide_a_misspelled_root(self):
        source = MODULE.Source("sample", "example/repo", self.pin, ("tests/", "test-typo/"))
        with self.assertRaisesRegex(ValueError, "test-typo/"):
            MODULE.inspect(source, self.fetcher({"tests/same": "a"}, {"tests/same": "a"}))

    def test_removed_and_new_roots_retain_their_source_events(self):
        source = MODULE.Source("sample", "example/repo", self.pin, ("old/", "new/"))
        result = MODULE.inspect(source, self.fetcher({"old/file": "a"}, {"new/file": "b"}))
        self.assertEqual(result["removed"], ["old/file"])
        self.assertEqual(result["added"], ["new/file"])
        self.assertEqual(result["watchedRoots"], [
            {"prefix": "old/", "pinnedFiles": 1, "observedFiles": 0},
            {"prefix": "new/", "pinnedFiles": 0, "observedFiles": 1}])

    def test_network_watch_includes_governor_and_cardano_codec_generators(self):
        rows = MODULE.sources(SCRIPT.parent.parent)
        network = next(row for row in rows if row.name == "network")
        paths = ["ouroboros-network/tests/lib/Test/Ouroboros/Network/PeerSelection/RootPeersDNS.hs",
                 "cardano-diffusion/tests/lib/Test/Cardano/Network/PeerSelection/LocalRootPeers.hs",
                 "cardano-diffusion/protocols/tests-lib/Cardano/Network/Protocol/Handshake/Test.hs",
                 "cardano-diffusion/protocols/cddl/specs/handshake-node-to-client.cddl"]
        before = {path: "a" for path in paths}
        # Other declared roots are present, so a typo in one cannot hide behind this example.
        before.update({prefix + "file": "a" for prefix in network.prefixes})
        after = dict(before)
        after.update({path: "b" for path in paths})
        source = MODULE.Source(network.name, network.repository, self.pin, network.prefixes)
        result = MODULE.inspect(source, self.fetcher(before, after))
        self.assertEqual(result["changed"], sorted(paths))

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

    def test_unrelated_document_commit_does_not_replace_plutus_release_pin(self):
        original_read = Path.read_text
        def read(path, *args, **kwargs):
            text = original_read(path, *args, **kwargs)
            return "Related commit `" + "f" * 40 + "`\n" + text if path.name == "plutus-conformance.md" else text
        with patch.object(Path, "read_text", read):
            plutus = next(row for row in MODULE.sources(SCRIPT.parent.parent) if row.name == "plutus")
        self.assertEqual(plutus.commit, "507cab8cf6192e5143f12be5b2d3005a2928dc6d")

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

    def test_missing_root_artifact_names_the_coverage_gap(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "report.json"
            summary = Path(directory) / "summary.md"
            argv = [str(SCRIPT), "--output", str(output), "--summary", str(summary)]
            with patch.object(sys, "argv", argv), \
                    patch.object(MODULE, "sources", return_value=[self.source]), \
                    patch.object(MODULE, "inspect", side_effect=MODULE.MissingWatchedRoots(["test-typo/"])), \
                    patch("builtins.print"):
                self.assertEqual(MODULE.main(), 1)
            report = MODULE.json.loads(output.read_text())
            self.assertEqual(report["sources"][0]["missingWatchedPrefixes"], ["test-typo/"])


if __name__ == "__main__":
    unittest.main()
