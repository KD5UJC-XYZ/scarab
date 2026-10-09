#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Twelve same-epoch ordinary node publications with a four-block volatile rollback window."""
import argparse
import json
import signal
import time
from private_cluster import Runner, JDK
from private_cluster_coherent import FIXTURE
from private_cluster_relay import RelayRunner, events
from private_cluster_transfer import converged_tips
from private_cluster_sequence import PRE_PINS, ORACLE_PINS, pair_admissions, workload_budget
from private_cluster_runner import LiveRunner

PROFILE = "conway-pv9-header11-2-derived-nonce-bounded-sequence-v1"
TARGET = 12
CAPACITY = 4
LOG_LIMIT = 32 * 1024 * 1024
STATE_FIELDS = ("contextId", "stateId", "revision", "retainedBlocks", "scopedAppliedTip", "blockNo",
                "depth", "compactedBlocks", "derivedAnchorId")


def integer(value, low, high):
    return type(value) is int and low <= value <= high


def digest(value):
    return isinstance(value, str) and len(value) == 64 and all(c in "0123456789abcdef" for c in value)


def point(value):
    if (not isinstance(value, dict) or set(value) != {"hash", "slot"}
            or not digest(value["hash"]) or not integer(value["slot"], 0, (1 << 64)-1)):
        raise ValueError("canonical bounded point required")
    return value


def json_records(stdout):
    if len(stdout.encode()) > LOG_LIMIT:
        raise ValueError("node output exceeds thirty-two MiB")
    rows=[]
    for line in stdout.splitlines(keepends=True):
        if line.startswith("{") and line.endswith("\n"):
            item=json.loads(line)
            if not isinstance(item,dict): raise ValueError("node record object required")
            rows.append(item)
    return rows


def same_state(a,b):
    return all(a.get(k)==b.get(k) for k in STATE_FIELDS)


def node_command(port):
    if not integer(port,1,65535): raise ValueError("loopback port range")
    return ('exec java -XX:ActiveProcessorCount=1 -Xmx512m -cp "$(cat app/target/runtime-classpath.txt)" '
        'lab.Main node --profile ' + PROFILE + ' --bootstrap /evidence --port ' + str(port) +
        ' --mode sustained-volatile --blocks 12 --rollback-capacity 4 --seconds 120 --events 128 '
        '--bytes 33554432 --reconnects 0 --audit true')


def terminal_trace(rows,bootstrap,ready,applied,outcome):
    captures=[r for r in rows if r.get("record")=="transfer-range-block"]
    if len(captures)!=TARGET: raise ValueError("twelve complete fetched originals required")
    trace=[r for r in rows if r.get("record") in
        ("node-bootstrap","node-rollback","node-download","node-applied","node-anchor-advance","transfer-range-block")]
    if len(trace)<2 or trace[:2]!=[bootstrap,ready]:
        raise ValueError("bootstrap and accepted initial intersection must precede traffic")
    trace=trace[2:]
    # An initial protocol alignment is a checked no-op, never a live fork or rollback demonstration.
    if trace and trace[0].get("phase")=="rollback-announced":
        if len(trace)<2: raise ValueError("alignment lacks checked rollback")
        announced,checked=trace[:2]
        if (announced.get("record")!="node-download" or announced.get("downloadCursor")!=bootstrap["suppliedAnchor"]
                or not integer(announced.get("payloadBytes"),0,0) or announced.get("downloadIsApplied") is not False
                or not same_state(announced,ready) or checked.get("record")!="node-rollback"
                or checked.get("initialIntersection") is not False or not same_state(checked,ready)):
            raise ValueError("only unchanged initial supplied-anchor alignment is supported")
        trace=trace[2:]
    if len(trace)!=TARGET*4+(TARGET-CAPACITY):
        raise ValueError("exact twelve announcement/fetch/capture/publications with eight advances required")
    previous=ready; hashes=set(); total=0; offset=0
    for i in range(TARGET):
        announced,fetched,captured=trace[offset:offset+3]; offset+=3
        advance=None
        if i>=CAPACITY:
            advance=trace[offset]; offset+=1
        published=trace[offset]; offset+=1
        if captured!=captures[i]: raise ValueError("exact original must follow fetch and precede publication")
        if advance is not None:
            if (advance.get("record")!="node-anchor-advance"
                    or advance.get("scopedAppliedTip")!=previous.get("scopedAppliedTip")
                    or advance.get("blockNo")!=previous.get("blockNo")
                    or not integer(advance.get("revision"),i,i) or not integer(advance.get("depth"),i,i)
                    or not integer(advance.get("retainedBlocks"),CAPACITY-1,CAPACITY-1)
                    or not integer(advance.get("compactedBlocks"),i-CAPACITY+1,i-CAPACITY+1)
                    or not digest(advance.get("derivedAnchorId"))
                    or advance.get("derivedAnchorId")!=published.get("derivedAnchorId")
                    or advance.get("contextId")!=previous.get("contextId")):
                raise ValueError("anchor advance must preserve applied tip and revision without publishing new block")
        if (announced.get("record"),announced.get("phase"),fetched.get("record"),fetched.get("phase"),
                published.get("record")) != ("node-download","announced","node-download","fetched","node-applied"):
            raise ValueError("node download/publication chronology differs")
        current=point(published.get("scopedAppliedTip")); before=previous.get("scopedAppliedTip") or bootstrap["suppliedAnchor"]
        if (current["slot"]<=before["slot"] or current["slot"]//500!=bootstrap["suppliedAnchor"]["slot"]//500
                or current["hash"] in hashes or not integer(published.get("blockNo"),previous["blockNo"]+1,previous["blockNo"]+1)):
            raise ValueError("distinct same-epoch contiguous publications required")
        hashes.add(current["hash"])
        for download,bound,name in ((announced,65535,"headerEnvelopeHex"),(fetched,1048576,"rawBlockHex")):
            size=download.get("payloadBytes")
            if (not integer(size,1,bound) or download.get("downloadCursor")!=current
                    or download.get("downloadIsApplied") is not False or not same_state(download,previous)):
                raise ValueError("download must retain the prior complete applied state")
            raw=captures[i].get(name)
            if not isinstance(raw,str) or len(raw)!=size*2 or any(c not in "0123456789abcdef" for c in raw):
                raise ValueError("cumulative byte accounting differs from original capture")
            total+=size
        if captures[i].get("acquisitionOnly") is not True or captures[i].get("appliedClaim") is not False:
            raise ValueError("fetched original must remain an acquisition observation")
        if not integer(published.get("revision"),i+1,i+1):
            raise ValueError("straight-line revision advances only on block publication")
        previous=published
    if not integer(outcome.get("returnedBytes"),total,total) or total>33554432:
        raise ValueError("node byte total mismatch")
    if not same_state(previous,outcome): raise ValueError("terminal tuple differs from last published state")
    projections=[r for r in rows if r.get("record")=="node-state"]
    if len(projections)!=1: raise ValueError("one finalized token-free projection required")
    state=projections[0]
    projection=state.get("projection")
    if (not isinstance(projection,dict) or projection.get("tupleId")!=outcome.get("stateId")
            or projection.get("contextId")!=outcome.get("contextId")
            or projection.get("tip")!={"hash":outcome["scopedAppliedTip"]["hash"],"slot":str(outcome["scopedAppliedTip"]["slot"])}
            or projection.get("appliedTip")!=projection.get("tip")):
        raise ValueError("full projection identity and applied endpoint differ from outcome")
    for key in ("revision","depth","compactedBlocks"):
        if state.get(key)!=str(outcome[key]): raise ValueError("projection counters differ from outcome")
    if state.get("derivedAnchorId")!=outcome.get("derivedAnchorId") or not isinstance(state.get("projection"),dict):
        raise ValueError("final projection anchor binding missing")


def node_progress(rows):
    boot=[r for r in rows if r.get("record")=="node-bootstrap"]
    ready=[r for r in rows if r.get("record")=="node-rollback" and r.get("initialIntersection") is True]
    applied=[r for r in rows if r.get("record")=="node-applied"]
    terminal=[r for r in rows if r.get("scope")=="bounded-node-outcome"]
    if any(len(r)>1 for r in (boot,ready,terminal)) or (ready and not boot) or (applied and not ready):
        raise ValueError("unique bootstrap/intersection precedes publication")
    if boot:
        anchor=point(boot[0].get("suppliedAnchor"))
        if (boot[0].get("sourceBound") is not True or boot[0].get("bootstrapValidated") is not False
                or not integer(boot[0].get("revision"),0,0) or not integer(boot[0].get("depth"),0,0)
                or not integer(boot[0].get("retainedBlocks"),0,0) or boot[0].get("scopedAppliedTip") is not None):
            raise ValueError("supplied bootstrap is not a validated tip")
    if ready and not same_state(ready[0],boot[0]): raise ValueError("initial intersection must check unchanged bootstrap")
    if len(applied)>TARGET: raise ValueError("twelve block operational target exceeded")
    for index,row in enumerate(applied,1):
        if (not integer(row.get("revision"),index,index) or not integer(row.get("depth"),index,index)
                or not integer(row.get("retainedBlocks"),min(index,CAPACITY),min(index,CAPACITY))
                or not integer(row.get("compactedBlocks"),max(0,index-CAPACITY),max(0,index-CAPACITY))
                or not integer(row.get("transactionCount"),0,16)
                or (index>CAPACITY and not digest(row.get("derivedAnchorId")))
                or (index<=CAPACITY and row.get("derivedAnchorId") is not None)):
            raise ValueError("bounded volatile window publication counters required")
    if terminal:
        out=terminal[0]
        if (out.get("typedStop")!="TargetReached" or out.get("scopedTargetReached") is not True
                or out.get("peerResourcesFinalized") is not True or out.get("caughtUp") is not False
                or out.get("mode")!="sustained-volatile" or out.get("auditEnabled") is not True
                or not integer(out.get("rollbackCapacity"),CAPACITY,CAPACITY) or len(applied)!=TARGET):
            raise ValueError("finalized ordinary sustained target required")
        for key,expected in (("peerOpens",1),("peerCloses",1),("reconnects",0)):
            if not integer(out.get(key),expected,expected): raise ValueError("node resource count mismatch")
        if not integer(out.get("events"),TARGET,128): raise ValueError("node event budget")
        for flag in ("fullLedgerValidated","consensusValidated","stateDerivedConsensus","durable","bootstrapValidated"):
            if out.get(flag) is not False: raise ValueError("unsupported node claim: "+flag)
        terminal_trace(rows,boot[0],ready[0],applied,out)
    return bool(ready),applied,terminal[0] if terminal else None


def exact_post(pre,post,outcome):
    current=point(outcome.get("scopedAppliedTip"))
    if (pre.get("era")!="Conway" or post.get("era")!="Conway" or pre["epoch"]!=post["epoch"]
            or pre["slot"]//500!=post["slot"]//500 or pre["slot"]//500!=pre["epoch"]
            or not 1<=pre["slotInEpoch"]<=80 or post["slot"]<=pre["slot"]
            or post["block"]-pre["block"]!=TARGET or post["hash"]!=current["hash"]
            or post["slot"]!=current["slot"] or post["block"]!=outcome["blockNo"]):
        raise ValueError("exact same-epoch twelve-block endpoint required; no target extension")
    return {"expectedCompleteBlocks":TARGET,"rollbackCapacity":CAPACITY,"epoch":pre["epoch"],
            "fullLedgerValidated":False,"singleAcquiredSnapshot":False}


def audit_report(report,outcome,pair):
    if report.get("scope")!="node-audit" or report.get("capturedBlocks")!=TARGET or report.get("transactionCount")!=2:
        raise ValueError("twelve original audit with exact two transaction grouping required")
    for key in ("passed","completeProjectionMatched","referencePostStateMatched","sameEpoch","distinctForwardHashes"):
        if report.get(key) is not True: raise ValueError("audit check missing: "+key)
    for key in ("fullLedgerValidated","consensusValidated","liveForkClaim","durableClaim"):
        if report.get(key) is not False: raise ValueError("unsupported audit claim")
    if report.get("emptyBlocks")!=TARGET-1 or not integer(report.get("transactionBlockIndex"),0,TARGET-1):
        raise ValueError("exact pair must share one original block with eleven empty blocks")
    if sorted(report.get("transactionIdsInBlockOrder",[]))!=sorted(s["transactionId"] for s in pair):
        raise ValueError("audit differs from actual submitted pair")
    for field,other in (("finalStateId","stateId"),("contextId","contextId"),("derivedAnchorId","derivedAnchorId")):
        if report.get(field)!=outcome.get(other): raise ValueError("audit state differs from online outcome")
    for key in ("revision","depth","compactedBlocks"):
        if report.get(key)!=str(outcome[key]): raise ValueError("audit publication counters differ")
    return report


class NodeRunner(LiveRunner):
    def start_observer(self,port):
        self.observer_attempted=True
        self.observer_phase="node"
        result=self.docker("run","-d","--pull=never","--name",self.name+"-scala",
            "--network=container:"+self.name,"--cpus=1","--memory=1g","--memory-swap=1g",
            "--pids-limit=128","--cap-drop=ALL","--security-opt=no-new-privileges","--user","1000:1000",
            "--read-only","--tmpfs","/tmp:size=64m","--log-driver=json-file","--log-opt=max-size=32m",
            "--log-opt=max-file=1","-v",str(self.args.scala_repo)+":/work:ro","-v",str(self.out)+":/evidence:ro",
            "-w","/work","--entrypoint=/bin/sh",JDK,"-c",node_command(port),timeout=5)
        self.save("node-container-id.md",result.stdout)

    def observer_logs(self):
        result=self.docker("logs","--tail","2000",self.name+"-scala",check=False,timeout=2)
        if getattr(self,"observer_phase","node")=="audit":
            self.save("node-audit-final.stdout.md",result.stdout); self.save("node-audit-final.stderr.md",result.stderr)
            return json_records(result.stdout)
        self.save("node-stdout.md",result.stdout); self.save("node-stderr.md",result.stderr)
        if result.returncode or len(result.stdout.encode())+len(result.stderr.encode())>LOG_LIMIT:
            raise ValueError("bounded owned node logs unavailable")
        if not result.stdout.startswith(getattr(self,"last_observer_stdout","")):
            raise ValueError("node logs truncated or rewritten")
        self.last_observer_stdout=result.stdout
        return json_records(result.stdout)

    def await_progress(self,predicate,seconds,label):
        until=min(self.deadline,time.monotonic()+seconds)
        while time.monotonic()<until:
            current=node_progress(self.observer_logs())
            if predicate(current): return current
            if not self.observer_state().get("Running"): raise ValueError("node exited before "+label)
            time.sleep(0.1)
        raise TimeoutError("bounded node wait expired: "+label)

    def live_sequence(self):
        handshake = Runner.scala(self)
        RelayRunner.prepare_transfer(self)
        pair = self.build_pair()  # All expensive construction occurs with producers running.
        relay = self.read("logs/node3/stdout.log")
        if "shelleyKESSource = Nothing" not in relay or "shelleyVRFFile = Nothing" not in relay:
            raise ValueError("verified nonproducing relay required")
        self.save("relay-role.md", relay.splitlines()[0])
        readiness = []; until = min(self.deadline - 190, time.monotonic() + 75)
        while time.monotonic() < until:
            tips = [self.query("tip", i) for i in (1, 2, 3)]
            readiness.append(tips); self.save("sequence-readiness.md", readiness)
            if converged_tips(tips) and tips[-1].get("epoch", 0) >= 1 and 1 <= tips[-1].get("slotInEpoch", 501) <= 30:
                break
            time.sleep(0.2)
        else:
            raise TimeoutError("early same-epoch sequence anchor unavailable")

        port=int(self.read("node-data/node3/port"))
        with self.paused("pre"):
            pre,before=self.snapshot("pre")
            if not 1<=pre["slotInEpoch"]<=80: raise ValueError("early pre-anchor window lost")
            utxo=json.loads(before["utxo"])
            if any(utxo.get(s["input"])!=s["originalInput"] for s in pair): raise ValueError("prepared pair inputs changed")
            self.save("transfer-genesis.md",self.read("shelley-genesis.json"))
            self.manifest("coherent-sequence-context.md","coherent-sequence-context-v1",PRE_PINS)
            self.start_observer(port)
            self.await_progress(lambda p:p[0],10,"ready")
            bootstrap=[r for r in json_records(self.last_observer_stdout) if r.get("record")=="node-bootstrap"][0]
            if bootstrap["suppliedAnchor"]!={"hash":pre["hash"],"slot":pre["slot"]} or bootstrap["blockNo"]!=pre["block"]:
                raise ValueError("node checked intersection differs from exact pre-anchor")
        _,applied,stop=self.await_progress(lambda p:bool(p[1]),16,"first-empty")
        if stop or len(applied)!=1 or applied[0]["transactionCount"]!=0:
            raise ValueError("one first applied empty successor required")
        with self.paused("submission"):
            for index, selection in enumerate(pair):
                started = time.monotonic()
                result = self.execute("cardano-cli", "conway", "transaction", "submit", "--tx-file",
                    selection["signedPath"], "--testnet-magic", "1082026", "--socket-path",
                    "/work/env/socket/node3/sock", timeout=3)
                self.save(f"sequence-submission-{index}.md", result.stdout + result.stderr)
                self.save(f"sequence-submission-{index}-timing.md", {"startedMonotonicSeconds": started,
                    "completedMonotonicSeconds": time.monotonic(), "transactionId": selection["transactionId"]})
            until = min(self.deadline, time.monotonic() + 2)
            while time.monotonic() < until:
                rows = events(self.read("logs/node3/stdout.log"))
                admitted = {s["transactionId"]: [e for e in rows if e["ns"] == "Mempool.AddedTx"
                    and e.get("data", {}).get("tx", {}).get("txid") in (s["transactionId"],s["transactionId"][:8])]
                    for s in pair}
                self.save("sequence-relay-admissions.md", admitted)
                if all(admitted.values()):
                    break
                time.sleep(0.1)
            else:
                raise ValueError("both relay admissions required before resuming producers")

        _,_,outcome=self.await_progress(lambda p:p[2] is not None,40,"target-twelve")
        with self.paused("post"):
            post,after=self.snapshot("post")
            window=exact_post(pre,post,outcome)
            if before["parameters"]!=after["parameters"]: raise ValueError("parameters changed")
            self.save("node-window.md",window)
        # The ordinary process exits before the oracle manifest is published; audit is a separate offline process.
        until=min(self.deadline,time.monotonic()+5)
        while time.monotonic()<until:
            state=self.observer_state()
            if not state.get("Running"): break
            time.sleep(0.1)
        else: raise TimeoutError("ordinary node exit deadline")
        rows=self.observer_logs(); node_progress(rows)
        self.save("node-exit-state.md",state)
        if state.get("ExitCode")!=0 or state.get("OOMKilled") is not False: raise ValueError("ordinary node exit failed")
        capture="".join(line for line in self.last_observer_stdout.splitlines(keepends=True)
            if line.startswith("{") and line.endswith("\n") and json.loads(line).get("record")=="transfer-range-block")
        self.save("scala-sequence-capture.md",capture)
        evidence={str(i):[e for e in events(self.read(f"logs/node{i}/stdout.log"))
            if e["ns"].startswith("Mempool.") or e["ns"].startswith("TxSubmission.")] for i in (1,2,3)}
        self.save("sequence-transaction-events.md",evidence)
        self.save("sequence-pair-admissions.md",pair_admissions(evidence,[s["transactionId"] for s in pair]))
        self.finalize_observer(); self.observer_attempted=False
        self.manifest("coherent-sequence-oracle.pending.md","coherent-sequence-oracle-v1",ORACLE_PINS)
        destination=self.out/"coherent-sequence-oracle.md"
        if destination.exists(): raise ValueError("oracle already published")
        (self.out/"coherent-sequence-oracle.pending.md").replace(destination)
        self.observer_attempted=True
        self.observer_phase="audit"
        result=self.docker("run","--rm","--pull=never","--name",self.name+"-scala","--network=none",
            "--cpus=1","--memory=1g","--memory-swap=1g","--pids-limit=128","--cap-drop=ALL",
            "--security-opt=no-new-privileges","--user","1000:1000","--read-only","--tmpfs","/tmp:size=64m",
            "-v",str(self.args.scala_repo)+":/work:ro","-v",str(self.out)+":/evidence:ro","-w","/work",
            "--entrypoint=/bin/sh",JDK,"-c",'exec java -XX:ActiveProcessorCount=1 -Xmx512m -cp "$(cat app/target/runtime-classpath.txt)" lab.Main node-audit /evidence /evidence /evidence/node-stdout.md 4 12',check=False,timeout=65)
        self.observer_attempted=False  # --rm completed; outer Runner.cleanup is the failure fallback.
        self.save("node-audit.md",result.stdout); self.save("node-audit.stderr.md",result.stderr)
        if result.returncode: raise ValueError("independent node audit failed")
        reports=[r for r in json_records(result.stdout) if r.get("scope")=="node-audit"]
        if len(reports)!=1: raise ValueError("one independent audit receipt required")
        report=audit_report(reports[0],outcome,pair)
        return {"handshake":handshake,"ordinaryNode":outcome,"audit":report,"fixtureProfile":FIXTURE,
                "singleAcquiredSnapshot":False,"relayOnlySubmission":True,"liveForkClaim":False}


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fixture-profile",required=True,choices=[FIXTURE])
    parser.add_argument("--reference-image",required=True); parser.add_argument("--output",required=True)
    parser.add_argument("--scala-repo",required=True); parser.add_argument("--seconds",type=int,default=480)
    args=parser.parse_args(); args.capture=False
    try: workload_budget(args.seconds)
    except ValueError as exc: parser.error(str(exc))
    signal.signal(signal.SIGTERM, lambda *_: (_ for _ in ()).throw(KeyboardInterrupt()))
    NodeRunner(args).run()


if __name__=="__main__": main()
