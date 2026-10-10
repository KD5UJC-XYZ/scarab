#!/usr/bin/env python3
"""Bounded Plutus V3 HTTP ingress fixture; preflight unless --execute.

Reference submission is restricted to one funding transaction before the native
bootstrap. The tested spend travels only through Scala HTTP and TxSubmission2.
"""
import argparse
from pathlib import Path
import signal
import shutil
import os
import re
import hashlib
import time

import private_cluster_ada_submission as ada
import private_cluster_native_boundary as base
import plutus_submission_fixture as fixture


def frozen_point(tip, ceiling=300):
    base.require(tip.get("era") == "Conway" and tip.get("epoch") == 0 and
                 type(tip.get("slot")) is int and 0 < tip["slot"] < ceiling,
                 "bounded frozen Conway epoch-zero point")
    base.require(type(tip.get("block")) is int and 0 <= tip["block"] < 2 ** 64,
                 "real bounded block number, including first block zero")
    # Block number zero with a positive slot and full hash is a real first block,
    # not chain origin. Funding must advance it before the shared capture path.
    return fixture.point(dict(slot=tip["slot"], blockNo=tip["block"], hash=tip.get("hash")))



def window_deadline(boundary_ms, slot, operation_deadline, maximum_seconds):
    """Convert a genesis-derived slot deadline to the current monotonic clock.

    future_boundary has already checked the fixed 1000-slot/100ms geometry.
    Controller startup time cannot extend this window.
    """
    base.require(type(boundary_ms) is int and slot in (100, 300), "checked local slot window")
    remaining = (boundary_ms - 100000 + slot * 100 - time.time_ns() // 1000000) / 1000
    if remaining <= 0:
        raise TimeoutError(f"Plutus slot-{slot} preparation window missed")
    now = time.monotonic()
    until = min(operation_deadline, now + maximum_seconds, now + remaining)
    if until <= now:
        raise TimeoutError("Plutus operation budget exhausted before preparation")
    return until


def prefunding_point(tip):
    """A late first block is a missed window, not a convergence failure."""
    if type(tip.get("slot")) is int and tip["slot"] >= 100:
        raise TimeoutError("Plutus pre-funding slot-100 window missed")
    return frozen_point(tip, 100)


def plutus_receipt(value):
    base.require(value.get("profileId") == fixture.PROFILE, "explicit Plutus profile receipt")
    return value



def same_epoch_request(value, initial, join_id, boundary_ms):
    base.require(value.get("schema") == "native-live-endpoint-request-v1", "endpoint request schema")
    terminal = base.point(value["point"])
    base.require(value.get("epoch") == 0 and initial["slot"] < terminal["slot"] < 1000 and
                 0 < terminal["blockNo"] - initial["blockNo"] <= 128, "bounded same-epoch successor")
    base.require(value.get("sourceJoinId") == join_id and base.hex64(value.get("networkAppliedStateId")), "endpoint owner bindings")
    base.require(value.get("followedAcrossBoundary") is False and value.get("sameEpoch") is True and
                 all(value.get(k) is True for k in ("peerClosed", "transportClosed")), "same-epoch finalized stream")
    start, end = value.get("streamStartedUnixMillis"), value.get("streamEndedUnixMillis")
    base.require(type(start) is int and type(end) is int and start <= end < boundary_ms and
                 0 <= end - start <= 65000, "same-epoch bounded stream")
    return terminal



def research_cli_tail(tail):
    tail = list(tail)
    index = tail.index("lab.NativeLiveBoundaryMain")
    original = tail[index + 1:]
    base.require(len(original) == 5, "exact isolated research launcher arguments")
    initial, manifest_pin, port, magic, exchange = original
    return tail[:index] + ["lab.Main", "plutus-research", "--profile", fixture.PROFILE,
            "--initial", initial, "--manifest-sha256", manifest_pin, "--port", port,
            "--magic", magic, "--exchange", exchange]


def checked_classpath(path, build_root, compile_only=False):
    value = base.read(path, 65536).decode().strip()
    components = value.split(":")
    base.require(components and all(x.startswith("/work/") and
                 ".." not in Path(x).parts and "\n" not in x and "\r" not in x and
                 (build_root / x[len("/work/"):]).exists() for x in components),
                 "classpath must resolve under read-only build")
    if compile_only:
        base.require(all(not any(part in ("test", "test-classes", "test-resources")
                                 for part in Path(x).parts) for x in components),
                     "runtime classpath must exclude Test classes/resources")
    return value



def evaluation_receipt(exchange, result, transfer, source_join_id, manifest_pin, client):
    relative = result.get("evaluationReceiptFile")
    base.require(isinstance(relative, str) and
                 re.fullmatch(r"evaluation-receipts/plutus-evaluation-[0-9]{4}\.json", relative),
                 "bounded evaluation receipt relative path")
    root = Path(exchange).resolve()
    path = root / relative
    base.require(path.resolve() == path and not path.parent.is_symlink(), "evaluation receipt has no symlink traversal")
    raw = base.read(path, 16384)
    pin = hashlib.sha256(raw).hexdigest()
    base.require(base.hex64(result.get("evaluationReceiptSHA256")) and result["evaluationReceiptSHA256"] == pin,
                 "final result pins original evaluation receipt bytes")
    value = base.decode(raw)
    base.require(value.get("schema") == "plutus-evaluation-evidence-v1" and
                 value.get("phase") == "admission" and value.get("outcome") == "accepted" and
                 value.get("newlyAdmitted") is True, "newly accepted HTTP admission evaluation")
    index = value.get("eventIndex")
    count = result.get("evaluationReceiptCount")
    base.require(type(count) is int and 1 <= count <= 128 and
                 type(index) is int and 0 <= index < count and
                 relative == f"evaluation-receipts/plutus-evaluation-{index:04d}.json", "receipt event identity")
    base.require(value.get("sourceJoinId") == source_join_id and
                 value.get("initialManifestSHA256") == manifest_pin, "evaluation bootstrap source bindings")
    for name in ("transactionId", "envelopeSHA256", "bodySHA256", "witnessesSHA256"):
        base.require(base.hex64(value.get(name)) and value[name] == transfer[name], "evaluation original binding: " + name)
    for name in ("requestDigest", "contextSHA256", "scriptSHA256", "modelSHA256"):
        base.require(base.hex64(value.get(name)), "evaluation hash: " + name)
    base.require(value["scriptSHA256"] == fixture.SCRIPT_SHA and
                 value["modelSHA256"] == "6ab455d588e186649a6aae2761fec85ae5a2647cb002a2acb737604f698b21a2",
                 "registered script and model evidence")
    base.require(value.get("evaluator") == "Scalus" and value.get("evaluatorVersion") == "1.3.0" and
                 value.get("language") == "PlutusV3" and value.get("semantics") == "C" and
                 type(value.get("protocolMajor")) is int and value["protocolMajor"] == 9,
                 "fixed evaluator semantics evidence")
    declared, consumed = value.get("declared"), value.get("consumed")
    base.require(isinstance(declared, dict) and isinstance(consumed, dict) and
                 set(declared) == set(consumed) == {"memory", "steps"}, "explicit execution-unit dimensions")
    for name, maximum in (("memory", 100000), ("steps", 30000000)):
        base.require(type(declared[name]) is int and declared[name] == maximum and
                     type(consumed[name]) is int and 0 <= consumed[name] <= maximum, "bounded execution units: " + name)
    state = value.get("statePin")
    base.require(isinstance(state, dict) and state.get("profileId") == fixture.PROFILE, "profile-bound state pin")
    base.require(base.hex64(state.get("ownerId")), "32-byte admission owner")
    for name in ("generation", "validationSlot"):
        base.require(type(state.get(name)) is int and 0 <= state[name] < 2 ** 64, "state pin integer: " + name)
    for name in ("coherentStateId", "ledgerStateId", "environmentId"):
        base.require(base.hex64(state.get(name)), "state pin hash: " + name)
    base.point(state.get("point"))
    base.require(state["validationSlot"] == state["point"]["slot"], "admission validation slot equals published point")
    accepted = client.get("acceptedResponse")
    base.require(isinstance(accepted, dict) and accepted.get("code") == "Accepted" and
                 accepted.get("profileId") == fixture.PROFILE, "actual accepted HTTP response")
    accepted_receipt = accepted.get("receipt")
    base.require(isinstance(accepted_receipt, dict) and
                 accepted_receipt.get("transactionId") == transfer["transactionId"] and
                 accepted_receipt.get("envelopeSHA256") == transfer["envelopeSHA256"] and
                 accepted_receipt.get("pin") == state, "exact accepted HTTP original and full state pin")
    base.require(all(value.get(name) is False for name in
                 ("fullLedgerValidated", "inclusionClaimed", "currentEligibilityClaimed")), "evaluation evidence scope")
    return raw


def controller_type(live):
    AdaController = ada.controller_type(live)

    class PlutusController(AdaController):
        def create(self, phase, tail):
            if phase == "scala":
                tail = research_cli_tail(tail)
            if phase == "ada-client":
                tail = [*tail, fixture.PROFILE]
            return super().create(phase, tail)

        def proof_helper(self, phase, arguments, output):
            base.require(phase in ("native-originals", "plutus-spend-originals") and
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

        def snapshot(self, phase, anchor):
            frozen_point(anchor)
            roles = self.roles()
            base.require(set(roles) == {"1", "2"}, "both owned keyless nodes required")
            utxo_raw = self.query_node(1, "utxo", "--whole-utxo", "--output-json")
            ledger_raw = self.query_node(1, "ledger-state", "--output-json")
            base.require(roles == self.roles() and live.process.same_tip(anchor, self.tip(1)) and
                         live.process.same_tip(anchor, self.tip(2)), "frozen separate query bracket changed")
            (self.out / (phase + "-utxo.json")).write_text(utxo_raw)
            (self.out / (phase + "-ledger.json")).write_text(ledger_raw)
            value = fixture.Snapshot(frozen_point(anchor), base.decode(utxo_raw),
                                    base.decode(ledger_raw)["stateBefore"]["esLState"]["utxoState"]["fees"]).checked()
            base.write(self.out / (phase + "-bracket.json"), dict(point=value.full_point, roles=roles,
                       separateAcquisitions=True, atomicSnapshot=False))
            return value

        def read_signed(self, path):
            return ada.signed_bytes(base.decode(self.execute("head", "-c", "131073", "--", path).stdout))

        def retain_signed(self, source, destination):
            raw = self.execute("head", "-c", "131073", "--", source).stdout.encode()
            base.require(0 < len(raw) <= 131072, "bounded signed JSON envelope")
            original = ada.signed_bytes(base.decode(raw))
            with destination.open("xb") as stream:
                stream.write(raw)
            return original

        def prepare_initial(self, anchor):
            prefunding_point(anchor)
            original_deadline = self.deadline
            self.deadline = window_deadline(self.boundary_ms, 300, original_deadline, 30)
            try:
                result = self.prepare_funding(anchor)
                window_deadline(self.boundary_ms, 300, self.deadline, 30)
                return result
            finally:
                self.deadline = original_deadline

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
            self.plan = fixture.funding_plan(before.utxo, source_address, script_address, beneficiary, beneficiary, self.script["datum"])
            self.commands = fixture.commands(self.plan, self.magic, fixture.ROOT + "/keys/utxo.skey")
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
            base.require(getattr(self, "funding_sealed", False), "confirmed sealed funding before bootstrap")
            root = self.exchange / "submission"
            root.mkdir(mode=0o700)
            before = self.tip(1)
            base.require(frozen_point(before) == self.initial, "native spend construction anchor")
            self.execute(*fixture.spend_command(self.plan, self.funded_txid, self.initial))
            self.execute(*self.commands["spendSign"])
            raw = self.retain_signed(fixture.SPEND, root / "transaction.signed.json")
            txid = self.execute("cardano-cli", "conway", "transaction", "txid", "--tx-file", fixture.SPEND,
                                "--output-text").stdout.strip()
            base.require(base.hex64(txid) and live.process.same_tip(before, self.tip(1)) and
                         live.process.same_tip(before, self.tip(2)), "native spend original point bracket")
            (root / "transaction.cbor").write_bytes(raw)
            self.transfer = dict(schema="ada-submission-transaction-v1", profileId=fixture.PROFILE,
                                 transactionId=txid, envelopeSHA256=fixture.digest(raw), bytes=len(raw),
                                 validityLowerBound=self.initial["slot"], validityUpperBound=999,
                                 initialPoint=self.initial, networkMagic=self.magic, cliSubmitted=False,
                                 input=self.funded_txid + "#0", amount=19700000, fee=fixture.FEE)
            proof = self.proof_helper("plutus-spend-originals", ["originals", "/exchange/submission/transaction.cbor",
                                     "/exchange/submission/identity.json"], root / "identity.json")
            base.require(proof.get("transactionId") == txid and proof.get("envelopeSHA256") == fixture.digest(raw),
                         "tested spend original identity")
            self.transfer.update(bodySHA256=proof["bodySHA256"], witnessesSHA256=proof["witnessesSHA256"],
                                 spentInput=self.funded_txid + "#0", collateralInput=self.funded_txid + "#1")
            # Descriptor is published once, after all original identity bindings.
            base.write(root / "descriptor.json", self.transfer)

        def run_client(self):
            # The independent external client remains a Test helper. Never add
            # its classes to the Compile-only runtime/originals process.
            runtime_classpath = self.classpath
            self.classpath = checked_classpath(self.args.client_classpath_file, self.args.scala_build_root)
            try:
                super().run_client()
                plutus_receipt(self.client_receipt)
            finally:
                self.classpath = runtime_classpath

        def wait_file(self, name, seconds):
            value = super().wait_file(name, seconds)
            if name in ("bootstrap-ready.json", "result.json"):
                plutus_receipt(value)
            if name == "result.json":
                base.require(value.get("plutusSubmission") is True, "Plutus final inclusion claim")
            return value

        def lifecycle(self, approval):
            root = self.args.owned_root
            config = self.environment / "configuration.yaml"
            base.require(root.is_absolute() and root.resolve() == root and
                         self.environment == root / "environment" and self.environment.resolve() == self.environment and
                         config.resolve() == config and root in config.parents,
                         "configuration must belong to the fresh owned fixture")
            base.require(not self.active, "configuration override before node startup")
            original = base.read(config)
            updated, receipt = ada.immediate_tx_submission(original)
            preserved = self.out / "original-node-configuration.json"
            with preserved.open("xb") as stream:
                stream.write(original)
                stream.flush()
                os.fsync(stream.fileno())
            pending = config.with_name("configuration.ada.pending")
            with pending.open("xb") as stream:
                stream.write(updated)
                stream.flush()
                os.fsync(stream.fileno())
            base.require(base.read(config) == original, "configuration changed before fixture override")
            pending.replace(config)
            base.require(base.read(config) == updated and base.read(preserved) == original,
                         "configuration override and preservation verification")
            base.write(self.out / "txsubmission-init-delay.json", dict(receipt,
                       configurationPath=str(config), originalConfigurationPath=str(preserved)))
            # The base reads self.configuration from these exact bytes and its
            # owned process checks continue to enforce configuration identity.
            return self.same_epoch_lifecycle(approval)

        def select_prefunding(self):
            original_deadline = self.deadline
            self.deadline = window_deadline(self.boundary_ms, 100, original_deadline - 150, 90)
            try:
                while time.monotonic() < self.deadline:
                    a, b = self.tip(1), self.tip(2)
                    for observed in (a, b):
                        if type(observed.get("slot")) is int and observed["slot"] >= 100:
                            raise TimeoutError("Plutus pre-funding slot-100 window missed")
                    # Slow queries may return an old tip after the actual window.
                    window_deadline(self.boundary_ms, 100, self.deadline, 90)
                    if live.process.same_tip(a, b) and a.get("era") == "Conway" and a.get("epoch") == 0 and 0 < a.get("slot", 0) < 100 and type(a.get("block")) is int and a["block"] >= 0:
                        prefunding_point(a)
                        prefunding_point(b)
                        return a, b
                    time.sleep(0.2)
                raise TimeoutError("Plutus pre-funding slot-100 window missed without a common point")
            finally:
                self.deadline = original_deadline

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
            self.start_scala(base.sha(initial / "adapter-inputs.json"))
            ready = self.wait_file("bootstrap-ready.json", 25)
            base.require(ready.get("schema") == "native-live-bootstrap-ready-v1" and base.point(ready["point"]) == self.initial,
                    "bootstrap full point")
            base.require(base.hex64(ready.get("sourceJoinId")) and ready.get("boundaryUnixMillis") == self.boundary_ms and
                    ready.get("networkMagic") == self.magic, "bootstrap geometry/bindings")
            base.require(ready.get("plutusDatumMemPackMatched") is True and
                         ready.get("fundedInput") == self.funded_txid + "#0" and
                         ready.get("collateralInput") == self.funded_txid + "#1", "actual datum MemPack bootstrap proof")
            base.write(self.out / "plutus-bootstrap-proof.json", ready)
            self.stop_node(1)
            self.stop_node(2)
            self.stopped.clear()
            base.require(time.time_ns() // 1000000 < self.boundary_ms - 5000, "producer restart needs preboundary time")
            # Keep one active producer after bootstrap so this explicitly
            # monotonic diagnostic does not claim fork/rollback support.
            self.start_node(1, True)
            self.start_node(2, False)
            resumed = time.time_ns() // 1000000
            base.require(resumed < self.boundary_ms, "producer restart missed epoch boundary")
            base.write(self.exchange / "peer-ready.json", dict(schema="native-live-peer-ready-v1",
                  referenceContainerId=self.containers["reference"], generatedPort=live.process.PORTS[1],
                  networkMagic=self.magic, producerResumedUnixMillis=resumed))
            request = self.wait_file("endpoint-request.json", 125)
            terminal = same_epoch_request(request, self.initial, ready["sourceJoinId"], self.boundary_ms)
            # Acquire the exact requested historical state immediately from the
            # running node. Never restart/fallback to latest on AcquireFailure.
            packet, acquisition = self.capture(terminal, "endpoint")
            base.write(self.exchange / "acquisition-result.json", acquisition)
            endpoint = self.exchange / "endpoint"
            shutil.copytree(packet, endpoint)
            result_pin = base.sha(self.exchange / "acquisition-result.json")
            descriptor = dict(schema="native-endpoint-reviewed-inputs-v1", point=terminal,
                              genesisSHA256=base.sha(initial / "effective-shelley-genesis.json"),
                              acquisitionResultSHA256=result_pin, inputs=base.manifest(endpoint, base.PACKET_NAMES))
            base.write(endpoint / "endpoint-inputs.json", descriptor)
            base.write(self.exchange / "endpoint-ready.json", dict(schema="native-endpoint-ready-v1",
                  manifestSHA256=base.sha(endpoint / "endpoint-inputs.json"), acquisitionResultSHA256=result_pin))
            result = self.wait_file("result.json", 30)
            base.require(result.get("schema") == "plutus-live-same-epoch-result-v1" and result.get("passed") is True and
                    result.get("initialPoint") == self.initial and result.get("finalPoint") == terminal and
                    result.get("sourceJoinId") == ready["sourceJoinId"] and result.get("networkAppliedStateId") == request["networkAppliedStateId"], "final live result binding")
            base.require(all(result.get(k) is True for k in ("resourcesFinalized", "sameEpoch", "endpointCompared",
                         "wholeUtxoEqual", "instantaneousStakeEqual", "collateralPreserved")), "final result claims")
            base.require(result.get("spentInput") == self.transfer["spentInput"] and
                         result.get("collateralInput") == self.transfer["collateralInput"] and
                         result.get("feeDelta") == fixture.FEE and result.get("governanceCompared") is False,
                         "same-epoch spend, collateral and exact fee bindings")
            base.require(all(result.get(k) is False for k in ("fullLedgerValidated", "nativeConformance", "runtimeImport", "livePulserCursorEqual")), "restricted scope")
            original_receipt = evaluation_receipt(self.exchange, result, self.transfer,
                                                   ready["sourceJoinId"], base.sha(initial / "adapter-inputs.json"),
                                                   self.client_receipt)
            with (self.out / "evaluation-receipt.json").open("xb") as stream:
                stream.write(original_receipt)
            cid = self.containers["scala"]
            until = min(self.deadline, time.monotonic() + 5)
            while time.monotonic() < until:
                state = self.owned(cid)["State"]
                if not state["Running"]:
                    break
                time.sleep(0.1)
            base.require(not state["Running"] and state["ExitCode"] == 0 and not state["OOMKilled"], "Scala process completion")
            base.write(self.out / "scala-result.json", result)
            self.stop_node(1)
            self.stop_node(2)
            self.cleanup()
            return result

    return PlutusController


def execute(live, support, args, classpath):
    launch = controller_type(live)(support, args, classpath)
    watchdog = live.DiskWatchdog(args.owned_root)
    previous = {}
    def interrupted(signum, _frame):
        if launch.in_cleanup and signum != signal.SIGALRM:
            return
        raise TimeoutError("bounded native operation interrupted")
    started = time.monotonic()
    try:
        previous = {s: signal.signal(s, interrupted) for s in (signal.SIGALRM, signal.SIGTERM, signal.SIGINT, signal.SIGUSR1)}
        signal.alarm(240)
        watchdog.start()
        base.write(launch.out / "invocation.json", dict(schema="plutus-submission-controller-invocation-v1",
                   supportManifestSHA256=args.support_sha, controllerSHA256=base.sha(Path(__file__)),
                   baseControllerSHA256=base.sha(Path(base.__file__)), resources=base.RESOURCES,
                   projectionSHA256=base.PROJECTION_SHA, scalaImage=args.scala_image,
                   scalaClasspathSHA256=base.sha(args.scala_classpath_file),
                   clientClasspathSHA256=base.sha(args.client_classpath_file), runtimeEntrypoint="lab.Main plutus-research",
                   scalaBuildRoot=str(args.scala_build_root),
                   operationSeconds=240, cleanupSeconds=30, profileId=fixture.PROFILE,
                   cliSubmissionAllowed="one-reference-funding-before-bootstrap-only",
                   testedSpendCliSubmissionAllowed=False, fixturePlannerSHA256=base.sha(Path(fixture.__file__)),
                   adaControllerSHA256=base.sha(Path(ada.__file__)),
                   network="reference-none;scala-and-http-client-exact-reference-namespace;capture-none"))
        base.require(launch.docker("image", "inspect", args.scala_image, "--format", "{{.Id}}").stdout.strip() == args.scala_image,
                     "Scala image pin")
        ids = dict(generatorSHA256=live.z.GENERATOR_SHA, launcherSHA256=base.sha(Path(__file__)),
                   imageId=live.z.REFERENCE_IMAGE, sourceCommit=support["sourceCommit"])
        result, receipt = live.controller.execute(args.owned_root, ids, launch.generate, launch.lifecycle, on_abort=launch.cleanup)
        base.write(launch.out / "controller-lifecycle.json", receipt)
        base.require(launch.cleaned and watchdog.failure is None, "owned cleanup/watchdog")
        base.require(time.monotonic() - started <= 270, "total deadline")
        base.write(launch.out / "result.json", dict(schema="plutus-submission-controller-result-v1", passed=True,
                   exchangeRoot=str(launch.exchange), cleanupVerified=True, elapsedSeconds=time.monotonic() - started,
                   initialPoint=result["initialPoint"], finalPoint=result["finalPoint"], transactionId=launch.transfer["transactionId"],
                   scalaResultSHA256=base.sha(launch.out / "scala-result.json"),
                   clientResultSHA256=base.sha(launch.out / "client-result.json"),
                   profileId=fixture.PROFILE, fundingComparisonSHA256=base.sha(launch.out / "funding-comparison.json"),
                   bootstrapProofSHA256=base.sha(launch.out / "plutus-bootstrap-proof.json"),
                   evaluationReceiptSHA256=base.sha(launch.out / "evaluation-receipt.json"),
                   evaluationReceiptFile=result["evaluationReceiptFile"], runtimeEntrypoint="lab.Main plutus-research",
                   runtimeClasspathSHA256=base.sha(args.scala_classpath_file),
                   clientClasspathSHA256=base.sha(args.client_classpath_file)))
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
    parser.add_argument("--execute", action="store_true")
    args = parser.parse_args()
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
