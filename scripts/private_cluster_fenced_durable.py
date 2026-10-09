#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Adaptive durable fence candidate: complete bounded driver, launch remains gated."""
import argparse
from dataclasses import dataclass
import hashlib
import json
import os
from pathlib import Path
import tempfile
import time
import shlex

FORMAT="live-completion-fence-v1"
CAPACITY=2
MAX_DEPTH=16
MAX_EVENTS=128
MAX_BYTES=32*1024*1024
TIP_RESPONSE_LIMIT=16*1024
# Diagnostic storage is separate from the original-wire byte budget. The count
# covers >8220 worst-case startup queries at137s/50ms across three nodes, plus
# bounded later queries. Any byte/count overflow is terminal, never eviction.
TIP_EVIDENCE_LIMIT=16*1024*1024
TIP_RECORD_LIMIT=16384

def reference_tip(raw,allow_origin=False):
    """Pinned CLI11.2.3.0 Output.hs197-279 omits ALL point keys at origin.

    https://github.com/IntersectMBO/cardano-cli/blob/cardano-cli-11.2.3.0/cardano-cli/src/Cardano/CLI/Type/Output.hs
    Only this fixture's complete genesis metadata is retryable at startup.
    Missing/partial/null point fields are never converted into a concrete tip.
    A complete point with block or slot zero is concrete, not origin. Optional
    metadata is scalar-checked; concrete geometry is not inferred here.
    """
    row=strict_json(raw,TIP_RESPONSE_LIMIT)
    require(type(row) is dict,"tip must be a JSON object")
    points={'block','slot','hash'}; present=points & set(row)
    metadata={'era','epoch','slotInEpoch','slotsToEpochEnd','syncProgress'}
    require(set(row)<=points|metadata,"unknown reference tip fields")
    for key in ('epoch','slotInEpoch','slotsToEpochEnd'):
        if key in row:
            require(type(row[key]) is int and 0<=row[key]<2**64,"bounded tip metadata: "+key)
    if 'syncProgress' in row:
        import re
        from decimal import Decimal
        value=row['syncProgress']
        require(type(value) is str and len(value)<=8 and re.fullmatch(r'(?:0|[1-9][0-9]?|100)(?:\.[0-9]{1,4})?',value) is not None
                and Decimal(value)<=100,"tip percentage text")
    if not present:
        required={'era','epoch','slotInEpoch','slotsToEpochEnd'}
        require(allow_origin and required<=set(row)<=required|{'syncProgress'},"startup origin metadata fields")
        require(row['era']=='Conway' and type(row['epoch']) is int and row['epoch']==0
                and type(row['slotInEpoch']) is int and row['slotInEpoch']==0
                and type(row['slotsToEpochEnd']) is int and row['slotsToEpochEnd']==1000,
                "startup origin fixture geometry")
        return None
    require(present==points,"partial tip point fields; missing "+','.join(sorted(points-present)))
    require(row.get('era')=='Conway' and type(row.get('epoch')) is int and 0<=row['epoch']<2**64,"Conway reference tip")
    require(type(row['block']) is int and 0<=row['block']<2**64 and type(row['slot']) is int and 0<=row['slot']<2**64,
            "bounded concrete tip numbers")
    Point(row['block'],row['slot'],row['hash'])
    return row

def require(ok,why):
    if not ok: raise ValueError(why)

def hex32(value):
    return type(value) is str and len(value)==64 and all(x in "0123456789abcdef" for x in value)

def canonical(value):
    return json.dumps(value,sort_keys=True,separators=(",",":"),allow_nan=False).encode()

def sha(raw): return hashlib.sha256(raw).hexdigest()

@dataclass(frozen=True)
class Point:
    block: int
    slot: int
    hash: str
    def __post_init__(self):
        require(type(self.block) is int and 0<=self.block<2**64 and type(self.slot) is int and 0<=self.slot<2**64
                and hex32(self.hash),"bounded concrete point")

@dataclass(frozen=True)
class Original:
    point: Point
    parent: str
    header_sha: str
    block_sha: str
    transactions: tuple=()
    def __post_init__(self):
        require(all(hex32(v) for v in (self.parent,self.header_sha,self.block_sha)),"original byte commitments")
        require(type(self.transactions) is tuple and len(self.transactions)<=2
                and all(hex32(t) for t in self.transactions),"bounded original transaction identities")

@dataclass(frozen=True)
class Role:
    node: int
    process: str
    database: str
    forging: bool
    def __post_init__(self):
        require(type(self.node) is int and self.node in (1,2) and hex32(self.process) and hex32(self.database)
                and type(self.forging) is bool,"owned process/database role")

@dataclass(frozen=True)
class Barrier:
    previous: tuple
    replacements: tuple
    exited: tuple
    closed_connections: tuple
    tips: tuple
    def checked(self,forging):
        require(len(self.previous)==len(self.replacements)==2 and {r.node for r in self.previous}=={1,2}
                and {r.node for r in self.replacements}=={1,2},"both owned role changes")
        old={r.node:r for r in self.previous}; new={r.node:r for r in self.replacements}
        require(set(self.exited)==set(self.closed_connections)=={r.process for r in self.previous}
                and len(self.exited)==len(self.closed_connections)==2,"both exits and old TCP generations acknowledged")
        require(all(new[n].database==old[n].database and new[n].process!=old[n].process
                    and new[n].forging is forging for n in (1,2)) and len({x.process for x in self.replacements})==2
                and not ({x.process for x in self.previous}&{x.process for x in self.replacements}),"same databases and exact replacement roles")
        if not forging:
            require(len(self.tips)==3 and len(set(self.tips))==1,"three observed converged tips")
            return self.tips[0]
        # Forging can produce immediately during the upgrade. Only the later
        # keyless barrier selects the point; upgrade tips never select a fence.
        return None

@dataclass(frozen=True)
class Fence:
    fence_id: str
    phase: str
    context_id: str
    depth: int
    point: Point
    def bytes(self):
        require(hex32(self.fence_id) and hex32(self.context_id) and self.phase in ("A","B")
                and type(self.depth) is int and 9<=self.depth<=MAX_DEPTH,"bounded process-bound fence")
        fields=(("version",FORMAT),("fenceId",self.fence_id),("phase",self.phase),("contextId",self.context_id),
                ("depth",str(self.depth)),("blockNo",str(self.point.block)),("slot",str(self.point.slot)),("hash",self.point.hash))
        return "".join(k+"="+v+"\n" for k,v in fields).encode("ascii")

def publish_fence(path,fence):
    """Atomic new immutable artifact; an existing path is never overwritten."""
    path=Path(path); parent=path.parent
    require(parent.is_dir() and not parent.is_symlink() and parent.resolve()==parent.absolute(),"real absolute fence directory")
    require(not path.exists() and not path.is_symlink(),"fence path already exists")
    raw=fence.bytes(); require(len(raw)<=2048,"bounded fence bytes")
    fd,name=tempfile.mkstemp(prefix=".fence-",dir=parent)
    try:
        with os.fdopen(fd,"wb") as stream:
            stream.write(raw); stream.flush(); os.fsync(stream.fileno())
        os.link(name,path) # Atomic publication, with no replacement race.
        directory=os.open(parent,os.O_RDONLY|os.O_DIRECTORY)
        try: os.fsync(directory)
        finally: os.close(directory)
    finally:
        Path(name).unlink(missing_ok=True)
    return sha(raw)

def claim_depth(view,depth,confirmation):
    require(view.get("confirmation")==confirmation and view.get("depth")==depth and view.get("revision")==depth
            and view.get("compactedBlocks")==max(1,depth-2) and view.get("retainedBlocks")==min(2,depth-1),
            "exact v2 depth/revision/retained tuple")
    require(view.get("potentiallyOlderThanDisk") is False and view.get("externalReceiptStale") is False
            and view.get("cleanupFailure") is None,"no storage/receipt/cleanup uncertainty")
    claim=view.get("fullClaim",{}); token=claim.get("token",{})
    require(token.get("generation")==str(0 if depth==2 else 2*depth-5)
            and claim.get("revision")==str(depth) and claim.get("compactedBlocks")==str(max(1,depth-2))
            and hex32(token.get("sessionId")) and hex32(claim.get("finalId")),"complete claim counters")
    require(type(view.get("projection")) is dict and hex32(view.get("processId")),"complete projection and process identity")

class Protocol:
    """Fail-closed controller ordering, separate from ledger/native verification.

    Production adapters must supply independently verified original headers,
    native acquired packets and process identities. These guards do not turn
    synthetic dictionaries into cryptographic evidence.
    """
    def __init__(self,context,epoch_length,seed_pre,seed_originals,seed_barrier):
        require(hex32(context) and type(epoch_length) is int and epoch_length>400,"explicit context/epoch geometry")
        self.context=context; self.epoch_length=epoch_length; self.anchor=seed_pre
        self.originals=[]; self.roles=seed_barrier.replacements; self.stage="seed"
        self._append(seed_originals,seed_pre.slot,empty=True)
        require(len(self.originals)==2 and seed_barrier.checked(False)==self.tip,"exact last-two seed ancestry/frozen point")
        self.fences={}; self.acks={}; self.ids=set(); self.ready_slot=None; self.phase_start=2
        self.events=0; self.returned_bytes=0

    @property
    def tip(self): return self.originals[-1].point if self.originals else self.anchor

    def _append(self,originals,ready_slot,empty=False):
        require(type(originals) in (tuple,list) and len(self.originals)+len(originals)<=MAX_DEPTH,"whole-window depth bound")
        for original in originals:
            require(original.parent==self.tip.hash and original.point.block==self.tip.block+1
                    and original.point.slot>max(self.tip.slot,ready_slot)
                    and original.point.slot//self.epoch_length==self.anchor.slot//self.epoch_length,
                    "complete contiguous same-epoch originals, newly produced after readiness")
            require(not empty or not original.transactions,"empty bootstrap/A original")
            self.originals.append(original)

    def ready(self,phase,view,clock_upper_slot):
        require((phase,self.stage) in (("A","seed"),("B","A-exited")),"ready stage order")
        require(type(clock_upper_slot) is int and clock_upper_slot>=self.tip.slot,"freshness upper clock slot")
        require(all(not role.forging for role in self.roles),"readiness only while keyless")
        depth=len(self.originals); confirmation="v2-acknowledged" if phase=="A" else "v2-loaded-verified"
        claim_depth(view,depth,confirmation)
        require(view.get("contextId")==self.context and view.get("point")==self.tip,"ready exact frozen context/point")
        require(view["processId"] not in self.ids,"distinct runtime process")
        self.ids.add(view["processId"])
        if phase=="B":
            a=self.acks["A"]["view"]
            require(canonical(view["fullClaim"])==canonical(a["fullClaim"])
                    and canonical(view["projection"])==canonical(a["projection"]),"loaded exact full claim and projection")
            require(view.get("receiptPath") is None and view.get("receiptSha256") is None,"loading creates no acknowledgment receipt")
        self.ready_slot=clock_upper_slot; self.phase_start=depth; self.runtime=view; self.stage=phase+"-ready"

    def produce(self,phase,barrier):
        require(self.stage==phase+"-ready" and barrier.previous==self.roles,"producer upgrade after ready")
        barrier.checked(True)
        self.roles=barrier.replacements; self.stage=phase+"-producing"

    def freeze(self,phase,barrier,originals,fence_id):
        require(self.stage==phase+"-producing" and barrier.previous==self.roles,"demotion before fence declaration")
        point=barrier.checked(False)
        self._append(originals,self.ready_slot,empty=phase=="A")
        depth=len(self.originals)
        require(point==self.tip,"frozen point equals complete supplied original prefix")
        require(9<=depth<=12 if phase=="A" else self.phase_start+3<=depth<=16,"bounded minimum and maximum phase depths")
        require(phase not in self.fences and fence_id not in {f.fence_id for f in self.fences.values()},"one fresh immutable phase fence")
        fence=Fence(fence_id,phase,self.context,depth,point); fence.bytes()
        self.roles=barrier.replacements; self.fences[phase]=fence; self.stage=phase+"-frozen"
        return fence

    def acknowledge(self,phase,ack):
        require(self.stage==phase+"-frozen","ack only after frozen point declaration")
        fence=self.fences[phase]; view=ack.get("view",{})
        claim_depth(view,fence.depth,"v2-acknowledged")
        require(ack.get("fenceId")==fence.fence_id and ack.get("phase")==phase
                and ack.get("fenceSha256")==sha(fence.bytes()) and ack.get("resourcesFinalized") is True,
                "exact fence acknowledgment after resource finalization")
        require(view.get("point")==fence.point and view.get("processId")==self.runtime["processId"]
                and view.get("contextId")==self.context and ack.get("projectionSha256")==sha(canonical(view["projection"])),
                "ack entire point/process/projection identity")
        if phase=="B":
            require(view["fullClaim"]["token"]["sessionId"]!=self.acks["A"]["view"]["fullClaim"]["token"]["sessionId"],
                    "new B successor session required")
        self.acks[phase]=ack; self.stage=phase+"-acknowledged"

    def exited(self,phase,process,code,checkpoint_sha,journal_sha):
        require(self.stage==phase+"-acknowledged" and process==self.runtime["processId"] and type(code) is int and code==0
                and hex32(checkpoint_sha) and hex32(journal_sha),"owned graceful exit and separate retained storage hashes")
        self.stage=phase+"-exited"

    def charge(self,events,returned_bytes):
        require(type(events) is int and events>=0 and type(returned_bytes) is int and returned_bytes>=0,"nonnegative cumulative charges")
        self.events+=events; self.returned_bytes+=returned_bytes
        require(self.events<=MAX_EVENTS and self.returned_bytes<=MAX_BYTES,"cumulative event/byte limit")

    def oracle(self,packet,pair):
        require(self.stage=="B-exited" and packet.get("requestedPoint")==self.tip
                and packet.get("firstPoint")==self.tip and packet.get("finalPoint")==self.tip
                and packet.get("firstBlockNo")==self.tip.block==packet.get("finalBlockNo")
                and packet.get("specificPointAcquire") is True and packet.get("fallback") is False,
                "one exact final-point oracle, never latest substitution")
        require(len(pair)==2 and len(set(pair))==2 and all(hex32(x) for x in pair),"two original submitted identities")
        # Pair membership/order comes from independent Scala parsing of the
        # original block bodies, never from intended submissions or tx counts.
        self.submitted_pair=tuple(pair); self.stage="oracle"

    def audited(self,report):
        require(self.stage=="oracle" and report.get("referencePostStateMatched") is True
                and report.get("onlineProjectionMatched") is True and report.get("capturedBlocks")==len(self.originals)
                and report.get("consensusValidated") is False and report.get("fullLedgerValidated") is False,
                "complete independent bounded reference/online audit")
        ids=report.get("transactionIdsInBlockOrder")
        block_index=report.get("transactionBlockIndex")
        require(type(ids) is list and len(ids)==2 and set(ids)==set(self.submitted_pair) and type(block_index) is int
                and self.phase_start<=block_index<len(self.originals),"independently parsed exact pair in fresh B block")
        self.stage="audited"

def run_stages(ops):
    """Injectable orchestration contract; no unreviewed Docker/native adapter.

    ops owns bounded process resources, exact query acquisition, role barriers,
    originals parsing, fence publication and terminal cleanup. Every exception,
    including cancellation, reaches cleanup. No failed phase is retried.
    """
    try:
        protocol=ops.bootstrap()
        ops.protocol=protocol
        protocol.charge(*ops.wire_totals("bootstrap"))
        for phase in ("A","B"):
            ops.remaining_wire(MAX_EVENTS-protocol.events,MAX_BYTES-protocol.returned_bytes)
            view,clock=ops.start_ready(phase,protocol)
            protocol.ready(phase,view,clock)
            protocol.produce(phase,ops.upgrade(phase,protocol))
            if phase=="B": ops.submit_pair(protocol)
            ops.await_minimum(phase,9 if phase=="A" else protocol.phase_start+3)
            barrier,originals,fence_id=ops.demote(phase,protocol)
            fence=protocol.freeze(phase,barrier,originals,fence_id)
            ops.publish(phase,fence)
            protocol.acknowledge(phase,ops.await_ack(phase,fence))
            protocol.exited(phase,*ops.exit_and_retain(phase))
            protocol.charge(*ops.wire_totals(phase))
        packet,pair=ops.query_final(protocol)
        protocol.oracle(packet,pair)
        # Sixth role transition restores production before growth/audit overlap.
        ops.restore_production_and_begin_growth(protocol)
        protocol.audited(ops.audit(protocol))
        ops.verify_two_epoch_growth(protocol)
        return protocol
    except BaseException as error:
        recorder=getattr(ops,'record_failure',None)
        if recorder is not None:
            try: recorder(getattr(ops,'diagnostic_stage','controller'),error)
            except BaseException as diagnostic:
                raise BaseExceptionGroup('controller failure and diagnostic failure',[error,diagnostic])
        raise
    finally:
        ops.cleanup()

def budget_plan(admitted,case_started,epoch_end,workload=500):
    # Proposed new fixture budget, not live authorization or a timing SLA.
    import math
    require(all(type(x) in (int,float) and math.isfinite(x) for x in (admitted,case_started,epoch_end))
            and admitted>=case_started and workload==500,"proposed finite five-hundred-second case")
    p=admitted+83
    require(p<=epoch_end and admitted+363<=case_started+workload,"complete fenced-stage budget unavailable")
    return dict(admission=admitted,productionDeadline=p-2,productionReservationEnd=p,
        exitRetainDeadline=p+15,oracleDeadline=p+40,tailResumeDeadline=p+45,
        tailDeadline=p+255,caseGuardDeadline=p+280,absoluteCleanupDeadline=case_started+600,
        auditSeconds=65,finalPinSeconds=10,tailOpportunitySeconds=210,
        epochSlots=1000,slotLength=.1,securityParam=5,activeSlotsCoeff=.05,
        randomnessStabilizationWindow=400,guaranteedBlockArrivals=False,
        workloadProposed=500,liveApproved=False)



OWNER_LABEL="lab.cardano-fenced.owner"
NATIVE_IMAGE="sha256:29cdc34ede8cd9716d1f40a8200447355ae210c05a539c1b0262ccc5fcaf183c"
PIDFD="/opt/reference/libexec/restart-pidfd"

def strict_json(raw,limit=MAX_BYTES):
    from private_cluster_sustained_durable import parse
    return parse(raw if isinstance(raw,bytes) else raw.encode(),limit)

def regular(path,limit):
    path=Path(path)
    require(path.is_absolute() and path.resolve()==path and not path.is_symlink(),"canonical regular input")
    fd=os.open(path,os.O_RDONLY|os.O_NOFOLLOW)
    try:
        import stat
        st=os.fstat(fd); require(stat.S_ISREG(st.st_mode) and 0<st.st_size<=limit,"bounded regular input")
        raw=os.read(fd,limit+1)
        require(len(raw)==st.st_size,"input size changed")
        return raw
    finally: os.close(fd)

class OwnedDocker:
    """Local owned resources only; uncertainty is resolved by owner-label inventory.

    Every operation uses an absolute enclosing deadline. Query cleanup shares
    its original 25-second boundary; it never receives a fresh five seconds.
    """
    def __init__(self,owner,evidence,absolute_deadline,clock=time.monotonic,invoke=None):
        from private_cluster_fork import bounded_run
        require(hex32(owner),"random explicit owner identity")
        self.owner=owner; self.evidence=Path(evidence); self.deadline=absolute_deadline
        self.clock=clock; self.invoke=invoke or bounded_run; self.containers={}; self.volumes=set()
    def call(self,*args,deadline=None,check=True,data=None,limit=MAX_BYTES,result_tap=None):
        end=min(self.deadline,deadline if deadline is not None else self.deadline)
        left=end-self.clock(); require(left>0,"owned operation deadline exhausted")
        result=self.invoke(["docker","--host","unix:///var/run/docker.sock",*map(str,args)],left,data,limit)
        if result_tap is not None: result_tap(result)
        require(self.clock()<=end,"owned operation deadline exceeded")
        if check: require(result.returncode==0,"owned Docker operation failed: "+result.stderr[:256])
        return result
    def inspect(self,cid,deadline=None):
        require(hex32(cid),"immutable container ID")
        rows=strict_json(self.call("inspect",cid,deadline=deadline).stdout)
        require(type(rows) is list and len(rows)==1 and rows[0].get("Id")==cid
                and rows[0].get("Config",{}).get("Labels",{}).get(OWNER_LABEL)==self.owner,"owned container identity")
        return rows[0]
    def create(self,key,args,deadline):
        require(key.isascii() and key.replace("-","").isalnum() and key not in self.containers,"fresh resource name")
        # Label search remains authoritative when create succeeds but stdout is lost.
        self.containers[key]=None
        cid=self.call("create","--name",self.owner[:16]+"-"+key,"--label",OWNER_LABEL+"="+self.owner,"--label",OWNER_LABEL+".resource="+key,
                      *args,deadline=deadline).stdout.strip()
        require(hex32(cid),"created immutable CID")
        self.containers[key]=cid; self.inspect(cid,deadline)
        self.call("start",cid,deadline=deadline)
        return cid
    def exec(self,cid,*args,deadline,data=None,check=True,limit=MAX_BYTES,result_tap=None):
        self.inspect(cid,deadline)
        return self.call("exec",*(('-i',) if data is not None else ()),cid,*args,
                         deadline=deadline,data=data,check=check,limit=limit,result_tap=result_tap)
    def remove(self,cid,deadline):
        self.inspect(cid,deadline)
        self.call("rm","--force",cid,deadline=deadline)
        rows=self.call("ps","--all","--quiet","--no-trunc","--filter","label="+OWNER_LABEL+"="+self.owner,
                       deadline=deadline).stdout.split()
        require(cid not in rows,"owned container removal unconfirmed")
    def remove_key(self,key,deadline):
        ids=self.call("ps","--all","--quiet","--no-trunc","--filter","label="+OWNER_LABEL+"="+self.owner,
                      "--filter","label="+OWNER_LABEL+".resource="+key,deadline=deadline).stdout.split()
        require(len(ids)<=1,"unique owned resource name")
        for cid in ids:
            row=self.inspect(cid,deadline)
            require(row['Config']['Labels'].get(OWNER_LABEL+'.resource')==key,"exact resource cleanup identity")
            self.remove(cid,deadline)
    def socket_volume(self,deadline):
        name=self.owner[:16]+"-sockets"; self.volumes.add(name)
        self.call("volume","create","--label",OWNER_LABEL+"="+self.owner,name,deadline=deadline)
        rows=strict_json(self.call("volume","inspect",name,deadline=deadline).stdout)
        require(len(rows)==1 and rows[0].get("Name")==name and rows[0].get("Labels",{}).get(OWNER_LABEL)==self.owner,
                "owned socket volume")
        return name
    def cleanup(self):
        failures=[]
        try:
            ids=self.call("ps","--all","--quiet","--no-trunc","--filter","label="+OWNER_LABEL+"="+self.owner).stdout.split()
        except BaseException as error: failures.append(error); ids=[x for x in self.containers.values() if x]
        for cid in ids:
            try: self.remove(cid,self.deadline)
            except BaseException as error: failures.append(error)
        for name in sorted(self.volumes):
            try:
                rows=strict_json(self.call("volume","inspect",name).stdout)
                require(len(rows)==1 and rows[0].get("Labels",{}).get(OWNER_LABEL)==self.owner,"socket volume ownership changed")
                self.call("volume","rm",name)
                names=self.call("volume","ls","--quiet","--filter","label="+OWNER_LABEL+"="+self.owner).stdout.split()
                require(name not in names,"socket volume removal unconfirmed")
            except BaseException as error: failures.append(error)
        absent=False
        try:
            containers=self.call("ps","--all","--quiet","--no-trunc","--filter","label="+OWNER_LABEL+"="+self.owner).stdout.split()
            volumes=self.call("volume","ls","--quiet","--filter","label="+OWNER_LABEL+"="+self.owner).stdout.split()
            absent=not containers and not volumes
            require(absent,"owned resource inventory not empty after cleanup")
        except BaseException as error: failures.append(error)
        record=dict(owner=self.owner,verifiedAbsent=absent,success=absent and not failures,
                    failureTypes=[type(e).__name__ for e in failures],absoluteDeadline=self.deadline,observedAt=self.clock())
        try: (self.evidence/'owned-cleanup.json').write_bytes(canonical(record))
        except BaseException as error: failures.append(error)
        if failures: raise BaseExceptionGroup("owned cleanup failures",failures)

def node_arguments(node,forging):
    require(type(node) is int and node in (1,2,3) and (not forging or node!=3),"two producers and one keyless relay")
    args=["/opt/reference/bin/cardano-node","run","--config","/work/env/configuration.yaml",
          "--topology",f"/work/env/node-data/node{node}/topology.json","--database-path",f"/work/env/node-data/node{node}/db",
          "--socket-path",f"/sockets/node{node}.sock","--host-addr","127.0.0.1","--port",str(5300+node)]
    if forging:
        root=f"/work/env/pools-keys/pool{node}"
        for flag,name in (("--shelley-kes-key","kes.skey"),("--shelley-vrf-key","vrf.skey"),
                          ("--shelley-operational-certificate","opcert.cert"),
                          ("--delegation-certificate","byron-delegation.cert"),("--signing-key","byron-delegate.key")):
            args.extend((flag,root+"/"+name))
    return args+["+RTS","-N1","-M512m","-RTS"]

class ReferenceRoles:
    """Identity-bound paired role changes, with no block-count freeze claim.

    closed_connections identifies terminated process TCP generations, inferred
    from acknowledged/reaped process exit. It is NOT a network or ChainDB queue
    drain proof. Freshness and the independent exact-point oracle remain checks.
    """
    def __init__(self,docker,cid,database_ids,save,tip,clock=time.monotonic):
        require(set(database_ids)=={1,2,3} and all(hex32(x) for x in database_ids.values()),"three fixed database identities")
        self.docker=docker; self.cid=cid; self.databases=database_ids; self.save=save; self.tip=tip; self.clock=clock
        self.active={}; self.serial=0; self.log_bases=[]
    def command(self,*args,deadline,**kwargs): return self.docker.exec(self.cid,*args,deadline=deadline,**kwargs)
    def identity(self,node,deadline):
        from private_cluster_fork import process_identity
        old=self.active[node]; pid=old['pid']
        stat=self.command("cat",f"/proc/{pid}/stat",deadline=deadline).stdout
        argv=self.command("cat",f"/proc/{pid}/cmdline",deadline=deadline).stdout.rstrip('\0').split('\0')
        actual=process_identity(pid,stat,argv,old['argv'])
        require(actual==old['identity'],"reference process changed")
        return actual
    def role(self,node):
        row=self.active[node]
        return Role(node,sha(canonical(row['identity'])),self.databases[node],row['forging'])
    def signal(self,node,deadline):
        old=self.active[node]; identity=self.identity(node,deadline)
        receipt=self.command(PIDFD,str(old['pid']),identity['startTicks'],deadline=deadline)
        self.save(Path(old['base']).name+'-signal.json',dict(identity=identity,returnCode=receipt.returncode,stdout=receipt.stdout,stderr=receipt.stderr))
        require(strict_json(receipt.stdout)==dict(method="pidfd_send_signal",pid=old['pid'],startTicks=int(identity['startTicks']),signal="TERM",sent=True),"exact pidfd TERM receipt")
    def stopped(self,node,deadline):
        old=self.active[node]
        while self.clock()<deadline:
            done=self.command("cat",old['base']+".exit",deadline=deadline,check=False)
            if done.returncode==0:
                require(done.stdout=="0","graceful reference exit")
                # Supervisor reaped the child. Confirm the same PID/start no longer exists.
                stat=self.command("cat",f"/proc/{old['pid']}/stat",deadline=deadline,check=False)
                if stat.returncode==0:
                    tail=stat.stdout.rsplit(') ',1)[1].split()
                    require(tail[19]!=old['identity']['startTicks'],"old reference process still present")
                self.save(Path(old['base']).name+"-exit.json",dict(identity=old['identity'],exitCode=0))
                del self.active[node]; return
            time.sleep(.02)
        raise TimeoutError("paired reference exit deadline")
    def start(self,node,forging,deadline):
        from private_cluster_fork import process_identity
        require(node not in self.active,"old role must exit before replacement")
        self.serial+=1; base=f"/work/fenced-{node}-{self.serial}"; argv=node_arguments(node,forging)
        self.log_bases.append(base)
        shell="umask 077; rm -f -- "+shlex.quote(f"/sockets/node{node}.sock")+"; cd /work/env; "+shlex.join(argv)+" > "+base+".log 2>&1 & p=$!; printf '%s' \"$p\" > "+base+".pid; wait \"$p\"; r=$?; printf '%s' \"$r\" > "+base+".exit"
        self.docker.call("exec","-d",self.cid,"/bin/sh","-c",shell,deadline=deadline)
        while self.clock()<deadline:
            pidrow=self.command("cat",base+".pid",deadline=deadline,check=False)
            if pidrow.returncode==0 and pidrow.stdout.isdigit():
                pid=int(pidrow.stdout)
                stat=self.command("cat",f"/proc/{pid}/stat",deadline=deadline,check=False)
                cmd=self.command("cat",f"/proc/{pid}/cmdline",deadline=deadline,check=False)
                try: identity=process_identity(pid,stat.stdout,cmd.stdout.rstrip('\0').split('\0'),argv)
                except (ValueError,IndexError): pass
                else:
                    self.active[node]=dict(pid=pid,identity=identity,argv=argv,base=base,forging=forging)
                    self.save(Path(base).name+"-start.json",self.active[node]); return
            time.sleep(.02)
        raise TimeoutError("reference replacement identity deadline")
    def change(self,forging,deadline):
        old=tuple(self.role(n) for n in (1,2)); errors=[]
        # Attempt both signals even on cancellation; no replacement until both
        # graceful exits are positively acknowledged.
        for node in (1,2):
            try: self.signal(node,deadline)
            except BaseException as error: errors.append(error)
        for node in (1,2):
            try: self.stopped(node,deadline)
            except BaseException as error: errors.append(error)
        if errors: raise BaseExceptionGroup("paired role shutdown",errors)
        for node in (1,2): self.start(node,forging,deadline)
        tips=()
        if not forging:
            while self.clock()<deadline:
                tips=tuple(self.tip(n,deadline) for n in (1,2,3))
                if len(set(tips))==1: break
                time.sleep(.02)
            require(len(tips)==3 and len(set(tips))==1,"keyless observed convergence deadline")
        result=Barrier(old,tuple(self.role(n) for n in (1,2)),tuple(x.process for x in old),tuple(x.process for x in old),tips)
        result.checked(forging); return result

def verify_packet(directory,requested,helper_sha):
    """Verify exact native artifacts after exit0 AND owned container removal."""
    directory=Path(directory)
    receipt=strict_json(regular(directory/'receipt.json',65536))
    expected={"request.json","capture.json","original-debug-epoch.cbor","original-whole-utxo.cbor","original-protocol.cbor","original-parameters.cbor","derived-ledger.json","derived-utxo.json","derived-parameters.json","derived-protocol.json"}
    require(receipt.get('kind')=='single-acquire-supported-state-oracle' and receipt.get('queryHelperSHA256')==helper_sha
            and receipt.get('point')==dict(slot=requested.slot,hash=requested.hash) and type(receipt.get('blockNo')) is int
            and receipt['blockNo']==requested.block and set(receipt.get('fileSHA256',{}))==expected,"exact point packet receipt")
    for name,digest in receipt['fileSHA256'].items():
        require(hex32(digest) and sha(regular(directory/name,32*1024*1024))==digest,"native packet artifact pin")
    capture=strict_json(regular(directory/'capture.json',MAX_BYTES))
    for name in ('requestedPoint','acquiredPoint','finalPoint'):
        require(capture.get(name)==dict(slot=requested.slot,hash=requested.hash),"native acquired point bracket")
    require(capture.get('blockNo')==requested.block==capture.get('finalBlockNo')
            and capture.get('acquireCount')==1 and capture.get('reacquireCount')==0
            and capture.get('release')=='sent-no-ack',"one native exact acquisition")
    require(all(receipt.get(name) is False for name in ('runtimeImport','monetaryParity','rewardSeedAdmission','fullLedgerValidation')),
            "native packet restricted claims")
    return dict(requestedPoint=requested,firstPoint=requested,finalPoint=requested,firstBlockNo=requested.block,
                finalBlockNo=requested.block,specificPointAcquire=True,fallback=False)

class ExactOracle:
    def __init__(self,docker,volume,client,binary,native,helper_sha,producer_image,producer_sha,clock=time.monotonic):
        require(hex32(helper_sha) and producer_image.startswith('sha256:') and hex32(producer_image[7:]) and hex32(producer_sha),"native and producer pins")
        self.docker=docker; self.volume=volume; self.client=Path(client); self.binary=Path(binary); self.native=Path(native)
        self.helper_sha=helper_sha; self.producer_image=producer_image; self.producer_sha=producer_sha; self.clock=clock
    def capture(self,point,directory,deadline):
        start=self.clock(); end=min(deadline,start+25)
        directory=Path(directory); require(directory.is_absolute() and not directory.exists(),"fresh native packet workspace")
        directory.mkdir(mode=0o700)
        request=dict(schema=1,socket='/sockets/node3.sock',point=dict(slot=point.slot,hash=point.hash),networkMagic=1082026,
                     byronEpochSlots=50,ntcVersion=16,producerBinarySHA256=self.producer_sha,producerImage=self.producer_image)
        (directory/'request.json').write_bytes(canonical(request))
        key='query-'+directory.name; cid=None; primary=None
        try:
            cid=self.docker.create(key,['--network','none','--cpus','1','--memory','1g','--memory-swap','1g','--pids-limit','64',
                '--read-only','--cap-drop','ALL','--security-opt','no-new-privileges','--user',str(os.getuid())+':'+str(os.getgid()),
                '--tmpfs','/tmp:rw,nosuid,nodev,size=32m','--mount','type=volume,src='+self.volume+',dst=/sockets,readonly',
                '--mount','type=bind,src='+str(self.client)+',dst=/client,readonly',
                '--mount','type=bind,src='+str(self.binary)+',dst=/query-helper,readonly',
                '--mount','type=bind,src='+str(self.native)+',dst=/native,readonly',
                '--mount','type=bind,src='+str(directory)+',dst=/packet','--env','LD_LIBRARY_PATH=/native',NATIVE_IMAGE,
                'python3','/client/packet.py','--query-helper','/query-helper','--query-sha256',self.helper_sha,
                '--request','/packet/request.json','--output','/packet/result'],min(end-3,start+22))
            code=self.docker.call('wait',cid,deadline=min(end-3,start+22),limit=1024).stdout.strip()
            require(code=='0',"exact point helper exit failure")
        except BaseException as error: primary=error
        finally:
            try:
                # Creation may have succeeded before inspect/start raised. Resolve
                # that uncertainty inside the SAME query boundary by exact label.
                self.docker.remove_key(key,end)
            except BaseException as error:
                if primary: raise BaseExceptionGroup('query and owned cleanup failed',[primary,error])
                raise
        if primary: raise primary
        result=verify_packet(directory/'result',point,self.helper_sha)
        require(self.clock()<=end,"native packet verification/cleanup exceeded shared deadline")
        return result

def runtime_arguments(phase,port,context,store_id,seed,fence_id,minimum,maximum,seconds,events,byte_budget):
    from private_cluster_node import PROFILE
    require(phase in ('A','B') and hex32(context) and hex32(store_id) and hex32(fence_id),"runtime independent bindings")
    require(type(minimum) is int and type(maximum) is int and (minimum,maximum)==(9,12) if phase=='A'
            else type(minimum) is int and 12<=minimum<=maximum==16,"bounded phase fence depths")
    require(type(port) is int and 0<port<65536 and type(seconds) is int and 0<seconds<=120
            and type(events) is int and 0<events<=MAX_EVENTS and type(byte_budget) is int and 0<byte_budget<=MAX_BYTES,"remaining runtime ceilings")
    args=['node','--profile',PROFILE,'--bootstrap','/evidence','--port',str(port),'--mode','sustained-durable',
          '--rollback-capacity','2','--store-action','create' if phase=='A' else 'resume','--store','/checkpoint',
          '--journal','/journal','--receipts','/receipts-'+phase.lower(),'--store-id',store_id,'--expected-context',context,
          '--blocks',str(maximum),'--seconds',str(seconds),'--events',str(events),'--bytes',str(byte_budget),'--reconnects','0',
          '--audit','true','--completion-fence','/control/fence-'+phase,'--fence-id',fence_id,'--fence-phase',phase,'--minimum-depth',str(minimum)]
    if phase=='A':
        require(type(seed) is dict and hex32(seed.get('sha256')) and isinstance(seed.get('compact'),Point),"exact pinned seed")
        args.extend(('--seed-capture','/evidence/v2-seed-capture.md','--seed-sha256',seed['sha256'],
                     '--compact-slot',str(seed['compact'].slot),'--compact-hash',seed['compact'].hash))
    else: require(seed is None,"journal-only resume rejects seed authority")
    return args



def log_rows(raw):
    from private_cluster_sustained_durable import records
    return records(raw)

def unique_record(rows,name,scope=False):
    selected=[r for r in rows if r.get('scope' if scope else 'record')==name]
    require(len(selected)==1,'exactly one '+name)
    return selected[0]

def normalized_view(row,projection,cid,context,store_id,phase):
    from private_cluster_sustained_durable import binding,checked_claim
    require(row.get('storageVersion')=='v2' and row.get('trustedLocalPrefix') is (phase=='B'),"explicit trusted local compacted prefix")
    expected=binding(context,store_id)
    require(canonical(row.get('storageBinding'))==canonical(expected),"independent complete storage binding")
    checked_claim(row.get('fullClaim'),row,expected)
    tip=row.get('scopedAppliedTip')
    require(type(tip) is dict and set(tip)=={'slot','hash'},"applied concrete point")
    result=dict(row,point=Point(row['blockNo'],tip['slot'],tip['hash']),processId=cid,projection=projection)
    for field in ('potentiallyOlderThanDisk','externalReceiptStale'):
        require(row.get(field,False) is False,"uncertain durable observation")
        result[field]=False
    result['cleanupFailure']=row.get('cleanupFailure')
    return result

def socket_owner_command(pid,socket_path='/sockets/node1.sock'):
    """POSIX shell builtins plus readlink, present in the pinned reference image."""
    require(type(pid) is int and pid>0,"owned socket PID")
    require(type(socket_path) is str and socket_path.startswith('/') and not any(c.isspace() for c in socket_path),"exact socket pathname")
    return ("set -eu; target="+shlex.quote(socket_path)+"; count=0; inode=; "
        "while read -r num refs proto flags kind state candidate path; do "
        "if [ \"$path\" = \"$target\" ]; then count=$((count+1)); inode=$candidate; fi; done < /proc/net/unix; "
        "test \"$count\" -eq 1; case \"$inode\" in ''|*[!0-9]*) exit 1;; esac; "
        "for fd in /proc/"+str(pid)+"/fd/*; do "
        "if [ \"$(readlink \"$fd\" 2>/dev/null || true)\" = \"socket:[$inode]\" ]; then exit 0; fi; done; exit 1")

def applied_transactions(rows,phase,pair,require_marker=False):
    """IDs are Scala observations of accepted original body bytes, not counts.

    This is a controller eligibility guard; the independent final Scala audit
    still compares original bodies, witnesses, grouping and reference state.
    """
    require(phase in ('A','B') and len(pair)==2 and len(set(pair))==2 and all(hex32(x) for x in pair),"two intended marker identities")
    applied=[r for r in rows if r.get('record')=='node-applied']
    require(len(applied)<=MAX_DEPTH,"bounded applied marker observations")
    observed=[]; markers=0; points=set()
    for row in applied:
        ids=row.get('transactionIds'); count=row.get('transactionCount')
        require(type(ids) is list and type(count) is int and count==len(ids)
                and all(hex32(x) for x in ids),"actual applied transaction identities required")
        tip=row.get('scopedAppliedTip')
        require(type(tip) is dict and set(tip)=={'slot','hash'},"applied marker point")
        point=Point(row.get('blockNo'),tip['slot'],tip['hash'])
        require(point not in points,"duplicate applied marker point"); points.add(point)
        if ids:
            require(phase=='B' and len(ids)==2 and len(set(ids))==2 and set(ids)==set(pair),"only both exact marker IDs in one block")
            markers+=1
            require(markers==1,"duplicate marker block")
        observed.append(tuple(ids))
    require(not require_marker or markers==1,"exact marker block missing before fence")
    return applied,observed,markers==1

class RuntimeProcess:
    """One bounded foreground engine in an owned detached container; no restart."""
    def __init__(self,docker,reference,repo,evidence,control,context,store_id,clock=time.monotonic):
        self.docker=docker; self.reference=reference; self.repo=Path(repo); self.evidence=Path(evidence)
        self.control=Path(control); self.context=context; self.store_id=store_id; self.clock=clock
        self.processes={}; self.previous_logs={}; self.final_rows={}; self.current=None
    def start(self,phase,fence_id,minimum,maximum,seed,deadline,events,size,allocation_deadline=None):
        from private_cluster import JDK
        require(phase not in self.processes and not (self.control/('fence-'+phase)).exists(),"new phase/fence path required")
        if phase=='B': require('A' in self.final_rows,"A exit and ownership release before B allocation")
        command=runtime_arguments(phase,5303,self.context,self.store_id,seed,fence_id,minimum,maximum,
                                  min(120,int(deadline-self.clock())),events,size)
        mounts=[]
        for name,target in (('checkpoint','/checkpoint'),('journal','/journal'),('receipts-'+phase.lower(),'/receipts-'+phase.lower())):
            path=self.evidence/name
            if not path.exists(): path.mkdir(mode=0o700)
            require(path.is_dir() and path.resolve()==path and not path.is_symlink(),"real owned durable directory")
            mounts.extend(('--mount','type=bind,src='+str(path)+',dst='+target))
        args=['--network','container:'+self.reference,'--cpus','1','--memory','1g','--memory-swap','1g','--pids-limit','128',
              '--read-only','--cap-drop','ALL','--security-opt','no-new-privileges','--user',str(os.getuid())+':'+str(os.getgid()),
              '--tmpfs','/tmp:rw,nosuid,nodev,size=64m','--log-driver','json-file','--log-opt','max-size=32m','--log-opt','max-file=1',
              '--mount','type=bind,src='+str(self.repo)+',dst=/work,readonly',
              '--mount','type=bind,src='+str(self.evidence)+',dst=/evidence,readonly',
              '--mount','type=bind,src='+str(self.control)+',dst=/control,readonly',*mounts,
              '--workdir','/work','--entrypoint','/bin/sh',JDK,'-c',
              'exec java -XX:ActiveProcessorCount=1 -Xmx512m -cp "$(cat app/target/runtime-classpath.txt)" lab.Main '+shlex.join(command)]
        cid=self.docker.create('runtime-'+phase.lower(),args,min(deadline,allocation_deadline or deadline))
        require(cid not in self.processes.values(),"distinct runtime processes")
        self.processes[phase]=cid; self.previous_logs[phase]=''; self.current=phase
        (self.evidence/('phase-'+phase+'-arguments.json')).write_bytes(canonical(command))
        return cid
    def rows(self,phase,deadline):
        cid=self.processes[phase]; self.docker.inspect(cid,deadline)
        result=self.docker.call('logs',cid,deadline=deadline,limit=MAX_BYTES)
        raw=result.stdout
        require(raw.startswith(self.previous_logs[phase]) and len(raw.encode())+len(result.stderr.encode())<=MAX_BYTES,"bounded immutable full stdout")
        self.previous_logs[phase]=raw
        (self.evidence/('phase-'+phase+'-stdout.md')).write_text(raw)
        (self.evidence/('phase-'+phase+'-stderr.md')).write_text(result.stderr)
        return log_rows(raw)
    def wait_record(self,phase,name,deadline):
        while self.clock()<deadline:
            rows=self.rows(phase,deadline); selected=[r for r in rows if r.get('record')==name]
            require(len(selected)<=1,"duplicate lifecycle record")
            if selected: return rows,selected[0]
            state=self.docker.inspect(self.processes[phase],deadline)['State']
            if not state['Running']:
                (self.evidence/('phase-'+phase+'-exit.json')).write_bytes(canonical(dict(exitCode=state.get('ExitCode'),awaitedRecord=name)))
            require(state['Running'],"runtime exited before "+name)
            time.sleep(.02)
        raise TimeoutError('runtime lifecycle deadline: '+name)
    def ready(self,phase,fence_id,minimum,maximum,deadline):
        rows,row=self.wait_record(phase,'node-fence-ready',deadline)
        require(row.get('fenceId')==fence_id and row.get('phase')==phase and row.get('minimumDepth')==minimum
                and row.get('maximumDepth')==maximum,"exact process fence readiness binding")
        require(type(row.get('projection')) is dict,"same-snapshot ready projection required")
        initial=[r for r in rows if r.get('record')=='node-rollback' and r.get('initialIntersection') is True]
        require(len(initial)==1 and rows.index(initial[0])<rows.index(row),"checked intersection before fence readiness")
        if phase=='B':
            loaded=unique_record(rows,'node-loaded')
            require(rows.index(loaded)<rows.index(initial[0]) and canonical(loaded.get('projection'))==canonical(row['projection'])
                    and canonical(loaded.get('fullClaim'))==canonical(row.get('fullClaim')),"loaded complete state before peer and unchanged no-op")
        return normalized_view(row,row['projection'],self.processes[phase],self.context,self.store_id,phase)
    def acknowledge(self,phase,fence,deadline):
        _,ack=self.wait_record(phase,'node-fence-ack',deadline)
        # ACK is only usable after actual owned process exit0, final logs and
        # confirmed container removal. No successor process starts beforehand.
        cid=self.processes[phase]
        status=self.docker.call('wait',cid,deadline=deadline,limit=1024)
        (self.evidence/('phase-'+phase+'-exit.json')).write_bytes(canonical(dict(stdout=status.stdout,stderr=status.stderr,returnCode=status.returncode)))
        rows=self.rows(phase,deadline)
        require(status.stdout.strip()=='0',"runtime graceful exit0")
        ack=unique_record(rows,'node-fence-ack')
        state=unique_record(rows,'node-state'); outcome=unique_record(rows,'bounded-node-outcome',True)
        require(rows.index(state)<rows.index(ack)<rows.index(outcome) and outcome.get('typedStop')=='TargetReached'
                and outcome.get('peerResourcesFinalized') is True and ack.get('resourcesFinalized') is True
                and outcome.get('potentiallyOlderThanDisk') is False and outcome.get('externalReceiptStale') is False
                and outcome.get('cleanupFailure') is None and type(outcome.get('peerOpens')) is int
                and outcome['peerOpens']==outcome.get('peerCloses'),"fence terminal/resource lifecycle")
        fields=('contextId','stateId','revision','depth','compactedBlocks','retainedBlocks','blockNo','derivedAnchorId',
                'scopedAppliedTip','confirmation','confirmedGeneration','receiptPath','receiptSha256','fullClaim',
                'storageBinding','storageVersion','trustedLocalPrefix')
        require(all(k in ack and k in outcome and canonical(ack[k])==canonical(outcome[k]) for k in fields),
                "terminal outcome must bind exact ACK state and claim")
        require(all(state.get(k)==str(ack[k]) for k in ('revision','depth','compactedBlocks'))
                and state.get('derivedAnchorId')==ack['derivedAnchorId'],"final projection record counters")
        projection=state.get('projection')
        require(type(projection) is dict and ack.get('projectionSha256')==sha(canonical(projection))
                and projection.get('tupleId')==ack['stateId'] and projection.get('contextId')==self.context
                and projection.get('appliedTip')==dict(slot=str(ack['slot']),hash=ack['hash']),"complete final projection digest")
        self.docker.remove(cid,deadline)
        self.final_rows[phase]=rows
        value=normalized_view(ack,projection,cid,self.context,self.store_id,phase)
        return dict(view=value,fenceId=ack.get('fenceId'),phase=ack.get('phase'),fenceSha256=ack.get('fenceSha256'),
                    resourcesFinalized=ack.get('resourcesFinalized'),projectionSha256=ack.get('projectionSha256'))
    def retain(self,phase):
        from private_cluster_sustained_durable import receipt,binding
        rows=self.final_rows[phase]
        for row in rows:
            if row.get('confirmation')!='v2-acknowledged': continue
            ref=row.get('receiptPath')
            require(type(ref) is str and Path(ref).parent==Path('/receipts-'+phase.lower()),"phase-local diagnostic receipt")
            receipt(regular(self.evidence/('receipts-'+phase.lower())/Path(ref).name,8192),row['receiptSha256'],row,binding(self.context,self.store_id))
        digests=[]
        for directory,name,limit in (('checkpoint','local-derived.bin',40*1024*1024),('journal','controller.bin',32768)):
            raw=regular(self.evidence/directory/name,limit)
            target=self.evidence/('phase-'+phase+'-'+directory+'.bin')
            with target.open('xb') as stream: stream.write(raw); stream.flush(); os.fsync(stream.fileno())
            digests.append(sha(raw))
        return self.processes[phase],0,*digests
    def wire(self,phase):
        row=unique_record(self.final_rows[phase],'bounded-node-outcome',True)
        require(type(row.get('events')) is int and type(row.get('returnedBytes')) is int,"reported bounded wire charges")
        return row['events'],row['returnedBytes']

def range_capture_rows(text,start,end,maximum):
    rows=log_rows(text); receipt=unique_record(rows,'reference-range-capture',True)
    raw=[r for r in rows if r.get('record')=='transfer-range-block']
    require(receipt.get('passed') is True and receipt.get('acquisitionOnly') is True and len(raw)==receipt.get('capturedBlocks')
            and 1<=len(raw)<=maximum<=8,"complete bounded acquisition receipt")
    previous=start; checked=[]
    for row in raw:
        point=Point(row['blockNo'],row['slot'],row['headerHash'])
        require(row['parentHash']==previous.hash and point.block==previous.block+1 and point.slot>previous.slot,"range original continuity")
        header=bytes.fromhex(row['headerEnvelopeHex']); block=bytes.fromhex(row['rawBlockHex'])
        require(0<len(header)<=65535 and 0<len(block)<=1024*1024,"per-original wire bound")
        checked.append(Original(point,row['parentHash'],sha(header),sha(block))); previous=point
    require(previous==end,"exact declared range endpoint")
    events,size=receipt.get('receivedEvents'),receipt.get('originalBytes')
    require(type(events) is int and 0<events<=MAX_EVENTS and type(size) is int and 0<size<=MAX_BYTES,"range aggregate charges")
    return raw,checked,(events,size)



class LiveOps:
    """Complete candidate wiring. Constructing is offline; run_stages launches.

    The public CLI deliberately remains plan-only until the complete candidate
    and explicit500/600 profile receive launch clearance. Dependencies are the
    reviewed ordinary Node CLI, range capture, native packet mapper and audit.
    """
    def __init__(self,args,clock=time.monotonic):
        self.args=args; self.clock=clock; self.started=clock(); self.deadline=self.started+500
        self.out=Path(args.output).absolute(); self.repo=Path(args.scala_repo).resolve()
        require(self.out.parent.resolve()==self.out.parent and not self.out.exists() and not self.out.is_relative_to(self.repo),"fresh private evidence outside checkout")
        self.out.mkdir(mode=0o700); self.owner=os.urandom(32).hex()
        self.docker=OwnedDocker(self.owner,self.out,self.started+600,clock=clock)
        self.context_dir=self.out/'context'; self.control=self.out/'control'; self.control.mkdir(mode=0o700)
        self.store_id=os.urandom(32).hex(); self.fence_ids={phase:os.urandom(32).hex() for phase in ('A','B')}
        self.remaining=(MAX_EVENTS,MAX_BYTES); self.totals={}; self.raw_seed=[]; self.phase_raw={}; self.phase_points={}
        self.roles=None; self.runtime=None; self.ref=None; self.budget=None; self.stage_deadline=self.deadline
        self.tip_sequence=0; self.tip_evidence_bytes=0; self.last_tip_response=None; self.diagnostic_stage='bootstrap-setup'
        self.tip_directory=self.out/'tip-observations'; self.tip_directory.mkdir(mode=0o700)
    def save(self,name,value):
        require(Path(name).name==name,"evidence basename")
        (self.out/name).write_text(value if isinstance(value,str) else json.dumps(value,indent=2,allow_nan=False))
    def execute(self,*args,**kwargs):
        end=min(self.stage_deadline,self.clock()+kwargs.pop('timeout',10))
        return self.docker.exec(self.ref,*args,deadline=end,**kwargs)
    def read(self,name): return self.execute('head','-c','4194305','/work/env/'+name).stdout
    def write_json(self,name,value):
        require(name in ('byron-genesis.json','shelley-genesis.json','configuration.yaml',*(f'node-data/node{n}/topology.json' for n in (1,2,3))),"owned fixture configuration")
        self.execute('/bin/sh','-c','cat > '+shlex.quote('/work/env/'+name),data=json.dumps(value))
    def hashes(self,paths): return {path:self.execute('sha256sum',path).stdout.split()[0] for path in paths}
    def relay_query(self,kind,*args):
        return self.execute('cardano-cli','conway','query',kind,'--testnet-magic','1082026','--socket-path','/sockets/node3.sock',*args).stdout
    def tip_evidence(self,name,value):
        raw=canonical(value)
        require(self.tip_evidence_bytes+len(raw)<=TIP_EVIDENCE_LIMIT,"cumulative tip evidence bound")
        self.tip_evidence_bytes+=len(raw)
        with (self.tip_directory/name).open('xb') as stream: stream.write(raw)
    def record_failure(self,stage,error):
        # First failure survives all subsequent diagnostic/cleanup failures.
        path=self.out/'terminal-diagnostic.json'
        value=dict(stage=stage,exceptionType=type(error).__name__,message=str(error)[:512],
                   observedMonotonic=self.clock(),tipSequence=self.tip_sequence,lastTipResponse=self.last_tip_response)
        raw=canonical(value); require(len(raw)<=8192,"terminal diagnostic bound")
        try:
            with path.open('xb') as stream: stream.write(raw)
        except FileExistsError: pass
    def query_tip(self,node,end,allow_origin):
        require(type(node) is int and node in (1,2,3),"owned reference tip node")
        require(self.tip_sequence<TIP_RECORD_LIMIT,"tip response count bound")
        self.tip_sequence+=1; sequence=self.tip_sequence; began=self.clock()
        base=f'tip-{sequence:06d}'
        phase='startup' if allow_origin else 'concrete'
        self.diagnostic_stage=phase+'-tip-query'
        retained=False
        def retain(result):
            nonlocal retained
            require(not retained,"one raw result per query")
            out=result.stdout.encode('utf-8'); err=result.stderr.encode('utf-8')
            name=base+'-response.json'
            self.tip_evidence(name,dict(sequence=sequence,node=node,phase=phase,startedMonotonic=began,
                finishedMonotonic=self.clock(),deadline=end,returnCode=result.returncode,
                stdout=result.stdout[:TIP_RESPONSE_LIMIT],stderr=result.stderr[:TIP_RESPONSE_LIMIT],
                stdoutBytes=len(out),stderrBytes=len(err),stdoutSHA256=sha(out),stderrSHA256=sha(err),
                truncated=len(out)+len(err)>TIP_RESPONSE_LIMIT))
            self.last_tip_response=str(Path('tip-observations')/name); retained=True
        try:
            result=self.docker.exec(self.ref,'cardano-cli','conway','query','tip','--testnet-magic','1082026',
                '--socket-path',f'/sockets/node{node}.sock',deadline=end,check=False,limit=TIP_RESPONSE_LIMIT,result_tap=retain)
            # Scripted test peers may return directly; production's tap runs
            # before OwnedDocker validates elapsed deadline and return code.
            if not retained: retain(result)
            require(self.clock()<=end,"tip query deadline exceeded")
            require(result.returncode==0,"tip query returned nonzero status")
            require(len(result.stdout.encode())+len(result.stderr.encode())<=TIP_RESPONSE_LIMIT,"tip response byte bound")
            self.diagnostic_stage=phase+'-tip-parse'
            row=reference_tip(result.stdout,allow_origin)
            self.tip_evidence(base+'-classification.json',dict(response=self.last_tip_response,
                classification='startup-origin' if row is None else 'concrete-point'))
            return row
        except BaseException as error:
            try: self.record_failure(self.diagnostic_stage,error)
            except BaseException as diagnostic:
                raise BaseExceptionGroup('tip failure and diagnostic failure',[error,diagnostic])
            raise
    def tip_json(self,node,end):
        return self.query_tip(node,end,False)
    def startup_tip_json(self,node,end):
        # Retry only an absent owned socket or the pinned complete origin shape.
        require(type(node) is int and node in (1,2,3),"owned startup node")
        self.diagnostic_stage='startup-socket-probe'
        probe=self.docker.exec(self.ref,'test','-S',f'/sockets/node{node}.sock',deadline=end,check=False)
        require(probe.returncode in (0,1),"startup socket probe failed")
        if probe.returncode==1: return None
        return self.query_tip(node,end,True)

    def tip(self,node,end):
        r=self.tip_json(node,end); return Point(r['block'],r['slot'],r['hash'])
    def clock_slot(self):
        import datetime,math
        before=self.clock(); utc=time.time()
        start=datetime.datetime.fromisoformat(self.genesis['systemStart'].replace('Z','+00:00')).timestamp()
        # One-second conservative local clock bracket. A delayed header at or
        # below this bound cannot establish post-ready production.
        slot=math.floor((utc+1-start)/.1)
        require(self.clock()-before<1 and slot>=0,"bounded freshness clock bracket")
        return slot,start
    def setup(self):
        from private_cluster import BINARY_HASHES,profile
        from private_cluster_coherent import fresh_genesis,GENESIS
        from private_cluster_fork import verify_build,topology_for_peer
        from private_cluster_restart import RestartRunner
        require(not os.environ.get('DOCKER_HOST') and not os.environ.get('DOCKER_CONTEXT'),"fixed Docker endpoint")
        self.pin=verify_build(self.repo,Path(self.args.source_pin),self.args.source_pin_sha256); self.save('source-pin.json',self.pin)
        self.verify_native()
        require(self.args.reference_image.startswith('sha256:') and hex32(self.args.reference_image[7:]),"explicit reference image identity")
        require(self.docker.call('image','inspect',self.args.reference_image,'--format','{{.Id}}',deadline=self.deadline).stdout.strip()==self.args.reference_image,"reference image pin")
        volume=self.docker.socket_volume(self.deadline)
        # No keys or database enter this volume; it contains only owned sockets.
        init=self.docker.create('socket-init',['--network','none','--cpus','1','--memory','1g','--memory-swap','1g',
            '--read-only','--cap-drop','ALL','--cap-add','CHOWN','--security-opt','no-new-privileges','--user','0:0',
            '--mount','type=volume,src='+volume+',dst=/sockets','--entrypoint','/bin/sh',self.args.reference_image,'-c','set -e; chmod 0700 /sockets; chown 1000:1000 /sockets'],self.deadline)
        require(self.docker.call('wait',init,deadline=self.deadline).stdout.strip()=='0',"socket volume initialization")
        self.docker.remove(init,self.deadline)
        self.ref=self.docker.create('reference',['--network','none','--cpus','2','--memory','2g','--memory-swap','2g','--pids-limit','256',
            '--cap-drop','ALL','--security-opt','no-new-privileges','--user','1000:1000','--read-only',
            '--tmpfs','/work:rw,nosuid,nodev,noexec,size=1g,uid=1000,gid=1000,mode=0700',
            '--tmpfs','/tmp:rw,nosuid,nodev,noexec,size=128m','--mount','type=volume,src='+volume+',dst=/sockets',
            '--env','CARDANO_CLI=/opt/reference/bin/cardano-cli','--env','CARDANO_NODE=/opt/reference/bin/cardano-node',
            '--entrypoint','/bin/sh',self.args.reference_image,'-c','umask 077; sleep 590'],self.deadline)
        for name,pin in BINARY_HASHES.items(): require(self.hashes(['/opt/reference/bin/'+name])['/opt/reference/bin/'+name]==pin,"reference binary pin")
        RestartRunner.verify_pidfd_helper(self)
        self.execute('cardano-testnet','create-env','--nodes','spo,spo,relay','--testnet-magic','1082026','--output','/work/env',timeout=40)
        originals={name:strict_json(self.read(name)) for name in GENESIS}
        require(originals['byron-genesis.json'].get('protocolConsts')==dict(k=5,protocolMagic=1082026),"pinned Byron geometry for exact native request")
        for name in GENESIS: self.save('generated-'+name,self.read(name))
        self.save('generated-configuration.json',self.read('configuration.yaml'))
        genesis,config,topologies=profile(strict_json(self.read('shelley-genesis.json')),strict_json(self.read('configuration.yaml')),
                                         [strict_json(self.read(f'node-data/node{n}/topology.json')) for n in (1,2,3)])
        for n in (1,2,3): self.execute('test','!','-e',f'/work/env/node-data/node{n}/db')
        self.write_json('byron-genesis.json',fresh_genesis(originals['byron-genesis.json'],originals['shelley-genesis.json']))
        genesis['epochLength']=1000; self.write_json('shelley-genesis.json',genesis); self.write_json('configuration.yaml',config)
        expected={**originals,'byron-genesis.json':fresh_genesis(originals['byron-genesis.json'],originals['shelley-genesis.json']),
                  'shelley-genesis.json':genesis}
        require({name:strict_json(self.read(name)) for name in GENESIS}==expected,"only explicit fresh fixture mutations")
        self.genesis=genesis; self.genesis_dir=self.out/'genesis'; self.genesis_dir.mkdir(mode=0o700)
        self.genesis_pins={}
        for name in GENESIS:
            raw=self.read(name).encode(); (self.genesis_dir/name).write_bytes(raw); self.genesis_pins[name]=sha(raw)
        for n in (1,2,3):
            topo=topology_for_peer(topologies[n-1],5301 if n!=1 else 5302)
            root=topo['localRoots'][0]
            root['accessPoints']=[dict(address='127.0.0.1',port=5300+x) for x in (1,2,3) if x!=n]
            for key in ('valency','hotValency','warmValency'):
                if key in root: root[key]=2
            self.write_json(f'node-data/node{n}/topology.json',topo)
        names=[*self.genesis_pins,'configuration.yaml',*(f'node-data/node{n}/topology.json' for n in (1,2,3))]
        self.reference_pins={name:sha(self.read(name).encode()) for name in names}
        self.roles=ReferenceRoles(self.docker,self.ref,{n:sha(canonical(dict(container=self.ref,path=f'/work/env/node-data/node{n}/db',genesis=self.genesis_pins))) for n in (1,2,3)},self.save,self.tip,self.clock)
        for n in (1,2,3): self.roles.start(n,n!=3,min(self.deadline,self.started+137))
        binary=self.verify_native()
        self.oracle=ExactOracle(self.docker,volume,self.args.native_client,binary['path'],self.args.native_libs,binary['sha256'],self.args.reference_image,BINARY_HASHES['cardano-node'],self.clock)
    def verify_native(self):
        conformance=regular(Path(self.args.native_client)/'CONFORMANCE.json',1024*1024)
        require(sha(conformance)==self.args.native_conformance_sha256,"native conformance independent pin")
        native=strict_json(conformance); binary=native['binaries']['sustained-query-capture']
        for name,pin in native['sourceSHA256'].items():
            path=Path(self.args.native_client)/name
            require(not Path(name).is_absolute() and '..' not in Path(name).parts
                    and sha(regular(path,4*1024*1024))==pin,"pinned native packet source closure")
        require(native['image']==NATIVE_IMAGE and sha(regular(Path(binary['path']),256*1024*1024))==binary['sha256'],"native executable pin")
        for name,pin in native['nativeLibraries'].items():
            require(Path(name).name==name and sha(Path(self.args.native_libs,name).resolve().read_bytes())==pin,"native library closure pin")
        return binary
    def scala(self,command,name,end,network=False):
        from private_cluster import JDK
        self.diagnostic_stage='scala-'+name
        cid=None; key='scala-'+name
        try:
            cid=self.docker.create(key,['--network','container:'+self.ref if network else 'none','--cpus','1','--memory','1g','--memory-swap','1g',
                '--pids-limit','128','--read-only','--cap-drop','ALL','--security-opt','no-new-privileges','--user',str(os.getuid())+':'+str(os.getgid()),
                '--tmpfs','/tmp:rw,nosuid,nodev,size=64m','--mount','type=bind,src='+str(self.repo)+',dst=/work,readonly',
                '--mount','type=bind,src='+str(self.out)+',dst=/evidence,readonly','--workdir','/work','--entrypoint','/bin/sh',JDK,'-c',
                'exec java -XX:ActiveProcessorCount=1 -Xmx512m -cp "$(cat app/target/runtime-classpath.txt)" lab.Main '+shlex.join(command)],end-2)
            status=self.docker.call('wait',cid,deadline=end-2,limit=1024)
            self.save(name+'-exit.json',dict(stdout=status.stdout,stderr=status.stderr,returnCode=status.returncode))
            logs=self.docker.call('logs',cid,deadline=end-1,limit=MAX_BYTES)
            self.save(name+'-stdout.md',logs.stdout); self.save(name+'-stderr.md',logs.stderr)
            require(status.stdout.strip()=='0',"bounded Scala subprocess exit0")
            return logs.stdout
        finally: self.docker.remove_key(key,end)
    def bootstrap(self):
        from private_cluster_fence_exports import prepare_context
        from private_cluster_sequence import SequenceRunner
        self.diagnostic_stage='bootstrap-setup'
        self.setup(); start=None; until=min(self.deadline,self.started+137)
        self.stage_deadline=until
        history=[]
        while self.clock()<until:
            rows=[self.startup_tip_json(n,until) for n in (1,2,3)]
            if len(history)<128: history.append(rows)
            self.save('bootstrap-tip-history.json',history)
            if any(row is None for row in rows):
                time.sleep(.05)
                continue
            if len({r['hash'] for r in rows})==1 and not hasattr(self,'pair'):
                self.diagnostic_stage='bootstrap-funding'
                self.pair=SequenceRunner.build_pair(self)
                self.diagnostic_stage='bootstrap-funding-deadline'
                require(self.clock()<until,"pre-admission funding setup deadline")
                continue
            if len({r['hash'] for r in rows})==1 and rows[0]['epoch']>=1:
                p=Point(rows[0]['block'],rows[0]['slot'],rows[0]['hash'])
                if start is None: start=p
                if p.block-start.block>=3: break
            time.sleep(.05)
        else:
            self.diagnostic_stage='bootstrap-concrete-tip-deadline'
            raise TimeoutError('bootstrap complete original opportunity')
        self.diagnostic_stage='bootstrap-keyless-barrier'
        seedbarrier=self.roles.change(False,min(until,self.clock()+5)); end=seedbarrier.checked(False)
        require(3<=end.block-start.block<=8,"bounded bootstrap range including predecessor")
        self.diagnostic_stage='bootstrap-original-range'
        text=self.scala(['reference-range-capture','5303','1082026',str(start.slot),start.hash,str(end.slot),end.hash,'8',str(MAX_EVENTS),str(MAX_BYTES)],
                        'bootstrap-range',min(until,self.clock()+60),True)
        raw,originals,total=range_capture_rows(text,start,end,8)
        pre=originals[-3].point; seed=originals[-2:]; self.raw_seed=raw[-2:]; self.totals['bootstrap']=total
        slot,system_start=self.clock_slot(); now=self.clock(); epoch=pre.slot//1000
        require(slot//1000==epoch and end.slot//1000==epoch,"wall clock and all seed originals same epoch")
        epoch_end=now+(system_start+(epoch+1)*100-time.time())-1
        self.budget=budget_plan(now,self.started,epoch_end); self.save('budget.json',self.budget)
        self.stage_deadline=self.budget['productionDeadline']
        self.seed_path=self.out/'seed-capture.md'; self.seed_path.write_text(''.join(json.dumps(r,separators=(',',':'))+'\n' for r in self.raw_seed))
        self.oracle.capture(pre,self.out/'pre-packet',min(self.stage_deadline,self.clock()+25))
        mapped=prepare_context(self.out/'pre-packet/result',self.context_dir,genesis_directory=self.genesis_dir,
                               seed_capture=self.seed_path,expected_point=dict(slot=pre.slot,hash=pre.hash),expected_block_no=pre.block)
        self.context=mapped['contextId']; (self.context_dir/'v2-seed-capture.md').write_bytes(self.seed_path.read_bytes())
        self.seed=dict(sha256=sha(self.seed_path.read_bytes()),compact=seed[0].point)
        self.runtime=RuntimeProcess(self.docker,self.ref,self.repo,self.context_dir,self.control,self.context,self.store_id,self.clock)
        # Funding selections are setup evidence only. Both submissions happen
        # after B readiness while reference roles are still keyless.
        require(hasattr(self,'pair'),"funding setup completed before admission")
        return Protocol(self.context,1000,pre,seed,seedbarrier)
    def wire_totals(self,phase): return self.totals[phase] if phase=='bootstrap' else self.runtime.wire(phase)
    def remaining_wire(self,events,size): self.remaining=(events,size)
    def start_ready(self,phase,p):
        self.diagnostic_stage=phase+'-runtime-ready'
        self.current=phase; minimum=9 if phase=='A' else len(p.originals)+3; maximum=12 if phase=='A' else 16
        # A create/seed replay and readiness share eight seconds; B load and
        # checked intersection share the handoff's remaining five seconds.
        end=min(self.stage_deadline,self.clock()+8) if phase=='A' else self.handoff_deadline
        self.runtime.start(phase,self.fence_ids[phase],minimum,maximum,self.seed if phase=='A' else None,
                           min(self.started+500,self.budget['productionDeadline']+15),*self.remaining,allocation_deadline=end)
        view=self.runtime.ready(phase,self.fence_ids[phase],minimum,maximum,end)
        slot,_=self.clock_slot(); return view,slot
    def verify_reference(self,end):
        paths=['/work/env/'+name for name in self.reference_pins]
        raw=self.docker.exec(self.ref,'sha256sum',*paths,deadline=end).stdout
        found={row.split()[1]:row.split()[0] for row in raw.splitlines()}
        require(found=={'/work/env/'+n:d for n,d in self.reference_pins.items()},"unchanged genesis/configuration/topologies")
    def upgrade(self,phase,p):
        end=min(self.stage_deadline,self.clock()+5)
        self.verify_reference(end); result=self.roles.change(True,end); self.verify_reference(end)
        return result
    def submit_pair(self,p):
        self.diagnostic_stage='B-active-producer-submission'
        until=min(self.stage_deadline,self.clock()+3); previous=self.stage_deadline; self.stage_deadline=until
        try:
            require(p.stage=='B-producing' and all(self.roles.active[n]['forging'] for n in (1,2)),"submission after both producer upgrades")
            identity=self.roles.identity(1,until); pid=self.roles.active[1]['pid']
            require(type(pid) is int and pid>0,"owned producer PID")
            # The replacement removes the old pathname before starting. Wait
            # only for this owned process's socket, without restarting it.
            while self.clock()<until:
                probe=self.docker.exec(self.ref,'test','-S','/sockets/node1.sock',deadline=until,check=False)
                require(probe.returncode in (0,1),"active producer socket probe")
                if probe.returncode==0: break
                time.sleep(.02)
            else: raise TimeoutError('active producer socket readiness')
            shell=socket_owner_command(pid)
            self.docker.exec(self.ref,'/bin/sh','-c',shell,deadline=until)
            require(self.roles.identity(1,until)==identity,"producer identity before submission")
            self.save('submission-target-before.json',dict(node=1,socket='/sockets/node1.sock',forging=True,identity=identity))
            for index,row in enumerate(self.pair):
                self.diagnostic_stage='B-submit-'+str(index)
                require(self.clock()<until and self.roles.identity(1,until)==identity,"same active producer before each submission")
                before=self.clock()
                result=self.execute('cardano-cli','conway','transaction','submit','--tx-file',row['signedPath'],
                                    '--testnet-magic','1082026','--socket-path','/sockets/node1.sock',check=False)
                self.save('submission-'+str(index)+'.json',dict(transactionId=row['transactionId'],returnCode=result.returncode,
                    stdout=result.stdout,stderr=result.stderr,startedMonotonic=before,finishedMonotonic=self.clock(),
                    phase='B',afterCheckedReady=True,bothProducersActive=True,producerNode=1,producerSocket='/sockets/node1.sock',producerIdentity=identity))
                require(result.returncode==0,"original pair submission failed")
            after=self.roles.identity(1,until)
            self.save('submission-target-after.json',dict(node=1,socket='/sockets/node1.sock',forging=True,identity=after))
            require(after==identity and self.clock()<=until,"paired submission identity and budget")
        finally: self.stage_deadline=previous
    def await_minimum(self,phase,minimum):
        p=self.protocol; ceiling=12 if phase=='A' else 16
        pair=tuple(x['transactionId'] for x in self.pair)
        while self.clock()<self.stage_deadline:
            tip=self.tip(3,self.stage_deadline); depth=tip.block-p.anchor.block
            self.diagnostic_stage=phase+'-marker-inclusion'
            require(depth<=ceiling and tip.slot//1000==p.anchor.slot//1000,"hard phase depth/epoch bound")
            rows=self.runtime.rows(phase,self.stage_deadline)
            applied,_,marker=applied_transactions(rows,phase,pair)
            require(all(type(r.get('depth')) is int and p.phase_start<r['depth']<=ceiling
                        and r['blockNo']==p.anchor.block+r['depth'] and r['scopedAppliedTip']['slot']//1000==p.anchor.slot//1000
                        for r in applied),"bounded phase applied depths")
            if depth>=minimum and (phase=='A' or marker): return
            require(depth<ceiling,"marker/minimum unavailable at hard phase depth")
            time.sleep(.02)
        raise TimeoutError('production opportunity expired before marker/minimum')
    def demote(self,phase,p):
        end=min(self.stage_deadline,self.clock()+5)
        self.verify_reference(end); barrier=self.roles.change(False,end); self.verify_reference(end); point=barrier.checked(False)
        require(self.clock()<self.stage_deadline,"frozen point verified before production deadline")
        # The authoritative complete originals come from the already live
        # runtime, which may still catch up after producers are keyless.
        require(phase not in self.phase_points,"frozen point already selected")
        self.phase_points[phase]=point
        if phase=='A': self.handoff_deadline=min(self.stage_deadline,self.clock()+5)
        catchup_end=self.handoff_deadline if phase=='A' else self.budget['exitRetainDeadline']
        self.save('frozen-point-'+phase+'.json',dict(block=point.block,slot=point.slot,hash=point.hash))
        while self.clock()<catchup_end:
            rows=self.runtime.rows(phase,catchup_end)
            applied=[r for r in rows if r.get('record')=='node-applied']
            if applied and applied[-1].get('blockNo')==point.block: break
            require(not applied or applied[-1]['blockNo']<=point.block,"runtime beyond frozen point")
            time.sleep(.02)
        else: raise TimeoutError('complete live originals before immutable fence')
        self.diagnostic_stage=phase+'-frozen-marker-revalidation'
        applied,observed,_=applied_transactions(rows,phase,tuple(x['transactionId'] for x in self.pair),require_marker=phase=='B')
        captures=[r for r in rows if r.get('record')=='transfer-range-block']
        require(len(captures)==len(applied)==point.block-p.tip.block,"every acquired original has one publication")
        previous=p.tip; originals=[]
        for raw,row,ids in zip(captures,applied,observed):
            tip=row['scopedAppliedTip']; current=Point(row['blockNo'],tip['slot'],tip['hash'])
            originals.append(Original(current,previous.hash,sha(bytes.fromhex(raw['headerEnvelopeHex'])),sha(bytes.fromhex(raw['rawBlockHex'])),ids))
            previous=current
        self.phase_raw[phase]=captures
        return barrier,originals,self.fence_ids[phase]
    def publish(self,phase,fence):
        publish_fence(self.control/('fence-'+phase),fence)
        require(self.clock()<(self.handoff_deadline if phase=='A' else self.budget['exitRetainDeadline']),"fence publication cumulative deadline")
    def await_ack(self,phase,fence):
        self.diagnostic_stage=phase+'-runtime-ack-exit'
        until=self.budget['exitRetainDeadline'] if phase=='B' else self.handoff_deadline
        return self.runtime.acknowledge(phase,fence,until)
    def exit_and_retain(self,phase):
        end=self.budget['exitRetainDeadline'] if phase=='B' else self.handoff_deadline
        require(self.clock()<end,"retention deadline exhausted")
        result=self.runtime.retain(phase)
        require(self.clock()<=end,"retention deadline exceeded")
        return result
    def query_final(self,p):
        from private_cluster_fence_exports import prepare_oracle
        self.stage_deadline=self.budget['oracleDeadline']
        packet=self.oracle.capture(p.tip,self.out/'post-packet',self.stage_deadline)
        rows=self.raw_seed+self.phase_raw['A']+self.phase_raw['B']+[unique_record(self.runtime.final_rows['B'],'node-state')]
        self.capture=self.out/'all-originals-final-state.md'; self.capture.write_text(''.join(json.dumps(r,separators=(',',':'))+'\n' for r in rows))
        self.oracle_dir=self.out/'oracle'
        prepare_oracle(self.out/'post-packet/result',self.oracle_dir,expected_point=dict(slot=p.tip.slot,hash=p.tip.hash),expected_block_no=p.tip.block,
                       capture_path=self.capture,transaction_paths=tuple(self.out/f'signed-transaction-{i}-cbor.md' for i in (0,1)))
        return packet,tuple(x['transactionId'] for x in self.pair)
    def restore_production_and_begin_growth(self,p):
        self.growth=[[self.tip_json(n,self.budget['tailResumeDeadline']) for n in (1,2,3)]]
        require(all(Point(t['block'],t['slot'],t['hash'])==p.tip for t in self.growth[0]),"tail baseline exact final point")
        self.roles.change(True,min(self.budget['tailResumeDeadline'],self.clock()+5))
    def audit(self,p):
        self.diagnostic_stage='final-audit'
        text=self.scala(['node-fence-audit','/evidence/context','/evidence/oracle','/evidence/all-originals-final-state.md','2',str(len(p.originals))],
                        'final-audit',min(self.budget['tailDeadline'],self.clock()+65))
        report=unique_record(log_rows(text),'node-fence-audit',True)
        require(report.get('passed') is True and report.get('completeProjectionMatched') is True,"actual supported final projection audit")
        from private_cluster_fork import verify_build
        until=min(self.budget['tailDeadline'],self.clock()+10)
        verify_build(self.repo,Path(self.args.source_pin),self.args.source_pin_sha256)
        self.verify_native(); self.verify_reference(until)
        require(self.clock()<=until,"final source pin deadline")
        report['onlineProjectionMatched']=report['completeProjectionMatched']; return report
    def verify_two_epoch_growth(self,p):
        from private_cluster import assess
        self.stage_deadline=self.budget['tailDeadline']
        while self.clock()<self.stage_deadline:
            self.growth.append([self.tip_json(n,self.stage_deadline) for n in (1,2,3)])
            require(len(self.growth)<=1024,"tail observation count bound")
            self.save('tail-observations.json',self.growth)
            parameters=strict_json(self.relay_query('protocol-parameters'))
            try: result=assess(self.growth,parameters)
            except ValueError: time.sleep(.5)
            else: self.save('tail-assessment.json',result); return
        raise TimeoutError('two epoch growth opportunity exhausted')
    def cleanup(self):
        failures=[]
        # Copy only known role stdout paths, never keys or database contents.
        # Diagnostics use bounded prefixes and explicitly retain truncation.
        if self.roles is not None:
            if len(self.roles.log_bases)>16: failures.append(ValueError("bounded role log inventory"))
            for base in self.roles.log_bases[:16]:
                try:
                    result=self.docker.exec(self.ref,'head','-c','4194305',base+'.log',
                        deadline=min(self.started+600,self.clock()+1),check=False)
                    raw=result.stdout.encode()
                    self.save(Path(base).name+'-stdout.md',raw[:4194304].decode('utf-8',errors='replace'))
                    self.save(Path(base).name+'-log-retention.json',dict(returnCode=result.returncode,
                        observedBytes=len(raw),truncated=len(raw)>4194304,maximumRetainedBytes=4194304))
                except BaseException as error: failures.append(error)
        if self.runtime is not None:
            for phase in self.runtime.processes:
                if phase not in self.runtime.final_rows:
                    try: self.runtime.rows(phase,min(self.started+600,self.clock()+2))
                    except BaseException as error: failures.append(error)
        try: self.docker.cleanup()
        except BaseException as error: failures.append(error)
        if failures: raise BaseExceptionGroup('diagnostic retention and cleanup failures',failures)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--plan",action="store_true",required=True)
    parser.parse_args()
    print(json.dumps(dict(profile="adaptive-durable-fence-design-v1",seed=2,capacity=2,
        phaseA=[9,12],phaseBMinimum="A+3",maximumDepth=16,roleChanges=6,exactPointQueries=2,
        liveAdapterIntegrated=True,adapterReviewed=False,budgetApproved=False,proposedWorkloadSeconds=500,absoluteCleanupSeconds=600,
        proposedEpochSlots=1000,liveExecutionEnabled=False),indent=2))

if __name__=="__main__": main()
