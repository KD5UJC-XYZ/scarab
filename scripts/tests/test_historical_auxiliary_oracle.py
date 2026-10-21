# SPDX-License-Identifier: Apache-2.0
"""Guards for the Shelley/Allegra/Mary auxiliary reference corpus (#290 stage A)."""
import importlib.util
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
HERE = ROOT / 'scripts/oracle/plutus/historical-auxiliary'
SPEC = importlib.util.spec_from_file_location('historical_auxiliary_run', HERE / 'run.py')
RUN = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RUN)


class HistoricalAuxiliaryOracleTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.rows = RUN.validate()
        cls.by_kind = {}
        for row in cls.rows:
            cls.by_kind.setdefault(row['kind'], {})[row['era']] = row['oracle']

    def test_counts_and_uniqueness(self):
        self.assertEqual(len(self.rows), 504)
        self.assertEqual(len({row['name'] for row in self.rows}), 504)
        self.assertEqual({(r['era'], r['protocolMajor']) for r in self.rows},
                         {('Shelley', 2), ('Allegra', 3), ('Mary', 4)})

    def test_sixty_four_byte_bound_starts_after_protocol_two(self):
        for kind in ['meta-bytes-65', 'meta-text-65-ascii', 'meta-key-bytes-65', 'meta-depth-1000-65-bytes-leaf']:
            self.assertEqual({e: o['decoded'] for e, o in self.by_kind[kind].items()},
                             {'Shelley': True, 'Allegra': False, 'Mary': False})
        for kind in ['meta-bytes-64', 'meta-text-64-ascii', 'meta-bytes-chunked-64', 'meta-text-chunked-64']:
            self.assertTrue(all(o['decoded'] for o in self.by_kind[kind].values()))

    def test_pre_alonzo_has_no_tag_259_and_shelley_has_no_list_form(self):
        for kind, outcomes in self.by_kind.items():
            if kind.startswith('tag259-') or kind.startswith('tag258-'):
                self.assertFalse(any(o['decoded'] for o in outcomes.values()), kind)
        self.assertFalse(self.by_kind['array-meta-with-script']['Shelley']['decoded'])
        self.assertTrue(self.by_kind['array-meta-with-script']['Allegra']['decoded'])
        self.assertTrue(self.by_kind['array-meta-with-script']['Mary']['decoded'])

    def test_invalid_metadata_predicate_is_never_observed_before_alonzo(self):
        for row in self.rows:
            oracle = row['oracle']
            if row['mode'] == 'aux' and oracle['decoded']:
                self.assertTrue(oracle['valid'], row['name'])
            if row['mode'] == 'tx' and oracle['decoded']:
                self.assertNotIn('InvalidMetadata', oracle['failures'], row['name'])

    def test_structural_refusal_precedes_hash_mismatch(self):
        for era in ('Shelley', 'Allegra', 'Mary'):
            self.assertFalse(self.by_kind['tx-wrong-hash-structurally-invalid-tag259'][era]['decoded'])
        self.assertEqual(self.by_kind['tx-wrong-hash-bytes-65']['Shelley']['failures'], ['ConflictingMetadataHash'])
        self.assertFalse(self.by_kind['tx-wrong-hash-bytes-65']['Allegra']['decoded'])
        self.assertFalse(self.by_kind['tx-wrong-hash-bytes-65']['Mary']['decoded'])

    def test_hash_is_over_original_bytes(self):
        for kind in ['tx-noncanonical-match-original-bytes', 'tx-indefinite-map-match-original-bytes']:
            self.assertTrue(all(o['failures'] == [] for o in self.by_kind[kind].values()))
        for kind in ['tx-noncanonical-hash-of-canonical-reencoding', 'tx-indefinite-map-hash-of-canonical-reencoding']:
            self.assertTrue(all(o['failures'] == ['ConflictingMetadataHash'] for o in self.by_kind[kind].values()))


if __name__ == '__main__':
    unittest.main()
