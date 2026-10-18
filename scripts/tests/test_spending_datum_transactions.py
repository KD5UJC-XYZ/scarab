# SPDX-License-Identifier: Apache-2.0
"""The reference corpus must preserve phase1 refusals and prove real, stable inclusion."""
import importlib.util
import hashlib
import json
from pathlib import Path
from types import SimpleNamespace
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location(
    "context_transactions", Path(__file__).resolve().parents[1] / "oracle/plutus/context_transactions.py")
context = importlib.util.module_from_spec(spec)
spec.loader.exec_module(context)


class SpendingDatumTransactionsTest(unittest.TestCase):
    def test_missing_witness_and_legacy_no_datum_are_phase1_for_both_flags(self):
        for language in (1, 2, 3):
            for valid in (True, False):
                self.assertEqual(context.expected_failure(language, context.DatumMode.MISSING, valid),
                                 "MissingRequiredDatums")
                if language != 3:
                    self.assertEqual(context.expected_failure(language, context.DatumMode.NONE, valid),
                                     "UnspendableUTxONoDatumHash")

    def test_v3_no_datum_and_supplied_datum_controls_keep_validity_tag_semantics(self):
        for mode, languages in ((context.DatumMode.NONE, (3,)), (context.DatumMode.PRESENT, (1, 2, 3))):
            for language in languages:
                self.assertIsNone(context.expected_failure(language, mode, True))
                self.assertEqual(context.expected_failure(language, mode, False), "ValidationTagMismatch")

    def confirm(self, observations):
        clock = SimpleNamespace(seconds=0)
        driver = SimpleNamespace(name="private", whole_utxo=lambda: observations[min(clock.seconds, len(observations) - 1)])

        def pause(seconds):
            clock.seconds += seconds

        def tip(*args, **kwargs):
            return {"block": clock.seconds + 2, "hash": f"block{clock.seconds + 2}"}

        with patch.object(context.time, "monotonic", lambda: clock.seconds), \
                patch.object(context.time, "sleep", pause), patch.object(context, "query", tip):
            return context.confirmed_case(driver, "original", 1, timeout=10)

    def test_absent_output_never_becomes_inclusion_even_when_tips_match_and_advance(self):
        with self.assertRaisesRegex(RuntimeError, "exact output lacked shared"):
            self.confirm([{}])

    def test_orphaned_output_resets_confirmation_window_without_resubmitting(self):
        proof = self.confirm([{("original", "0"): {}}, {}, {("original", "0"): {}}])
        self.assertEqual((proof["firstSharedBlock"], proof["confirmedBlock"]), (4, 6))
        self.assertEqual(proof["txId"], "original")

    def test_retained_actual_corpus_binds_original_bytes_raw_refusals_and_confirmation(self):
        directory = Path(__file__).resolve().parents[2] / "fixtures/reference/spending-datum"
        manifest = json.loads((directory / "manifest.json").read_text())
        raw = (directory / "conway-pv10.json").read_bytes()
        self.assertEqual(hashlib.sha256(raw).hexdigest(), manifest["files"]["conway-pv10.json"])
        cases = json.loads(raw)
        self.assertEqual((len(cases), sum(case["reference"]["accepted"] for case in cases)), (18, 4))
        for case in cases:
            recorded, datum = case["reference"], case["datumCase"]
            body, witnesses = bytes.fromhex(case["body"]), bytes.fromhex(case["witnesses"])
            flag = b"\xf5" if case["isValid"] else b"\xf4"
            self.assertEqual(bytes.fromhex(datum["transaction"]), b"\x84" + body + witnesses + flag + b"\xf6")
            self.assertEqual(hashlib.blake2b(body, digest_size=32).hexdigest(), case["txId"])
            self.assertEqual(datum["spentAfter"], {"funding": recorded["accepted"],
                                                  "script": recorded["accepted"], "collateral": False})
            if recorded["accepted"]:
                self.assertTrue(case["isValid"])
                self.assertIsNone(recorded["reasonCbor"])
                proof = datum["inclusion"]
                self.assertEqual(proof["txId"], case["txId"])
                self.assertGreaterEqual(proof["confirmedBlock"] - proof["firstSharedBlock"], proof["securityParam"] + 1)
                self.assertEqual({row["node"] for row in datum["broadcast"]}, {1, 2, 3})
            else:
                self.assertIsNone(datum["inclusion"])
                reason = bytes.fromhex(recorded["reasonCbor"])
                _, end = context.ntcquery.decode(reason)
                self.assertEqual(end, len(reason))
