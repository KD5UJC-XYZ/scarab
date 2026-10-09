#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Reviewed-source candidate: bounded graceful ordinary-node durable create/resume acceptance."""
import hashlib
import json
import re
import argparse
import os
from pathlib import Path
import shlex
import signal
import time
from private_cluster import Runner, JDK
from private_cluster_coherent import FIXTURE
from private_cluster_relay import RelayRunner, events
from private_cluster_transfer import converged_tips
from private_cluster_sequence import SequenceRunner, PRE_PINS, ORACLE_PINS, pair_admissions
from private_cluster_runner import LiveRunner
from private_cluster_node import json_records, integer, point, STATE_FIELDS
from private_cluster_node import PROFILE

PLAN = """# Bounded durable ordinary-node restart acceptance — proposed

This is a review-ready launcher design. Independent source/launcher
review and passing offline checks must precede any live run. Do not alter or resume a past run.

## Scope and resource ownership

Use one fresh supported-genesis private reference cluster (3 CPU / 6 GiB) and at most one
Scala process (1 CPU / 1 GiB) at a time. Keep the existing 480-second workload and absolute
600-second cleanup deadline, with pre/post pauses at most 20 seconds and submission pause
at most 8 seconds. A, B and the final offline audit run sequentially. No retries or endpoint
extension. This is bounded durable mode, capacity 8, cumulative targets A=2 and B=4;
no anchor advancement or durable compaction, epoch transition, live fork or power-loss claim.

## Before starting A

Prepare the two independent supported key transactions while producers run, but submit neither.
Await existing relay readiness and early-epoch convergence. In the existing pre pause, retain
the seven supplied bootstrap sources, full stable-tip/producer brackets, and raw ledger CBOR
side-export described below. Pin bootstrap context and reference config/genesis/image identity.
Start ordinary node A with create mode; require checked initial intersection before CONT.

## A -> B graceful transition

A must publish exactly two EMPTY live successors with acknowledged durable confirmations.
After finalized target A=2, pause producers promptly; require the relay tip equal A's tip.
Require A exit 0, no OOM, peer/store finalizers complete, and lock release before B starts.
Capture A stdout, full token-free projection, container/process identity and store-byte digest.
Copy the exact acknowledged artifact selected by A's final receiptPath/receiptSha256 into an
external controller directory and force file+directory; validate its explicit context/token.
Never choose a token by scanning the store, promote a pending artifact, or use last disk token
as an external acknowledgement. If any storage outcome is uncertain, stop and preserve it.

Use distinct writable receipts-a and receipts-b directories. The checkpoint store is shared
sequentially; the independently retained A acknowledged receipt is mounted read-only for B.
Pending records remain phase-local and are not restart authority. B resumes with the exact
receipt path, SHA256 and expected context. B's node-loaded full projection must equal A's
last acknowledged projection before any peer action. Its generation/receipt identity must
match A, classification loaded-verified. Initial anchor no-op retains that classification;
it cannot manufacture acknowledgement or increment generation.

## Live continuation after resume

After B's checked intersection readiness, CONT. Queue the prebuilt pair during the separate
short relay-only submission pause; retain each exact signed transaction, submission timing
and relay admission. Resume promptly. Require B to fetch and apply two NEW original successors
from the unchanged live reference cluster to cumulative target 4; recovery of A's retained
originals is not live-continuation evidence. The pair must occur together in one new block;
the other new block is empty. A's two original headers/blocks are exact preserved prefix.
Any epoch change, missed grouping, extra endpoint block or target race fails without retry.

At B's finalized target, use the existing post pause and require exact tip equality. Export
full JSON/UTxO CBOR/protocol state and raw ledger CBOR in that same bracket; CONT before
cryptographic replay/audit. Preserve B's acknowledged receipt, projection, process exit and
checkpoint digest separately. A final read-only, no-network audit must compare all four live
originals, complete recovered/derived state, reference endpoint UTxO/fee/nonces/counters and
actual pair grouping. No audit success means no completed restart acceptance.

## Raw full-ledger CBOR side-export

Inside each existing pre/post stopped-producer bracket, query a tip immediately before and
after `cardano-cli conway query ledger-state --testnet-magic 1082026 --socket-path
/work/env/socket/node3/sock --output-text --out-file /work/pre-ledger-state.cbor` (post uses
the post label). Preserve binary via Docker file copy, never stdout UTF-8 conversion. Bound
the file to 4 MiB, hash exact bytes, and include original tips, protocol export, configuration,
genesis, CLI binary/version, reference image and command in the private provenance receipt.
The export must fit the existing pause deadline; late/unsupported/changed-tip results fail,
with no resumed-producer side capture or larger pause. Retain existing separate-acquisition
qualification. JSON possibleRewardUpdate:null is not evidence of absent reward state.

Installed CLI help was checked offline in the pinned reference image: command elapsed about
0.30 seconds, network none, no node socket. Help confirms output-text/out-file flags; this
is not a runtime query-duration guarantee. Pinned CLI source eac27b8b0437a80cea2152917850aadea5749d90
Query/Run.hs ledgerStateAsTextByteString returns unSerialised bytes unchanged. Raw compatibility
with the independently built native epoch exporter remains to be checked against this new
capture. Preserve the evidence; no exporter/epoch-stake integration in this milestone.

## Proposed ownership

Node owner supplies bounded-durable CLI, loaded/acknowledged distinctions and immutable receipt
records. Runner owner supplies backend lifecycle/publication correctness. This lane owns only
the new durable launcher, pure guards and bounded orchestration after schemas/review.
Independent reviewer clears source, resource bounds, evidence and publication separately.
Main owns final integration, one-slot live dispatch and tested/reviewed publication.
"""

HEX = re.compile(r"[0-9a-f]{64}\Z")
DEC = re.compile(r"0|[1-9][0-9]*\Z")


def require(condition, message):
    if not condition: raise ValueError(message)


def acknowledged_receipt(raw, expected_sha256, expected_context):
    require(isinstance(raw, bytes) and 0 < len(raw) <= 4096, "bounded original receipt bytes required")
    require(isinstance(expected_sha256,str) and HEX.fullmatch(expected_sha256), "external SHA256 required")
    require(hashlib.sha256(raw).hexdigest() == expected_sha256, "external receipt digest mismatch")
    def unique(items):
        out={}
        for key,value in items:
            require(key not in out,"duplicate receipt field")
            out[key]=value
        return out
    value=json.loads(raw,object_pairs_hook=unique)
    require(isinstance(value,dict) and set(value)=={"format","storeId","contextId","digest","generation","capacity"},
            "exact immutable acknowledged artifact fields required")
    require(value["format"]=="node-durable-acknowledged-v1", "pending artifacts are not resume authority")
    for key in ("storeId","contextId","digest"):
        require(isinstance(value[key],str) and HEX.fullmatch(value[key]), "canonical receipt identity required")
    generation=value["generation"]
    require(isinstance(generation,str) and re.fullmatch(r"0|[1-9][0-9]*",generation)
            and int(generation)<1<<63,"canonical bounded generation required")
    require(type(value["capacity"]) is int and value["capacity"]==8,"bounded durable capacity eight required")
    require(value["contextId"]==expected_context,"independently expected context mismatch")
    return value


def phase_arguments(action, port, context, receipt_sha256=None, *, event_budget=64, byte_budget=33554432, seconds=120):
    require(action in ("create","resume"),"explicit durable store action required")
    require(type(port) is int and 1<=port<=65535,"bounded loopback port required")
    require(isinstance(context,str) and HEX.fullmatch(context),"explicit expected context required")
    require(type(event_budget) is int and 4<=event_budget<=128,"remaining event allowance required")
    require(type(byte_budget) is int and 1<=byte_budget<=33554432,"remaining byte allowance required")
    require(type(seconds) is int and 1<=seconds<=120,"remaining online duration required")
    args=["node","--profile",PROFILE,"--bootstrap","/evidence","--port",str(port),
          "--mode","bounded-durable","--store-action",action,"--store","/checkpoint",
          "--receipts","/receipts-a" if action=="create" else "/receipts-b",
          "--expected-context",context,"--blocks","2" if action=="create" else "4",
          "--seconds",str(seconds),"--events",str(event_budget),"--bytes",str(byte_budget),"--reconnects","0","--audit","true"]
    if action=="resume":
        require(isinstance(receipt_sha256,str) and HEX.fullmatch(receipt_sha256),"explicit external acknowledged receipt pin required")
        args += ["--resume-receipt","/resume/phase-a-acknowledged.json","--resume-sha256",receipt_sha256]
    else:
        require(receipt_sha256 is None,"create must not silently resume")
    return args


def raw_export_arguments(label):
    require(label in ("pre","post"),"raw export only inside existing pre/post bracket")
    return ["cardano-cli","conway","query","ledger-state","--testnet-magic","1082026",
        "--socket-path","/work/env/socket/node3/sock","--output-text","--out-file",
        "/work/"+label+"-ledger-state.cbor"]


def raw_export_provenance(label, raw, tips, protocol, configuration, elapsed, producers_paused):
    require(isinstance(raw,bytes) and 0<len(raw)<=4194304,"bounded binary ledger export required")
    require(producers_paused is True and isinstance(elapsed,(int,float)) and not isinstance(elapsed,bool)
            and 0<=elapsed<=20,"existing paused bracket budget exceeded")
    require(isinstance(tips,list) and len(tips)>=2,"tip brackets required around raw export")
    keys=("hash","slot","block","epoch","era")
    first=tuple(tips[0].get(k) for k in keys)
    require(first[4]=="Conway" and isinstance(first[0],str) and HEX.fullmatch(first[0]),"explicit Conway point required")
    require(all(type(tips[0].get(k)) is int for k in ("slot","block","epoch")),"bounded point numbers required")
    require(all(tuple(t.get(k) for k in keys)==first for t in tips),"raw export tip changed")
    require(isinstance(protocol,bytes) and protocol and isinstance(configuration,bytes) and configuration,
            "matching original protocol/config bytes required")
    return {"command":raw_export_arguments(label),"rawSha256":hashlib.sha256(raw).hexdigest(),
        "rawBytes":len(raw),"protocolSha256":hashlib.sha256(protocol).hexdigest(),
        "configurationSha256":hashlib.sha256(configuration).hexdigest(),"point":dict(zip(keys,first)),
        "singleAcquiredSnapshot":False,"rewardVariant":"uninterpreted",
        "nativeDecoderCompatibilityEstablished":False}


def checked_loaded_state(acknowledged, loaded, acknowledged_sha256):
    require(acknowledged.get("confirmation")=="acknowledged","A must have acknowledged authority")
    require(loaded.get("confirmation")=="loaded-verified","B begins verified, not newly acknowledged")
    for key in ("projection","revision","confirmedGeneration"):
        require(key in acknowledged and loaded.get(key)==acknowledged[key],"recovered complete state differs: "+key)
    require(loaded.get("receiptSha256")==acknowledged_sha256==acknowledged.get("receiptSha256"),
            "loaded identity must match independent acknowledged receipt")
    require(loaded.get("potentiallyOlderThanDisk") is False and acknowledged.get("potentiallyOlderThanDisk") is False,
            "uncertain storage outcome cannot authorize continuation")
    return True


def phase_progress(rows, phase, context):
    start,end=(0,2) if phase=="a" else (2,4)
    boot=[r for r in rows if r.get("record")=="node-bootstrap"]
    loaded=[r for r in rows if r.get("record")=="node-loaded"]
    ready=[r for r in rows if r.get("record")=="node-rollback" and r.get("initialIntersection") is True]
    applied=[r for r in rows if r.get("record")=="node-applied"]
    stopped=[r for r in rows if r.get("scope")=="bounded-node-outcome"]
    require(all(len(x)<=1 for x in (boot,loaded,ready,stopped)),"unique phase lifecycle records required")
    if ready: require(boot and (phase=="a" or loaded),"recovery must precede peers/intersection")
    if boot:
        b=boot[0]
        require(b.get("contextId")==context and integer(b.get("revision"),start,start)
                and integer(b.get("depth"),start,start),"phase bootstrap identity differs")
        require(b.get("confirmation")==("acknowledged" if phase=="a" else "loaded-verified"),"phase bootstrap classification")
        require(b.get("potentiallyOlderThanDisk") is False and b.get("externalReceiptStale") is False,"uncertain bootstrap")
    if loaded:
        require(phase=="b" and boot and rows.index(loaded[0])>rows.index(boot[0]),"loaded state belongs only to resume")
        require(all(loaded[0].get(k)==boot[0].get(k) for k in STATE_FIELDS),"loaded state differs from bootstrap")
    if ready:
        require(all(ready[0].get(k)==boot[0].get(k) for k in (*STATE_FIELDS,"confirmation","confirmedGeneration","receiptSha256")),
                "initial intersection must preserve loaded/acknowledged authority")
        require(phase=="a" or rows.index(loaded[0])<rows.index(ready[0]),"loaded projection must precede peer readiness")
    require(len(applied)<=2,"phase may add only two live blocks")
    if applied: require(ready and rows.index(ready[0])<rows.index(applied[0]),"readiness before online publication")
    for index,row in enumerate(applied,start+1):
        require(all(integer(row.get(k),index,index) for k in ("revision","depth","retainedBlocks"))
                and integer(row.get("compactedBlocks"),0,0) and row.get("derivedAnchorId") is None,"bounded durable cannot compact")
        require(row.get("confirmation")=="acknowledged" and integer(row.get("confirmedGeneration"),index,index)
                and row.get("potentiallyOlderThanDisk",False) is False and row.get("externalReceiptStale",False) is False,"publication needs actual acknowledgement")
        require(integer(row.get("transactionCount"),0,2) and (phase!="a" or row["transactionCount"]==0),"A must retain two empty successors")
    if stopped:
        out=stopped[0]
        require(len(applied)==2 and out.get("typedStop")=="TargetReached" and out.get("scopedTargetReached") is True,
                "phase must reach its cumulative target")
        require(out.get("mode")=="bounded-durable" and out.get("confirmation")=="acknowledged"
                and out.get("potentiallyOlderThanDisk") is False and out.get("externalReceiptStale") is False
                and out.get("cleanupFailure") is None,
                "normal acknowledged bounded-durable outcome required")
        require(out.get("peerResourcesFinalized") is True and integer(out.get("peerOpens"),1,1)
                and integer(out.get("peerCloses"),1,1) and integer(out.get("reconnects"),0,0),"phase peer finalization required")
        require(integer(out.get("events"),2,128) and integer(out.get("returnedBytes"),1,33554432),"phase event/byte budget")
        require(all(out.get(k)==applied[-1].get(k) for k in (*STATE_FIELDS,"receiptSha256","confirmedGeneration")),"phase final state differs from last acknowledgement")
        for k in ("fullLedgerValidated","consensusValidated","stateDerivedConsensus","caughtUp"):
            require(out.get(k) is False,"unsupported durable node claim: "+k)
        trace=[r for r in rows if r.get("record") in ("node-download","node-applied","node-rollback","transfer-range-block")]
        require(trace and trace[0]==ready[0],"accepted intersection must precede any download")
        trace=trace[1:]; previous=ready[0]; total=0
        if trace and trace[0].get("phase")=="rollback-announced":
            require(len(trace)>=2,"alignment must complete")
            announcement,check=trace[:2]
            require(announcement.get("downloadCursor")== (previous.get("scopedAppliedTip") or boot[0].get("suppliedAnchor"))
                    and integer(announcement.get("payloadBytes"),0,0) and announcement.get("downloadIsApplied") is False
                    and all(announcement.get(k)==previous.get(k) for k in STATE_FIELDS)
                    and check.get("record")=="node-rollback" and check.get("initialIntersection") is False
                    and all(check.get(k)==previous.get(k) for k in (*STATE_FIELDS,"confirmation","confirmedGeneration","receiptSha256")),
                    "only unchanged initial endpoint alignment supported")
            trace=trace[2:]
        require(len(trace)==8,"exactly two newly fetched original publications required")
        for i in range(2):
            announced,fetched,captured,published=trace[i*4:i*4+4]
            require((announced.get("phase"),fetched.get("phase"),captured.get("record"),published.get("record"))==
                    ("announced","fetched","transfer-range-block","node-applied"),"online phase chronology differs")
            current=point(published.get("scopedAppliedTip")); prior=previous.get("scopedAppliedTip") or boot[0]["suppliedAnchor"]
            require(current["slot"]>prior["slot"] and current["slot"]//500==prior["slot"]//500
                    and published["blockNo"]==previous["blockNo"]+1,"live continuation must be contiguous and same epoch")
            for download,bound,key in ((announced,65535,"headerEnvelopeHex"),(fetched,1048576,"rawBlockHex")):
                size=download.get("payloadBytes")
                require(integer(size,1,bound) and download.get("downloadCursor")==current
                        and download.get("downloadIsApplied") is False
                        and all(download.get(k)==previous.get(k) for k in STATE_FIELDS),"download cannot advance authority")
                raw=captured.get(key)
                require(isinstance(raw,str) and len(raw)==size*2 and all(c in "0123456789abcdef" for c in raw),"retained original size mismatch")
                total+=size
            require(captured.get("acquisitionOnly") is True and captured.get("appliedClaim") is False,"original is acquisition only")
            previous=published
        require(out["returnedBytes"]==total,"exact phase byte charge mismatch")
        state=[r for r in rows if r.get("record")=="node-state"]
        require(len(state)==1 and state[0].get("revision")==str(end) and state[0].get("depth")==str(end)
                and state[0].get("compactedBlocks")=="0" and state[0].get("derivedAnchorId") is None,"final phase projection required")
        require(state[0].get("projection",{}).get("tupleId")==out.get("stateId"),"projection/ack state binding")
    return bool(ready),applied,stopped[0] if stopped else None


def combined_bounds(a,b,elapsed):
    require(a["events"]+b["events"]<=128 and a["returnedBytes"]+b["returnedBytes"]<=33554432,
            "combined phase event/byte budget exceeded")
    require(0<=elapsed<=120,"combined online phase deadline exceeded")


def remaining_allowance(previous, remaining_seconds):
    require(integer(previous.get("events"),2,128) and integer(previous.get("returnedBytes"),1,33554432),
            "previous phase exact counters required")
    result={"event_budget":128-previous["events"],"byte_budget":33554432-previous["returnedBytes"],
            "seconds":min(120,int(remaining_seconds))}
    require(result["event_budget"]>=4 and result["byte_budget"]>0 and result["seconds"]>=1,
            "combined online allowance exhausted before resume")
    return result


def checkpoint_binding(image, token_digest):
    require(isinstance(image,bytes) and 32<len(image)<=40*1024*1024,"bounded checkpoint image required")
    require(isinstance(token_digest,str) and HEX.fullmatch(token_digest),"canonical token digest required")
    payload_hash=hashlib.sha256(image[:-32]).hexdigest()
    require(payload_hash==token_digest and image[-32:].hex()==token_digest,
            "checkpoint payload/trailer differ from exact acknowledged token")
    return {"checkpointSha256":hashlib.sha256(image).hexdigest(),"checkpointPayloadSha256":payload_hash,
            "checkpointTrailerDigest":image[-32:].hex()}


class DurableNodeRunner(LiveRunner):
    def observer_logs(self):
        result=self.docker("logs","--tail","2000",self.name+"-scala",check=False,timeout=2)
        label="durable-audit" if getattr(self,"auditing",False) else "durable-"+self.phase
        self.save(label+"-stdout.md",result.stdout); self.save(label+"-stderr.md",result.stderr)
        require(result.returncode==0 and len(result.stdout.encode())+len(result.stderr.encode())<=32*1024*1024,"bounded phase logs required")
        if not getattr(self,"auditing",False):
            require(result.stdout.startswith(getattr(self,"last_observer_stdout","")),"phase logs rewritten")
            self.last_observer_stdout=result.stdout
        return json_records(result.stdout)

    def phase_wait(self,predicate,seconds,label):
        until=min(self.deadline,self.online_deadline,time.monotonic()+seconds)
        while time.monotonic()<until:
            rows=self.observer_logs(); progress=phase_progress(rows,self.phase,self.context_id)
            self.check_live_budget(rows,progress[2])
            if predicate(progress): return rows,progress
            require(self.observer_state().get("Running"),"phase exited before "+label)
            time.sleep(0.1)
        raise TimeoutError("bounded durable phase wait: "+label)

    def check_live_budget(self,rows,outcome):
        returned=sum(r["payloadBytes"] for r in rows if r.get("record")=="node-download"
                     and r.get("phase") in ("announced","fetched") and type(r.get("payloadBytes")) is int)
        previous=getattr(self,"phase_a_outcome",None) if self.phase=="b" else None
        old_bytes=previous["returnedBytes"] if previous else 0
        old_events=previous["events"] if previous else 0
        require(returned<=self.phase_allowance["byte_budget"] and old_bytes+returned<=33554432,
                "observed download exceeds remaining combined byte allowance")
        if outcome:
            require(outcome["events"]<=self.phase_allowance["event_budget"] and old_events+outcome["events"]<=128,
                    "observed events exceed remaining combined allowance")

    def start_phase(self,phase,port,receipt=None):
        self.phase=phase; self.last_observer_stdout=""; self.auditing=False; self.observer_attempted=True
        allowance=remaining_allowance(self.phase_a_outcome,self.online_deadline-time.monotonic()) if phase=="b" else {
            "event_budget":64,"byte_budget":33554432,"seconds":max(1,min(120,int(self.online_deadline-time.monotonic())))}
        self.phase_allowance=allowance
        args=phase_arguments("create" if phase=="a" else "resume",port,self.context_id,receipt,**allowance)
        self.save("durable-"+phase+"-allowance.md",allowance)
        mounts=["-v",str(self.out/"checkpoint")+":/checkpoint:rw",
                "-v",str(self.out/("receipts-"+phase))+":/receipts-"+phase+":rw",
                "-v",str(self.out/"resume")+":/resume:ro"]
        result=self.docker("run","-d","--pull=never","--name",self.name+"-scala",
            "--network=container:"+self.name,"--cpus=1","--memory=1g","--memory-swap=1g","--pids-limit=128",
            "--cap-drop=ALL","--security-opt=no-new-privileges","--user","1000:1000","--read-only",
            "--tmpfs","/tmp:size=64m","--log-driver=json-file","--log-opt=max-size=32m","--log-opt=max-file=1",
            "-v",str(self.args.scala_repo)+":/work:ro","-v",str(self.out)+":/evidence:ro",*mounts,
            "-w","/work","--entrypoint=/bin/sh",JDK,"-c",
            'exec java -XX:ActiveProcessorCount=1 -Xmx512m -cp "$(cat app/target/runtime-classpath.txt)" lab.Main '+shlex.join(args),timeout=5)
        cid=result.stdout.strip()
        info=json.loads(self.docker("inspect",cid,timeout=2).stdout)[0]
        self.save("durable-"+phase+"-process-before.md",info)
        self.identities[phase]=info

    def finish_phase(self):
        until=min(self.deadline,time.monotonic()+5)
        while time.monotonic()<until:
            info=json.loads(self.docker("inspect",self.name+"-scala",timeout=2).stdout)[0]
            if not info["State"]["Running"]: break
            time.sleep(0.1)
        else: raise TimeoutError("phase graceful exit deadline")
        self.observer_logs()
        self.save("durable-"+self.phase+"-process-after.md",info)
        require(info["State"]["ExitCode"]==0 and info["State"]["OOMKilled"] is False,"successful graceful phase exit required")
        require(info["Id"]==self.identities[self.phase]["Id"],"phase container identity changed")
        self.identities[self.phase+"-final"]=info
        # Normal release honors the active pause/workload deadline. Only outer emergency cleanup
        # may use LiveRunner.finalize_observer's overall-deadline fallback.
        self._finalize_observer(); self.observer_attempted=False

    def snapshot(self,label):
        tip,outputs=super().snapshot(label)
        # Extend the same existing bracket while producer states still prove STOP.
        before_raw=self.relay_query("tip"); before=json.loads(before_raw); self.pause_evidence()
        self.save(label+"-raw-tip-before.md",before_raw)
        args=raw_export_arguments(label)
        result=self.execute(*args,timeout=4)
        self.save(label+"-ledger-raw-query.md",{"command":args,"stdout":result.stdout,"stderr":result.stderr})
        remote=args[-1]
        size=int(self.execute("stat","-c","%s",remote,timeout=2).stdout)
        require(0<size<=4194304,"raw ledger export size bound")
        destination=self.out/(label+"-ledger-state.cbor")
        self.docker("cp",self.name+":"+remote,str(destination),timeout=3)
        raw=destination.read_bytes(); require(len(raw)==size,"raw file copy length changed")
        after_raw=self.relay_query("tip"); after=json.loads(after_raw); paused=self.pause_evidence()
        self.save(label+"-raw-tip-after.md",after_raw)
        existing=json.loads((self.out/(label+"-tips.md")).read_text())
        tips=existing+[before,after]
        receipt=raw_export_provenance(label,raw,tips,outputs["protocol-state"].encode(),self.reference_configuration,
            max(0,20-(self.deadline-time.monotonic())),True)
        receipt.update(referenceImage=self.image,cliSha256=self.cli_sha256,cliVersion=self.cli_version,
            sourceCliCommit="eac27b8b0437a80cea2152917850aadea5749d90",referenceContainerId=self.reference_id,
            genesisSha256=self.genesis_hashes,rawPath=str(destination),
            beforeTipSha256=hashlib.sha256(before_raw.encode()).hexdigest(),afterTipSha256=hashlib.sha256(after_raw.encode()).hexdigest())
        self.save(label+"-ledger-raw-provenance.md",receipt)
        self.save(label+"-raw-producer-bracket.md",paused)
        self.save(label+"-tips.md",tips)
        return tip,outputs

    def retain_ack(self,outcome,label):
        path=Path(outcome["receiptPath"])
        require(path.parent==Path("/receipts-"+label) and path.name.endswith(".json"),"acknowledgement must be phase-local")
        source=self.out/("receipts-"+label)/path.name
        require(not source.is_symlink() and source.is_file() and source.stat().st_size<=4096,"bounded immutable acknowledgement file required")
        raw=source.read_bytes(); token=acknowledged_receipt(raw,outcome["receiptSha256"],self.context_id)
        require(int(token["generation"])==outcome["confirmedGeneration"],"ack generation differs from outcome")
        destination=self.out/"resume"/("phase-"+label+"-acknowledged.json")
        with destination.open("xb") as stream:
            stream.write(raw); stream.flush(); os.fsync(stream.fileno())
        fd=os.open(destination.parent,os.O_RDONLY|os.O_DIRECTORY)
        try: os.fsync(fd)
        finally: os.close(fd)
        checkpoint=self.out/"checkpoint"/"validated.bin"
        require(checkpoint.is_file() and not checkpoint.is_symlink() and 0<checkpoint.stat().st_size<=40*1024*1024,"bounded exact durable checkpoint required")
        image=checkpoint.read_bytes()
        binding=checkpoint_binding(image,token["digest"])
        saved_image=self.out/("durable-"+label+"-validated.bin")
        with saved_image.open("xb") as stream:
            stream.write(image); stream.flush(); os.fsync(stream.fileno())
        self.save("durable-"+label+"-external-ack.md",{"receiptSha256":outcome["receiptSha256"],
            "receiptFile":str(destination),**binding,"retainedCheckpointFile":str(saved_image),
            "contextId":self.context_id,"generation":token["generation"],"pendingUsed":False})
        return token

    def exact_phase_tip(self,tip,outcome,pre,depth):
        require(tip.get("era")=="Conway" and tip.get("epoch")==pre["epoch"]
                and tip.get("hash")==outcome["scopedAppliedTip"]["hash"]
                and tip.get("slot")==outcome["scopedAppliedTip"]["slot"]
                and tip.get("block")==pre["block"]+depth==outcome["blockNo"],"exact same-epoch phase endpoint unavailable")

    def live_sequence(self):
        self.identities={}
        for name in ("checkpoint","receipts-a","receipts-b","resume"):
            (self.out/name).mkdir(mode=0o700)
        handshake=Runner.scala(self); RelayRunner.prepare_transfer(self); pair=self.build_pair()
        self.reference_configuration=self.read("configuration.yaml").encode()
        self.save("durable-reference-configuration.md",self.reference_configuration.decode())
        self.genesis_hashes={name:hashlib.sha256(self.read(name).encode()).hexdigest() for name in
            ("byron-genesis.json","shelley-genesis.json","alonzo-genesis.json","conway-genesis.json")}
        self.reference_id=json.loads(self.docker("inspect",self.name).stdout)[0]["Id"]
        self.cli_sha256=self.execute("sha256sum","/opt/reference/bin/cardano-cli").stdout.split()[0]
        self.cli_version=self.execute("cardano-cli","--version").stdout.strip()
        relay=self.read("logs/node3/stdout.log")
        require("shelleyKESSource = Nothing" in relay and "shelleyVRFFile = Nothing" in relay,"verified relay-only role required")
        self.save("relay-role.md",relay.splitlines()[0])
        until=min(self.deadline-200,time.monotonic()+75); readiness=[]
        while time.monotonic()<until:
            tips=[self.query("tip",i) for i in (1,2,3)]; readiness.append(tips); self.save("durable-readiness.md",readiness)
            if converged_tips(tips) and tips[-1].get("epoch",0)>=1 and 1<=tips[-1].get("slotInEpoch",501)<=30: break
            time.sleep(0.2)
        else: raise TimeoutError("early durable epoch window unavailable")
        port=int(self.read("node-data/node3/port")); self.online_started=time.monotonic(); self.online_deadline=self.online_started+120
        with self.paused("pre"):
            pre,before=self.snapshot("pre")
            require(1<=pre["slotInEpoch"]<=80,"early anchor lost")
            original_utxo=json.loads(before["utxo"])
            require(all(original_utxo.get(item["input"])==item["originalInput"] for item in pair),"prepared inputs changed")
            self.save("transfer-genesis.md",self.read("shelley-genesis.json"))
            self.manifest("coherent-sequence-context.md","coherent-sequence-context-v1",PRE_PINS)
            recipe="coherent-sequence-context-v1\n"+"".join(k+"="+hashlib.sha256((self.out/n).read_bytes()).hexdigest()+"\n" for k,n in sorted(PRE_PINS.items()))
            self.context_id=hashlib.sha256(recipe.encode()).hexdigest()
            self.start_phase("a",port)
            self.phase_wait(lambda p:p[0],10,"A ready")
        rows_a,(_,_,a)=self.phase_wait(lambda p:p[2] is not None,18,"A target")
        self.phase_a_outcome=a
        restart_started=time.monotonic()
        with self.paused("restart"):
            tips=[self.query("tip") for _ in range(2)]
            for tip in tips: self.exact_phase_tip(tip,a,pre,2)
            self.save("durable-mid-tips.md",tips)
            self.finish_phase(); token_a=self.retain_ack(a,"a")
            projection_a=next(r["projection"] for r in rows_a if r.get("record")=="node-state")
            self.start_phase("b",port,a["receiptSha256"])
            rows_b,_=self.phase_wait(lambda p:p[0],10,"B loaded ready")
            loaded=next(r for r in rows_b if r.get("record")=="node-loaded")
            checked_loaded_state(dict(a,projection=projection_a),loaded,a["receiptSha256"])
            require(self.identities["a"]["Id"]!=self.identities["b"]["Id"],"separate process identities required")
            require(self.identities["a-final"]["State"]["FinishedAt"]<self.identities["b"]["State"]["StartedAt"],"A must finish before B starts")
            self.save("durable-resume-binding.md",{"acknowledgedReceiptSha256":a["receiptSha256"],"loadedBeforePeerReady":True,
                "fullProjectionMatched":True,"referenceContainerId":self.reference_id,"retainedOnlyRecoveryIsLiveContinuation":False})
            require(time.monotonic()<self.deadline,"restart pause deadline exhausted")
        require(time.monotonic()-restart_started<=20,"restart pause including resume exceeded twenty seconds")
        with self.paused("submission"):
            for index,item in enumerate(pair):
                started=time.monotonic()
                result=self.execute("cardano-cli","conway","transaction","submit","--tx-file",item["signedPath"],
                    "--testnet-magic","1082026","--socket-path","/work/env/socket/node3/sock",timeout=3)
                self.save(f"durable-submission-{index}.md",result.stdout+result.stderr)
                self.save(f"durable-submission-{index}-timing.md",{"startedMonotonic":started,"finishedMonotonic":time.monotonic(),"transactionId":item["transactionId"]})
            until=min(self.deadline,time.monotonic()+2)
            while time.monotonic()<until:
                records=events(self.read("logs/node3/stdout.log"))
                admissions={item["transactionId"]:[e for e in records if e["ns"]=="Mempool.AddedTx" and
                    e.get("data",{}).get("tx",{}).get("txid") in (item["transactionId"],item["transactionId"][:8])] for item in pair}
                self.save("durable-relay-admissions.md",admissions)
                if all(admissions.values()): break
                time.sleep(0.1)
            else: raise ValueError("both relay admissions required")
        rows_b,(_,_,b)=self.phase_wait(lambda p:p[2] is not None,20,"B target")
        combined_bounds(a,b,time.monotonic()-self.online_started)
        with self.paused("post"):
            post,after=self.snapshot("post"); self.exact_phase_tip(post,b,pre,4)
            require(before["parameters"]==after["parameters"],"parameter endpoints differ")
        self.finish_phase(); token_b=self.retain_ack(b,"b")
        require(token_b["storeId"]==token_a["storeId"] and token_b["contextId"]==token_a["contextId"]
                and int(token_b["generation"])==int(token_a["generation"])+2,"new acknowledged live continuation required")
        require(self.read("configuration.yaml").encode()==self.reference_configuration,"reference config changed across phases")
        require(json.loads(self.docker("inspect",self.name).stdout)[0]["Id"]==self.reference_id,"reference container changed")
        require({name:hashlib.sha256(self.read(name).encode()).hexdigest() for name in self.genesis_hashes}==self.genesis_hashes,
                "reference genesis identities changed across restart")
        originals=[r for r in rows_a+rows_b if r.get("record")=="transfer-range-block"]
        require(len(originals)==4,"four actual online originals required")
        capture="".join(line for phase in ("a","b")
            for line in (self.out/("durable-"+phase+"-stdout.md")).read_text().splitlines(keepends=True)
            if line.startswith("{") and line.endswith("\n") and json.loads(line).get("record")=="transfer-range-block")
        require([r for r in json_records(capture)]==originals,"exact phase stdout capture changed")
        self.save("scala-sequence-capture.md",capture)
        final_state=next(r for r in rows_b if r.get("record")=="node-state")
        self.save("durable-combined-audit.md",capture+json.dumps(final_state,separators=(",",":"))+"\n")
        evidence={str(i):[e for e in events(self.read(f"logs/node{i}/stdout.log")) if e["ns"].startswith("Mempool.") or e["ns"].startswith("TxSubmission.")] for i in (1,2,3)}
        self.save("sequence-transaction-events.md",evidence)
        self.save("sequence-pair-admissions.md",pair_admissions(evidence,[item["transactionId"] for item in pair]))
        self.manifest("coherent-sequence-oracle.pending.md","coherent-sequence-oracle-v1",ORACLE_PINS)
        (self.out/"coherent-sequence-oracle.pending.md").replace(self.out/"coherent-sequence-oracle.md")
        self.auditing=True; self.observer_attempted=True
        result=self.docker("run","--rm","--pull=never","--name",self.name+"-scala","--network=none",
            "--cpus=1","--memory=1g","--memory-swap=1g","--pids-limit=128","--cap-drop=ALL","--security-opt=no-new-privileges",
            "--user","1000:1000","--read-only","--tmpfs","/tmp:size=64m","-v",str(self.args.scala_repo)+":/work:ro",
            "-v",str(self.out)+":/evidence:ro","-w","/work","--entrypoint=/bin/sh",JDK,"-c",
            'exec java -XX:ActiveProcessorCount=1 -Xmx512m -cp "$(cat app/target/runtime-classpath.txt)" lab.Main node-audit /evidence /evidence /evidence/durable-combined-audit.md 8 4',check=False,timeout=65)
        self.observer_attempted=False
        self.save("durable-audit-stdout.md",result.stdout); self.save("durable-audit-stderr.md",result.stderr)
        require(result.returncode==0,"full independent durable continuation audit failed")
        reports=[r for r in json_records(result.stdout) if r.get("scope")=="node-audit"]
        require(len(reports)==1,"one full audit required"); audit=reports[0]
        require(audit.get("capturedBlocks")==4 and audit.get("emptyBlocks")==3 and audit.get("transactionCount")==2
                and audit.get("transactionBlockIndex") in (2,3),"pair must occur in new B continuation")
        require(sorted(audit.get("transactionIdsInBlockOrder",[]))==sorted(item["transactionId"] for item in pair),"submitted pair audit mismatch")
        for key in ("passed","completeProjectionMatched","referencePostStateMatched","sameEpoch","distinctForwardHashes"):
            require(audit.get(key) is True,"audit missing "+key)
        for key in ("fullLedgerValidated","consensusValidated","liveForkClaim","durableClaim"):
            require(audit.get(key) is False,"unsupported offline audit claim")
        require(audit.get("finalStateId")==b["stateId"] and audit.get("contextId")==self.context_id
                and audit.get("revision")=="4" and audit.get("depth")=="4" and audit.get("compactedBlocks")=="0",
                "complete online/resumed projection mismatch")
        return {"handshake":handshake,"durableGracefulRestart":True,"actualNewLiveContinuation":True,
                "phaseA":a,"phaseB":b,"independentAudit":audit,"singleAcquiredSnapshot":False,
                "powerLossRecovery":False,"liveForkClaim":False,"epochTransition":False}


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--plan",action="store_true")
    parser.add_argument("--fixture-profile",choices=[FIXTURE]); parser.add_argument("--reference-image")
    parser.add_argument("--output"); parser.add_argument("--scala-repo"); parser.add_argument("--seconds",type=int,default=480)
    args=parser.parse_args()
    if args.plan: print(PLAN); return
    if not all((args.fixture_profile,args.reference_image,args.output,args.scala_repo)):
        parser.error("explicit fixture/image/output/build are required")
    if not 420<=args.seconds<=480: parser.error("durable workload budget must be 420..480 seconds")
    args.capture=False
    signal.signal(signal.SIGTERM,lambda *_: (_ for _ in ()).throw(KeyboardInterrupt()))
    DurableNodeRunner(args).run()


if __name__=="__main__": main()
