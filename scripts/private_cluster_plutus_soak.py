#!/usr/bin/env python3
"""Opt-in 120/600-second two-service JVM-only research soak; no default execution."""
import argparse
import copy
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import signal
import stat
import threading
import time
import private_cluster_plutus_two_service as two
import private_cluster_plutus_early_restart as restart
import plutus_soak_fixture as soak_fixture

fixture=two.fixture
diagnostic=two.diagnostic
window_deadline=two.window_deadline
prefunding_point=two.prefunding_point
frozen_point=two.frozen_point

base = two.base
single = two.single
MODE = "repeated-jvm-v1"
SERVICE_PHASES = two.SERVICE_PHASES
PROFILE = "early-restart-two-service-soak-v1"
MAX_OPERATION = 720
MAX_CLEANUP = 30
MAX_TREE_BYTES = 8 * base.GIB
MAX_LOG_BYTES = 64 * 1024 * 1024
MAX_FILES = 100000


def soak_limits(duration, blocks):
    base.require(type(duration) is int and duration in (120, 600), "explicit 120/600-second soak")
    base.require(type(blocks) is int and blocks == 512, "explicit 512-block soak ceiling")
    two.budget()


def repeated_args(args, root, initial, classpath, reference, port, magic, manifest):
    soak_limits(args.duration_seconds, args.max_blocks)
    small = copy.copy(args)
    small.duration_seconds, small.max_blocks = 30, 128
    command = two.service_args(small, root, initial, classpath, reference, port, magic, manifest)
    command[command.index("--duration-seconds")+1] = str(args.duration_seconds)
    command[command.index("--max-blocks")+1] = str(args.max_blocks)
    return command + ["--epoch-mode", MODE]


def check_epoch_mode(value):
    base.require(isinstance(value, dict) and value.get("mode") == MODE and
                 all(value.get(k) is True for k in ("jvmComputed", "researchOnly", "activeStartsAfterPeerReady")) and
                 all(value.get(k) is False for k in ("nativeChecked", "nativeRuntimeDependency", "lateCheckpointRestoreSupported")) and
                 value.get("nativeExecutableSHA256") is None and value.get("nativeEvidenceDirectory") is None and
                 value.get("generationEvidenceDirectory") == "jvm-likelihood" and
                 type(value.get("maxEpochTransitions")) is int and value["maxEpochTransitions"] == 8 and
                 type(value.get("startupTimeoutSeconds")) is int and value["startupTimeoutSeconds"] == 30,
                 "explicit JVM-only research mode")


def active(value, duration):
    base.require(value.get("schema") == "plutus-service-active-v1" and value.get("epochMode") == MODE and
                 type(value.get("durationSeconds")) is int and value["durationSeconds"] == duration and
                 type(value.get("startedUnixMillis")) is int and type(value.get("deadlineUnixMillis")) is int and
                 value["deadlineUnixMillis"]-value["startedUnixMillis"] == duration*1000,
                 "full active duration starts after peer readiness")
    return value


def tree_budget(roots):
    total=count=0
    for root in roots:
        for directory, _, names in os.walk(root, followlinks=False):
            for name in names:
                p=Path(directory)/name
                try:
                    info=p.lstat()
                except FileNotFoundError:
                    # Atomic publication may remove its owned staging name.
                    continue
                count+=1
                total+=info.st_size
                base.require(count<=MAX_FILES and total<=MAX_TREE_BYTES,"owned soak disk/inode ceiling")
                if name.endswith((".log",".stdout",".stderr")):
                    base.require(info.st_size<=MAX_LOG_BYTES,"owned log ceiling")
        existing = root
        while not existing.exists():
            existing = existing.parent
        base.require(shutil.disk_usage(existing).free>=2*base.GIB,"two GiB free space floor")
    return dict(bytes=total,files=count)


class BudgetMonitor:
    def __init__(self, roots):
        self.roots=roots;self.stop_event=threading.Event();self.failure=None;self.high_water=dict(bytes=0,files=0)
        self.signal_lock=threading.Lock()
        self.thread=threading.Thread(target=self.run,daemon=True)
    def start(self): self.thread.start()
    def run(self):
        while not self.stop_event.is_set():
            try:
                value=tree_budget(self.roots)
                self.high_water={k:max(self.high_water[k],value[k]) for k in value}
            except BaseException as error:
                with self.signal_lock:
                    if not self.stop_event.is_set():
                        self.failure=type(error).__name__
                        os.kill(os.getpid(),signal.SIGUSR1)
                return
            self.stop_event.wait(2)
    def stop(self):
        with self.signal_lock:
            self.stop_event.set()
        if self.thread.ident is not None:
            self.thread.join(timeout=3)


def soak_client_result(value, transfers, ports):
    two.client_result(value, transfers, ports)
    base.require(value.get("soakProfile") == PROFILE and value.get("secondSubmittedAfterEpochOne") is True,
                 "explicit post-boundary spend evidence")
    crossed = value.get("boundaryPins")
    base.require(isinstance(crossed, list) and len(crossed) == 2, "both actual boundary observations")
    for row, observed in zip(value["endpoints"], crossed):
        current = two.pin(observed, row["observedOwnerId"])
        before = row["initialState"]["pin"]
        base.require(current["point"]["slot"] >= 1000 and two.follows(current, before) and
                     current["generation"] > before["generation"] and current["environmentId"] != before["environmentId"],
                     "same owner reached checked changed epoch environment")
    accepted = value["endpoints"][1]["acceptedResponse"]["receipt"]["pin"]
    base.require(two.follows(accepted, crossed[1]) and accepted["environmentId"] == crossed[1]["environmentId"] and
                 accepted["point"]["slot"] >= 1000, "second HTTP acceptance after actual epoch observation")
    return value


def jvm_generations(root):
    base.require(root.resolve() == root and root.is_dir(), "JVM evidence directory")
    directories = sorted(root.iterdir())
    base.require(0 < len(directories) <= 128, "bounded actual JVM generations")
    rows = []
    for index, directory in enumerate(directories):
        base.require(directory.name == f"generation-{index:04d}" and directory.resolve() == directory and directory.is_dir(),
                     "contiguous JVM evidence generations")
        base.require({p.name for p in directory.iterdir()} == {"request.txt", "jvm-result.txt", "execution.txt"},
                     "exact JVM evidence fields without native response")
        request = restart.read_exact(directory/"request.txt", 65536)
        result = restart.read_exact(directory/"jvm-result.txt", 65536)
        execution_raw = restart.read_exact(directory/"execution.txt", 4096)
        execution = execution_raw.decode("ascii").splitlines()
        base.require(len(execution) == 6 and execution[:4] == ["jvm-likelihood-execution-v1", restart.digest(request),
                     restart.digest(result), "PureJvm"] and execution[5] == "0 0" and
                     re.fullmatch(r"(0|[1-9][0-9]*) (0|[1-9][0-9]*)", execution[4]) is not None,
                     "exact source-bound JVM execution without native comparisons")
        words32, words64 = map(int, execution[4].split())
        base.require(0 <= words64 <= 64 and words32 == 100*words64, "computed JVM word bounds")
        lines = request.decode("ascii").splitlines()
        base.require(len(lines) == words64+3 and lines[0] == "conway-native-likelihood-v1" and base.hex64(lines[1]) and
                     lines[2] == "1000 1 20 0 1" and request == ("\n".join(lines)+"\n").encode("ascii"), "canonical original frozen request")
        pools = []
        for line in lines[3:]:
            base.require(re.fullmatch(r"[0-9a-f]{56} (0|[1-9][0-9]*) [1-9][0-9]* (0|[1-9][0-9]*)", line) is not None,
                         "canonical bounded pool request")
            pool, stake, circulation, blocks = line.split()
            base.require(len(stake) <= 20 and len(circulation) <= 20 and len(blocks) <= 4 and
                         0 <= int(stake) <= int(circulation) < 2**64 and int(blocks) <= 1000, "pool request arithmetic domain")
            pools.append(pool)
        base.require(pools == sorted(set(pools)), "unique sorted pool domain")
        prefix = b"conway-jvm-likelihood-result-v1\n"+request+b"--jvm--\n"
        base.require(result.startswith(prefix), "JVM output binds whole original request")
        result_rows = result[len(prefix):].decode("ascii").splitlines()
        base.require(len(result_rows) == words64 and result == prefix+"".join(x+"\n" for x in result_rows).encode("ascii"), "complete canonical JVM output pool domain")
        for pool, line in zip(pools, result_rows):
            base.require(re.fullmatch(pool+r" [0-9a-f]{16} [0-9a-f]{800}", line) is not None, "exact JVM raw IEEE word evidence")
        rows.append(dict(generation=index, frozenId=lines[1], requestSHA256=restart.digest(request),
                         evidenceSHA256=restart.digest(result), executionSHA256=restart.digest(execution_raw),
                         computedRaw32Words=words32, computedRaw64Words=words64, nativeComparisons=0))
    return rows


def repeated_publications(root, result, observed, source, manifest, active_record):
    refs = result.get("publications")
    base.require(isinstance(refs, list) and 1 <= len(refs) <= 512, "bounded repeated publication references")
    final = two.pin(result.get("finalPin"))
    actual, epochs, generations = {}, set(), []
    previous = None
    def epoch(value, state):
        base.require(isinstance(value, dict) and type(value.get("epoch")) is int and value["epoch"] == state["point"]["slot"]//1000 and
                     type(value.get("transitions")) is int and 0 <= value["transitions"] <= 8 and
                     all(base.hex64(value.get(k)) for k in ("componentId", "nonMyopicId")), "actual repeated epoch tuple")
        epochs.add(value["epoch"])
        checked = value.get("checkedLikelihood")
        base.require(value.get("frozenId") is None if checked is None else value.get("frozenId") == checked.get("frozenId"),
                     "selected frozen component agrees with generation")
        if checked is not None:
            base.require(isinstance(checked, dict) and checked.get("mode") == "pure-jvm" and
                         all(base.hex64(checked.get(k)) for k in ("frozenId", "preTickTupleId", "requestSHA256", "evidenceSHA256")) and
                         checked.get("nativeResponseSHA256") is None and checked.get("nativeValidated") is False and
                         checked.get("diagnosticNativeDependency") is False and
                         all(type(checked.get(k)) is int and checked[k] == 0 for k in ("raw32Comparisons", "raw64Comparisons", "jvmMismatchWords")) and
                         all(type(checked.get(k)) is int for k in ("computedRaw32Words", "computedRaw64Words", "applicationEpoch", "observedSlot")) and
                         0 <= checked["applicationEpoch"] <= 8 and 0 <= checked["observedSlot"] <= state["point"]["slot"],
                         "publication binds actual JVM generation without native parity claim")
            generations.append(checked)
    for index, ref in enumerate(refs):
        name = f"publication-{index:04d}.json"
        base.require(ref.get("file") == name, "contiguous publication reference")
        raw = restart.read_exact(root/name, 131072)
        base.require(restart.digest(raw) == ref.get("sha256"), "publication original bytes pin")
        value = base.decode(raw)
        state = two.pin(value.get("pin"), final["ownerId"])
        base.require(value.get("schema") == "plutus-service-publication-v1" and type(value.get("index")) is int and
                     value["index"] == index and value.get("sourceJoinId") == source and value.get("initialManifestSHA256") == manifest and
                     value.get("profileId") == fixture.PROFILE and value.get("fullLedgerValidated") is False and
                     value.get("diagnosticOnly") is True and ref.get("pin") == state and two.follows(final, state), "source-bound publication")
        if previous is not None:
            base.require(two.follows(state, previous) and state["generation"] > previous["generation"], "strict coherent publication progression")
        previous = state
        epoch(value.get("repeatedEpoch"), state)
        actual[name] = (ref["sha256"], value)
    if active_record.get("repeatedEpoch") is not None:
        epoch(active_record["repeatedEpoch"], two.pin(active_record.get("pin"), final["ownerId"]))
    base.require(len(epochs) >= 2 and max(epochs) == final["point"]["slot"]//1000, "multiple actual epochs observed")
    for transaction in observed:
        name = transaction.get("publicationFile")
        base.require(name in actual and actual[name] == (transaction.get("publicationSHA256"), transaction.get("publication")),
                     "both client original inclusion proofs bind retained publications")
    records = jvm_generations(root/"jvm-likelihood")
    selected, drafts = [], []
    for record in records:
        matches = [g for g in generations if all(g.get(k) == record[k] for k in
                   ("frozenId", "requestSHA256", "evidenceSHA256", "computedRaw32Words", "computedRaw64Words"))]
        # A completed recorder write may precede a canceled candidate. Preserve
        # its hashes as an unselected draft, never as a published generation.
        (selected if matches else drafts).append(record)
    base.require(all(any(all(g.get(k) == record[k] for k in
                     ("frozenId", "requestSHA256", "evidenceSHA256", "computedRaw32Words", "computedRaw64Words"))
                         for record in selected) for g in generations), "every publication generation has original JVM evidence")
    base.require(bool(selected), "actual selected JVM generation evidence")
    return dict(epochs=sorted(epochs), selectedGenerations=selected, unselectedDrafts=drafts,
                nativeParityChecked=False)


def require_comparator():
    raise NotImplementedError("repeated comparator implementation and reviewed contract are not yet available; no soak launch")


def soak_comparison_result(*_args):
    require_comparator()


def controller_type(live):
    Parent=two.controller_type(live)
    class SoakController(Parent):
        def __init__(self,*args):
            super().__init__(*args)
            self.deadline=time.monotonic()+MAX_OPERATION
            self.service_roots={p:self.exchange/p for p in two.SERVICE_PHASES}
            self.service_instances=[]
            self.instance_counter=0

        def create(self,phase,tail):
            # Bounded daemon logs as well as the owned evidence/database tree.
            tail=["--log-driver=json-file","--log-opt=max-size=16m","--log-opt=max-file=2",*tail]
            cid=super().create(phase,tail)
            obj=self.owned(cid)
            base.require(obj["HostConfig"]["LogConfig"] == {"Type":"json-file","Config":{"max-size":"16m","max-file":"2"}},"actual bounded Docker logs")
            if phase in two.SERVICE_PHASES:
                self.instance_counter+=1
                self.service_instances.append(dict(phase=phase,containerId=cid,instance=self.instance_counter))
            return cid

        def wait_service(self,phase,name,seconds):
            base.require(phase in two.SERVICE_PHASES and name in ("bootstrap-ready.json","service-active.json","result.json"),"closed repeated service receipt")
            root=self.service_roots[phase];until=min(self.deadline,time.monotonic()+seconds)
            while time.monotonic()<until:
                base.require(not(root/"failure.json").exists(),"service reported failure")
                if (root/name).exists():return base.decode(restart.read_exact(root/name,1048576))
                if not self.owned(self.containers[phase])["State"]["Running"]:
                    base.require(not(root/"failure.json").exists(),"service reported failure")
                    base.require((root/name).exists(),"service exited before receipt")
                    return base.decode(restart.read_exact(root/name,1048576))
                time.sleep(.1)
            raise TimeoutError("bounded repeated receipt")

        def remove_service(self,phase):
            base.require(phase in two.SERVICE_PHASES,"closed cleanup role")
            cid=self.containers.get(phase)
            found=self.docker("ps","-aq","--no-trunc","--filter","label="+live.LABEL+"="+self.token,
                              "--filter","label=lab.zero-live.phase="+phase).stdout.split()
            base.require(len(found)<=1,"unambiguous current owned service instance")
            if found and found != [cid]:
                base.require(cid is None or not self.docker("ps","-aq","--no-trunc","--filter","id="+cid).stdout.strip(),
                             "previous instance absent before recovering interrupted create")
                cid=found[0]
                self.containers[phase]=cid
            if not cid:return
            ids=self.docker("ps","-aq","--no-trunc","--filter","id="+cid).stdout.split()
            base.require(ids in ([],[cid]),"immutable cleanup identity")
            if ids:
                self.owned(cid)
                logs=self.docker("logs","--tail","1000",cid,check=False,timeout=3)
                path=self.out/(phase+"-"+cid[:12]+".log")
                if not path.exists():restart.publish_new(path,(logs.stdout+logs.stderr).encode()[-262144:] or b"\n")
                self.docker("rm","--force",cid)
            base.require(not self.docker("ps","-aq","--no-trunc","--filter","id="+cid).stdout.strip(),"instance absence")
            path=self.out/(phase+"-"+cid[:12]+"-cleanup.json")
            if not path.exists():base.write(path,dict(containerId=cid,absenceVerified=True,removedBeforeNamespaceOwner=True))

        def start_repeated(self,phase,root,manifest,restore_extra=(),restore_mounts=()):
            root.mkdir(mode=0o700)
            self.service_roots[phase]=root
            initial=self.exchange/"initial"
            args=repeated_args(self.args,root,initial,self.classpath,self.containers["reference"],live.process.PORTS[1],self.magic,manifest)
            pos=args.index("--entrypoint")
            mounts={(str(self.args.scala_build_root),"/work",False),(str(initial),"/initial",False),(str(root),"/exchange",True)}
            for host,guest in restore_mounts:
                base.require(host.resolve()==host and host.is_file() and guest.startswith("/restore/"),"exact readonly restore input")
                args[pos:pos]=["--mount","type=bind,src="+str(host)+",dst="+guest+",readonly"];pos+=2;mounts.add((str(host),guest,False))
            args+=list(restore_extra)
            cid=self.create(phase,args);observed=self.owned(cid)
            two.check_service(observed,phase,self.args.scala_image,"container:"+self.containers["reference"],mounts)
            base.write(self.out/(phase+"-soak-inspection.json"),observed)
            self.docker("start",cid)

        def resume_producer(self):
            self.stop_node(1);self.stop_node(2);self.stopped.clear()
            self.start_node(1,True);self.start_node(2,False)
            self.last_resumed=time.time_ns()//1000000
            return self.last_resumed

        def freeze_producer(self):
            self.stop_node(1);self.stop_node(2);self.stopped.clear()
            self.start_node(1,False);self.start_node(2,False)

        def prepare_soak_initial(self, approval):
            self.exchange.mkdir(mode=0o700)
            shutil.copytree(approval.evidence, self.out / "fixture")
            self.genesis = base.decode(base.read(self.environment / "shelley-genesis.json"))
            self.boundary_ms = base.future_boundary(self.genesis)
            self.configuration = self.read("configuration.yaml")
            self.magic = self.genesis["networkMagic"]
            for node in (1, 2):
                port = self.read("node-data/node" + str(node) + "/port").strip()
                base.require(port.isdigit() and 1 <= int(port) <= 65535, "generated private port")
                live.process.PORTS[node] = int(port)
                self.topologies[node] = base.decode(self.read("node-data/node" + str(node) + "/topology.json"))
            base.require(live.process.PORTS[1] != live.process.PORTS[2], "distinct ports")
            self.start_node(1, True)
            self.start_node(2, False)
            a, b = self.select_prefunding()
            self.stop_node(1)
            self.stop_node(2)
            self.start_node(1, False)
            self.start_node(2, False)
            # Clean stop can leave the non-forging peer one block behind.
            # Both restarted nodes are keyless; allow bounded synchronization.
            until = window_deadline(self.boundary_ms, 300, self.deadline, 10)
            while time.monotonic() < until:
                a, b = self.tip(1), self.tip(2)
                if live.process.same_tip(a, b):
                    break
                time.sleep(0.2)
            prefunding_point(a)
            prefunding_point(b)
            base.require(live.process.same_tip(a, b), "pre-funding keyless full-point convergence")
            a = self.prepare_initial(a)
            base.require(live.process.same_tip(a, self.tip(1)) and live.process.same_tip(a, self.tip(2)) and
                    a.get("era") == "Conway" and a.get("epoch") == 0 and 0 < a.get("slot", 0) < 300,
                    "prepared frozen initial point")
            self.initial = base.point(dict(slot=a["slot"], blockNo=a["block"], hash=a["hash"]))
            packet, acquisition = self.capture(self.initial, "initial")
            base.require(live.process.same_tip(a, self.tip(1)) and live.process.same_tip(a, self.tip(2)), "initial bracket moved")
            base.write(self.out / "initial-acquisition-result.json", acquisition)
            initial = self.exchange / "initial"
            shutil.copytree(packet, initial)
            shutil.copyfile(self.environment / "shelley-genesis.json", initial / "effective-shelley-genesis.json")
            self.project(initial)
            descriptor = dict(schema="native-ledger-v2-reviewed-inputs-v1", point=self.initial,
                              inputs=base.manifest(initial, base.PACKET_NAMES + ("native-projection.json", "effective-shelley-genesis.json")))
            base.write(initial / "adapter-inputs.json", descriptor)
            manifest_pin = base.sha(initial / "adapter-inputs.json")
            return manifest_pin

        def construct_transfer(self):
            base.require(getattr(self, "funding_sealed", False), "sealed double funding before service bootstrap")
            root = self.exchange / "submission"
            root.mkdir(mode=0o700)
            before = self.tip(1)
            base.require(frozen_point(before) == self.initial, "two-spend construction anchor")
            self.transfers = []
            for pair, conflict in ((0, False), (1, False), (0, True)):
                name = "conflict-1" if conflict else "transaction-"+str(pair+1)
                commands = soak_fixture.spend_commands(self.plan, self.funded_txid, self.initial, self.magic, pair, conflict, profile=PROFILE)
                self.execute(*commands["build"])
                self.execute(*commands["sign"])
                raw = self.retain_signed(commands["signed"], root / (name+".signed.json"))
                (root / (name+".cbor")).write_bytes(raw)
                if not conflict:
                    proof = self.proof_helper("plutus-spend-"+str(pair+1)+"-originals",
                        ["originals", "/exchange/submission/"+name+".cbor", "/exchange/submission/"+name+"-identity.json"],
                        root / (name+"-identity.json"))
                    base.require(proof.get("envelopeSHA256") == fixture.digest(raw) and
                                 all(base.hex64(proof.get(k)) for k in ("transactionId", "bodySHA256", "witnessesSHA256")),
                                 "two tested original envelopes")
                    self.transfers.append(dict(proof, spentInput=commands["spentInput"],
                        collateralInput=commands["collateralInput"], fee=commands["fee"], cliSubmitted=False))
            base.require(live.process.same_tip(before, self.tip(1)) and live.process.same_tip(before, self.tip(2)),
                         "two-spend construction frozen bracket")
            base.write(root / "descriptors.json", dict(profileId=fixture.PROFILE, transactions=self.transfers))

        def run_client(self):
            base.require(not getattr(self, "client_started", False), "one two-ingress helper")
            self.client_started = True
            output = self.exchange / "client-output"
            output.mkdir(mode=0o700)
            network = "container:"+self.containers["reference"]
            mounts = [(str(self.args.scala_build_root), "/work", False),
                      (str(output), "/exchange/client-output", True)] + [
                      (str(self.service_roots[p]), "/exchange/"+p, False) for p in SERVICE_PHASES]
            args = ["--pull=never", "--network="+network, "--cpus=1", "--memory=2g", "--memory-swap=2g",
                    "--pids-limit=256", "--read-only", "--cap-drop=ALL", "--security-opt=no-new-privileges",
                    "--user", "1000:1000", "--tmpfs", "/tmp:rw,nosuid,nodev,size=64m"]
            for source, destination, writable in mounts:
                args += ["--mount", "type=bind,src="+source+",dst="+destination+("" if writable else ",readonly")]
            args += ["--entrypoint", self.args.java, self.args.scala_image, "-Xmx512m", "-XX:ActiveProcessorCount=1",
                     "-cp", single.checked_classpath(self.args.client_classpath_file, self.args.scala_build_root),
                     "lab.PlutusSoakClientMain", *map(str, self.api_ports), str(self.client_duration),
                     "/exchange/service-1", "/exchange/service-2", "/exchange/client-output"]
            cid = self.create("ada-client", args)
            try:
                observed = self.owned(cid)
                base.check_resources(observed, "helper", self.args.scala_image, network)
                base.require({(m["Source"], m["Destination"], m["RW"]) for m in observed["Mounts"] if m["Type"] == "bind"} == set(mounts),
                             "two-ingress client exact read-only endpoint mounts")
                base.write(self.out / "client-inspection.json", observed)
                timeout = min(125, self.deadline-time.monotonic())
                base.require(timeout > 0, "bounded helper deadline")
                try:
                    attached = self.docker("start", "--attach", cid, timeout=timeout)
                except ValueError as error:
                    failed = getattr(live.bounded_process, "__globals__", {}).get("FailedProcess")
                    if isinstance(failed, type) and isinstance(error, failed):
                        try:
                            base.write(self.out / "client-helper-failure.json", single.retain_helper_failure(error, output))
                        except Exception:
                            pass
                    raise
                (self.out / "client-attach.log").write_text(attached.stdout+attached.stderr)
                state = self.owned(cid)["State"]
                base.require(not state["Running"] and state["ExitCode"] == 0 and not state["OOMKilled"], "two-ingress helper exit")
                self.client_receipt = soak_client_result(base.decode(base.read(output / "soak-client-result.json", 1048576)),
                                                   self.transfers, self.api_ports)
                base.write(self.out / "client-result.json", self.client_receipt)
                self.client_completed = True
            finally:
                self.remove_client()

        def compare_service(self, phase, terminal, ready, result):
            base.require(self.client_completed and all(not self.owned(self.containers[p])["State"]["Running"]
                         for p in SERVICE_PHASES), "serial oracle after both services and client exit")
            root = self.service_roots[phase]
            packet, acquisition = self.capture(terminal, phase+"-endpoint")
            base.write(root / "acquisition-result.json", acquisition)
            shutil.copytree(packet, root / "endpoint")
            acquisition_pin = base.sha(root / "acquisition-result.json")
            initial = self.exchange / "initial"
            base.write(root / "endpoint/endpoint-inputs.json", dict(schema="native-endpoint-reviewed-inputs-v1",
                       point=terminal, genesisSHA256=base.sha(initial / "effective-shelley-genesis.json"),
                       acquisitionResultSHA256=acquisition_pin, inputs=base.manifest(root / "endpoint", base.PACKET_NAMES)))
            base.write(root / "endpoint-ready.json", dict(schema="native-endpoint-ready-v1",
                       manifestSHA256=base.sha(root / "endpoint/endpoint-inputs.json"), acquisitionResultSHA256=acquisition_pin))
            role = phase+"-oracle"
            mounts = [(str(self.args.scala_build_root), "/work", False), (str(initial), "/initial", False),
                      (str(self.exchange / "submission"), "/originals", False), (str(root), "/exchange", True)]
            args = ["--pull=never", "--network=none", "--cpus=1", "--memory=2g", "--memory-swap=2g",
                    "--pids-limit=256", "--read-only", "--cap-drop=ALL", "--security-opt=no-new-privileges",
                    "--user", "1000:1000", "--tmpfs", "/tmp:rw,nosuid,nodev,size=64m"]
            for source, destination, writable in mounts:
                args += ["--mount", "type=bind,src="+source+",dst="+destination+("" if writable else ",readonly")]
            args += ["--entrypoint", self.args.java, self.args.scala_image, "-Xmx512m", "-XX:ActiveProcessorCount=1",
                     "-cp", single.checked_classpath(self.args.client_classpath_file, self.args.scala_build_root),
                     "lab.PlutusRepeatedServiceCompareMain", "/initial", self.manifest_pin, "/exchange", "/exchange",
                     "/originals/transaction-1.cbor", "/originals/transaction-2.cbor", "/exchange/service-comparison.json"]
            cid = self.create(role, args)
            try:
                observed = self.owned(cid)
                base.check_resources(observed, "helper", self.args.scala_image, "none")
                base.require({(m["Source"], m["Destination"], m["RW"]) for m in observed["Mounts"] if m["Type"] == "bind"} == set(mounts),
                             "serial oracle exact mounts")
                base.write(self.out / (role+"-inspection.json"), observed)
                timeout = min(35, self.deadline-time.monotonic())
                base.require(timeout > 0, "remaining oracle deadline")
                attached = self.docker("start", "--attach", cid, timeout=timeout)
                (self.out / (role+".log")).write_text(attached.stdout+attached.stderr)
                state = self.owned(cid)["State"]
                base.require(not state["Running"] and state["ExitCode"] == 0 and not state["OOMKilled"], "oracle completed")
                value = soak_comparison_result(base.decode(base.read(root / "service-comparison.json", 65536)),
                                                 self.transfers, terminal, ready["sourceJoinId"], self.manifest_pin)
                observation = base.decode(base.read(root / "terminal-observation.json", 1048576))
                base.require(value["terminalObservationSHA256"] == result["terminalObservationSHA256"] and
                             value["outputMapSHA256"] == observation["outputMapSHA256"], "oracle terminal original binding")
                base.write(self.out / (phase+"-endpoint-comparison.json"), value)
            finally:
                self.owned(cid)
                self.docker("rm", "--force", cid)
                base.require(not self.docker("ps", "-aq", "--no-trunc", "--filter", "id="+cid).stdout.strip(), "oracle absence")
                base.write(self.out / (role+"-cleanup.json"), dict(containerId=cid, absenceVerified=True))

        def verify_result(self, index, ready, result, resumed):
            phase = SERVICE_PHASES[index]
            root = self.service_roots[phase]
            row = self.client_receipt["endpoints"][index]
            owner = row["observedOwnerId"]
            final_pin = two.pin(result.get("finalPin"), owner)
            base.require(result.get("schema") == "plutus-service-result-v1" and result.get("status") == "stopped" and
                         result.get("stopReason") == "durationLimit" and result.get("profileId") == fixture.PROFILE and
                         result.get("initialPoint") == ready["initialPoint"] and result.get("sourceJoinId") == ready["sourceJoinId"] and
                         result.get("initialManifestSHA256") == self.manifest_pin, "service terminal source bindings")
            base.require(result.get("resourcesFinalized") is True and type(result.get("transportOpens")) is int and type(result.get("transportCloses")) is int and
                         result["transportOpens"] > 0 and result["transportOpens"] == result.get("transportCloses") and
                         all(result.get(k) is False for k in ("transactionSuccessClaimed", "fullLedgerValidated", "restartSupported")),
                         "finalized bounded service with limited claims")
            active_record = self.actives[phase]
            active_owner(active_record, row["initialState"]["pin"], final_pin)
            base.require(result.get("effectiveDeadlineUnixMillis") == active_record["deadlineUnixMillis"] and
                         result.get("requestedDeadlineUnixMillis") == active_record["deadlineUnixMillis"] and
                         result.get("startedUnixMillis") == active_record["startedUnixMillis"] and
                         type(result.get("endedUnixMillis")) is int and result["endedUnixMillis"] >= active_record["deadlineUnixMillis"],
                         "entire requested active duration, never early client success")
            check_epoch_mode(result.get("epochMode"))
            base.require(two.follows(final_pin, row["finalState"]["pin"]), "terminal includes final HTTP observation")
            terminal = base.point(final_pin["point"])
            base.require(self.initial["slot"] < terminal["slot"] < 8000 and terminal["slot"]//1000 >= self.args.duration_seconds//100 and
                         0 < terminal["blockNo"]-self.initial["blockNo"] <= self.args.max_blocks,
                         "bounded terminal successor")
            observed = [dict(tx, includedResponse=dict(pin=tx["publication"]["pin"])) for tx in row["observedTransactions"]]
            repeated_publications(root, result, observed, ready["sourceJoinId"], self.manifest_pin, active_record)
            base.require(result.get("terminalObservationFile") == "terminal-observation.json" and
                         result.get("terminalObservationSHA256") == base.sha(root / "terminal-observation.json"), "terminal raw bytes")
            observation = base.decode(base.read(root / "terminal-observation.json", 1048576))
            base.require(observation.get("pin") == final_pin and observation.get("sourceJoinId") == ready["sourceJoinId"] and
                         observation.get("initialManifestSHA256") == self.manifest_pin and observation.get("epoch") == terminal["slot"]//1000,
                         "terminal exact source/owner/epoch")
            refs = result.get("evaluationReceipts")
            base.require(isinstance(refs, list) and 1 <= len(refs) <= 128, "bounded endpoint evaluations")
            matches = []
            names = set()
            for ref in refs:
                relative = ref.get("file")
                base.require(isinstance(relative, str) and diagnostic.re.fullmatch(r"evaluation-receipts/plutus-evaluation-[0-9]{4}\.json", relative)
                             and relative not in names, "unique bounded evaluation filename")
                names.add(relative)
                path = root / relative
                base.require(path.resolve() == path, "evaluation no symlink traversal")
                raw = base.read(path, 16384)
                base.require(fixture.digest(raw) == ref.get("sha256"), "evaluation exact original pin")
                record = base.decode(raw)
                if record.get("transactionId") == self.transfers[index]["transactionId"] and record.get("phase") == "admission" and record.get("outcome") == "accepted" and record.get("newlyAdmitted") is True:
                    matches.append(ref)
            base.require(len(matches) == 1, "unique designated accepted evaluation")
            shaped = dict(evaluationReceiptFile=matches[0]["file"], evaluationReceiptSHA256=matches[0]["sha256"], evaluationReceiptCount=len(refs))
            raw = diagnostic.evaluation_receipt(root, shaped, self.transfers[index], ready["sourceJoinId"], self.manifest_pin, row)
            with (self.out / (phase+"-evaluation-receipt.json")).open("xb") as stream:
                stream.write(raw)
            base.write(self.out / (phase+"-result.json"), result)
            return terminal

        def same_epoch_lifecycle(self, approval):
            require_comparator()
            # The inherited dispatcher name is retained; this explicit profile
            # uses the repeated runtime and never claims the old same-epoch gate.
            self.manifest_pin = self.prepare_soak_initial(approval)
            self.construct_transfer()
            source_args = self.args
            early = copy.copy(source_args)
            early.duration_seconds, early.max_blocks = 30, 8
            self.args = early
            def continuation(_args, *remaining):
                return repeated_args(source_args, *remaining)
            try:
                restored = restart.perform_early_restart(self, live, self.manifest_pin,
                    store_id=restart.digest(os.urandom(32)), session_id=restart.digest(os.urandom(32)), generation=0,
                    resume_producer=self.resume_producer, freeze_producer=self.freeze_producer,
                    checkpoint_after=1, continuation_builder=continuation)
            finally:
                self.args = source_args
            self.restored = restored
            # Old service-1 has exited and been removed. Its files are retained;
            # the independent peer has a different output root and process ID.
            self.start_repeated("service-1", self.exchange/"peer-service", self.manifest_pin)
            peer = self.wait_service("service-1", "bootstrap-ready.json", 25)
            readies = [peer, restored.ready]
            self.api_ports = []
            for phase, ready in zip(SERVICE_PHASES, readies):
                expected = self.initial if phase == "service-1" else restored.claim["terminalPoint"]
                base.require(ready.get("schema") == "plutus-service-ready-v1" and ready.get("initialPoint") == expected and
                             ready.get("profileId") == fixture.PROFILE and ready.get("initialEpoch") == 0 and
                             ready.get("networkMagic") == self.magic and ready.get("initialManifestSHA256") == self.manifest_pin and
                             ready.get("sourceJoinId") == restored.claim["sourceJoinId"], "checked repeated bootstrap source")
                check_epoch_mode(ready.get("epochMode"))
                port = ready.get("apiPort")
                base.require(type(port) is int and 1 <= port <= 65535 and port not in live.process.PORTS.values(), "actual API port")
                self.api_ports.append(port)
                limits = ready.get("limits", {})
                base.require(type(limits.get("durationSeconds")) is int and limits["durationSeconds"] == self.args.duration_seconds and
                             type(limits.get("maxBlocks")) is int and limits["maxBlocks"] == 512 and
                             limits.get("maxEvents") == 4096 and limits.get("maxEvaluationReceipts") == 128, "bounded repeated runtime")
                base.write(self.out/(phase+"-bootstrap-proof.json"), ready)
            base.require(len(set(self.api_ports)) == 2, "distinct loopback ingress ports")
            # Do not restart the reference process once the restored follower is
            # active. The fresh peer joins that same running mutable chain.
            base.write(self.service_roots["service-1"]/"peer-ready.json", dict(schema="native-live-peer-ready-v1",
                       referenceContainerId=self.containers["reference"], generatedPort=live.process.PORTS[1],
                       networkMagic=self.magic, producerResumedUnixMillis=self.last_resumed))
            self.actives = {p: active(self.wait_service(p, "service-active.json", 10), self.args.duration_seconds) for p in SERVICE_PHASES}
            for index, phase in enumerate(SERVICE_PHASES):
                destination = self.service_roots[phase]/"submission"
                destination.mkdir(mode=0o700)
                shutil.copyfile(self.exchange/"submission"/(f"transaction-{index+1}.cbor"), destination/"transaction-1.cbor")
            restart.wait_checked_successor(self, restored, seconds=20)
            self.client_duration = 120
            self.run_client()
            # Client completion is never the service lifetime criterion.
            results = self.wait_full_intervals()
            for phase in SERVICE_PHASES:
                restart.wait_exit(self, phase)
            terminals = [self.verify_result(i, ready, result, self.last_resumed)
                         for i, (ready, result) in enumerate(zip(readies, results))]
            for phase, terminal, ready, result in zip(SERVICE_PHASES, terminals, readies, results):
                self.compare_service(phase, terminal, ready, result)
            self.stop_node(1)
            self.stop_node(2)
            self.cleanup()
            return terminals

        def wait_full_intervals(self):
            results = {}
            while len(results) != 2 and time.monotonic() < self.deadline:
                for phase in SERVICE_PHASES:
                    if phase in results:
                        continue
                    root = self.service_roots[phase]
                    base.require(not (root/"failure.json").exists(), "active service failure")
                    if (root/"result.json").exists():
                        result = base.decode(restart.read_exact(root/"result.json", 1048576))
                        full_interval(result, self.actives[phase])
                        results[phase] = result
                    else:
                        if not self.owned(self.containers[phase])["State"]["Running"]:
                            base.require(not (root/"failure.json").exists(), "active service failure")
                            base.require((root/"result.json").exists(), "service exited before full active evidence")
                            result = base.decode(restart.read_exact(root/"result.json", 1048576))
                            full_interval(result, self.actives[phase])
                            results[phase] = result
                if len(results) != 2:
                    time.sleep(.5)
            base.require(len(results) == 2, "both full intervals within outer bound")
            return [results[p] for p in SERVICE_PHASES]

    return SoakController

def full_interval(result, clock):
    base.require(result.get("schema") == "plutus-service-result-v1" and result.get("status") == "stopped" and
                 result.get("stopReason") == "durationLimit" and result.get("startedUnixMillis") == clock["startedUnixMillis"] and
                 result.get("requestedDeadlineUnixMillis") == result.get("effectiveDeadlineUnixMillis") == clock["deadlineUnixMillis"] and
                 type(result.get("endedUnixMillis")) is int and result["endedUnixMillis"] >= clock["deadlineUnixMillis"],
                 "full requested active interval, no block-limit or early success substitution")


def active_owner(clock, initial, final):
    current = two.pin(final)
    start = two.pin(clock.get("pin"), current["ownerId"])
    first = two.pin(initial, current["ownerId"])
    base.require(two.follows(first, start) and two.follows(current, first),
                 "actual active clock and HTTP observations belong to the same progressing owner")


def execute(live, support, args, classpath):
    require_comparator()
    soak_limits(args.duration_seconds, args.max_blocks)
    launch = controller_type(live)(support, args, classpath)
    watchdog = live.DiskWatchdog(args.owned_root)
    monitor = BudgetMonitor([args.owned_root, args.evidence_root])
    previous = {}
    def interrupted(signum, _frame):
        if launch.in_cleanup and signum != signal.SIGALRM:
            return
        raise TimeoutError("bounded soak operation interrupted")
    started = time.monotonic()
    try:
        previous = {s: signal.signal(s, interrupted) for s in (signal.SIGALRM, signal.SIGTERM, signal.SIGINT, signal.SIGUSR1)}
        signal.alarm(MAX_OPERATION)
        watchdog.start()
        monitor.start()
        base.write(launch.out/"invocation.json", dict(schema="plutus-soak-invocation-v1", soakProfile=PROFILE, epochMode=MODE,
                   supportManifestSHA256=args.support_sha, controllerSHA256=base.sha(Path(__file__)),
                   twoServiceControllerSHA256=base.sha(Path(two.__file__)), restartControllerSHA256=base.sha(Path(restart.__file__)),
                   fixtureSHA256=base.sha(Path(soak_fixture.__file__)), resourcesNanoCpuAndBytes=two.RESOURCES,
                   scalaImage=args.scala_image, runtimeClasspathSHA256=base.sha(args.scala_classpath_file),
                   clientClasspathSHA256=base.sha(args.client_classpath_file), runtimeEntrypoint="lab.Main plutus-service",
                   operationSeconds=MAX_OPERATION, cleanupSeconds=MAX_CLEANUP, durationSeconds=args.duration_seconds,
                   maxBlocks=args.max_blocks, profileId=fixture.PROFILE, testedSpendCliSubmissionAllowed=False,
                   cliSubmissionAllowed="one-reference-funding-before-bootstrap-only", testedSpendTTL=8000,
                   nativeRuntimeDependency=False, posthocNativeComparisonClaimed=False))
        base.require(launch.docker("image", "inspect", args.scala_image, "--format", "{{.Id}}").stdout.strip() == args.scala_image,
                     "pinned Scala image")
        ids = dict(generatorSHA256=live.z.GENERATOR_SHA, launcherSHA256=base.sha(Path(__file__)),
                   imageId=live.z.REFERENCE_IMAGE, sourceCommit=support["sourceCommit"])
        result, receipt = live.controller.execute(args.owned_root, ids, launch.generate, launch.lifecycle, on_abort=launch.cleanup)
        base.write(launch.out/"controller-lifecycle.json", receipt)
        base.require(launch.cleaned and watchdog.failure is None and monitor.failure is None and
                     time.monotonic()-started <= MAX_OPERATION+MAX_CLEANUP, "bounded finalized soak")
        base.write(launch.out/"result.json", dict(schema="plutus-soak-controller-result-v1", passed=True,
                   cleanupVerified=True, elapsedSeconds=time.monotonic()-started, durationSeconds=args.duration_seconds,
                   soakProfile=PROFILE, epochMode=MODE, profileId=fixture.PROFILE, exchangeRoot=str(launch.exchange),
                   initialPoint=launch.initial, finalPoints=result, serviceCount=2,
                   earlyRestart=dict(authoritySHA256=launch.restored.authority_sha256,
                     successorSHA256=base.sha(launch.out/"restart-successor.json"), freshOwner=True, emptyPoolObserved=True),
                   independentIngressOwners=True, postBoundaryHttpSpend=True, fullActiveIntervalsVerified=True,
                   fullLedgerValidated=False, lateCheckpointRestoreSupported=False, crashDurable=False,
                   nativeRuntimeDependency=False, posthocNativeComparisonClaimed=False,
                   diskHighWater=monitor.high_water,
                   clientResultSHA256=base.sha(launch.out/"client-result.json"),
                   serviceResultSHA256=[base.sha(launch.out/(p+"-result.json")) for p in SERVICE_PHASES],
                   endpointComparisonSHA256=[base.sha(launch.out/(p+"-endpoint-comparison.json")) for p in SERVICE_PHASES],
                   evaluationReceiptSHA256=[base.sha(launch.out/(p+"-evaluation-receipt.json")) for p in SERVICE_PHASES],
                   transactionIds=[t["transactionId"] for t in launch.transfers]))
    except BaseException as error:
        if not (launch.out/"failure.json").exists():
            base.write(launch.out/"failure.json", dict(errorType=type(error).__name__, message=str(error)[:4096]))
        raise
    finally:
        try:
            launch.cleanup()
        finally:
            monitor.stop()
            watchdog.stop()
            signal.alarm(0)
            for sig, handler in previous.items():
                signal.signal(sig, handler)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("support-manifest", "owned-root", "evidence-root", "scala-build-root", "scala-classpath-file", "client-classpath-file", "projection"):
        parser.add_argument("--"+name, type=Path, required=True)
    parser.add_argument("--support-sha", required=True)
    parser.add_argument("--scala-image", required=True)
    parser.add_argument("--java", default="java")
    parser.add_argument("--duration-seconds", type=int, choices=(120, 600), default=120)
    parser.add_argument("--max-blocks", type=int, choices=(512,), default=512)
    parser.add_argument("--soak-profile", choices=(PROFILE,), required=True)
    parser.add_argument("--epoch-mode", choices=(MODE,), required=True)
    parser.add_argument("--execute", action="store_true")
    args = parser.parse_args()
    soak_limits(args.duration_seconds, args.max_blocks)
    require_comparator()
    live, support, classpath = base.preflight(args)
    base.require(classpath == single.checked_classpath(args.scala_classpath_file, args.scala_build_root, True), "Compile-only runtimes")
    single.checked_classpath(args.client_classpath_file, args.scala_build_root)
    if args.execute:
        execute(live, support, args, classpath)
    else:
        print(json.dumps(dict(preflight=True, executed=False, soakProfile=PROFILE, epochMode=MODE,
                         resourcesNanoCpuAndBytes=two.RESOURCES, nativeRuntimeDependency=False), sort_keys=True))


if __name__ == "__main__":
    main()
