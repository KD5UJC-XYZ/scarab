# SPDX-License-Identifier: Apache-2.0
"""Guard the Leios testnet chain reader: its CBOR walk, its reading of the recorded testnet blocks, the
stratified sample, and the reading of the context oracle's `PlutusWithContext` encodings."""
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import types
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location(
    'leios_chain_scan', ROOT / 'scripts/oracle/leios-testnet/leios_chain_scan.py')
SCAN = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SCAN)
CHAIN = SCAN.CHAIN
CONTEXTS = CHAIN.DIRECTORY
SCRIPTS = SCAN.SCRIPTS


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

    def test_a_collection_that_failed_at_any_level_is_refused(self):
        chain = json.loads((CONTEXTS / 'cases.json').read_text())
        results = json.loads((CONTEXTS / 'results.json').read_text())
        models, rows = SCAN.derive(chain, results)
        self.assertEqual(len(rows), 273)
        with_subs = next(n for n, r in enumerate(results) if r['result']['subtxs'])
        for level, broken in (('subtxs', {'collected': False}), ('subtxs', {'collected': True}),
                              ('top', {'collected': False}), ('top', {'contexts': []})):
            changed = json.loads(json.dumps(results))
            if level == 'top':
                changed[with_subs]['result']['top'] = broken
            else:
                changed[with_subs]['result']['subtxs'][0] = broken
            with self.assertRaisesRegex(ValueError, 'failed or lists no contexts'):
                SCAN.derive(chain, changed)
        changed = json.loads(json.dumps(results))
        changed[0]['result']['failure'] = 'NoRedeemer'
        with self.assertRaisesRegex(ValueError, 'not collected'):
            SCAN.derive(chain, changed)

    def test_a_deleted_collection_or_case_is_refused(self):
        chain = json.loads((CONTEXTS / 'cases.json').read_text())
        results = json.loads((CONTEXTS / 'results.json').read_text())
        with_subs = next(n for n, r in enumerate(results) if r['result']['subtxs'])
        self.assertEqual(SCAN.CBOR.subtransactions(bytes.fromhex(chain['cases'][with_subs]['bytes'])),
                         len(results[with_subs]['result']['subtxs']))
        changed = json.loads(json.dumps(results))
        changed[with_subs]['result']['subtxs'] = []
        with self.assertRaisesRegex(ValueError, 'sub-transaction collections for'):
            SCAN.derive(chain, changed)
        with self.assertRaisesRegex(ValueError, 'no case'):
            SCAN.derive({**chain, 'cases': []}, [])
        with self.assertRaisesRegex(ValueError, 'not those of the cases'):
            SCAN.derive(chain, results[:-1])

    def contexts_copy(self, scratch, change):
        """A copy of the contexts fixture, `change`d (it takes the cases, the results and the provenance), with the
        provenance's digests made those of the changed files, as recording makes them."""
        copy = Path(scratch) / 'contexts'
        shutil.copytree(CONTEXTS, copy)
        chain = json.loads((copy / 'cases.json').read_text())
        results = json.loads((copy / 'results.json').read_text())
        provenance = json.loads((copy / 'provenance.json').read_text())
        change(chain, results, provenance)
        (copy / 'cases.json').write_text(json.dumps(chain))
        (copy / 'results.json').write_text(json.dumps(results))
        provenance['cases']['sha256'] = CHAIN.sha256(copy / 'cases.json')
        provenance['results']['sha256'] = CHAIN.sha256(copy / 'results.json')
        (copy / 'provenance.json').write_text(json.dumps(provenance))
        return copy

    def test_the_retained_contexts_are_bound_to_their_files_image_and_harness(self):
        chain, results = CHAIN.validate(CONTEXTS)
        self.assertEqual(len(chain['cases']), 200)
        provenance = json.loads((CONTEXTS / 'provenance.json').read_text())
        self.assertEqual(provenance['oracle']['pinnedSources'], {c: 'unchanged' for c in CHAIN.pinned()})
        self.assertEqual(provenance['oracle']['network'], 'none')
        self.assertEqual(set(CHAIN.accepted_build(CONTEXTS)), set(CHAIN.IMAGE_BUILD))
        with tempfile.TemporaryDirectory() as scratch:
            CHAIN.validate(self.contexts_copy(scratch, lambda chain, results, provenance: None))

    def test_a_changed_contexts_file_fails_validation(self):
        with tempfile.TemporaryDirectory() as scratch:
            copy = Path(scratch) / 'contexts'
            shutil.copytree(CONTEXTS, copy)
            (copy / 'results.json').write_text((copy / 'results.json').read_text().replace('"top"', '"top" ', 1))
            with self.assertRaisesRegex(ValueError, 'changed fixture file: results.json'):
                CHAIN.validate(copy)

    def test_deleted_contexts_fail_validation_even_with_their_digests_recorded(self):
        def no_subtxs(chain, results, provenance):
            next(r for r in results if r['result']['subtxs'])['result']['subtxs'] = []

        def no_cases(chain, results, provenance):
            chain['cases'] = []
            results.clear()

        def last_case(chain, results, provenance):
            del chain['cases'][-1]
            del results[-1]

        for change, refusal in ((no_subtxs, 'sub-transaction collections for'), (no_cases, 'no case'),
                                (last_case, 'the provenance counts')):
            with tempfile.TemporaryDirectory() as scratch:
                with self.assertRaisesRegex(ValueError, refusal, msg=change.__name__):
                    CHAIN.validate(self.contexts_copy(scratch, change))

    def test_one_contradicted_contexts_provenance_field_fails_validation(self):
        for change in (lambda o, p: o.update(network='host'),
                       lambda o, p: o.update(oracleBinarySha256='0' * 64),
                       lambda o, p: o.update(imageId='scarab-leios-testnet-chain:local'),
                       lambda o, p: o['pinnedSources'].update({'0' * 40: 'unchanged'}),
                       lambda o, p: o.update(runnerSha256={}),
                       lambda o, p: p['cases'].update(count=199),
                       lambda o, p: p['results'].update(refused=1)):
            with tempfile.TemporaryDirectory() as scratch:
                copy = self.contexts_copy(scratch, lambda chain, results, provenance: change(provenance['oracle'],
                                                                                             provenance))
                with self.assertRaises(ValueError):
                    CHAIN.validate(copy)

    def test_the_retained_verdicts_are_those_of_the_retained_contexts_and_bound(self):
        rows, verdicts = SCAN.verify_verdicts(SCRIPTS, CONTEXTS)
        self.assertEqual(len(rows), 273)
        self.assertEqual({v['stage'] for v in verdicts}, {'success'})
        provenance = json.loads((SCRIPTS / 'provenance.json').read_text())
        self.assertEqual(set(SCAN.RECORDED.accepted_build(SCRIPTS)), set(SCAN.RECORDED.IMAGE_BUILD))
        self.assertEqual(provenance['network'], 'none')

    def scripts_copy(self, scratch, change):
        """A copy of the verdict fixture, `change`d (it takes the requests and verdicts as lists of lines, the
        manifest and the provenance), with the manifest's digests made those of the changed files."""
        copy = Path(scratch) / 'scripts'
        shutil.copytree(SCRIPTS, copy)
        requests = (copy / 'requests.jsonl').read_text().splitlines(True)
        verdicts = (copy / 'verdicts.jsonl').read_text().splitlines(True)
        manifest = json.loads((copy / 'manifest.json').read_text())
        provenance = json.loads((copy / 'provenance.json').read_text())
        change(requests, verdicts, manifest, provenance)
        (copy / 'requests.jsonl').write_text(''.join(requests))
        (copy / 'verdicts.jsonl').write_text(''.join(verdicts))
        for name in ('requests.jsonl', 'verdicts.jsonl'):
            manifest['sha256'][name] = SCAN.RECORDED.GENERATED.sha256(copy / name)
        (copy / 'manifest.json').write_text(json.dumps(manifest))
        (copy / 'provenance.json').write_text(json.dumps(provenance))
        return copy

    def test_requests_other_than_the_contexts_give_fail_validation(self):
        def swapped(requests, verdicts, manifest, provenance):
            requests[0], requests[1] = requests[1], requests[0]

        def deleted(requests, verdicts, manifest, provenance):
            del requests[-1]
            del verdicts[-1]
            manifest['requests'] = provenance['requests'] = len(requests)

        for change in (swapped, deleted):
            with tempfile.TemporaryDirectory() as scratch:
                with self.assertRaisesRegex(ValueError, 'not those the retained contexts give', msg=change.__name__):
                    SCAN.verify_verdicts(self.scripts_copy(scratch, change), CONTEXTS)

    def test_one_contradicted_verdict_record_fails_validation(self):
        for change, refusal in (
                (lambda m, p: m.update(transactions=199), 'manifest counts'),
                (lambda m, p: p.update(requests=272), 'provenance counts'),
                (lambda m, p: p.update(network='host'), 'without network'),
                (lambda m, p: p.update(contextsSha256='0' * 64), 'retained contexts by their digest'),
                (lambda m, p: p.update(contexts='fixtures/reference/leios-testnet-contexts/results.json'),
                 'retained contexts by their digest'),
                (lambda m, p: p.update(oracleBinarySha256='0' * 64), 'does not bind the image')):
            with tempfile.TemporaryDirectory() as scratch:
                copy = self.scripts_copy(scratch, lambda requests, verdicts, m, p: change(m, p))
                with self.assertRaisesRegex(ValueError, refusal):
                    SCAN.verify_verdicts(copy, CONTEXTS)

    def test_a_failed_or_unfinished_measurement_is_refused(self):
        ok = 'file:/x ' + '0' * 64 + '\nbuild current\n'
        for status, stdout in ((1, ok + 'measured all\n'), (0, ok), (0, ok + 'measured all\nmeasured all\n'),
                               (0, ok + 'measured some\n')):
            ran = subprocess.CompletedProcess([], status, stdout=stdout, stderr='fatal: stub')
            with patch.object(CHAIN.subprocess, 'run', return_value=ran):
                with self.assertRaisesRegex(ValueError, f'could not be measured \\(exit {status}\\)'):
                    CHAIN.measure('sha256:' + 'a' * 64, 'test')
        ran = subprocess.CompletedProcess([], 0, stdout=ok + 'measured all\n', stderr='')
        with patch.object(CHAIN.subprocess, 'run', return_value=ran):
            self.assertEqual(CHAIN.measure('sha256:' + 'a' * 64, 'test')['build'], ['current'])

    def chain_binding(self, accepted=None, **changes):
        """The chain image binding of measurements that hold, with `changes` applied (None removes a key)."""
        binary = 'b' * 64
        measured = {f'file:{path}': [CHAIN.sha256(source)] for path, source in CHAIN.REPOSITORY_FILES.items()}
        measured.update({f'file:{CHAIN.IMAGE_BUILD[2]}': ['c' * 64], f'file:{CHAIN.IMAGE_BINARY}': [binary],
                         'build': ['current'], 'built': [binary], 'measured': ['all']})
        measured.update({f'source:{commit}': ['unchanged'] for commit in CHAIN.pinned()})
        measured = {k: v for k, v in {**measured, **changes}.items() if v is not None}
        with patch.object(CHAIN, 'entrypoint', return_value=[CHAIN.IMAGE_BINARY]), \
                patch.object(CHAIN, 'measure', return_value=measured):
            return CHAIN.image_binding('sha256:' + 'a' * 64, 'test', accepted)

    def test_a_chain_image_of_the_repository_harness_and_pinned_checkouts_is_bound(self):
        bound = self.chain_binding()
        self.assertEqual(bound['imageFilesSha256'][CHAIN.IMAGE_BINARY], 'b' * 64)
        self.assertEqual(bound['pinnedSources'], {c: 'unchanged' for c in CHAIN.pinned()})
        self.chain_binding(accepted={path: bound['imageFilesSha256'][path] for path in CHAIN.IMAGE_BUILD})

    def test_a_chain_image_unlike_the_repository_or_its_pins_is_refused(self):
        first, second = CHAIN.pinned()
        project = f'file:{CHAIN.PROJECT}/cabal.project'
        for changes, refusal in (({f'file:{CHAIN.IMAGE_HARNESS}': ['0' * 64]}, 'stale oracle harness'),
                                 ({project: ['0' * 64]}, 'package or cabal project'),
                                 ({f'source:{first}': ['changed']}, 'differs from its commit'),
                                 ({f'source:{second}': None}, 'not those the cabal project pins'),
                                 ({f'source:{"0" * 40}': ['unchanged']}, 'not those the cabal project pins'),
                                 ({'build': None}, 'stale executable'),
                                 ({'built': ['e' * 64]}, 'entry point is not the executable'),
                                 ({f'file:{CHAIN.IMAGE_BINARY}': None}, 'could not be measured')):
            with self.assertRaisesRegex(ValueError, refusal):
                self.chain_binding(**changes)
        accepted = {path: 'f' * 64 for path in CHAIN.IMAGE_BUILD}
        with self.assertRaisesRegex(ValueError, 'differ from the accepted oracle build'):
            self.chain_binding(accepted=accepted)
        with patch.object(CHAIN, 'entrypoint', return_value=['/bin/sh']):
            with self.assertRaisesRegex(ValueError, 'does not run'):
                CHAIN.image_binding('sha256:' + 'a' * 64, 'test', None)

    STUB = """#!/bin/sh
name=$(basename "$0")
dir=$PWD
if [ "$1" = "-C" ]; then dir=$2; shift 2; fi
case "$name $1" in
  "git rev-parse") printf '%s\\n' "$(basename "$dir")" ;;
  "git status") [ -z "$LISTING" ] || printf '%s\\n' "$LISTING" ;;
  "git diff") code=${DIFF_EXIT:-0} ;;
  "cabal build") printf 'Resolving dependencies...\\nUp to date\\n' ;;
  "cabal list-bin") printf '%s\\n' /stub/bin ;;
  sha256sum*) printf '%064d  %s\\n' 0 "$1" ;;
esac
if [ "$FAIL" = "$name $1" ] || [ "$FAIL" = "$name" ]; then exit 128; fi
exit ${code:-0}
"""

    def run_chain_measure(self, **env):
        """The chain MEASURE under bash, in a project directory with two checkouts, with stub `git`, `cabal` and
        `sha256sum` that print valid output; `FAIL` names the one that then exits 128."""
        with tempfile.TemporaryDirectory() as scratch:
            stubs = Path(scratch) / 'stubs'
            stubs.mkdir()
            for name in ('git', 'cabal', 'sha256sum'):
                stub = stubs / name
                stub.write_text(self.STUB)
                stub.chmod(0o755)
            for checkout in ('one', 'two'):
                (Path(scratch) / 'dist-newstyle' / 'src' / checkout).mkdir(parents=True)
            script = CHAIN.MEASURE.replace(f'cd {CHAIN.PROJECT}', f'cd {scratch}')
            environment = {**os.environ, 'PATH': f"{stubs}:{os.environ['PATH']}", **env}
            result = subprocess.run(['bash', '-c', script], env=environment, capture_output=True, text=True)
            measured = {}
            for line in result.stdout.splitlines():
                key, value = line.split(' ', 1)
                measured.setdefault(key, []).append(value)
            return result.returncode, measured

    def test_a_chain_measurement_that_holds_runs_to_its_end(self):
        status, measured = self.run_chain_measure()
        self.assertEqual(status, 0)
        self.assertEqual((measured['build'], measured['measured']), (['current'], ['all']))
        self.assertEqual(sorted(k for k in measured if k.startswith('source:')), ['source:one', 'source:two'])

    def test_a_chain_producer_that_fails_after_valid_output_ends_the_measurement(self):
        for failing in ('sha256sum', 'git rev-parse', 'git status', 'git diff', 'cabal build', 'cabal list-bin'):
            status, measured = self.run_chain_measure(FAIL=failing)
            self.assertNotEqual(status, 0, failing)
            self.assertNotIn('measured', measured, failing)

    def test_a_changed_checkout_is_measured_as_changed(self):
        for env in ({'LISTING': '?? Extra.hs'}, {'DIFF_EXIT': '1'}):
            status, measured = self.run_chain_measure(**env)
            self.assertEqual((status, measured['measured']), (0, ['all']), env)
            self.assertEqual(measured['source:one'], ['changed'], env)

    def test_txin_bytes_writes_a_transaction_input(self):
        tx = bytes(range(32))
        self.assertEqual(SCAN.txin_bytes((tx, 0)), b'\x82\x58\x20' + tx + b'\x00')
        self.assertEqual(SCAN.txin_bytes((tx, 24)), b'\x82\x58\x20' + tx + b'\x18\x18')
        self.assertEqual(SCAN.txin_bytes((tx, 300)), b'\x82\x58\x20' + tx + b'\x19\x01\x2c')


if __name__ == '__main__':
    unittest.main()
