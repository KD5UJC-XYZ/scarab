# SPDX-License-Identifier: Apache-2.0
"""Offline controller guards and real scripted subprocesses; never starts Docker."""
import datetime
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import patch
import private_cluster_fork as f

class ForkControllerTests(unittest.TestCase):
    def test_future_genesis_readiness_does_not_extend_process_deadline(self):
        genesis=dict(systemStart='2026-10-09T07:21:35Z')
        now=datetime.datetime(2026,10,9,7,21,23,tzinfo=datetime.timezone.utc).timestamp()
        got=f.startup_deadlines(genesis,now,100,300)
        self.assertEqual(got['processDeadline'],110)
        self.assertEqual(got['socketDeadline'],122)
        self.assertEqual(f.startup_deadlines(genesis,now+20,100,300)['socketDeadline'],110)

    def test_startup_clips_to_case_and_rejects_exhausted_budget(self):
        genesis=dict(systemStart='2026-10-09T07:21:35Z')
        now=datetime.datetime(2026,10,9,7,21,23,tzinfo=datetime.timezone.utc).timestamp()
        self.assertEqual(f.startup_deadlines(genesis,now,100,115)['socketDeadline'],115)
        for deadline in (99,100,111,112):
            with self.assertRaises(ValueError): f.startup_deadlines(genesis,now,100,deadline)
        for bad in ('2026-10-09T07:21:35','not-a-time'):
            with self.assertRaises(ValueError): f.startup_deadlines(dict(systemStart=bad),now,100,300)

    def test_future_genesis_socket_can_become_ready_after_ten_seconds(self):
        runner=f.ForkRunner.__new__(f.ForkRunner)
        runner.genesis=dict(systemStart='2026-10-09T07:21:35Z'); runner.deadline=130
        runner.active={}; runner.serial=0; runner.containers={'reference':'a'*64}
        runner.save=lambda *a:None; runner.docker=lambda *a,**k:None; runner.node_inventory=lambda:None
        clock=[100.0]; probes=[]
        args=f.node_arguments(1,True)
        def execute(*command,**kwargs):
            path=command[-1]
            value='42' if path.endswith('.pid') else '42 (cardano-node) S '+' '.join(['0']*18+['1234']) if path.endswith('/stat') else '\0'.join(args)+'\0'
            return subprocess.CompletedProcess(command,0,value,'')
        def tip(node,timeout=4):
            probes.append((clock[0],timeout))
            if clock[0]<112: raise ValueError('awaiting genesis')
            return dict(era='Conway')
        runner.execute=execute; runner.tip=tip
        now=datetime.datetime(2026,10,9,7,21,23,tzinfo=datetime.timezone.utc).timestamp()
        with patch.object(f.time,'time',return_value=now), patch.object(f.time,'monotonic',side_effect=lambda:clock[0]), patch.object(f.time,'sleep',side_effect=lambda seconds:clock.__setitem__(0,clock[0]+seconds)):
            runner.start_node(1,True)
        self.assertGreater(probes[-1][0],110)
        self.assertLess(probes[-1][0],122)
        self.assertIn(1,runner.active)

    def test_exhausted_case_prevents_node_launch(self):
        runner=f.ForkRunner.__new__(f.ForkRunner)
        runner.genesis=dict(systemStart='2026-10-09T07:21:35Z')
        runner.active={}; runner.deadline=100
        with patch.object(f.time,'time',return_value=2000000000), patch.object(f.time,'monotonic',return_value=100), patch.object(runner,'docker') as docker:
            with self.assertRaisesRegex(ValueError,'case deadline exhausted'): runner.start_node(1,True)
            docker.assert_not_called()

    def test_clock_crossing_socket_deadline_cannot_report_ready(self):
        runner=f.ForkRunner.__new__(f.ForkRunner)
        runner.genesis=dict(systemStart='2026-10-09T07:21:35Z'); runner.deadline=130
        runner.active={}; runner.serial=0; runner.containers={'reference':'a'*64}
        runner.save=lambda *a:None; runner.docker=lambda *a,**k:None
        args=f.node_arguments(1,True)
        results=[subprocess.CompletedProcess([],0,value,'') for value in ('42','42 (cardano-node) S '+' '.join(['0']*18+['1234']),'\0'.join(args)+'\0')]
        now=datetime.datetime(2026,10,9,7,21,23,tzinfo=datetime.timezone.utc).timestamp()
        with patch.object(f.time,'time',return_value=now), patch.object(f.time,'monotonic',side_effect=[100,100,100,121.9,122.1]), patch.object(runner,'execute',side_effect=results), patch.object(runner,'tip') as tip, patch.object(runner,'node_inventory') as inventory:
            with self.assertRaisesRegex(TimeoutError,'node socket readiness'): runner.start_node(1,True)
            tip.assert_not_called(); inventory.assert_not_called()

    def test_process_streams_and_exit_are_preserved(self):
        got=f.bounded_run([sys.executable,'-c','import sys; print("accepted"); print("rejected",file=sys.stderr); sys.exit(7)'],2)
        self.assertEqual((got.returncode,got.stdout,got.stderr),(7,'accepted\n','rejected\n'))

    def test_process_timeout_and_output_cap_terminate_owned_child(self):
        for source,timeout,limit in [('import time; time.sleep(10)',.15,1000),('print("x"*2000)',2,100)]:
            children=[]; original=subprocess.Popen
            def spawn(*a,**k):
                child=original(*a,**k); children.append(child); return child
            with patch.object(f.subprocess,'Popen',side_effect=spawn):
                with self.assertRaises((TimeoutError,ValueError)): f.bounded_run([sys.executable,'-c',source],timeout,limit=limit)
            self.assertIsNotNone(children[0].poll())

    def test_actual_argv_and_start_identity(self):
        args=f.node_arguments(1,False); stat='42 (cardano-node) S '+' '.join(['0']*18+['1234'])
        identity=f.process_identity(42,stat,args,args); f.frozen_role(identity,1)
        self.assertEqual(identity['startTicks'],'1234')
        for bad in (dict(identity,argv=f.node_arguments(1,True)),dict(identity,argv=f.node_arguments(2,False))):
            with self.assertRaises(ValueError): f.frozen_role(bad,1)
        with self.assertRaises(ValueError): f.process_identity(43,stat,args,args)
        with self.assertRaises(ValueError): f.process_identity(42,stat.replace(') S',') Z'),args,args)

    def test_utc_headroom_uses_wall_clock_not_frozen_tip(self):
        start=1700000000
        genesis=dict(systemStart=datetime.datetime.fromtimestamp(start,datetime.timezone.utc).isoformat(),epochLength=1000,slotLength=.1,securityParam=5,activeSlotsCoeff=.05)
        anchor=dict(epoch=1,slot=1005)
        self.assertGreater(f.epoch_headroom(genesis,anchor,start+105,start+105.1,start+105.05)['secondsRemaining'],90)
        for before,after,container in [(start+190,start+190.1,start+190),(start+201,start+201,start+201),(start+105,start+109,start+106),(start+105,start+105,start+108)]:
            with self.assertRaises(ValueError): f.epoch_headroom(genesis,anchor,before,after,container)
        with self.assertRaises(ValueError): f.epoch_headroom(dict(genesis,epochLength=500),anchor,start+105,start+105,start+105)

    def test_branch_capacity_and_epoch_bounds(self):
        anchor=dict(hash='a'*64,slot=1001,epoch=1,block=10,era='Conway')
        for a,b in ((1,2),(1,4),(2,3),(2,4)):
            tip=dict(anchor,hash='b'*64,slot=1010,block=10+a)
            self.assertEqual(f.branch_bounds(anchor,tip,'a'),a)
            self.assertEqual(f.branch_bounds(anchor,dict(tip,block=10+b),'b',a),b)
        for changes,branch,a in [({'block':13},'a',None),({'block':12},'b',2),({'block':15},'b',2),({'slot':2001},'a',None),({},'wrong',None)]:
            with self.assertRaises(ValueError): f.branch_bounds(anchor,dict(anchor,**changes),branch,a)

    def test_fixed_durable_cli_and_resume_digest(self):
        a=f.phase_arguments('a',5301,'a'*64,2)
        b=f.phase_arguments('b',5302,'a'*64,3,'b'*64)
        self.assertNotIn('--rollback-capacity',a+b)
        self.assertNotIn('--resume-receipt',a)
        self.assertIn('/resume/phase-a-acknowledged.json',b)
        for args in [('b',5302,'a'*64,3,None),('a',5301,'a'*64,3,None),('a',3001,'a'*64,1,None)]:
            with self.assertRaises(ValueError): f.phase_arguments(*args)

    def test_topology_isolation_and_tip_stability(self):
        template=dict(localRoots=[dict(accessPoints=[dict(address='127.0.0.1',port=1)],advertise=False,valency=2)],bootstrapPeers=None,publicRoots=[],useLedgerAfterSlot=-1)
        got=f.topology_for_peer(template,5302)
        self.assertEqual(got['localRoots'][0]['accessPoints'],[dict(address='127.0.0.1',port=5302)])
        self.assertEqual(template['localRoots'][0]['valency'],2)
        self.assertEqual(f.isolated_topology()['localRoots'],[])
        tip=dict(hash='a'*64,slot=1001,block=5,epoch=1,era='Conway',syncProgress='99')
        self.assertTrue(f.same_tip(tip,dict(tip,syncProgress='100')))
        self.assertFalse(f.same_tip(tip,dict(tip,block=6)))

    def test_receipt_mounts_hashes_and_symlinks(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory); (root/'receipts-a').mkdir(); (root/'resume').mkdir()
            raw=b'original'; (root/'receipts-a/x.json').write_bytes(raw)
            needed=[dict(path='/receipts-a/x.json',sha256=f.sha(raw))]
            self.assertEqual(f.retained_receipts(root,needed),{f.sha(raw):raw})
            for path in ('/etc/x.json','/receipts-a/../x.json','/receipts-a//x.json','/resume/foreign.json'):
                with self.assertRaises(ValueError): f.retained_receipts(root,[dict(needed[0],path=path)])
            with self.assertRaises(ValueError): f.retained_receipts(root,[dict(needed[0],sha256='0'*64)])
            (root/'receipts-a/link.json').symlink_to(root/'receipts-a/x.json')
            with self.assertRaises(OSError): f.retained_receipts(root,[dict(needed[0],path='/receipts-a/link.json')])

    def test_write_uses_immutable_reference_and_allowlisted_path(self):
        runner=f.ForkRunner.__new__(f.ForkRunner); runner.containers={'reference':'a'*64}
        with patch.object(runner,'docker') as docker:
            runner.write_json('node-data/node1/topology.json',f.isolated_topology())
            self.assertEqual(docker.call_args.args[:3],('exec','-i','a'*64))
            with self.assertRaises(ValueError): runner.write_json('../foreign',{})

    def test_cleanup_uncertain_create_uses_only_verified_owned_ids(self):
        runner=f.ForkRunner.__new__(f.ForkRunner); runner.owner='test-owner'; runner.deadline=0
        removed=[]; saved=[]; cid='a'*64; nid='b'*64
        def docker(*args,**kw):
            if args[:2]==('ps','-aq'): value='' if cid in removed else cid
            elif args[:3]==('network','ls','-q'): value='' if nid in removed else nid
            elif args[0]=='inspect': value=json.dumps([{'Id':cid,'Config':{'Labels':{f.LABEL:runner.owner}}}])
            elif args[:2]==('network','inspect'): value=json.dumps([{'Id':nid,'Labels':{f.LABEL:runner.owner}}])
            else: removed.append(args[-1]); value=''
            return subprocess.CompletedProcess(args,0,value,'')
        runner.docker=docker; runner.save=lambda *a:saved.append(a)
        runner.cleanup(); self.assertEqual(removed,[cid,nid]); self.assertTrue(saved[0][1]['verifiedAbsent'])
        removed.clear()
        runner.owner='changed-owner'
        def foreign(*a,**kw):
            result=docker(*a,**kw)
            if 'inspect' in a: result.stdout=result.stdout.replace('changed-owner','foreign-owner')
            return result
        runner.docker=foreign
        with self.assertRaises(ValueError): runner.cleanup()
        self.assertEqual(removed,[])

    def test_strict_json_rejects_duplicate_and_nonfinite(self):
        for value in ('{"x":1,"x":2}','{"x":NaN}'):
            with self.assertRaises(ValueError): f.strict_json(value)

    def test_failed_pidfd_has_no_numeric_signal_fallback(self):
        runner=f.ForkRunner.__new__(f.ForkRunner)
        identity=dict(pid=42,startTicks='1234',argv=f.node_arguments(1,False))
        runner.active={1:dict(identity,base='/work/process-1-1',identity=identity)}
        runner.process=lambda _:identity; runner.pidfd_ready=True; runner.save=lambda *a:None
        with patch.object(runner,'execute',return_value=subprocess.CompletedProcess([],2,'','replacement identity')) as execute:
            with self.assertRaises(ValueError): runner.stop_node(1)
            self.assertEqual(execute.call_count,1)
            self.assertEqual(execute.call_args.args,(f.PIDFD,'42','1234'))
        runner.pidfd_ready=False
        with patch.object(runner,'execute') as execute:
            with self.assertRaises(ValueError): runner.stop_node(1)
            execute.assert_not_called()

if __name__=='__main__': unittest.main()
