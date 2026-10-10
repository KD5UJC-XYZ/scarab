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


if __name__ == '__main__': unittest.main()
