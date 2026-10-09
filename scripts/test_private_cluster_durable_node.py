# SPDX-License-Identifier: Apache-2.0
import hashlib
import json
import unittest
import copy
import types
import time
from pathlib import Path
from private_cluster_durable_node import (acknowledged_receipt,phase_arguments,raw_export_arguments,
    raw_export_provenance,checked_loaded_state,phase_progress,combined_bounds,remaining_allowance,checkpoint_binding,DurableNodeRunner,PLAN)


class DurableNodePlanGuards(unittest.TestCase):
    def receipt(self,**changes):
        value=dict(format="node-durable-acknowledged-v1",storeId="a"*64,contextId="b"*64,
                   digest="c"*64,generation="3",capacity=8)
        value.update(changes); return json.dumps(value).encode()
    def check(self,raw): return acknowledged_receipt(raw,hashlib.sha256(raw).hexdigest(),"b"*64)

    def test_exact_external_ack_required_not_pending_or_current_disk(self):
        self.assertEqual(self.check(self.receipt())["generation"],"3")
        for change in ({"format":"node-durable-pending-v1"},{"generation":"03"},
                       {"generation":3},{"capacity":True},{"contextId":"d"*64},{"digest":"G"*64},
                       {"proposed":{}},{"generation":str(1<<63)}):
            with self.assertRaises(ValueError): self.check(self.receipt(**change))
        with self.assertRaises(ValueError): acknowledged_receipt(self.receipt(),"0"*64,"b"*64)
        raw=self.receipt()[:-1]+b',"capacity":8}'
        with self.assertRaises(ValueError): self.check(raw)

    def test_phase_configs_cumulative_targets_and_distinct_receipts(self):
        a=phase_arguments("create",3001,"b"*64)
        b=phase_arguments("resume",3001,"b"*64,"f"*64)
        self.assertEqual(a[a.index("--blocks")+1],"2")
        self.assertEqual(b[b.index("--blocks")+1],"4")
        self.assertIn("/receipts-a",a); self.assertIn("/receipts-b",b)
        self.assertIn("/resume/phase-a-acknowledged.json",b)
        self.assertNotIn("--resume-receipt",a)
        for args in (("resume",3001,"b"*64),("create",True,"b"*64),("create",3001,"b"*64,"f"*64)):
            with self.assertRaises(ValueError): phase_arguments(*args)

    def test_raw_binary_export_command_never_stdout_conversion(self):
        args=raw_export_arguments("pre")
        self.assertIn("--output-text",args)
        self.assertEqual(args[-2:],["--out-file","/work/pre-ledger-state.cbor"])
        with self.assertRaises(ValueError): raw_export_arguments("late")

    def test_raw_provenance_requires_same_paused_point_and_no_reward_guess(self):
        tip=dict(hash="a"*64,slot=1020,block=10,epoch=2,era="Conway")
        args=("pre",b"\x87\xff",[tip,dict(tip)],b"protocol",b"config",19.0,True)
        receipt=raw_export_provenance(*args)
        self.assertEqual(receipt["rewardVariant"],"uninterpreted")
        self.assertFalse(receipt["nativeDecoderCompatibilityEstablished"])
        self.assertFalse(receipt["singleAcquiredSnapshot"])
        for index,value in ((1,"not binary"),(2,[tip,dict(tip,slot=1021)]),(5,20.1),(6,False)):
            changed=list(args); changed[index]=value
            with self.assertRaises(ValueError): raw_export_provenance(*changed)

    def test_loaded_recovery_is_not_a_fresh_ack_and_matches_full_projection(self):
        a=dict(confirmation="acknowledged",projection={"nested":{"fees":"400000"}},revision=2,
               confirmedGeneration=3,receiptSha256="f"*64,potentiallyOlderThanDisk=False)
        b=dict(a,confirmation="loaded-verified")
        self.assertTrue(checked_loaded_state(a,b,"f"*64))
        for change in ({"confirmation":"acknowledged"},{"projection":{"nested":{"fees":"0"}}},
                       {"confirmedGeneration":4},{"potentiallyOlderThanDisk":True},{"receiptSha256":"e"*64}):
            with self.assertRaises(ValueError): checked_loaded_state(a,dict(b,**change),"f"*64)

    def test_design_retains_review_requirement(self):
        self.assertIn("review-ready launcher",PLAN)
        self.assertIn("two EMPTY live successors",PLAN)
        self.assertIn("not live-continuation evidence",PLAN)


class DurableOrchestrationGuards(unittest.TestCase):
    def state(self,n,phase):
        return dict(contextId="b"*64,stateId=format(100+n,"064x"),revision=n,depth=n,retainedBlocks=n,
            compactedBlocks=0,derivedAnchorId=None,blockNo=10+n,
            scopedAppliedTip={"hash":format(n+1,"064x"),"slot":1020+n*10} if n else None,
            confirmation="acknowledged",confirmedGeneration=n,receiptPath=f"/receipts-{phase}/{n}.json",receiptSha256=format(n+200,"064x"))
    def rows(self,phase,alignment=True):
        start=0 if phase=="a" else 2
        initial=self.state(start,phase)
        if phase=="b": initial.update(confirmation="loaded-verified",receiptPath="/resume/phase-a-acknowledged.json")
        boot=dict(initial,record="node-bootstrap",potentiallyOlderThanDisk=False,externalReceiptStale=False,
                  suppliedAnchor={"hash":format(1,"064x"),"slot":1020})
        rows=[boot]
        if phase=="b": rows.append(dict(initial,record="node-loaded",projection={"tupleId":initial["stateId"]}))
        rows.append(dict(initial,record="node-rollback",initialIntersection=True))
        if alignment:
            rows.append(dict(initial,record="node-download",phase="rollback-announced",payloadBytes=0,
                downloadIsApplied=False,downloadCursor=initial["scopedAppliedTip"] or boot["suppliedAnchor"]))
            rows.append(dict(initial,record="node-rollback",initialIntersection=False))
        previous=initial
        for n in range(start+1,start+3):
            now=self.state(n,phase)
            for label,size in (("announced",2),("fetched",3)):
                rows.append(dict(previous,record="node-download",phase=label,payloadBytes=size,
                    downloadIsApplied=False,downloadCursor=now["scopedAppliedTip"]))
            rows.append(dict(record="transfer-range-block",acquisitionOnly=True,appliedClaim=False,
                headerEnvelopeHex="0001",rawBlockHex="020304"))
            rows.append(dict(now,record="node-applied",transactionCount=2 if n==3 else 0))
            previous=now
        rows.append(dict(record="node-state",projection={"tupleId":previous["stateId"]},revision=str(start+2),
                         depth=str(start+2),compactedBlocks="0",derivedAnchorId=None))
        rows.append(dict(previous,scope="bounded-node-outcome",typedStop="TargetReached",scopedTargetReached=True,
            mode="bounded-durable",potentiallyOlderThanDisk=False,externalReceiptStale=False,
            peerResourcesFinalized=True,peerOpens=1,peerCloses=1,reconnects=0,events=3,returnedBytes=10,
            fullLedgerValidated=False,consensusValidated=False,stateDerivedConsensus=False,caughtUp=False))
        return rows
    def test_both_phases_require_two_new_online_blocks_and_full_stop(self):
        for phase in ("a","b"):
            for alignment in (True,False):
                ready,applied,out=phase_progress(self.rows(phase,alignment),phase,"b"*64)
                self.assertTrue(ready); self.assertEqual(len(applied),2)
                self.assertEqual(out["depth"],2 if phase=="a" else 4)
    def test_retained_only_resume_or_pending_classification_cannot_pass(self):
        rows=self.rows("b")
        altered=[r for r in rows if r.get("record")!="node-applied"]
        with self.assertRaises(ValueError): phase_progress(altered,"b","b"*64)
        for kind,change in (("node-bootstrap",{"confirmation":"acknowledged"}),
                            ("node-loaded",{"stateId":"f"*64}),
                            ("node-applied",{"confirmation":"loaded-verified"}),
                            ("node-applied",{"confirmedGeneration":10}),
                            ("node-applied",{"derivedAnchorId":"f"*64})):
            altered=copy.deepcopy(rows); next(r for r in altered if r.get("record")==kind).update(change)
            with self.assertRaises(ValueError): phase_progress(altered,"b","b"*64)
    def test_A_cannot_submit_pair_and_B_cannot_rollback_fork(self):
        rows=self.rows("a"); next(r for r in rows if r.get("record")=="node-applied")["transactionCount"]=2
        with self.assertRaises(ValueError): phase_progress(rows,"a","b"*64)
        rows=self.rows("b"); next(r for r in rows if r.get("phase")=="rollback-announced")["downloadCursor"]={"hash":"f"*64,"slot":1}
        with self.assertRaises(ValueError): phase_progress(rows,"b","b"*64)
    def test_combined_event_byte_and_elapsed_bounds(self):
        a=self.rows("a")[-1]; b=self.rows("b")[-1]
        combined_bounds(a,b,119)
        for changed,elapsed in ((dict(b,events=128),119),(dict(b,returnedBytes=33554432),119),(b,121)):
            with self.assertRaises(ValueError): combined_bounds(a,changed,elapsed)
    def test_storage_uncertainty_resource_failure_and_byte_lie_stop(self):
        for change in ({"typedStop":"StorageFailure"},{"potentiallyOlderThanDisk":True},
                       {"externalReceiptStale":True},{"peerCloses":0},{"returnedBytes":11}):
            rows=self.rows("b"); rows[-1].update(change)
            with self.assertRaises(ValueError): phase_progress(rows,"b","b"*64)
    def test_exact_phase_endpoint_does_not_extend(self):
        runner=object.__new__(DurableNodeRunner)
        out=self.rows("a")[-1]; pre=dict(epoch=2,block=10)
        tip=dict(era="Conway",epoch=2,hash=out["scopedAppliedTip"]["hash"],slot=1040,block=12)
        runner.exact_phase_tip(tip,out,pre,2)
        for change in ({"epoch":3},{"hash":"f"*64},{"block":13}):
            with self.assertRaises(ValueError): runner.exact_phase_tip(dict(tip,**change),out,pre,2)


class DurableReviewGuards(unittest.TestCase):
    def test_checkpoint_token_binds_payload_and_trailer_not_whole_file_hash(self):
        payload=b"private validated checkpoint payload"
        digest=hashlib.sha256(payload).digest(); image=payload+digest
        result=checkpoint_binding(image,digest.hex())
        self.assertEqual(result["checkpointPayloadSha256"],digest.hex())
        self.assertEqual(result["checkpointTrailerDigest"],digest.hex())
        self.assertNotEqual(result["checkpointSha256"],digest.hex())
        for altered,pin in ((image[:-1]+b"x",digest.hex()),(b"X"+image[1:],digest.hex()),
                            (image,hashlib.sha256(image).hexdigest())):
            with self.assertRaises(ValueError): checkpoint_binding(altered,pin)

    def test_B_command_uses_remaining_aggregate_budgets_before_acquisition(self):
        allowance=remaining_allowance({"events":9,"returnedBytes":10000},61.9)
        self.assertEqual(allowance,{"event_budget":119,"byte_budget":33544432,"seconds":61})
        args=phase_arguments("resume",3001,"b"*64,"f"*64,**allowance)
        for key,value in (("--events","119"),("--bytes","33544432"),("--seconds","61")):
            self.assertEqual(args[args.index(key)+1],value)
        for previous,seconds in (({"events":125,"returnedBytes":10},40),
                                 ({"events":2,"returnedBytes":33554432},40),
                                 ({"events":2,"returnedBytes":10},0.9)):
            with self.assertRaises(ValueError): remaining_allowance(previous,seconds)

    def test_each_observed_download_is_checked_against_B_remaining_budget(self):
        runner=object.__new__(DurableNodeRunner); runner.phase="b"
        runner.phase_a_outcome={"events":5,"returnedBytes":33554422}
        runner.phase_allowance={"event_budget":123,"byte_budget":10,"seconds":60}
        rows=[dict(record="node-download",phase="announced",payloadBytes=4),
              dict(record="node-download",phase="fetched",payloadBytes=6)]
        runner.check_live_budget(rows,None)
        with self.assertRaises(ValueError): runner.check_live_budget(rows+[dict(rows[0])],None)
        with self.assertRaises(ValueError): runner.check_live_budget(rows,{"events":124})

    def test_successful_finish_keeps_active_restart_pause_deadline(self):
        runner=object.__new__(DurableNodeRunner); runner.phase="a"; runner.name="owned"
        runner.deadline=time.monotonic()+3; expected=runner.deadline
        runner.observer_attempted=True
        runner.identities={"a":{"Id":"owned-id"}}
        runner.save=lambda *args:None
        state={"Id":"owned-id","State":{"Running":False,"ExitCode":0,"OOMKilled":False}}
        calls=[]
        runner.docker=lambda *args,**kwargs:(calls.append((args,runner.deadline)) or types.SimpleNamespace(stdout=json.dumps([state])))
        runner.observer_logs=lambda:[]
        runner._finalize_observer=lambda:calls.append(("normal-cleanup",runner.deadline))
        def emergency(): self.fail("successful restart cleanup must not invoke deadline-clearing fallback")
        runner.finalize_observer=emergency
        runner.finish_phase()
        self.assertTrue(calls)
        self.assertTrue(all(deadline==expected for _,deadline in calls))
        self.assertEqual(runner.deadline,expected)
        self.assertFalse(runner.observer_attempted)


if __name__=="__main__": unittest.main()
