#!/usr/bin/env python3
"""Bounded native-script HTTP ingress fixture; preflight unless --execute.

Reference submission is restricted to one funding transaction before the native
bootstrap. The tested spend travels only through Scala HTTP and TxSubmission2.
"""
import argparse
from pathlib import Path
import signal
import time

import private_cluster_ada_submission as ada
import private_cluster_native_boundary as base
import native_script_submission_fixture as fixture


def frozen_point(tip, ceiling=300):
    base.require(tip.get("era") == "Conway" and tip.get("epoch") == 0 and
                 type(tip.get("slot")) is int and 0 < tip["slot"] < ceiling,
                 "bounded frozen Conway epoch-zero point")
    return base.point(dict(slot=tip["slot"], blockNo=tip["block"], hash=tip["hash"]))


def native_receipt(value):
    base.require(value.get("profileId") == fixture.PROFILE, "explicit native profile receipt")
    return value


def controller_type(live):
    AdaController = ada.controller_type(live)

    class NativeController(AdaController):
        def create(self, phase, tail):
            if phase in ("scala", "ada-client"):
                tail = [*tail, fixture.PROFILE]
            return super().create(phase, tail)

        def proof_helper(self, phase, arguments, output):
            base.require(phase in ("native-originals", "native-bootstrap") and
                         phase not in self.containers and "scala" not in self.containers,
                         "serial fixture proof before Scala startup")
            args = ["--pull=never", "--network=none", "--cpus=1", "--memory=2g", "--memory-swap=2g",
                    "--pids-limit=256", "--read-only", "--cap-drop=ALL", "--security-opt=no-new-privileges",
                    "--user", "1000:1000", "--tmpfs", "/tmp:rw,nosuid,nodev,size=64m",
                    "--mount", "type=bind,src=" + str(self.args.scala_build_root) + ",dst=/work,readonly",
                    "--mount", "type=bind,src=" + str(self.exchange) + ",dst=/exchange",
                    "--entrypoint", self.args.java, self.args.scala_image, "-Xmx512m", "-XX:ActiveProcessorCount=1",
                    "-cp", self.classpath, "lab.NativeScriptFixtureMain", *arguments]
            cid = self.create(phase, args)
            try:
                observed = self.owned(cid)
                base.check_resources(observed, "helper", self.args.scala_image, "none")
                base.require({(m["Source"], m["Destination"], m["RW"]) for m in observed["Mounts"] if m["Type"] == "bind"} ==
                             {(str(self.args.scala_build_root), "/work", False), (str(self.exchange), "/exchange", True)},
                             "exact fixture proof mounts")
                base.write(self.out / (phase + "-inspection.json"), observed)
                result = self.docker("start", "--attach", cid, timeout=35)
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
            frozen_point(anchor, 100)
            root = self.exchange / "native-fixture"
            root.mkdir(mode=0o700)
            before = self.snapshot("funding-before", anchor)
            keyroot = self.environment / "utxo-keys"
            addresses = [self.execute("cardano-cli", "address", "build", "--payment-verification-key-file",
                         str(keyroot / ("utxo" + str(i)) / "utxo.vkey"), "--testnet-magic", str(self.magic)).stdout.strip()
                         for i in (1, 2)]
            keyhash = self.execute("cardano-cli", "address", "key-hash", "--payment-verification-key-file",
                                   str(keyroot / "utxo1/utxo.vkey")).stdout.strip()
            self.script = fixture.script_fixture(keyhash)
            base.write(root / "script.json", self.script["json"])
            (root / "script.cbor").write_bytes(bytes.fromhex(self.script["originalCborHex"]))
            base.write(self.out / "script-fixture.json", self.script)
            self.execute("mkdir", "-p", fixture.ROOT + "/keys")
            self.execute("cp", "--", str(keyroot / "utxo1/utxo.skey"), fixture.ROOT + "/keys/utxo.skey")
            self.execute("ln", "-s", str(self.environment / "socket"), fixture.ROOT + "/socket")
            self.owned(self.containers["reference"])
            self.docker("exec", "-i", self.containers["reference"], "tee", fixture.SCRIPT,
                        data=base.read(root / "script.json", 16384))
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
            self.plan = fixture.funding_plan(before.utxo, addresses[0], script_address, addresses[1])
            self.commands = fixture.commands(self.plan, self.magic, fixture.ROOT + "/keys/utxo.skey")
            self.execute(*self.commands["fundingBuild"])
            self.execute(*self.commands["fundingSign"])
            original = self.retain_signed(fixture.FUNDING, root / "funding.signed.json")
            (root / "funding.cbor").write_bytes(original)
            self.funded_txid = self.execute("cardano-cli", "conway", "transaction", "txid", "--tx-file",
                                           fixture.FUNDING, "--output-text").stdout.strip()
            identity = self.proof_helper("native-originals", ["originals", "/exchange/native-fixture/funding.cbor",
                                         "/exchange/native-fixture/funding-identity.json"], root / "funding-identity.json")
            base.require(identity.get("transactionId") == self.funded_txid and
                         identity.get("envelopeSHA256") == fixture.digest(original), "funding Scala original identity")
            base.require(live.process.same_tip(anchor, self.tip(1)) and live.process.same_tip(anchor, self.tip(2)),
                         "pre-funding construction bracket moved")
            # Restart before submission: stopping a producer after submit could
            # discard its volatile mempool. Node 2 remains keyless throughout.
            self.stop_node(1)
            self.stopped.discard(1)
            self.start_node(1, True)
            self.funding_gate = fixture.ReferenceSubmissionGate(
                lambda *argv: super(AdaController, self).execute(*argv), self.magic,
                fixture.ROOT + "/socket/node1/sock")
            started = time.monotonic()
            submitted = self.funding_gate.submit_funding(self.read_signed, fixture.digest(original))
            base.write(self.out / "funding-submit.json", dict(
                scope="reference-only-fixture-funding", returncode=submitted.returncode,
                elapsedSeconds=time.monotonic() - started, attempts=1,
                originalSHA256=fixture.digest(original), ledgerAcceptanceProven=False))
            until = min(self.deadline - 150, started + 15)
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
            until = min(self.deadline - 150, time.monotonic() + 10)
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
                                 validityLowerBound=self.initial["slot"], validityUpperBound=2000,
                                 initialPoint=self.initial, networkMagic=self.magic, cliSubmitted=False,
                                 input=self.funded_txid + "#0", amount=10000000, change=9800000, fee=fixture.FEE)
            base.write(root / "descriptor.json", self.transfer)
            proof = self.proof_helper("native-bootstrap", ["bootstrap", "/exchange/initial",
                                      "/exchange/native-fixture/script.cbor", self.funded_txid, "0", str(fixture.AMOUNT),
                                      "/exchange/native-fixture/bootstrap-proof.json"],
                                      self.exchange / "native-fixture/bootstrap-proof.json")
            base.require(proof.get("schema") == "native-script-bootstrap-proof-v1" and proof.get("passed") is True and
                         proof.get("manifestSHA256") == base.sha(self.exchange / "initial/adapter-inputs.json") and
                         proof.get("point") == self.initial and proof.get("fundedInput") == self.funded_txid + "#0" and
                         proof.get("coin") == fixture.AMOUNT and proof.get("scriptHash") == self.script["scriptHash"] and
                         proof.get("fullLedgerValidated") is False and proof.get("runtimeImport") is False,
                         "native bootstrap proof bindings and restricted scope")
            base.write(self.out / "native-bootstrap-proof.json", proof)

        def run_client(self):
            super().run_client()
            native_receipt(self.client_receipt)

        def wait_file(self, name, seconds):
            value = super().wait_file(name, seconds)
            if name in ("bootstrap-ready.json", "result.json"):
                native_receipt(value)
            if name == "result.json":
                base.require(value.get("nativeScriptSubmission") is True, "native final inclusion claim")
            return value

    return NativeController


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
        base.write(launch.out / "invocation.json", dict(schema="native-submission-controller-invocation-v1",
                   supportManifestSHA256=args.support_sha, controllerSHA256=base.sha(Path(__file__)),
                   baseControllerSHA256=base.sha(Path(base.__file__)), resources=base.RESOURCES,
                   projectionSHA256=base.PROJECTION_SHA, scalaImage=args.scala_image,
                   scalaClasspathSHA256=base.sha(args.scala_classpath_file), scalaBuildRoot=str(args.scala_build_root),
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
        base.write(launch.out / "result.json", dict(schema="native-submission-controller-result-v1", passed=True,
                   exchangeRoot=str(launch.exchange), cleanupVerified=True, elapsedSeconds=time.monotonic() - started,
                   initialPoint=result["initialPoint"], finalPoint=result["finalPoint"], transactionId=launch.transfer["transactionId"],
                   scalaResultSHA256=base.sha(launch.out / "scala-result.json"),
                   clientResultSHA256=base.sha(launch.out / "client-result.json"),
                   profileId=fixture.PROFILE, fundingComparisonSHA256=base.sha(launch.out / "funding-comparison.json"),
                   bootstrapProofSHA256=base.sha(launch.out / "native-bootstrap-proof.json")))
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
    for name in ("support-manifest", "owned-root", "evidence-root", "scala-build-root", "scala-classpath-file", "projection"):
        parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument("--support-sha", required=True)
    parser.add_argument("--scala-image", required=True)
    parser.add_argument("--java", default="java")
    parser.add_argument("--execute", action="store_true")
    args = parser.parse_args()
    live, support, classpath = base.preflight(args)
    if args.execute:
        execute(live, support, args, classpath)
    else:
        print(base.json.dumps(dict(preflight=True, executed=False, resources=base.RESOURCES,
                              profileId=fixture.PROFILE,
                              cliSubmissionAllowed="one-reference-funding-before-bootstrap-only",
                              testedSpendCliSubmissionAllowed=False), sort_keys=True))


if __name__ == "__main__":
    main()
