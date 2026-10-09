#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Synthetic protocol fixtures only; no native state or cryptographic evidence."""
import copy
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import private_cluster_fenced_durable as f

def h(n): return f"{n:064x}"
CONTEXT=h(900)
PAIR=(h(800),h(801))

def roles(generation,forging):
    return tuple(f.Role(n,h(generation*10+n),h(100+n),forging) for n in (1,2))

def barrier(old,new,point):
    ids=tuple(x.process for x in old)
    return f.Barrier(old,new,ids,ids,(point,point,point))

def originals(previous,count,pair=False):
    result=[]
    for i in range(count):
        point=f.Point(previous.block+1,previous.slot+10,h(previous.block+1))
        result.append(f.Original(point,previous.hash,h(10000+point.block),h(20000+point.block),PAIR if pair and i==0 else ()))
        previous=point
    return result

def view(protocol,phase,depth=None):
    depth=depth or len(protocol.originals)
    return dict(confirmation="v2-acknowledged",depth=depth,revision=depth,compactedBlocks=max(1,depth-2),
        retainedBlocks=min(2,depth-1),potentiallyOlderThanDisk=False,externalReceiptStale=False,cleanupFailure=None,
        fullClaim=dict(token=dict(generation=str(0 if depth==2 else 2*depth-5),sessionId=h(700 if phase=="A" else 701)),
                       revision=str(depth),compactedBlocks=str(max(1,depth-2)),finalId=h(9000+depth)),
        projection=dict(state=h(9000+depth),fees=400000 if phase=="B" else 0),processId=h(600 if phase=="A" else 601),
        contextId=CONTEXT,point=protocol.tip,receiptPath="diagnostic.json",receiptSha256=h(300))

class FakeOps:
    def __init__(self,extra_seed=False,extra_a=False,extra_b=False,cancel=None):
        self.extra_seed=extra_seed; self.extra_a=extra_a; self.extra_b=extra_b; self.cancel=cancel
        self.calls=[]; self.cleaned=False; self.p=None; self.ack=None; self.role_gen=2
    def wire_totals(self,phase): return (8,1000)
    def remaining_wire(self,events,size): self.calls.append(("remaining",events,size))
    def step(self,name):
        self.calls.append(name)
        if self.cancel==name: raise KeyboardInterrupt(name)
    def bootstrap(self):
        for event in ("seed-produce","seed-stop1","seed-stop2","seed-keyless1","seed-keyless2","query-pre","seed-verify"):
            self.step(event)
        # Immediate third bootstrap block moves frozen F before declaration;
        # exact-point pre moves too, retaining exactly its LAST two successors.
        pre=f.Point(101 if self.extra_seed else 100,2000,h(101 if self.extra_seed else 100))
        blocks=originals(pre,2)
        self.p=f.Protocol(CONTEXT,1000,pre,blocks,barrier(roles(1,True),roles(2,False),blocks[-1].point))
        return self.p
    def start_ready(self,phase,p):
        self.step(phase+"-ready")
        if phase=="B":
            result=copy.deepcopy(self.ack["view"])
            result.update(confirmation="v2-loaded-verified",processId=h(601),receiptPath=None,receiptSha256=None)
        else: result=view(p,phase)
        return result,p.tip.slot
    def role(self,phase,p,forging,point):
        for event in ("stop1","stop2","start1","start2"): self.step(phase+"-"+event)
        self.role_gen+=1
        return barrier(p.roles,roles(self.role_gen,forging),point)
    def upgrade(self,phase,p): return self.role(phase+"-upgrade",p,True,p.tip)
    def submit_pair(self,p): self.step("B-submit")
    def await_minimum(self,phase,minimum): self.step(phase+"-minimum")
    def demote(self,phase,p):
        count=(9-len(p.originals)+int(self.extra_a)) if phase=="A" else 3+int(self.extra_b)
        blocks=originals(p.tip,count,pair=phase=="B")
        return self.role(phase+"-demote",p,False,blocks[-1].point),blocks,h(500 if phase=="A" else 501)
    def publish(self,phase,fence): self.step(phase+"-publish"); self.fence=fence
    def await_ack(self,phase,fence):
        self.step(phase+"-ack")
        value=view(self.p,phase)
        self.ack=dict(fenceId=fence.fence_id,phase=phase,fenceSha256=f.sha(fence.bytes()),resourcesFinalized=True,
                      projectionSha256=f.sha(f.canonical(value["projection"])),view=value)
        return self.ack
    def exit_and_retain(self,phase):
        self.step(phase+"-exit")
        return self.p.runtime["processId"],0,h(400),h(401)
    def query_final(self,p):
        self.step("query-final")
        return dict(requestedPoint=p.tip,firstPoint=p.tip,finalPoint=p.tip,firstBlockNo=p.tip.block,
                    finalBlockNo=p.tip.block,specificPointAcquire=True,fallback=False),PAIR
    def restore_production_and_begin_growth(self,p): self.step("tail-sixth-role-change")
    def audit(self,p):
        self.step("audit")
        return dict(referencePostStateMatched=True,onlineProjectionMatched=True,capturedBlocks=len(p.originals),
                    consensusValidated=False,fullLedgerValidated=False,transactionIdsInBlockOrder=list(PAIR),
                    submissionIndexesInBlockOrder=[0,1],transactionBlockIndex=p.phase_start)
    def verify_two_epoch_growth(self,p): self.step("tail-growth")
    def cleanup(self): self.calls.extend(["cleanup-runtime","cleanup-query","cleanup-role1","cleanup-role2","cleanup-socket"]); self.cleaned=True

class FencedProtocolTests(unittest.TestCase):
    def test_whole_protocol_staged_ready_then_upgrade_ack_then_exit(self):
        ops=FakeOps(); p=f.run_stages(ops)
        self.assertEqual(p.stage,"audited"); self.assertEqual(len(p.originals),12); self.assertTrue(ops.cleaned)
        for phase in ("A","B"):
            self.assertLess(ops.calls.index(phase+"-ready"),ops.calls.index(phase+"-upgrade-start1"))
            self.assertLess(ops.calls.index(phase+"-demote-start2"),ops.calls.index(phase+"-publish"))
            self.assertLess(ops.calls.index(phase+"-ack"),ops.calls.index(phase+"-exit"))
        self.assertLess(ops.calls.index("A-exit"),ops.calls.index("B-ready"))
        self.assertLess(ops.calls.index("B-exit"),ops.calls.index("query-final"))

    def test_immediate_extra_block_at_each_boundary_is_included_before_fence(self):
        for seed,a,b in ((True,False,False),(False,True,False),(False,False,True),(True,True,True)):
            with self.subTest(seed=seed,a=a,b=b):
                ops=FakeOps(seed,a,b); p=f.run_stages(ops)
                self.assertEqual(len(p.originals),12+int(a)+int(b))
                self.assertEqual(p.anchor.block,101 if seed else 100)
                self.assertEqual(p.fences["A"].depth,9+int(a))
                self.assertEqual(p.fences["B"].point,p.tip)

    def test_cancel_every_owned_role_query_fence_ack_boundary_always_cleans(self):
        baseline=FakeOps(); f.run_stages(baseline)
        events=[x for x in baseline.calls if isinstance(x,str) and not x.startswith("cleanup")]
        for event in events:
            with self.subTest(event=event):
                ops=FakeOps(cancel=event)
                with self.assertRaisesRegex(KeyboardInterrupt,event): f.run_stages(ops)
                self.assertTrue(ops.cleaned)
                self.assertEqual(ops.calls[-5:],["cleanup-runtime","cleanup-query","cleanup-role1","cleanup-role2","cleanup-socket"])

    def prepared(self):
        ops=FakeOps(); p=ops.bootstrap(); v,c=ops.start_ready("A",p); p.ready("A",v,c); p.produce("A",ops.upgrade("A",p))
        return ops,p

    def test_delayed_historical_original_is_not_new_production(self):
        ops,p=self.prepared(); bar,blocks,fid=ops.demote("A",p)
        p.ready_slot=blocks[0].point.slot
        with self.assertRaisesRegex(ValueError,"newly produced"): p.freeze("A",bar,blocks,fid)

    def test_role_barrier_requires_both_exit_tcp_closure_and_same_database(self):
        ops,p=self.prepared(); bar,_,_=ops.demote("A",p)
        for bad in (f.Barrier(bar.previous,bar.replacements,bar.exited[:1],bar.closed_connections,bar.tips),
                    f.Barrier(bar.previous,bar.replacements,bar.exited,(),bar.tips),
                    f.Barrier(bar.previous,(f.Role(1,h(777),h(778),False),bar.replacements[1]),bar.exited,bar.closed_connections,bar.tips)):
            with self.assertRaises(ValueError): bad.checked(False)

    def test_fence_cannot_be_declared_twice_or_acknowledged_at_next_point(self):
        ops,p=self.prepared(); bar,blocks,fid=ops.demote("A",p); fence=p.freeze("A",bar,blocks,fid)
        with self.assertRaises(ValueError): p.freeze("A",bar,[],h(502))
        ack=ops.await_ack("A",fence); ack["view"]["point"]=f.Point(p.tip.block+1,p.tip.slot+10,h(999))
        with self.assertRaisesRegex(ValueError,"point/process"): p.acknowledge("A",ack)

    def test_ack_requires_original_fence_and_finalized_resources(self):
        for field,value in (("fenceSha256",h(999)),("resourcesFinalized",False),("phase","B")):
            ops,p=self.prepared(); bar,blocks,fid=ops.demote("A",p); fence=p.freeze("A",bar,blocks,fid)
            ack=ops.await_ack("A",fence); ack[field]=value
            with self.assertRaises(ValueError): p.acknowledge("A",ack)

    def test_loaded_claim_or_projection_change_is_not_resume(self):
        class Bad(FakeOps):
            def start_ready(self,phase,p):
                value,clock=super().start_ready(phase,p)
                if phase=="B": value["projection"]["fees"]+=1
                return value,clock
        ops=Bad()
        with self.assertRaisesRegex(ValueError,"loaded exact"): f.run_stages(ops)
        self.assertTrue(ops.cleaned)

    def test_latest_tip_is_never_oracle_fallback(self):
        class Bad(FakeOps):
            def query_final(self,p):
                packet,pair=super().query_final(p); packet["finalPoint"]=f.Point(p.tip.block+1,p.tip.slot+10,h(777)); return packet,pair
        with self.assertRaisesRegex(ValueError,"exact final-point"): f.run_stages(Bad())

    def test_cumulative_budgets_never_reset_between_processes(self):
        p=FakeOps().bootstrap(); p.charge(64,16*1024*1024); p.charge(64,16*1024*1024)
        with self.assertRaises(ValueError): p.charge(1,0)
        with self.assertRaises(ValueError): p.charge(0,1)

    def test_atomic_fence_publication_canonical_and_never_overwrites(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/"fence"; fence=f.Fence(h(500),"A",CONTEXT,9,f.Point(109,2090,h(109)))
            digest=f.publish_fence(path,fence)
            self.assertEqual(digest,f.sha(path.read_bytes())); self.assertEqual(len(path.read_text().splitlines()),8)
            self.assertTrue(path.read_bytes().endswith(b"\n"))
            with self.assertRaises(ValueError): f.publish_fence(path,fence)
            self.assertFalse(list(Path(directory).glob(".fence-*")))

    def test_budget_proposal_is_honest_about_extra_twenty_seconds(self):
        p=f.budget_plan(137,0,220)
        self.assertEqual(p["caseGuardDeadline"],500)
        self.assertEqual(p["productionDeadline"],218)
        self.assertEqual(p["absoluteCleanupDeadline"],600)
        self.assertFalse(p["liveApproved"])
        for args in ((137.01,0,221),(137,0,219.99),(119.7,0,220,480)):
            with self.assertRaises(ValueError): f.budget_plan(*args)

if __name__=="__main__": unittest.main()


class AdapterTests(unittest.TestCase):
    def test_immediate_successor_during_upgrade_does_not_select_fence(self):
        ops=FakeOps(); p=ops.bootstrap(); v,c=ops.start_ready('A',p); p.ready('A',v,c)
        nextpoint=f.Point(p.tip.block+1,p.tip.slot+10,h(990))
        bar=barrier(p.roles,roles(3,True),nextpoint)
        p.produce('A',bar)
        self.assertEqual(p.tip,v['point']); self.assertEqual(p.fences,{})

    def test_every_completed_phase_charges_remaining_wire_without_reset(self):
        ops=FakeOps(); p=f.run_stages(ops)
        self.assertEqual((p.events,p.returned_bytes),(24,3000))
        remaining=[x for x in ops.calls if isinstance(x,tuple)]
        self.assertEqual(remaining,[('remaining',120,f.MAX_BYTES-1000),('remaining',112,f.MAX_BYTES-2000)])
        ops=FakeOps(); ops.wire_totals=lambda phase:(129,1) if phase=='bootstrap' else (1,1)
        with self.assertRaisesRegex(ValueError,'cumulative'): f.run_stages(ops)
        self.assertTrue(ops.cleaned); self.assertNotIn('A-ready',ops.calls)

    def test_runtime_explicit_bounds_journal_resume_and_no_seed_fallback(self):
        args=f.runtime_arguments('B',5303,CONTEXT,h(99),None,h(90),13,16,50,100,100000)
        self.assertEqual(args[args.index('--minimum-depth')+1],'13')
        self.assertEqual(args[args.index('--store-action')+1],'resume')
        self.assertNotIn('--seed-capture',args); self.assertNotIn('--resume-receipt',args)
        with self.assertRaises(ValueError): f.runtime_arguments('B',5303,CONTEXT,h(99),{},h(90),13,16,50,100,100000)
        with self.assertRaises(ValueError): f.runtime_arguments('A',5303,CONTEXT,h(99),{},h(90),9,13,50,100,100000)

    def test_node_role_arguments_share_db_and_socket_not_signing_authority(self):
        for n in (1,2):
            a=f.node_arguments(n,True); b=f.node_arguments(n,False)
            for flag in ('--database-path','--config','--topology','--socket-path'):
                self.assertEqual(a[a.index(flag)+1],b[b.index(flag)+1])
            self.assertIn('--shelley-vrf-key',a); self.assertNotIn('--shelley-vrf-key',b)
        self.assertIn('/sockets/node3.sock',f.node_arguments(3,False))
        with self.assertRaises(ValueError): f.node_arguments(3,True)

    def test_both_pidfd_signals_and_waits_attempted_after_interrupt_no_restart(self):
        r=f.ReferenceRoles(None,h(9),{n:h(n) for n in (1,2,3)},lambda *a:None,lambda *a:None)
        r.active={n:dict(identity={'n':n},forging=True) for n in (1,2)}
        calls=[]
        def signal(n,end):
            calls.append(('signal',n))
            if n==1: raise KeyboardInterrupt('cancel')
        r.signal=signal; r.stopped=lambda n,end:calls.append(('wait',n)); r.start=lambda *a:calls.append(('start',a))
        with self.assertRaises(BaseExceptionGroup): r.change(False,100)
        self.assertEqual(calls,[('signal',1),('signal',2),('wait',1),('wait',2)])

    def test_role_replacements_start_only_after_both_exits(self):
        r=f.ReferenceRoles(None,h(9),{n:h(n) for n in (1,2,3)},lambda *a:None,lambda *a:None)
        r.active={n:dict(identity={'n':n},forging=False) for n in (1,2)}; calls=[]
        r.signal=lambda n,end:calls.append(('signal',n))
        def stopped(n,end): calls.append(('wait',n)); del r.active[n]
        def start(n,forging,end): calls.append(('start',n)); r.active[n]=dict(identity={'n':n,'new':True},forging=forging)
        r.stopped=stopped; r.start=start
        bar=r.change(True,100)
        self.assertEqual(calls,[('signal',1),('signal',2),('wait',1),('wait',2),('start',1),('start',2)])
        self.assertEqual(bar.tips,())

    def test_owned_docker_deadline_prevents_any_expired_command(self):
        calls=[]
        d=f.OwnedDocker(h(91),'/tmp',100,clock=lambda:50,invoke=lambda *a:calls.append(a))
        with self.assertRaisesRegex(ValueError,'deadline'): d.call('ps',deadline=50)
        self.assertEqual(calls,[])

    def test_owned_query_cleanup_uses_original_deadline_on_cancellation(self):
        class Docker:
            def __init__(self): self.calls=[]
            def create(self,key,args,end): self.calls.append(('create',end,args)); return h(8)
            def call(self,*args,**kw): self.calls.append(('wait',kw['deadline'])); raise KeyboardInterrupt('query cancelled')
            def remove_key(self,key,end): self.calls.append(('remove',end))
        docker=Docker()
        with tempfile.TemporaryDirectory() as tmp:
            oracle=f.ExactOracle(docker,'owned-sockets','/client','/binary','/native',h(1),'sha256:'+h(2),h(3),clock=lambda:10)
            with self.assertRaises(KeyboardInterrupt): oracle.capture(f.Point(5,50,h(5)),Path(tmp)/'packet',100)
        self.assertEqual(docker.calls[0][1],32); self.assertEqual(docker.calls[1],('wait',32)); self.assertEqual(docker.calls[2],('remove',35))
        args=docker.calls[0][2]
        self.assertIn('--network',args); self.assertIn('none',args); self.assertIn('1g',args)
        self.assertIn('type=volume,src=owned-sockets,dst=/sockets,readonly',args)

    def test_query_exit_failure_cannot_admit_even_complete_receipt(self):
        from types import SimpleNamespace
        class Docker:
            removed=False
            def create(self,*a): return h(8)
            def call(self,*a,**kw): return SimpleNamespace(stdout='1')
            def remove_key(self,*a): self.removed=True
        docker=Docker()
        with tempfile.TemporaryDirectory() as tmp, patch.object(f,'verify_packet') as verify:
            oracle=f.ExactOracle(docker,'v','/c','/b','/n',h(1),'sha256:'+h(2),h(3),clock=lambda:0)
            with self.assertRaisesRegex(ValueError,'exit failure'): oracle.capture(f.Point(5,50,h(5)),Path(tmp)/'packet',25)
            verify.assert_not_called(); self.assertTrue(docker.removed)

    def test_query_cleanup_failure_cannot_admit_receipt(self):
        from types import SimpleNamespace
        class Docker:
            def create(self,*a): return h(8)
            def call(self,*a,**kw): return SimpleNamespace(stdout='0')
            def remove_key(self,*a): raise ValueError('removal failed')
        with tempfile.TemporaryDirectory() as tmp, patch.object(f,'verify_packet') as verify:
            oracle=f.ExactOracle(Docker(),'v','/c','/b','/n',h(1),'sha256:'+h(2),h(3),clock=lambda:0)
            with self.assertRaisesRegex(ValueError,'removal failed'): oracle.capture(f.Point(5,50,h(5)),Path(tmp)/'packet',25)
            verify.assert_not_called()


class RuntimeSchemaTests(unittest.TestCase):
    def test_uncertain_query_creation_still_resolves_owned_resource_before_return(self):
        class Docker:
            calls=[]
            def create(self,key,args,end): self.calls.append(('create',key,end)); raise TimeoutError('server created, response lost')
            def remove_key(self,key,end): self.calls.append(('cleanup',key,end))
        docker=Docker()
        with tempfile.TemporaryDirectory() as tmp,patch.object(f,'verify_packet') as verify:
            oracle=f.ExactOracle(docker,'v','/c','/b','/n',h(1),'sha256:'+h(2),h(3),clock=lambda:10)
            with self.assertRaises(TimeoutError): oracle.capture(f.Point(5,50,h(5)),Path(tmp)/'packet',100)
            self.assertEqual(docker.calls,[('create','query-packet',32),('cleanup','query-packet',35)])
            verify.assert_not_called()

    def fixture(self):
        from private_cluster_sustained_durable import binding,FORMAT,AUTHORITY
        from private_cluster_node import PROFILE
        context=CONTEXT; store=h(44); state=h(45); derived=h(46)
        projection=dict(tupleId=state,contextId=context,appliedTip=dict(slot='2090',hash=h(109)))
        claim=dict(token=dict(storeId=store,contextId=context,sessionId=h(47),generation='13',digest=h(48)),
                   format=FORMAT,profile=PROFILE,authority=AUTHORITY,anchorId=h(49),finalId=state,compactedBlocks='7',revision='9')
        ack=dict(record='node-fence-ack',fenceId=h(500),phase='A',fenceSha256=h(50),slot=2090,hash=h(109),
                 projectionSha256=f.sha(f.canonical(projection)),resourcesFinalized=True,
                 contextId=context,stateId=state,revision=9,depth=9,compactedBlocks=7,retainedBlocks=2,blockNo=109,
                 derivedAnchorId=derived,scopedAppliedTip=dict(slot=2090,hash=h(109)),confirmation='v2-acknowledged',
                 confirmedGeneration=13,receiptPath='/receipts-a/ack.json',receiptSha256=h(51),fullClaim=claim,
                 storageBinding=binding(context,store),storageVersion='v2',trustedLocalPrefix=False)
        state_row=dict(record='node-state',projection=projection,revision='9',depth='9',compactedBlocks='7',derivedAnchorId=derived)
        outcome={k:v for k,v in ack.items() if k not in ('record','fenceId','phase','fenceSha256','slot','hash','projectionSha256','resourcesFinalized')}
        outcome.update(scope='bounded-node-outcome',typedStop='TargetReached',peerResourcesFinalized=True,potentiallyOlderThanDisk=False,
                       externalReceiptStale=False,cleanupFailure=None,peerOpens=1,peerCloses=1,events=30,returnedBytes=4096)
        return [state_row,ack,outcome],store

    def run_ack(self,rows,store,tmp):
        from types import SimpleNamespace
        class Docker:
            def call(self,*a,**kw): return SimpleNamespace(stdout='0')
            def remove(self,*a): pass
        runtime=f.RuntimeProcess(Docker(),h(1),tmp,tmp,tmp,CONTEXT,store,clock=lambda:0)
        runtime.processes['A']=h(600)
        runtime.wait_record=lambda *a:(rows,rows[1])
        runtime.rows=lambda *a:rows
        return runtime.acknowledge('A',None,10)

    def test_actual_cli_terminal_names_order_and_complete_claim_correlation(self):
        rows,store=self.fixture()
        with tempfile.TemporaryDirectory() as tmp:
            result=self.run_ack(rows,store,tmp)
            self.assertFalse(result['view']['trustedLocalPrefix'])
            for field,value in (('stateId',h(55)),('depth',10),('peerResourcesFinalized',False),('potentiallyOlderThanDisk',True),
                                ('externalReceiptStale',True),('cleanupFailure','CleanupFailed'),('peerCloses',0)):
                bad=copy.deepcopy(rows); bad[-1][field]=value
                with self.subTest(field=field),self.assertRaises(ValueError): self.run_ack(bad,store,tmp)
            with self.assertRaises(ValueError): self.run_ack([rows[0],rows[2],rows[1]],store,tmp)

    def test_a_and_b_trusted_prefix_classification_is_not_interchangeable(self):
        rows,store=self.fixture(); ack=rows[1]; projection=rows[0]['projection']
        f.normalized_view(ack,projection,h(600),CONTEXT,store,'A')
        with self.assertRaises(ValueError): f.normalized_view(ack,projection,h(600),CONTEXT,store,'B')
        ack['trustedLocalPrefix']=True
        f.normalized_view(ack,projection,h(600),CONTEXT,store,'B')
        with self.assertRaises(ValueError): f.normalized_view(ack,projection,h(600),CONTEXT,store,'A')


class LiveOpsBoundaryTests(unittest.TestCase):
    def driver(self,tmp,clock):
        from types import SimpleNamespace
        args=SimpleNamespace(output=str(Path(tmp)/'private'),scala_repo=str(Path(tmp)/'repo'))
        return f.LiveOps(args,clock=clock)

    def test_funding_failure_runs_once_preserves_evidence_and_cleans_up(self):
        from private_cluster_sequence import SequenceRunner
        with tempfile.TemporaryDirectory() as tmp:
            d=self.driver(tmp,lambda:10); d.setup=lambda:None
            d.startup_tip_json=lambda *_:dict(hash=h(5),slot=1001,block=10,epoch=1)
            calls=[]; d.cleanup=lambda:calls.append('cleanup')
            def fail(driver):
                calls.append('funding'); driver.save('partial-funding.json',{'retained':True})
                raise ValueError('funding/signing failed')
            with patch.object(SequenceRunner,'build_pair',side_effect=fail) as funding:
                with self.assertRaisesRegex(ValueError,'funding/signing'): f.run_stages(d)
                funding.assert_called_once_with(d)
            self.assertEqual(calls,['funding','cleanup'])
            self.assertTrue((d.out/'partial-funding.json').is_file())

    def test_malformed_tip_and_funding_deadline_fail_without_retry(self):
        from private_cluster_sequence import SequenceRunner
        for failure in ('tip','deadline'):
            with self.subTest(failure=failure),tempfile.TemporaryDirectory() as tmp:
                clock=[10.];d=self.driver(tmp,lambda:clock[0]);d.setup=lambda:None
                calls=[];d.cleanup=lambda:calls.append('cleanup')
                def tip(*_):
                    if failure=='tip': raise ValueError('malformed tip')
                    return dict(hash=h(5),slot=1001,block=10,epoch=1)
                d.startup_tip_json=tip
                def funding(_): clock[0]=148.;return []
                with patch.object(SequenceRunner,'build_pair',side_effect=funding) as build:
                    with self.assertRaisesRegex(ValueError,'malformed tip|funding setup deadline'):f.run_stages(d)
                    self.assertEqual(build.call_count,0 if failure=='tip' else 1)
                self.assertEqual(calls,['cleanup'])

    def test_only_explicit_absent_startup_socket_is_retryable(self):
        from types import SimpleNamespace
        from unittest.mock import Mock
        with tempfile.TemporaryDirectory() as tmp:
            d=self.driver(tmp,lambda:10);d.ref=h(1);d.tip_json=Mock(return_value={'valid':'tip'})
            d.docker=SimpleNamespace(exec=Mock(return_value=SimpleNamespace(returncode=1)))
            self.assertIsNone(d.startup_tip_json(1,20));d.tip_json.assert_not_called()
            d.docker.exec.return_value.returncode=0
            self.assertEqual(d.startup_tip_json(1,20),{'valid':'tip'})
            d.docker.exec.return_value.returncode=2
            with self.assertRaisesRegex(ValueError,'socket probe'):d.startup_tip_json(1,20)
            d.docker.exec.side_effect=ValueError('deadline exhausted')
            with self.assertRaisesRegex(ValueError,'deadline exhausted'):d.startup_tip_json(1,20)

    def records(self,points):
        result=[]
        for p in points:
            result.extend((dict(record='transfer-range-block',headerEnvelopeHex='80',rawBlockHex='80'),
                           dict(record='node-applied',blockNo=p.block,scopedAppliedTip=dict(slot=p.slot,hash=p.hash),transactionCount=0)))
        return result

    def test_b_frozen_point_confirmed_before_deadline_catchup_uses_existing_reservation(self):
        from types import SimpleNamespace
        time=[10.]
        with tempfile.TemporaryDirectory() as tmp:
            d=self.driver(tmp,lambda:time[0]); d.stage_deadline=20.; d.budget={'exitRetainDeadline':35.}
            ops,p=FencedProtocolTests().prepared()
            bar,blocks,fid=ops.demote('A',p); p.freeze('A',bar,blocks,fid)
            p.acknowledge('A',ops.await_ack('A',p.fences['A'])); p.exited('A',h(600),0,h(4),h(5))
            view,c=ops.start_ready('B',p); p.ready('B',view,c); p.produce('B',ops.upgrade('B',p))
            new=originals(p.tip,4); end=new[-1].point
            selected=barrier(p.roles,roles(8,False),end)
            d.roles=SimpleNamespace(change=lambda *_:selected)
            d.verify_reference=lambda *_:None
            deadlines=[]
            def rows(phase,deadline):
                deadlines.append(deadline); time[0]=24.
                return self.records([b.point for b in new])
            d.runtime=SimpleNamespace(rows=rows)
            result,raw,_=d.demote('B',p)
            self.assertEqual(result,selected); self.assertEqual(len(raw),4); self.assertEqual(deadlines,[35.])
            self.assertEqual(d.phase_points['B'],end)
            self.assertTrue((d.out/'frozen-point-B.json').is_file())

    def test_a_catchup_exit_and_b_load_share_one_nonrenewing_handoff_deadline(self):
        from types import SimpleNamespace
        time=[10.]
        with tempfile.TemporaryDirectory() as tmp:
            d=self.driver(tmp,lambda:time[0]); d.stage_deadline=80.; d.budget={'exitRetainDeadline':98.}
            ops,p=FencedProtocolTests().prepared(); new=originals(p.tip,7)
            selected=barrier(p.roles,roles(8,False),new[-1].point)
            d.roles=SimpleNamespace(change=lambda *_:selected); d.verify_reference=lambda *_:None
            d.runtime=SimpleNamespace(rows=lambda *_:self.records([b.point for b in new]))
            _,_,_=d.demote('A',p); self.assertEqual(d.handoff_deadline,15.)
            fence=f.Fence(d.fence_ids['A'],'A',CONTEXT,9,new[-1].point)
            time[0]=14.; d.publish('A',fence)
            self.assertEqual(d.handoff_deadline,15.)
            time[0]=16.
            with self.assertRaises(ValueError): d.exit_and_retain('A')

    def test_diagnostics_failure_does_not_skip_second_log_or_owned_cleanup(self):
        from types import SimpleNamespace
        with tempfile.TemporaryDirectory() as tmp:
            d=self.driver(tmp,lambda:10); calls=[]
            d.ref=h(1); d.roles=SimpleNamespace(log_bases=['/work/role1','/work/role2']); d.runtime=None
            class Docker:
                def exec(self,*args,**kw):
                    calls.append(args[-1])
                    if args[-1].endswith('role1.log'): raise KeyboardInterrupt('cancel')
                    return SimpleNamespace(returncode=0,stdout='log')
                def cleanup(self): calls.append('cleanup')
            d.docker=Docker()
            with self.assertRaises(BaseExceptionGroup): d.cleanup()
            self.assertEqual(calls,['/work/role1.log','/work/role2.log','cleanup'])

    def test_submission_actual_results_retained_before_any_upgrade(self):
        from types import SimpleNamespace
        with tempfile.TemporaryDirectory() as tmp:
            d=self.driver(tmp,lambda:10); d.stage_deadline=20
            d.roles=SimpleNamespace(active={1:{'forging':False},2:{'forging':False}})
            d.pair=[dict(transactionId=h(800+i),signedPath='/work/tx'+str(i)) for i in range(2)]
            d.execute=lambda *a,**kw:SimpleNamespace(returncode=0,stdout='accepted\n',stderr='')
            d.submit_pair(None)
            for i in range(2):
                row=json.loads((d.out/f'submission-{i}.json').read_text())
                self.assertEqual(row['stdout'],'accepted\n'); self.assertTrue(row['bothProducersKeyless'])
                self.assertEqual(row['transactionId'],h(800+i))


class ClippedQueryDeadlineTests(unittest.TestCase):
    def test_caller_eighteen_second_window_reserves_three_for_uncertain_wait_cleanup(self):
        class Docker:
            def __init__(self): self.calls=[]
            def create(self,key,args,end): self.calls.append(('create',end)); return h(8)
            def call(self,*args,**kwargs):
                self.calls.append(('wait',kwargs['deadline']))
                raise TimeoutError('server outcome uncertain')
            def remove_key(self,key,end): self.calls.append(('cleanup',end))
        docker=Docker()
        with tempfile.TemporaryDirectory() as tmp,patch.object(f,'verify_packet') as verify:
            oracle=f.ExactOracle(docker,'v','/c','/b','/n',h(1),'sha256:'+h(2),h(3),clock=lambda:10)
            with self.assertRaises(TimeoutError): oracle.capture(f.Point(5,50,h(5)),Path(tmp)/'packet',28)
            self.assertEqual(docker.calls,[('create',25),('wait',25),('cleanup',28)])
            verify.assert_not_called()
