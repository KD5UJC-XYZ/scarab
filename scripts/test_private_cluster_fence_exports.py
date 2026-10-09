# SPDX-License-Identifier: Apache-2.0
import json
from pathlib import Path
import tempfile
import unittest
from private_cluster_fence_exports import *

class Exports(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.source = self.root / 'packet'; self.source.mkdir()
        self.point = {'slot': 1042, 'hash': 'ab' * 32}
        self.request = {'point': self.point, 'networkMagic': 1082026}
        self.capture = {'kind': 'single-acquire-sustained-payloads', 'requestedPoint': self.point,
            'acquiredPoint': self.point, 'finalPoint': self.point, 'blockNo': 33, 'finalBlockNo': 33,
            'acquireCount': 1, 'reacquireCount': 0, 'release': 'sent-no-ack'}
        self.files = {'request.json': encoded(self.request), 'original-debug-epoch.cbor': b'epoch',
            'original-whole-utxo.cbor': b'\xa0', 'original-protocol.cbor': b'protocol',
            'original-parameters.cbor': b'params', 'derived-ledger.json': b'{"lastEpoch":1,"stake":0.12345678901234567890123456789}',
            'derived-utxo.json': b'{}', 'derived-parameters.json': b'{"native":true}',
            'derived-protocol.json': b'{"lastSlot":1042}'}
        for k,n in [('epochHex','original-debug-epoch.cbor'),('utxoHex','original-whole-utxo.cbor'),
                    ('protocolHex','original-protocol.cbor'),('parametersHex','original-parameters.cbor')]:
            self.capture[k] = self.files[n].hex()
        self.capture['projection'] = {'kind': 'derived-native-supported-state', 'nativeSemanticRoundTrips': True,
                                      'fullLedgerValidation': False, 'monetaryParity': False}
        for k,n in [('ledgerJsonHex','derived-ledger.json'),('utxoJsonHex','derived-utxo.json'),
                    ('protocolJsonHex','derived-protocol.json'),('parametersJsonHex','derived-parameters.json')]:
            self.capture['projection'][k] = self.files[n].hex()
        self.receipt = {'schema': 1, 'kind': 'single-acquire-supported-state-oracle', 'point': self.point,
            'blockNo': 33, 'acquireCount': 1, 'reacquireCount': 0, 'release': 'sent-no-ack',
            'runtimeImport': False, 'monetaryParity': False, 'rewardSeedAdmission': False, 'fullLedgerValidation': False}
        self.request['ntcVersion'] = 16; self.files['request.json'] = encoded(self.request)
        self.capture.update(ntcVersion=16, queryEncoding='GetCBOR-server-maxBound')
        self.receipt['admissionChecks'] = 'not-performed'
        self.write_packet()
        self.genesis = self.root / 'genesis'; self.genesis.mkdir()
        (self.genesis / 'shelley-genesis.json').write_bytes(encoded({'networkId': 'Testnet',
            'networkMagic': 1082026, 'epochLength': 1000}))
        self.seed = self.root / 'seed'; self.seed.write_bytes(encoded({'record':'transfer-range-block'}) * 2)

    def write_packet(self):
        self.files['capture.json'] = encoded(self.capture)
        self.receipt['fileSHA256'] = {n:sha(raw) for n,raw in self.files.items()}
        for name,raw in self.files.items(): (self.source/name).write_bytes(raw)
        (self.source/'receipt.json').write_bytes(encoded(self.receipt))

    def context(self, target='context'):
        return prepare_context(self.source, self.root/target, genesis_directory=self.genesis,
            seed_capture=self.seed, expected_point=self.point, expected_block_no=33)

    def test_exact_bytes_and_context_recipe(self):
        result = self.context()
        destination = self.root/'context'
        self.assertEqual((destination/'pre-ledger-state.md').read_bytes(),self.files['derived-ledger.json'])
        self.assertEqual((destination/'pre-utxo-cbor.md').read_bytes(),b'a0\n')
        pins = dict(line.split('\t') for line in (destination/'coherent-sequence-context.md').read_text().splitlines())
        recipe = pins.pop('format')+'\n'+''.join(k+'='+pins[k]+'\n' for k in sorted(pins))
        self.assertEqual(result['contextId'], sha(recipe.encode()))
        provenance=parse((destination/'native-source-attribution.json').read_bytes())
        self.assertFalse(provenance['originalCLIOutput'])
        self.assertIn('first-and-final',provenance['tipSource'])

    def test_changed_point_and_block_rejected(self):
        for key,value in [('finalPoint',dict(self.point,slot=1043)),('finalBlockNo',34),('acquireCount',2)]:
            original=self.capture[key];self.capture[key]=value;self.write_packet()
            with self.assertRaises(ValueError): self.context()
            self.capture[key]=original

    def test_byte_binding_not_only_receipt_hash(self):
        self.files['derived-ledger.json']=b'{"lastEpoch":1}'
        self.write_packet()
        with self.assertRaisesRegex(ValueError,'derived byte'):self.context()

    def test_tamper_symlink_duplicate_missing(self):
        file=self.source/'derived-utxo.json';file.write_bytes(b'{"tamper":1}')
        with self.assertRaisesRegex(ValueError,'digest'):self.context()
        self.write_packet();file.unlink();file.symlink_to(self.seed)
        with self.assertRaises(OSError):self.context()
        with self.assertRaises(ValueError):parse(b'{"x":1,"x":2}')
        with self.assertRaises(ValueError):parse(b'{"x":NaN}')

    def test_wrong_genesis_and_seed_bound(self):
        (self.genesis/'shelley-genesis.json').write_bytes(encoded({'networkId':'Mainnet','networkMagic':1082026,'epochLength':1000}))
        with self.assertRaises(ValueError):self.context()
        (self.genesis/'shelley-genesis.json').write_bytes(encoded({'networkId':'Testnet','networkMagic':1082026,'epochLength':1000}))
        self.seed.write_bytes(encoded({'record':'transfer-range-block'}))
        with self.assertRaises(ValueError):self.context()

    def test_oracle_retains_capture_and_pair(self):
        tx=tuple(self.root/f'tx{i}' for i in range(2))
        for i,p in enumerate(tx):p.write_text('80' if i==0 else '81')
        out=prepare_oracle(self.source,self.root/'oracle',expected_point=self.point,expected_block_no=33,
            capture_path=self.seed,transaction_paths=tx)
        self.assertTrue(out.is_file())
        self.assertEqual((out.parent/'scala-sequence-capture.md').read_bytes(),self.seed.read_bytes())
        self.assertEqual((out.parent/'post-parameters.md').read_bytes(),self.files['derived-parameters.json'])
        with self.assertRaises(FileExistsError):
            prepare_oracle(self.source,self.root/'oracle',expected_point=self.point,expected_block_no=33,
                capture_path=self.seed,transaction_paths=tx)

    def test_no_overwrite_or_unsupported_claims(self):
        self.context()
        with self.assertRaises(FileExistsError):self.context()
        self.receipt['runtimeImport']=True;self.write_packet()
        with self.assertRaises(ValueError):self.context('second')

if __name__=='__main__':unittest.main()
