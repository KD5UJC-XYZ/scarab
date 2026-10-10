#!/usr/bin/env python3
"""Explicit epoch-zero restart/rejoin check; no transaction or endpoint-parity claim."""
import argparse
import copy
import os
from pathlib import Path
import shutil
import signal
import time
import private_cluster_plutus_two_service as two
import private_cluster_plutus_early_restart as restart

base, single, fixture = two.base, two.single, two.fixture
window_deadline, prefunding_point = two.window_deadline, two.prefunding_point
PROFILE = "early-restart-only-v1"


def continuation_args(args, *remaining):
    selected = copy.copy(args)
    selected.max_blocks = 1
    return two.service_args(selected, *remaining)


def finalized_successor(root, restored, successor, result, manifest):
    fresh = restart.startup(restored.ready, restored.claim["sourceJoinId"], manifest,
                            restored.claim["terminalPoint"], True, restored.prior_owner, restored.prior_checkpoint)
    final = two.pin(result.get("finalPin"), fresh["ownerId"])
    base.require(result.get("schema") == "plutus-service-result-v1" and result.get("status") == "stopped" and
                 result.get("stopReason") == "blockLimit" and result.get("resourcesFinalized") is True and
                 result.get("profileId") == fixture.PROFILE and result.get("sourceJoinId") == restored.claim["sourceJoinId"] and
                 result.get("initialManifestSHA256") == manifest and result.get("initialPoint") == restored.claim["terminalPoint"] and
                 type(result.get("initialEpoch")) is int and result["initialEpoch"] == 0 and result.get("epochMode") is None and
                 all(result.get(k) is False for k in ("fullLedgerValidated", "transactionSuccessClaimed", "restartSupported")),
                 "finalized default-mode restart successor")
    base.require(type(result.get("transportOpens")) is int and result["transportOpens"] > 0 and
                 type(result.get("transportCloses")) is int and result["transportCloses"] == result["transportOpens"],
                 "all successor transports finalized")
    base.require(final == successor and final["generation"] > fresh["generation"] and
                 0 < restored.claim["sourcePoint"]["slot"] < restored.claim["terminalPoint"]["slot"] < final["point"]["slot"] < 1000 and
                 final["point"]["blockNo"] == restored.claim["terminalPoint"]["blockNo"]+1,
                 "exact first restored successor remains in epoch zero")
    base.require(result.get("evaluationReceipts") == [], "no tested transaction admissions or revalidations")
    refs = result.get("publications")
    base.require(isinstance(refs, list) and len(refs) == 1 and refs[0].get("file") == "publication-0000.json" and
                 two.pin(refs[0].get("pin"), fresh["ownerId"]) == final, "one exact successor publication")
    raw = restart.read_exact(root/"publication-0000.json", 131072)
    publication = base.decode(raw)
    base.require(restart.digest(raw) == refs[0].get("sha256") and two.pin(publication.get("pin"), fresh["ownerId"]) == final and
                 publication.get("included") == [] and publication.get("sourceJoinId") == restored.claim["sourceJoinId"] and
                 publication.get("initialManifestSHA256") == manifest and publication.get("profileId") == fixture.PROFILE and
                 publication.get("schema") == "plutus-service-publication-v1" and type(publication.get("index")) is int and
                 publication["index"] == 0 and publication.get("diagnosticOnly") is True and publication.get("fullLedgerValidated") is False,
                 "original checked publication has no unrequested transaction")
    base.require(result.get("terminalObservationFile") == "terminal-observation.json", "fixed terminal evidence path")
    terminal_raw = restart.read_exact(root/"terminal-observation.json", 1048576)
    terminal = base.decode(terminal_raw)
    base.require(restart.digest(terminal_raw) == result.get("terminalObservationSHA256") and two.pin(terminal.get("pin"), fresh["ownerId"]) == final and
                 terminal.get("sourceJoinId") == restored.claim["sourceJoinId"] and terminal.get("initialManifestSHA256") == manifest and
                 type(terminal.get("epoch")) is int and terminal["epoch"] == 0 and
                 terminal.get("schema") == "plutus-service-terminal-observation-v1" and terminal.get("diagnosticOnly") is True and
                 terminal.get("restartSupported") is False and terminal.get("fullLedgerValidated") is False, "source-bound terminal observation original")
    start, end = result.get("startedUnixMillis"), result.get("endedUnixMillis")
    base.require(type(start) is int and type(end) is int and 0 <= end-start <= 35000, "bounded default successor interval")
    return final


def controller_type(live):
    Parent = two.controller_type(live)
    class RestartOnly(Parent):
        def resume_producer(self):
            self.stop_node(1); self.stop_node(2); self.stopped.clear()
            self.start_node(1, True); self.start_node(2, False)
            return time.time_ns()//1000000

        def freeze_producer(self):
            self.stop_node(1); self.stop_node(2); self.stopped.clear()
            self.start_node(1, False); self.start_node(2, False)

        def prepare_restart_initial(self, approval):
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


        def same_epoch_lifecycle(self, approval):
            self.manifest_pin = self.prepare_restart_initial(approval)
            # No construct_transfer/run_client call: only existing bootstrap funding.
            self.restored = restart.perform_early_restart(self, live, self.manifest_pin,
                store_id=restart.digest(os.urandom(32)), session_id=restart.digest(os.urandom(32)), generation=0,
                resume_producer=self.resume_producer, freeze_producer=self.freeze_producer,
                checkpoint_after=1, continuation_builder=continuation_args)
            successor = restart.wait_checked_successor(self, self.restored, seconds=30)
            # Both wait helpers inspect completed files before process-running
            # state, so a fast blockLimit exit cannot erase valid evidence.
            result = self.wait_service(self.restored.phase, "result.json", 10)
            restart.wait_exit(self, self.restored.phase)
            final = finalized_successor(self.exchange/self.restored.phase, self.restored, successor, result, self.manifest_pin)
            raw_result = restart.read_exact(self.exchange/self.restored.phase/"result.json", 1048576)
            base.require(restart.encoded(base.decode(raw_result)) == restart.encoded(result), "final original matches validated result")
            restart.publish_new(self.out/"restored-service-result.json", raw_result)
            self.stop_node(1); self.stop_node(2)
            self.cleanup()
            return final
    return RestartOnly


def execute(live, support, args, classpath):
    base.require(args.restart_profile == PROFILE and args.duration_seconds == 30 and args.max_blocks == 8,
                 "closed restart-only driver limits")
    two.budget()
    launch = controller_type(live)(support, args, classpath)
    watchdog = live.DiskWatchdog(args.owned_root)
    previous = {}
    def interrupted(signum, _frame):
        if launch.in_cleanup and signum != signal.SIGALRM:
            return
        raise TimeoutError("bounded early restart interrupted")
    started = time.monotonic()
    try:
        previous = {s: signal.signal(s, interrupted) for s in (signal.SIGALRM, signal.SIGTERM, signal.SIGINT, signal.SIGUSR1)}
        signal.alarm(240)
        watchdog.start()
        base.write(launch.out/"invocation.json", dict(schema="plutus-early-restart-invocation-v1", restartProfile=PROFILE,
                   supportManifestSHA256=args.support_sha, controllerSHA256=base.sha(Path(__file__)),
                   adapterSHA256=base.sha(Path(restart.__file__)), twoServiceControllerSHA256=base.sha(Path(two.__file__)),
                   resourcesNanoCpuAndBytes=two.RESOURCES, scalaImage=args.scala_image,
                   runtimeClasspathSHA256=base.sha(args.scala_classpath_file), clientClasspathSHA256=base.sha(args.client_classpath_file),
                   operationSeconds=240, cleanupSeconds=30, firstCheckpointAfter=1, restoredMaxBlocks=1,
                   profileId=fixture.PROFILE, testedSpendCliSubmissionAllowed=False,
                   cliSubmissionAllowed="one-reference-funding-before-bootstrap-only", wholeStateOracleCompared=False))
        base.require(launch.docker("image", "inspect", args.scala_image, "--format", "{{.Id}}").stdout.strip() == args.scala_image,
                     "pinned Scala image")
        ids = dict(generatorSHA256=live.z.GENERATOR_SHA, launcherSHA256=base.sha(Path(__file__)),
                   imageId=live.z.REFERENCE_IMAGE, sourceCommit=support["sourceCommit"])
        final, receipt = live.controller.execute(args.owned_root, ids, launch.generate, launch.lifecycle, on_abort=launch.cleanup)
        base.write(launch.out/"controller-lifecycle.json", receipt)
        base.require(launch.cleaned and watchdog.failure is None and time.monotonic()-started <= 270,
                     "bounded finalized owned restart lifecycle")
        base.write(launch.out/"result.json", dict(schema="plutus-early-restart-controller-result-v1", passed=True,
                   restartProfile=PROFILE, cleanupVerified=True, elapsedSeconds=time.monotonic()-started,
                   profileId=fixture.PROFILE, exchangeRoot=str(launch.exchange), initialPoint=launch.initial, finalPin=final,
                   authoritySHA256=launch.restored.authority_sha256,
                   acceptedPairSHA256=base.sha(launch.out/"restart-accepted-pair.json"),
                   acquisitionSHA256=base.sha(launch.out/"restart-acquisition.json"),
                   restoredReadySHA256=base.sha(launch.out/"restart-ready.json"),
                   successorSHA256=base.sha(launch.out/"restart-successor.json"),
                   restoredServiceResultSHA256=base.sha(launch.out/"restored-service-result.json"),
                   freshOwner=True, emptyPoolObserved=True, checkedSuccessor=True, epochZeroOnly=True,
                   transactionInclusionClaimed=False, wholeStateOracleCompared=False, multiEpoch=False,
                   fullLedgerValidated=False, lateCheckpointRestoreSupported=False, crashDurable=False))
    except BaseException as error:
        if not (launch.out/"failure.json").exists():
            base.write(launch.out/"failure.json", dict(errorType=type(error).__name__, message=str(error)[:4096]))
        raise
    finally:
        try:
            launch.cleanup()
        finally:
            watchdog.stop()
            signal.alarm(0)
            for sig, handler in previous.items():signal.signal(sig, handler)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("support-manifest", "owned-root", "evidence-root", "scala-build-root", "scala-classpath-file", "client-classpath-file", "projection"):
        parser.add_argument("--"+name, type=Path, required=True)
    parser.add_argument("--support-sha", required=True)
    parser.add_argument("--scala-image", required=True)
    parser.add_argument("--java", default="java")
    parser.add_argument("--restart-profile", choices=(PROFILE,), required=True)
    parser.add_argument("--execute", action="store_true")
    args = parser.parse_args()
    args.duration_seconds, args.max_blocks = 30, 8
    live, support, classpath = base.preflight(args)
    base.require(classpath == single.checked_classpath(args.scala_classpath_file, args.scala_build_root, True), "Compile-only runtime")
    single.checked_classpath(args.client_classpath_file, args.scala_build_root)
    two.budget()
    if args.execute:execute(live, support, args, classpath)
    else:print(base.json.dumps(dict(preflight=True, executed=False, restartProfile=PROFILE,
               resourcesNanoCpuAndBytes=two.RESOURCES, epochZeroOnly=True, wholeStateOracleCompared=False),sort_keys=True))


if __name__ == "__main__":main()
