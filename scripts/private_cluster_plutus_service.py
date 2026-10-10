#!/usr/bin/env python3
"""Bounded same-epoch two-spend service exercise; preflight unless --execute.

One reference funding submission precedes native bootstrap. Both tested spends
enter the Compile-only service through a separate HTTP client. All state
comparisons happen externally after the service's configured bounded stop.
This acceptance lane requires the full requested duration to fit before the
epoch margin and accepts durationLimit/blockLimit only. The service also supports
epochLimit, but this controller makes no live claim for that separate stop path.
"""
import argparse
from pathlib import Path
import signal
import shutil
import os
import time
import private_cluster_ada_submission as ada
import private_cluster_native_boundary as base
import private_cluster_plutus_submission as diagnostic
import plutus_service_fixture as fixture

frozen_point = diagnostic.frozen_point
prefunding_point = diagnostic.prefunding_point
window_deadline = diagnostic.window_deadline
checked_classpath = diagnostic.checked_classpath


def service_limits(duration, blocks):
    base.require(type(duration) is int and 1 <= duration <= 60, "bounded service duration 1..60")
    base.require(type(blocks) is int and 1 <= blocks <= 128, "bounded service blocks 1..128")


def typed_failure_summary(raw):
    report = base.decode(raw)
    base.require(isinstance(report, dict) and report.get("schema") == "sequential-devnet-runner-v1" and
                 report.get("stop") == "Failed" and report.get("allRequestedPassed") is False and
                 report.get("executedScenariosPassed") is False and report.get("observationalOnly") is True and
                 report.get("restoreAuthority") is False, "typed unsuccessful observational report")
    scenarios = ("ObserveService", "TwoSequentialTransfers", "ObserveService",
                 "RestartAndRejoin", "FollowAcrossEpochs", "MultipleNodes")
    rows = report.get("rows")
    base.require(isinstance(rows, list) and 1 <= len(rows) <= 6, "bounded failed scenario prefix")
    for index, row in enumerate(rows):
        base.require(isinstance(row, dict) and row.get("scenario") == scenarios[index] and
                     isinstance(row.get("verdict"), dict), "ordered failed scenario prefix")
    verdict = rows[-1]["verdict"]
    reasons = ("ActionRejected", "AdapterError", "ActionDeadline", "CheckpointTooLarge", "EvidenceBudget", "ClientFailure")
    base.require(verdict.get("outcome") == "failed" and verdict.get("reason") in reasons,
                 "closed typed failure reason")
    summary = dict(schema=report["schema"], stop="Failed", allRequestedPassed=False,
                   executedScenariosPassed=False, scenario=scenarios[len(rows)-1], reason=verdict["reason"])
    diagnostic_value = verdict.get("diagnostic")
    if diagnostic_value is not None:
        causes = ("ConnectionFailure", "Deadline", "InvalidObservation", "TransportFailure", "EvidenceUnavailable", "UnknownClientFailure")
        base.require(verdict["reason"] == "ClientFailure" and isinstance(diagnostic_value, dict) and
                     diagnostic_value.get("operation") == "HttpClient" and diagnostic_value.get("cause") in causes,
                     "closed HTTP failure diagnostic")
        summary["diagnostic"] = dict(operation="HttpClient", cause=diagnostic_value["cause"])
        observed = diagnostic_value.get("lastObservation")
        if observed is not None:
            stages = ("Admission1", "Admission2", "Duplicate1", "Duplicate2", "Conflict1", "WaitFirstInclusion",
                      "WaitSecondInclusion", "BetweenState", "AfterSecondState", "FirstStatusAfterSecond", "Unknown")
            codes = ("Accepted", "AlreadyPresent", "InputsReserved", "Pending", "Included", "State", "Rejected",
                     "Unsupported", "StaleState", "Unavailable", "Unknown")
            base.require(isinstance(observed, dict) and observed.get("stage") in stages and
                         observed.get("responseCode") in codes, "closed last HTTP observation")
            summary["diagnostic"]["lastObservation"] = dict(stage=observed["stage"], responseCode=observed["responseCode"])
    return summary


def retain_helper_failure(error, submission):
    """Retain bounded helper bytes privately; return hash-only safe metadata."""
    submission = Path(submission)
    base.require(submission.resolve() == submission, "owned failure evidence directory")
    base.require(type(error.code) is int and -128 <= error.code <= 255, "bounded helper exit code")
    result = dict(schema="plutus-service-helper-failure-v1", exitCode=error.code, passed=False,
                  logsPrivate=True, fullLedgerValidated=False)
    for name, upstream_limit, retained_limit in (("stdout", 4*1024*1024, 262144), ("stderr", 131072, 131072)):
        raw = getattr(error, name)
        base.require(isinstance(raw, bytes) and len(raw) <= upstream_limit, "bounded helper "+name)
        kept = raw[:retained_limit]
        filename = "client-helper-failure."+name
        descriptor = os.open(submission / filename, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(kept)
        result[name] = dict(file="submission/"+filename, observedBytes=len(raw), retainedBytes=len(kept),
                            truncated=len(kept) != len(raw), originalSHA256=fixture.digest(raw),
                            retainedSHA256=fixture.digest(kept))
    report_path = submission / "scenario-runner-result.json"
    if report_path.exists():
        try:
            base.require(report_path.resolve() == report_path, "typed failure report has no symlink traversal")
            raw = base.read(report_path, 65536)
            result["scenarioReport"] = dict(file="submission/scenario-runner-result.json", bytes=len(raw),
                                             sha256=fixture.digest(raw), validation="rejected")
            result["scenarioReport"]["summary"] = typed_failure_summary(raw)
            result["scenarioReport"]["validation"] = "typed-failure"
        except (ValueError, KeyError, TypeError, OSError):
            # The original helper failure remains primary. Never copy arbitrary
            # exception text or unvalidated report text into public metadata.
            result.setdefault("scenarioReport", dict(validation="unavailable"))
    return result


def client_command(mode, port, duration):
    """Closed Test-only client selection; the Compile runtime is unchanged."""
    base.require(mode in ("direct", "sequential"), "closed client mode: direct or sequential")
    service_limits(duration, 128)
    base.require(type(port) is int and 1 <= port <= 65535, "bounded client API port")
    if mode == "sequential":
        base.require(duration >= 3, "sequential client requires at least three seconds")
    main = "lab.PlutusServiceClientMain" if mode == "direct" else "lab.PlutusScenarioRunnerMain"
    return [main, str(port), str(duration), "/exchange"]


def sequential_report(submission, client):
    """Validate typed outcomes against original observational checkpoint bytes.

    These files are evidence, never a durable state image or restore authority.
    Known unsupported operations must remain explicitly blocked, not passed.
    """
    submission = Path(submission)
    path = submission / "scenario-runner-result.json"
    base.require(path.resolve() == path, "scenario report has no symlink traversal")
    raw = base.read(path, 65536)
    report = base.decode(raw)
    base.require(report.get("schema") == "sequential-devnet-runner-v1" and
                 report.get("executedScenariosPassed") is True and report.get("allRequestedPassed") is False and
                 report.get("stop") == "Exhausted" and report.get("observationalOnly") is True and
                 report.get("restoreAuthority") is False, "bounded observational sequential outcomes")
    expected = ("ObserveService", "TwoSequentialTransfers", "ObserveService",
                "RestartAndRejoin", "FollowAcrossEpochs", "MultipleNodes")
    missing = ("DurableRestore", "RepeatedEpochTransition", "MultipleIngressNodes")
    rows = report.get("rows")
    base.require(isinstance(rows, list) and len(rows) == len(expected), "exact requested scenario count")
    checkpoints = []
    for index, (row, scenario) in enumerate(zip(rows, expected)):
        base.require(isinstance(row, dict) and set(row) == {"scenario", "verdict"} and row["scenario"] == scenario,
                     "exact ordered typed scenario")
        verdict = row["verdict"]
        base.require(isinstance(verdict, dict), "typed scenario verdict")
        if index >= 3:
            base.require(verdict == dict(outcome="blocked", missingRequirement=missing[index-3]),
                         "known unsupported scenario remains explicitly blocked")
            continue
        base.require(set(verdict) == {"outcome", "checkpointSHA256", "checkpointBytes", "index"} and
                     verdict["outcome"] == "completed" and type(verdict["index"]) is int and verdict["index"] == index and
                     type(verdict["checkpointBytes"]) is int and 0 < verdict["checkpointBytes"] <= 65536 and
                     base.hex64(verdict["checkpointSHA256"]), "bounded completed observational checkpoint")
        checkpoint_path = submission / f"scenario-checkpoint-{index}.json"
        base.require(checkpoint_path.resolve() == checkpoint_path, "checkpoint has no symlink traversal")
        checkpoint = base.read(checkpoint_path, 65536)
        base.require(len(checkpoint) == verdict["checkpointBytes"] and
                     fixture.digest(checkpoint) == verdict["checkpointSHA256"], "exact checkpoint original bytes")
        checkpoints.append(checkpoint)
    base.require(sum(map(len, checkpoints)) <= 65536, "aggregate checkpoint byte budget")
    client_path = submission / "service-client-result.json"
    base.require(client_path.resolve() == client_path and checkpoints[1] == base.read(client_path, 65536) and
                 base.decode(checkpoints[1]) == client, "transfer checkpoint preserves original external client result")
    states = [base.decode(checkpoints[index]) for index in (0, 2)]
    for state in states:
        base.require(state.get("code") == "State" and state.get("profileId") == fixture.PROFILE and
                     state.get("closed") is False and state.get("fullLedgerValidated") is False,
                     "live bounded state observation, not restoration authority")
    pins = [state.get("pin") for state in states]
    for tx in client["transactions"]:
        pins.extend((tx["acceptedResponse"]["receipt"]["pin"], tx["includedResponse"]["pin"]))
    pins.extend(client[name]["pin"] for name in
                ("betweenStateResponse", "afterSecondStateResponse", "firstStatusAfterSecondResponse"))
    owner = pins[0].get("ownerId") if isinstance(pins[0], dict) else None
    base.require(base.hex64(owner), "initial observed owner identity")
    for pin in pins:
        base.require(isinstance(pin, dict) and pin.get("ownerId") == owner and pin.get("profileId") == fixture.PROFILE,
                     "all observations and HTTP evidence belong to the initial owner")
        for key in ("coherentStateId", "ledgerStateId", "environmentId"):
            base.require(base.hex64(pin.get(key)), "observational state identity: "+key)
        point = base.point(pin.get("point"))
        base.require(type(pin.get("generation")) is int and 0 <= pin["generation"] < 2**64 and
                     type(pin.get("validationSlot")) is int and pin["validationSlot"] == point["slot"],
                     "observational generation and validation point")
    base.require(states[0]["pin"]["generation"] <= states[1]["pin"]["generation"] and
                 states[0]["pin"]["point"]["slot"] <= states[1]["pin"]["point"]["slot"] and
                 states[0]["pin"]["generation"] <= client["transactions"][0]["acceptedResponse"]["receipt"]["pin"]["generation"] and
                 states[0]["pin"]["point"]["slot"] <= client["transactions"][0]["acceptedResponse"]["receipt"]["pin"]["point"]["slot"] and
                 states[1]["pin"]["point"]["slot"] >= client["transactions"][1]["includedResponse"]["pin"]["point"]["slot"] and
                 states[1]["pin"]["generation"] >= client["transactions"][1]["includedResponse"]["pin"]["generation"],
                 "sequential state observations surround both inclusions")
    return raw, checkpoints


def service_cli_tail(tail, duration, blocks):
    service_limits(duration, blocks)
    tail = list(tail)
    index = tail.index("lab.NativeLiveBoundaryMain")
    original = tail[index+1:]
    base.require(len(original) == 5, "exact service launcher arguments")
    initial, manifest, port, magic, output = original
    return tail[:index] + ["lab.Main", "plutus-service", "--profile", fixture.PROFILE,
            "--initial", initial, "--manifest-sha256", manifest, "--port", port,
            "--magic", magic, "--output", output, "--duration-seconds", str(duration),
            "--max-blocks", str(blocks)]


def epoch_budget(boundary_ms, seconds, now_ms=None):
    service_limits(seconds, 128)
    if now_ms is None: now_ms = time.time_ns() // 1000000
    base.require(type(boundary_ms) is int and type(now_ms) is int, "integer epoch wallclock")
    if now_ms + seconds*1000 + 2000 >= boundary_ms:
        raise TimeoutError("service duration cannot fit remaining epoch with two-second margin")


def client_result(value, transfers):
    base.require(value.get("schema") == "plutus-service-client-result-v1" and value.get("passed") is True and
                 value.get("profileId") == fixture.PROFILE and value.get("fullLedgerValidated") is False,
                 "bounded two-transaction HTTP client result")
    for name in ("serviceAliveAfterSecondInclusion", "sameOwner", "firstInclusionThenSecondSubmission",
                 "apiAvailableAfterFirst", "apiAvailableAfterSecond"):
        base.require(value.get(name) is True, "service client observation: "+name)
    base.require(value.get("staleReceiptSubmitted") is False, "historical receipt is audit only")
    rows = value.get("transactions")
    base.require(isinstance(rows, list) and len(rows) == len(transfers) == 2, "two independent HTTP transactions")
    for index, (row, transfer) in enumerate(zip(rows, transfers), 1):
        base.require(row.get("ordinal") == index, "ordered client transaction")
        for name in ("transactionId", "envelopeSHA256", "bodySHA256", "witnessesSHA256"):
            base.require(base.hex64(row.get(name)) and row[name] == transfer[name], "HTTP original binding: "+name)
        for name in ("bodySHA256", "witnessesSHA256"):
            base.require(row.get("included"+name[0].upper()+name[1:]) == transfer[name], "included original span")
        accepted, included = row.get("acceptedResponse", {}), row.get("includedResponse", {})
        base.require(accepted.get("code") == "Accepted" and included.get("code") == "Included", "accepted then included")
        a, b = row.get("acceptedObservedNanos"), row.get("includedObservedNanos")
        base.require(type(a) is int and type(b) is int and a < b, "ordered HTTP observations")
    base.require(rows[0]["includedObservedNanos"] < rows[1]["acceptedObservedNanos"], "first inclusion precedes second admission")
    base.require(transfers[0]["transactionId"] != transfers[1]["transactionId"] and
                 len({t[k] for t in transfers for k in ("spentInput", "collateralInput")}) == 4,
                 "disjoint script and collateral references")
    return value


def comparison_result(value, transfers, terminal, join_id, manifest):
    base.require(value.get("schema") == "plutus-service-endpoint-comparison-v1" and
                 value.get("terminalPoint") == terminal and value.get("sourceJoinId") == join_id and
                 value.get("initialManifestSHA256") == manifest, "external exact historical comparison bindings")
    for name in ("completeUtxoEqual", "collateralPreserved", "instantaneousStakeEqual",
                 "endpointSnapshotsUnchanged", "representedProtocolEqual", "diagnosticOnly"):
        base.require(value.get(name) is True, "external comparison: "+name)
    base.require(value.get("fullLedgerValidated") is False and value.get("restartSupported") is False,
                 "external comparison diagnostic scope")
    before, after = value.get("feesBefore"), value.get("feesAfter")
    base.require(type(before) is int and type(after) is int and before >= 0 and after-before == 2*fixture.FEE,
                 "two exact fee charges")
    rows = value.get("transactions")
    base.require(isinstance(rows, list) and len(rows) == 2, "two oracle transaction rows")
    for row, transfer in zip(rows, transfers):
        for name in ("transactionId", "envelopeSHA256", "bodySHA256", "witnessesSHA256"):
            base.require(row.get(name) == transfer[name], "oracle original identity: "+name)
        base.require(row.get("spent") == transfer["spentInput"] and row.get("collateral") == transfer["collateralInput"],
                     "oracle disjoint input pair")
    return value


def publication_bindings(exchange, result, client, join_id, manifest):
    refs = result.get("publications")
    base.require(isinstance(refs, list) and 1 <= len(refs) <= 128, "bounded final publication references")
    indexed = {}
    for ref in refs:
        name = ref.get("file")
        base.require(isinstance(name, str) and diagnostic.re.fullmatch(r"publication-[0-9]{4}\.json", name) and
                     name not in indexed, "unique bounded publication filename")
        path = Path(exchange) / name
        base.require(path.resolve() == path, "publication has no symlink traversal")
        raw = base.read(path, 131072)
        base.require(fixture.digest(raw) == ref.get("sha256"), "final result pins publication originals")
        value = base.decode(raw)
        base.require(value.get("schema") == "plutus-service-publication-v1" and value.get("profileId") == fixture.PROFILE and
                     value.get("sourceJoinId") == join_id and value.get("initialManifestSHA256") == manifest and
                     value.get("pin") == ref.get("pin") and value.get("diagnosticOnly") is True and
                     value.get("fullLedgerValidated") is False, "publication source/owner/scope bindings")
        index = value.get("index")
        base.require(type(index) is int and 0 <= index < 128 and name == f"publication-{index:04d}.json", "publication event index")
        indexed[name] = (ref, value)
    for row in client["transactions"]:
        name = row.get("publicationFile")
        base.require(name in indexed, "client inclusion is in finalized publication list")
        ref, value = indexed[name]
        base.require(row.get("publicationSHA256") == ref["sha256"] and row.get("publication") == value and
                     row["includedResponse"].get("pin") == value["pin"], "exact client publication and included full pin")
        included = value.get("included")
        identity = {name: row[name] for name in ("transactionId", "bodySHA256", "witnessesSHA256")}
        base.require(isinstance(included, list) and included.count(identity) == 1, "included original body/witness identity")


def controller_type(live):
    DiagnosticController = diagnostic.controller_type(live)
    AdaController = DiagnosticController.__bases__[0]
    BoundaryController = AdaController.__bases__[0]

    class ServiceController(DiagnosticController):
        operation_seconds = 240
        invocation_schema = "plutus-service-controller-invocation-v1"
        result_schema = "plutus-service-controller-result-v1"

        def mode_evidence(self):
            return {}

        def service_command(self, tail):
            epoch_budget(self.boundary_ms, self.args.duration_seconds)
            return service_cli_tail(tail, self.args.duration_seconds, self.args.max_blocks)

        def client_duration(self):
            return self.args.duration_seconds

        def oracle_main(self):
            return "lab.PlutusServiceCompareMain"

        def readiness_deadline(self, ready):
            deadline = ready.get("deadlineUnixMillis")
            base.require(type(deadline) is int and time.time_ns()//1000000 < deadline < self.boundary_ms and
                         deadline == ready.get("requestedDeadlineUnixMillis"), "uncropped same-epoch service deadline")
            return deadline

        def peer_started(self, ready, resumed):
            pass

        def terminal_interval(self, result, deadline, resumed):
            base.require(result.get("effectiveDeadlineUnixMillis") == deadline and
                         type(result.get("startedUnixMillis")) is int and type(result.get("endedUnixMillis")) is int and
                         resumed <= result["startedUnixMillis"] <= result["endedUnixMillis"] < self.boundary_ms,
                         "actual same-epoch service interval")
            terminal = base.point(result["finalPin"]["point"])
            base.require(self.initial["slot"] < terminal["slot"] < 1000 and
                         0 < terminal["blockNo"]-self.initial["blockNo"] <= self.args.max_blocks,
                         "bounded same-epoch terminal successor")
            return terminal

        def validate_publications(self, result, ready, manifest):
            publication_bindings(self.exchange, result, self.client_receipt, ready["sourceJoinId"], manifest)

        def terminal_observation(self, result, ready, manifest):
            base.require(result.get("terminalObservationFile") == "terminal-observation.json" and
                         result.get("terminalObservationSHA256") == base.sha(self.exchange / "terminal-observation.json"),
                         "original terminal observation bytes")
            observation = base.decode(base.read(self.exchange / "terminal-observation.json", 1048576))
            base.require(observation.get("pin") == result["finalPin"] and observation.get("sourceJoinId") == ready["sourceJoinId"] and
                         observation.get("initialManifestSHA256") == manifest and observation.get("epoch") == 0,
                         "full terminal owner/source/epoch cross-binding")
            return observation

        def validate_comparison(self, value, terminal, ready, manifest, result, endpoint_ready):
            return comparison_result(value, self.transfers, terminal, ready["sourceJoinId"], manifest)

        def create(self, phase, tail):
            if phase == "scala":
                tail = self.service_command(tail)
            # Skip diagnostic command rewriting, retaining its actual audited
            # ADA/base container ownership implementation in this MRO.
            return super(DiagnosticController, self).create(phase, tail)

        def wait_file(self, name, seconds):
            if name != "result.json":
                return BoundaryController.wait_file(self, name, seconds)
            until = min(self.deadline, time.monotonic()+seconds)
            while time.monotonic() < until:
                base.require(not (self.exchange / "failure.json").exists(), "service reported terminal failure")
                path = self.exchange / name
                if path.exists():
                    return base.decode(base.read(path, 1048576))
                base.require(self.owned(self.containers["scala"])["State"]["Running"], "service exited before final result")
                time.sleep(0.1)
            raise TimeoutError("bounded service result readiness")

        def proof_helper(self, phase, arguments, output):
            base.require(phase in ("native-originals", "plutus-spend-1-originals", "plutus-spend-2-originals") and
                         phase not in self.containers and "scala" not in self.containers,
                         "serial fixture proof before Scala startup")
            base.require(len(arguments) == 3 and arguments[0] == "originals",
                         "original-span Compile command only")
            args = ["--pull=never", "--network=none", "--cpus=1", "--memory=2g", "--memory-swap=2g",
                    "--pids-limit=256", "--read-only", "--cap-drop=ALL", "--security-opt=no-new-privileges",
                    "--user", "1000:1000", "--tmpfs", "/tmp:rw,nosuid,nodev,size=64m",
                    "--mount", "type=bind,src=" + str(self.args.scala_build_root) + ",dst=/work,readonly",
                    "--mount", "type=bind,src=" + str(self.exchange) + ",dst=/exchange",
                    "--entrypoint", self.args.java, self.args.scala_image, "-Xmx512m", "-XX:ActiveProcessorCount=1",
                    "-cp", self.classpath, "lab.Main", "transaction-originals", *arguments[1:]]
            cid = self.create(phase, args)
            try:
                observed = self.owned(cid)
                base.check_resources(observed, "helper", self.args.scala_image, "none")
                base.require({(m["Source"], m["Destination"], m["RW"]) for m in observed["Mounts"] if m["Type"] == "bind"} ==
                             {(str(self.args.scala_build_root), "/work", False), (str(self.exchange), "/exchange", True)},
                             "exact fixture proof mounts")
                base.write(self.out / (phase + "-inspection.json"), observed)
                timeout = min(35, self.deadline - time.monotonic())
                if timeout <= 0:
                    raise TimeoutError("Plutus fixture proof exceeded preparation window")
                result = self.docker("start", "--attach", cid, timeout=timeout)
                (self.out / (phase + ".log")).write_text(result.stdout + result.stderr)
                state = self.owned(cid)["State"]
                base.require(not state["Running"] and state["ExitCode"] == 0 and not state["OOMKilled"], "fixture proof exit")
                return base.decode(base.read(output, 16384))
            finally:
                self.owned(cid)
                self.docker("rm", "--force", cid)
                base.require(not self.docker("ps", "-aq", "--no-trunc", "--filter", "id=" + cid).stdout.strip(),
                             "fixture proof absence")
                base.write(self.out / (phase + "-cleanup.json"), dict(containerId=cid, absenceVerified=True))

        def prepare_funding(self, anchor):
            prefunding_point(anchor)
            root = self.exchange / "plutus-fixture"
            root.mkdir(mode=0o700)
            before = self.snapshot("funding-before", anchor)
            keyroot = self.environment / "utxo-keys"
            source_address = self.execute("cardano-cli", "address", "build", "--payment-verification-key-file",
                                          str(keyroot / "utxo1/utxo.vkey"),
                                          "--testnet-magic", str(self.magic)).stdout.strip()
            self.execute("mkdir", "-p", fixture.ROOT + "/keys")
            self.execute("cardano-cli", "address", "key-gen", "--verification-key-file", fixture.ROOT + "/keys/beneficiary.vkey",
                         "--signing-key-file", fixture.ROOT + "/keys/beneficiary.skey")
            keyhash = self.execute("cardano-cli", "address", "key-hash", "--payment-verification-key-file",
                                   fixture.ROOT + "/keys/beneficiary.vkey").stdout.strip()
            beneficiary = self.execute("cardano-cli", "address", "build", "--payment-verification-key-file",
                                       fixture.ROOT + "/keys/beneficiary.vkey", "--testnet-magic", str(self.magic)).stdout.strip()
            info = base.decode(self.execute("cardano-cli", "address", "info", "--address", beneficiary).stdout)
            base.require(info.get("base16") == "60" + keyhash, "enterprise beneficiary/collateral address")
            payload = base.read(self.args.scala_build_root / "vm/src/test/resources/plutus-pv9-reference/script.cbor", 370)
            self.script = fixture.script_fixture(keyhash, payload)
            base.write(root / "script.json", self.script["json"])
            (root / "script.cbor").write_bytes(bytes.fromhex(self.script["originalCborHex"]))
            base.write(self.out / "script-fixture.json", self.script)
            self.execute("cp", "--", str(keyroot / "utxo1/utxo.skey"), fixture.ROOT + "/keys/utxo.skey")
            self.execute("ln", "-s", str(self.environment / "socket"), fixture.ROOT + "/socket")
            self.owned(self.containers["reference"])
            self.docker("exec", "-i", self.containers["reference"], "tee", fixture.SCRIPT,
                        data=base.read(root / "script.json", 16384))
            for path, value in ((fixture.DATUM, self.script["datum"]), (fixture.REDEEMER, {"int": 7})):
                self.docker("exec", "-i", self.containers["reference"], "tee", path, data=base.json.dumps(value).encode())
            self.query_node(1, "protocol-parameters", "--out-file", fixture.PARAMETERS)
            address = self.execute("cardano-cli", "address", "build", "--payment-script-file", fixture.SCRIPT,
                                   "--testnet-magic", str(self.magic)).stdout.strip()
            info_raw = self.execute("cardano-cli", "address", "info", "--address", address).stdout.encode()
            base.require(0 < len(info_raw) <= 16384, "bounded address information")
            with (root / "address-info.json").open("xb") as stream:
                stream.write(info_raw)
            info = base.decode(info_raw)
            reference_hash = self.execute("cardano-cli", "conway", "transaction", "policyid",
                                          "--script-file", fixture.SCRIPT).stdout.strip()
            base.require(reference_hash == self.script["scriptHash"], "reference native script hash binding")
            base.write(self.out / "script-reference-comparison.json", dict(
                referenceScriptHash=reference_hash, scriptHash=self.script["scriptHash"],
                addressInfoSHA256=base.sha(root / "address-info.json"), matched=True))
            script_address = fixture.checked_script_address(self.script, info)
            self.plan = fixture.funding_plan(before.utxo, source_address, script_address, beneficiary, self.script["datum"])
            self.commands = fixture.funding_commands(self.plan, self.magic, fixture.ROOT + "/keys/utxo.skey")
            self.execute(*self.commands["fundingBuild"])
            self.execute(*self.commands["fundingSign"])
            original = self.retain_signed(fixture.FUNDING, root / "funding.signed.json")
            (root / "funding.cbor").write_bytes(original)
            self.funded_txid = self.execute("cardano-cli", "conway", "transaction", "txid", "--tx-file",
                                           fixture.FUNDING, "--output-text").stdout.strip()
            identity = self.proof_helper("native-originals", ["originals", "/exchange/plutus-fixture/funding.cbor",
                                         "/exchange/plutus-fixture/funding-identity.json"], root / "funding-identity.json")
            base.require(identity.get("transactionId") == self.funded_txid and
                         identity.get("envelopeSHA256") == fixture.digest(original), "funding Scala original identity")
            base.require(live.process.same_tip(anchor, self.tip(1)) and live.process.same_tip(anchor, self.tip(2)),
                         "pre-funding construction bracket moved")
            # Restart before submission: stopping a producer after submit could
            # discard its volatile mempool. Node 2 remains keyless throughout.
            window_deadline(self.boundary_ms, 300, self.deadline, 15)
            self.stop_node(1)
            self.stopped.discard(1)
            self.start_node(1, True)
            self.funding_gate = fixture.ReferenceSubmissionGate(
                lambda *argv: super(AdaController, self).execute(*argv), self.magic,
                fixture.ROOT + "/socket/node1/sock")
            started = time.monotonic()
            window_deadline(self.boundary_ms, 300, self.deadline, 15)
            submitted = self.funding_gate.submit_funding(self.read_signed, fixture.digest(original))
            base.write(self.out / "funding-submit.json", dict(
                scope="reference-only-fixture-funding", returncode=submitted.returncode,
                elapsedSeconds=time.monotonic() - started, attempts=1,
                originalSHA256=fixture.digest(original), ledgerAcceptanceProven=False))
            until = window_deadline(self.boundary_ms, 300, self.deadline, 15)
            while time.monotonic() < until:
                utxo = base.decode(self.query_node(1, "utxo", "--whole-utxo", "--output-json"))
                if self.funded_txid + "#0" in utxo:
                    break
                time.sleep(0.2)
            else:
                raise TimeoutError("funding inclusion within 15 seconds")
            elapsed = time.monotonic() - started
            base.require(elapsed <= 15, "funding inclusion deadline")
            base.write(self.out / "funding-observation.json", dict(
                elapsedSeconds=elapsed, maximumSeconds=15, fundedInput=self.funded_txid + "#0",
                observedInWholeUtxo=True, completeLedgerComparisonProven=False))
            self.stop_node(1)
            self.stop_node(2)
            self.stopped.clear()
            self.start_node(1, False)
            self.start_node(2, False)
            until = window_deadline(self.boundary_ms, 300, self.deadline, 10)
            while time.monotonic() < until:
                a, b = self.tip(1), self.tip(2)
                if live.process.same_tip(a, b):
                    break
                time.sleep(0.2)
            base.require(live.process.same_tip(a, b), "post-funding keyless convergence")
            frozen_point(a)
            after = self.snapshot("funding-after", a)
            comparison = fixture.funding_comparison(before, after, self.plan, self.funded_txid, original, identity)
            self.funding_gate.seal_bootstrap(comparison)
            base.write(self.out / "funding-comparison.json", comparison)
            self.funding_sealed = True
            return a

        def construct_transfer(self):
            base.require(getattr(self, "funding_sealed", False), "sealed double funding before service bootstrap")
            root = self.exchange / "submission"
            root.mkdir(mode=0o700)
            before = self.tip(1)
            base.require(frozen_point(before) == self.initial, "two-spend construction anchor")
            self.transfers = []
            for pair, conflict in ((0, False), (1, False), (0, True)):
                name = "conflict-1" if conflict else "transaction-"+str(pair+1)
                commands = fixture.spend_commands(self.plan, self.funded_txid, self.initial, self.magic, pair, conflict)
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
            base.require(not getattr(self, "client_started", False), "single external two-spend client")
            self.client_started = True
            network = "container:" + self.containers["reference"]
            submission = self.exchange / "submission"
            client_classpath = checked_classpath(self.args.client_classpath_file, self.args.scala_build_root)
            args = ["--pull=never", "--network="+network, "--cpus=1", "--memory=2g", "--memory-swap=2g",
                    "--pids-limit=256", "--read-only", "--cap-drop=ALL", "--security-opt=no-new-privileges",
                    "--user", "1000:1000", "--tmpfs", "/tmp:rw,nosuid,nodev,size=64m",
                    "--mount", "type=bind,src="+str(self.args.scala_build_root)+",dst=/work,readonly",
                    "--mount", "type=bind,src="+str(self.exchange)+",dst=/exchange,readonly",
                    "--mount", "type=bind,src="+str(submission)+",dst=/exchange/submission",
                    "--entrypoint", self.args.java, self.args.scala_image, "-Xmx512m", "-XX:ActiveProcessorCount=1",
                    "-cp", client_classpath, *client_command(getattr(self.args, "client_mode", "direct"),
                    self.api_port, self.client_duration())]
            cid = self.create("ada-client", args)
            try:
                observed = self.owned(cid)
                base.check_resources(observed, "helper", self.args.scala_image, network)
                base.require({(m["Source"], m["Destination"], m["RW"]) for m in observed["Mounts"] if m["Type"] == "bind"} ==
                             {(str(self.args.scala_build_root), "/work", False), (str(self.exchange), "/exchange", False),
                              (str(submission), "/exchange/submission", True)}, "exact two-spend client mounts")
                base.write(self.out / "client-inspection.json", dict(containerId=cid, image=observed["Image"],
                           network=network, cpus=1, memoryBytes=2*base.GIB, mounts=observed["Mounts"]))
                timeout = min(self.client_duration()+5, self.deadline-time.monotonic())
                base.require(timeout > 0, "remaining external client deadline")
                try:
                    attached = self.docker("start", "--attach", cid, timeout=timeout)
                except ValueError as error:
                    # Resolve the exact exception class from the already pinned
                    # support function, rather than matching arbitrary messages.
                    failed_type = getattr(getattr(live, "bounded_process", None), "__globals__", {}).get("FailedProcess")
                    if isinstance(failed_type, type) and isinstance(error, failed_type):
                        try:
                            failure = retain_helper_failure(error, submission)
                            base.write(self.out / "client-helper-failure.json", failure)
                        except Exception as diagnostic_error:
                            try:
                                self.diagnostic_write("client-helper-failure-recording-error.json",
                                                      dict(errorType=type(diagnostic_error).__name__))
                            except Exception:
                                pass  # Never replace the original FailedProcess.
                    raise
                (self.out / "client-attach.log").write_text(attached.stdout+attached.stderr)
                state = self.owned(cid)["State"]
                base.require(not state["Running"] and state["ExitCode"] == 0 and not state["OOMKilled"], "two-spend HTTP client exit")
                self.client_receipt = client_result(base.decode(base.read(submission / "service-client-result.json", 65536)), self.transfers)
                base.write(self.out / "client-result.json", self.client_receipt)
                if getattr(self.args, "client_mode", "direct") == "sequential":
                    report_raw, checkpoints = sequential_report(submission, self.client_receipt)
                    with (self.out / "scenario-runner-result.json").open("xb") as stream:
                        stream.write(report_raw)
                    for index, raw in enumerate(checkpoints):
                        with (self.out / f"scenario-checkpoint-{index}.json").open("xb") as stream:
                            stream.write(raw)
                    self.scenario_evidence = dict(clientMode="sequential",
                        scenarioRunnerReportFile="scenario-runner-result.json",
                        scenarioRunnerReportSHA256=fixture.digest(report_raw),
                        scenarioCheckpointSHA256=[fixture.digest(raw) for raw in checkpoints])
                self.client_completed = True
            finally:
                self.remove_client()

        def compare_endpoint(self, manifest_pin):
            base.require(not self.owned(self.containers["scala"])["State"]["Running"] and self.client_completed,
                         "serial endpoint oracle after service and client stop")
            phase = "service-endpoint-oracle"
            base.require(phase not in self.containers, "one external endpoint oracle")
            args = ["--pull=never", "--network=none", "--cpus=1", "--memory=2g", "--memory-swap=2g",
                    "--pids-limit=256", "--read-only", "--cap-drop=ALL", "--security-opt=no-new-privileges",
                    "--user", "1000:1000", "--tmpfs", "/tmp:rw,nosuid,nodev,size=64m",
                    "--mount", "type=bind,src="+str(self.args.scala_build_root)+",dst=/work,readonly",
                    "--mount", "type=bind,src="+str(self.exchange)+",dst=/exchange",
                    "--entrypoint", self.args.java, self.args.scala_image, "-Xmx512m", "-XX:ActiveProcessorCount=1",
                    "-cp", checked_classpath(self.args.client_classpath_file, self.args.scala_build_root),
                    self.oracle_main(), "/exchange/initial", manifest_pin, "/exchange", "/exchange",
                    "/exchange/submission/transaction-1.cbor", "/exchange/submission/transaction-2.cbor",
                    "/exchange/service-comparison.json"]
            cid = self.create(phase, args)
            try:
                observed = self.owned(cid)
                base.check_resources(observed, "helper", self.args.scala_image, "none")
                base.require({(m["Source"], m["Destination"], m["RW"]) for m in observed["Mounts"] if m["Type"] == "bind"} ==
                             {(str(self.args.scala_build_root), "/work", False), (str(self.exchange), "/exchange", True)},
                             "exact external oracle mounts")
                base.write(self.out / "oracle-inspection.json", observed)
                timeout = min(35, self.deadline-time.monotonic())
                base.require(timeout > 0, "remaining external oracle deadline")
                attached = self.docker("start", "--attach", cid, timeout=timeout)
                (self.out / "oracle.log").write_text(attached.stdout+attached.stderr)
                state = self.owned(cid)["State"]
                base.require(not state["Running"] and state["ExitCode"] == 0 and not state["OOMKilled"], "external oracle exit")
                return base.decode(base.read(self.exchange / "service-comparison.json", 65536))
            finally:
                self.owned(cid)
                self.docker("rm", "--force", cid)
                base.require(not self.docker("ps", "-aq", "--no-trunc", "--filter", "id="+cid).stdout.strip(), "oracle absence")
                base.write(self.out / "oracle-cleanup.json", dict(containerId=cid, absenceVerified=True))

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
            self.start_scala(manifest_pin)
            ready = self.wait_file("bootstrap-ready.json", 25)
            base.require(ready.get("schema") == "plutus-service-ready-v1" and ready.get("initialPoint") == self.initial and
                         ready.get("profileId") == fixture.PROFILE and ready.get("initialEpoch") == 0 and
                         ready.get("networkMagic") == self.magic and ready.get("initialManifestSHA256") == manifest_pin and
                         base.hex64(ready.get("sourceJoinId")), "service bootstrap full source bindings")
            port = ready.get("apiPort")
            base.require(type(port) is int and 1 <= port <= 65535 and port not in live.process.PORTS.values(), "distinct service API port")
            self.api_port = port
            limits = ready.get("limits", {})
            base.require(limits.get("durationSeconds") == self.args.duration_seconds and limits.get("maxBlocks") == self.args.max_blocks and
                         limits.get("maxEvents") == 4096 and limits.get("maxEvaluationReceipts") == 128, "service readiness limits")
            deadline = self.readiness_deadline(ready)
            base.write(self.out / "plutus-bootstrap-proof.json", ready)
            self.stop_node(1)
            self.stop_node(2)
            self.stopped.clear()
            self.start_node(1, True)
            self.start_node(2, False)
            resumed = time.time_ns()//1000000
            base.require(resumed < deadline, "producer restart fits service startup deadline")
            base.write(self.exchange / "peer-ready.json", dict(schema="native-live-peer-ready-v1",
                       referenceContainerId=self.containers["reference"], generatedPort=live.process.PORTS[1],
                       networkMagic=self.magic, producerResumedUnixMillis=resumed))
            self.peer_started(ready, resumed)
            self.run_client()
            # The producer stays running: stopping/restarting it would close
            # the service's socket, and reconnect is outside this profile.
            result = self.wait_file("result.json", self.args.duration_seconds+10)
            base.require(result.get("schema") == "plutus-service-result-v1" and result.get("status") == "stopped" and
                         result.get("stopReason") in ("durationLimit", "blockLimit") and
                         result.get("profileId") == fixture.PROFILE and result.get("initialPoint") == self.initial and
                         result.get("sourceJoinId") == ready["sourceJoinId"] and result.get("initialManifestSHA256") == manifest_pin,
                         "bounded service terminal result")
            base.require(result.get("resourcesFinalized") is True and result.get("transportOpens") == result.get("transportCloses") and
                         type(result.get("transportOpens")) is int and result["transportOpens"] > 0 and
                         all(result.get(k) is False for k in ("transactionSuccessClaimed", "fullLedgerValidated", "restartSupported")),
                         "finalized service with restricted claims")
            terminal = self.terminal_interval(result, deadline, resumed)
            cid = self.containers["scala"]
            until = min(self.deadline, time.monotonic()+5)
            state = self.owned(cid)["State"]
            while state["Running"] and time.monotonic() < until:
                time.sleep(0.1)
                state = self.owned(cid)["State"]
            base.require(not state["Running"] and state["ExitCode"] == 0 and not state["OOMKilled"], "bounded service process completion")
            self.validate_publications(result, ready, manifest_pin)
            observation = self.terminal_observation(result, ready, manifest_pin)
            # Exact historical acquisition; capture rejects unavailable points.
            # Never query latest or freeze/restart after service shutdown.
            packet, acquisition = self.capture(terminal, "endpoint")
            base.write(self.exchange / "acquisition-result.json", acquisition)
            endpoint = self.exchange / "endpoint"
            shutil.copytree(packet, endpoint)
            result_pin = base.sha(self.exchange / "acquisition-result.json")
            descriptor = dict(schema="native-endpoint-reviewed-inputs-v1", point=terminal,
                              genesisSHA256=base.sha(initial / "effective-shelley-genesis.json"),
                              acquisitionResultSHA256=result_pin, inputs=base.manifest(endpoint, base.PACKET_NAMES))
            base.write(endpoint / "endpoint-inputs.json", descriptor)
            endpoint_ready = dict(schema="native-endpoint-ready-v1",
                                  manifestSHA256=base.sha(endpoint / "endpoint-inputs.json"), acquisitionResultSHA256=result_pin)
            base.write(self.exchange / "endpoint-ready.json", endpoint_ready)
            compared = self.validate_comparison(self.compare_endpoint(manifest_pin), terminal, ready, manifest_pin,
                                                result, endpoint_ready)
            base.require(compared.get("terminalObservationSHA256") == result["terminalObservationSHA256"] and
                         compared.get("outputMapSHA256") == observation.get("outputMapSHA256"), "oracle binds terminal originals")
            base.write(self.out / "endpoint-comparison.json", compared)
            refs = result.get("evaluationReceipts")
            base.require(isinstance(refs, list) and 2 <= len(refs) <= 128, "bounded final evaluation references")
            selected = set()
            for ordinal, (transfer, client) in enumerate(zip(self.transfers, self.client_receipt["transactions"]), 1):
                matches = []
                for ref in refs:
                    relative = ref.get("file")
                    base.require(isinstance(relative, str) and diagnostic.re.fullmatch(r"evaluation-receipts/plutus-evaluation-[0-9]{4}\.json", relative),
                                 "bounded service evaluation reference")
                    path = self.exchange / relative
                    base.require(path.resolve() == path and not path.parent.is_symlink(), "receipt no symlink traversal")
                    raw = base.read(path, 16384)
                    base.require(fixture.digest(raw) == ref.get("sha256"), "every referenced evaluation original pinned")
                    record = base.decode(raw)
                    if record.get("transactionId") == transfer["transactionId"] and record.get("phase") == "admission" and record.get("outcome") == "accepted" and record.get("newlyAdmitted") is True:
                        matches.append(ref)
                base.require(len(matches) == 1 and matches[0]["file"] not in selected, "unique independent accepted evaluation")
                selected.add(matches[0]["file"])
                shaped = dict(evaluationReceiptFile=matches[0]["file"], evaluationReceiptSHA256=matches[0]["sha256"], evaluationReceiptCount=len(refs))
                raw = diagnostic.evaluation_receipt(self.exchange, shaped, transfer, ready["sourceJoinId"], manifest_pin, client)
                with (self.out / ("evaluation-receipt-"+str(ordinal)+".json")).open("xb") as stream: stream.write(raw)
            base.write(self.out / "scala-result.json", result)
            self.stop_node(1)
            self.stop_node(2)
            self.cleanup()
            return dict(result, finalPoint=terminal)


    return ServiceController


def execute(live, support, args, classpath, *, controller=None, source=None):
    """Shared bounded supervisor; opt-in controllers retain the same owned cleanup."""
    source = Path(__file__) if source is None else Path(source)
    launch = (controller_type(live) if controller is None else controller)(support, args, classpath)
    operation = launch.operation_seconds
    base.require(type(operation) is int and 1 <= operation <= 300, "single-service operation hard cap")
    watchdog = live.DiskWatchdog(args.owned_root)
    previous = {}
    def interrupted(signum, _frame):
        if launch.in_cleanup and signum != signal.SIGALRM:
            return
        raise TimeoutError("bounded native operation interrupted")
    started = time.monotonic()
    try:
        previous = {s: signal.signal(s, interrupted) for s in (signal.SIGALRM, signal.SIGTERM, signal.SIGINT, signal.SIGUSR1)}
        signal.alarm(operation)
        watchdog.start()
        base.write(launch.out / "invocation.json", dict(schema=launch.invocation_schema,
                   supportManifestSHA256=args.support_sha, controllerSHA256=base.sha(source),
                   serviceControllerSHA256=base.sha(Path(__file__)),
                   baseControllerSHA256=base.sha(Path(base.__file__)),
                   diagnosticControllerSHA256=base.sha(Path(diagnostic.__file__)),
                   singleFixtureSHA256=base.sha(Path(fixture.one.__file__)), resources=base.RESOURCES,
                   projectionSHA256=base.PROJECTION_SHA, scalaImage=args.scala_image,
                   scalaClasspathSHA256=base.sha(args.scala_classpath_file),
                   clientClasspathSHA256=base.sha(args.client_classpath_file), runtimeEntrypoint="lab.Main plutus-service",
                   scalaBuildRoot=str(args.scala_build_root),
                   operationSeconds=operation, cleanupSeconds=30, profileId=fixture.PROFILE,
                   cliSubmissionAllowed="one-reference-funding-before-bootstrap-only",
                   testedSpendCliSubmissionAllowed=False, fixturePlannerSHA256=base.sha(Path(fixture.__file__)),
                   adaControllerSHA256=base.sha(Path(ada.__file__)),
                   network="reference-none;scala-and-http-client-exact-reference-namespace;capture-none", **launch.mode_evidence()))
        base.require(launch.docker("image", "inspect", args.scala_image, "--format", "{{.Id}}").stdout.strip() == args.scala_image,
                     "Scala image pin")
        ids = dict(generatorSHA256=live.z.GENERATOR_SHA, launcherSHA256=base.sha(source),
                   imageId=live.z.REFERENCE_IMAGE, sourceCommit=support["sourceCommit"])
        result, receipt = live.controller.execute(args.owned_root, ids, launch.generate, launch.lifecycle, on_abort=launch.cleanup)
        base.write(launch.out / "controller-lifecycle.json", receipt)
        base.require(launch.cleaned and watchdog.failure is None, "owned cleanup/watchdog")
        base.require(time.monotonic() - started <= operation+30, "total deadline")
        base.write(launch.out / "result.json", dict(schema=launch.result_schema, passed=True,
                   exchangeRoot=str(launch.exchange), cleanupVerified=True, elapsedSeconds=time.monotonic() - started,
                   initialPoint=result["initialPoint"], finalPoint=result["finalPoint"], transactionIds=[t["transactionId"] for t in launch.transfers],
                   scalaResultSHA256=base.sha(launch.out / "scala-result.json"),
                   clientResultSHA256=base.sha(launch.out / "client-result.json"),
                   profileId=fixture.PROFILE, fundingComparisonSHA256=base.sha(launch.out / "funding-comparison.json"),
                   bootstrapProofSHA256=base.sha(launch.out / "plutus-bootstrap-proof.json"),
                   endpointComparisonSHA256=base.sha(launch.out / "endpoint-comparison.json"),
                   evaluationReceiptSHA256=[base.sha(launch.out / ("evaluation-receipt-"+str(i)+".json")) for i in (1,2)], runtimeEntrypoint="lab.Main plutus-service",
                   runtimeClasspathSHA256=base.sha(args.scala_classpath_file),
                   clientClasspathSHA256=base.sha(args.client_classpath_file), **getattr(launch, "scenario_evidence", {}),
                   **launch.mode_evidence()))
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
        parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument("--support-sha", required=True)
    parser.add_argument("--scala-image", required=True)
    parser.add_argument("--java", default="java")
    parser.add_argument("--duration-seconds", type=int, default=30)
    parser.add_argument("--max-blocks", type=int, default=128)
    parser.add_argument("--client-mode", choices=("direct", "sequential"), default="direct")
    parser.add_argument("--execute", action="store_true")
    args = parser.parse_args()
    service_limits(args.duration_seconds, args.max_blocks)
    client_command(args.client_mode, 1, args.duration_seconds)
    live, support, classpath = base.preflight(args)
    base.require(classpath == checked_classpath(args.scala_classpath_file, args.scala_build_root, True),
                 "Compile-only runtime classpath")
    checked_classpath(args.client_classpath_file, args.scala_build_root)
    if args.execute:
        execute(live, support, args, classpath)
    else:
        print(base.json.dumps(dict(preflight=True, executed=False, resources=base.RESOURCES,
                              profileId=fixture.PROFILE,
                              cliSubmissionAllowed="one-reference-funding-before-bootstrap-only",
                              testedSpendCliSubmissionAllowed=False), sort_keys=True))


if __name__ == "__main__":
    main()
