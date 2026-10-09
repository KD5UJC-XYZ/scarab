#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Synthetic guard fixtures only; these do not manufacture valid Cardano evidence."""
import copy
import hashlib
import json
from pathlib import Path
import unittest
from unittest.mock import patch
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

if __name__=="__main__": unittest.main()
