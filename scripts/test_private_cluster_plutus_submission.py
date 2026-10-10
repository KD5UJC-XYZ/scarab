#!/usr/bin/env python3
import ast
import copy
from pathlib import Path
import unittest
import tempfile
import json
import hashlib
from types import SimpleNamespace
from unittest.mock import patch

import private_cluster_plutus_submission as c


class ControllerTest(unittest.TestCase):
    def setUp(self):
        self.initial = dict(slot=20, blockNo=2, hash='11' * 32)
        self.request = dict(schema='native-live-endpoint-request-v1', epoch=0,
            point=dict(slot=50, blockNo=3, hash='22' * 32), sourceJoinId='33' * 32,
            networkAppliedStateId='44' * 32, followedAcrossBoundary=False, sameEpoch=True,
            peerClosed=True, transportClosed=True, streamStartedUnixMillis=100, streamEndedUnixMillis=200)

    def check(self, **changes):
        return c.same_epoch_request(dict(self.request, **changes), self.initial, '33' * 32, 1000)

    def test_same_epoch_terminal(self): self.assertEqual(self.check(), self.request['point'])

    def test_epoch_crossing_rejected(self):
        with self.assertRaises(ValueError): self.check(epoch=1)
        with self.assertRaises(ValueError): self.check(followedAcrossBoundary=True)
        with self.assertRaises(ValueError): self.check(point=dict(slot=1000, blockNo=3, hash='22' * 32))

    def test_stream_boundary_or_open_resource_rejected(self):
        with self.assertRaises(ValueError): self.check(streamEndedUnixMillis=1000)
        with self.assertRaises(ValueError): self.check(peerClosed=False)
        with self.assertRaises(ValueError): self.check(transportClosed=False)

    def test_stale_or_different_owner_rejected(self):
        with self.assertRaises(ValueError): self.check(point=self.initial)
        with self.assertRaises(ValueError): self.check(sourceJoinId='55' * 32)

    def test_resource_budget_unchanged(self):
        c.base.budget()
        self.assertEqual(sum(x[0] for x in c.base.RESOURCES.values()), 4)
        self.assertEqual(sum(x[1] for x in c.base.RESOURCES.values()), 7 * c.base.GIB)

    def test_exact_main_and_client_profile_dispatch(self):
        class Launcher:
            def create(self, phase, tail): return tuple(tail)
        controller = c.controller_type(SimpleNamespace(Launcher=Launcher))
        obj = object.__new__(controller)
        self.assertEqual(obj.create('scala', ['java', 'lab.NativeLiveBoundaryMain', 'initial', 'pin', '1', '2', 'exchange']),
                         ('java', 'lab.Main', 'plutus-research', '--profile', c.fixture.PROFILE,
                          '--initial', 'initial', '--manifest-sha256', 'pin', '--port', '1',
                          '--magic', '2', '--exchange', 'exchange'))
        self.assertEqual(obj.create('ada-client', ['lab.AdaSubmissionClientMain', '1']),
                         ('lab.AdaSubmissionClientMain', '1', c.fixture.PROFILE))

    def test_research_dispatch_rejects_legacy_argument_shape(self):
        with self.assertRaises(ValueError): c.research_cli_tail(['lab.NativeLiveBoundaryMain', 'only-one'])

    def test_compile_classpath_rejects_test_output_but_client_may_use_it(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'app/target/test-classes').mkdir(parents=True)
            (root / 'app/target/classes').mkdir()
            cp = root / 'classpath.txt'
            cp.write_text('/work/app/target/test-classes')
            with self.assertRaisesRegex(ValueError, 'exclude Test'):
                c.checked_classpath(cp, root, True)
            self.assertEqual(c.checked_classpath(cp, root), '/work/app/target/test-classes')
            cp.write_text('/work/app/target/classes')
            self.assertEqual(c.checked_classpath(cp, root, True), '/work/app/target/classes')

    def test_external_client_failure_restores_compile_classpath(self):
        class Launcher: pass
        controller = c.controller_type(SimpleNamespace(Launcher=Launcher))
        obj = object.__new__(controller)
        obj.classpath = '/work/runtime/classes'
        obj.args = SimpleNamespace(client_classpath_file=Path('/client-cp'), scala_build_root=Path('/build'))
        def fail_client(actual):
            self.assertEqual(actual.classpath, '/work/client/test-classes')
            raise TimeoutError('external test client failure')
        with patch.object(c, 'checked_classpath', return_value='/work/client/test-classes'), \
             patch.object(controller.__bases__[0], 'run_client', fail_client):
            with self.assertRaises(TimeoutError): obj.run_client()
        self.assertEqual(obj.classpath, '/work/runtime/classes')

    def test_reference_submit_forbidden_through_inherited_dispatch(self):
        class Launcher:
            def execute(self, *args, **kwargs): raise AssertionError('must not execute')
        controller = c.controller_type(SimpleNamespace(Launcher=Launcher))
        obj = object.__new__(controller)
        with self.assertRaises(ValueError):
            obj.execute('cardano-cli', 'conway', 'transaction', 'submit', '--tx-file', c.fixture.SPEND)

    def proof_failure(self, resource_failure):
        class Launcher: pass
        controller = c.controller_type(SimpleNamespace(Launcher=Launcher))
        obj = object.__new__(controller)
        obj.args = SimpleNamespace(scala_build_root=Path('/owned/build'), java='java', scala_image='pinned')
        obj.exchange, obj.out, obj.classpath = Path('/owned/exchange'), Path('/owned/evidence'), '/work/classes'
        obj.containers = {}
        obj.deadline = c.time.monotonic() + 60
        def create(phase, args):
            self.assertIn('lab.Main', args)
            self.assertIn('transaction-originals', args)
            self.assertNotIn('lab.NativeScriptFixtureMain', args)
            return 'owned-id'
        obj.create = create
        inspections = []
        def owned(cid):
            inspections.append(cid)
            return dict(Mounts=[dict(Type='bind', Source='/owned/build', Destination='/work', RW=False),
                                dict(Type='bind', Source='/owned/exchange', Destination='/exchange', RW=True)])
        obj.owned = owned
        calls = []
        def docker(*argv, **kwargs):
            calls.append(argv)
            if argv[0] == 'start': raise TimeoutError('mock bounded attach failure')
            return SimpleNamespace(stdout='')
        obj.docker = docker
        with patch.object(c.base, 'write'), patch.object(c.base, 'check_resources',
                side_effect=ValueError('mock inspection rejection') if resource_failure else None):
            with self.assertRaises((TimeoutError, ValueError)):
                obj.proof_helper('plutus-spend-originals', ['originals', 'input', 'output'], Path('/owned/exchange/out.json'))
        self.assertEqual(inspections, ['owned-id', 'owned-id'])
        self.assertIn(('rm', '--force', 'owned-id'), calls)
        self.assertTrue(any(x[0] == 'ps' for x in calls))

    def test_owned_helper_start_failure_cleanup(self): self.proof_failure(False)

    def test_owned_helper_resource_failure_cleanup(self): self.proof_failure(True)

    def test_staged_controller_separate_lifecycle(self):
        tree = ast.parse(Path(c.__file__).read_text())
        cls = next(n for n in ast.walk(tree) if isinstance(n, ast.ClassDef) and n.name == 'PlutusController')
        methods = {n.name for n in cls.body if isinstance(n, ast.FunctionDef)}
        self.assertIn('same_epoch_lifecycle', methods)
        self.assertNotIn('cleanup', methods)  # inherit token/immutable-ID cleanup
        self.assertNotIn('execute', methods)  # inherit reference submit prohibition


class EvaluationReceiptTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        (self.root / 'evaluation-receipts').mkdir()
        self.path = self.root / 'evaluation-receipts/plutus-evaluation-0000.json'
        self.transfer = {name: str(i) * 64 for i, name in enumerate(
            ('transactionId', 'envelopeSHA256', 'bodySHA256', 'witnessesSHA256'), 1)}
        self.value = dict(schema='plutus-evaluation-evidence-v1', eventIndex=0,
            phase='admission', outcome='accepted', newlyAdmitted=True, sourceJoinId='5' * 64,
            initialManifestSHA256='6' * 64, **self.transfer,
            requestDigest='7' * 64, contextSHA256='8' * 64, scriptSHA256=c.fixture.SCRIPT_SHA,
            modelSHA256='6ab455d588e186649a6aae2761fec85ae5a2647cb002a2acb737604f698b21a2',
            evaluator='Scalus', evaluatorVersion='1.3.0', language='PlutusV3', semantics='C', protocolMajor=9,
            declared=dict(memory=100000, steps=30000000), consumed=dict(memory=50000, steps=20000000),
            statePin=dict(ownerId='e' * 64, generation=1, validationSlot=200, profileId=c.fixture.PROFILE,
                point=dict(slot=200, blockNo=5, hash='9' * 64), coherentStateId='a' * 64,
                ledgerStateId='b' * 64, environmentId='c' * 64),
            fullLedgerValidated=False, inclusionClaimed=False, currentEligibilityClaimed=False)
        self.client = dict(acceptedResponse=dict(code='Accepted', profileId=c.fixture.PROFILE,
            receipt=dict(transactionId=self.transfer['transactionId'], envelopeSHA256=self.transfer['envelopeSHA256'],
                         pin=copy.deepcopy(self.value['statePin']))))

    def result(self):
        self.raw = json.dumps(self.value, indent=2).encode()
        self.path.write_bytes(self.raw)
        return dict(evaluationReceiptFile=str(self.path.relative_to(self.root)),
                    evaluationReceiptSHA256=hashlib.sha256(self.raw).hexdigest(), evaluationReceiptCount=1)

    def check(self, result=None):
        return c.evaluation_receipt(self.root, result or self.result(), self.transfer, '5' * 64, '6' * 64, self.client)

    def test_original_accepted_receipt_bytes_retained(self):
        result = self.result()
        self.assertEqual(self.check(result), self.raw)

    def test_changed_receipt_digest_rejected(self):
        result = self.result()
        result['evaluationReceiptSHA256'] = 'd' * 64
        with self.assertRaisesRegex(ValueError, 'pins original'): self.check(result)

    def test_different_original_or_bootstrap_rejected_even_with_valid_file_hash(self):
        for name in ('transactionId', 'bodySHA256', 'sourceJoinId', 'initialManifestSHA256'):
            with self.subTest(field=name):
                old = self.value[name]
                self.value[name] = 'd' * 64
                with self.assertRaises(ValueError): self.check()
                self.value[name] = old

    def test_duplicate_and_revalidation_are_not_new_http_acceptance(self):
        for changes in (dict(outcome='already-present', newlyAdmitted=False), dict(phase='revalidation')):
            old = self.value.copy()
            self.value.update(changes)
            with self.assertRaisesRegex(ValueError, 'newly accepted'): self.check()
            self.value = old

    def test_claim_inflation_or_budget_overrun_rejected(self):
        self.value['inclusionClaimed'] = True
        with self.assertRaisesRegex(ValueError, 'scope'): self.check()
        self.value['inclusionClaimed'] = False
        self.value['consumed']['steps'] = 30000001
        with self.assertRaisesRegex(ValueError, 'execution units'): self.check()

    def test_other_admission_state_rejected_with_same_transaction_and_sources(self):
        for field, replacement in (('ownerId', 'f' * 64), ('generation', 2),
                                   ('ledgerStateId', 'd' * 64), ('environmentId', 'd' * 64)):
            with self.subTest(field=field):
                old = self.value['statePin'][field]
                self.value['statePin'][field] = replacement
                with self.assertRaisesRegex(ValueError, 'full state pin'): self.check()
                self.value['statePin'][field] = old
        self.value['statePin']['validationSlot'] = 201
        self.value['statePin']['point']['slot'] = 201
        with self.assertRaisesRegex(ValueError, 'full state pin'): self.check()

    def test_receipt_record_bounds(self):
        result = self.result()
        result['evaluationReceiptCount'] = 129
        with self.assertRaisesRegex(ValueError, 'event identity'): self.check(result)

    def test_receipt_path_traversal_and_symlink_rejected(self):
        result = self.result()
        result['evaluationReceiptFile'] = '../evaluation-receipts/plutus-evaluation-0000.json'
        with self.assertRaisesRegex(ValueError, 'relative path'): self.check(result)
        result = self.result()
        alternate = self.root / 'alternate.json'
        self.path.rename(alternate)
        self.path.symlink_to(alternate)
        with self.assertRaisesRegex(ValueError, 'symlink'): self.check(result)


class BlockZeroReadinessTest(unittest.TestCase):
    def tip(self, **changes):
        return dict(dict(era='Conway', epoch=0, slot=66, block=0, hash='1' * 64), **changes)

    def test_common_real_block_zero_selected_under_same_deadline(self):
        class Launcher: pass
        controller = c.controller_type(SimpleNamespace(Launcher=Launcher,
                                process=SimpleNamespace(same_tip=lambda a, b: a == b)))
        obj = object.__new__(controller)
        obj.boundary_ms, obj.deadline = 1_100_000, 9000
        queried = []
        def tip(node):
            queried.append(node)
            return self.tip()
        obj.tip = tip
        with patch.object(c.time, 'time_ns', return_value=1_007_000_000_000), \
             patch.object(c.time, 'monotonic', return_value=5000):
            a, b = obj.select_prefunding()
        self.assertEqual((a['block'], b['block']), (0, 0))
        self.assertEqual(queried, [1, 2])
        self.assertEqual(obj.deadline, 9000)

    def test_peer_boolean_block_is_not_integer_zero(self):
        class Launcher: pass
        controller = c.controller_type(SimpleNamespace(Launcher=Launcher,
                                process=SimpleNamespace(same_tip=lambda a, b: a == b)))
        obj = object.__new__(controller)
        obj.boundary_ms, obj.deadline = 1_100_000, 9000
        obj.tip = lambda node: self.tip(block=0 if node == 1 else False)
        with patch.object(c.time, 'time_ns', return_value=1_007_000_000_000), \
             patch.object(c.time, 'monotonic', return_value=5000):
            with self.assertRaisesRegex(ValueError, 'real bounded block'):
                obj.select_prefunding()
        self.assertEqual(obj.deadline, 9000)

    def test_block_zero_snapshot_and_confirmed_funding_advance(self):
        f = c.fixture
        source, script, beneficiary = ('addr_test1' + char * 40 for char in 'abc')
        source_id, txid = '1' * 64 + '#0', '2' * 64
        def row(address, amount, datum=None):
            return dict(address=address, value=dict(lovelace=amount), inlineDatum=datum)
        datum = dict(constructor=0, fields=[dict(bytes='ab' * 28), dict(int=5000000)])
        before = f.Snapshot(c.prefunding_point(self.tip()), {source_id: row(source, 100000000)}, 0).checked()
        plan = f.funding_plan(before.utxo, source, script, beneficiary, beneficiary, datum)
        after = f.Snapshot(c.frozen_point(self.tip(slot=90, block=1, hash='2' * 64)),
            {txid+'#0': row(script, 20000000, datum), txid+'#1': row(beneficiary, 5000000),
             txid+'#2': row(source, 74800000)}, 200000).checked()
        original = b'funding-original'
        identity = dict(transactionId=txid, envelopeSHA256=f.digest(original), bytes=len(original),
                        bodySHA256='3' * 64, witnessesSHA256='4' * 64)
        result = f.funding_comparison(before, after, plan, txid, original, identity)
        self.assertEqual((result['beforePoint']['blockNo'], result['afterPoint']['blockNo']), (0, 1))
        self.assertTrue(result['completeUtxoChecked'])

    def test_invalid_or_absent_block_number_rejected(self):
        for block in (None, True, False, -1, 2 ** 64):
            with self.subTest(block=block), self.assertRaises(ValueError):
                c.frozen_point(self.tip(block=block))
        tip = self.tip()
        del tip['block']
        with self.assertRaises(ValueError): c.frozen_point(tip)

    def test_origin_missing_hash_and_slot_bounds_rejected(self):
        for changes in (dict(slot=0), dict(slot=-1), dict(slot=True), dict(slot=300),
                        dict(hash=None), dict(hash='not-a-hash'), dict(era='Babbage'), dict(epoch=1)):
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                c.frozen_point(self.tip(**changes))
        with self.assertRaises(TimeoutError): c.prefunding_point(self.tip(slot=100))


class TimingTest(unittest.TestCase):
    def tip(self, slot):
        return dict(era="Conway", epoch=0, slot=slot, block=1, hash="11" * 32)

    def test_pre_funding_exact_edge_and_late_first_block(self):
        self.assertEqual(c.prefunding_point(self.tip(99))["slot"], 99)
        for slot in (100, 175, 299):
            with self.subTest(slot=slot), self.assertRaisesRegex(TimeoutError, "slot-100 window missed"):
                c.prefunding_point(self.tip(slot))

    def test_genesis_window_not_controller_uptime(self):
        # Genesis start 1,000,000ms, slot300 deadline 1,030,000ms.
        # The controller could have any monotonic origin or long operation budget.
        with patch.object(c.time, "time_ns", return_value=1_029_200_000_000), \
             patch.object(c.time, "monotonic", return_value=5000):
            self.assertAlmostEqual(c.window_deadline(1_100_000, 300, 9000, 15), 5000.8)

    def test_missed_wallclock_window_even_with_frozen_early_tip(self):
        c.prefunding_point(self.tip(50))
        with patch.object(c.time, "time_ns", return_value=1_030_000_000_000), \
             patch.object(c.time, "monotonic", return_value=1):
            with self.assertRaisesRegex(TimeoutError, "slot-300 preparation window missed"):
                c.window_deadline(1_100_000, 300, 100000, 30)

    def test_operation_and_phase_caps_still_apply(self):
        with patch.object(c.time, "time_ns", return_value=1_001_000_000_000), \
             patch.object(c.time, "monotonic", return_value=100):
            self.assertEqual(c.window_deadline(1_100_000, 300, 105, 15), 105)
            self.assertEqual(c.window_deadline(1_100_000, 300, 200, 15), 115)
            self.assertEqual(c.window_deadline(1_100_000, 100, 200, 90), 109)

    def test_preparation_restores_deadline_and_rejects_late_return(self):
        class Launcher: pass
        cls = c.controller_type(SimpleNamespace(Launcher=Launcher))
        obj = object.__new__(cls)
        obj.boundary_ms, obj.deadline = 1_100_000, 9000
        observed = []
        def prepare(anchor):
            observed.append(obj.deadline)
            return self.tip(292)
        obj.prepare_funding = prepare
        with patch.object(c.time, "time_ns", side_effect=[1_020_000_000_000, 1_030_000_000_000]), \
             patch.object(c.time, "monotonic", return_value=5000):
            with self.assertRaisesRegex(TimeoutError, "slot-300 preparation window missed"):
                obj.prepare_initial(self.tip(90))
        self.assertEqual(observed, [5010])
        self.assertEqual(obj.deadline, 9000)

    def test_late_anchor_fails_before_preparation_actions(self):
        class Launcher: pass
        cls = c.controller_type(SimpleNamespace(Launcher=Launcher))
        obj = object.__new__(cls)
        obj.prepare_funding = lambda _: self.fail("must not prepare late anchor")
        with self.assertRaisesRegex(TimeoutError, "pre-funding slot-100"):
            obj.prepare_initial(self.tip(175))

    def test_slow_tip_queries_cannot_accept_old_point_after_window(self):
        class Launcher: pass
        cls = c.controller_type(SimpleNamespace(Launcher=Launcher,
                                process=SimpleNamespace(same_tip=lambda a, b: a == b)))
        obj = object.__new__(cls)
        obj.boundary_ms, obj.deadline = 1_100_000, 9000
        wall = [1_009_900_000_000]
        observed_deadlines = []
        def tip(node):
            observed_deadlines.append(obj.deadline)
            if node == 2: wall[0] = 1_010_001_000_000
            return self.tip(99)
        obj.tip = tip
        with patch.object(c.time, "time_ns", side_effect=lambda: wall[0]), \
             patch.object(c.time, "monotonic", return_value=5000):
            with self.assertRaisesRegex(TimeoutError, "slot-100 preparation window missed"):
                obj.select_prefunding()
        self.assertEqual(observed_deadlines, [5000.1, 5000.1])
        self.assertEqual(obj.deadline, 9000)

    def test_final_frozen_point_remains_strict_slot300(self):
        self.assertEqual(c.frozen_point(self.tip(299))["slot"], 299)
        with self.assertRaises(ValueError): c.frozen_point(self.tip(300))


if __name__ == '__main__': unittest.main()
