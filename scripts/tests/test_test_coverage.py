# SPDX-License-Identifier: Apache-2.0
import copy
import hashlib
import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from check_test_coverage import (fixture_inventory, read_json, validate_inventory, validate_provenance,
                                 validate_register)


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

    def test_duplicate_json_fields_cannot_hide_a_payload_identity_or_policy(self):
        path = self.root / "inventory.json"
        for raw in (
            '{"files": {}, "files": {}}',
            '{"files": {"fixtures/native/case.cbor": "unverified", '
            '"fixtures/native/case.cbor": "verified"}}',
            '{"entries": [{"status": "open", "status": "covered"}]}'
        ):
            with self.subTest(raw=raw):
                path.write_text(raw)
                with self.assertRaisesRegex(ValueError, "duplicate JSON field"):
                    read_json(path)

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

    def test_a_bounded_entry_names_its_issue_and_is_not_candidate_coverage(self):
        row = {"id": "membership", "classification": "sampling-limit", "status": "bounded",
               "scope": "refusal comparisons", "assertion": "one typed cause is in the native list",
               "rationale": "membership, not list equality", "owner": "ledger maintainer",
               "tracking": "https://github.com/KD5UJC-XYZ/scarab/issues/412",
               "resolution": "accumulated lists compared exactly",
               "evidence": ["fixtures/native/README.md"], "candidateRequired": False}
        register = {"schemaVersion": 1, "entries": [row]}
        self.assertEqual(validate_register(self.root, register, candidate=True), 1)
        # negative controls: a bounded entry without an issue, or one a candidate requires, fails
        for tracking in ("issue412", "https://github.com/KD5UJC-XYZ/scarab/pull/412"):
            row["tracking"] = tracking
            with self.assertRaisesRegex(ValueError, "bounded entry must name the issue"):
                validate_register(self.root, register)
        row["tracking"] = "https://github.com/KD5UJC-XYZ/scarab/issues/412"
        row["candidateRequired"] = True
        with self.assertRaisesRegex(ValueError, "unresolved required coverage"):
            validate_register(self.root, register, candidate=True)

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


class ProvenanceTest(unittest.TestCase):
    """A corpus's own provenance record is schema-checked, not prose: every field a reader trusts."""

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.corpus = self.root / "fixtures" / "reference" / "sample"
        self.corpus.mkdir(parents=True)
        (self.corpus / "README.md").write_text("Original native capture; no regeneration from Scala.\n")
        (self.corpus / "cases.jsonl").write_text('{"case": 1}\n{"case": 2}\n')
        self.digest = hashlib.sha256((self.corpus / "cases.jsonl").read_bytes()).hexdigest()
        self.record = {
            "schemaVersion": 1, "measuredAt": "2026-11-03",
            "scope": "one decoder at version 9", "ledgerSourceCommit": "f649f9751074d2ab3de033fc3912f29c9862c1f5",
            "casesSha256": self.digest, "cases": 2, "accepted": 1, "refused": 1,
            "notCovered": {"relayName": "the relay name reader belongs to #42"},
        }
        self.components = [{"id": "sample-row",
                            "evidence": {"paths": ["fixtures/reference/sample/cases.jsonl"]},
                            "limitations": {"issues": [42]}}]
        self.write()

    def write(self, record=None):
        (self.corpus / "provenance.json").write_text(json.dumps(record or self.record))

    def check(self, components=None):
        return validate_provenance(self.root, "fixtures/reference/sample",
                                   self.components if components is None else components)

    def test_a_complete_record_passes_and_the_file_is_named(self):
        self.assertEqual(self.check(), "fixtures/reference/sample/provenance.json")

    def test_the_envelope_is_versioned_an_object_and_scoped(self):
        record = dict(self.record)
        del record["schemaVersion"]
        self.write(record)
        with self.assertRaisesRegex(ValueError, "provenance.json: schemaVersion: must be 1"):
            self.check()
        record = dict(self.record, schemaVersion=2)
        self.write(record)
        with self.assertRaisesRegex(ValueError, "schemaVersion: must be 1"):
            self.check()
        record = dict(self.record, scope="  ")
        self.write(record)
        with self.assertRaisesRegex(ValueError, "provenance.json: scope: must be a non-empty string"):
            self.check()
        self.write()
        (self.corpus / "provenance.json").write_text(json.dumps([self.record]))
        with self.assertRaisesRegex(ValueError, "root.: must be one JSON object per corpus"):
            self.check()

    def test_a_record_must_name_the_source_revision_and_a_real_date(self):
        record = {key: value for key, value in self.record.items() if key != "ledgerSourceCommit"}
        self.write(record)
        with self.assertRaisesRegex(ValueError, "source revision: must record the source under one of"):
            self.check()
        self.write(dict(self.record, ledgerSourceCommit="f649f9"))
        with self.assertRaisesRegex(ValueError, "ledgerSourceCommit: must be a hexadecimal source revision"):
            self.check()
        self.write(dict(self.record, measuredAt="3 November 2026"))
        with self.assertRaisesRegex(ValueError, "measuredAt: must be an ISO 8601 date"):
            self.check()
        self.write(dict(self.record, measuredAt="2026-02-30"))
        with self.assertRaisesRegex(ValueError, "measuredAt: must be a real calendar date"):
            self.check()

    def test_a_missing_count_is_reported_with_the_file_and_field(self):
        record = {key: value for key, value in self.record.items() if key != "cases"}
        self.write(record)
        with self.assertRaisesRegex(ValueError, "sample/provenance.json: cases: must record how many cases "
                                                "cases.jsonl holds .2."):
            self.check()

    def test_a_count_or_hash_that_disagrees_with_the_corpus_fails(self):
        self.write(dict(self.record, cases=3))
        with self.assertRaisesRegex(ValueError, "cases: records 3 cases, cases.jsonl holds 2"):
            self.check()
        self.write(dict(self.record, casesSha256="0" * 64))
        with self.assertRaisesRegex(ValueError, "casesSha256: does not match cases.jsonl"):
            self.check()
        self.write(dict(self.record, casesSha256="ABC"))
        with self.assertRaisesRegex(ValueError, "casesSha256: must be a lowercase hexadecimal SHA-256"):
            self.check()

    def test_admitted_and_refused_cannot_contradict_the_case_count(self):
        self.write(dict(self.record, accepted=2, refused=2))
        with self.assertRaisesRegex(ValueError, "cases: records 2 cases, accepted 2 plus refused 2 is 4"):
            self.check()
        # the same rule binds the documented `cases` object form, and a per-batch record inside the file
        self.write(dict(self.record, cases={"file": "cases.jsonl", "count": 2, "sha256": self.digest},
                        accepted=2, refused=2))
        with self.assertRaisesRegex(ValueError, "cases.count: records 2 cases, accepted 2 plus refused 2 is 4"):
            self.check()
        self.write(dict(self.record, batches=[{"cases": 4, "accepted": 1, "refused": 1}]))
        with self.assertRaisesRegex(ValueError, "batches.0.cases: records 4 cases, accepted 1 plus refused 1 "
                                                "is 2"):
            self.check()

    def test_a_nested_digest_is_checked_wherever_it_sits(self):
        self.write(dict(self.record, batches=[{"configurationSha256": {"configuration.yaml": "not-a-digest"}}]))
        with self.assertRaisesRegex(ValueError, "batches.0.configurationSha256.configuration.yaml: must be a "
                                                "lowercase hexadecimal SHA-256"):
            self.check()

    def test_a_nested_cases_block_is_verified_against_the_file_it_names(self):
        self.write(dict(self.record, cases={"file": "cases.jsonl", "count": 2, "sha256": self.digest}))
        self.assertEqual(self.check(), "fixtures/reference/sample/provenance.json")
        self.write(dict(self.record, cases={"file": "cases.jsonl", "count": 2, "sha256": "0" * 64}))
        with self.assertRaisesRegex(ValueError, "cases.sha256: does not match cases.jsonl"):
            self.check()
        self.write(dict(self.record, cases={"file": "elsewhere.jsonl", "count": 2, "sha256": self.digest}))
        with self.assertRaisesRegex(ValueError, "cases.file: must name the corpus's case file"):
            self.check()

    def test_a_gap_must_name_an_issue_the_owning_register_entry_lists(self):
        self.write(dict(self.record, notCovered={"relayName": "the relay reader is a separate change"}))
        with self.assertRaisesRegex(ValueError, "notCovered.relayName: must name the issue that owns the gap"):
            self.check()
        self.write(dict(self.record, notCovered={"relayName": "the relay reader belongs to #77"}))
        with self.assertRaisesRegex(ValueError, "names issue #77, which the register entry 'sample-row' does "
                                                "not list in limitations.issues"):
            self.check()
        self.write(dict(self.record, notCovered={"relayName": "  "}))
        with self.assertRaisesRegex(ValueError, "notCovered.relayName: must say what the corpus does not cover"):
            self.check()

    def test_a_gap_without_a_register_entry_or_with_two_is_unowned(self):
        with self.assertRaisesRegex(ValueError, "cited by exactly one register entry"):
            self.check(components=[])
        second = copy.deepcopy(self.components[0])
        second["id"] = "other-row"
        with self.assertRaisesRegex(ValueError, "cited by exactly one register entry"):
            self.check(components=self.components + [second])

    def test_a_corpus_without_a_provenance_record_is_not_silently_checked(self):
        (self.corpus / "provenance.json").unlink()
        self.assertIsNone(self.check())


if __name__ == "__main__":
    unittest.main()
