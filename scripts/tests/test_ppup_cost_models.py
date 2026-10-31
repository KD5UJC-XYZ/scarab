# SPDX-License-Identifier: Apache-2.0
"""Pending cost-model equality compares complete values, while retaining original CBOR evidence."""
from copy import deepcopy
import json
from pathlib import Path
import sys
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from devnet import compare, eratx, eraview


def indefinite(models):
    return b'\xbf' + b''.join(eratx.enc(language) + b'\x9f' + b''.join(eratx.enc(n) for n in parameters) + b'\xff'
                              for language, parameters in models.items()) + b'\xff'


def proposals(raw):
    return {phase: {'genesis': {'14': '820700', '18': raw.hex()}} for phase in ('current', 'future')}


class ProposalCostModelTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        genesis = json.loads((Path(__file__).resolve().parents[2] / 'fixtures/eras/genesis/alonzo-genesis.json').read_text())
        cls.models = {0: genesis['costModels']['PlutusV1'], 1: genesis['extraConfig']['costModels']['PlutusV2']}

    def semantic(self, models):
        return eraview.proposal_values(proposals(eratx.enc(models)))[0]

    def test_real_full_vectors_match_with_distinct_definite_and_indefinite_encodings(self):
        definite, varying = proposals(eratx.enc(self.models)), proposals(indefinite(self.models))
        self.assertTrue(compare.diff(definite, varying))  # Reproduces the old false-positive comparator.
        before = deepcopy(varying)
        left, left_raw = eraview.proposal_values(definite)
        right, right_raw = eraview.proposal_values(varying)
        self.assertEqual(compare.diff(left, right), [])
        self.assertEqual(varying, before)
        for phase in ('current', 'future'):
            self.assertEqual(left_raw[phase]['genesis'], definite[phase]['genesis']['18'])
            self.assertEqual(right_raw[phase]['genesis'], varying[phase]['genesis']['18'])
            self.assertEqual(left[phase]['genesis']['18'], {str(k): v for k, v in self.models.items()})

    def test_every_parameter_order_length_language_and_other_update_field_remains_compared(self):
        variants = []
        changed = deepcopy(self.models); changed[1][-1] += 1; variants.append(changed)
        changed = deepcopy(self.models); changed[1][-2:] = reversed(changed[1][-2:]); variants.append(changed)
        changed = deepcopy(self.models); changed[1].pop(); variants.append(changed)
        changed = deepcopy(self.models); changed[1].append(42); variants.append(changed)
        changed = deepcopy(self.models); del changed[0]; variants.append(changed)
        changed = deepcopy(self.models); changed[2] = [1, 2, 3]; variants.append(changed)
        for changed in variants:
            self.assertTrue(compare.diff(self.semantic(self.models), self.semantic(changed)))
        for phase in ('current', 'future'):
            changed = self.semantic(self.models)
            changed[phase]['genesis']['14'] = '820800'
            self.assertTrue(compare.diff(self.semantic(self.models), changed))
            changed = self.semantic(self.models)
            changed[phase]['other-genesis'] = changed[phase].pop('genesis')
            self.assertTrue(compare.diff(self.semantic(self.models), changed))

    def test_map_order_is_irrelevant_but_no_map_entry_is_discarded(self):
        swapped = dict(reversed(list(self.models.items())))
        self.assertEqual(compare.diff(self.semantic(self.models), self.semantic(swapped)), [])
        self.assertEqual(eraview.cost_model_values('bfff'), {})
        self.assertEqual(eraview.cost_model_values('a0'), {})

    def test_malformed_trailing_duplicate_and_wrong_typed_fields_are_rejected(self):
        bad = [b'', b'\x80', b'\xa1', b'\xb8', b'\xbf\x00\x80',
               eratx.enc(self.models) + b'\x00', b'\xa2\x00\x80\x00\x80',
               eratx.enc({True: [1]}), eratx.enc({-1: [1]}), eratx.enc({256: [1]}),
               eratx.enc({0: True}), eratx.enc({0: [True]}), eratx.enc({0: [b'bytes']}),
               eratx.enc({0: [2 ** 63]}), eratx.enc({0: [-(2 ** 63) - 1]})]
        for raw in bad:
            with self.subTest(raw=raw.hex()), self.assertRaises(ValueError):
                eraview.cost_model_values(raw.hex())

    def test_live_pre_conway_comparison_uses_semantic_values_and_retains_both_raw_encodings(self):
        left, right = proposals(eratx.enc(self.models)), proposals(indefinite(self.models))
        tip = {'slot': 123, 'hash': 'block'}
        reference = {'epoch': 7, 'utxo': {}, 'rewardUpdate': None, 'params': {}, 'ppup': left,
                     'mir': {'reserves': {}, 'treasury': {}, 'deltaReserves': 0, 'deltaTreasury': 0}}
        scala = {**reference, 'ppup': right}
        session = Mock(); context = Mock(); context.__enter__ = Mock(return_value=session)
        context.__exit__ = Mock(return_value=False)
        with patch.object(compare, 'protocol_parameters', return_value={}), \
                patch.object(compare, 'lsq', return_value=context), \
                patch.object(eraview, 'reference_pre_conway', return_value=(tip, reference)), \
                patch.object(compare, 'scala_state', return_value=scala):
            result = compare.compare_pre_conway('isolated', 'alonzo', attempts=1)
        self.assertTrue(result['matched'])
        self.assertEqual(result['differenceCount'], 0)
        evidence = result['evidence']['ppupCostModelEncodings']
        for phase in ('current', 'future'):
            self.assertEqual(evidence['reference'][phase]['genesis'], left[phase]['genesis']['18'])
            self.assertEqual(evidence['scala'][phase]['genesis'], right[phase]['genesis']['18'])

    def test_comparison_error_fails_even_with_enough_other_matches_while_intentional_skips_do_not(self):
        rows = {era: {'matched': 5, 'mismatched': 0, 'errors': 0, 'skipped': 2} for era in compare.ERAS}
        whole = {era: {'matched': 5, 'mismatched': 0, 'errors': 0, 'skipped': 0} for era in compare.ERAS[1:]}
        comparison = SimpleNamespace(per_era=rows, whole=whole)
        self.assertEqual(compare.judge_comparisons(comparison, 3, True), [])
        rows['alonzo']['errors'] = 1
        self.assertEqual(compare.judge_comparisons(comparison, 3, True), ['alonzo: 1 ledger comparison errors'])
