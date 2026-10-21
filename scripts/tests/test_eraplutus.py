# SPDX-License-Identifier: Apache-2.0
"""Admission coverage and conditional ledger effects of normal V1/V2 era load."""
import sys
from pathlib import Path
import unittest
from unittest.mock import patch
from types import SimpleNamespace

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from devnet import eraplutus


class EraPlutusTest(unittest.TestCase):
    normal = [("fund", 0), ("script", 0)]
    collateral = ("collateral", 0)
    before = {("fund", 0): (10, {}), ("script", 0): (10, {}), ("collateral", 0): (3, {})}
    outputs = {0: (18, {b"policy": {b"token": 7}})}

    def errors(self, after, valid):
        return eraplutus.effect_errors(self.before, after, self.normal, self.collateral, "new", valid, self.outputs)

    def test_success_requires_normal_consumption_mint_and_unchanged_collateral(self):
        after = {self.collateral: self.before[self.collateral], ("new", 0): self.outputs[0]}
        self.assertEqual(self.errors(after, True), [])
        for missing in (self.collateral, ("new", 0)):
            self.assertTrue(self.errors({k: v for k, v in after.items() if k != missing}, True))
        self.assertTrue(self.errors({**after, self.normal[0]: self.before[self.normal[0]]}, True))
        self.assertTrue(self.errors({**after, ("new", 1): (1, {})}, True))
        self.assertTrue(self.errors({**after, ("new", 0): (18, {})}, True))

    def test_invalid_tag_requires_collateral_consumption_and_no_mint_or_regular_outputs(self):
        after = {i: self.before[i] for i in self.normal}
        self.assertEqual(self.errors(after, False), [])
        self.assertTrue(self.errors({**after, self.collateral: self.before[self.collateral]}, False))
        self.assertTrue(self.errors({**after, ("new", 0): self.outputs[0]}, False))
        for normal in self.normal:
            self.assertTrue(self.errors({k: v for k, v in after.items() if k != normal}, False))
            self.assertTrue(self.errors({**after, normal: (9, {})}, False))

    def test_all_era_language_validity_combinations_are_required_and_failures_do_not_count(self):
        rows = [{"era": era, "language": language, "case": case, "passed": True}
                for era, languages in eraplutus.LANGUAGES.items() for language in languages
                for case in ("success", "collateral", "reject-failure-valid", "reject-success-invalid")]
        self.assertEqual(len(rows), 20)
        self.assertEqual(eraplutus.coverage_failures(rows), [])
        for index in range(len(rows)):
            self.assertTrue(eraplutus.coverage_failures(rows[:index] + rows[index + 1:]))
            self.assertTrue(eraplutus.coverage_failures(rows[:index] + [{**rows[index], "passed": False}] + rows[index + 1:]))

    def test_snapshot_indexes_all_addresses_and_preserves_assets(self):
        self.assertEqual(eraplutus.index_utxo((1, 4, {b"address": [(self.normal[0], 10, {b"p": {b"n": 2}})]})),
                         {self.normal[0]: (10, {b"p": {b"n": 2}})})


class BabbageModelBootstrapTest(unittest.TestCase):
    def propose(self, major):
        from devnet import cluster
        driver = cluster.EraDriver.__new__(cluster.EraDriver)
        driver.name = "isolated"
        driver.address = {1: "funding-address"}
        driver.utxo = lambda address: {"input#0": 100_000_000}
        driver.submit = lambda era, build, label: SimpleNamespace(returncode=0)
        driver.wait_spent = lambda *args: None
        genesis = {"costModels": {"PlutusV1": [1, 2]},
                   "extraConfig": {"costModels": {"PlutusV1": [9], "PlutusV2": [3, 4]}}}
        with patch.object(cluster, "read_json", return_value=genesis) as read, \
                patch.object(cluster, "put_file") as write, patch.object(cluster, "in_ref") as command:
            driver.propose("alonzo", 6, major, False)
        return read, write, command

    def test_vasil_proposal_installs_generated_v2_and_retains_live_genesis_v1(self):
        import json
        read, write, command = self.propose(7)
        read.assert_called_once()
        self.assertEqual(json.loads(write.call_args.args[2]), {"PlutusV1": [1, 2], "PlutusV2": [3, 4]})
        self.assertIn("--cost-model-file /work/tx/babbage-cost-models.json", command.call_args.args[1])

    def test_other_protocol_proposals_do_not_change_models(self):
        for major in (None, 3, 4, 5, 6, 8, 9):
            read, write, command = self.propose(major)
            read.assert_not_called()
            write.assert_not_called()
            self.assertNotIn("--cost-model-file", command.call_args.args[1])


class ReferenceObservationTest(unittest.TestCase):
    def load(self, era, actual=None, version=23):
        from unittest.mock import Mock
        indices = {"alonzo": 4, "babbage": 5, "conway": 6}
        session = Mock()
        session.agreed_version = version
        params = [0] * 24
        params[14 if era == "alonzo" else 12] = [7, 0]
        session.ledger_query.side_effect = [(123, b"point hash"), params]
        context = Mock()
        context.__enter__ = Mock(return_value=session)
        context.__exit__ = Mock(return_value=False)
        load = SimpleNamespace(lsq_factory=lambda: context, ERA_INDEX=indices,
                               current_era=lambda acquired: indices[era] if actual is None else actual)
        return load, session

    def test_actual_era_point_and_protocol_share_one_acquisition(self):
        from unittest.mock import call
        for era, index in (("alonzo", 4), ("babbage", 5), ("conway", 6)):
            load, session = self.load(era)
            self.assertEqual(eraplutus.reference_observation(load, era),
                             {"era": era, "eraIndex": index, "slot": 123, "hash": b"point hash".hex(),
                              "protocolVersion": {"major": 7, "minor": 0}})
            session.acquire.assert_called_once_with()
            self.assertEqual(session.ledger_query.call_args_list, [call(index, [0]), call(index, [3])])

    def test_era_drift_cannot_be_reported_as_the_target_era(self):
        load, session = self.load("alonzo", actual=5)
        with self.assertRaisesRegex(RuntimeError, "reference era index 5"):
            eraplutus.reference_observation(load, "alonzo")
        session.ledger_query.assert_not_called()

    def test_unknown_legacy_or_malformed_protocol_encoding_is_not_invented(self):
        load, session = self.load("alonzo", version=20)
        with self.assertRaisesRegex(RuntimeError, "NtC 21"):
            eraplutus.reference_observation(load, "alonzo")
        load, session = self.load("conway")
        session.ledger_query.side_effect = [(123, b"hash"), [0] * 24]
        with self.assertRaisesRegex(RuntimeError, "protocol-version encoding"):
            eraplutus.reference_observation(load, "conway")

    def test_pinned_reference_current_params_fixture_has_the_observed_protocol_pair(self):
        import json
        from devnet import ntcquery
        fixture = json.loads((Path(__file__).resolve().parents[2] / 'fixtures/devnet/lsq/golden.json').read_text())
        row = next(r for r in fixture['queries'] if r['label'] == 'GetCurrentPParams')
        [params] = ntcquery.decode_all(bytes.fromhex(row['result']))
        load, session = self.load('conway')
        session.ledger_query.side_effect = [(fixture['slot'], bytes.fromhex(fixture['hash'])), params]
        observed = eraplutus.reference_observation(load, 'conway')
        self.assertEqual(observed['protocolVersion'], {'major': 10, 'minor': 0})
        self.assertEqual(observed['slot'], fixture['slot'])
        self.assertEqual(observed['hash'], fixture['hash'])
