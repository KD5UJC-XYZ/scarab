#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Prepared bounded same-epoch v2 compaction/graceful-resume acceptance; never runs on import."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shlex
import signal
import subprocess
import time
from private_cluster import Runner, JDK
from private_cluster_coherent import FIXTURE
from private_cluster_durable_node import DurableNodeRunner, checkpoint_binding
from private_cluster_node import PROFILE, STATE_FIELDS, point, integer, audit_report
from private_cluster_relay import RelayRunner, events
from private_cluster_sequence import PRE_PINS, ORACLE_PINS, pair_admissions
from private_cluster_transfer import converged_tips
from private_cluster_fork import verify_build

CAPACITY = 2
TARGETS = {"seed": 2, "a": 9, "b": 12}
STARTS = {"seed": 0, "a": 2, "b": 9}
FORMAT = "restricted-local-derived-checkpoint-v2"
AUTHORITY = "reviewed-local-writer-crash-recovery-v1"
LAUNCH = "in-process-resource-v1"
MAX_BYTES = 33554432
MAX_EVENTS = 128
ONLINE_SECONDS = 120
PLAN = """Preparation only until independently reviewed: one fresh private reference (2 CPU/2 GiB),
sequential seed/A/B/offline Scala (1 CPU/1 GiB), cap2, supplied prestate plus seed2 empty
originals, A9/gen13/compacted7, B12/gen19/compacted10. No live forks or epoch transitions.
Workload420..480s; absolute600s cleanup; shared online120s/128events/32MiB; paused brackets20s,
submission8s. Resume exact separately persisted journal binding, no diagnostic receipt authority.
All12 original captures and full final projection must pass separate network-none cap2 NodeAudit.
"""

def require(ok, reason):
    if not ok: raise ValueError(reason)

def digest(value):
    return type(value) is str and len(value) == 64 and all(c in "0123456789abcdef" for c in value)

def exact(a, b):
    return json.dumps(a, sort_keys=True, separators=(",", ":"), allow_nan=False) == json.dumps(b, sort_keys=True, separators=(",", ":"), allow_nan=False)

def parse(raw, limit=MAX_BYTES):
    require(type(raw) is bytes and 0 < len(raw) <= limit, "bounded original JSON bytes")
    def unique(items):
        out = {}
        for k,v in items:
            require(k not in out, "duplicate JSON field")
            out[k] = v
        return out
    return json.loads(raw.decode("utf-8", "strict"), object_pairs_hook=unique,
                      parse_constant=lambda _: (_ for _ in ()).throw(ValueError("non-JSON number")))

def records(text):
    raw = text.encode("utf-8")
    require(len(raw) <= MAX_BYTES, "bounded phase stdout")
    lines = text.splitlines(keepends=True)
    require(len(lines) <= 2048, "bounded phase records")
    # Docker can return a partial final write; only its last incomplete line waits for the next poll.
    rows = [parse(line.encode()) for line in lines if line.endswith("\n")]
    require(all(type(row) is dict for row in rows), "object records required")
    return rows

def binding(context, store_id):
    require(digest(context) and digest(store_id), "independent context/store ID")
    return dict(journal="/journal", checkpoint="/checkpoint", storeId=store_id, contextId=context,
                profile=PROFILE, format=FORMAT, authority=AUTHORITY, launchPolicy=LAUNCH)

def decimal(value):
    require(type(value) is str and value.isascii() and value.isdigit() and value == str(int(value))
            and 0 <= int(value) < 1 << 63, "canonical bounded decimal string")
    return int(value)

def checked_claim(claim, row, expected_binding):
    require(type(claim) is dict and set(claim) == {"token","format","profile","authority","anchorId","finalId","compactedBlocks","revision"}, "exact full claim")
    token = claim["token"]
    require(type(token) is dict and set(token) == {"storeId","contextId","sessionId","generation","digest"}, "exact v2 token")
    require(all(digest(token[k]) for k in ("storeId","contextId","sessionId","digest"))
            and all(digest(claim[k]) for k in ("anchorId","finalId")), "full claim identities")
    require(token["storeId"] == expected_binding["storeId"] and token["contextId"] == expected_binding["contextId"], "claim independent binding")
    require((claim["format"],claim["profile"],claim["authority"]) == (FORMAT,PROFILE,AUTHORITY), "claim scope")
    require(claim["finalId"] == row["stateId"] and token["contextId"] == row["contextId"]
            and decimal(claim["revision"]) == row["revision"]
            and decimal(claim["compactedBlocks"]) == row["compactedBlocks"]
            and decimal(token["generation"]) == row["confirmedGeneration"], "claim complete state correlation")
    return claim

def state(row, depth, generation, context, expected_binding, confirmation, trusted=False):
    require(row.get("contextId") == context and digest(row.get("stateId")), "state context identity")
    compacted = 0 if confirmation == "volatile" else max(1, depth-CAPACITY)
    retained = depth-compacted
    require(all(integer(row.get(k),v,v) for k,v in (("revision",depth),("depth",depth),
            ("compactedBlocks",compacted),("retainedBlocks",retained))), "state revision/window arithmetic")
    require(row.get("confirmation") == confirmation and row.get("potentiallyOlderThanDisk",False) is False
            and row.get("externalReceiptStale",False) is False, "certain exact confirmation")
    if confirmation == "volatile":
        require(row.get("confirmedGeneration") is None and row.get("derivedAnchorId") is None, "seed remains volatile")
    else:
        require(integer(row.get("confirmedGeneration"),generation,generation) and digest(row.get("derivedAnchorId")), "v2 generation/provenance")
        require(row.get("storageVersion") == "v2" and row.get("trustedLocalPrefix") is trusted
                and exact(row.get("storageBinding"),expected_binding), "exact v2 storage binding and qualification")
        checked_claim(row.get("fullClaim"), row, expected_binding)
        if confirmation == "v2-loaded-verified":
            require(row.get("receiptPath") is None and row.get("receiptSha256") is None, "loaded must not create acknowledged export")
        else:
            require(type(row.get("receiptPath")) is str and digest(row.get("receiptSha256")), "acknowledged export reference")

def phase_arguments(phase, port, context, store_id, seconds, event_budget, byte_budget, seed=None):
    require(phase in TARGETS and integer(port,1,65535), "fixed phase/loopback port")
    require(integer(seconds,1,120) and integer(event_budget,12,128) and integer(byte_budget,1,MAX_BYTES), "remaining cumulative budget")
    args = ["node","--profile",PROFILE,"--bootstrap","/evidence","--port",str(port),
            "--blocks",str(TARGETS[phase]),"--seconds",str(seconds),"--events",str(event_budget),
            "--bytes",str(byte_budget),"--reconnects","0","--audit","true"]
    if phase == "seed":
        require(seed is None, "seed stage has no checkpoint authority")
        return args + ["--mode","bounded-volatile"]
    binding(context,store_id)
    args += ["--mode","sustained-durable","--rollback-capacity","2","--store-action","create" if phase=="a" else "resume",
             "--store","/checkpoint","--journal","/journal","--receipts","/receipts-"+phase,
             "--store-id",store_id,"--expected-context",context]
    if phase == "a":
        require(type(seed) is dict and set(seed)=={"sha256","point"} and digest(seed["sha256"]), "pinned seed originals")
        p=point(seed["point"])
        args += ["--seed-capture","/evidence/v2-seed-capture.md","--seed-sha256",seed["sha256"],
                 "--compact-slot",str(p["slot"]),"--compact-hash",p["hash"]]
    else: require(seed is None, "journal-only resume forbids seed/receipt authority")
    return args

def one(rows, name, scope=False):
    selected=[r for r in rows if r.get("scope" if scope else "record")==name]
    require(len(selected)==1,"unique "+name)
    return selected[0]

def generation(depth):
    return 0 if depth==2 else 2*depth-5

def progress(rows, phase, context, expected_binding):
    start,end=STARTS[phase],TARGETS[phase]
    boots=[r for r in rows if r.get("record")=="node-bootstrap"]
    ready=[r for r in rows if r.get("record")=="node-rollback" and r.get("initialIntersection") is True]
    stops=[r for r in rows if r.get("scope")=="bounded-node-outcome"]
    require(all(len(xs)<=1 for xs in (boots,ready,stops)),"unique phase lifecycle")
    applied=[r for r in rows if r.get("record")=="node-applied"]
    require(len(applied)<=end-start and (not applied or ready),"bounded publications after readiness")
    kind="volatile" if phase=="seed" else "v2-loaded-verified" if phase=="b" else "v2-acknowledged"
    if boots:
        state(boots[0],start,0 if phase!="b" else 13,context,expected_binding,kind,phase=="b")
        require(boots[0].get("mode")==("bounded-volatile" if phase=="seed" else "sustained-durable")
                and boots[0].get("auditEnabled") is True,"explicit ordinary mode")
    if ready:
        require(boots and rows.index(boots[0])<rows.index(ready[0]),"bootstrap before checked intersection")
        state(ready[0],start,0 if phase!="b" else 13,context,expected_binding,kind,phase=="b")
        require(all(exact(ready[0].get(k),boots[0].get(k)) for k in (*STATE_FIELDS,"fullClaim","storageBinding","confirmation","confirmedGeneration","receiptPath","receiptSha256")),"initial intersection no-op")
    for i,row in enumerate(applied,start+1):
        state(row,i,0 if phase=="seed" else generation(i),context,expected_binding,"volatile" if phase=="seed" else "v2-acknowledged",phase=="b")
        require(integer(row.get("transactionCount"),0,2) and (phase=="b" or row["transactionCount"]==0),"transactions only in new B continuation")
    if stops:
        out=stops[0]
        require(len(applied)==end-start and out.get("typedStop")=="TargetReached" and out.get("scopedTargetReached") is True,"exact phase target")
        require(out.get("peerResourcesFinalized") is True and out.get("cleanupFailure") is None
                and out.get("potentiallyOlderThanDisk") is False and out.get("externalReceiptStale") is False,
                "certain finalized phase")
        require(all(integer(out.get(k),v,v) for k,v in (("peerOpens",1),("peerCloses",1),("reconnects",0))),"one owned peer")
        require(integer(out.get("events"),end-start,128) and integer(out.get("returnedBytes"),1,MAX_BYTES),"bounded counters")
        for k in ("caughtUp","fullLedgerValidated","consensusValidated","stateDerivedConsensus"):
            require(out.get(k) is False,"unsupported claim")
        state(out,end,0 if phase=="seed" else generation(end),context,expected_binding,"volatile" if phase=="seed" else "v2-acknowledged",phase=="b")
        terminal_trace(rows,phase,boots[0],ready[0],applied,out,expected_binding)
    return bool(ready),applied,stops[0] if stops else None

def terminal_trace(rows,phase,boot,ready,applied,out,b):
    offers=one(rows,"node-intersection-offered"); selected=one(rows,"node-intersection-selected")
    tip=boot.get("scopedAppliedTip") or boot["suppliedAnchor"]
    require(selected.get("selectedPoint")==tip and selected.get("offeredMatch") is True
            and type(offers.get("offeredPoints")) is list and offers["offeredPoints"][0]==tip,"resume exact applied endpoint")
    require(rows.index(boot)<rows.index(offers)<rows.index(selected)<rows.index(ready),"actual intersection ordering")
    require(all(r.get("acquisitionOnly") is True and r.get("appliedClaim") is False for r in (offers,selected)),"intersection acquisition only")
    if phase=="b":
        loaded=one(rows,"node-loaded")
        require(rows.index(boot)<rows.index(loaded)<rows.index(offers),"loaded complete state before peer")
        require(loaded.get("projection",{}).get("tupleId")==boot["stateId"] and exact(loaded.get("fullClaim"),boot["fullClaim"]),"loaded complete claim projection")
    else: require(not any(r.get("record")=="node-loaded" for r in rows),"create/seed cannot claim loaded")
    trace=[r for r in rows[rows.index(ready)+1:] if r.get("record") in ("node-download","node-rollback","transfer-range-block","node-anchor-advance","node-applied")]
    previous=ready; charged=0; sessions=set()
    if phase != "seed":
        point(boot.get("suppliedAnchor")); point(boot.get("retainedAnchor"))
        require(boot["suppliedAnchor"] != boot["retainedAnchor"], "supplied and derived retained anchors remain distinct")
    if trace and trace[0].get("phase")=="rollback-announced":
        require(len(trace)>=2,"paired alignment")
        ann,check=trace[:2]
        require(ann.get("downloadCursor")==tip and ann.get("downloadIsApplied") is False and integer(ann.get("payloadBytes"),0,0)
                and check.get("initialIntersection") is False and exact({k:v for k,v in check.items() if k!="initialIntersection"},{k:v for k,v in ready.items() if k!="initialIntersection"}),"exact no-op alignment")
        require(all(exact(ann.get(k),ready.get(k)) for k in (*STATE_FIELDS,"confirmation","confirmedGeneration","fullClaim","storageBinding","receiptPath","receiptSha256")), "alignment download cannot change confirmed state")
        require(out["events"]>=len(applied)+1,"alignment event must be charged")
        trace=trace[2:]
    for pub in applied:
        require(len(trace)>=4,"complete online original sequence")
        ann,fetched,capture=trace[:3]; trace=trace[3:]
        depth=pub["depth"]
        expected_advance=phase!="seed" and depth>=4
        if expected_advance:
            require(trace and trace[0].get("record")=="node-anchor-advance","required compaction before publication")
            advance=trace.pop(0)
            require(advance["revision"]==previous["revision"] and advance["depth"]==previous["depth"]
                    and advance["compactedBlocks"]==previous["compactedBlocks"]+1 and advance["retainedBlocks"]==1
                    and advance["scopedAppliedTip"]==previous["scopedAppliedTip"]
                    and advance["confirmedGeneration"]==previous["confirmedGeneration"]+1,
                    "compaction changes acknowledged generation without ledger revision")
            require(digest(advance.get("stateId")) and advance["stateId"]!=previous["stateId"]
                    and digest(advance.get("derivedAnchorId")) and advance["derivedAnchorId"]!=previous["derivedAnchorId"]
                    and advance.get("trustedLocalPrefix") is (phase=="b") and advance.get("storageVersion")=="v2"
                    and advance.get("potentiallyOlderThanDisk",False) is False and advance.get("externalReceiptStale",False) is False
                    and type(advance.get("receiptPath")) is str and digest(advance.get("receiptSha256")),
                    "compaction preserves trust qualification and has a new certain acknowledged identity")
            checked_claim(advance.get("fullClaim"),advance,b)
            sessions.add(advance["fullClaim"]["token"]["sessionId"])
            require(exact(advance.get("storageBinding"),b) and advance.get("confirmation")=="v2-acknowledged","compaction acknowledgment")
        require(trace and trace.pop(0) is pub,"one exact publication follows original")
        require((ann.get("record"),ann.get("phase"),fetched.get("record"),fetched.get("phase"),capture.get("record"))==
                ("node-download","announced","node-download","fetched","transfer-range-block"),"download order")
        current=point(pub["scopedAppliedTip"]); prior=previous.get("scopedAppliedTip") or boot["suppliedAnchor"]
        require(current["slot"]>prior["slot"] and current["slot"]//500==boot["suppliedAnchor"]["slot"]//500
                and pub["blockNo"]==previous["blockNo"]+1,"same epoch contiguous live successors")
        for row,limit,key in ((ann,65535,"headerEnvelopeHex"),(fetched,1048576,"rawBlockHex")):
            size=row.get("payloadBytes"); original=capture.get(key)
            require(integer(size,1,limit) and row.get("downloadCursor")==current and row.get("downloadIsApplied") is False
                    and all(exact(row.get(k),previous.get(k)) for k in (*STATE_FIELDS,"fullClaim","storageBinding","confirmation","confirmedGeneration","receiptPath","receiptSha256")),"download cannot publish state")
            require(type(original) is str and len(original)==2*size and all(c in "0123456789abcdef" for c in original),"exact original bytes")
            charged+=size
        require(capture.get("acquisitionOnly") is True and capture.get("appliedClaim") is False,"capture acquisition only")
        if phase != "seed": sessions.add(pub["fullClaim"]["token"]["sessionId"])
        previous=pub
    if phase != "seed":
        require(len(sessions)==1, "one actual publication session per process")
        old=boot["fullClaim"]["token"]["sessionId"]
        require((old not in sessions) if phase=="b" else (old in sessions), "resume loads old session then new writer acknowledges successors")
    require(not trace and out["returnedBytes"]==charged,"no extra transitions or uncharged originals")
    require(all(exact(out.get(k),previous.get(k)) for k in (*STATE_FIELDS,"fullClaim","confirmation","confirmedGeneration","receiptSha256")),"final exact acknowledgment")
    final=one(rows,"node-state")
    require(final.get("projection",{}).get("tupleId")==out["stateId"] and final["projection"]["contextId"]==out["contextId"],"complete projection identity")
    require(all(final.get(k)==str(out[k]) for k in ("revision","depth","compactedBlocks"))
            and final.get("derivedAnchorId")==out.get("derivedAnchorId"),"final projection counters")
    require(rows.index(applied[-1])<rows.index(final)<rows.index(out) and rows[-1] is out,"finalizers before final projection/outcome")

def checked_bootstrap(rows, supplied, retained=None):
    boot=one(rows,"node-bootstrap")
    require(exact(boot.get("suppliedAnchor"),point(supplied)),"original supplied anchor is immutable")
    if retained is not None:
        require(exact(boot.get("retainedAnchor"),point(retained)),"exact compacted retained anchor")


def verify_source_build(repo,pin_path,pin_sha):
    repo=Path(repo).resolve()
    pin=verify_build(repo,Path(pin_path),pin_sha)
    tracked=subprocess.check_output(["git","-C",str(repo),"ls-files","-z"],timeout=5).decode().split("\0")
    tracked={name for name in tracked if name}
    require(set(pin["sourceHashes"])==tracked,"pin must cover every current tracked file, not a partial baseline")
    needed={"scripts/private_cluster_sustained_durable.py","scripts/test_private_cluster_sustained_durable.py",
            "docs/private-cluster-sustained-durable.md"}
    require(needed<=tracked,"reviewed controller must be integrated before live execution")
    runtime=set()
    for entry in pin["classpath"].split(":"):
        path=repo/entry.removeprefix("/work/")
        require(path.exists() and not path.is_symlink(),"real pinned runtime entry required")
        candidates=[path] if path.is_file() else sorted(path.rglob("*"))
        for item in candidates:
            require(not item.is_symlink(),"runtime symlinks forbidden")
            if item.is_file(): runtime.add(str(item.relative_to(repo)))
        require(len(runtime)<=30000,"bounded runtime inventory")
    require(runtime and runtime==set(pin["compiled"]),"pin must cover complete current runtime classpath contents")
    require(Path(__file__).resolve()==repo/"scripts/private_cluster_sustained_durable.py","execute only pinned launcher copy")
    return pin


def checked_loaded(a_rows,b_rows):
    a=one(a_rows,"bounded-node-outcome",True); loaded=one(b_rows,"node-loaded")
    require(loaded.get("confirmation")=="v2-loaded-verified" and loaded.get("receiptSha256") is None
            and loaded.get("receiptPath") is None,"journal load is not new acknowledgment")
    require(exact(loaded.get("projection"),one(a_rows,"node-state")["projection"]),"entire A projection restored")
    require(all(exact(loaded.get(k),a.get(k)) for k in (*STATE_FIELDS,"fullClaim","storageBinding","confirmedGeneration")),"entire full claim and state restored")
    require(b_rows.index(loaded)<b_rows.index(one(b_rows,"node-intersection-offered")),"loaded before peer")

def receipt(raw, expected_hash, row, expected_binding):
    require(digest(expected_hash) and hashlib.sha256(raw).hexdigest()==expected_hash,"independent artifact digest")
    value=parse(raw,8192)
    require(type(value) is dict and set(value)=={"format","diagnosticOnly","capacity","binding","claim"}
            and value["format"]=="node-v2-acknowledged-v1" and value["diagnosticOnly"] is True
            and integer(value["capacity"],2,2),"v2 diagnostic artifact only")
    require(exact(value["binding"],expected_binding) and exact(value["claim"],row["fullClaim"]),"entire diagnostic claim and binding")
    checked_claim(value["claim"],row,expected_binding)
    return value

class SustainedDurableRunner(DurableNodeRunner):
    def preflight(self):
        self.source_pin=verify_source_build(self.args.scala_repo,self.args.source_pin,self.args.source_pin_sha256)
        self.save("v2-source-pin.json",self.source_pin)
        super().preflight()

    def docker(self,*args,**kwargs):
        # Narrow only the one freshly owned reference run; all Scala limits stay unchanged.
        if args and args[0]=="run" and "--name" in args and args[args.index("--name")+1]==self.name:
            args=tuple({"--cpus=3":"--cpus=2","--memory=6g":"--memory=2g","--memory-swap=6g":"--memory-swap=2g"}.get(x,x) for x in args)
        return super().docker(*args,**kwargs)

    def observer_logs(self):
        result=self.docker("logs","--tail","2048",self.name+"-scala",check=False,timeout=2)
        label="v2-"+self.phase
        self.save(label+"-stdout.md",result.stdout); self.save(label+"-stderr.md",result.stderr)
        require(result.returncode==0 and len(result.stdout.encode())+len(result.stderr.encode())<=MAX_BYTES,"bounded owned logs")
        require(result.stdout.startswith(getattr(self,"last_observer_stdout","")),"logs immutable prefix")
        self.last_observer_stdout=result.stdout
        return records(result.stdout)

    def start_phase(self,phase,port,seed=None):
        self.phase=phase; self.last_observer_stdout=""; self.observer_attempted=True
        remaining=int(self.online_deadline-time.monotonic())
        budgets=dict(seconds=min(120,remaining),event_budget=MAX_EVENTS-self.used_events,byte_budget=MAX_BYTES-self.used_bytes)
        command=phase_arguments(phase,port,self.context_id,self.store_id,seed=seed,**budgets)
        self.save("v2-"+phase+"-arguments.md",command)
        mounts=[] if phase=="seed" else ["-v",str(self.out/"checkpoint")+":/checkpoint:rw","-v",str(self.out/"journal")+":/journal:rw",
            "-v",str(self.out/("receipts-"+phase))+":/receipts-"+phase+":rw"]
        result=self.docker("run","-d","--pull=never","--name",self.name+"-scala","--network=container:"+self.name,
            "--cpus=1","--memory=1g","--memory-swap=1g","--pids-limit=128","--cap-drop=ALL","--security-opt=no-new-privileges",
            "--user","1000:1000","--read-only","--tmpfs","/tmp:size=64m","--log-driver=json-file","--log-opt=max-size=32m","--log-opt=max-file=1",
            "-v",str(self.args.scala_repo)+":/work:ro","-v",str(self.out)+":/evidence:ro",*mounts,"-w","/work","--entrypoint=/bin/sh",JDK,"-c",
            'exec java -XX:ActiveProcessorCount=1 -Xmx512m -cp "$(cat app/target/runtime-classpath.txt)" lab.Main '+shlex.join(command),timeout=5)
        info=parse(self.docker("inspect",result.stdout.strip(),timeout=2).stdout.encode())[0]
        self.identities[phase]=info; self.save("v2-"+phase+"-process-before.md",info)
        if phase!="seed":
            prior="seed" if phase=="a" else "a"
            require(info["Id"]!=self.identities[prior]["Id"] and self.identities[prior+"-final"]["State"]["FinishedAt"]<info["State"]["StartedAt"],"distinct sequential Scala processes")

    def phase_wait(self,predicate,seconds,label):
        until=min(self.deadline,self.online_deadline,time.monotonic()+seconds)
        while time.monotonic()<until:
            rows=self.observer_logs(); current=progress(rows,self.phase,self.context_id,self.expected_binding)
            if predicate(current): return rows,current
            require(self.observer_state().get("Running"),"phase exited before "+label)
            time.sleep(.1)
        raise TimeoutError("bounded v2 phase wait: "+label)

    def finish_phase(self):
        super().finish_phase()
        rows=records(self.last_observer_stdout)
        progress(rows,self.phase,self.context_id,self.expected_binding)
        out=one(rows,"bounded-node-outcome",True)
        self.used_events+=out["events"]; self.used_bytes+=out["returnedBytes"]
        require(self.used_events<=MAX_EVENTS and self.used_bytes<=MAX_BYTES,"combined online budgets")
        return rows

    def retain_v2(self,rows,phase):
        for row in rows:
            if row.get("confirmation")!="v2-acknowledged": continue
            path=Path(row["receiptPath"])
            require(path.parent==Path("/receipts-"+phase) and path.name.endswith(".json"),"phase-local export path")
            source=self.out/("receipts-"+phase)/path.name
            require(not source.is_symlink() and source.is_file() and source.stat().st_size<=8192,"bounded regular original export")
            receipt(source.read_bytes(),row["receiptSha256"],row,self.expected_binding)
        out=one(rows,"bounded-node-outcome",True)
        checkpoint=self.out/"checkpoint/local-derived.bin"; journal=self.out/"journal/controller.bin"
        require(checkpoint.is_file() and journal.is_file() and not checkpoint.is_symlink() and not journal.is_symlink(),"separate retained domains")
        require(checkpoint.stat().st_size<=40*1024*1024 and journal.stat().st_size<=32768,"bounded retained storage")
        for name,source in (("checkpoint",checkpoint),("journal",journal)):
            with (self.out/("v2-"+phase+"-"+name+".bin")).open("xb") as copy:
                copy.write(source.read_bytes()); copy.flush(); os.fsync(copy.fileno())
        self.save("v2-"+phase+"-storage-binding.md",dict(checkpoint_binding(checkpoint.read_bytes(),out["fullClaim"]["token"]["digest"]),
            journalSha256=hashlib.sha256(journal.read_bytes()).hexdigest(),fullClaim=out["fullClaim"],binding=self.expected_binding,
            diagnosticReceiptIsResumeAuthority=False))

    def exact_tip(self,tip,out,pre,depth):
        require(tip.get("era")=="Conway" and tip["epoch"]==pre["epoch"] and tip["slot"]//500==pre["slot"]//500
                and tip["block"]-pre["block"]==depth and tip["block"]==out["blockNo"]
                and {"hash":tip["hash"],"slot":tip["slot"]}==out["scopedAppliedTip"],"exact same-epoch endpoint; no extension")

    def live_sequence(self):
        self.identities={}; self.used_events=0; self.used_bytes=0
        for name in ("checkpoint","journal","receipts-a","receipts-b"): (self.out/name).mkdir(mode=0o700)
        self.store_id=os.urandom(32).hex() # Public independent store identifier, not a signing key.
        handshake=Runner.scala(self); RelayRunner.prepare_transfer(self); pair=self.build_pair()
        self.reference_configuration=self.read("configuration.yaml").encode()
        self.save("v2-reference-configuration.md",self.reference_configuration.decode())
        self.genesis_hashes={n:hashlib.sha256(self.read(n).encode()).hexdigest() for n in ("byron-genesis.json","shelley-genesis.json","alonzo-genesis.json","conway-genesis.json")}
        self.reference_id=parse(self.docker("inspect",self.name).stdout.encode())[0]["Id"]
        self.cli_sha256=self.execute("sha256sum","/opt/reference/bin/cardano-cli").stdout.split()[0]
        self.cli_version=self.execute("cardano-cli","--version").stdout.strip()
        relay=self.read("logs/node3/stdout.log")
        require("shelleyKESSource = Nothing" in relay and "shelleyVRFFile = Nothing" in relay,"nonproducing relay")
        until=min(self.deadline-210,time.monotonic()+75)
        while time.monotonic()<until:
            tips=[self.query("tip",i) for i in (1,2,3)]
            self.save("v2-readiness.md",tips)
            if converged_tips(tips) and tips[-1].get("epoch",0)>=1 and 1<=tips[-1].get("slotInEpoch",501)<=30: break
            time.sleep(.2)
        else: raise TimeoutError("early same-epoch window unavailable")
        port=int(self.read("node-data/node3/port")); self.online_deadline=time.monotonic()+ONLINE_SECONDS
        with self.paused("pre"):
            pre,before=self.snapshot("pre")
            require(1<=pre["slotInEpoch"]<=80,"early anchor lost")
            require(all(json.loads(before["utxo"]).get(s["input"])==s["originalInput"] for s in pair),"pair inputs changed")
            self.save("transfer-genesis.md",self.read("shelley-genesis.json"))
            self.manifest("coherent-sequence-context.md","coherent-sequence-context-v1",PRE_PINS)
            recipe="coherent-sequence-context-v1\n"+"".join(k+"="+hashlib.sha256((self.out/n).read_bytes()).hexdigest()+"\n" for k,n in sorted(PRE_PINS.items()))
            self.context_id=hashlib.sha256(recipe.encode()).hexdigest(); self.expected_binding=binding(self.context_id,self.store_id)
            self.save("v2-expected-binding.md",self.expected_binding)
            self.start_phase("seed",port); seed_ready,_=self.phase_wait(lambda p:p[0],10,"seed ready")
            supplied={"hash":pre["hash"],"slot":pre["slot"]}
            checked_bootstrap(seed_ready,supplied,supplied)
        seed_rows,(_,_,seed_out)=self.phase_wait(lambda p:p[2] is not None,18,"seed target")
        with self.paused("seed-create"):
            self.exact_tip(self.query("tip"),seed_out,pre,2); self.finish_phase()
            capture="".join(line for line in self.last_observer_stdout.splitlines(keepends=True) if json.loads(line).get("record")=="transfer-range-block")
            require(len(records(capture))==2,"two exact seed originals")
            self.save("v2-seed-capture.md",capture)
            first=next(row for row in seed_rows if row.get("record")=="node-applied")
            seed={"sha256":hashlib.sha256(capture.encode()).hexdigest(),"point":first["scopedAppliedTip"]}
            self.start_phase("a",port,seed); a_ready,_=self.phase_wait(lambda p:p[0],10,"A checked ready")
            checked_bootstrap(a_ready,supplied,seed["point"])
        a_rows,(_,_,a)=self.phase_wait(lambda p:p[2] is not None,40,"A target nine")
        with self.paused("restart"):
            self.exact_tip(self.query("tip"),a,pre,9); self.finish_phase(); self.retain_v2(a_rows,"a")
            self.start_phase("b",port); b_rows,_=self.phase_wait(lambda p:p[0],10,"B loaded ready")
            checked_loaded(a_rows,b_rows)
            retained=one(a_rows,"node-state")["projection"]["anchor"]
            checked_bootstrap(b_rows,supplied,{"hash":retained["hash"],"slot":decimal(retained["slot"])})
            require(not any((self.out/"receipts-b").iterdir()), "journal-loaded readiness creates no diagnostic acknowledgment")
        with self.paused("submission"):
            for i,s in enumerate(pair):
                result=self.execute("cardano-cli","conway","transaction","submit","--tx-file",s["signedPath"],"--testnet-magic","1082026","--socket-path","/work/env/socket/node3/sock",timeout=3)
                self.save("v2-submission-"+str(i)+".md",result.stdout+result.stderr)
            until=min(self.deadline,time.monotonic()+2)
            while time.monotonic()<until:
                rows=events(self.read("logs/node3/stdout.log"))
                admitted={s["transactionId"]:[e for e in rows if e["ns"]=="Mempool.AddedTx" and e.get("data",{}).get("tx",{}).get("txid") in (s["transactionId"],s["transactionId"][:8])] for s in pair}
                self.save("v2-relay-admissions.md",admitted)
                if all(admitted.values()): break
                time.sleep(.1)
            else: raise ValueError("pair relay admissions required")
        b_rows,(_,_,b)=self.phase_wait(lambda p:p[2] is not None,20,"B target twelve")
        with self.paused("post"):
            post,after=self.snapshot("post"); self.exact_tip(post,b,pre,12)
            require(before["parameters"]==after["parameters"],"parameter endpoints changed")
        self.finish_phase(); self.retain_v2(b_rows,"b")
        require(time.monotonic()<=self.online_deadline,"combined online time bound")
        require(self.read("configuration.yaml").encode()==self.reference_configuration and
                {n:hashlib.sha256(self.read(n).encode()).hexdigest() for n in self.genesis_hashes}==self.genesis_hashes,"reference source identity unchanged")
        require(parse(self.docker("inspect",self.name).stdout.encode())[0]["Id"]==self.reference_id,"same reference container")
        capture="".join(line for phase in ("seed","a","b") for line in (self.out/("v2-"+phase+"-stdout.md")).read_text().splitlines(keepends=True)
                        if json.loads(line).get("record")=="transfer-range-block")
        require(len(records(capture))==12,"twelve actual originals across distinct processes")
        self.save("scala-sequence-capture.md",capture)
        self.save("v2-combined-audit.md",capture+json.dumps(one(b_rows,"node-state"),separators=(",",":"))+"\n")
        evidence={str(i):[e for e in events(self.read(f"logs/node{i}/stdout.log")) if e["ns"].startswith("Mempool.") or e["ns"].startswith("TxSubmission.")] for i in (1,2,3)}
        self.save("sequence-pair-admissions.md",pair_admissions(evidence,[s["transactionId"] for s in pair]))
        self.manifest("coherent-sequence-oracle.pending.md","coherent-sequence-oracle-v1",ORACLE_PINS)
        require(not (self.out/"coherent-sequence-oracle.md").exists(),"oracle cannot overwrite")
        (self.out/"coherent-sequence-oracle.pending.md").replace(self.out/"coherent-sequence-oracle.md")
        self.phase="audit"; self.last_observer_stdout=""; self.observer_attempted=True
        result=self.docker("run","--rm","--pull=never","--name",self.name+"-scala","--network=none","--cpus=1","--memory=1g","--memory-swap=1g",
            "--pids-limit=128","--cap-drop=ALL","--security-opt=no-new-privileges","--user","1000:1000","--read-only","--tmpfs","/tmp:size=64m",
            "-v",str(self.args.scala_repo)+":/work:ro","-v",str(self.out)+":/evidence:ro","-w","/work","--entrypoint=/bin/sh",JDK,"-c",
            'exec java -XX:ActiveProcessorCount=1 -Xmx512m -cp "$(cat app/target/runtime-classpath.txt)" lab.Main node-audit /evidence /evidence /evidence/v2-combined-audit.md 2 12',check=False,timeout=65)
        self.observer_attempted=False; self.save("v2-audit-stdout.md",result.stdout); self.save("v2-audit-stderr.md",result.stderr)
        require(result.returncode==0,"complete offline reference audit failed")
        report=one(records(result.stdout),"node-audit",True); audit_report(report,b,pair)
        require(integer(report.get("transactionBlockIndex"),9,11),"pair only in new B continuation")
        require(exact(verify_source_build(self.args.scala_repo,self.args.source_pin,self.args.source_pin_sha256),self.source_pin),"source/build pin changed")
        return dict(handshake=handshake,sustainedDurableGracefulResume=True,phaseA=a,phaseB=b,independentAudit=report,
                    actualNewLiveContinuation=True,diagnosticReceiptIsResumeAuthority=False,singleAcquiredSnapshot=False,
                    powerLossRecovery=False,liveForkClaim=False,epochTransition=False)

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--plan",action="store_true")
    parser.add_argument("--fixture-profile",choices=[FIXTURE]); parser.add_argument("--reference-image")
    parser.add_argument("--source-pin"); parser.add_argument("--source-pin-sha256")
    parser.add_argument("--output"); parser.add_argument("--scala-repo"); parser.add_argument("--seconds",type=int,default=480)
    args=parser.parse_args()
    if args.plan: print(PLAN); return
    if not all((args.fixture_profile,args.reference_image,args.output,args.scala_repo,args.source_pin,args.source_pin_sha256)): parser.error("explicit fresh fixture/image/output/build required")
    if not 420<=args.seconds<=480: parser.error("workload bound420..480 seconds")
    args.capture=False
    signal.signal(signal.SIGTERM,lambda *_: (_ for _ in ()).throw(KeyboardInterrupt()))
    SustainedDurableRunner(args).run()

if __name__=="__main__": main()
