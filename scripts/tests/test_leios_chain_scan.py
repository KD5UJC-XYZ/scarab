# SPDX-License-Identifier: Apache-2.0
"""Guard the Leios testnet chain reader: its CBOR walk, its reading of the recorded testnet blocks, the
stratified sample, and the reading of the context oracle's `PlutusWithContext` encodings."""
import importlib.util
import json
from pathlib import Path
import tempfile
import types
import unittest

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location(
    'leios_chain_scan', ROOT / 'scripts/oracle/leios-testnet/leios_chain_scan.py')
SCAN = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SCAN)


def recorded_blocks():
    """The blocks of `fixtures/leios/testnet/blocks-rc-2026w44.tsv` by label."""
    out = {}
    for line in (ROOT / 'fixtures/leios/testnet/blocks-rc-2026w44.tsv').read_text().splitlines():
        if line.startswith('#'):
            continue
        label, _tag, number, slot, digest, raw = line.split('\t')
        out[label] = (int(number), int(slot), digest, bytes.fromhex(raw))
    return out


class LeiosChainScanTests(unittest.TestCase):
    def test_skip_reads_every_form_of_item_to_its_end(self):
        for item in ('00', '8301820203a0', '9f01829f02ff03ff', 'bf0102ff', 'd9010283010203',
                     '5f4101420203ff', '7f6161ff', '80', 'a0', '9fff', '8180', 'f5', 'f93c00',
                     'fb3ff0000000000000', 'c249010000000000000000', 'a201a10203829f80ff0405'):
            raw = bytes.fromhex(item)
            self.assertEqual(SCAN.skip(raw + b'\x00', 0), len(raw), item)

    def test_skip_does_not_recurse_on_deep_nesting(self):
        raw = b'\x81' * 100_000 + b'\x00'
        self.assertEqual(SCAN.skip(raw, 0), len(raw))

    def test_the_recorded_blocks_read_as_the_reference_reads_them(self):
        blocks = recorded_blocks()
        for label, (number, slot, digest, raw) in blocks.items():
            block = SCAN.Block(raw)
            self.assertEqual((block.number, block.slot, block.hash.hex()), (number, slot, digest), label)
        certifying = SCAN.Block(blocks['certifying'][3])
        self.assertTrue(certifying.has_cert and certifying.certifies and not certifying.txs)
        announcing = SCAN.Block(blocks['announcing-with-txs'][3])
        self.assertIsNotNone(announcing.announces)
        self.assertEqual(len(announcing.txs), 40)

    def test_the_recorded_transactions_have_the_reference_ids_and_no_redeemer(self):
        expected = {}
        for line in (ROOT / 'fixtures/reference/leios-testnet-blocks/cases.jsonl').read_text().splitlines():
            case = json.loads(line)
            if case['kind'] == 'tx' and case['source'].endswith('announcing-with-txs'):
                expected[case['txid']] = case['valid']
        txs = [SCAN.Tx(raw) for raw in SCAN.Block(recorded_blocks()['announcing-with-txs'][3]).txs]
        self.assertEqual({tx.id.hex(): tx.valid for tx in txs}, expected)
        self.assertEqual([tx.redeemers() for tx in txs], [[]] * 40)
        self.assertTrue(all(tx.outputs() for tx in txs))

    def test_the_sample_takes_every_stratum_and_the_cheap_new_scripts_first(self):
        def row(n, source, purpose, scripts, size=600, redeemers=1, valid=True):
            return {'id': f'{n:064x}', 'source': source, 'valid': valid, 'subtxs': 0, 'interval': [False, False],
                    'size': size, 'scripts': [{'language': 'PlutusV3', 'hash': h, 'from': 'witness'} for h in scripts],
                    'redeemers': [{'level': 'top', 'purpose': purpose, 'index': 0, 'units': [1, 1]}] * redeemers}
        rows = [row(0, 'rb', 'spending', ['a']), row(1, 'rb', 'spending', ['a']), row(2, 'rb', 'spending', ['b']),
                row(3, 'eb', 'spending', ['a']), row(4, 'rb', 'minting', ['c'], size=9000, redeemers=9),
                row(5, 'rb', 'minting', ['d'], size=9000, redeemers=9), row(6, 'rb', 'spending', ['e'], valid=False)]
        with tempfile.TemporaryDirectory() as tmp:
            Path(tmp, 'plutus.jsonl').write_text(''.join(json.dumps(r) + '\n' for r in rows))
            SCAN.sample(types.SimpleNamespace(scan=tmp, out=tmp, size=5))
            sampled = [json.loads(line)['id'] for line in Path(tmp, 'sample.jsonl').read_text().splitlines()]
        # every stratum once; then the spending stratum's new script (2) before its repeat (1); the second
        # minting transaction is not ordinary (nine redeemers), so it waits however new its script is
        self.assertEqual([int(i, 16) for i in sampled], [0, 2, 3, 4, 6])

    def test_a_plutus_with_context_of_the_reference_reads_as_its_fields(self):
        results = json.loads((ROOT / 'fixtures/reference/leios-testnet-contexts/results.json').read_text())
        everything = next(r for r in results if r['name'] == 'everything')['result']
        fields = [SCAN.plutus_with_context(bytes.fromhex(p)) for p in everything['top']['contexts']]
        self.assertTrue(fields)
        for f in fields:
            self.assertEqual((f['protocol'], f['language'], len(f['arguments'])), (12, 4, 1))
            self.assertEqual(f['costModel'], [0] * 251)
            self.assertEqual(bytes.fromhex(f['script'])[0], 0x4A)
            self.assertGreater(f['memory'], 0)

    def test_the_purpose_of_a_reference_context_is_its_script_info(self):
        results = json.loads((ROOT / 'fixtures/reference/leios-testnet-contexts/results.json').read_text())
        purposes = {}
        for case in results:
            result = case['result']
            if result.get('accepted') and not result.get('failure'):
                levels = [result['top']] + result['subtxs']
                purposes[case['name']] = [
                    [SCAN.purpose_of(f['language'], f['arguments'])
                     for f in (SCAN.plutus_with_context(bytes.fromhex(p)) for p in level.get('contexts', []))]
                    for level in levels]
        # the reference lists a transaction's scripts in the reverse of the order they are needed in
        self.assertEqual(purposes['everything'],
                         [['proposing', 'voting', 'minting', 'certifying', 'rewarding', 'spending']])
        self.assertEqual(purposes['guarded'], [['guarding'], ['minting'], []])
        self.assertEqual(purposes['sub-transaction scripts'], [[], ['guarding', 'spending', 'spending']])

    def test_txin_bytes_writes_a_transaction_input(self):
        tx = bytes(range(32))
        self.assertEqual(SCAN.txin_bytes((tx, 0)), b'\x82\x58\x20' + tx + b'\x00')
        self.assertEqual(SCAN.txin_bytes((tx, 24)), b'\x82\x58\x20' + tx + b'\x18\x18')
        self.assertEqual(SCAN.txin_bytes((tx, 300)), b'\x82\x58\x20' + tx + b'\x19\x01\x2c')


if __name__ == '__main__':
    unittest.main()
