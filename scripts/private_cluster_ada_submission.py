#!/usr/bin/env python3
"""Isolated ADA HTTP ingress controller; preflight only unless --execute.

Reuses the reviewed native boundary lifecycle and exact-point acquisition. The
reference CLI only constructs/signs a disposable transfer. A separate bounded
client process sends those bytes to Scala HTTP; it never submits through CLI.
"""
import argparse
import hashlib
import os
from pathlib import Path
import re
import signal
import time

import private_cluster_native_boundary as base


def immediate_tx_submission(original):
    """Change one node-11.1.3 JSON setting, preserving all other JSON values.

    Node Configuration/POM.hs accepts numeric TxSubmissionInitDelay and Run.hs
    forwards it to diffusion. The pinned ouroboros-network default is 60 seconds.
    A zero delay lets this isolated fixture exercise the existing bounded lease.
    """
    base.require(isinstance(original, bytes) and len(original) <= 4 * 1024 * 1024,
                 "bounded original JSON configuration")
    config = base.decode(original)
    base.require(isinstance(config, dict), "JSON configuration object")
    key = "TxSubmissionInitDelay"
    previous = config.get(key)
    base.require(key not in config or (type(previous) in (int, float) and previous == 60),
                 "expected absent or default 60-second TxSubmissionInitDelay")
    updated = dict(config, TxSubmissionInitDelay=0)
    raw = (base.json.dumps(updated, indent=2, ensure_ascii=False, allow_nan=False) + "\n").encode()
    decoded = base.decode(raw)
    base.require({k: v for k, v in decoded.items() if k != key} ==
                 {k: v for k, v in config.items() if k != key} and type(decoded[key]) is int and decoded[key] == 0,
                 "only initialization delay may change")
    return raw, dict(schema="ada-private-txsubmission-init-delay-v1", key=key,
                     originalKeyPresent=key in config, originalValue=previous,
                     defaultSeconds=60, configuredSeconds=0,
                     beforeSHA256=hashlib.sha256(original).hexdigest(), afterSHA256=hashlib.sha256(raw).hexdigest(),
                     otherConfigurationValuesUnchanged=True, relayDeadlinesUnchanged=True,
                     scope="isolated disposable fixture", nodeVersion="11.1.3")


def select_transfer(utxo, addresses, amount=10000000, fee=200000):
    base.require(isinstance(utxo, dict) and len(utxo) <= 16384, "bounded UTxO map")
    base.require(len(addresses) == 2 and all(isinstance(a, str) and
                 re.fullmatch(r"addr_test1[0-9a-z]{20,200}", a) for a in addresses), "test addresses")
    selected = [(key, value) for key, value in utxo.items() if value.get("address") == addresses[0]]
    base.require(len(selected) == 1, "exactly one disposable source UTxO")
    txin, value = selected[0]
    base.require(re.fullmatch(r"[0-9a-f]{64}#[0-9]{1,5}", txin) is not None, "input identity")
    base.require(set(value["value"]) == {"lovelace"}, "ADA-only source")
    coin = value["value"]["lovelace"]
    base.require(type(coin) is int and 0 < coin < 2 ** 64, "source coin")
    base.require(type(amount) is int and type(fee) is int and amount >= 1000000 and fee >= 200000,
                 "bounded valid transfer values")
    change = coin - amount - fee
    base.require(change >= amount, "sufficient disposable source value")
    return dict(input=txin, sourceCoin=coin, destination=addresses[1], amount=amount,
                changeAddress=addresses[0], change=change, fee=fee)


def signed_bytes(envelope):
    raw = envelope.get("cborHex")
    base.require(isinstance(raw, str) and 0 < len(raw) <= 131072 and len(raw) % 2 == 0 and
                 re.fullmatch("[0-9a-fA-F]+", raw) is not None, "bounded signed CBOR")
    return bytes.fromhex(raw)


def client_result(value, descriptor):
    base.require(value.get("schema") == "ada-submission-client-result-v1" and
                 value.get("passed") is True and value.get("accepted") is True and
                 value.get("included") is True and value.get("ingress") == "scala-http",
                 "HTTP ingress and follower inclusion receipt")
    base.require(value.get("transactionId") == descriptor["transactionId"], "client transaction binding")
    return value


def controller_type(live):
    class AdaController(base.controller_type(live)):
        def lifecycle(self, approval):
            root = self.args.owned_root
            config = self.environment / "configuration.yaml"
            base.require(root.is_absolute() and root.resolve() == root and
                         self.environment == root / "environment" and self.environment.resolve() == self.environment and
                         config.resolve() == config and root in config.parents,
                         "configuration must belong to the fresh owned fixture")
            base.require(not self.active, "configuration override before node startup")
            original = base.read(config)
            updated, receipt = immediate_tx_submission(original)
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
            return super().lifecycle(approval)

        def create(self, phase, tail):
            if phase == "scala":
                tail = ["lab.AdaSubmissionMain" if x == "lab.NativeLiveBoundaryMain" else x for x in tail]
            return super().create(phase, tail)

        def execute(self, *args, **kwargs):
            # Defense in depth against accidentally inheriting a CLI submit path.
            base.require(not (args and Path(args[0]).name == "cardano-cli" and "submit" in args),
                         "reference CLI submission forbidden")
            return super().execute(*args, **kwargs)

        def construct_transfer(self):
            root = self.exchange / "submission"
            root.mkdir(mode=0o700)
            before = self.tip(1)
            base.require(base.point(dict(slot=before["slot"], blockNo=before["block"], hash=before["hash"])) == self.initial,
                         "transaction construction initial point")
            addresses = [self.execute("cardano-cli", "address", "build", "--payment-verification-key-file",
                         str(self.environment / "utxo-keys" / ("utxo" + str(i)) / "utxo.vkey"),
                         "--testnet-magic", str(self.magic)).stdout.strip() for i in (1, 2)]
            utxo = base.decode(self.query_node(1, "utxo", "--whole-utxo", "--output-json"))
            selection = select_transfer(utxo, addresses)
            body, signed = "/work/ada-http.body", "/work/ada-http.signed"
            self.execute("cardano-cli", "conway", "transaction", "build-raw", "--tx-in", selection["input"],
                         "--tx-out", selection["destination"] + "+" + str(selection["amount"]),
                         "--tx-out", selection["changeAddress"] + "+" + str(selection["change"]),
                         "--fee", str(selection["fee"]), "--invalid-hereafter", "2000", "--out-file", body)
            self.execute("cardano-cli", "conway", "transaction", "sign", "--tx-body-file", body,
                         "--signing-key-file", str(self.environment / "utxo-keys/utxo1/utxo.skey"),
                         "--testnet-magic", str(self.magic), "--out-file", signed)
            envelope = base.decode(self.execute("head", "-c", "131073", "--", signed).stdout)
            raw = signed_bytes(envelope)
            txid = self.execute("cardano-cli", "conway", "transaction", "txid", "--tx-file", signed,
                                "--output-text").stdout.strip()
            base.require(base.hex64(txid), "reference body transaction ID")
            base.require(live.process.same_tip(before, self.tip(1)), "frozen construction bracket moved")
            with (root / "transaction.cbor").open("xb") as stream:
                stream.write(raw)
            self.transfer = dict(schema="ada-submission-transaction-v1", transactionId=txid,
                                 envelopeSHA256=hashlib.sha256(raw).hexdigest(), bytes=len(raw),
                                 validityUpperBound=2000, initialPoint=self.initial, networkMagic=self.magic,
                                 cliSubmitted=False, **selection)
            base.write(root / "descriptor.json", self.transfer)

        def start_scala(self, manifest_pin):
            self.construct_transfer()
            return super().start_scala(manifest_pin)

        def remove_client(self):
            found = self.docker("ps", "-aq", "--no-trunc", "--filter", "label=" + live.LABEL + "=" + self.token,
                                "--filter", "label=lab.zero-live.phase=ada-client").stdout.split()
            base.require(len(found) <= 1, "unambiguous HTTP client ownership")
            cid = self.containers.get("ada-client")
            base.require(not found or cid is None or found == [cid], "client immutable identity")
            for actual in found:
                self.owned(actual)
                try:
                    logs = self.docker("logs", "--tail", "1000", actual, check=False, timeout=3)
                    raw = (logs.stdout + logs.stderr).encode()
                    with (self.out / "client.log").open("xb") as stream:
                        stream.write(raw[-262144:])
                except BaseException as error:
                    self.diagnostic_write("client-log-collection-error.json", dict(errorType=type(error).__name__))
                self.docker("rm", "--force", actual)
                base.require(not self.docker("ps", "-aq", "--no-trunc", "--filter", "id=" + actual).stdout.strip(),
                             "client absence")
            actual = found[0] if found else cid
            if actual and not (self.out / "client-cleanup.json").exists():
                base.require(not self.docker("ps", "-aq", "--no-trunc", "--filter", "id=" + actual).stdout.strip(),
                             "client final absence")
                completed = getattr(self, "client_completed", False)
                base.write(self.out / "client-cleanup.json", dict(containerId=actual, absenceVerified=True,
                           role="ada-client", outcome="completed" if completed else "aborted",
                           removedBeforeEndpointCapture=completed, removedBeforeNamespaceOwner=True))

        def cleanup(self):
            if not self.cleaned:
                self.in_cleanup = True
                if self.cleanup_deadline is None:
                    self.cleanup_deadline = time.monotonic() + 30
                self.deadline = self.cleanup_deadline
                signal.alarm(max(1, int(self.deadline - time.monotonic())))
                self.remove_client()
            super().cleanup()

        def run_client(self):
            base.require(not getattr(self, "client_started", False), "single external client")
            self.client_started = True
            network = "container:" + self.containers["reference"]
            submission = self.exchange / "submission"
            args = ["--pull=never", "--network=" + network, "--cpus=1", "--memory=2g", "--memory-swap=2g",
                    "--pids-limit=256", "--read-only", "--cap-drop=ALL", "--security-opt=no-new-privileges",
                    "--user", "1000:1000", "--tmpfs", "/tmp:rw,nosuid,nodev,size=64m",
                    "--mount", "type=bind,src=" + str(self.args.scala_build_root) + ",dst=/work,readonly",
                    "--mount", "type=bind,src=" + str(submission) + ",dst=/exchange/submission",
                    "--entrypoint", self.args.java, self.args.scala_image, "-Xmx512m", "-XX:ActiveProcessorCount=1",
                    "-cp", self.classpath, "lab.AdaSubmissionClientMain", str(self.api_port),
                    "/exchange/submission/transaction.cbor", "/exchange/submission/client-result.json"]
            cid = self.create("ada-client", args)
            observed = self.owned(cid)
            base.check_resources(observed, "helper", self.args.scala_image, network)
            base.require({(m["Source"], m["Destination"], m["RW"]) for m in observed["Mounts"] if m["Type"] == "bind"} ==
                         {(str(self.args.scala_build_root), "/work", False),
                          (str(submission), "/exchange/submission", True)}, "exact HTTP client mounts")
            base.write(self.out / "client-inspection.json", dict(containerId=cid, image=observed["Image"],
                       network=network, cpus=1, memoryBytes=2 * base.GIB, mounts=observed["Mounts"]))
            try:
                result = self.docker("start", "--attach", cid, timeout=65)
                (self.out / "client-attach.log").write_text(result.stdout + result.stderr)
                state = self.owned(cid)["State"]
                base.require(not state["Running"] and state["ExitCode"] == 0 and not state["OOMKilled"], "HTTP client exit")
                receipt = client_result(base.decode(base.read(submission / "client-result.json", 65536)), self.transfer)
                self.client_receipt = receipt
                base.write(self.out / "client-result.json", receipt)
                self.client_completed = True
            finally:
                self.remove_client()

        def wait_file(self, name, seconds):
            if name == "endpoint-request.json":
                self.run_client()
            value = super().wait_file(name, seconds)
            if name == "bootstrap-ready.json":
                port = value.get("apiPort")
                base.require(type(port) is int and 1 <= port <= 65535 and port not in live.process.PORTS.values(),
                             "distinct loopback API port")
                self.api_port = port
            if name == "result.json":
                base.require(value.get("adaSubmittedViaHttp") is True and value.get("adaIncluded") is True and
                             value.get("transactionId") == self.transfer["transactionId"], "final ADA inclusion binding")
                for field in ("includedBodySHA256", "includedWitnessesSHA256"):
                    base.require(base.hex64(value.get(field)) and value[field] == self.client_receipt.get(field),
                                 "submitted and included original span binding: " + field)
            return value

    return AdaController


def execute(live, support, args, classpath):
    launch = controller_type(live)(support, args, classpath)
    watchdog = live.DiskWatchdog(args.owned_root)
    previous = {}
    def interrupted(signum, _frame):
        if launch.in_cleanup and signum != signal.SIGALRM:
            return
        raise TimeoutError("bounded ADA operation interrupted")
    started = time.monotonic()
    try:
        previous = {s: signal.signal(s, interrupted) for s in (signal.SIGALRM, signal.SIGTERM, signal.SIGINT, signal.SIGUSR1)}
        signal.alarm(240)
        watchdog.start()
        base.write(launch.out / "invocation.json", dict(schema="ada-submission-controller-invocation-v1",
                   supportManifestSHA256=args.support_sha, controllerSHA256=base.sha(Path(__file__)),
                   baseControllerSHA256=base.sha(Path(base.__file__)), resources=base.RESOURCES,
                   projectionSHA256=base.PROJECTION_SHA, scalaImage=args.scala_image,
                   scalaClasspathSHA256=base.sha(args.scala_classpath_file), scalaBuildRoot=str(args.scala_build_root),
                   operationSeconds=240, cleanupSeconds=30, cliSubmissionAllowed=False,
                   network="reference-none;scala-and-http-client-exact-reference-namespace;capture-none"))
        base.require(launch.docker("image", "inspect", args.scala_image, "--format", "{{.Id}}").stdout.strip() == args.scala_image,
                     "Scala image pin")
        ids = dict(generatorSHA256=live.z.GENERATOR_SHA, launcherSHA256=base.sha(Path(__file__)),
                   imageId=live.z.REFERENCE_IMAGE, sourceCommit=support["sourceCommit"])
        result, receipt = live.controller.execute(args.owned_root, ids, launch.generate, launch.lifecycle, on_abort=launch.cleanup)
        base.write(launch.out / "controller-lifecycle.json", receipt)
        base.require(launch.cleaned and watchdog.failure is None, "owned cleanup/watchdog")
        base.require(time.monotonic() - started <= 270, "total deadline")
        base.write(launch.out / "result.json", dict(schema="ada-submission-controller-result-v1", passed=True,
                   exchangeRoot=str(launch.exchange), cleanupVerified=True, elapsedSeconds=time.monotonic() - started,
                   initialPoint=result["initialPoint"], finalPoint=result["finalPoint"], transactionId=launch.transfer["transactionId"],
                   scalaResultSHA256=base.sha(launch.out / "scala-result.json"),
                   clientResultSHA256=base.sha(launch.out / "client-result.json")))
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
                              cliSubmissionAllowed=False), sort_keys=True))


if __name__ == "__main__":
    main()
