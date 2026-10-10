#!/usr/bin/env python3
"""Opt-in single-service repeated-JVM endpoint prerequisite; preflight by default.

This captures one declared terminal epoch, never enables the two-service soak,
never restores a checkpoint, and uses the existing two short epoch-zero spends.
"""
import argparse
from pathlib import Path
import time

import private_cluster_plutus_service as single
import private_cluster_plutus_soak as repeated

base = single.base
fixture = single.fixture
MODE = "repeated-jvm-v1"
MAX_OPERATION = 300
MAX_CLEANUP = 30
TERMINAL_FIELDS = frozenset((
    "schema", "diagnosticOnly", "restartSupported", "fullLedgerValidated", "pin", "sourceJoinId",
    "initialManifestSHA256", "outputMapFile", "outputMapSHA256", "fees", "epoch", "validationSlot",
    "instantaneousStake", "representedProtocol", "components", "componentsSHA256", "repeatedEpoch"))


def capture_limits(duration, blocks, terminal_epoch, mode=MODE):
    base.require(mode == MODE, "explicit pure-JVM capture mode only")
    base.require(type(duration) is int and 10 <= duration <= 120, "capture duration 10..120 seconds")
    base.require(type(blocks) is int and 1 <= blocks <= 128, "capture publications 1..128")
    base.require(type(terminal_epoch) is int and terminal_epoch in (0, 1), "declared terminal epoch 0 or 1")
    base.budget()


def capture_scope(terminal_epoch):
    base.require(type(terminal_epoch) is int and terminal_epoch in (0, 1), "declared terminal epoch")
    return "epoch-zero-only" if terminal_epoch == 0 else "post-boundary-exact-epoch-one"


def service_cli_tail(tail, duration, blocks, terminal_epoch):
    capture_limits(duration, blocks, terminal_epoch)
    # Reuse exact option construction, without weakening its same-epoch bounds.
    command = single.service_cli_tail(tail, min(duration, 60), blocks)
    command[command.index("--duration-seconds")+1] = str(duration)
    return command + ["--epoch-mode", MODE]


def declared_terminal(value, expected_epoch):
    base.require(isinstance(value, dict) and set(value) == TERMINAL_FIELDS, "exact repeated terminal fields")
    pin = repeated.two.pin(value.get("pin"))
    capture_scope(expected_epoch)
    components = value.get("components")
    base.require(type(value.get("epoch")) is int and value["epoch"] == expected_epoch == pin["point"]["slot"]//1000 and
                 isinstance(components, dict) and type(components.get("epoch")) is int and
                 components["epoch"] == expected_epoch, "declared terminal component/full-point epoch")
    component_raw = (base.json.dumps(components, sort_keys=True, separators=(",", ":"),
                                    ensure_ascii=False, allow_nan=False)+"\n").encode("utf-8")
    base.require(base.hex64(value.get("componentsSHA256")) and
                 fixture.digest(component_raw) == value["componentsSHA256"], "terminal component original digest")
    reward = components.get("reward")
    base.require(isinstance(reward, dict) and reward.get("phase") in ("absent", "complete"),
                 "active monetary reward pulsing is unsupported")
    epoch = value.get("repeatedEpoch")
    base.require(isinstance(epoch, dict) and epoch.get("generationMode") == "pure-jvm" and
                 type(epoch.get("transitions")) is int and epoch["transitions"] == expected_epoch,
                 "declared pure-JVM epoch transitions")
    return value


def controller_type(live):
    Parent = single.controller_type(live)

    class RepeatedCaptureController(Parent):
        operation_seconds = MAX_OPERATION
        invocation_schema = "plutus-repeated-capture-invocation-v1"
        result_schema = "plutus-repeated-capture-result-v1"

        def __init__(self, *args):
            super().__init__(*args)
            capture_limits(self.args.duration_seconds, self.args.max_blocks, self.args.terminal_epoch, self.args.epoch_mode)
            self.operation_started = time.monotonic()
            self.deadline = self.operation_started + MAX_OPERATION

        def mode_evidence(self):
            evidence = dict(epochMode=MODE, captureScope=capture_scope(self.args.terminal_epoch),
                declaredTerminalEpoch=self.args.terminal_epoch, serviceCount=1,
                durationSeconds=self.args.duration_seconds, maxBlocks=self.args.max_blocks,
                nativeRuntimeDependency=False, likelihoodNativeParityChecked=False,
                fullSoakValidated=False, postBoundaryHttpSpend=False,
                fullLedgerValidated=False, restartSupported=False,
                repeatedEvidenceValidatorSHA256=base.sha(Path(repeated.__file__)),
                twoServiceControllerSHA256=base.sha(Path(repeated.two.__file__)),
                restartControllerSHA256=base.sha(Path(repeated.restart.__file__)))
            if hasattr(self, "terminal_proof"):
                evidence["terminalEpochProofSHA256"] = base.sha(self.out / "terminal-epoch-proof.json")
            return evidence

        def service_command(self, tail):
            # Reserve full startup, follow, shutdown, exact capture and oracle time.
            # The monotonic operation deadline never expands after this check.
            reserve = 30 + self.args.duration_seconds + 10 + 57 + 35
            base.require(time.monotonic()+reserve < self.deadline,
                         "capture startup/follow/finalization/exact-endpoint/oracle must fit 300 seconds")
            return service_cli_tail(tail, self.args.duration_seconds, self.args.max_blocks, self.args.terminal_epoch)

        def client_duration(self):
            # Existing two originals retain TTL 999. They must both be included
            # before epoch one; this lane makes no post-boundary admission claim.
            return min(self.args.duration_seconds, 30)

        def oracle_main(self):
            return "lab.PlutusRepeatedServiceCompareMain"

        def readiness_deadline(self, ready):
            repeated.check_epoch_mode(ready.get("epochMode"))
            base.require("soakProfile" not in ready and "boundedRestart" not in ready,
                         "single volatile prerequisite without soak or restore mode")
            deadline, requested = ready.get("deadlineUnixMillis"), ready.get("requestedDeadlineUnixMillis")
            now = time.time_ns()//1000000
            base.require(type(deadline) is int and type(requested) is int and now < deadline <= now+30000 and
                         requested-deadline == (self.args.duration_seconds-30)*1000,
                         "separate bounded repeated startup deadline")
            return deadline

        def peer_started(self, ready, resumed):
            self.active_clock = repeated.active(self.wait_file("service-active.json", 10), self.args.duration_seconds)
            start = repeated.two.pin(self.active_clock.get("pin"))
            base.require(start["point"] == self.initial and
                         resumed <= self.active_clock["startedUnixMillis"] < ready["deadlineUnixMillis"],
                         "actual initial owner starts after peer readiness within startup window")
            base.write(self.out / "service-active.json", self.active_clock)

        def terminal_interval(self, result, deadline, resumed):
            repeated.check_epoch_mode(result.get("epochMode"))
            base.require("soakProfile" not in result and "boundedRestart" not in result,
                         "single volatile terminal prerequisite")
            repeated.full_interval(result, self.active_clock)
            base.require(result["endedUnixMillis"] <= self.active_clock["deadlineUnixMillis"]+10000,
                         "bounded repeated finalization after full active duration")
            final = repeated.two.pin(result.get("finalPin"), self.active_clock["pin"]["ownerId"])
            terminal = final["point"]
            base.require(repeated.two.follows(final, self.active_clock["pin"]) and
                         self.initial["slot"] < terminal["slot"] and
                         0 < terminal["blockNo"]-self.initial["blockNo"] <= self.args.max_blocks and
                         terminal["slot"]//1000 == self.args.terminal_epoch,
                         "actual progressing full terminal point reaches declared epoch")
            self.final_pin = final
            return terminal

        def validate_publications(self, result, ready, manifest):
            # Preserve the original client-to-inclusion binding checks as well
            # as the repeated generation checks; neither can substitute for it.
            super().validate_publications(result, ready, manifest)
            base.require(result["publications"][-1]["pin"] == result["finalPin"],
                         "last original publication is the complete terminal pin")
            owner = result["finalPin"]["ownerId"]
            for row in self.client_receipt["transactions"]:
                for pin in (row["acceptedResponse"]["receipt"]["pin"], row["includedResponse"]["pin"]):
                    checked = repeated.two.pin(pin, owner)
                    base.require(checked["point"]["slot"] < 1000 and
                                 repeated.two.follows(checked, self.active_clock["pin"]) and
                                 repeated.two.follows(result["finalPin"], checked),
                                 "both short original spends included under same owner in epoch zero")
            proof = repeated.repeated_publications(self.exchange, result, self.client_receipt["transactions"],
                ready["sourceJoinId"], manifest, self.active_clock, terminal_epoch=self.args.terminal_epoch)
            base.write(self.out / "repeated-publication-proof.json", proof)

        def terminal_observation(self, result, ready, manifest):
            value = repeated.terminal_observation(self.exchange, result, result["finalPin"], ready["sourceJoinId"], manifest)
            declared_terminal(value, self.args.terminal_epoch)
            raw = repeated.restart.read_exact(self.exchange / "terminal-output-map.cbor", 1048576)
            base.require(fixture.digest(raw) == value["outputMapSHA256"], "terminal original output map digest")
            self.terminal_proof = dict(schema="plutus-repeated-capture-terminal-epoch-v1",
                captureScope=capture_scope(self.args.terminal_epoch), declaredTerminalEpoch=self.args.terminal_epoch,
                observedTerminalEpoch=value["epoch"], finalPin=value["pin"],
                terminalObservationSHA256=result["terminalObservationSHA256"], outputMapSHA256=value["outputMapSHA256"],
                rewardPhase=value["components"]["reward"]["phase"],
                activeRecordSHA256=base.sha(self.out / "service-active.json"),
                publicationsProofSHA256=base.sha(self.out / "repeated-publication-proof.json"),
                postBoundaryTerminalObserved=value["epoch"] > 0,
                nativeEndpointAgreement=False, fullSoakValidated=False)
            # This proof records the selected export. Only the later, separately
            # hashed endpoint-comparison.json establishes native endpoint agreement.
            base.write(self.out / "terminal-epoch-proof.json", self.terminal_proof)
            return value

        def validate_comparison(self, value, terminal, ready, manifest, result, endpoint_ready):
            repeated.soak_comparison_result(value, self.transfers, terminal, ready["sourceJoinId"], manifest,
                result["finalPin"], endpoint_ready, terminal_epoch=self.args.terminal_epoch)
            base.require(value["endpointWholeUtxoSHA256"] == base.sha(self.exchange / "endpoint/original-whole-utxo.cbor"),
                         "independent complete endpoint UTxO original digest")
            return value

    return RepeatedCaptureController


def parser():
    result = argparse.ArgumentParser(description=__doc__)
    for name in ("support-manifest", "owned-root", "evidence-root", "scala-build-root",
                 "scala-classpath-file", "client-classpath-file", "projection"):
        result.add_argument("--"+name, type=Path, required=True)
    result.add_argument("--support-sha", required=True)
    result.add_argument("--scala-image", required=True)
    result.add_argument("--java", default="java")
    result.add_argument("--epoch-mode", choices=(MODE,), required=True)
    result.add_argument("--terminal-epoch", type=int, choices=(0, 1), required=True)
    result.add_argument("--duration-seconds", type=int, default=30)
    result.add_argument("--max-blocks", type=int, default=128)
    result.add_argument("--execute", action="store_true")
    return result


def main():
    args = parser().parse_args()
    capture_limits(args.duration_seconds, args.max_blocks, args.terminal_epoch, args.epoch_mode)
    live, support, classpath = base.preflight(args)
    base.require(classpath == single.checked_classpath(args.scala_classpath_file, args.scala_build_root, True),
                 "Compile-only repeated runtime classpath")
    single.checked_classpath(args.client_classpath_file, args.scala_build_root)
    if args.execute:
        single.execute(live, support, args, classpath, controller=controller_type(live), source=Path(__file__))
    else:
        print(base.json.dumps(dict(preflight=True, executed=False, epochMode=MODE, serviceCount=1,
            captureScope=capture_scope(args.terminal_epoch), declaredTerminalEpoch=args.terminal_epoch,
            operationSeconds=MAX_OPERATION, cleanupSeconds=MAX_CLEANUP, resources=base.RESOURCES,
            nativeRuntimeDependency=False, fullSoakValidated=False, postBoundaryHttpSpend=False), sort_keys=True))


if __name__ == "__main__":
    main()
