#!/usr/bin/env python3
"""Opt-in bounded two-service local ingress topology; preflight unless --execute.

Two independently owned Scala processes share the one owned reference network
namespace and frozen source. Each accepts one disjoint Plutus transaction via
HTTP. Both terminal states are compared with exact historical reference state.
"""
import argparse
from pathlib import Path
import shutil
import signal
import time
import private_cluster_plutus_service as single

base = single.base
fixture = single.fixture
diagnostic = single.diagnostic
window_deadline = single.window_deadline
frozen_point = single.frozen_point
prefunding_point = single.prefunding_point
SERVICE_PHASES = ("service-1", "service-2")
RESOURCES = {"reference": (2_000_000_000, 3 * base.GIB),
             "service-1": (500_000_000, base.GIB), "service-2": (500_000_000, base.GIB),
             "helper": (1_000_000_000, 2 * base.GIB)}


def budget(resources=RESOURCES):
    base.require(set(resources) == {"reference", "service-1", "service-2", "helper"}, "exact two-service roles")
    base.require(all(type(c) is int and c > 0 and type(m) is int and m > 0 for c, m in resources.values()),
                 "positive exact NanoCPU/memory limits")
    base.require(sum(c for c, _ in resources.values()) <= 4_000_000_000 and
                 sum(m for _, m in resources.values()) <= 7 * base.GIB, "aggregate four CPU/seven GiB budget")


def check_service(obj, phase, image, network, mounts):
    budget()
    base.require(phase in SERVICE_PHASES, "closed service role")
    h = obj["HostConfig"]
    cpu, memory = RESOURCES[phase]
    base.require(obj["Image"] == image and h["NetworkMode"] == network and
                 h["NanoCpus"] == cpu and h["Memory"] == memory and h["MemorySwap"] == memory,
                 "actual two-service image/network/resources")
    base.require(h["PidsLimit"] == 256 and h["ReadonlyRootfs"] is True and
                 obj["Config"]["User"] == "1000:1000" and "ALL" in h["CapDrop"] and
                 any(v.startswith("no-new-privileges") for v in h["SecurityOpt"]), "service privileges")
    base.require({(m["Source"], m["Destination"], m["RW"]) for m in obj["Mounts"] if m["Type"] == "bind"} ==
                 set(mounts), "isolated service exact mounts")


def service_args(args, root, initial, classpath, reference, peer_port, magic, manifest):
    single.service_limits(args.duration_seconds, args.max_blocks)
    base.require(root.is_absolute() and root.resolve() == root and initial.is_absolute() and
                 initial.resolve() == initial and root != initial, "separate canonical service output and source")
    return ["--pull=never", "--network=container:"+reference, "--cpus=0.5", "--memory=1g", "--memory-swap=1g",
            "--pids-limit=256", "--read-only", "--cap-drop=ALL", "--security-opt=no-new-privileges",
            "--user", "1000:1000", "--tmpfs", "/tmp:rw,nosuid,nodev,size=64m",
            "--mount", "type=bind,src="+str(args.scala_build_root)+",dst=/work,readonly",
            "--mount", "type=bind,src="+str(initial)+",dst=/initial,readonly",
            "--mount", "type=bind,src="+str(root)+",dst=/exchange",
            "--entrypoint", args.java, args.scala_image, "-Xmx512m", "-XX:ActiveProcessorCount=1",
            "-cp", classpath, "lab.Main", "plutus-service", "--profile", fixture.PROFILE,
            "--initial", "/initial", "--manifest-sha256", manifest, "--port", str(peer_port),
            "--magic", str(magic), "--output", "/exchange", "--duration-seconds", str(args.duration_seconds),
            "--max-blocks", str(args.max_blocks)]


def pin(value, owner=None):
    base.require(isinstance(value, dict) and set(value) == {"ownerId", "generation", "point", "coherentStateId",
                 "ledgerStateId", "environmentId", "validationSlot", "profileId"}, "full typed state pin")
    base.require(value["profileId"] == fixture.PROFILE and all(base.hex64(value[k]) for k in
                 ("ownerId", "coherentStateId", "ledgerStateId", "environmentId")), "state pin profile and identities")
    p = base.point(value["point"])
    base.require(type(value["generation"]) is int and 0 <= value["generation"] < 2**64 and
                 type(value["validationSlot"]) is int and value["validationSlot"] == p["slot"], "state pin generation/slot")
    base.require(owner is None or value["ownerId"] == owner, "endpoint-specific owner")
    return value


def follows(later, earlier):
    return later["generation"] >= earlier["generation"] and later["point"]["slot"] >= earlier["point"]["slot"] and \
        later["point"]["blockNo"] >= earlier["point"]["blockNo"] and \
        (later["generation"] != earlier["generation"] or later == earlier) and \
        ((later["point"]["slot"] != earlier["point"]["slot"] and later["point"]["blockNo"] != earlier["point"]["blockNo"]) or
         later["point"] == earlier["point"])


def client_result(value, transfers, ports):
    base.require(value.get("schema") == "multi-endpoint-client-result-v1" and value.get("passed") is True and
                 value.get("diagnosticOnly") is True and value.get("resourcesFinalized") is True and
                 value.get("fullLedgerValidated") is False and value.get("submittedEnvelopeByteEqualityVerified") is False,
                 "bounded finalized two-endpoint HTTP client evidence")
    rows = value.get("endpoints")
    base.require(isinstance(rows, list) and len(rows) == len(transfers) == len(ports) == 2 and
                 len(set(ports)) == 2, "two distinct HTTP endpoints")
    base.require(len({t[k] for t in transfers for k in ("spentInput", "collateralInput")}) == 4,
                 "two independent script/collateral pairs")
    owners = []
    for index, (row, tx, port) in enumerate(zip(rows, transfers, ports)):
        base.require(row.get("endpointId") == SERVICE_PHASES[index] and row.get("port") == port and
                     type(port) is int and 1 <= port <= 65535, "designated ingress identity")
        owner = row.get("observedOwnerId")
        base.require(base.hex64(owner), "actual observed owner ID")
        owners.append(owner)
        for key in ("transactionId", "envelopeSHA256", "bodySHA256", "witnessesSHA256"):
            base.require(base.hex64(row.get(key)) and row[key] == tx[key], "designated original: "+key)
        for key in ("initialState", "finalState"):
            state = row.get(key, {})
            base.require(state.get("code") == "State" and state.get("closed") is False and state.get("volatile") is True and
                         state.get("profileId") == fixture.PROFILE and state.get("fullLedgerValidated") is False,
                         "scoped endpoint state")
            pin(state.get("pin"), owner)
        accepted, included = row.get("acceptedResponse", {}), row.get("includedResponse", {})
        base.require(accepted.get("code") == "Accepted" and included.get("code") == "Included" and
                     accepted.get("profileId") == included.get("profileId") == fixture.PROFILE and
                     accepted.get("fullLedgerValidated") is False and included.get("fullLedgerValidated") is False and
                     accepted.get("volatile") is True and included.get("volatile") is True,
                     "actual scoped accepted/included responses")
        receipt = accepted.get("receipt", {})
        base.require(receipt.get("transactionId") == tx["transactionId"] and
                     receipt.get("envelopeSHA256") == tx["envelopeSHA256"] and
                     included.get("transactionId") == tx["transactionId"] and
                     included.get("submittedEnvelopeByteEqualityVerified") is False, "accepted original envelope binding")
        a, b = pin(receipt.get("pin"), owner), pin(included.get("pin"), owner)
        base.require(a["generation"] < b["generation"] and a["point"]["slot"] < b["point"]["slot"] and
                     a["point"]["blockNo"] < b["point"]["blockNo"], "strict successor inclusion per ingress")
        base.require(follows(a, row["initialState"]["pin"]) and follows(row["finalState"]["pin"], b), "endpoint state surrounds inclusion")
        observed = row.get("observedTransactions")
        base.require(isinstance(observed, list) and len(observed) == 2, "both originals observed at each endpoint")
        for seen, original in zip(observed, transfers):
            for key in ("transactionId", "bodySHA256", "witnessesSHA256"):
                base.require(seen.get(key) == original[key], "cross-endpoint original identity")
            pub = seen.get("publication", {})
            pin(pub.get("pin"), owner)
            base.require(follows(row["finalState"]["pin"], pub["pin"]), "final state includes both observed publications")
            identity = {k: original[k] for k in ("transactionId", "bodySHA256", "witnessesSHA256")}
            base.require(isinstance(pub.get("included"), list) and pub["included"].count(identity) == 1,
                         "actual original spans in each endpoint publication")
        own = observed[index]
        base.require(all(row.get(k) == own.get(k) for k in ("publicationFile", "publicationSHA256", "publication")) and
                     row["publication"].get("pin") == b, "HTTP inclusion and exact designated publication pin")
    base.require(len(set(owners)) == 2, "two distinct actual service owners")
    return value


def controller_type(live):
    ServiceController = single.controller_type(live)

    class TwoServiceController(ServiceController):
        def cleanup(self):
            if self.cleaned:
                return
            self.in_cleanup = True
            if self.cleanup_deadline is None:
                self.cleanup_deadline = time.monotonic()+30
            self.deadline = self.cleanup_deadline
            signal.alarm(max(1, int(self.deadline-time.monotonic())))
            self.remove_client()
            for phase in reversed(SERVICE_PHASES):
                self.remove_service(phase)
            super().cleanup()

        def remove_service(self, phase):
            base.require(phase in SERVICE_PHASES, "closed cleanup role")
            found = self.docker("ps", "-aq", "--no-trunc", "--filter", "label="+live.LABEL+"="+self.token,
                                "--filter", "label=lab.zero-live.phase="+phase).stdout.split()
            base.require(len(found) <= 1, "unambiguous service ownership")
            cid = self.containers.get(phase)
            base.require(not found or cid is None or found == [cid], "service immutable identity")
            if not cid and found:
                cid = found[0]
            if cid:
                ids = self.docker("ps", "-aq", "--no-trunc", "--filter", "id="+cid).stdout.split()
                base.require(ids in ([], [cid]), "service cleanup identity")
                if ids:
                    self.owned(cid)
                    try:
                        logs = self.docker("logs", "--tail", "1000", cid, check=False, timeout=3)
                        with (self.out / (phase+".log")).open("xb") as stream:
                            stream.write((logs.stdout+logs.stderr).encode()[-262144:])
                    except BaseException as error:
                        self.diagnostic_write(phase+"-log-error.json", dict(errorType=type(error).__name__))
                    self.docker("rm", "--force", cid)
                base.require(not self.docker("ps", "-aq", "--no-trunc", "--filter", "id="+cid).stdout.strip(), "service absence")
                if not (self.out / (phase+"-cleanup.json")).exists():
                    base.write(self.out / (phase+"-cleanup.json"), dict(containerId=cid, absenceVerified=True,
                               removedBeforeNamespaceOwner=True))

        def wait_service(self, phase, name, seconds):
            base.require(phase in SERVICE_PHASES and name in ("bootstrap-ready.json", "result.json"), "closed service receipt")
            root = self.exchange / phase
            until = min(self.deadline, time.monotonic()+seconds)
            while time.monotonic() < until:
                base.require(not (root / "failure.json").exists(), "service reported failure")
                path = root / name
                if path.exists():
                    base.require(path.resolve() == path, "service receipt no symlink traversal")
                    return base.decode(base.read(path, 1048576))
                base.require(self.owned(self.containers[phase])["State"]["Running"], "service exited before receipt")
                time.sleep(0.1)
            raise TimeoutError("bounded two-service readiness")

        def start_services(self, manifest):
            self.construct_transfer()
            self.manifest_pin = manifest
            initial = self.exchange / "initial"
            for index, phase in enumerate(SERVICE_PHASES, 1):
                root = self.exchange / phase
                root.mkdir(mode=0o700)
                (root / "submission").mkdir(mode=0o700)
                shutil.copyfile(self.exchange / "submission" / f"transaction-{index}.cbor",
                                root / "submission/transaction-1.cbor")
                single.epoch_budget(self.boundary_ms, self.args.duration_seconds)
                args = service_args(self.args, root, initial, self.classpath, self.containers["reference"],
                                    live.process.PORTS[1], self.magic, manifest)
                cid = self.create(phase, args)
                observed = self.owned(cid)
                check_service(observed, phase, self.args.scala_image, "container:"+self.containers["reference"],
                              {(str(self.args.scala_build_root), "/work", False), (str(initial), "/initial", False),
                               (str(root), "/exchange", True)})
                base.write(self.out / (phase+"-inspection.json"), observed)
                self.docker("start", cid)

        def run_client(self):
            base.require(not getattr(self, "client_started", False), "one two-ingress helper")
            self.client_started = True
            output = self.exchange / "client-output"
            output.mkdir(mode=0o700)
            network = "container:"+self.containers["reference"]
            mounts = [(str(self.args.scala_build_root), "/work", False),
                      (str(output), "/exchange/client-output", True)] + [
                      (str(self.exchange / p), "/exchange/"+p, False) for p in SERVICE_PHASES]
            args = ["--pull=never", "--network="+network, "--cpus=1", "--memory=2g", "--memory-swap=2g",
                    "--pids-limit=256", "--read-only", "--cap-drop=ALL", "--security-opt=no-new-privileges",
                    "--user", "1000:1000", "--tmpfs", "/tmp:rw,nosuid,nodev,size=64m"]
            for source, destination, writable in mounts:
                args += ["--mount", "type=bind,src="+source+",dst="+destination+("" if writable else ",readonly")]
            args += ["--entrypoint", self.args.java, self.args.scala_image, "-Xmx512m", "-XX:ActiveProcessorCount=1",
                     "-cp", single.checked_classpath(self.args.client_classpath_file, self.args.scala_build_root),
                     "lab.PlutusMultiEndpointClientMain", *map(str, self.api_ports), str(self.client_duration),
                     "/exchange/service-1", "/exchange/service-2", "/exchange/client-output"]
            cid = self.create("ada-client", args)
            try:
                observed = self.owned(cid)
                base.check_resources(observed, "helper", self.args.scala_image, network)
                base.require({(m["Source"], m["Destination"], m["RW"]) for m in observed["Mounts"] if m["Type"] == "bind"} == set(mounts),
                             "two-ingress client exact read-only endpoint mounts")
                base.write(self.out / "client-inspection.json", observed)
                timeout = min(self.args.duration_seconds+5, self.deadline-time.monotonic())
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
                self.client_receipt = client_result(base.decode(base.read(output / "multi-endpoint-client-result.json", 1048576)),
                                                   self.transfers, self.api_ports)
                base.write(self.out / "client-result.json", self.client_receipt)
                self.client_completed = True
            finally:
                self.remove_client()

        def compare_service(self, phase, terminal, ready, result):
            base.require(self.client_completed and all(not self.owned(self.containers[p])["State"]["Running"]
                         for p in SERVICE_PHASES), "serial oracle after both services and client exit")
            root = self.exchange / phase
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
                     "lab.PlutusServiceCompareMain", "/initial", self.manifest_pin, "/exchange", "/exchange",
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
                value = single.comparison_result(base.decode(base.read(root / "service-comparison.json", 65536)),
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
            root = self.exchange / phase
            row = self.client_receipt["endpoints"][index]
            owner = row["observedOwnerId"]
            final_pin = pin(result.get("finalPin"), owner)
            base.require(result.get("schema") == "plutus-service-result-v1" and result.get("status") == "stopped" and
                         result.get("stopReason") in ("durationLimit", "blockLimit") and result.get("profileId") == fixture.PROFILE and
                         result.get("initialPoint") == self.initial and result.get("sourceJoinId") == ready["sourceJoinId"] and
                         result.get("initialManifestSHA256") == self.manifest_pin, "service terminal source bindings")
            base.require(result.get("resourcesFinalized") is True and type(result.get("transportOpens")) is int and
                         result["transportOpens"] > 0 and result["transportOpens"] == result.get("transportCloses") and
                         all(result.get(k) is False for k in ("transactionSuccessClaimed", "fullLedgerValidated", "restartSupported")),
                         "finalized bounded service with limited claims")
            base.require(result.get("effectiveDeadlineUnixMillis") == ready["deadlineUnixMillis"] and
                         type(result.get("startedUnixMillis")) is int and type(result.get("endedUnixMillis")) is int and
                         resumed <= result["startedUnixMillis"] <= result["endedUnixMillis"] < self.boundary_ms,
                         "service actually remains same epoch")
            base.require(result["stopReason"] != "durationLimit" or result["endedUnixMillis"] >= ready["deadlineUnixMillis"], "duration stop reached configured deadline")
            base.require(follows(final_pin, row["finalState"]["pin"]), "terminal includes final HTTP observation")
            terminal = base.point(final_pin["point"])
            base.require(self.initial["slot"] < terminal["slot"] < 1000 and
                         0 < terminal["blockNo"]-self.initial["blockNo"] <= self.args.max_blocks,
                         "bounded terminal successor")
            observed = [dict(tx, includedResponse=dict(pin=tx["publication"]["pin"])) for tx in row["observedTransactions"]]
            single.publication_bindings(root, result, dict(transactions=observed), ready["sourceJoinId"], self.manifest_pin)
            base.require(result.get("terminalObservationFile") == "terminal-observation.json" and
                         result.get("terminalObservationSHA256") == base.sha(root / "terminal-observation.json"), "terminal raw bytes")
            observation = base.decode(base.read(root / "terminal-observation.json", 1048576))
            base.require(observation.get("pin") == final_pin and observation.get("sourceJoinId") == ready["sourceJoinId"] and
                         observation.get("initialManifestSHA256") == self.manifest_pin and observation.get("epoch") == 0,
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
            self.start_services(manifest_pin)
            readies = [self.wait_service(phase, "bootstrap-ready.json", 25) for phase in SERVICE_PHASES]
            self.api_ports = []
            for phase, ready in zip(SERVICE_PHASES, readies):
                base.require(ready.get("schema") == "plutus-service-ready-v1" and ready.get("initialPoint") == self.initial and
                             ready.get("profileId") == fixture.PROFILE and ready.get("initialEpoch") == 0 and
                             ready.get("networkMagic") == self.magic and ready.get("initialManifestSHA256") == manifest_pin and
                             base.hex64(ready.get("sourceJoinId")), "independent service bootstrap source")
                port = ready.get("apiPort")
                base.require(type(port) is int and 1 <= port <= 65535 and port not in live.process.PORTS.values(), "independent loopback API port")
                self.api_ports.append(port)
                limits = ready.get("limits", {})
                base.require(limits.get("durationSeconds") == self.args.duration_seconds and limits.get("maxBlocks") == self.args.max_blocks and
                             limits.get("maxEvents") == 4096 and limits.get("maxEvaluationReceipts") == 128, "bounded service readiness")
                deadline = ready.get("deadlineUnixMillis")
                base.require(type(deadline) is int and time.time_ns()//1000000 < deadline < self.boundary_ms and
                             deadline == ready.get("requestedDeadlineUnixMillis"), "uncropped service duration in epoch")
                base.write(self.out / (phase+"-bootstrap-proof.json"), ready)
            base.require(len(set(self.api_ports)) == 2, "distinct actual API ports")
            self.stop_node(1)
            self.stop_node(2)
            self.stopped.clear()
            self.start_node(1, True)
            self.start_node(2, False)
            resumed = time.time_ns()//1000000
            earliest_deadline = min(r["deadlineUnixMillis"] for r in readies)
            self.client_duration = min(self.args.duration_seconds, (earliest_deadline-resumed)//1000)
            base.require(self.client_duration >= 3, "useful shared client interval before either service deadline")
            for phase in SERVICE_PHASES:
                base.write(self.exchange / phase / "peer-ready.json", dict(schema="native-live-peer-ready-v1",
                           referenceContainerId=self.containers["reference"], generatedPort=live.process.PORTS[1],
                           networkMagic=self.magic, producerResumedUnixMillis=resumed))
            self.run_client()
            results = [self.wait_service(phase, "result.json", self.args.duration_seconds+10) for phase in SERVICE_PHASES]
            for phase in SERVICE_PHASES:
                until = min(self.deadline, time.monotonic()+5)
                state = self.owned(self.containers[phase])["State"]
                while state["Running"] and time.monotonic() < until:
                    time.sleep(0.1)
                    state = self.owned(self.containers[phase])["State"]
                base.require(not state["Running"] and state["ExitCode"] == 0 and not state["OOMKilled"], "owned service exited cleanly")
            terminals = [self.verify_result(index, ready, result, resumed)
                         for index, (ready, result) in enumerate(zip(readies, results))]
            for phase, terminal, ready, result in zip(SERVICE_PHASES, terminals, readies, results):
                self.compare_service(phase, terminal, ready, result)
            self.stop_node(1)
            self.stop_node(2)
            self.cleanup()
            return terminals


    return TwoServiceController


def execute(live, support, args, classpath):
    budget()
    launch = controller_type(live)(support, args, classpath)
    watchdog = live.DiskWatchdog(args.owned_root)
    previous = {}
    def interrupted(signum, _frame):
        if launch.in_cleanup and signum != signal.SIGALRM:
            return
        raise TimeoutError("bounded two-service operation interrupted")
    started = time.monotonic()
    try:
        previous = {s: signal.signal(s, interrupted) for s in (signal.SIGALRM, signal.SIGTERM, signal.SIGINT, signal.SIGUSR1)}
        signal.alarm(240)
        watchdog.start()
        base.write(launch.out / "invocation.json", dict(schema="plutus-two-service-invocation-v1",
                   supportManifestSHA256=args.support_sha, controllerSHA256=base.sha(Path(__file__)),
                   singleControllerSHA256=base.sha(Path(single.__file__)), diagnosticControllerSHA256=base.sha(Path(diagnostic.__file__)),
                   baseControllerSHA256=base.sha(Path(base.__file__)), resourcesNanoCpuAndBytes=RESOURCES,
                   scalaImage=args.scala_image, runtimeClasspathSHA256=base.sha(args.scala_classpath_file),
                   clientClasspathSHA256=base.sha(args.client_classpath_file), runtimeEntrypoint="lab.Main plutus-service",
                   operationSeconds=240, cleanupSeconds=30, durationSeconds=args.duration_seconds, maxBlocks=args.max_blocks,
                   profileId=fixture.PROFILE, testedSpendCliSubmissionAllowed=False,
                   cliSubmissionAllowed="one-reference-funding-before-bootstrap-only"))
        base.require(launch.docker("image", "inspect", args.scala_image, "--format", "{{.Id}}").stdout.strip() == args.scala_image,
                     "pinned Scala image")
        ids = dict(generatorSHA256=live.z.GENERATOR_SHA, launcherSHA256=base.sha(Path(__file__)),
                   imageId=live.z.REFERENCE_IMAGE, sourceCommit=support["sourceCommit"])
        result, receipt = live.controller.execute(args.owned_root, ids, launch.generate, launch.lifecycle, on_abort=launch.cleanup)
        base.write(launch.out / "controller-lifecycle.json", receipt)
        base.require(launch.cleaned and watchdog.failure is None and time.monotonic()-started <= 270, "bounded finalized topology")
        base.write(launch.out / "result.json", dict(schema="plutus-two-service-controller-result-v1", passed=True,
                   cleanupVerified=True, elapsedSeconds=time.monotonic()-started, profileId=fixture.PROFILE,
                   exchangeRoot=str(launch.exchange), initialPoint=launch.initial, finalPoints=result,
                   serviceCount=2, independentIngressOwners=True, fullLedgerValidated=False, restartSupported=False,
                   clientResultSHA256=base.sha(launch.out / "client-result.json"),
                   serviceResultSHA256=[base.sha(launch.out / (p+"-result.json")) for p in SERVICE_PHASES],
                   endpointComparisonSHA256=[base.sha(launch.out / (p+"-endpoint-comparison.json")) for p in SERVICE_PHASES],
                   evaluationReceiptSHA256=[base.sha(launch.out / (p+"-evaluation-receipt.json")) for p in SERVICE_PHASES],
                   transactionIds=[t["transactionId"] for t in launch.transfers]))
    except BaseException as error:
        if not (launch.out / "failure.json").exists():
            base.write(launch.out / "failure.json", dict(errorType=type(error).__name__, message=str(error)[:4096]))
        raise
    finally:
        try:
            launch.cleanup()
        finally:
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
    parser.add_argument("--duration-seconds", type=int, default=30)
    parser.add_argument("--max-blocks", type=int, default=128)
    parser.add_argument("--topology", choices=("two-services",), required=True)
    parser.add_argument("--execute", action="store_true")
    args = parser.parse_args()
    single.service_limits(args.duration_seconds, args.max_blocks)
    base.require(args.duration_seconds >= 3, "two-ingress client minimum duration")
    budget()
    live, support, classpath = base.preflight(args)
    base.require(classpath == single.checked_classpath(args.scala_classpath_file, args.scala_build_root, True), "Compile-only runtimes")
    single.checked_classpath(args.client_classpath_file, args.scala_build_root)
    if args.execute:
        execute(live, support, args, classpath)
    else:
        print(base.json.dumps(dict(preflight=True, executed=False, topology="two-services", resourcesNanoCpuAndBytes=RESOURCES,
                                   profileId=fixture.PROFILE, testedSpendCliSubmissionAllowed=False), sort_keys=True))


if __name__ == "__main__":
    main()
