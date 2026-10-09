# SPDX-License-Identifier: Apache-2.0
import copy
import json
from pathlib import Path
import time
import types
import unittest
from private_cluster_node import (NodeRunner, TARGET, CAPACITY, PROFILE, LOG_LIMIT,
    node_command, node_progress, exact_post, audit_report, json_records)
from private_cluster_runner import LiveRunner


class NodeLauncherTests(unittest.TestCase):
    def point(self,i): return {"hash":format(i+1,"064x"),"slot":1020+i*10}
    def state(self,i):
        return dict(contextId="a"*64,stateId=format(i+100,"064x"),revision=i,depth=i,
            compactedBlocks=max(0,i-4),derivedAnchorId=format(i+200,"064x") if i>4 else None,
            retainedBlocks=min(i,4),scopedAppliedTip=self.point(i) if i else None,blockNo=10+i)
    def rows(self,alignment=False):
        bootstrap=dict(self.state(0),record="node-bootstrap",sourceBound=True,bootstrapValidated=False,suppliedAnchor=self.point(0))
        ready=dict(self.state(0),record="node-rollback",initialIntersection=True)
        rows=[bootstrap,ready]
        if alignment:
            rows += [dict(self.state(0),record="node-download",phase="rollback-announced",downloadCursor=self.point(0),payloadBytes=0,downloadIsApplied=False),
                     dict(self.state(0),record="node-rollback",initialIntersection=False)]
        for i in range(1,13):
            for phase,size in (("announced",2),("fetched",3)):
                rows.append(dict(self.state(i-1),record="node-download",phase=phase,downloadCursor=self.point(i),payloadBytes=size,downloadIsApplied=False))
            rows.append(dict(record="transfer-range-block",headerEnvelopeHex="0001",rawBlockHex="020304",acquisitionOnly=True,appliedClaim=False))
            if i>4:
                rows.append(dict(self.state(i-1),record="node-anchor-advance",retainedBlocks=3,compactedBlocks=i-4,derivedAnchorId=self.state(i)["derivedAnchorId"]))
            rows.append(dict(self.state(i),record="node-applied",transactionCount=2 if i==2 else 0))
        projection={"tupleId":self.state(12)["stateId"],"contextId":"a"*64,
                    "tip":{"hash":self.point(12)["hash"],"slot":str(self.point(12)["slot"])},
                    "appliedTip":{"hash":self.point(12)["hash"],"slot":str(self.point(12)["slot"])}}
        rows.append(dict(record="node-state",projection=projection,revision="12",depth="12",compactedBlocks="8",derivedAnchorId=self.state(12)["derivedAnchorId"]))
        rows.append(dict(self.state(12),scope="bounded-node-outcome",typedStop="TargetReached",scopedTargetReached=True,
            peerResourcesFinalized=True,caughtUp=False,mode="sustained-volatile",auditEnabled=True,rollbackCapacity=4,
            peerOpens=1,peerCloses=1,reconnects=0,events=13,returnedBytes=60,fullLedgerValidated=False,
            consensusValidated=False,stateDerivedConsensus=False,durable=False,bootstrapValidated=False))
        return rows

    def test_actual_main_node_configuration(self):
        command=node_command(3001)
        self.assertIn("lab.Main node --profile "+PROFILE,command)
        for argument in ("--mode sustained-volatile","--blocks 12","--rollback-capacity 4","--seconds 120",
                         "--events 128","--bytes 33554432","--reconnects 0","--audit true"):
            self.assertIn(argument,command)
        for port in (True,0,65536):
            with self.assertRaises(ValueError): node_command(port)

    def test_twelve_publications_four_retained_eight_compactions(self):
        for alignment in (False,True):
            ready,applied,terminal=node_progress(self.rows(alignment))
            self.assertTrue(ready); self.assertEqual(len(applied),12)
            self.assertEqual((terminal["revision"],terminal["depth"],terminal["retainedBlocks"],terminal["compactedBlocks"]),(12,12,4,8))

    def test_download_does_not_publish_and_anchor_advance_does_not_advance_tip(self):
        rows=self.rows()
        for kind,change in (("node-download",{"stateId":"f"*64}),
                            ("node-download",{"payloadBytes":65536}),
                            ("node-download",{"downloadIsApplied":True}),
                            ("node-anchor-advance",{"revision":5}),
                            ("node-anchor-advance",{"scopedAppliedTip":self.point(5)}),
                            ("node-applied",{"retainedBlocks":True})):
            altered=copy.deepcopy(rows); next(r for r in altered if r.get("record")==kind).update(change)
            with self.assertRaises(ValueError): node_progress(altered)
        altered=copy.deepcopy(rows); altered[2],altered[3]=altered[3],altered[2]
        with self.assertRaises(ValueError): node_progress(altered)
        self.assertEqual(node_progress(rows[:4])[1],[])

    def test_only_initial_exact_anchor_alignment_is_supported(self):
        for change in ({"downloadCursor":self.point(1)},{"payloadBytes":1},{"revision":1}):
            rows=self.rows(True); rows[2].update(change)
            with self.assertRaises(ValueError): node_progress(rows)
        rows=self.rows(True); rows.insert(8,rows.pop(2))
        with self.assertRaises(ValueError): node_progress(rows)

    def test_projection_counter_byte_and_finalization_bindings(self):
        for change in ({"returnedBytes":61},{"peerCloses":0},{"reconnects":1},{"typedStop":"TimeBudget"},
                       {"durable":True},{"revision":20},{"compactedBlocks":7},{"depth":True}):
            rows=self.rows(); rows[-1].update(change)
            with self.assertRaises(ValueError): node_progress(rows)
        rows=self.rows(); rows[-2]["projection"]["tupleId"]="f"*64
        with self.assertRaises(ValueError): node_progress(rows)
        rows=self.rows(); rows[-2]["revision"]="20"
        with self.assertRaises(ValueError): node_progress(rows)

    def tip(self,i):
        p=self.point(i)
        return dict(hash=p["hash"],slot=p["slot"],epoch=2,slotInEpoch=p["slot"]%500,block=10+i,era="Conway")
    def test_post_point_exact_same_epoch_no_extension(self):
        pre=self.tip(0); post=self.tip(12); out=self.rows()[-1]
        self.assertEqual(exact_post(pre,post,out)["expectedCompleteBlocks"],12)
        for change in ({"hash":"f"*64},{"block":23},{"epoch":3},{"slot":1520}):
            with self.assertRaises(ValueError): exact_post(pre,dict(post,**change),out)

    def test_audit_exact_pair_grouping_and_full_projection_required(self):
        out=self.rows()[-1]; pair=[{"transactionId":"a"*64},{"transactionId":"b"*64}]
        report=dict(scope="node-audit",capturedBlocks=12,transactionCount=2,emptyBlocks=11,transactionBlockIndex=1,
            transactionIdsInBlockOrder=["b"*64,"a"*64],passed=True,completeProjectionMatched=True,
            referencePostStateMatched=True,sameEpoch=True,distinctForwardHashes=True,fullLedgerValidated=False,
            consensusValidated=False,liveForkClaim=False,durableClaim=False,finalStateId=out["stateId"],
            contextId=out["contextId"],derivedAnchorId=out["derivedAnchorId"],revision="12",depth="12",compactedBlocks="8")
        self.assertEqual(audit_report(report,out,pair),report)
        for change in ({"emptyBlocks":10},{"transactionIdsInBlockOrder":["a"*64,"a"*64]},
                       {"completeProjectionMatched":False},{"revision":"20"},{"liveForkClaim":True}):
            with self.assertRaises(ValueError): audit_report(dict(report,**change),out,pair)

    def fake(self):
        obj=object.__new__(NodeRunner); obj.deadline=time.monotonic()+100
        obj.name="owned-node"; obj.out=Path("/private")
        obj.args=types.SimpleNamespace(scala_repo="/build"); obj.saved={}
        obj.save=lambda k,v:obj.saved.update({k:v})
        return obj
    def test_owned_observer_limits_and_cleanup_preserve_ordinary_node_logs(self):
        obj=self.fake(); calls=[]
        obj.docker=lambda *a,**kw:(calls.append(a) or types.SimpleNamespace(stdout="id",stderr="",returncode=0))
        obj.start_observer(3001)
        for flag in ("--cpus=1","--memory=1g","--memory-swap=1g","--log-opt=max-size=32m","--read-only"):
            self.assertIn(flag,calls[0])
        self.assertIn("lab.Main node ",calls[0][-1])
        obj.observer_phase="audit"; obj.observer_logs()
        self.assertNotIn("node-stdout.md",obj.saved)
        self.assertIn("node-audit-final.stdout.md",obj.saved)
        self.assertIs(NodeRunner.scala,LiveRunner.scala)
        self.assertIs(NodeRunner.finalize_observer,LiveRunner.finalize_observer)

    def test_bounded_stdout_and_partial_line(self):
        self.assertEqual(json_records('{"record":"x"}\n{"partial"'),[{"record":"x"}])
        with self.assertRaises(ValueError): json_records("x"*(LOG_LIMIT+1))


if __name__=="__main__": unittest.main()
