# SPDX-License-Identifier: Apache-2.0
import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import candidate_manifest as contract
from candidate_fixture import make_candidate


class CandidateManifestTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.manifest = make_candidate(self.root)

    def save(self):
        (self.root / 'candidate.json').write_text(json.dumps(self.manifest))

    def replace_evidence(self, kind, transform):
        descriptor = next(e for e in self.manifest['evidence'] if e['kind'] == kind)
        path = self.root / descriptor['file']['path']
        value = contract.read_json(path)
        transform(value)
        path.write_text(json.dumps(value))
        descriptor['file'] = contract.file_record(self.root, path)
        self.save()

    def test_real_transport_fixture_and_all_signing_subjects_validate(self):
        self.assertEqual(contract.validate(self.root, require_release=True)['status'], 'verified')
        subjects = contract.signing_subjects(self.root)
        self.assertEqual(len(subjects), 6)
        self.assertEqual({s['name'] for s in subjects}, {'candidate.json', 'oci-manifest'} |
                         {item['file']['path'] for item in self.manifest['subjects']} |
                         {item['bom']['file']['path'] for item in self.manifest['subjects']})

    def test_development_candidates_cannot_become_release_inputs(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            make_candidate(root, release=False)
            self.assertEqual(contract.validate(root)['source']['buildIdentity']['mode'], 'development')
            with self.assertRaisesRegex(ValueError, 'development'):
                contract.validate(root, require_release=True)

    def test_each_missing_mandatory_evidence_kind_fails(self):
        for kind in contract.EVIDENCE:
            with self.subTest(kind=kind):
                original = self.manifest['evidence']
                self.manifest['evidence'] = [item for item in original if item['kind'] != kind]
                self.save()
                with self.assertRaisesRegex(ValueError, 'mandatory'):
                    contract.validate(self.root)
                self.manifest['evidence'] = original

    def test_dropped_bom_component_fails_even_after_updating_transport_hash(self):
        subject = next(s for s in self.manifest['subjects'] if s['kind'] == 'jar')
        path = self.root / subject['bom']['file']['path']
        bom = contract.read_json(path)
        bom['components'].pop()
        path.write_text(json.dumps(bom))
        subject['bom']['file'] = contract.file_record(self.root, path)
        self.save()
        with self.assertRaisesRegex(ValueError, 'resolved/packaged'):
            contract.validate(self.root)

    def test_native_identity_must_bind_the_pinned_packaged_library(self):
        self.replace_evidence('native-identity', lambda value: value['resources']['Linux/amd64/libblst.so'].update(pinned='b' * 64))
        with self.assertRaisesRegex(ValueError, 'native identity'):
            contract.validate(self.root)
        self.replace_evidence('native-identity', lambda value: value.update(jarSha256='c' * 64))
        with self.assertRaisesRegex(ValueError, 'native identity'):
            contract.validate(self.root)

    def test_os_omission_cannot_be_hidden_by_refreshing_all_descriptor_hashes(self):
        record = next(e for e in self.manifest['evidence'] if e['kind'] == 'image-inventory')
        inventory_path = self.root / record['file']['path']
        inventory = contract.read_json(inventory_path)
        scan = self.root / inventory['scannerBom']['path']
        raw = contract.read_json(scan)
        raw['components'] = [c for c in raw['components'] if c.get('name') != 'synthetic']
        scan.write_text(json.dumps(raw))
        inventory['scannerBom'] = contract.file_record(self.root, scan)
        inventory_path.write_text(json.dumps(inventory))
        record['file'] = contract.file_record(self.root, inventory_path)
        self.save()
        with self.assertRaisesRegex(ValueError, 'installed OS package'):
            contract.validate(self.root)

    def test_licence_review_cannot_be_silently_called_approved(self):
        self.manifest['assurance']['licenceReview']['status'] = 'approved'
        self.save()
        with self.assertRaisesRegex(ValueError, 'review boundary'):
            contract.validate(self.root)

    def test_boolean_is_not_an_integer_schema_version(self):
        self.manifest['schemaVersion'] = True
        self.save()
        with self.assertRaisesRegex(ValueError, 'unsupported'):
            contract.validate(self.root)
