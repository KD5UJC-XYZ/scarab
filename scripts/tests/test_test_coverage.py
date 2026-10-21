# SPDX-License-Identifier: Apache-2.0
import copy
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from check_test_coverage import fixture_inventory, validate_inventory, validate_register


class FixtureCoverageTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.corpus = self.root / "fixtures" / "native"
        self.corpus.mkdir(parents=True)
        (self.corpus / "README.md").write_text("Original native output; no regeneration from Scala.\n")
        (self.corpus / "case.cbor").write_bytes(b"\x82\x01\x02")
        self.pinned = fixture_inventory(self.root)

    def test_missing_added_and_changed_payloads_are_rejected(self):
        original = self.corpus / "case.cbor"
        original.unlink()
        with self.assertRaisesRegex(ValueError, "missing=.*case.cbor"):
            validate_inventory(self.root, self.pinned)
        original.write_bytes(b"\x82\x01\x02")
        extra = self.corpus / "unexpected.cbor"
        extra.write_bytes(b"\x01")
        with self.assertRaisesRegex(ValueError, "added=.*unexpected.cbor"):
            validate_inventory(self.root, self.pinned)
        extra.unlink()
        original.write_bytes(b"\x82\x01\x03")
        with self.assertRaisesRegex(ValueError, "changed=.*case.cbor"):
            validate_inventory(self.root, self.pinned)

    def test_missing_or_empty_readme_cannot_hide_behind_unchanged_payloads(self):
        readme = self.corpus / "README.md"
        readme.unlink()
        with self.assertRaisesRegex(ValueError, "README.md"):
            validate_inventory(self.root, self.pinned)
        readme.write_text("\n")
        with self.assertRaisesRegex(ValueError, "empty corpus README"):
            validate_inventory(self.root, self.pinned)

    def test_module_local_resources_require_their_own_contract(self):
        module = self.root / "node/src/test/resources/upstream/native"
        module.mkdir(parents=True)
        (module / "case.cbor").write_bytes(b"\x01")
        pinned = fixture_inventory(self.root)
        with self.assertRaisesRegex(ValueError, "README.md"):
            validate_inventory(self.root, pinned)
        (module / "README.md").write_text("Native contract\n")
        self.assertEqual(validate_inventory(self.root, pinned), pinned)

    def test_symlink_payload_cannot_replace_a_pinned_regular_file(self):
        original = self.corpus / "case.cbor"
        original.unlink()
        (self.root / "elsewhere.cbor").write_bytes(b"\x82\x01\x02")
        original.symlink_to(self.root / "elsewhere.cbor")
        with self.assertRaisesRegex(ValueError, "nonregular"):
            fixture_inventory(self.root)

    def test_symlink_directories_and_dangling_aliases_cannot_hide_fixture_inputs(self):
        linked = self.corpus / "alias"
        linked.symlink_to(self.root / "missing", target_is_directory=True)
        with self.assertRaisesRegex(ValueError, "nonregular"):
            fixture_inventory(self.root)
        linked.unlink()
        linked.symlink_to(self.corpus, target_is_directory=True)
        with self.assertRaisesRegex(ValueError, "nonregular"):
            fixture_inventory(self.root)

    def test_coverage_register_does_not_authorize_unresolved_candidate_gaps(self):
        row = {"id": "native-join", "classification": "native-parity", "status": "open",
               "scope": "paired native snapshots", "assertion": "nonempty exact keys and values",
               "rationale": "zero comparisons must not pass", "owner": "storage maintainer",
               "tracking": "https://github.com/KD5UJC-XYZ/scarab/issues/294",
               "resolution": "negative controls and native paired comparison pass",
               "evidence": ["fixtures/native/README.md"], "candidateRequired": True}
        register = {"schemaVersion": 1, "entries": [row]}
        self.assertEqual(validate_register(self.root, register), 1)
        with self.assertRaisesRegex(ValueError, "unresolved required coverage"):
            validate_register(self.root, register, candidate=True)
        row["status"] = "covered"
        self.assertEqual(validate_register(self.root, register, candidate=True), 1)
        register["entries"].append(copy.deepcopy(row))
        with self.assertRaisesRegex(ValueError, "duplicate coverage ID"):
            validate_register(self.root, register)

    def test_stale_evidence_unknown_classification_and_path_escape_fail(self):
        row = {"id": "native", "classification": "native-parity", "status": "covered",
               "scope": "native data", "assertion": "original bytes", "rationale": "fixture binding",
               "owner": "maintainer", "tracking": "issue294", "resolution": "retain original hashes",
               "evidence": ["../outside"], "candidateRequired": False}
        register = {"schemaVersion": 1, "entries": [row]}
        with self.assertRaisesRegex(ValueError, "unsafe repository path"):
            validate_register(self.root, register)
        row["evidence"] = ["fixtures/native/missing.scala"]
        with self.assertRaisesRegex(ValueError, "missing or nonregular"):
            validate_register(self.root, register)
        row["evidence"] = "fixtures/native/README.md"
        with self.assertRaisesRegex(ValueError, "missing candidate policy/evidence"):
            validate_register(self.root, register)
        row["evidence"] = ["fixtures/native/README.md"]
        row["classification"] = "ignore-a-failure"
        with self.assertRaisesRegex(ValueError, "unknown classification"):
            validate_register(self.root, register)


if __name__ == "__main__":
    unittest.main()
