# SPDX-License-Identifier: Apache-2.0
"""Auxiliary script framing, coverage, and actual-reference receipt/effect requirements."""
from pathlib import Path
import sys
import json
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from devnet import eraauxiliary, eratx, ntcquery


class AuxiliaryBuilderTest(unittest.TestCase):
    def build(self, **kwargs):
        return eratx.build_tx("conway", [("01" * 32, 0)], [], 500_000, [], **kwargs)

    def test_tagged_auxiliary_keeps_script_bytes_and_hashes_exact_original_encoding(self):
        # A noncanonical CBOR bytes header inside PlutusBinary must remain untouched.
        script = bytes.fromhex("580401000061")
        tx, txid = self.build(metadata={1: b"metadata"}, aux_scripts=[[1, []]], aux_plutus={3: [script]})
        decoded = ntcquery.decode_all(tx)
        body, witnesses, valid, auxiliary = decoded
        tag, fields = auxiliary.tag, auxiliary.value
        self.assertEqual(tag, 259)
        self.assertEqual(fields, {0: {1: b"metadata"}, 1: [[1, []]], 4: [script]})
        self.assertEqual(body[7], eratx.blake2b(eratx.enc(eratx.Tag(tag, fields))))
        self.assertEqual(txid, eratx.blake2b(eratx.enc(body)).hex())
        self.assertEqual(witnesses, {})
        self.assertTrue(valid)
        self.assertNotIn(11, body)  # Auxiliary scripts introduce neither redeemers nor integrity hash.

    def test_existing_native_and_metadata_auxiliary_forms_remain_unchanged(self):
        for args, expected in (({"metadata": {1: 2}}, {1: 2}),
                               ({"metadata": {1: 2}, "aux_scripts": [[1, []]]}, [{1: 2}, [[1, []]]])):
            tx, _ = self.build(**args)
            self.assertEqual(ntcquery.decode_all(tx)[3], expected)

    def test_auxiliary_plutus_requires_supported_envelope_and_language(self):
        with self.assertRaises(ValueError):
            eratx.build_tx("mary", [], [], 1, [], aux_plutus={1: [b"script"]})
        with self.assertRaises(ValueError):
            self.build(aux_plutus={4: [b"script"]})


class AuxiliaryEvidenceTest(unittest.TestCase):
    normal = ("fund", 0)
    collateral = ("fund", 1)
    before = {normal: (eraauxiliary.FUND, {}), collateral: (eraauxiliary.COLLATERAL, {})}

    def errors(self, after, included, before=None):
        return eraauxiliary.effect_errors(self.before if before is None else before, after,
                                         self.normal, self.collateral, "new", included)

    def test_success_spends_only_normal_input_and_materializes_exact_payment(self):
        after = {self.collateral: self.before[self.collateral],
                 ("new", 0): (eraauxiliary.FUND - eraauxiliary.FEE, {})}
        self.assertEqual(self.errors(after, True), [])
        self.assertTrue(self.errors({k: v for k, v in after.items() if k != self.collateral}, True))
        self.assertTrue(self.errors({**after, self.normal: self.before[self.normal]}, True))
        self.assertTrue(self.errors({**after, ("new", 1): (1, {})}, True))

    def test_refusal_requires_normal_and_collateral_unchanged_and_no_outputs(self):
        self.assertEqual(self.errors(dict(self.before), False), [])
        for key in self.before:
            self.assertTrue(self.errors({k: v for k, v in self.before.items() if k != key}, False))
            self.assertTrue(self.errors({**self.before, key: (1, {})}, False))
        self.assertTrue(self.errors({**self.before, ("new", 0): (1, {})}, False))
        self.assertTrue(self.errors({}, False, before={}))

    def test_all_twenty_four_rows_are_required_and_unsuccessful_rows_do_not_count(self):
        rows = [{"era": era, "language": language, "case": case, "passed": True}
                for era, languages in eraauxiliary.LANGUAGES.items()
                for language in languages for case in eraauxiliary.CASES]
        self.assertEqual(len(rows), 24)
        self.assertEqual(eraauxiliary.coverage_failures(rows), [])
        for index in range(len(rows)):
            self.assertTrue(eraauxiliary.coverage_failures(rows[:index] + rows[index + 1:]))
            changed = rows[:index] + [{**rows[index], "passed": False}] + rows[index + 1:]
            self.assertTrue(eraauxiliary.coverage_failures(changed))

    def test_rejection_requires_both_matching_cli_refusal_and_actual_raw_reason(self):
        session = Mock()
        session.submit.return_value = b"actual raw refusal"
        context = Mock()
        context.__enter__ = Mock(return_value=session)
        context.__exit__ = Mock(return_value=False)
        load = SimpleNamespace(sh=Mock(return_value=SimpleNamespace(returncode=1, stdout="", stderr="InvalidMetadata")),
                               lsq_factory=Mock(return_value=context), ERA_INDEX={"conway": 6})
        passed, receipt = eraauxiliary.submit_reference(load, "conway", b"original transaction", "case", False)
        self.assertTrue(passed)
        self.assertEqual(receipt["rawRefusal"], b"actual raw refusal".hex())
        session.submit.assert_called_once_with(b"original transaction", 6)
        session.submit.return_value = None
        self.assertFalse(eraauxiliary.submit_reference(load, "conway", b"tx", "case", False)[0])
        session.submit.return_value = b"reason"
        load.sh.return_value.stderr = "BadInputsUTxO"
        self.assertFalse(eraauxiliary.submit_reference(load, "conway", b"tx", "case", False)[0])
        load.sh.return_value.returncode = 0
        load.sh.return_value.stderr = "InvalidMetadata"
        self.assertFalse(eraauxiliary.submit_reference(load, "conway", b"tx", "case", False)[0])


class AuxiliaryWorkflowTest(unittest.TestCase):
    def test_driver_builds_and_checks_all_twenty_four_signed_reference_cases(self):
        key = eratx.Key(json.dumps({"cborHex": "5820" + "00" * 32}))
        address = eratx.enterprise_address(key.hash)
        state = {}
        setup = "01" * 32
        records = []
        load = SimpleNamespace(wallets=[SimpleNamespace(pay=key, address=address)],
            protocol_params=lambda: {"protocolVersion": {"major": 9}},
            stop=SimpleNamespace(is_set=lambda: False), auxiliary_evidence=[],
            keep_tx=lambda file, row: records.append((file, row)), log=lambda row: None)
        def fund(era, label, wallet, specs, **kwargs):
            state.clear()
            for index, (_, coin, _, _) in enumerate(specs):
                state[(setup, index)] = (coin, {})
            return setup
        load.act = fund
        load.snapshot = lambda: (0, 6, {address: [(point, coin, assets) for point, (coin, assets) in state.items()]})
        def submit(actual_load, era, tx, label, included):
            self.assertIs(actual_load, load)
            body, witnesses, valid, auxiliary = ntcquery.decode_all(tx)
            self.assertEqual(len(witnesses[0]), 1)
            self.assertNotIn(11, body)
            self.assertEqual(set(witnesses), {0})
            self.assertEqual(auxiliary.tag, 259)
            [field] = auxiliary.value
            [script] = auxiliary.value[field]
            self.assertIn(field - 1, eraauxiliary.LANGUAGES[era])
            self.assertEqual(included, script != eraauxiliary.MALFORMED)
            self.assertEqual(valid, not label.endswith("malformed-invalid"))
            if included:
                [(txid, index)] = body[0]
                del state[(txid.hex(), index)]
                state[(eratx.blake2b(eratx.enc(body)).hex(), 0)] = (body[1][0][1], {})
            return True, {"returncode": 0 if included else 1, "rawRefusal": None if included else "01"}
        def observation(actual_load, era):
            self.assertIs(actual_load, load)
            return {"era": era, "eraIndex": 6, "slot": 1, "hash": "00",
                    "protocolVersion": {"major": 9, "minor": 0}}
        with patch.object(eraauxiliary, "submit_reference", side_effect=submit) as submissions, \
                patch.object(eraauxiliary, "reference_observation", side_effect=observation):
            for era in eraauxiliary.LANGUAGES:
                eraauxiliary.exercise(load, era)
        self.assertEqual(submissions.call_count, 24)
        self.assertEqual(eraauxiliary.coverage_failures(load.auxiliary_evidence), [])
        self.assertTrue(all(row["passed"] for row in load.auxiliary_evidence))
        self.assertTrue(all(row["referenceBefore"]["era"] == row["era"] and
                            row["referenceAfter"]["era"] == row["era"]
                            for row in load.auxiliary_evidence))
        self.assertEqual(len(records), 24)
        self.assertTrue(all(row["tx"] and row["script"] for _, row in records))
