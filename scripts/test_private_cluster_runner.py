# SPDX-License-Identifier: Apache-2.0
import json
from pathlib import Path
import time
import types
import unittest
from unittest.mock import patch
from private_cluster import Runner
from private_cluster_sequence import SequenceRunner
from private_cluster_runner import (LiveRunner, LOG_LIMIT, records, progress, exact_post,
    capture_lines, live_report)


class LiveLauncherTests(unittest.TestCase):
    def ready(self):
        return dict(record="live-validator-ready",pointHash="a"*64,slot=1020,blockNo=10,
                    revision=0,retainedBlocks=0,intersectionAccepted=True,coordinatorChecked=True,
                    stateId="a"*64,scopedAppliedTip=None)

    def applied(self,index):
        return dict(record="live-validator-applied",pointHash=str(index)*64,slot=1020+index*10,
                    blockNo=10+index,revision=index,retainedBlocks=index,
                    transactionCount=2 if index==2 else 0,stateId=str(index)*64,
                    scopedAppliedTip={"pointHash":str(index)*64,"slot":1020+index*10})

    def stop(self):
        return dict(self.applied(4),record="live-validator-stop",typedStop="TargetReached",
            events=5,returnedBytes=20,reconnects=0,rollbackEvents=0,peerOpens=1,peerCloses=1,
            transportOpens=5,transportCloses=5,transportCounted=True,resourcesFinalized=True,
            announcedCursor={"pointHash":"4"*64,"slot":1060},fetchedCursor={"pointHash":"4"*64,"slot":1060})

    def rows(self):
        rows=[self.ready()]
        for index in range(1,5):
            previous=self.ready() if index==1 else self.applied(index-1)
            for phase,size in (("announced",2),("fetched",3)):
                rows.append(dict(record="live-validator-download",phase=phase,pointHash=str(index)*64,
                    slot=1020+index*10,payloadBytes=size,appliedClaim=False,
                    scopedAppliedTip=previous["scopedAppliedTip"],appliedRevision=index-1,
                    appliedStateId=previous["stateId"]))
            rows.append(self.applied(index))
        rows.extend(dict(record="transfer-range-block",headerEnvelopeHex="0001",rawBlockHex="020304") for _ in range(4))
        return rows+[self.stop()]

    def test_ready_after_intersection_precedes_actual_application(self):
        self.assertEqual(progress([self.ready()]),(True,[],None))
        self.assertEqual(len(progress(self.rows())[1]),4)
        for rows in ([self.applied(1),self.ready()], [self.ready(),self.ready()],
                     [dict(self.ready(),revision=1)], [self.ready(),self.applied(2)],
                     [dict(self.ready(),revision=False)], [dict(self.ready(),coordinatorChecked=False)],
                     [dict(self.ready(),intersectionAccepted=False)], [self.ready(),dict(self.applied(1),revision=True)]):
            with self.assertRaises(ValueError): progress(rows)
        download=dict(record="live-validator-download",phase="fetched",returnedBytes=800)
        self.assertEqual(progress([self.ready(),download]),(True,[],None))

    def test_terminal_reason_count_and_finalization_are_required(self):
        for change in ({"typedStop":"IdleTimeout"},{"resourcesFinalized":False},
                       {"peerCloses":0},{"transportCloses":1},{"reconnects":1},
                       {"retainedBlocks":3},{"pointHash":"e"*64}):
            with self.assertRaises(ValueError): progress(self.rows()[:-1]+[dict(self.stop(),**change)])
        with self.assertRaises(ValueError): progress(self.rows()[:-2]+[self.stop()])
        with self.assertRaises(ValueError): progress(self.rows()+[self.stop()])

    def test_download_trace_proves_scoped_cursor_does_not_advance_until_apply(self):
        rows=self.rows()
        self.assertEqual(progress(rows)[2],self.stop())
        for index,change in ((1,{"appliedRevision":1}),(2,{"scopedAppliedTip":self.applied(1)["scopedAppliedTip"]}),
                             (2,{"appliedStateId":"f"*64}),(2,{"pointHash":"f"*64}),
                             (1,{"payloadBytes":65536}),(2,{"payloadBytes":1048577}),
                             (1,{"appliedClaim":True}),(2,{"payloadBytes":True}),
                             (1,{"appliedRevision":False})):
            changed=[dict(row) for row in rows]; changed[index].update(change)
            with self.assertRaises(ValueError): progress(changed)
        changed=list(rows); changed[1],changed[2]=changed[2],changed[1]
        with self.assertRaises(ValueError): progress(changed)
        changed=list(rows); changed[1],changed[3]=changed[3],changed[1]
        with self.assertRaises(ValueError): progress(changed)
        changed=[dict(row) for row in rows]; changed[-1]["returnedBytes"]=21
        with self.assertRaises(ValueError): progress(changed)
        # In-progress polls may stop between announcement, fetch and publication.
        self.assertEqual(progress(rows[:2])[1],[])
        self.assertEqual(progress(rows[:3])[1],[])

    def test_initial_anchor_alignment_only_is_accepted_and_counted(self):
        rows=self.rows(); anchor=self.ready()
        rollback=dict(record="live-validator-download",phase="rollback-announced",
            pointHash=anchor["pointHash"],slot=anchor["slot"],payloadBytes=0,appliedClaim=False,
            scopedAppliedTip=None,appliedRevision=0,appliedStateId=anchor["stateId"])
        rows.insert(1,rollback); rows[-1]=dict(rows[-1],rollbackEvents=1)
        self.assertEqual(progress(rows)[2]["rollbackEvents"],1)
        for change in ({"pointHash":"e"*64},{"slot":1021},{"payloadBytes":1},
                       {"payloadBytes":False},{"appliedClaim":True},{"appliedRevision":1},
                       {"appliedStateId":"f"*64},{"scopedAppliedTip":self.applied(1)["scopedAppliedTip"]}):
            changed=[dict(row) for row in rows]; changed[1].update(change)
            with self.assertRaises(ValueError): progress(changed)
        for count in (0,2,True):
            changed=[dict(row) for row in rows]; changed[-1]["rollbackEvents"]=count
            with self.assertRaises(ValueError): progress(changed)
        late=list(rows); late.insert(5,late.pop(1))
        with self.assertRaises(ValueError): progress(late)
        repeated=list(rows); repeated.insert(2,rollback)
        with self.assertRaises(ValueError): progress(repeated)

    def tip(self,slot,block,hash):
        return dict(era="Conway",epoch=slot//500,slot=slot,slotInEpoch=slot%500,block=block,hash=hash)

    def test_exact_four_block_endpoint_no_extension_or_epoch_crossing(self):
        pre=self.tip(1020,10,"a"*64); post=self.tip(1060,14,"4"*64)
        self.assertEqual(exact_post(pre,post,self.stop())["expectedCompleteBlocks"],4)
        for change in ({"hash":"e"*64},{"block":15},{"slot":1061,"slotInEpoch":61},
                       {"slot":1520,"slotInEpoch":20,"epoch":3}):
            with self.assertRaises(ValueError): exact_post(pre,dict(post,**change),self.stop())

    def test_bounded_complete_lines_and_exact_capture_bytes(self):
        line='{"record":"transfer-range-block", "rawBlockHex":"aa"}\n'
        text=json.dumps(self.ready())+'\n'+line*4+'{"unfinished"'
        self.assertEqual(capture_lines(text),line*4)
        self.assertEqual(len(records(text)),5)
        with self.assertRaises(ValueError): capture_lines(line*3)
        with self.assertRaises(ValueError): capture_lines(line*5)
        with self.assertRaises(ValueError): records("x"*(LOG_LIMIT+1))

    def test_live_report_requires_online_tuple_and_no_overclaim(self):
        report=dict(scope="live-validator-observation",typedStop="TargetReached",passed=True,
            onlineBeforePostOracle=True,downloadCursorSeparate=True,finalTupleReferenceMatched=True,
            resourcesFinalized=True,fullLedgerValidated=False,consensusValidated=False,durableClaim=False,
            liveForkClaim=False,referenceSnapshotAtomic=False,authenticatedSnapshot=False,
            onlineRevision=4,offlineInitialPassRevision=4,finalStateId=self.stop()["stateId"],transportCounted=True,
            **{k:self.stop()[k] for k in ("peerOpens","peerCloses","transportOpens","transportCloses",
                                        "events","returnedBytes","reconnects","rollbackEvents")})
        self.assertEqual(live_report(report,self.stop()),report)
        for change in ({"onlineBeforePostOracle":False},{"finalTupleReferenceMatched":False},
                       {"durableClaim":True},{"liveForkClaim":True},{"consensusValidated":True},
                       {"onlineRevision":3},{"offlineInitialPassRevision":True},{"finalStateId":"0"*64},
                       {"events":6},{"returnedBytes":21},{"rollbackEvents":1},{"transportCounted":False}):
            with self.assertRaises(ValueError): live_report(dict(report,**change),self.stop())

    def fake(self):
        obj=object.__new__(LiveRunner); obj.deadline=time.monotonic()+100
        obj.name="owned-test"; obj.out=Path("/private-evidence")
        obj.args=types.SimpleNamespace(scala_repo="/private-build")
        obj.saved={}; obj.save=lambda k,v:obj.saved.update({k:v})
        return obj

    def test_detached_observer_owned_name_readonly_and_resource_caps(self):
        obj=self.fake(); calls=[]
        obj.docker=lambda *a,**kw:(calls.append((a,kw)) or types.SimpleNamespace(stdout="owned-id"))
        obj.start_observer(3001)
        args,kw=calls[0]
        for flag in ("-d","--cpus=1","--memory=1g","--memory-swap=1g","--read-only",
                     "--network=container:owned-test","--log-opt=max-size=12m"):
            self.assertIn(flag,args)
        self.assertEqual(args[args.index("--name")+1],"owned-test-scala")
        self.assertTrue(obj.observer_attempted)
        self.assertIn("live-validator 3001 /evidence",args[-1])
        self.assertIs(LiveRunner.run,Runner.run)
        self.assertIs(LiveRunner.paused,SequenceRunner.paused)

    def test_wait_timeout_or_premature_exit_cannot_be_ready(self):
        obj=self.fake(); obj.observer_logs=lambda:[]
        obj.observer_state=lambda:{"Running":False,"ExitCode":1}
        with self.assertRaises(ValueError): obj.await_progress(lambda p:p[0],1,"ready")
        obj.deadline=time.monotonic()-1
        with self.assertRaises(TimeoutError): obj.await_progress(lambda p:p[0],1,"ready")

    def test_cleanup_removes_owned_observer_even_if_final_logs_fail(self):
        obj=self.fake(); obj.observer_attempted=True; calls=[]
        def badlogs(): raise ValueError("lost logs")
        obj.observer_logs=badlogs
        obj.docker=lambda *a,**kw:(calls.append(a) or types.SimpleNamespace(returncode=0,stdout="removed",stderr=""))
        obj.finalize_observer()
        self.assertEqual(calls,[("rm","-f","owned-test-scala")])
        self.assertIn("live-observer-final-read-error.md",obj.saved)

    def test_launch_failure_still_finalizes_owned_observer(self):
        obj=self.fake(); calls=[]
        def fails(): obj.observer_attempted=True; raise KeyboardInterrupt()
        obj.live_sequence=fails; obj.finalize_observer=lambda:calls.append("finalized")
        with self.assertRaises(KeyboardInterrupt): obj.scala()
        self.assertEqual(calls,["finalized"])

    def test_log_rewrite_truncation_rejects_instead_of_accepting_download_tip(self):
        obj=self.fake(); text=json.dumps(self.ready())+'\n'
        obj.docker=lambda *a,**kw:types.SimpleNamespace(returncode=0,stdout=text,stderr="")
        self.assertEqual(len(obj.observer_logs()),1)
        obj.docker=lambda *a,**kw:types.SimpleNamespace(returncode=0,stdout="",stderr="")
        with self.assertRaises(ValueError): obj.observer_logs()


if __name__ == "__main__": unittest.main()
