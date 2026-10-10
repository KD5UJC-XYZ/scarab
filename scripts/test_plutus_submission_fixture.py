#!/usr/bin/env python3
import copy
from pathlib import Path
from types import SimpleNamespace
import unittest

import plutus_submission_fixture as f


class FixtureTest(unittest.TestCase):
    def setUp(self):
        self.source = 'addr_test1' + 'a' * 40
        self.script = 'addr_test1' + 'b' * 40
        self.beneficiary = 'addr_test1' + 'c' * 40
        self.txid = '44' * 32
        self.source_id = '11' * 32 + '#0'
        self.datum = dict(constructor=0, fields=[dict(bytes='aa' * 28), dict(int=5_000_000)])
        self.before = f.Snapshot(dict(slot=10, blockNo=1, hash='11' * 32),
                                 {self.source_id: self.row(self.source, 100_000_000)}, 0)
        self.plan = f.funding_plan(self.before.utxo, self.source, self.script,
                                   self.beneficiary, self.beneficiary, self.datum)
        self.after = f.Snapshot(dict(slot=20, blockNo=2, hash='22' * 32), {
            self.txid + '#0': self.row(self.script, f.AMOUNT, self.datum),
            self.txid + '#1': self.row(self.beneficiary, f.COLLATERAL),
            self.txid + '#2': self.row(self.source, self.plan['change'])}, f.FUNDING_FEE)
        self.raw = b'fixture-original'
        self.identity = dict(transactionId=self.txid, envelopeSHA256=f.digest(self.raw), bytes=len(self.raw),
                             bodySHA256='ab' * 32, witnessesSHA256='cd' * 32)

    @staticmethod
    def row(address, amount, datum=None):
        return dict(address=address, value=dict(lovelace=amount), inlineDatum=datum)

    def compare(self, after=None):
        return f.funding_comparison(self.before, after or self.after, self.plan, self.txid, self.raw, self.identity)

    def test_whole_funding_and_fee(self):
        self.assertTrue(self.compare()['completeUtxoChecked'])
        self.assertEqual(self.compare()['collateralInput'], self.txid + '#1')

    def test_missing_collateral_rejected(self):
        changed = copy.deepcopy(self.after)
        del changed.utxo[self.txid + '#1']
        with self.assertRaises(ValueError): self.compare(changed)

    def test_wrong_datum_beneficiary_rejected(self):
        changed = copy.deepcopy(self.after)
        changed.utxo[self.txid + '#0']['inlineDatum']['fields'][0]['bytes'] = 'bb' * 28
        with self.assertRaises(ValueError): self.compare(changed)

    def test_wrong_collateral_coin_rejected(self):
        changed = copy.deepcopy(self.after)
        changed.utxo[self.txid + '#1']['value']['lovelace'] -= 1
        with self.assertRaises(ValueError): self.compare(changed)

    def test_wrong_fee_pot_rejected(self):
        with self.assertRaises(ValueError):
            self.compare(f.Snapshot(self.after.full_point, self.after.utxo, f.FUNDING_FEE + 1))

    def test_extra_utxo_rejected(self):
        changed = copy.deepcopy(self.after)
        changed.utxo['55' * 32 + '#0'] = self.row(self.source, 2_000_000)
        with self.assertRaises(ValueError): self.compare(changed)

    def test_reference_script_rejected(self):
        row = self.row(self.script, f.AMOUNT, self.datum)
        row['referenceScript'] = {'script': 'x'}
        with self.assertRaises(ValueError): f.output(row)

    def test_bool_coin_rejected(self):
        with self.assertRaises(ValueError): f.output(self.row(self.source, True))

    def test_spend_plan_only_fixed_outputs_and_budget(self):
        argv = f.spend_command(self.plan, self.txid, self.after.full_point)
        self.assertEqual(argv.count('--tx-out'), 1)
        self.assertEqual(argv[argv.index('--tx-out') + 1], self.beneficiary + '+19700000')
        self.assertEqual(argv[argv.index('--tx-in-collateral') + 1], self.txid + '#1')
        self.assertEqual(argv[argv.index('--tx-in-execution-units') + 1], '(30000000,100000)')
        for forbidden in ('submit', '--tx-total-collateral', '--tx-out-return-collateral', '--required-signer'):
            self.assertNotIn(forbidden, argv)

    def test_commands_never_submit_tested_spend(self):
        commands = f.commands(self.plan, 42, f.ROOT + '/keys/utxo.skey')
        self.assertTrue(all('submit' not in argv for argv in commands.values()))
        self.assertEqual(commands['fundingBuild'].count('--tx-out'), 3)
        self.assertIn(f.ROOT + '/keys/beneficiary.skey', commands['spendSign'])

    def gate(self, code=0):
        self.calls = []
        def execute(*argv):
            self.calls.append(argv)
            return SimpleNamespace(returncode=code)
        return f.ReferenceSubmissionGate(execute, 42, f.ROOT + '/socket/node1/sock')

    def test_single_funding_then_seal(self):
        gate = self.gate()
        gate.submit_funding(lambda _: self.raw, f.digest(self.raw))
        gate.seal_bootstrap(self.compare())
        self.assertEqual(self.calls[0][self.calls[0].index('--tx-file') + 1], f.FUNDING)
        with self.assertRaises(ValueError): gate.submit_funding(lambda _: self.raw, f.digest(self.raw))
        with self.assertRaises(ValueError): gate.execute('cardano-cli', 'conway', 'transaction', 'submit', '--tx-file', f.SPEND)

    def test_uncertain_funding_never_retries(self):
        gate = self.gate(1)
        with self.assertRaises(ValueError): gate.submit_funding(lambda _: self.raw, f.digest(self.raw))
        with self.assertRaises(ValueError): gate.submit_funding(lambda _: self.raw, f.digest(self.raw))
        self.assertEqual(len(self.calls), 1)

    def test_changed_original_prevents_submission(self):
        gate = self.gate()
        with self.assertRaises(ValueError): gate.submit_funding(lambda _: b'changed', f.digest(self.raw))
        self.assertEqual(self.calls, [])

    def test_registered_script_hash_and_outer_wrapper(self):
        candidates = [Path(__file__).resolve().parents[1] / 'vm/src/test/resources/plutus-pv9-reference/script.cbor',
                      Path('<repo>/vm/src/test/resources/plutus-pv9-reference/script.cbor')]
        payload = next(p for p in candidates if p.is_file()).read_bytes()
        value = f.script_fixture('aa' * 28, payload)
        self.assertEqual(bytes.fromhex(value['json']['cborHex']), bytes.fromhex('590171') + payload)
        self.assertEqual(set(value['json']), {'type', 'description', 'cborHex'})
        self.assertEqual(value['json']['type'], 'PlutusScriptV3')
        self.assertEqual(value['json']['description'], 'Bounded isolated PV9 test script')
        self.assertEqual(value['originalSHA256'], f.SCRIPT_SHA)
        self.assertEqual(value['enterpriseAddressHex'], '70' + value['scriptHash'])
        with self.assertRaises(ValueError): f.script_fixture('aa' * 28, payload[3:])


if __name__ == '__main__': unittest.main()
