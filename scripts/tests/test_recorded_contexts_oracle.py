# SPDX-License-Identifier: Apache-2.0
"""Guard the recorded-contexts inputs: every recorded case, its clock, and the several-voter probes."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
HERE = ROOT / 'scripts/oracle/plutus/recorded-contexts'
SPEC = importlib.util.spec_from_file_location('recorded_contexts_run', HERE / 'run.py')
RUN = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RUN)
PREPARE_SPEC = importlib.util.spec_from_file_location('recorded_contexts_prepare_test', HERE / 'prepare.py')
PREPARE = importlib.util.module_from_spec(PREPARE_SPEC)
PREPARE_SPEC.loader.exec_module(PREPARE)


class RecordedContextsOracleTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.inputs, cls.rows = RUN.validate()
        cls.by_name = {row['name']: row for row in cls.rows}

    def test_every_recorded_preview_transaction_has_a_reference_answer(self):
        files = sorted(path.stem for path in (ROOT / 'fixtures/preview/plutus').glob('*.json'))
        named = sorted(Path(row['provenance']['sourcePath']).stem for row in self.rows
                       if row['name'].startswith('preview/'))
        self.assertEqual(named, files)

    def test_every_recorded_preprod_transaction_has_a_reference_answer(self):
        files = sorted(str(path.relative_to(ROOT)) for path in (ROOT / 'fixtures/preprod/plutus').glob('*.json'))
        named = [row['provenance']['sourcePath'] for row in self.rows if row['name'].startswith('preprod/')]
        self.assertEqual(named, files)

    def test_only_the_large_transaction_is_kept_as_digests(self):
        for row in self.rows:
            for script in row['oracle'].get('scripts', []):
                digested = row['name'] in RUN.DIGESTED
                self.assertEqual('request' in script, digested, row['name'])
                self.assertEqual('contextCbor' in script, not digested, row['name'])
        large = self.by_name['preprod/preprod-c70243bb']['oracle']['scripts']
        self.assertEqual(len(large), 158)
        self.assertTrue(all(len(script['request']['argumentsSha256']) == 3 for script in large))

    def test_digests_of_a_whole_request_name_each_argument(self):
        script = self.by_name['preview/slot1140200-450a242a']['oracle']['scripts'][0]
        request = RUN.request_digests(script['contextCbor'])
        self.assertEqual((request['protocolMajor'], request['language']), (7, 1))
        self.assertEqual(len(request['argumentsSha256']), 3)

    def test_recorded_inputs_are_not_copied_but_bound(self):
        for row in self.rows:
            if row['provenance']['kind'] == 'recorded-chain-transaction':
                self.assertNotIn('transaction', row)
                self.assertEqual(len(row['inputSha256']), 64)

    def test_a_clock_sample_off_the_network_line_is_refused(self):
        source = json.loads((ROOT / 'fixtures/preview/plutus/slot1178938-3cfa1a9c.json').read_text())
        slot = next(iter(source['clock']))
        source['clock'][slot] = str(int(source['clock'][slot]) + 1000)
        with tempfile.TemporaryDirectory(prefix='scarab-recorded-contexts-test-') as scratch:
            (Path(scratch) / 'case.json').write_text(json.dumps(source))
            with patch.dict(PREPARE.NETWORKS, {'preview': (Path(scratch), 1666656000)}):
                with patch.object(PREPARE, 'ROOT', Path(scratch)):
                    with self.assertRaisesRegex(ValueError, 'off the preview clock'):
                        PREPARE.recorded('preview')

    def test_voters_are_written_in_the_reverse_of_ord_voter_and_claim_no_admission(self):
        row = next(row for row in self.inputs if row['name'] == 'voters/ord-indices')
        body, _, _, _ = PREPARE.decode_all(bytes.fromhex(row['transaction']))
        self.assertEqual([voter[0] for voter in body[19]], [4, 2, 3, 0, 1])
        for name in ('voters/ord-indices', 'voters/transaction-order-indices',
                     'voters/canonical-order-indices', 'voters/every-index'):
            provenance = self.by_name[name]['provenance']
            self.assertFalse(provenance['ledgerAdmissionClaim'])
            self.assertFalse(provenance['signaturesRevalidated'])
        self.assertTrue(self.by_name['voters/ord-indices']['oracle']['collected'])
        self.assertEqual(self.by_name['voters/canonical-order-indices']['oracle']['failureKinds'],
                         ['NoRedeemer', 'NoRedeemer'])


if __name__ == '__main__':
    unittest.main()
