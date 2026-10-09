#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Synthetic guard fixtures only; these do not manufacture valid Cardano evidence."""
import copy
import hashlib
import json
from pathlib import Path
import unittest
import os
import signal
import subprocess
import tempfile
import time
from unittest.mock import patch, Mock
from types import SimpleNamespace
import private_cluster_sustained_durable as m

CONTEXT="aa"*32
STORE="bb"*32
BINDING=m.binding(CONTEXT,STORE)

def fixture(phase):
    start,end=m.STARTS[phase],m.TARGETS[phase]
    def p(depth): return dict(hash=f"{depth+1:064x}",slot=10+depth)
    def row(depth,gen,kind,session="01"*32,compacted=None):
        compacted=(0 if kind=="volatile" else max(1,depth-2)) if compacted is None else compacted
        identity=f"{1000+depth*30+compacted:064x}"
        value=dict(contextId=CONTEXT,stateId=identity,revision=depth,depth=depth,compactedBlocks=compacted,
            retainedBlocks=depth-compacted,derivedAnchorId=None if kind=="volatile" else f"{200+compacted:064x}",
            scopedAppliedTip=p(depth) if depth else None,blockNo=50+depth,confirmation=kind,
            confirmedGeneration=None if kind=="volatile" else gen,receiptPath=None,receiptSha256=None)
        if kind!="volatile":
            value.update(storageVersion="v2",trustedLocalPrefix=phase=="b",storageBinding=copy.deepcopy(BINDING),
                fullClaim=dict(token=dict(storeId=STORE,contextId=CONTEXT,sessionId=session,generation=str(gen),digest=f"{9000+gen:064x}"),
                    format=m.FORMAT,profile=m.PROFILE,authority=m.AUTHORITY,anchorId=f"{3000+compacted:064x}",
                    finalId=identity,compactedBlocks=str(compacted),revision=str(depth)))
            if kind=="v2-acknowledged": value.update(receiptPath="/receipts-"+phase+f"/{gen}.json",receiptSha256=f"{5000+gen:064x}")
        return value
    def projection(v): return dict(tupleId=v["stateId"],contextId=CONTEXT,tip=dict(hash=p(v["depth"])["hash"],slot=str(p(v["depth"])["slot"])))
    kind="volatile" if phase=="seed" else "v2-loaded-verified" if phase=="b" else "v2-acknowledged"
    boot=row(start,13 if phase=="b" else 0,kind)
    boot.update(record="node-bootstrap",mode="bounded-volatile" if phase=="seed" else "sustained-durable",auditEnabled=True,
                suppliedAnchor=p(0),retainedAnchor=p(max(1,start-2)) if phase!="seed" else p(0),potentiallyOlderThanDisk=False,externalReceiptStale=False)
    rows=[boot]
    if phase=="b": rows.append(dict(copy.deepcopy(boot),record="node-loaded",projection=projection(boot)))
    tip=p(start)
    rows += [dict(record="node-intersection-offered",offeredPoints=[tip],acquisitionOnly=True,appliedClaim=False),
             dict(record="node-intersection-selected",selectedPoint=tip,offeredMatch=True,acquisitionOnly=True,appliedClaim=False)]
    ready=dict(copy.deepcopy(boot),record="node-rollback",initialIntersection=True,projection=projection(boot))
    rows.append(ready); previous=ready; charged=0
    for d in range(start+1,end+1):
        for stage in ("announced","fetched"):
            rows.append(dict(copy.deepcopy(previous),record="node-download",phase=stage,payloadBytes=1,downloadCursor=p(d),downloadIsApplied=False))
            charged+=1
        rows.append(dict(record="transfer-range-block",headerEnvelopeHex="ab",rawBlockHex="cd",acquisitionOnly=True,appliedClaim=False))
        session="02"*32 if phase=="b" else "01"*32
        if phase!="seed" and d>=4:
            advance=row(d-1,previous["confirmedGeneration"]+1,"v2-acknowledged",session,previous["compactedBlocks"]+1)
            rows.append(dict(advance,record="node-anchor-advance"))
        pub=row(d,0 if phase=="seed" else m.generation(d),"volatile" if phase=="seed" else "v2-acknowledged",session)
        pub.update(record="node-applied",transactionCount=2 if phase=="b" and d==10 else 0)
        rows.append(pub); previous=pub
    rows.append(dict(record="node-state",projection=projection(previous),revision=str(end),depth=str(end),
                     compactedBlocks=str(previous["compactedBlocks"]),derivedAnchorId=previous["derivedAnchorId"]))
    terminal=dict(copy.deepcopy(previous),scope="bounded-node-outcome",typedStop="TargetReached",scopedTargetReached=True,
        peerResourcesFinalized=True,cleanupFailure=None,potentiallyOlderThanDisk=False,externalReceiptStale=False,
        peerOpens=1,peerCloses=1,reconnects=0,events=end-start,returnedBytes=charged,caughtUp=False,
        fullLedgerValidated=False,consensusValidated=False,stateDerivedConsensus=False)
    terminal.pop("record"); rows.append(terminal)
    return rows

class SustainedDurableTests(unittest.TestCase):
    def test_explicit_seed_create_resume_grammar_and_resource_bounds(self):
        seed=dict(sha256="cc"*32,point=dict(hash="dd"*32,slot=10))
        for phase in m.TARGETS:
            args=m.phase_arguments(phase,3001,CONTEXT,STORE,120,128,33554432,seed if phase=="a" else None)
            self.assertEqual(args[args.index("--blocks")+1],str(m.TARGETS[phase]))
            self.assertNotIn("--resume-receipt",args); self.assertNotIn("--resume-sha256",args)
            if phase=="b": self.assertNotIn("--seed-capture",args)
            if phase!="seed": self.assertEqual(args[args.index("--journal")+1],"/journal")
        for seconds,event,byte in ((121,128,100),(120,129,100),(0,128,100),(120,128,33554433)):
            with self.assertRaises(ValueError): m.phase_arguments("b",3001,CONTEXT,STORE,seconds,event,byte)
        with self.assertRaises(ValueError): m.phase_arguments("b",3001,CONTEXT,STORE,120,128,100,seed)
        with self.assertRaises(ValueError): m.phase_arguments("a",3001,CONTEXT,STORE,120,128,100)

    def test_reference_resource_override_is_narrow_and_scala_is_unchanged(self):
        runner=object.__new__(m.SustainedDurableRunner); runner.name="owned"
        with patch.object(m.DurableNodeRunner,"docker",return_value=None) as called:
            runner.docker("run","--name","owned","--cpus=3","--memory=6g","--memory-swap=6g")
            self.assertEqual(called.call_args.args,("run","--name","owned","--cpus=2","--memory=2g","--memory-swap=2g"))
            runner.docker("run","--name","owned-scala","--cpus=1","--memory=1g")
            self.assertEqual(called.call_args.args,("run","--name","owned-scala","--cpus=1","--memory=1g"))

    def test_synthetic_complete_phase_guards_and_generation_arithmetic(self):
        for phase in m.TARGETS:
            ready,applied,out=m.progress(fixture(phase),phase,CONTEXT,BINDING)
            self.assertTrue(ready); self.assertEqual(len(applied),m.TARGETS[phase]-m.STARTS[phase])
            if phase!="seed": self.assertEqual(out["confirmedGeneration"],13 if phase=="a" else 19)

    def test_full_loaded_claim_and_projection_before_peer_no_new_export(self):
        a,b=fixture("a"),fixture("b")
        m.checked_loaded(a,b)
        for field in ("sessionId","digest","generation"):
            changed=copy.deepcopy(b); loaded=m.one(changed,"node-loaded")
            loaded["fullClaim"]["token"][field]="ff"*32
            with self.subTest(field=field),self.assertRaises(ValueError): m.checked_loaded(a,changed)
        for field,value in (("projection",{}),("receiptPath","/fake"),("receiptSha256","cc"*32)):
            changed=copy.deepcopy(b); m.one(changed,"node-loaded")[field]=value
            with self.assertRaises(ValueError): m.checked_loaded(a,changed)

    def test_compaction_cannot_change_revision_or_skip_generation(self):
        for phase in ("a","b"):
            for key in ("revision","depth","compactedBlocks","retainedBlocks","confirmedGeneration"):
                rows=fixture(phase); row=next(r for r in rows if r.get("record")=="node-anchor-advance"); row[key]+=1
                with self.subTest(phase=phase,key=key),self.assertRaises(ValueError): m.progress(rows,phase,CONTEXT,BINDING)

    def test_compaction_identity_trust_and_uncertainty_are_not_weakened(self):
        for phase in ("a","b"):
            for key,value in (("stateId","bad"),("derivedAnchorId","bad"),("trustedLocalPrefix",phase!="b"),
                              ("potentiallyOlderThanDisk",True),("externalReceiptStale",True),("receiptSha256",None)):
                rows=fixture(phase); row=next(r for r in rows if r.get("record")=="node-anchor-advance"); row[key]=value
                with self.subTest(phase=phase,key=key),self.assertRaises(ValueError): m.progress(rows,phase,CONTEXT,BINDING)
        for phase in ("a","b"):
            rows=fixture(phase); rows[0]["trustedLocalPrefix"]=phase!="b"
            with self.assertRaises(ValueError): m.progress(rows,phase,CONTEXT,BINDING)

    def test_alignment_is_exact_noop_with_charged_event(self):
        for change in (None,"claim","generation","late","uncharged"):
            rows=fixture("b"); initial=m.one(rows,"node-rollback")
            announced=dict(copy.deepcopy(initial),record="node-download",phase="rollback-announced",downloadCursor=rows[0]["scopedAppliedTip"],downloadIsApplied=False,payloadBytes=0)
            checked=copy.deepcopy(initial); checked["initialIntersection"]=False
            i=rows.index(initial)+1; rows[i:i]=[announced,checked]; rows[-1]["events"]+=1
            if change=="claim": announced["fullClaim"]["anchorId"]="ff"*32
            elif change=="generation": checked["confirmedGeneration"]+=1
            elif change=="late": rows.remove(announced); rows.remove(checked); rows[-2:-2]=[announced,checked]
            elif change=="uncharged": rows[-1]["events"]-=1
            if change is None: self.assertTrue(m.progress(rows,"b",CONTEXT,BINDING)[0])
            else:
                with self.subTest(change=change),self.assertRaises(ValueError): m.progress(rows,"b",CONTEXT,BINDING)

    def test_resume_successor_cannot_reuse_loaded_old_session(self):
        rows=fixture("b")
        for row in rows:
            if row.get("confirmation")=="v2-acknowledged": row["fullClaim"]["token"]["sessionId"]="01"*32
        with self.assertRaises(ValueError): m.progress(rows,"b",CONTEXT,BINDING)

    def test_download_cannot_publish_or_lose_exact_original_bytes(self):
        for change in ("state","claim","bytes","order","extra"):
            rows=fixture("a"); downloaded=next(r for r in rows if r.get("phase")=="fetched")
            if change=="state": downloaded["revision"]+=1
            elif change=="claim": downloaded["fullClaim"]["anchorId"]="ff"*32
            elif change=="bytes": next(r for r in rows if r.get("record")=="transfer-range-block")["rawBlockHex"]="00ff"
            elif change=="extra": rows.insert(-2,copy.deepcopy(downloaded))
            else: rows.remove(downloaded); rows.insert(0,downloaded)
            with self.subTest(change=change),self.assertRaises(ValueError): m.progress(rows,"a",CONTEXT,BINDING)

    def test_failure_scope_or_endpoint_uncertainty_never_passes(self):
        for key,value in (("typedStop","TimeBudget"),("peerResourcesFinalized",False),("cleanupFailure","failed"),
                          ("potentiallyOlderThanDisk",True),("externalReceiptStale",True),("fullLedgerValidated",True)):
            rows=fixture("b"); rows[-1][key]=value
            with self.subTest(key=key),self.assertRaises(ValueError): m.progress(rows,"b",CONTEXT,BINDING)

    def test_derived_anchor_never_relabels_original_supplied_anchor(self):
        rows=fixture("b"); rows[0]["retainedAnchor"]=copy.deepcopy(rows[0]["suppliedAnchor"])
        with self.assertRaises(ValueError): m.progress(rows,"b",CONTEXT,BINDING)

    def test_exact_supplied_and_retained_anchor_binding(self):
        rows=fixture("a"); boot=rows[0]
        m.checked_bootstrap(rows,boot["suppliedAnchor"],boot["retainedAnchor"])
        for key in ("suppliedAnchor","retainedAnchor"):
            wrong=copy.deepcopy(rows); wrong[0][key]["slot"]+=1
            with self.subTest(key=key),self.assertRaises(ValueError):
                m.checked_bootstrap(wrong,boot["suppliedAnchor"],boot["retainedAnchor"])

    def test_diagnostic_receipt_is_exact_full_claim_not_resume_authority(self):
        row=fixture("a")[-1]
        artifact=dict(format="node-v2-acknowledged-v1",diagnosticOnly=True,capacity=2,binding=BINDING,claim=row["fullClaim"])
        original=json.dumps(artifact).encode(); pin=hashlib.sha256(original).hexdigest()
        self.assertEqual(m.receipt(original,pin,row,BINDING),artifact)
        with self.assertRaises(ValueError): m.receipt(original+b" ",pin,row,BINDING)
        for key,value in (("diagnosticOnly",False),("capacity",True),("format","node-durable-acknowledged-v1"),("extra",1)):
            modified=dict(artifact,**{key:value}); raw=json.dumps(modified).encode()
            with self.assertRaises(ValueError): m.receipt(raw,hashlib.sha256(raw).hexdigest(),row,BINDING)

    def test_duplicate_nonfinite_and_oversize_json_rejects(self):
        for raw in (b'{"x":1,"x":2}',b'{"x":NaN}',b'\xff',b'{}'*5000):
            with self.assertRaises((ValueError,UnicodeError)): m.parse(raw,8192)
        self.assertEqual(m.records('{"x":1}\n{"partial":'),[{"x":1}])


class EndpointFreezeTests(unittest.TestCase):
    def hint(self,height=96):
        return json.dumps(dict(ns="ChainDB.AddBlockEvent.AddedToCurrentChain",data=dict(kind="AddedToCurrentChain",
            newSuffixSelectView=dict(blockNo=height,slotNo=1887),newtip="ab"*32+"@1887"))).encode()

    def test_exact_hint_is_only_accepted_with_frozen_same_epoch_query(self):
        hint=m.endpoint_hint(self.hint(),96)
        tip=dict(hint,era="Conway",epoch=3)
        m.endpoint_tip(tip,hint,dict(epoch=3))
        for key,value in (("block",97),("slot",1888),("hash","cd"*32),("epoch",4),("block",True)):
            with self.subTest(key=key,value=value), self.assertRaises(ValueError):
                m.endpoint_tip(dict(tip,**{key:value}),hint,dict(epoch=3))

    def test_hint_rejects_overshoot_missing_malformed_duplicate_and_byte_overrun(self):
        for raw in (self.hint(97),self.hint(95),b"{}",b"{",self.hint().replace(b'96',b'true'),
                    self.hint().replace(b'"blockNo": 96',b'"blockNo":96,"blockNo":96'),b" "*65537):
            with self.subTest(raw=raw[:50]), self.assertRaises((ValueError,TypeError)):
                m.endpoint_hint(raw,96)

    def test_owned_paused_identity_rejects_foreign_reused_or_running_process(self):
        stat="10 (cardano-node) "+" ".join(["T"]+["0"]*18+["99"])
        command="/opt/reference/bin/cardano-node\0run\0--database-path\0/work/env/node-data/node1/db\0"
        identity=m.producer_identity(10,stat,command,1)
        self.assertEqual(identity["startTicks"],"99")
        self.assertEqual(identity["commandSha256"],hashlib.sha256(command.encode()).hexdigest())
        for pid,st,cmd,node in ((11,stat,command,1),(10,stat.replace(") T", ") R"),command,1),
                              (10,stat,command,2),(10,stat,command.replace("cardano-node","other"),1)):
            with self.assertRaises(ValueError): m.producer_identity(pid,st,cmd,node)

    def test_pause_budget_uses_container_elapsed_not_clock_origin(self):
        self.assertEqual(m.endpoint_deadline(50,[900000,904000],100),64)
        self.assertEqual(m.endpoint_deadline(50,[900000,904000],60),60)
        for sample in ([1,20001],[10,9],[True,2],[1],[-1,2]):
            with self.assertRaises(ValueError): m.endpoint_deadline(50,sample,100)

    def runner(self):
        r=object.__new__(m.SustainedDurableRunner); r.deadline=100; r.online_deadline=100
        r.save=Mock(); r.pause_evidence=Mock(); r.query=Mock(return_value=dict(block=96,slot=1887,hash="ab"*32,era="Conway",epoch=3))
        return r

    def test_frozen_query_precedes_body_and_deadline_restores_after_body_error(self):
        r=self.runner(); order=[]
        def execute(*args,**kw):
            order.append("clock" if args[0]=="/bin/sh" else "hint")
            return SimpleNamespace(stdout="1000\n5.00\n" if args[0]=="/bin/sh" else self.hint().decode())
        r.execute=execute
        r.query.side_effect=lambda *_: (order.append("query") or dict(block=96,slot=1887,hash="ab"*32,era="Conway",epoch=3))
        with patch.object(m.time,"monotonic",return_value=50):
            with self.assertRaisesRegex(RuntimeError,"body"):
                with r.frozen_endpoint(dict(block=84,epoch=3)):
                    self.assertEqual(order,["clock","hint","query"])
                    self.assertEqual(r.deadline,64)
                    raise RuntimeError("body")
        self.assertEqual(r.deadline,100)

    def test_partial_pause_or_failed_watcher_never_enters_body(self):
        for status in ("done 95\n","1000\n21.00\n"):
            r=self.runner(); r.execute=Mock(return_value=SimpleNamespace(stdout=status))
            with patch.object(m.time,"monotonic",return_value=50), self.assertRaises(ValueError):
                with r.frozen_endpoint(dict(block=84,epoch=3)): self.fail("must not enter")
            r.query.assert_not_called()
        r=self.runner(); r.execute=Mock(side_effect=[SimpleNamespace(stdout="1000\n2.00\n"),SimpleNamespace(stdout=self.hint().decode())])
        r.pause_evidence.side_effect=ValueError("partial pause")
        with patch.object(m.time,"monotonic",return_value=50), self.assertRaisesRegex(ValueError,"partial pause"):
            with r.frozen_endpoint(dict(block=84,epoch=3)): self.fail("must not enter")

    def test_release_failure_still_attempts_both_identity_bound_resumes(self):
        r=self.runner(); r.endpoint_armed=True
        r.endpoint_identities=[dict(pid=i,startTicks="99",commandSha256="ab"*32) for i in (10,11)]
        def execute(*args,**kw):
            if args[0]=="touch": raise RuntimeError("release write")
            return SimpleNamespace(returncode=0)
        r.execute=Mock(side_effect=execute)
        with self.assertRaisesRegex(RuntimeError,"release write"): r.release_endpoint()
        calls=r.execute.call_args_list
        self.assertEqual(len(calls),3)
        self.assertEqual([call.args[4] for call in calls[1:]],["10","11"])
        self.assertTrue(all("kill -CONT" in call.args[2] for call in calls[1:]))


@unittest.skipUnless(os.name=="posix" and Path("/proc/uptime").exists(),"Linux shell/process smoke")
class EndpointShellTests(unittest.TestCase):
    def smoke(self,mode):
        # Synthetic children only. No reference process, key, DB, socket or Docker.
        children=[]; watcher=None
        with tempfile.TemporaryDirectory(prefix="v2-watch-test-") as directory:
            base=Path(directory)/"endpoint"; log=Path(directory)/"relay.log"; log.write_text("")
            script=m.ENDPOINT_WATCH.replace("/work/v2-endpoint",str(base)).replace("/work/env/logs/node3/stdout.log",str(log))
            if mode=="partial": script=script.replace('kill -STOP "$p1" "$p2"','kill -STOP "$p1"; exit 97')
            if mode=="watchdog": script=script.replace("frozen + 19000","frozen + 200")
            args=["96"]
            def state(child): return Path(f"/proc/{child.pid}/stat").read_text().rsplit(") ",1)[1].split()
            def wait_for(predicate):
                until=time.monotonic()+3
                while time.monotonic()<until:
                    if predicate(): return
                    time.sleep(.005)
                self.fail("bounded synthetic watcher wait")
            try:
                for _ in range(2):
                    child=subprocess.Popen(["sleep","10"],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
                    children.append(child); os.kill(child.pid,signal.SIGSTOP)
                    wait_for(lambda:state(child)[0]=="T")
                    args += [str(child.pid),state(child)[19],hashlib.sha256(Path(f"/proc/{child.pid}/cmdline").read_bytes()).hexdigest()]
                args.append("1")
                with (Path(directory)/"watcher.out").open("wb") as output:
                    watcher=subprocess.Popen(["/bin/sh","-c",script,"endpoint-test",*args],stdout=output,stderr=output)
                    wait_for(lambda:Path(str(base)+".armed").exists())
                    for child in children: os.kill(child.pid,signal.SIGCONT)
                    if mode!="missing":
                        event=dict(ns="ChainDB.AddBlockEvent.AddedToCurrentChain",data=dict(kind="AddedToCurrentChain",
                            newSuffixSelectView=dict(blockNo=96,slotNo=1887),newtip="ab"*32+"@1887"))
                        with log.open("a") as stream: stream.write(json.dumps(event,separators=(",",":"))+"\n"); stream.flush()
                    if mode in ("normal","watchdog"):
                        wait_for(lambda:Path(str(base)+".frozen").exists())
                        self.assertTrue(all(state(child)[0]=="T" for child in children))
                        m.endpoint_hint(Path(str(base)+".hint").read_bytes(),96)
                        if mode=="normal": Path(str(base)+".release").touch()
                    code=watcher.wait(timeout=3)
                self.assertEqual(code,{"normal":0,"missing":94,"partial":97,"watchdog":96}[mode])
                self.assertEqual(Path(str(base)+".done").read_text(),str(code)+"\n")
                self.assertTrue(all(state(child)[0]!="T" for child in children))
                self.assertFalse(Path(f"/proc/{watcher.pid}").exists())
                self.assertLess((Path(directory)/"watcher.out").stat().st_size,4096)
            finally:
                if watcher is not None and watcher.poll() is None:
                    watcher.terminate(); watcher.wait(timeout=2)
                for child in children:
                    if child.poll() is None:
                        os.kill(child.pid,signal.SIGCONT); child.terminate()
                    child.wait(timeout=2)

    def test_real_shell_exact_trigger_stop_both_release_and_exit(self): self.smoke("normal")
    def test_real_shell_missing_trigger_times_out_without_orphans(self): self.smoke("missing")
    def test_real_shell_partial_stop_trap_resumes_both(self): self.smoke("partial")
    def test_real_shell_watchdog_resumes_when_controller_does_not_release(self): self.smoke("watchdog")


class ReadinessBudgetTests(unittest.TestCase):
    def inputs(self,relative=42,now=54.2,case=500):
        genesis=dict(epochLength=500,slotLength=.1,securityParam=5,activeSlotsCoeff=.05,
                     systemStart="1970-01-01T00:00:00Z")
        tip=dict(era="Conway",epoch=1,slot=500+relative,slotInEpoch=relative,slotsToEpochEnd=500-relative,
                 block=20,hash="ab"*32)
        return [genesis,[copy.deepcopy(tip) for _ in range(3)],now,now,now,100.,100.,case]

    def test_exact_forty_five_and_complete_case_boundary(self):
        args=self.inputs(now=55,case=389)
        plan=m.readiness_budget(*args)
        self.assertEqual(plan["productionDeadline"],144)
        self.assertEqual(plan["fixedCaseReserve"],245)
        self.assertEqual(sum(m.PAUSE_CAPS.values())+plan["minimumProductionOpportunitySeconds"]+1,45)
        self.assertEqual(plan["onlineDeadline"],179)
        self.assertFalse(plan["guaranteedBlockArrivals"])
        for index,value in ((3,55.001),(7,388.999)):
            changed=copy.deepcopy(args); changed[index]=value
            with self.assertRaises(ValueError): m.readiness_budget(*changed)

    def test_relative_33_34_42_admitted_by_fresh_clock_stale_tip_rejected(self):
        for relative in (33,34,42):
            plan=m.readiness_budget(*self.inputs(relative,50+relative/10))
            self.assertGreater(plan["remainingEpochSeconds"],45)
            with self.assertRaises(ValueError): m.readiness_budget(*self.inputs(relative,60))
        with self.assertRaises(ValueError): m.readiness_budget(*self.inputs(42,101))

    def test_geometry_and_nonfinite_or_skewed_clock_rejected(self):
        args=self.inputs()
        for key,value in (("epoch",2),("slotInEpoch",43),("slotsToEpochEnd",1),("slot",True)):
            changed=copy.deepcopy(args)
            for tip in changed[1]: tip[key]=value
            with self.assertRaises(ValueError): m.readiness_budget(*changed)
        for index,value in ((2,float("nan")),(3,53),(4,60),(5,float("inf")),(6,104)):
            changed=copy.deepcopy(args); changed[index]=value
            with self.assertRaises(ValueError): m.readiness_budget(*changed)

    def test_query_latency_charged_and_deadline_cannot_reset(self):
        fast=m.readiness_budget(*self.inputs(relative=30,now=53))
        slow=self.inputs(relative=30,now=53); slow[3]=54; slow[6]=101
        plan=m.readiness_budget(*slow)
        self.assertEqual(plan["productionDeadline"],fast["productionDeadline"]-1)
        with self.assertRaises(ValueError):
            m.stage_deadline(131,plan["onlineDeadline"],18,plan["productionDeadline"]-14)

    def test_slow_bracket_cannot_claim_twenty_seconds_of_forging(self):
        args=self.inputs(now=52); args[3]=55; args[6]=103
        with self.assertRaisesRegex(ValueError,"minimum production"):
            m.readiness_budget(*args)

    def test_accumulated_phase_reserves_and_stage_expiry(self):
        plan=m.readiness_budget(*self.inputs(now=55))
        self.assertEqual(m.stage_deadline(100,179,18,plan["productionDeadline"]-14),118)
        self.assertEqual(m.stage_deadline(126,179,40,plan["productionDeadline"]-9),135)
        self.assertEqual(m.stage_deadline(140,179,20,plan["productionDeadline"]),144)
        with self.assertRaises(ValueError): m.stage_deadline(144,179,20,144)
        self.assertEqual(m.stage_deadline(178,179,15,179),179)

    def runner(self):
        r=object.__new__(m.SustainedDurableRunner); r.deadline=179
        r.budget=dict(productionDeadline=144,caseDeadline=389); r.save=Mock()
        r.read=Mock(side_effect=["10","11"])
        def execute(*args,**kwargs):
            pid=10 if "/10/" in args[1] else 11; node=pid-9
            value=(str(pid)+" (cardano-node) "+" ".join(["S"]+["0"]*18+["99"])) if args[1].endswith("stat") else "/opt/reference/bin/cardano-node\0run\0--database-path\0/work/env/node-data/node"+str(node)+"/db\0"
            return SimpleNamespace(stdout=value)
        r.execute=execute
        return r

    def test_pause_cleanup_included_and_outer_deadline_restored(self):
        r=self.runner(); clock=[100.]; r.signal_owned=Mock()
        with patch.object(m.time,"monotonic",side_effect=lambda:clock[0]):
            with r.paused("pre"):
                self.assertEqual(r.deadline,109)
                clock[0]=108
            self.assertEqual(r.deadline,179)
        self.assertEqual([x.args[1] for x in r.signal_owned.call_args_list],["STOP","CONT"])
        self.assertEqual(r.save.call_args.args[1]["elapsedSeconds"],8)

    def test_slow_cont_fails_total_cap_and_body_error_still_resumes(self):
        for body_failure in (False,True):
            r=self.runner(); clock=[100.]
            def signal_owned(identities,action):
                if action=="CONT": clock[0]=111
            r.signal_owned=Mock(side_effect=signal_owned)
            with patch.object(m.time,"monotonic",side_effect=lambda:clock[0]):
                with self.assertRaises((ValueError,RuntimeError)):
                    with r.paused("pre"):
                        clock[0]=108
                        if body_failure: raise RuntimeError("body expired")
            self.assertEqual(r.deadline,179)
            self.assertEqual(r.signal_owned.call_args.args[1],"CONT")

    def test_first_cont_failure_still_attempts_second_owned_identity(self):
        r=self.runner(); r.execute=Mock(side_effect=[RuntimeError("first"),SimpleNamespace(returncode=0)])
        identities=[dict(pid=i,startTicks="99",commandSha256="ab"*32) for i in (10,11)]
        with self.assertRaisesRegex(ValueError,"10"): r.signal_owned(identities,"CONT")
        self.assertEqual(len(r.execute.call_args_list),2)

    def test_expired_business_deadline_does_not_suppress_either_cont(self):
        r=self.runner(); r.deadline=1
        observed=[]
        def execute(*args,**kwargs):
            observed.append(r.deadline)
            if len(observed)==1: raise TimeoutError("first cleanup")
            return SimpleNamespace(returncode=0)
        r.execute=execute
        identities=[dict(pid=i,startTicks="99",commandSha256="ab"*32) for i in (10,11)]
        with self.assertRaises(ValueError): r.signal_owned(identities,"CONT")
        self.assertEqual(observed,[None,None])
        self.assertEqual(r.deadline,1)

    def test_interrupt_during_first_cont_preserved_after_second_attempt(self):
        r=self.runner(); r.execute=Mock(side_effect=[KeyboardInterrupt("cancel"),SimpleNamespace(returncode=0)])
        identities=[dict(pid=i,startTicks="99",commandSha256="ab"*32) for i in (10,11)]
        with self.assertRaisesRegex(KeyboardInterrupt,"cancel"): r.signal_owned(identities,"CONT")
        self.assertEqual(len(r.execute.call_args_list),2)
        self.assertEqual(r.deadline,179)

    def test_authoritative_frozen_query_after_production_deadline_rejected(self):
        r=EndpointFreezeTests().runner(); r.budget=dict(productionDeadline=49)
        r.execute=Mock(return_value=SimpleNamespace(stdout="1000\n2.00\n"))
        with patch.object(m.time,"monotonic",return_value=50), self.assertRaises(TimeoutError):
            with r.frozen_endpoint(dict(block=84,epoch=3)): self.fail("late freeze")
        r.query.assert_not_called()

if __name__=="__main__": unittest.main()
