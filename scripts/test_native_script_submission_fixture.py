#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Offline fixture tests. All executors are fakes; no keys, subprocesses or networking."""
import copy
import unittest
from types import SimpleNamespace
import native_script_submission_fixture as f


class FixtureTests(unittest.TestCase):
    def setUp(self):
        self.source = "addr_test1" + "a" * 40
        self.script = "addr_test1" + "b" * 40
        self.destination = "addr_test1" + "c" * 40
        self.input = "11" * 32 + "#0"
        self.other = "22" * 32 + "#1"
        self.txid = "33" * 32
        self.before = {self.input: self.out(self.source, 50000000), self.other: self.out(self.destination, 7000000)}
        self.plan = f.funding_plan(self.before, self.source, self.script, self.destination)
        self.after = {self.other: self.before[self.other], self.txid + "#0": self.out(self.script, 20000000),
                      self.txid + "#1": self.out(self.source, 29800000)}
        self.p0 = dict(slot=10, blockNo=2, hash="44" * 32)
        self.p1 = dict(slot=20, blockNo=3, hash="55" * 32)
        self.raw = bytes.fromhex("84a0a0f5f6")
        self.identity = dict(transactionId=self.txid, envelopeSHA256=f.digest(self.raw), bytes=len(self.raw),
                             bodySHA256="66" * 32, witnessesSHA256="77" * 32)

    @staticmethod
    def out(address, amount):
        return dict(address=address, value=dict(lovelace=amount), datum=None, referenceScript=None)

    def compare(self, before=None, after=None, fee=200000, identity=None, point=None):
        return f.funding_comparison(f.Snapshot(self.p0, before or self.before, 0),
            f.Snapshot(point or self.p1, after or self.after, fee), self.plan, self.txid, self.raw,
            self.identity if identity is None else identity)

    def test_signature_script_literal_hash_and_enterprise_kind(self):
        value = f.script_fixture("22" * 28)
        self.assertEqual(value["originalCborHex"], "8200581c" + "22" * 28)
        self.assertEqual(value["scriptHash"], "6d88a61d44faea8672dd738057445517c8cb5fbcece60610df803b85")
        self.assertEqual(value["enterpriseAddressHex"], "70" + value["scriptHash"])
        self.assertEqual(f.checked_script_address(value, dict(address=self.script, base16=value["enterpriseAddressHex"])), self.script)
        for bad in ("71" + value["scriptHash"], "70" + "00" * 28):
            with self.assertRaises(ValueError):
                f.checked_script_address(value, dict(address=self.script, base16=bad))

    def test_funding_comparison_preserves_complete_map_fees_point_and_original_identity(self):
        receipt = self.compare()
        self.assertTrue(receipt["passed"])
        self.assertTrue(receipt["completeUtxoChecked"])
        self.assertFalse(receipt["scalaFundingValidated"])
        self.assertFalse(receipt["fullLedgerValidated"])
        self.assertEqual(receipt["scope"], "reference-only-fixture-funding")
        self.assertEqual(receipt["afterPoint"], self.p1)

    def test_funding_must_not_modify_or_omit_unrelated_rows(self):
        for mutation in (lambda d: d.pop(self.other), lambda d: d[self.other]["value"].update(lovelace=1),
                         lambda d: d.update({"99" * 32 + "#0": self.out(self.source, 1)})):
            changed = copy.deepcopy(self.after); mutation(changed)
            with self.assertRaises(ValueError): self.compare(after=changed)

    def test_funding_script_value_address_and_fee_delta_checked(self):
        for changed in (self.out(self.script, 20000001), self.out(self.destination, 20000000)):
            rows = dict(self.after, **{self.txid + "#0": changed})
            with self.assertRaises(ValueError): self.compare(after=rows)
        with self.assertRaises(ValueError): self.compare(fee=200001)
        with self.assertRaises(ValueError): self.compare(point=self.p0)

    def test_funding_original_identity_mismatch_rejected(self):
        for key, value in (("transactionId", "99" * 32), ("envelopeSHA256", "99" * 32), ("bytes", 1), ("bytes", True), ("bodySHA256", None)):
            with self.assertRaises(ValueError): self.compare(identity=dict(self.identity, **{key:value}))

    def test_closed_scalar_ada_snapshot_profile(self):
        for row in (dict(self.out(self.source, 1), unknown=True), dict(self.out(self.source, 1), datum="00"),
                    dict(self.out(self.source, 1), referenceScript={}), dict(address=self.source, value=dict(lovelace=True)),
                    dict(address=self.source, value=dict(lovelace=1, asset=2))):
            with self.assertRaises(ValueError): f.whole_utxo({self.input: row})
        for key in ("11" * 32 + "#65536", "11" * 32 + "#01", "input injection"):
            with self.assertRaises(ValueError): f.whole_utxo({key:self.out(self.source, 1)})

    def test_pinned_cli_null_inline_datum_raw_is_absent_but_nonnull_is_unsupported(self):
        row = dict(self.out(self.source, 1), inlineDatumRaw=None)
        self.assertEqual(f.output(row), (self.source, 1))
        for value in (False, 0, "", "00", {}, [], {"constructor": 0}):
            with self.subTest(value=value):
                with self.assertRaises(ValueError):
                    f.output(dict(row, inlineDatumRaw=value))
        with self.assertRaises(ValueError):
            f.output(dict(row, unrecognizedNullField=None))

    def test_plan_rejects_prefunded_script_ambiguous_source_and_small_change(self):
        for changes in ({"99" * 32 + "#0": self.out(self.script, 1)},
                        {"99" * 32 + "#0": self.out(self.source, 1)},
                        {self.input:self.out(self.source, 20200000)}):
            with self.assertRaises(ValueError): f.funding_plan(dict(self.before, **changes), self.source, self.script, self.destination)

    def test_build_sign_commands_never_submit_acceptance_transaction(self):
        plan = f.commands(self.plan, 1082026, "/work/env/utxo-keys/utxo1/utxo.skey")
        self.assertFalse(any("submit" in argv for argv in plan.values()))
        spend = f.spend_command(self.plan, self.txid, self.p1)
        self.assertIn(self.txid + "#0", spend)
        self.assertIn("--tx-in-script-file", spend)
        self.assertNotIn(self.script + "+20000000", spend)
        self.assertIn(self.destination + "+10000000", spend)
        self.assertIn(self.source + "+9800000", spend)
        self.assertEqual(spend[-1], f.ROOT + "/spend.body")

    def test_command_paths_and_geometry_are_bounded(self):
        for key in ("/home/private/key.skey", "/work/../key.skey", "/work/keys/new key.skey"):
            with self.assertRaises(ValueError): f.commands(self.plan, 1082026, key)
        for slot in (0, 300, True):
            with self.assertRaises(ValueError): f.spend_command(self.plan, self.txid, dict(self.p1, slot=slot))

    def test_only_exact_single_funding_submit_can_reach_executor(self):
        calls = []
        def execute(*args):
            calls.append(args)
            return SimpleNamespace(returncode=0)
        gate = f.ReferenceSubmissionGate(execute, 1082026, "/work/env/socket/node1/sock")
        for path in (f.FUNDING, f.SPEND, "/work/other.signed"):
            with self.assertRaises(ValueError): gate.execute("cardano-cli", "conway", "transaction", "submit", "--tx-file", path)
        self.assertFalse(calls)
        gate.submit_funding(lambda path: self.raw if path == f.FUNDING else None, f.digest(self.raw))
        self.assertEqual(len(calls), 1)
        self.assertEqual(calls[0][5], f.FUNDING)
        with self.assertRaises(ValueError): gate.seal_bootstrap(dict(self.compare(), originalSHA256="00" * 32))
        gate.seal_bootstrap(self.compare())
        with self.assertRaises(ValueError): gate.submit_funding(lambda _:self.raw, f.digest(self.raw))
        with self.assertRaises(ValueError): gate.execute("bash", "-c", "cardano-cli submit")

    def test_uncertain_submit_is_not_retried_and_changed_original_never_submitted(self):
        def failed(*args): raise TimeoutError("lost native reply")
        gate = f.ReferenceSubmissionGate(failed, 1082026, "/work/env/socket/node1/sock")
        with self.assertRaises(ValueError): gate.submit_funding(lambda _:self.raw, "00" * 32)
        with self.assertRaises(TimeoutError): gate.submit_funding(lambda _:self.raw, f.digest(self.raw))
        with self.assertRaises(ValueError): gate.seal_bootstrap(self.compare())
        with self.assertRaises(ValueError): gate.submit_funding(lambda _:self.raw, f.digest(self.raw))

    def test_missing_failed_or_wrongly_typed_submit_status_consumes_attempt_without_sealing(self):
        results = [None, object(), SimpleNamespace(), {"returncode": 0}]
        results += [SimpleNamespace(returncode=status) for status in
                    (None, True, False, 0.0, "0", b"0", [], {}, 1, -1)]
        for result in results:
            with self.subTest(result=repr(result)):
                calls = []
                def execute(*args):
                    calls.append(args)
                    return result
                gate = f.ReferenceSubmissionGate(execute, 1082026, "/work/env/socket/node1/sock")
                with self.assertRaisesRegex(ValueError, "explicit integer-zero"):
                    gate.submit_funding(lambda _: self.raw, f.digest(self.raw))
                with self.assertRaisesRegex(ValueError, "submission unavailable"):
                    gate.submit_funding(lambda _: self.raw, f.digest(self.raw))
                with self.assertRaisesRegex(ValueError, "successful funding submission"):
                    gate.seal_bootstrap(self.compare())
                self.assertEqual(len(calls), 1)

    def test_negative_oracle_requires_same_body_state_slot_and_typed_rejection(self):
        control = dict(transactionId=self.txid, originalSHA256="11" * 32, bodySHA256="22" * 32,
                       outcome="ScopedDerived", fullLedgerValidated=False, preStateSHA256="33" * 32, validationSlot=40)
        candidate = dict(control, originalSHA256="44" * 32, outcome="Rejected", predicate="MissingScripts")
        reference = dict(schema="native-script-oracle-v1", ledgerRejected=True, originalSHA256="44" * 32,
                         predicate="MissingScripts", stateUnchanged=True, evaluationSlotEstablished=True,
                         preStateSHA256="33" * 32, validationSlot=40)
        self.assertTrue(f.negative_reference_comparison("missing-script", candidate, control, reference)["passed"])
        for key, value in (("outcome", "Unsupported"), ("outcome", "ResourceLimit"), ("bodySHA256", "99" * 32), ("validationSlot", 41)):
            with self.assertRaises(ValueError): f.negative_reference_comparison("missing-script", dict(candidate, **{key:value}), control, reference)
        for key, value in (("evaluationSlotEstablished", False), ("ledgerRejected", False), ("originalSHA256", "99" * 32), ("preStateSHA256", "99" * 32), ("validationSlot", True)):
            with self.assertRaises(ValueError): f.negative_reference_comparison("missing-script", candidate, control, dict(reference, **{key:value}))


if __name__ == "__main__":
    unittest.main()
