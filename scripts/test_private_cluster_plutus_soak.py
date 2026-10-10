import copy
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch
import private_cluster_plutus_soak as soak
import private_cluster_plutus_early_restart as restart
from test_private_cluster_plutus_early_restart import pin, H, OTHER, JOIN


PROCESS=dict(pid=1234,startedAt="2026-10-10T00:00:00Z",restartCount=0)


class SoakTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        disk=patch.object(soak.shutil,"disk_usage",return_value=SimpleNamespace(free=4*soak.base.GIB))
        disk.start();self.addCleanup(disk.stop)

    def test_complete_stage_plan_and_remaining_budget(self):
        plan=soak.stage_plan(600)
        self.assertEqual((plan["peerLifetime"],plan["restoredLifetime"],plan["latestFollowEnd"]),(720,660,750))
        self.assertEqual(plan["operationBudget"],1069)
        self.assertEqual((soak.MAX_OPERATION,soak.MAX_CLEANUP),(1080,30))
        with patch.object(soak.time,"monotonic",return_value=100):
            soak.require_budget(1170,1069,"whole plan")
            with self.assertRaises(ValueError):soak.require_budget(1169,1069,"whole plan")
        for target in (True,0,601):
            with self.assertRaises(ValueError):soak.stage_plan(target)

    def overlap_evidence(self):
        def result(start,end):
            return dict(soakProfile=soak.PROFILE,startedUnixMillis=start-100,endedUnixMillis=end+100,
                        followWindow=dict(schema="plutus-service-follow-window-v1",startedUnixMillis=start,
                                          endedUnixMillis=end,elapsedMonotonicNanos=(end-start)*1000000))
        return [result(1000,721000),result(61000,721000)],[dict(startedUnixMillis=2000,endedUnixMillis=3000),
                                                                      dict(startedUnixMillis=62000,endedUnixMillis=63000)]

    def test_overlap_uses_actual_loops_not_service_finalizer_lifetimes(self):
        results,obs=self.overlap_evidence()
        self.assertEqual(soak.actual_overlap(results,600,obs)["measuredOverlapMillis"],660000)
        for change in (dict(endedUnixMillis=600999),dict(elapsedMonotonicNanos=1),
                       dict(elapsedMonotonicNanos=662000000000),dict(startedUnixMillis=True),dict(schema="other")):
            bad=copy.deepcopy(results);bad[1]["followWindow"].update(change)
            with self.subTest(change=change),self.assertRaises(ValueError):soak.actual_overlap(bad,600,obs)
        bad=copy.deepcopy(results);bad[1]["soakProfile"]="other"
        with self.assertRaises(ValueError):soak.actual_overlap(bad,600,obs)
        bad=copy.deepcopy(results);bad[1]["followWindow"]=None
        with self.assertRaises(ValueError):soak.actual_overlap(bad,600,obs)
        # Individually long loops are insufficient if the start skew reduces overlap.
        bad=copy.deepcopy(results);bad[1]["followWindow"].update(startedUnixMillis=122000,endedUnixMillis=782000)
        bad[1]["endedUnixMillis"]=782100
        with self.assertRaises(ValueError):soak.actual_overlap(bad,600,obs)
        # Both individual loops exceed600s, but a permitted endpoint clock
        # discrepancy must not inflate an exact600s wall overlap into a pass.
        bad=copy.deepcopy(results)
        bad[0]["followWindow"].update(endedUnixMillis=661000,elapsedMonotonicNanos=659500000000)
        with self.assertRaises(ValueError):soak.actual_overlap(bad,600,obs)
        bad=copy.deepcopy(obs);bad[0]["startedUnixMillis"]=999
        with self.assertRaises(ValueError):soak.actual_overlap(results,600,bad)

    def test_peer_proofs_reject_owner_pid_and_progress_substitution(self):
        before=dict(schema="plutus-service-state-probe-v1",startedUnixMillis=1000,endedUnixMillis=1001,
                    resourcesFinalized=True,diagnosticOnly=True,fullLedgerValidated=False,containerId=H,process=PROCESS,state=dict(pin=pin()))
        after=copy.deepcopy(before);after.update(startedUnixMillis=2000,endedUnixMillis=2001)
        after["state"]["pin"]=pin(dict(hash=H,slot=20,blockNo=2),generation=1)
        soak.peer_probe(before,pin());soak.peer_survival(before,after)
        for mutate in (lambda b:b.update(process=dict(PROCESS,pid=5678)),lambda b:b.update(containerId=OTHER),lambda b:b["state"]["pin"].update(ownerId=OTHER),
                       lambda b:b.update(state=before["state"]),lambda b:b.update(startedUnixMillis=1000)):
            bad=copy.deepcopy(after);mutate(bad)
            with self.assertRaises(ValueError):soak.peer_survival(before,bad)

    def test_peer_live_guard_rejects_stopped_or_replaced_process(self):
        with patch.object(soak.two,"controller_type",return_value=object):cls=soak.controller_type(SimpleNamespace())
        c=object.__new__(cls);c.peer_process=PROCESS;c.peer_cid=H;c.containers={"service-1":H};c.service_roots={"service-1":self.root}
        c.owned=lambda cid:dict(RestartCount=0,State=dict(Running=True,OOMKilled=False,Pid=1234,StartedAt=PROCESS["startedAt"]))
        c.peer_alive()
        c.containers["service-1"]=OTHER
        with self.assertRaises(ValueError):c.peer_alive()
        c.containers["service-1"]=H;c.owned=lambda cid:dict(State=dict(Running=False,OOMKilled=False))
        with self.assertRaises(ValueError):c.peer_alive()

    def test_lifecycle_keeps_peer_before_through_and_after_restart(self):
        # Exercise the actual orchestration, replacing only process/I/O boundaries.
        with patch.object(soak.two,"controller_type",return_value=object):cls=soak.controller_type(SimpleNamespace(process=SimpleNamespace(PORTS={1:3001,2:3002})))
        c=object.__new__(cls);c.args=SimpleNamespace(duration_seconds=600,max_blocks=512)
        c.budget_plan=soak.stage_plan(600);c.operation_started=soak.time.monotonic();c.deadline=c.operation_started+1080
        c.exchange=self.root;c.out=self.root/"out";c.out.mkdir();c.initial=dict(hash=H,slot=10,blockNo=1);c.magic=42
        c.service_roots={p:self.root/p for p in soak.SERVICE_PHASES};c.containers={};events=[]
        c.owned=lambda cid:dict(RestartCount=0,State=dict(Running=True,OOMKilled=False,Pid=1234,StartedAt=PROCESS["startedAt"]))
        mode=dict(mode=soak.MODE,jvmComputed=True,researchOnly=True,activeStartsAfterPeerReady=True,nativeChecked=False,
                  nativeRuntimeDependency=False,lateCheckpointRestoreSupported=False,nativeExecutableSHA256=None,
                  nativeEvidenceDirectory=None,generationEvidenceDirectory="jvm-likelihood",maxEpochTransitions=8,startupTimeoutSeconds=30)
        point2=dict(hash=H,slot=20,blockNo=2)
        def ready(point,port,lifetime):
            return dict(schema="plutus-service-ready-v1",soakProfile=soak.PROFILE,initialPoint=point,profileId=soak.fixture.PROFILE,initialEpoch=0,
                        networkMagic=42,initialManifestSHA256=H,sourceJoinId=JOIN,apiPort=port,epochMode=mode,
                        limits=dict(durationSeconds=lifetime,maxBlocks=512,maxEvents=4096,maxEvaluationReceipts=128))
        peer=ready(c.initial,4001,720);restored_ready=ready(point2,4002,660)
        results,observations=self.overlap_evidence()
        def prepare(_):events.append("prepare");return H
        c.prepare_soak_initial=prepare
        def construct():
            events.append("funded-fixtures");d=self.root/"submission";d.mkdir()
            for i in (1,2):(d/f"transaction-{i}.cbor").write_bytes(b"offline")
        c.construct_transfer=construct
        def start(phase,root,manifest,**kw):
            self.assertEqual(phase,"service-1");events.append("peer-start");root.mkdir();c.containers[phase]=H
        c.start_repeated=start
        def wait(phase,name,seconds):
            if name=="bootstrap-ready.json":return peer
            return dict(schema="plutus-service-active-v1",epochMode=soak.MODE,durationSeconds=720,
                        startedUnixMillis=900,deadlineUnixMillis=720900,pin=pin())
        c.wait_service=wait
        def resume():events.append("producer-resume-once");c.last_resumed=900
        c.resume_producer=resume;c.signal_peer=lambda phase:events.append("signal-peer")
        def first(_):events.append("peer-checked-progress");return pin()
        c.first_peer_publication=first
        def probe(label,ready,previous):
            events.append(label)
            value=dict(observations[0 if label=="before-restart" else 1],containerId=H,process=PROCESS,state=dict(pin=pin()))
            if label=="after-restore":value["state"]["pin"]=pin(point2,generation=1)
            return value
        c.probe_peer=probe
        def recover(_):
            self.assertEqual(events[-1],"before-restart");self.assertEqual(c.containers["service-1"],H)
            events.extend(["checkpoint-service2","old-service2-removed","restored-service2"])
            c.service_roots["service-2"].mkdir();c.containers["service-2"]=OTHER
            c.actives["service-2"]=dict(startedUnixMillis=61000,deadlineUnixMillis=721000)
            return SimpleNamespace(ready=restored_ready,claim=dict(terminalPoint=point2,sourceJoinId=JOIN))
        c.restart_while_peer_follows=recover;c.run_client=lambda:events.append("client-after-rejoin")
        c.wait_full_intervals=lambda:results;c.verify_result=lambda *a:c.initial
        c.compare_service=lambda *a:events.append("oracle");c.stop_node=lambda n:events.append("stop-node")
        c.cleanup=lambda:events.append("cleanup")
        with patch.object(soak,"require_comparator"),patch.object(restart,"wait_checked_successor",side_effect=lambda *a,**kw:events.append("checked-rejoin")),patch.object(restart,"wait_exit"):
            c.same_epoch_lifecycle(None)
        self.assertEqual(events[:10],["prepare","funded-fixtures","peer-start","producer-resume-once","signal-peer",
                                     "peer-checked-progress","before-restart","checkpoint-service2","old-service2-removed","restored-service2"])
        self.assertLess(events.index("checked-rejoin"),events.index("after-restore"))
        self.assertLess(events.index("after-restore"),events.index("client-after-rejoin"))
        self.assertEqual(c.containers["service-1"],H)
        self.assertEqual(c.overlap["measuredOverlapMillis"],660000)
        self.assertEqual(events.count("producer-resume-once"),1)

    def test_restart_method_authenticates_pair_and_removes_only_old_slot_before_replacement(self):
        from test_private_cluster_plutus_early_restart import wire,ready,SOURCE,TERMINAL
        live=SimpleNamespace(process=SimpleNamespace(PORTS={1:3001,2:3002}))
        with patch.object(soak.two,"controller_type",return_value=object):cls=soak.controller_type(live)
        c=object.__new__(cls);c.args=SimpleNamespace(duration_seconds=600,max_blocks=512)
        c.exchange=self.root;c.out=self.root/"out";c.out.mkdir();c.manifest_pin=H;c.initial=SOURCE
        c.deadline=soak.time.monotonic()+1080;c.budget_plan=soak.stage_plan(600);c.magic=42;c.last_resumed=1000
        c.lifetimes={"service-1":720,"service-2":660};c.peer_process=PROCESS;c.peer_cid=H;c.containers={"service-1":H,"reference":JOIN}
        c.service_roots={p:self.root/p for p in soak.SERVICE_PHASES};c.service_roots["service-1"].mkdir()
        c.actives={"service-1":dict(startedUnixMillis=1000)};events=[];live_ids={H,JOIN}
        c.owned=lambda cid:dict(RestartCount=0,State=dict(Running=cid in live_ids,OOMKilled=False,Pid=1234,StartedAt=PROCESS["startedAt"]))
        raw=bytearray(wire());raw[8:40]=bytes.fromhex(restart.digest(b"a"*32));raw[40:72]=bytes.fromhex(restart.digest(b"b"*32))
        raw=bytes(raw);claim=restart.envelope_claim(raw)
        first=ready();second=ready(True,TERMINAL,OTHER,OTHER)
        request=dict(schema="plutus-service-checkpoint-publication-v1",checkpointFile="checkpoint.bin",checkpointBytes=len(raw),
                     claim=claim,restoreAuthorized=False,crashDurable=False,scope=restart.SCOPE)
        request_raw=restart.encoded(request)
        stopped=dict(schema="plutus-service-result-v1",status="stopped",stopReason="blockLimit",resourcesFinalized=True,
                     sourceJoinId=JOIN,initialManifestSHA256=H,fullLedgerValidated=False,transactionSuccessClaimed=False,
                     transportOpens=1,transportCloses=1,finalPin=pin(TERMINAL,generation=1),
                     boundedRestart=dict(checkpointFile="checkpoint.bin",checkpointSHA256=restart.digest(raw),
                                         checkpointRequestSHA256=restart.digest(request_raw),checkpointAfter=1))
        old="ef"*32;fresh="34"*32
        def launch(controller,live,phase,root,manifest,extra):
            self.assertEqual(phase,"service-2");self.assertIn(H,live_ids);self.assertEqual(c.args.duration_seconds,30)
            root.mkdir();(root/"checkpoint.bin").write_bytes(raw);(root/"checkpoint-request.json").write_bytes(request_raw)
            c.containers[phase]=old;live_ids.add(old);events.append("checkpoint-created")
        def wait(phase,name,seconds):
            self.assertIn(H,live_ids)
            if name=="bootstrap-ready.json":return first if c.containers[phase]==old else second
            if name=="result.json":live_ids.remove(old);events.append("checkpoint-exited");return stopped
            return dict(schema="plutus-service-active-v1",epochMode=soak.MODE,durationSeconds=660,
                        startedUnixMillis=21000,deadlineUnixMillis=681000)
        c.wait_service=wait
        def capture(point,name):
            self.assertEqual(point,TERMINAL);self.assertIn(H,live_ids);self.assertNotIn(old,live_ids)
            events.append("independent-acquisition")
            return self.root,dict(schema="native-live-acquisition-result-v1",acquireCount=1,reacquireCount=0,
                                 exactAcquiredPoint=True,referenceContainerId=JOIN,point=TERMINAL)
        c.capture=capture
        def remove(phase):
            self.assertEqual(phase,"service-2");self.assertNotIn(old,live_ids)
            self.assertTrue((c.out/"restart-authority.json").exists());events.append("old-removed")
        c.remove_service=remove
        def start(phase,root,manifest,extra,mounts,lifetime):
            self.assertEqual(events[-1],"old-removed");self.assertIn(H,live_ids)
            self.assertEqual(lifetime,660);self.assertEqual(c.args.duration_seconds,600)
            self.assertEqual(restart.base.decode((c.out/"restart-authority.json").read_bytes())["claim"],claim)
            root.mkdir();c.service_roots[phase]=root;c.containers[phase]=fresh;live_ids.add(fresh);events.append("replacement-created")
        c.start_repeated=start
        with patch.object(restart,"launch_service",side_effect=launch),patch.object(restart,"wait_exit"),             patch.object(soak.os,"urandom",side_effect=[b"a"*32,b"b"*32]):
            restored=c.restart_while_peer_follows(first)
        self.assertEqual(events,["checkpoint-created","checkpoint-exited","independent-acquisition","old-removed","replacement-created"])
        self.assertEqual(c.containers["service-1"],H);self.assertIn(H,live_ids)
        self.assertEqual(restored.output_root,self.root/"service-2-restored")
        self.assertEqual(restored.claim,claim)

    def test_serial_probes_and_client_keep_distinct_immutable_cleanup_evidence(self):
        with patch.object(soak.two,"controller_type",return_value=object):cls=soak.controller_type(SimpleNamespace(LABEL="owned"))
        c=object.__new__(cls);c.out=self.root;c.token="token";c.containers={};present=set();removed=[]
        def docker(*args,**kwargs):
            if args[0]=="ps":
                if "label=lab.zero-live.phase=ada-client" in args:return SimpleNamespace(stdout="\n".join(present))
                cid=next(a[3:] for a in args if a.startswith("id="))
                return SimpleNamespace(stdout=cid if cid in present else "")
            if args[0]=="logs":return SimpleNamespace(stdout=args[-1],stderr="")
            if args[0]=="rm":present.remove(args[-1]);removed.append(args[-1]);return SimpleNamespace(stdout="")
            raise AssertionError(args)
        c.docker=docker;c.owned=lambda cid:self.assertIn(cid,present)
        for cid in (H,OTHER,JOIN):
            c.containers["ada-client"]=cid;present.add(cid);c.remove_client();c.remove_client()
            record=restart.base.decode((self.root/("client-"+cid[:12]+"-cleanup.json")).read_bytes())
            self.assertEqual(record["containerId"],cid);self.assertTrue(record["absenceVerified"])
        self.assertEqual(removed,[H,OTHER,JOIN]);self.assertFalse(present)
        self.assertEqual(len(list(self.root.glob("client-*-cleanup.json"))),3)

    def test_maximum_planned_finalization_still_leaves_two_serial_endpoint_reserves(self):
        with patch.object(soak.two,"controller_type",return_value=object):cls=soak.controller_type(SimpleNamespace())
        c=object.__new__(cls);c.deadline=1080;c.budget_plan=soak.stage_plan(600)
        c.service_roots={p:self.root/p for p in soak.SERVICE_PHASES}
        clock=dict(startedUnixMillis=1000,deadlineUnixMillis=721000)
        c.actives={p:clock for p in soak.SERVICE_PHASES}
        value=dict(schema="plutus-service-result-v1",status="stopped",stopReason="durationLimit",startedUnixMillis=1000,
                   requestedDeadlineUnixMillis=721000,effectiveDeadlineUnixMillis=721000,endedUnixMillis=736000)
        for root in c.service_roots.values():root.mkdir();(root/"result.json").write_bytes(restart.encoded(value))
        # 120setup +750follow +15finalization.184seconds of terminal gates remain.
        with patch.object(soak.time,"monotonic",return_value=885):
            self.assertEqual(c.wait_full_intervals(),[value,value])
            soak.require_budget(c.deadline,184,"two endpoint gates")
        results,obs=self.overlap_evidence();results[1]["endedUnixMillis"]=results[1]["followWindow"]["endedUnixMillis"]+15001
        with self.assertRaises(ValueError):soak.actual_overlap(results,600,obs)

    def test_process_identity_rejects_restart_pid_and_start_time_substitution(self):
        original=dict(RestartCount=0,State=dict(Running=True,OOMKilled=False,Pid=1234,StartedAt=PROCESS["startedAt"]))
        self.assertEqual(soak.process_identity(original),PROCESS)
        for changed in (dict(original,RestartCount=1),dict(original,State=dict(original["State"],Pid=True)),
                        dict(original,State=dict(original["State"],StartedAt=""))):
            with self.assertRaises(ValueError):soak.process_identity(changed)
        with patch.object(soak.two,"controller_type",return_value=object):cls=soak.controller_type(SimpleNamespace())
        c=object.__new__(cls);c.peer_cid=H;c.peer_process=PROCESS;c.containers={"service-1":H};c.service_roots={"service-1":self.root}
        for changed in (dict(original,State=dict(original["State"],Pid=5678)),
                        dict(original,State=dict(original["State"],StartedAt="2026-10-10T00:01:00Z"))):
            c.owned=lambda cid,value=changed:value
            with self.assertRaises(ValueError):c.peer_alive()

    def test_peer_bootstrap_time_is_inside_setup_allowance_before_checkpoint(self):
        from unittest.mock import Mock
        with patch.object(soak.two,"controller_type",return_value=object):cls=soak.controller_type(SimpleNamespace())
        c=object.__new__(cls);c.args=SimpleNamespace(duration_seconds=600,max_blocks=512)
        c.operation_started=0;c.deadline=1080;c.budget_plan=soak.stage_plan(600);c.exchange=self.root;c.containers={"service-1":H}
        c.prepare_soak_initial=lambda approval:H;c.construct_transfer=lambda:None
        c.start_repeated=lambda *args,**kwargs:None;c.resume_producer=lambda:None;c.signal_peer=lambda phase:None
        c.first_peer_publication=Mock();clock=[0]
        def wait(phase,name,seconds):
            if name=="bootstrap-ready.json":return {}
            clock[0]=121
            return dict(schema="plutus-service-active-v1",epochMode=soak.MODE,durationSeconds=720,
                        startedUnixMillis=121000,deadlineUnixMillis=841000)
        c.wait_service=wait
        with patch.object(soak,"require_comparator"),patch.object(soak.time,"monotonic",side_effect=lambda:clock[0]):
            with self.assertRaisesRegex(ValueError,"peer bootstrap exhausted setup allowance"):
                c.same_epoch_lifecycle(None)
        c.first_peer_publication.assert_not_called()

    def generations(self, count=1):
        root = self.root/"jvm-likelihood"; root.mkdir()
        d = root/"generation-0000"; d.mkdir()
        pool = "11"*28
        rows = [pool+" 1 100 1"] if count else []
        request = ("conway-native-likelihood-v1\n"+H+"\n1000 1 20 0 1\n"+"".join(x+"\n" for x in rows)).encode()
        result = b"conway-jvm-likelihood-result-v1\n"+request+b"--jvm--\n"+(pool+" "+"0"*16+" "+"0"*800+"\n").encode()*count
        execution = ("jvm-likelihood-execution-v1\n"+restart.digest(request)+"\n"+restart.digest(result)+
                     f"\nPureJvm\n{100*count} {count}\n0 0\n").encode()
        for name, raw in (("request.txt", request), ("jvm-result.txt", result), ("execution.txt", execution)):
            (d/name).write_bytes(raw)
        return root

    def test_missing_comparator_refuses_before_controller_or_cluster_creation(self):
        with patch.object(soak,"controller_type") as construct:
            with self.assertRaises(NotImplementedError):soak.execute(None,None,None,None)
            construct.assert_not_called()

    def comparison_evidence(self):
        terminal=dict(hash=OTHER,slot=6010,blockNo=301)
        final=pin(terminal,generation=300)
        endpoint=dict(schema="native-endpoint-ready-v1",manifestSHA256=OTHER,acquisitionResultSHA256=JOIN)
        transfers=[]
        for index in (0,1):
            identity={k:f"{index+1:02x}"*32 for k in ("transactionId","envelopeSHA256","bodySHA256","witnessesSHA256")}
            transfers.append(dict(identity,spentInput=f"{H}#{2*index}",collateralInput=f"{H}#{2*index+1}"))
        value={k:True for k in soak.COMPARISON_TRUE_FIELDS}
        value.update({k:H for k in soak.COMPARISON_HASH_FIELDS})
        value.update(schema="plutus-repeated-service-endpoint-comparison-v1",terminalPoint=terminal,terminalPin=final,
                     sourceJoinId=JOIN,initialManifestSHA256=H,endpointManifestSHA256=OTHER,endpointAcquisitionResultSHA256=JOIN,
                     epoch=6,feesBefore=200000,feesAfter=0,entries=12,fullLedgerValidated=False,restartSupported=False,
                     transactions=[dict({k:t[k] for k in ("transactionId","envelopeSHA256","bodySHA256","witnessesSHA256")},
                                        spent=t["spentInput"],collateral=t["collateralInput"]) for t in transfers])
        return value,transfers,terminal,JOIN,H,copy.deepcopy(final),endpoint

    def test_repeated_comparison_uses_exact_new_schema_and_actual_fee_pots(self):
        evidence=self.comparison_evidence()
        self.assertEqual(soak.soak_comparison_result(*evidence),evidence[0])
        # Successful standalone contract checks cannot authorize a live launch.
        with self.assertRaises(NotImplementedError):soak.require_comparator()
        for field in soak.COMPARISON_FIELDS:
            changed=copy.deepcopy(evidence);del changed[0][field]
            with self.subTest(missing=field),self.assertRaises(ValueError):soak.soak_comparison_result(*changed)
        changed=copy.deepcopy(evidence);changed[0]["endpointSnapshotsUnchanged"]=True
        with self.assertRaises(ValueError):soak.soak_comparison_result(*changed)
        changed=copy.deepcopy(evidence);changed[0]["schema"]="plutus-service-endpoint-comparison-v1"
        with self.assertRaises(ValueError):soak.soak_comparison_result(*changed)

    def test_repeated_comparison_rejects_missing_components_and_broader_claims(self):
        for field in soak.COMPARISON_TRUE_FIELDS:
            for bad in (False,1,None):
                changed=self.comparison_evidence();changed[0][field]=bad
                with self.subTest(field=field,bad=bad),self.assertRaises(ValueError):soak.soak_comparison_result(*changed)
        for field in ("fullLedgerValidated","restartSupported"):
            changed=self.comparison_evidence();changed[0][field]=True
            with self.subTest(field=field),self.assertRaises(ValueError):soak.soak_comparison_result(*changed)

    def test_repeated_comparison_rejects_foreign_owner_state_and_endpoint_acquisition(self):
        for field in ("ownerId","coherentStateId","ledgerStateId","environmentId"):
            changed=self.comparison_evidence();changed[0]["terminalPin"][field]=OTHER
            with self.subTest(field=field),self.assertRaises(ValueError):soak.soak_comparison_result(*changed)
        for field in ("sourceJoinId","initialManifestSHA256","endpointManifestSHA256","endpointAcquisitionResultSHA256"):
            changed=self.comparison_evidence();changed[0][field]="ef"*32
            with self.subTest(field=field),self.assertRaises(ValueError):soak.soak_comparison_result(*changed)
        for field in soak.COMPARISON_HASH_FIELDS:
            changed=self.comparison_evidence();changed[0][field]="AB"*32
            with self.subTest(hash=field),self.assertRaises(ValueError):soak.soak_comparison_result(*changed)
        changed=self.comparison_evidence();changed[0]["terminalPoint"]=dict(changed[2],blockNo=302)
        with self.assertRaises(ValueError):soak.soak_comparison_result(*changed)

    def test_repeated_comparison_rejects_invented_epochs_pots_and_transaction_originals(self):
        for field,bad in (("epoch",0),("epoch",5),("epoch",8),("epoch",True),("feesBefore",-1),
                          ("feesAfter",True),("feesAfter",2**64),("entries",0),("entries",True),("entries",100001)):
            changed=self.comparison_evidence();changed[0][field]=bad
            with self.subTest(field=field,bad=bad),self.assertRaises(ValueError):soak.soak_comparison_result(*changed)
        for field in ("transactionId","envelopeSHA256","bodySHA256","witnessesSHA256","spent","collateral"):
            changed=self.comparison_evidence();changed[0]["transactions"][0][field]=OTHER
            with self.subTest(field=field),self.assertRaises(ValueError):soak.soak_comparison_result(*changed)
        changed=self.comparison_evidence();changed[0]["transactions"].reverse()
        with self.assertRaises(ValueError):soak.soak_comparison_result(*changed)
        changed=self.comparison_evidence();changed[0]["transactions"][1]=changed[0]["transactions"][0]
        with self.assertRaises(ValueError):soak.soak_comparison_result(*changed)

    def terminal_evidence(self, padding=0):
        final=pin(dict(hash=OTHER,slot=2010,blockNo=90),generation=89)
        value=dict(schema="plutus-repeated-service-terminal-observation-v1",diagnosticOnly=True,restartSupported=False,
                   fullLedgerValidated=False,pin=final,sourceJoinId=JOIN,initialManifestSHA256=H,epoch=2,validationSlot=2010,
                   outputMapFile="terminal-output-map.cbor",outputMapSHA256=H,componentPadding="x"*padding)
        return value,copy.deepcopy(final)

    def write_terminal(self, value):
        raw=restart.encoded(value);(self.root/"terminal-observation.json").write_bytes(raw)
        return dict(terminalObservationFile="terminal-observation.json",terminalObservationSHA256=restart.digest(raw))

    def test_terminal_reader_accepts_large_repeated_components_with_original_hash(self):
        value,final=self.terminal_evidence(1100000)
        result=self.write_terminal(value)
        self.assertEqual(soak.terminal_observation(self.root,result,final,JOIN,H),value)
        with self.assertRaises(ValueError):soak.terminal_observation(self.root,dict(result,terminalObservationSHA256=OTHER),final,JOIN,H)
        value["componentPadding"]="x"*soak.MAX_REPEATED_TERMINAL_BYTES
        result=self.write_terminal(value)
        with self.assertRaises(ValueError):soak.terminal_observation(self.root,result,final,JOIN,H)

    def test_terminal_reader_rejects_foreign_pins_legacy_schema_and_unbounded_filenames(self):
        for field,bad in (("schema","plutus-service-terminal-observation-v1"),("epoch",1),("epoch",True),
                          ("validationSlot",2011),("sourceJoinId",OTHER),("initialManifestSHA256",OTHER),
                          ("outputMapFile","../terminal-output-map.cbor"),("outputMapSHA256","not-a-hash"),
                          ("diagnosticOnly",False),("fullLedgerValidated",True),("restartSupported",True)):
            value,final=self.terminal_evidence();value[field]=bad;result=self.write_terminal(value)
            with self.subTest(field=field),self.assertRaises(ValueError):soak.terminal_observation(self.root,result,final,JOIN,H)
        value,final=self.terminal_evidence();value["pin"]["ownerId"]=OTHER;result=self.write_terminal(value)
        with self.assertRaises(ValueError):soak.terminal_observation(self.root,result,final,JOIN,H)
        value,final=self.terminal_evidence();final["generation"]=1;value["pin"]["generation"]=True;result=self.write_terminal(value)
        with self.assertRaises(ValueError):soak.terminal_observation(self.root,result,final,JOIN,H)
        value,final=self.terminal_evidence();result=self.write_terminal(value)
        with self.assertRaises(ValueError):soak.terminal_observation(self.root,dict(result,terminalObservationFile="../terminal-observation.json"),final,JOIN,H)
        (self.root/"terminal-observation.json").rename(self.root/"other.json")
        (self.root/"terminal-observation.json").symlink_to(self.root/"other.json")
        with self.assertRaises(ValueError):soak.terminal_observation(self.root,result,final,JOIN,H)

    def test_serial_comparison_joins_retained_originals_and_cleans_up_after_substitution(self):
        # Test the actual supervisor callsite. Docker and the independent Scala
        # comparator are fake boundaries; this is no ledger-parity evidence.
        for substitute in (False,True):
            with self.subTest(substitute=substitute):
                root=self.root/str(substitute);root.mkdir()
                exchange=root/"exchange";exchange.mkdir()
                initial=exchange/"initial";initial.mkdir();(initial/"effective-shelley-genesis.json").write_bytes(b"{}")
                service=exchange/"service-1";service.mkdir()
                packet=root/"packet";packet.mkdir()
                for name in soak.base.PACKET_NAMES:(packet/name).write_bytes(b"original fixture")
                out=root/"out";out.mkdir()
                value,transfers,terminal,join_id,manifest,final,_=self.comparison_evidence()
                observation,_=self.terminal_evidence();observation.update(pin=final,epoch=6,validationSlot=6010)
                output_map=b"original output map";observation["outputMapSHA256"]=restart.digest(output_map)
                raw=restart.encoded(observation);(service/"terminal-observation.json").write_bytes(raw)
                (service/"terminal-output-map.cbor").write_bytes(output_map)
                result=dict(finalPin=final,terminalObservationFile="terminal-observation.json",terminalObservationSHA256=restart.digest(raw))
                with patch.object(soak.two,"controller_type",return_value=object):kind=soak.controller_type(SimpleNamespace())
                c=object.__new__(kind);c.client_completed=True;c.containers={"service-1":H,"service-2":OTHER}
                c.service_roots={"service-1":service};c.exchange=exchange;c.out=out;c.manifest_pin=manifest;c.transfers=transfers
                c.deadline=soak.time.monotonic()+60
                c.args=SimpleNamespace(scala_build_root=root/"build",client_classpath_file=root/"classpath",java="java",scala_image="sha256:"+H)
                c.capture=lambda point,label:(packet,dict(point=point))
                inspected=[];calls=[]
                def create(role,args):
                    self.assertEqual(role,"service-1-oracle");self.assertIn("--network=none",args)
                    self.assertIn("lab.PlutusRepeatedServiceCompareMain",args)
                    mounts=[]
                    for index,argument in enumerate(args):
                        if argument=="--mount":
                            fields=dict(part.split("=",1) for part in args[index+1].split(",") if "=" in part)
                            mounts.append(dict(Type="bind",Source=fields["src"],Destination=fields["dst"],RW="readonly" not in args[index+1]))
                    inspected.append(dict(Mounts=mounts,State=dict(Running=False,ExitCode=0,OOMKilled=False)))
                    return JOIN
                c.create=create;c.owned=lambda cid:inspected[0] if cid==JOIN else dict(State=dict(Running=False))
                with patch.object(c,"owned",return_value=dict(State=dict(Running=True))):
                    with self.assertRaises(ValueError):c.compare_service("service-1",terminal,dict(sourceJoinId=join_id),result)
                self.assertEqual(inspected,[])
                def docker(*args,**kwargs):
                    calls.append(args)
                    if args[:2]==("start","--attach"):
                        value.update(terminalObservationSHA256=result["terminalObservationSHA256"],outputMapSHA256=observation["outputMapSHA256"],
                                     endpointManifestSHA256=soak.base.sha(service/"endpoint/endpoint-inputs.json"),
                                     endpointAcquisitionResultSHA256=soak.base.sha(service/"acquisition-result.json"))
                        (service/"service-comparison.json").write_bytes(restart.encoded(value))
                        if substitute:(service/"terminal-output-map.cbor").write_bytes(b"substituted after comparison")
                    return SimpleNamespace(stdout="",stderr="")
                c.docker=docker
                with patch.object(soak.base,"check_resources"),patch.object(soak.single,"checked_classpath",return_value="checked-cp"):
                    if substitute:
                        with self.assertRaises(ValueError):c.compare_service("service-1",terminal,dict(sourceJoinId=join_id),result)
                    else:c.compare_service("service-1",terminal,dict(sourceJoinId=join_id),result)
                self.assertIn(("rm","--force",JOIN),calls)
                self.assertTrue((out/"service-1-oracle-cleanup.json").is_file())
                self.assertEqual((out/"service-1-endpoint-comparison.json").is_file(),not substitute)

    def test_explicit_soak_limits_do_not_widen_existing_modes(self):
        for duration in (120,600): soak.soak_limits(duration,512)
        for duration, blocks in ((60,512),(601,512),(120,128),(True,512)):
            with self.assertRaises(ValueError): soak.soak_limits(duration,blocks)
        with self.assertRaises(ValueError): soak.single.service_limits(120,512)

    def test_pure_command_has_no_native_mount_or_flags_and_keeps_resource_ceiling(self):
        args=SimpleNamespace(duration_seconds=600,max_blocks=512,scala_build_root=self.root,java="java",scala_image="sha256:"+H)
        command=soak.repeated_args(args,self.root/"output",self.root/"initial","cp",H,3001,42,H)
        self.assertEqual(command[-4:],["--epoch-mode","repeated-jvm-v1","--soak-profile",soak.PROFILE])
        self.assertEqual(command[command.index("--duration-seconds")+1],"720")
        self.assertEqual(command[command.index("--max-blocks")+1],"512")
        self.assertIn("--cpus=0.5",command);self.assertIn("--memory=1g",command)
        self.assertFalse(any("native-likelihood" in x or "/oracle-libs" in x for x in command))

    def test_active_clock_and_result_require_complete_requested_interval(self):
        clock=dict(schema="plutus-service-active-v1",epochMode=soak.MODE,durationSeconds=600,startedUnixMillis=1000,deadlineUnixMillis=601000)
        soak.active(clock,600)
        result=dict(schema="plutus-service-result-v1",status="stopped",stopReason="durationLimit",startedUnixMillis=1000,
                    requestedDeadlineUnixMillis=601000,effectiveDeadlineUnixMillis=601000,endedUnixMillis=601001)
        soak.full_interval(result,clock)
        for change in (dict(endedUnixMillis=600999),dict(stopReason="blockLimit"),dict(startedUnixMillis=0),dict(effectiveDeadlineUnixMillis=60000)):
            with self.subTest(change=change),self.assertRaises(ValueError):soak.full_interval(dict(result,**change),clock)
        with self.assertRaises(ValueError):soak.active(dict(clock,deadlineUnixMillis=60000),600)

    def test_active_clock_cannot_belong_to_a_different_or_later_owner_state(self):
        first=pin(dict(hash=H,slot=20,blockNo=2),generation=1)
        final=pin(dict(hash=H,slot=30,blockNo=3),generation=2)
        soak.active_owner(dict(pin=pin()),first,final)
        with self.assertRaises(ValueError):soak.active_owner(dict(pin=pin(owner=OTHER)),first,final)
        with self.assertRaises(ValueError):soak.active_owner(dict(pin=final),first,final)

    def test_waiting_full_intervals_rejects_early_results_even_after_client_success(self):
        # Exercise supervisor method with no Docker/network; a client success
        # cannot convert an early service result into the requested duration.
        live=SimpleNamespace()
        with patch.object(soak.two,"controller_type",return_value=object): cls=soak.controller_type(live)
        controller=object.__new__(cls);controller.deadline=soak.time.monotonic()+1;controller.budget_plan=dict(terminalReserve=0)
        controller.service_roots={p:self.root/p for p in soak.SERVICE_PHASES}
        controller.actives={p:dict(startedUnixMillis=1,deadlineUnixMillis=600001) for p in soak.SERVICE_PHASES}
        for root in controller.service_roots.values():
            root.mkdir();(root/"result.json").write_bytes(restart.encoded(dict(schema="plutus-service-result-v1",status="stopped",
                stopReason="durationLimit",startedUnixMillis=1,requestedDeadlineUnixMillis=600001,effectiveDeadlineUnixMillis=600001,endedUnixMillis=5)))
        with self.assertRaises(ValueError):controller.wait_full_intervals()

    def test_nonempty_jvm_originals_are_hash_bound_without_native_parity(self):
        root=self.generations(); row=soak.jvm_generations(root)[0]
        self.assertEqual(row["computedRaw32Words"],100);self.assertEqual(row["nativeComparisons"],0)
        (root/"generation-0000/jvm-result.txt").write_bytes(b"substituted")
        with self.assertRaises(ValueError):soak.jvm_generations(root)

    def test_empty_go_has_valid_zero_computation_without_invented_native_match(self):
        row=soak.jvm_generations(self.generations(0))[0]
        self.assertEqual((row["computedRaw32Words"],row["computedRaw64Words"]),(0,0))

    def test_generation_extra_native_file_or_directory_gap_rejects(self):
        root=self.generations(); d=root/"generation-0000"
        (d/"response.txt").write_bytes(b"native")
        with self.assertRaises(ValueError):soak.jvm_generations(root)
        (d/"response.txt").unlink();d.rename(root/"generation-0001")
        with self.assertRaises(ValueError):soak.jvm_generations(root)

    def test_generation_symlink_cannot_substitute_other_evidence(self):
        root=self.generations(); d=root/"generation-0000"
        (self.root/"original").write_bytes((d/"request.txt").read_bytes());(d/"request.txt").unlink()
        (d/"request.txt").symlink_to(self.root/"original")
        with self.assertRaises(ValueError):soak.jvm_generations(root)

    def test_native_mount_option_requires_independent_complete_library_pin(self):
        binary=self.root/"binary";binary.write_bytes(b"test-only-not-executed");binary.chmod(0o700)
        libs=self.root/"libs";libs.mkdir();(libs/"a.so.1").write_bytes(b"lib");(libs/"a.so").symlink_to("a.so.1")
        record=restart.library_manifest(libs)
        mounts=restart.NativeRuntimeMounts(binary,restart.digest(binary.read_bytes()),libs,restart.digest(restart.encoded(record)))
        self.assertEqual(len(mounts.checked_mounts()),2)
        (libs/"a.so.1").write_bytes(b"changed")
        with self.assertRaises(ValueError):mounts.checked_mounts()

    def test_native_library_symlink_escape_rejects(self):
        libs=self.root/"libs";libs.mkdir();(self.root/"outside").write_bytes(b"x");(libs/"escape").symlink_to("../outside")
        with self.assertRaises(ValueError):restart.library_manifest(libs)

    def test_native_library_traversal_counts_empty_directories_before_sorting(self):
        libs=self.root/"libs";libs.mkdir();(libs/"a.so").write_bytes(b"x")
        for index in range(256):(libs/f"empty-{index:03d}").mkdir()
        with self.assertRaises(ValueError):restart.library_manifest(libs)

    def test_disk_and_log_budgets_fail_closed_and_missing_start_root_is_safe(self):
        with patch.object(soak.shutil,"disk_usage",return_value=SimpleNamespace(free=4*soak.base.GIB)):
            soak.tree_budget([self.root/"not-created-yet"])
        with patch.object(soak.shutil,"disk_usage",return_value=SimpleNamespace(free=soak.base.GIB)),self.assertRaises(ValueError):
            soak.tree_budget([self.root])
        (self.root/"x.log").write_bytes(b"12345")
        with patch.object(soak,"MAX_LOG_BYTES",4),self.assertRaises(ValueError):soak.tree_budget([self.root])
        with patch.object(soak,"MAX_TREE_BYTES",4),self.assertRaises(ValueError):soak.tree_budget([self.root])

    def test_stopped_inspection_race_rechecks_full_interval_result(self):
        with patch.object(soak.two,"controller_type",return_value=object):cls=soak.controller_type(SimpleNamespace())
        controller=object.__new__(cls);controller.deadline=soak.time.monotonic()+2;controller.budget_plan=dict(terminalReserve=0)
        controller.service_roots={p:self.root/p for p in soak.SERVICE_PHASES}
        controller.containers={p:p for p in soak.SERVICE_PHASES}
        clock=dict(startedUnixMillis=1000,deadlineUnixMillis=121000)
        controller.actives={p:clock for p in soak.SERVICE_PHASES}
        result=dict(schema="plutus-service-result-v1",status="stopped",stopReason="durationLimit",startedUnixMillis=1000,
                    requestedDeadlineUnixMillis=121000,effectiveDeadlineUnixMillis=121000,endedUnixMillis=121001)
        for root in controller.service_roots.values():root.mkdir()
        def stopped(phase):
            (controller.service_roots[phase]/"result.json").write_bytes(restart.encoded(result))
            return dict(State=dict(Running=False))
        controller.owned=stopped
        self.assertEqual(controller.wait_full_intervals(),[result,result])

    def test_receipt_race_rechecks_failure_precedence(self):
        with patch.object(soak.two,"controller_type",return_value=object):cls=soak.controller_type(SimpleNamespace())
        controller=object.__new__(cls);controller.deadline=soak.time.monotonic()+2;controller.budget_plan=dict(terminalReserve=0)
        controller.service_roots={"service-2":self.root};controller.containers={"service-2":"id"}
        def stopped(_):
            (self.root/"result.json").write_bytes(b"{}")
            (self.root/"failure.json").write_bytes(b"{}")
            return dict(State=dict(Running=False))
        controller.owned=stopped
        with self.assertRaises(ValueError):controller.wait_service("service-2","result.json",1)

    def test_monitor_stop_is_safe_before_start_and_releases_waiting_thread(self):
        monitor=soak.BudgetMonitor([self.root]);monitor.stop()
        monitor=soak.BudgetMonitor([self.root]);monitor.start();monitor.stop()
        self.assertFalse(monitor.thread.is_alive());self.assertIsNone(monitor.failure)

    def test_late_scan_failure_cannot_signal_after_monitor_shutdown(self):
        import threading
        entered=threading.Event();release=threading.Event()
        def delayed(_):
            entered.set();release.wait(5);raise ValueError("late failure")
        monitor=soak.BudgetMonitor([self.root])
        with patch.object(soak,"tree_budget",side_effect=delayed),patch.object(soak.os,"kill") as kill:
            monitor.start();self.assertTrue(entered.wait(1))
            # Simulate delayed filesystem I/O surviving the bounded shutdown
            # join; the serialized stop flag prevents a late process signal.
            with patch.object(monitor.thread,"join"):
                monitor.stop()
            release.set();monitor.thread.join(1)
            self.assertFalse(monitor.thread.is_alive());kill.assert_not_called()

    def test_long_fixture_changes_only_expiry_and_requires_explicit_profile(self):
        original=dict(build=("cli","transaction","build-raw","--invalid-hereafter","999","--fee","300000"),sign=("sign",))
        with patch.object(soak.soak_fixture.short,"spend_commands",return_value=original):
            result=soak.soak_fixture.spend_commands(None,None,None,None,0,False,profile="early-restart-two-service-soak-v1")
            self.assertEqual(result["build"],tuple("8000" if x=="999" else x for x in original["build"]))
            self.assertEqual(result["sign"],original["sign"]);self.assertEqual(original["build"][4],"999")
            with self.assertRaises(ValueError):soak.soak_fixture.spend_commands(None,None,None,None,0,False,profile="unknown")

    def test_replaced_slot_cleanup_recovers_interrupted_create_without_touching_old_or_other_ids(self):
        with patch.object(soak.two,"controller_type",return_value=object):
            cls=soak.controller_type(SimpleNamespace(LABEL="owned"))
        controller=object.__new__(cls);controller.out=self.root;controller.token="token";controller.containers={"service-1":"old"}
        events=[];present={"new"}
        def docker(*args,**kwargs):
            if args[0]=="ps":
                if "label=lab.zero-live.phase=service-1" in args:return SimpleNamespace(stdout="new" if "new" in present else "")
                identity=next(a[3:] for a in args if a.startswith("id="))
                return SimpleNamespace(stdout=identity if identity in present else "")
            if args[0]=="logs":return SimpleNamespace(stdout="bounded",stderr="")
            if args[0]=="rm":present.remove(args[-1]);events.append(args[-1]);return SimpleNamespace(stdout="")
            raise AssertionError(args)
        controller.docker=docker;controller.owned=lambda cid:self.assertEqual(cid,"new")
        controller.remove_service("service-1")
        self.assertEqual(events,["new"]);self.assertFalse(present)
        self.assertTrue((self.root/"service-1-new-cleanup.json").exists())

    def test_publications_bind_every_jvm_generation_and_reject_substitution(self):
        evidence_root=self.generations(0)
        record=soak.jvm_generations(evidence_root)[0]
        checked=dict(record,mode="pure-jvm",nativeResponseSHA256=None,nativeValidated=False,diagnosticNativeDependency=False,
                     raw32Comparisons=0,raw64Comparisons=0,jvmMismatchWords=0,preTickTupleId=H,observedSlot=1000,applicationEpoch=1)
        refs=[];observed=[]
        for index,slot in enumerate((20,1001)):
            state=pin(dict(hash=H,slot=slot,blockNo=index+2),generation=index+1)
            value=dict(schema="plutus-service-publication-v1",index=index,pin=state,sourceJoinId=JOIN,initialManifestSHA256=H,
                       profileId=soak.fixture.PROFILE,diagnosticOnly=True,fullLedgerValidated=False,included=[],
                       repeatedEpoch=dict(epoch=slot//1000,componentId=H,nonMyopicId=H,transitions=index,frozenId=H if index else None,
                                          checkedLikelihood=checked if index else None))
            name=f"publication-{index:04d}.json";raw=restart.encoded(value);(self.root/name).write_bytes(raw)
            refs.append(dict(file=name,sha256=restart.digest(raw),pin=state))
            observed.append(dict(publicationFile=name,publicationSHA256=restart.digest(raw),publication=value))
        result=dict(publications=refs,finalPin=refs[-1]["pin"])
        proof=soak.repeated_publications(self.root,result,observed,JOIN,H,{})
        self.assertEqual(proof["epochs"],[0,1]);self.assertFalse(proof["nativeParityChecked"])
        draft=evidence_root/"generation-0001";draft.mkdir()
        request=(evidence_root/"generation-0000/request.txt").read_bytes().replace(H.encode(),OTHER.encode())
        result_bytes=(evidence_root/"generation-0000/jvm-result.txt").read_bytes().replace(H.encode(),OTHER.encode())
        (draft/"request.txt").write_bytes(request);(draft/"jvm-result.txt").write_bytes(result_bytes)
        (draft/"execution.txt").write_text("jvm-likelihood-execution-v1\n"+restart.digest(request)+"\n"+restart.digest(result_bytes)+"\nPureJvm\n0 0\n0 0\n")
        proof=soak.repeated_publications(self.root,result,observed,JOIN,H,{})
        self.assertEqual(len(proof["selectedGenerations"]),1)
        self.assertEqual(len(proof["unselectedDrafts"]),1)
        self.assertEqual(proof["unselectedDrafts"][0]["frozenId"],OTHER)
        changed=copy.deepcopy(observed);changed[1]["publication"]["pin"]["ownerId"]=OTHER
        with self.assertRaises(ValueError):soak.repeated_publications(self.root,result,changed,JOIN,H,{})
        value=copy.deepcopy(observed[1]["publication"]);value["repeatedEpoch"]["checkedLikelihood"]["evidenceSHA256"]=OTHER
        raw=restart.encoded(value);(self.root/refs[1]["file"]).write_bytes(raw);refs[1]["sha256"]=restart.digest(raw)
        with self.assertRaises(ValueError):soak.repeated_publications(self.root,result,[],JOIN,H,{})

    def test_post_boundary_http_guard_rejects_pre_boundary_receipts_and_changed_owner(self):
        from test_private_cluster_plutus_two_service import example
        value,transfers=example()
        value.update(soakProfile=soak.PROFILE,secondSubmittedAfterEpochOne=True,
                     boundaryPins=[row["initialState"]["pin"] for row in value["endpoints"]])
        with self.assertRaises(ValueError):soak.soak_client_result(value,transfers,[31001,31002])
        # The underlying two-owner/original validation still precedes the new
        # epoch observations; a supplied boundary flag never grants admission.
        value["endpoints"][1]["observedOwnerId"]=value["endpoints"][0]["observedOwnerId"]
        with self.assertRaises(ValueError):soak.soak_client_result(value,transfers,[31001,31002])


if __name__ == "__main__":unittest.main()
