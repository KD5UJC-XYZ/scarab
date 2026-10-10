#!/usr/bin/env python3
"""Reusable owned-controller early restart stage. Importing never starts processes.

The caller owns bootstrap, producer lifecycle, deadlines and final cleanup. This
stage leaves the restored service running; later epoch support is not implied.
No tested transaction is submitted here and no inclusion-before-restart is claimed.
"""
from dataclasses import dataclass
from pathlib import Path
import hashlib
import json
import os
import struct
import stat
import time
import private_cluster_plutus_two_service as two

base = two.base
MAX_IMAGE = 16 * 1024 * 1024
SCOPE = "linear-epoch-zero-max8"


def digest(raw):
    return hashlib.sha256(raw).hexdigest()


def read_exact(path, maximum):
    path = Path(path)
    base.require(path.is_absolute() and path.resolve() == path, "canonical nonsymlink input")
    raw = base.read(path, maximum)
    base.require(0 < len(raw) <= maximum, "nonempty bounded input")
    return raw


def publish_new(path, raw):
    """One CREATE_NEW file; no pair atomicity or crash durability claim."""
    path = Path(path)
    base.require(path.parent.resolve() == path.parent and 0 < len(raw) <= MAX_IMAGE,
                 "owned canonical bounded output")
    pending = path.with_name(path.name + ".pending")
    fd = os.open(pending, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    try:
        with os.fdopen(fd, "wb") as stream:
            stream.write(raw)
        os.link(pending, path, follow_symlinks=False)
    finally:
        pending.unlink()


def encoded(value):
    return (json.dumps(value, sort_keys=True, separators=(",", ":")) + "\n").encode()


def envelope_claim(raw):
    """Bound wire envelope only; semantic ledger/replay authority remains Scala's."""
    base.require(isinstance(raw, bytes) and 0 < len(raw) <= MAX_IMAGE, "checkpoint bound")
    offset = 0
    def take(n):
        nonlocal offset
        base.require(type(n) is int and 0 <= n <= len(raw)-offset, "checkpoint truncation")
        value = raw[offset:offset+n]
        offset += n
        return value
    def number():
        return int.from_bytes(take(8), "big")
    def integer():
        return struct.unpack(">i", take(4))[0]
    def hash_value():
        return take(32).hex()
    def point():
        p = dict(hash=hash_value(), slot=number(), blockNo=number())
        base.point(p)
        base.require(p["slot"] < 1000, "epoch-zero checkpoint point")
        return p
    def blob(maximum):
        size = integer()
        base.require(0 < size <= maximum, "bounded checkpoint blob")
        take(size)
    base.require(take(8) == b"RPLREST1", "reviewed checkpoint format")
    c = dict(storeId=hash_value(), sessionId=hash_value(), generation=number(), contextId=hash_value(),
             sourceJoinId=hash_value(), manifestSHA256=hash_value(), sourcePoint=point(), terminalPoint=point(),
             historicalStateId=hash_value(), historicalRevision=number(), capacity=integer(), originalCount=integer())
    base.require(c["generation"] < 2**63 and 1 <= c["capacity"] <= 8 and
                 0 <= c["originalCount"] <= c["capacity"] and c["historicalRevision"] == c["originalCount"] and
                 c["terminalPoint"]["blockNo"] == c["sourcePoint"]["blockNo"] + c["originalCount"] and
                 c["terminalPoint"]["slot"] >= c["sourcePoint"]["slot"], "linear checkpoint bounds")
    take(64)  # certificate and nonce identities are checked during semantic recovery
    tag = take(1)[0]
    base.require(tag in (0, 1) and (tag == 1 or c["originalCount"] == 0), "eligibility tag")
    if tag:
        take(32)
    blob(3*1024*1024)
    blob(3*1024*1024)
    for _ in range(c["originalCount"]):
        blob(65536)
        blob(1048576)
    base.require(offset == len(raw), "no trailing checkpoint bytes")
    c["publicationSHA256"] = digest(raw)
    expected_context = digest(("native-sequence-diagnostic-context-v1\n" + c["sourceJoinId"] + "\n").encode())
    base.require(c["contextId"] == expected_context, "source-bound native context")
    return c


def startup(ready, source, manifest, initial, restored, old_owner=None, old_checkpoint=None):
    base.require(base.hex64(source) and base.hex64(manifest), "independent source hashes")
    base.point(initial)
    base.require(ready.get("schema") == "plutus-service-ready-v1" and ready.get("profileId") == two.fixture.PROFILE and
                 ready.get("sourceJoinId") == source and ready.get("initialManifestSHA256") == manifest and
                 ready.get("initialPoint") == initial and ready.get("initialEpoch") == 0,
                 "independent startup source and full point")
    meta = ready.get("boundedRestart", {})
    base.require(meta.get("scope") == SCOPE and meta.get("startupRestored") is restored and
                 meta.get("pendingAdmissionRestored") is False and meta.get("crashDurable") is False and
                 base.hex64(meta.get("freshCheckpointId")), "scoped restart readiness")
    pool = meta.get("startupAdmission", {})
    state_pin = two.pin(pool.get("pin"))
    base.require(state_pin["point"] == initial and all(type(pool.get(k)) is int and pool[k] == 0
                 for k in ("size", "byteSize", "eligibleCount")) and pool.get("rebuilding") is False and
                 pool.get("closed") is False, "genuine new empty admission pool")
    base.require(old_owner is None or state_pin["ownerId"] != old_owner, "fresh restored owner")
    base.require(old_checkpoint is None or meta["freshCheckpointId"] != old_checkpoint, "fresh ledger checkpoint")
    return state_pin


def authorize_pair(root, ready, result, *, store_id, session_id, generation, source_point,
                   manifest, acquired_point, checkpoint_after):
    """Current controller decision after owned clean stop and independent exact-point capture.

    Caller must enforce lifecycle/anti-rollback policy before each invocation. The
    returned bytes are not written or accepted automatically by this pure check.
    """
    base.require(all(base.hex64(x) for x in (store_id, session_id, manifest)) and
                 type(generation) is int and 0 <= generation < 2**63 and
                 type(checkpoint_after) is int and 1 <= checkpoint_after <= 8, "configured authority bounds")
    initial_pin = startup(ready, ready.get("sourceJoinId"), manifest, source_point, False)
    raw = read_exact(root / "checkpoint.bin", MAX_IMAGE)
    request_raw = read_exact(root / "checkpoint-request.json", 65536)
    request = base.decode(request_raw)
    base.require(set(request) == {"schema", "checkpointFile", "checkpointBytes", "claim", "restoreAuthorized",
                                 "crashDurable", "scope"} and request["schema"] == "plutus-service-checkpoint-publication-v1" and
                 request["checkpointFile"] == "checkpoint.bin" and type(request["checkpointBytes"]) is int and
                 request["checkpointBytes"] == len(raw) and request["restoreAuthorized"] is False and
                 request["crashDurable"] is False and request["scope"] == SCOPE, "complete scoped checkpoint pair")
    claim = envelope_claim(raw)
    base.require(encoded(request["claim"]) == encoded(claim), "request equals independently decoded binary claim")
    final_pin = two.pin(result.get("finalPin"), initial_pin["ownerId"])
    meta = result.get("boundedRestart", {})
    base.require(result.get("schema") == "plutus-service-result-v1" and result.get("status") == "stopped" and
                 result.get("stopReason") == "blockLimit" and result.get("resourcesFinalized") is True and
                 result.get("sourceJoinId") == ready["sourceJoinId"] and result.get("initialManifestSHA256") == manifest and
                 result.get("fullLedgerValidated") is False and result.get("transactionSuccessClaimed") is False and
                 type(result.get("transportOpens")) is int and result["transportOpens"] > 0 and
                 type(result.get("transportCloses")) is int and
                 result["transportOpens"] == result.get("transportCloses"), "finalized successful checkpoint stop")
    base.require(claim["storeId"] == store_id and claim["sessionId"] == session_id and claim["generation"] == generation and
                 claim["sourceJoinId"] == ready["sourceJoinId"] and claim["manifestSHA256"] == manifest and
                 claim["sourcePoint"] == source_point and claim["terminalPoint"] == base.point(acquired_point) == final_pin["point"] and
                 claim["historicalStateId"] == final_pin["coherentStateId"] and claim["originalCount"] == checkpoint_after,
                 "external source/store/session/generation/full-point decision")
    base.require(meta.get("checkpointFile") == "checkpoint.bin" and meta.get("checkpointSHA256") == digest(raw) and
                 meta.get("checkpointRequestSHA256") == digest(request_raw) and type(meta.get("checkpointAfter")) is int and
                 meta.get("checkpointAfter") == checkpoint_after,
                 "final result binds exact two publication files")
    decision = dict(schema="plutus-service-restore-authority-v1", decision="accept", claim=claim)
    return raw, encoded(decision), claim


@dataclass(frozen=True)
class RunningRestored:
    phase: str
    ready: dict
    claim: dict
    authority_sha256: str
    prior_owner: str
    prior_checkpoint: str


def library_manifest(directory):
    directory = Path(directory)
    base.require(directory.is_absolute() and directory.resolve() == directory and directory.is_dir(), "canonical native library directory")
    rows = []
    total = 0
    # Count every directory entry before collecting/sorting; do not eagerly
    # materialize an unbounded recursive tree, including empty directories.
    entries = []
    pending = [directory]
    while pending:
        parent = pending.pop()
        with os.scandir(parent) as listing:
            for item in listing:
                base.require(len(entries) < 256, "bounded native library traversal")
                path = Path(item.path)
                base.require(len(path.relative_to(directory).as_posix()) <= 256, "bounded native library path")
                entries.append(path)
                if item.is_dir(follow_symlinks=False):
                    pending.append(path)
    for path in sorted(entries):
        relative = path.relative_to(directory).as_posix()
        base.require(len(rows) < 128 and len(relative) <= 256, "bounded native library domain")
        info = path.lstat()
        if stat.S_ISDIR(info.st_mode):
            continue
        if stat.S_ISLNK(info.st_mode):
            target = os.readlink(path)
            resolved = path.resolve(strict=True)
            base.require(not Path(target).is_absolute() and resolved.is_relative_to(directory) and resolved.is_file(), "contained native library link")
            rows.append(dict(path=relative, link=target))
        else:
            base.require(stat.S_ISREG(info.st_mode), "regular native library member")
            raw = base.read(path, 32*1024*1024)
            total += len(raw)
            base.require(total <= 128*1024*1024, "native library aggregate bound")
            rows.append(dict(path=relative, bytes=len(raw), sha256=digest(raw)))
    base.require(rows, "nonempty native library set")
    return dict(schema="plutus-native-library-set-v1", files=rows)


@dataclass(frozen=True)
class NativeRuntimeMounts:
    executable: Path
    executable_sha256: str
    libraries: Path
    libraries_sha256: str

    def checked_mounts(self):
        raw = read_exact(self.executable,256*1024*1024)
        base.require(base.hex64(self.executable_sha256) and digest(raw) == self.executable_sha256 and
                     os.access(self.executable,os.X_OK), "pinned executable native runtime")
        manifest = library_manifest(self.libraries)
        base.require(base.hex64(self.libraries_sha256) and digest(encoded(manifest)) == self.libraries_sha256,
                     "independently pinned complete native library set")
        return {(str(self.executable),"/native/likelihood",False),(str(self.libraries),"/oracle-libs",False)}


def launch_service(controller, live, phase, root, manifest, extra, readonly=(), command_builder=None, native_runtime=None):
    """Uses inspected exact existing resource envelope; custom builder is trusted supervisor code."""
    root.mkdir(mode=0o700)
    initial = controller.exchange / "initial"
    builder = command_builder or two.service_args
    args = builder(controller.args, root, initial, controller.classpath, controller.containers["reference"],
                   live.process.PORTS[1], controller.magic, manifest)
    insertion = args.index("--entrypoint")
    mounts = {(str(controller.args.scala_build_root), "/work", False),
              (str(initial), "/initial", False), (str(root), "/exchange", True)}
    if native_runtime is not None:
        base.require(type(native_runtime) is NativeRuntimeMounts, "typed native runtime mount contract")
        mounts |= native_runtime.checked_mounts()
    for host, guest in readonly:
        base.require(host.resolve() == host and host.is_file() and guest.startswith("/restore/"), "exact restore file mount")
        args[insertion:insertion] = ["--mount", "type=bind,src="+str(host)+",dst="+guest+",readonly"]
        insertion += 2
        mounts.add((str(host), guest, False))
    args += extra
    cid = controller.create(phase, args)
    observed = controller.owned(cid)
    two.check_service(observed, phase, controller.args.scala_image, "container:"+controller.containers["reference"], mounts)
    if native_runtime is not None:
        base.require("LD_LIBRARY_PATH=/oracle-libs" in observed["Config"]["Env"], "exact native library search path")
    base.write(controller.out / (phase+"-restart-inspection.json"), observed)
    controller.docker("start", cid)


def wait_exit(controller, phase):
    until = min(controller.deadline, time.monotonic()+5)
    while time.monotonic() < until:
        state = controller.owned(controller.containers[phase])["State"]
        if not state["Running"]:
            base.require(state["ExitCode"] == 0 and not state["OOMKilled"], "checkpoint service clean exit")
            return
        time.sleep(.1)
    raise TimeoutError("checkpoint clean exit deadline")


def peer_ready(controller, live, phase, resumed):
    base.require(type(resumed) is int and 0 < resumed <= time.time_ns()//1000000,
                 "actual owned producer resume timestamp")
    base.write(controller.exchange / phase / "peer-ready.json", dict(schema="native-live-peer-ready-v1",
               referenceContainerId=controller.containers["reference"], generatedPort=live.process.PORTS[1],
               networkMagic=controller.magic, producerResumedUnixMillis=resumed))


def perform_early_restart(controller, live, manifest, *, store_id, session_id, generation,
                          resume_producer, freeze_producer, checkpoint_after=1, continuation_builder=None, continuation_native=None):
    """Live-ready stage on an already bootstrapped, owned, frozen mutable network.

    Required controller is the existing TwoServiceController; its deadline,
    owner token, cleanup and resource policy stay authoritative. Both service
    roles must be unused. Producer callbacks operate only its owned processes.
    The returned service remains running for the supervisor's normal workload.
    No duration/epoch limits are changed by this adapter. A future long runtime
    can supply a separately reviewed continuation command builder.
    """
    base.require(all(p not in controller.containers for p in two.SERVICE_PHASES), "fresh reserved restart roles")
    base.require(all(base.hex64(x) for x in (store_id, session_id, manifest)) and
                 type(generation) is int and 0 <= generation < 2**63 and
                 callable(resume_producer) and callable(freeze_producer), "explicit controller authority and lifecycle")
    base.require(base.sha(controller.exchange / "initial/adapter-inputs.json") == manifest,
                 "independent initial manifest pin")
    two.single.service_limits(controller.args.duration_seconds, controller.args.max_blocks)
    base.require(type(checkpoint_after) is int and 1 <= checkpoint_after <= min(8, controller.args.max_blocks), "early checkpoint publication budget")
    identity = ["--store-id", store_id, "--session-id", session_id, "--generation", str(generation)]
    first_root, second_root = (controller.exchange / p for p in two.SERVICE_PHASES)
    launch_service(controller, live, "service-1", first_root, manifest,
                   ["--checkpoint-after", str(checkpoint_after), *identity])
    first = controller.wait_service("service-1", "bootstrap-ready.json", 25)
    before = startup(first, first.get("sourceJoinId"), manifest, controller.initial, False)
    resumed = resume_producer()
    peer_ready(controller, live, "service-1", resumed)
    stopped = controller.wait_service("service-1", "result.json", controller.args.duration_seconds+10)
    wait_exit(controller, "service-1")
    freeze_producer()
    terminal = base.point(two.pin(stopped.get("finalPin"))["point"])
    packet, acquisition = controller.capture(terminal, "restart-checkpoint")
    base.require(acquisition.get("schema") == "native-live-acquisition-result-v1" and
                 type(acquisition.get("acquireCount")) is int and type(acquisition.get("reacquireCount")) is int and
                 acquisition.get("exactAcquiredPoint") is True and acquisition.get("acquireCount") == 1 and
                 acquisition.get("reacquireCount") == 0 and acquisition.get("referenceContainerId") == controller.containers["reference"] and
                 acquisition.get("point") == terminal, "independent exact checkpoint acquisition")
    base.write(controller.out / "restart-acquisition.json", acquisition)
    # capture's existing exact historical acquisition contract is required; this
    # is point authentication, not a new whole-state oracle comparison claim.
    raw, accepted, claim = authorize_pair(first_root, first, stopped, store_id=store_id,
        session_id=session_id, generation=generation, source_point=controller.initial,
        manifest=manifest, acquired_point=acquisition["point"], checkpoint_after=checkpoint_after)
    authority = controller.out / "restart-authority.json"
    publish_new(authority, accepted)
    authority_pin = digest(accepted)
    base.write(controller.out / "restart-accepted-pair.json", dict(claim=claim, requestSHA256=base.sha(first_root / "checkpoint-request.json"),
               authoritySHA256=authority_pin, acquisitionSHA256=base.sha(controller.out / "restart-acquisition.json"),
               semanticRecoveryPending=True, wholeStateOracleCompared=False, crashDurable=False))
    controller.remove_service("service-1")
    launch_service(controller, live, "service-2", second_root, manifest,
        ["--restore-checkpoint", "/restore/checkpoint.bin", "--restore-authority", "/restore/authority.json",
         "--restore-authority-sha256", authority_pin, *identity],
        readonly=((first_root / "checkpoint.bin", "/restore/checkpoint.bin"), (authority, "/restore/authority.json")),
        command_builder=continuation_builder,
        **({"native_runtime":continuation_native} if continuation_native is not None else {}))
    ready = controller.wait_service("service-2", "bootstrap-ready.json", 25)
    startup(ready, first["sourceJoinId"], manifest, claim["terminalPoint"], True,
            before["ownerId"], first["boundedRestart"]["freshCheckpointId"])
    base.require(ready["boundedRestart"].get("sourceAnchorPoint") == controller.initial and
                 ready["boundedRestart"].get("restoredDepth") == checkpoint_after, "restored exact anchor/depth")
    resumed = resume_producer()
    peer_ready(controller, live, "service-2", resumed)
    base.write(controller.out / "restart-ready.json", ready)
    return RunningRestored("service-2", ready, claim, authority_pin, before["ownerId"],
                           first["boundedRestart"]["freshCheckpointId"])


def wait_checked_successor(controller, restored, seconds=20):
    """Observe an actual checked successor while leaving service ownership to caller."""
    base.require(type(seconds) is int and 1 <= seconds <= 60, "bounded successor observation")
    root = controller.exchange / restored.phase
    path = root / "publication-0000.json"
    until = min(controller.deadline, time.monotonic()+seconds)
    while time.monotonic() < until:
        base.require(not (root / "failure.json").exists(), "restored service failure")
        if path.exists():
            raw = read_exact(path, 131072)
            value = base.decode(raw)
            fresh = restored.ready["boundedRestart"]["startupAdmission"]["pin"]
            current = two.pin(value.get("pin"), fresh["ownerId"])
            base.require(value.get("schema") == "plutus-service-publication-v1" and type(value.get("index")) is int and value.get("index") == 0 and
                         value.get("sourceJoinId") == restored.claim["sourceJoinId"] and
                         value.get("initialManifestSHA256") == restored.claim["manifestSHA256"] and
                         value.get("profileId") == two.fixture.PROFILE and value.get("fullLedgerValidated") is False and
                         current["generation"] > fresh["generation"] and
                         current["point"]["blockNo"] == restored.claim["terminalPoint"]["blockNo"]+1 and
                         current["point"]["slot"] > restored.claim["terminalPoint"]["slot"], "genuine first checked rejoin successor")
            base.write(controller.out / "restart-successor.json", dict(schema="plutus-early-restart-successor-v1",
                       publicationSHA256=digest(raw), pin=current, authoritySHA256=restored.authority_sha256,
                       freshOwner=True, emptyPoolObserved=True, checkedSuccessor=True,
                       transactionInclusionClaimed=False, wholeStateOracleCompared=False,
                       fullLedgerValidated=False, crashDurable=False))
            return current
        if not controller.owned(controller.containers[restored.phase])["State"]["Running"]:
            # Publication may have completed during the Docker inspection.
            # Re-enter the same validation path only if final evidence now exists;
            # the outer deadline still applies, and stopped-without-evidence fails.
            base.require(not (root / "failure.json").exists(), "restored service failed")
            base.require(path.exists(), "restored service exited before successor")
            continue
        time.sleep(.1)
    raise TimeoutError("checked restart successor deadline")
