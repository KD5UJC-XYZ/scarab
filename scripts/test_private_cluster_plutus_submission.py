#!/usr/bin/env python3
import ast
import copy
from pathlib import Path
import unittest
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
        self.assertEqual(obj.create('scala', ['java', 'lab.NativeLiveBoundaryMain', 'a']),
                         ('java', 'lab.PlutusSubmissionMain', 'a'))
        self.assertEqual(obj.create('ada-client', ['lab.AdaSubmissionClientMain', '1']),
                         ('lab.AdaSubmissionClientMain', '1', c.fixture.PROFILE))

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
        obj.create = lambda phase, args: 'owned-id'
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
                obj.proof_helper('plutus-spend-originals', [], Path('/owned/exchange/out.json'))
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
