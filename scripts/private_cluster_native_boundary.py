#!/usr/bin/env python3
"""Bounded private live boundary controller. Preflight only unless --execute.

Uses a source-pinned external fixture/controller package; no native build, remote
network, publication, existing-root reuse, or shared cache writes. Evidence and
keys are private external files. This proves only the restricted diagnostic path.
"""
import argparse
import datetime
import hashlib
import importlib
import json
import os
from pathlib import Path
import re
import shutil
import signal
import stat
import sys
import tempfile
import time

GIB = 1024 ** 3
RESOURCES = {"reference": (2, 3 * GIB), "scala": (1, 2 * GIB), "helper": (1, 2 * GIB)}
PROJECTION_SHA = "1ec0731a8bdb4c02e6e8254e13d75e78fa0bc1b2684fa4ef35837f9a7eb92216"
PACKET_NAMES = ("request.json", "capture.json", "native-verification.json", "receipt.json",
                "original-debug-epoch.cbor", "original-whole-utxo.cbor",
                "derived-full-epoch-seed.cbor", "original-debug-protocol.cbor")


def require(value, why):
    if not value:
        raise ValueError(why)


def read(path, limit=4 * 1024 * 1024):
    path = Path(path)
    info = path.lstat()
    require(stat.S_ISREG(info.st_mode) and info.st_size <= limit, "bounded regular file: " + str(path))
    with path.open("rb") as stream:
        raw = stream.read(limit + 1)
    require(len(raw) <= limit, "file grew past bound")
    return raw


def sha(path):
    return hashlib.sha256(read(path, 256 * 1024 * 1024)).hexdigest()


def decode(raw):
    def pairs(items):
        result = {}
        for key, value in items:
            require(key not in result, "duplicate JSON key")
            result[key] = value
        return result
    def bad(_):
        raise ValueError("nonfinite JSON")
    return json.loads(raw, object_pairs_hook=pairs, parse_constant=bad)


def write(path, value):
    """Publish readiness only after complete bytes have been fsynced."""
    path = Path(path)
    require(not path.exists(), "output already exists: " + str(path))
    pending = path.with_name(path.name + ".pending")
    with pending.open("x") as stream:
        json.dump(value, stream, sort_keys=True, indent=2)
        stream.write("\n")
        stream.flush()
        os.fsync(stream.fileno())
    pending.rename(path)


def hex64(value):
    return isinstance(value, str) and re.fullmatch("[0-9a-f]{64}", value) is not None


def point(value):
    require(isinstance(value, dict) and set(value) == {"slot", "blockNo", "hash"}, "exact full point")
    require(all(type(value[k]) is int and 0 < value[k] < 2 ** 64 for k in ("slot", "blockNo")), "point integer bounds")
    require(hex64(value["hash"]), "point hash")
    return value


def budget(resources=RESOURCES):
    require(set(resources) == {"reference", "scala", "helper"}, "exact roles")
    require(all(type(c) is int and c > 0 and type(m) is int and m > 0 for c, m in resources.values()), "positive resource limits")
    require(sum(c for c, _ in resources.values()) <= 4 and sum(m for _, m in resources.values()) <= 7 * GIB, "combined resource budget")


def check_resources(obj, role, image, network):
    budget()
    cpus, memory = RESOURCES[role]
    h = obj["HostConfig"]
    require(obj["Image"] == image and h["NetworkMode"] == network, "actual image/network")
    require(h["NanoCpus"] == cpus * 10 ** 9 and h["Memory"] == memory and h["MemorySwap"] == memory,
            "actual resource limits")
    require(h["PidsLimit"] == 256 and h["ReadonlyRootfs"] is True, "actual process/root limits")
    require(obj["Config"]["User"] == "1000:1000" and "ALL" in h["CapDrop"] and
            any(v.startswith("no-new-privileges") for v in h["SecurityOpt"]), "actual privileges")


def endpoint_request(value, initial, join_id, boundary_ms):
    require(value.get("schema") == "native-live-endpoint-request-v1", "endpoint request schema")
    terminal = point(value["point"])
    require(value.get("epoch") == 1 and 1000 <= terminal["slot"] < 1300, "endpoint must precede next reward window")
    require(0 < terminal["blockNo"] - initial["blockNo"] <= 128, "bounded successor count")
    require(value.get("sourceJoinId") == join_id and hex64(value.get("networkAppliedStateId")), "endpoint owner bindings")
    require(all(value.get(k) is True for k in ("followedAcrossBoundary", "peerClosed", "transportClosed")), "network resource finalization")
    start, end = value.get("streamStartedUnixMillis"), value.get("streamEndedUnixMillis")
    require(type(start) is int and type(end) is int and start < boundary_ms <= end and 0 <= end - start <= 120000,
            "stream must span live boundary within deadline")
    return terminal


def future_boundary(genesis):
    require(genesis["epochLength"] == 1000 and genesis["slotLength"] == 0.1 and
            genesis["securityParam"] == 5 and genesis["activeSlotsCoeff"] == 0.05, "proven profile timing")
    start = datetime.datetime.fromisoformat(genesis["systemStart"].replace("Z", "+00:00"))
    require(start.tzinfo is not None, "genesis timezone")
    return round(start.timestamp() * 1000) + 100000


def manifest(root, names):
    return {name: {"sha256": sha(root / name), "bytes": (root / name).stat().st_size} for name in names}


def preflight(args):
    require(sha(args.support_manifest) == args.support_sha and hex64(args.support_sha), "support manifest pin")
    support = decode(read(args.support_manifest))
    repo = Path(support["repository"])
    require(repo.is_absolute() and repo.resolve() == repo, "canonical support repository")
    # Verify source before executing its imports. The support validator checks the
    # complete import closure, image/binary/runtime/test provenance and git commit.
    for name, pin in support["sourceSHA256"].items():
        relative = Path(name)
        require(not relative.is_absolute() and ".." not in relative.parts, "relative support source")
        require(sha(repo / relative) == pin, "support source changed: " + name)
    sys.path[:0] = [str(repo / "scripts"), str(repo / "reference/epoch-query-client")]
    live = importlib.import_module("zero_live_launcher")
    require(Path(live.__file__).resolve() == repo / "scripts/zero_live_launcher.py", "support import identity")
    support = dict(support, ownedRoot=str(args.owned_root), publicRoot=str(args.evidence_root))
    with tempfile.TemporaryDirectory(prefix="cardano-boundary-preflight-") as directory:
        path = Path(directory) / "manifest.json"
        write(path, support)
        live.validate_manifest(path, sha(path))
    require(args.owned_root != args.evidence_root and args.owned_root not in args.evidence_root.parents and
            args.evidence_root not in args.owned_root.parents, "separate fresh roots")
    for path in (args.scala_build_root, args.projection):
        require(path.is_absolute() and path.resolve() == path and "," not in str(path), "canonical mount")
    require(args.scala_build_root.is_dir() and sha(args.projection) == PROJECTION_SHA, "pinned projection/build root")
    require(re.fullmatch(r"sha256:[0-9a-f]{64}", args.scala_image) is not None, "pinned Scala image")
    classpath = read(args.scala_classpath_file, 65536).decode().strip()
    components = classpath.split(":")
    require(components and all(v.startswith("/work/") and ".." not in Path(v).parts and
            (args.scala_build_root / v[len("/work/"):]).exists() for v in components), "classpath must resolve under read-only build")
    require(all("\n" not in v and "\r" not in v for v in components), "single classpath line")
    budget()
    return live, support, classpath


def controller_type(live):
    class BoundaryController(live.Launcher):
        def __init__(self, support, args, classpath):
            super().__init__(support)
            self.args, self.classpath = args, classpath
            self.exchange = args.owned_root / "exchange"
            self.boundary_ms = None
            self.initial = None

        def create(self, phase, tail):
            tail = list(tail)
            if phase == "reference":
                tail = ["--memory=3g" if x == "--memory=2g" else
                        "--memory-swap=3g" if x == "--memory-swap=2g" else x for x in tail]
            return super().create(phase, tail)

        def resource_check(self, obj, image):
            role = "reference" if image == live.z.REFERENCE_IMAGE else "helper"
            check_resources(obj, role, image, "none")

        def cleanup(self):
            if self.cleaned:
                return
            # Remove the namespace consumer before its reference namespace owner.
            # All mutation uses the independently checked immutable ID and token.
            self.in_cleanup = True
            if self.cleanup_deadline is None:
                self.cleanup_deadline = time.monotonic() + 30
            self.deadline = self.cleanup_deadline
            signal.alarm(max(1, int(self.deadline - time.monotonic())))
            found = self.docker("ps", "-aq", "--no-trunc", "--filter", "label=" + live.LABEL + "=" + self.token,
                                "--filter", "label=lab.zero-live.phase=scala").stdout.split()
            require(len(found) <= 1, "ambiguous Scala cleanup ownership")
            cid = self.containers.get("scala")
            if cid is None and found:
                cid = found[0]
            require(not found or found == [cid], "Scala immutable identity differs")
            if cid:
                ids = self.docker("ps", "-aq", "--no-trunc", "--filter", "id=" + cid).stdout.split()
                require(ids in ([], [cid]), "Scala cleanup identity")
                if ids:
                    self.owned(cid)
                    try:
                        logs = self.docker("logs", "--tail", "1000", cid, check=False, timeout=3)
                        (self.out / "scala.log").write_text(logs.stdout + logs.stderr)
                    except BaseException as error:
                        self.diagnostic_write("scala-log-collection-error.json", dict(errorType=type(error).__name__))
                    self.docker("rm", "--force", cid)
                    require(not self.docker("ps", "-aq", "--no-trunc", "--filter", "id=" + cid).stdout.strip(), "Scala absence")
                if not (self.out / "scala-cleanup.json").exists():
                    write(self.out / "scala-cleanup.json", dict(containerId=cid, absenceVerified=True,
                          removedBeforeNamespaceOwner=True))
            super().cleanup()

        def wait_file(self, name, seconds):
            until = min(self.deadline, time.monotonic() + seconds)
            path = self.exchange / name
            while time.monotonic() < until:
                if (self.exchange / "failure.json").exists():
                    raise ValueError("Scala failure: " + read(self.exchange / "failure.json", 65536).decode())
                if path.exists():
                    return decode(read(path, 65536))
                if "scala" in self.containers:
                    state = self.owned(self.containers["scala"])["State"]
                    require(state["Running"] is True, "Scala exited before " + name)
                time.sleep(0.1)
            raise TimeoutError("bounded readiness: " + name)

        def capture(self, target, phase):
            target = point(target)
            byron = decode(read(self.environment / "byron-genesis.json"))
            req = dict(schema=2, socket="/capture/node.sock", point={k: target[k] for k in ("slot", "hash")},
                       networkMagic=self.magic, byronEpochSlots=10 * byron["protocolConsts"]["k"], ntcVersion=16,
                       producerBinarySHA256=live.z.NODE_SHA, producerImage=live.z.REFERENCE_IMAGE)
            request_path = self.out / (phase + "-request.json")
            write(request_path, req)
            socket = self.environment / "socket/node1/sock"
            info = socket.stat()
            require(stat.S_ISSOCK(info.st_mode) and socket.resolve() == socket, "owned socket")
            opts = dict(endpoint="unix:///var/run/docker.sock", image=live.z.HELPER_IMAGE,
                        socket=str(socket), approved_socket=str(socket), query=self.m["helpers"]["query"]["path"],
                        query_sha=live.z.QUERY_SHA, verifier=self.m["helpers"]["verify"]["path"],
                        verifier_sha=live.z.VERIFY_SHA, request_file=str(request_path),
                        output_root=str(self.exchange / (phase + "-capture")), frozen_root=self.m["runtimeRoot"],
                        name="epoch-query-" + live.secrets.token_hex(16))
            cmd = live.container_run.command(**opts)
            cmd = ["--cpus=1" if x == "--cpus=2" else x for x in cmd]
            self.capture_mounts = set()
            for index, value in enumerate(cmd):
                if value == "--mount":
                    spec = cmd[index + 1].split(",")
                    kv = dict(v.split("=", 1) for v in spec if "=" in v)
                    self.capture_mounts.add((kv["src"], kv["dst"], "readonly" not in spec))
            require(time.monotonic() + 57 < self.deadline, "capture+cleanup must fit operation deadline")
            started = time.time_ns() // 1000000
            live.container_run.run(cmd, opts["output_root"], caller=self.observed_capture)
            packet = Path(opts["output_root"]) / "packet"
            packet_v2 = importlib.import_module("packet_v2")
            old_packet = importlib.import_module("packet")
            cap = decode(read(packet / "capture.json"))
            epoch, utxo, protocol = packet_v2.check_capture(req, cap)
            require(cap["blockNo"] == target["blockNo"], "requested full block identity")
            derived = old_packet.check_verification(epoch, utxo, decode(read(packet / "native-verification.json")))
            for name, raw in (("original-debug-epoch.cbor", epoch), ("original-whole-utxo.cbor", utxo),
                              ("original-debug-protocol.cbor", protocol), ("derived-full-epoch-seed.cbor", derived)):
                require(read(packet / name) == raw, "packet original differs")
            receipt = decode(read(packet / "receipt.json"))
            require(receipt["queryHelperSHA256"] == live.z.QUERY_SHA and receipt["verifierSHA256"] == live.z.VERIFY_SHA, "helper receipt")
            for field, name in (("requestSHA256", "request.json"), ("captureSHA256", "capture.json"),
                                ("epochSHA256", "original-debug-epoch.cbor"), ("utxoSHA256", "original-whole-utxo.cbor"),
                                ("protocolSHA256", "original-debug-protocol.cbor"), ("derivedSeedSHA256", "derived-full-epoch-seed.cbor")):
                require(receipt[field] == sha(packet / name), "receipt original hash")
            require((socket.stat().st_dev, socket.stat().st_ino) == (info.st_dev, info.st_ino), "socket replaced")
            result = dict(schema="native-live-acquisition-result-v1", point=target,
                          startedUnixMillis=started, endedUnixMillis=time.time_ns() // 1000000,
                          captureContainerId=self.capture_observed, referenceContainerId=self.containers["reference"],
                          packetSHA256={name: sha(packet / name) for name in PACKET_NAMES},
                          exactAcquiredPoint=True, acquireCount=1, reacquireCount=0,
                          protocolSemanticsVerified=False, runtimeImport=False)
            return packet, result

        def project(self, initial):
            supply = self.genesis["maxLovelaceSupply"]
            require(type(supply) is int and 0 < supply < 2 ** 64, "genesis supply")
            args = ["--pull=never", "--network=none", "--cpus=1", "--memory=2g", "--memory-swap=2g",
                    "--pids-limit=256", "--read-only", "--cap-drop=ALL", "--security-opt=no-new-privileges",
                    "--user", "1000:1000", "--tmpfs", "/runwork:rw,exec,nosuid,nodev,size=128m,uid=1000,gid=1000,mode=0700",
                    "--tmpfs", "/tmp:rw,nosuid,nodev,noexec,size=16m",
                    "--mount", "type=bind,src=" + str(self.args.projection) + ",dst=/input/helper,readonly",
                    "--mount", "type=bind,src=" + str(initial / "derived-full-epoch-seed.cbor") + ",dst=/input/seed.cbor,readonly",
                    "--mount", "type=bind,src=" + self.m["runtimeRoot"] + ",dst=/work,readonly",
                    "-e", "LD_LIBRARY_PATH=/work/tools/native/lib", "--entrypoint", "/bin/sh", live.z.HELPER_IMAGE,
                    "-c", "set -eu\ncp /input/helper /runwork/helper\nchmod 500 /runwork/helper\nexec /runwork/helper /input/seed.cbor " + str(supply) + " +RTS -M768m -K16m -N1 -RTS"]
            cid = self.create("projection", args)
            observed = self.owned(cid)
            self.resource_check(observed, live.z.HELPER_IMAGE)
            expected = {(str(self.args.projection), "/input/helper", False),
                        (str(initial / "derived-full-epoch-seed.cbor"), "/input/seed.cbor", False),
                        (self.m["runtimeRoot"], "/work", False)}
            require({(m["Source"], m["Destination"], m["RW"]) for m in observed["Mounts"] if m["Type"] == "bind"} == expected,
                    "actual projection mounts")
            try:
                result = self.docker("start", "--attach", cid, timeout=20)
                state = self.owned(cid)["State"]
                require(not state["Running"] and state["ExitCode"] == 0 and not state["OOMKilled"], "projection exit")
                raw = result.stdout.encode()
                require(len(raw) <= 524288, "projection bytes")
                decode(raw)
                with (initial / "native-projection.json").open("xb") as stream:
                    stream.write(raw)
            finally:
                self.owned(cid)
                self.docker("rm", "--force", cid)
                require(not self.docker("ps", "-aq", "--no-trunc", "--filter", "id=" + cid).stdout.strip(), "projection absence")

        def start_scala(self, manifest_pin):
            network = "container:" + self.containers["reference"]
            args = ["--pull=never", "--network=" + network, "--cpus=1", "--memory=2g", "--memory-swap=2g",
                    "--pids-limit=256", "--read-only", "--cap-drop=ALL", "--security-opt=no-new-privileges",
                    "--user", "1000:1000", "--tmpfs", "/tmp:rw,nosuid,nodev,size=128m",
                    "--mount", "type=bind,src=" + str(self.args.scala_build_root) + ",dst=/work,readonly",
                    "--mount", "type=bind,src=" + str(self.exchange) + ",dst=/exchange",
                    "--entrypoint", self.args.java, self.args.scala_image, "-Xmx1400m", "-XX:ActiveProcessorCount=1",
                    "-cp", self.classpath, "lab.NativeLiveBoundaryMain", "/exchange/initial", manifest_pin,
                    str(live.process.PORTS[1]), str(self.magic), "/exchange"]
            cid = self.create("scala", args)
            observed = self.owned(cid)
            check_resources(observed, "scala", self.args.scala_image, network)
            require({(m["Source"], m["Destination"], m["RW"]) for m in observed["Mounts"] if m["Type"] == "bind"} ==
                    {(str(self.args.scala_build_root), "/work", False), (str(self.exchange), "/exchange", True)}, "actual Scala mounts")
            write(self.out / "scala-inspection.json", dict(containerId=cid, image=observed["Image"],
                  network=observed["HostConfig"]["NetworkMode"], mounts=observed["Mounts"],
                  cpus=1, memoryBytes=2 * GIB, readOnlyRootfs=True))
            self.docker("start", cid)
            return cid

        def lifecycle(self, approval):
            self.exchange.mkdir(mode=0o700)
            shutil.copytree(approval.evidence, self.out / "fixture")
            self.genesis = decode(read(self.environment / "shelley-genesis.json"))
            self.boundary_ms = future_boundary(self.genesis)
            self.configuration = self.read("configuration.yaml")
            self.magic = self.genesis["networkMagic"]
            for node in (1, 2):
                port = self.read("node-data/node" + str(node) + "/port").strip()
                require(port.isdigit() and 1 <= int(port) <= 65535, "generated private port")
                live.process.PORTS[node] = int(port)
                self.topologies[node] = decode(self.read("node-data/node" + str(node) + "/topology.json"))
            require(live.process.PORTS[1] != live.process.PORTS[2], "distinct ports")
            self.start_node(1, True)
            self.start_node(2, False)
            until = min(self.deadline - 150, time.monotonic() + 90)
            while time.monotonic() < until:
                a, b = self.tip(1), self.tip(2)
                if live.process.same_tip(a, b) and a.get("era") == "Conway" and a.get("epoch") == 0 and 0 < a.get("slot", 0) < 300 and a.get("block", 0) > 0:
                    break
                time.sleep(0.2)
            else:
                raise TimeoutError("fresh common early epoch-zero point")
            self.stop_node(1)
            self.stop_node(2)
            self.start_node(1, False)
            self.start_node(2, False)
            # Clean stop can leave the non-forging peer one block behind.
            # Both restarted nodes are keyless; allow bounded synchronization.
            until = min(self.deadline - 150, time.monotonic() + 10)
            while time.monotonic() < until:
                a, b = self.tip(1), self.tip(2)
                if live.process.same_tip(a, b):
                    break
                time.sleep(0.2)
            require(live.process.same_tip(a, b) and a["epoch"] == 0 and 0 < a["slot"] < 300, "frozen initial point")
            self.initial = point(dict(slot=a["slot"], blockNo=a["block"], hash=a["hash"]))
            packet, acquisition = self.capture(self.initial, "initial")
            require(live.process.same_tip(a, self.tip(1)) and live.process.same_tip(a, self.tip(2)), "initial bracket moved")
            write(self.out / "initial-acquisition-result.json", acquisition)
            initial = self.exchange / "initial"
            shutil.copytree(packet, initial)
            shutil.copyfile(self.environment / "shelley-genesis.json", initial / "effective-shelley-genesis.json")
            self.project(initial)
            descriptor = dict(schema="native-ledger-v2-reviewed-inputs-v1", point=self.initial,
                              inputs=manifest(initial, PACKET_NAMES + ("native-projection.json", "effective-shelley-genesis.json")))
            write(initial / "adapter-inputs.json", descriptor)
            self.start_scala(sha(initial / "adapter-inputs.json"))
            ready = self.wait_file("bootstrap-ready.json", 25)
            require(ready.get("schema") == "native-live-bootstrap-ready-v1" and point(ready["point"]) == self.initial,
                    "bootstrap full point")
            require(hex64(ready.get("sourceJoinId")) and ready.get("boundaryUnixMillis") == self.boundary_ms and
                    ready.get("networkMagic") == self.magic, "bootstrap geometry/bindings")
            self.stop_node(1)
            self.stop_node(2)
            self.stopped.clear()
            require(time.time_ns() // 1000000 < self.boundary_ms - 5000, "producer restart needs preboundary time")
            # Keep one active producer after bootstrap so this explicitly
            # monotonic diagnostic does not claim fork/rollback support.
            self.start_node(1, True)
            self.start_node(2, False)
            resumed = time.time_ns() // 1000000
            require(resumed < self.boundary_ms, "producer restart missed epoch boundary")
            write(self.exchange / "peer-ready.json", dict(schema="native-live-peer-ready-v1",
                  referenceContainerId=self.containers["reference"], generatedPort=live.process.PORTS[1],
                  networkMagic=self.magic, producerResumedUnixMillis=resumed))
            request = self.wait_file("endpoint-request.json", 125)
            terminal = endpoint_request(request, self.initial, ready["sourceJoinId"], self.boundary_ms)
            # Acquire the exact requested historical state immediately from the
            # running node. Never restart/fallback to latest on AcquireFailure.
            packet, acquisition = self.capture(terminal, "endpoint")
            write(self.exchange / "acquisition-result.json", acquisition)
            endpoint = self.exchange / "endpoint"
            shutil.copytree(packet, endpoint)
            result_pin = sha(self.exchange / "acquisition-result.json")
            descriptor = dict(schema="native-endpoint-reviewed-inputs-v1", point=terminal,
                              genesisSHA256=sha(initial / "effective-shelley-genesis.json"),
                              acquisitionResultSHA256=result_pin, inputs=manifest(endpoint, PACKET_NAMES))
            write(endpoint / "endpoint-inputs.json", descriptor)
            write(self.exchange / "endpoint-ready.json", dict(schema="native-endpoint-ready-v1",
                  manifestSHA256=sha(endpoint / "endpoint-inputs.json"), acquisitionResultSHA256=result_pin))
            result = self.wait_file("result.json", 30)
            require(result.get("schema") == "native-live-boundary-result-v1" and result.get("passed") is True and
                    result.get("initialPoint") == self.initial and result.get("finalPoint") == terminal and
                    result.get("sourceJoinId") == ready["sourceJoinId"] and result.get("networkAppliedStateId") == request["networkAppliedStateId"], "final live result binding")
            require(all(result.get(k) is True for k in ("resourcesFinalized", "liveBoundaryFollow", "endpointCompared")), "final result claims")
            require(all(result.get(k) is False for k in ("fullLedgerValidated", "nativeConformance", "runtimeImport", "livePulserCursorEqual")), "restricted scope")
            cid = self.containers["scala"]
            until = min(self.deadline, time.monotonic() + 5)
            while time.monotonic() < until:
                state = self.owned(cid)["State"]
                if not state["Running"]:
                    break
                time.sleep(0.1)
            require(not state["Running"] and state["ExitCode"] == 0 and not state["OOMKilled"], "Scala process completion")
            write(self.out / "scala-result.json", result)
            self.stop_node(1)
            self.stop_node(2)
            self.cleanup()
            return result

    return BoundaryController


def execute(live, support, args, classpath):
    launch = controller_type(live)(support, args, classpath)
    watchdog = live.DiskWatchdog(args.owned_root)
    previous = {}
    def interrupted(signum, _frame):
        if launch.in_cleanup and signum != signal.SIGALRM:
            return
        raise TimeoutError("bounded live operation interrupted")
    started = time.monotonic()
    try:
        previous = {s: signal.signal(s, interrupted) for s in (signal.SIGALRM, signal.SIGTERM, signal.SIGINT, signal.SIGUSR1)}
        signal.alarm(240)
        watchdog.start()
        write(launch.out / "invocation.json", dict(schema="native-live-controller-invocation-v1",
              supportManifestSHA256=args.support_sha, controllerSHA256=sha(Path(__file__)), resources=RESOURCES,
              projectionSHA256=PROJECTION_SHA, scalaImage=args.scala_image,
              scalaClasspathSHA256=sha(args.scala_classpath_file), scalaBuildRoot=str(args.scala_build_root),
              operationSeconds=240, cleanupSeconds=30, forgingBeforeAndAfterBootstrap=[1], nonForgingPeers=[2], network="reference-none;scala-exact-reference-namespace;helper-none"))
        require(launch.docker("image", "inspect", args.scala_image, "--format", "{{.Id}}").stdout.strip() == args.scala_image, "Scala image pin")
        ids = dict(generatorSHA256=live.z.GENERATOR_SHA, launcherSHA256=sha(Path(__file__)),
                   imageId=live.z.REFERENCE_IMAGE, sourceCommit=support["sourceCommit"])
        result, receipt = live.controller.execute(args.owned_root, ids, launch.generate, launch.lifecycle, on_abort=launch.cleanup)
        write(launch.out / "controller-lifecycle.json", receipt)
        require(launch.cleaned and watchdog.failure is None, "owned cleanup/watchdog")
        require(time.monotonic() - started <= 270, "total deadline")
        write(launch.out / "result.json", dict(schema="native-live-controller-result-v1", passed=True,
              exchangeRoot=str(launch.exchange), cleanupVerified=True, elapsedSeconds=time.monotonic() - started,
              initialPoint=result["initialPoint"], finalPoint=result["finalPoint"],
              scalaResultSHA256=sha(launch.out / "scala-result.json")))
    except BaseException as error:
        if not (launch.out / "failure.json").exists():
            write(launch.out / "failure.json", dict(errorType=type(error).__name__, message=str(error)[:4096]))
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
    for name in ("support-manifest", "owned-root", "evidence-root", "scala-build-root", "scala-classpath-file", "projection"):
        parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument("--support-sha", required=True)
    parser.add_argument("--scala-image", required=True)
    parser.add_argument("--java", default="java")
    parser.add_argument("--execute", action="store_true")
    args = parser.parse_args()
    live, support, classpath = preflight(args)
    if args.execute:
        execute(live, support, args, classpath)
    else:
        print(json.dumps(dict(preflight=True, executed=False, resources=RESOURCES, projectionSHA256=PROJECTION_SHA), sort_keys=True))


if __name__ == "__main__":
    main()
