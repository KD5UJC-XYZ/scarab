#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Integrated private transfer scenario; point observations remain explicitly bracketed."""
import argparse
import hashlib
import json
import signal
import time
from private_cluster import Runner, JDK


class TransferRunner(Runner):
    def execute(self, *args, **kwargs):
        if args and args[0] == "/bin/sh" and len(args) == 3 and "cardano-testnet create-env" in args[2]:
            args = (args[0], args[1], args[2].replace("--num-pool-nodes 3", "--nodes spo,spo,relay"))
        return super().execute(*args, **kwargs)

    def query(self, kind, node=3):
        return super().query(kind, node)

    def relay_query(self, kind, *options):
        result = self.execute("cardano-cli", "conway", "query", kind,
            "--testnet-magic", "1082026", "--socket-path", "/work/env/socket/node3/sock", *options)
        if len(result.stdout) > 4 * 1024 * 1024:
            raise ValueError("reference state observation exceeds 4 MiB")
        return result.stdout

    def producers(self, action):
        for i in (1, 2):
            pid = int(self.read(f"logs/node{i}/node.pid").strip())
            if pid <= 1:
                raise ValueError("invalid owned producer pid")
            self.execute("/bin/sh", "-c", "kill -" + action + " " + str(pid))
            if action == "STOP":
                state = self.execute("cat", f"/proc/{pid}/status").stdout
                self.save(f"producer{i}-paused.md", state)
                if "State:\tT" not in state:
                    raise ValueError("producer pause not observed")

    def pause_evidence(self):
        states = []
        for i in (1, 2):
            pid = int(self.read(f"logs/node{i}/node.pid").strip())
            if pid <= 1:
                raise ValueError("invalid owned producer pid")
            state = self.execute("cat", f"/proc/{pid}/status").stdout
            if "State:\tT" not in state:
                raise ValueError("producer progressed during state queries")
            states.append({"node": i, "pid": pid, "status": state})
        return states

    def snapshot(self, label):
        paused = [self.pause_evidence()]
        tips = []
        tip_originals = []
        def observe_tip():
            original = self.relay_query("tip")
            value = json.loads(original)
            self.save(label + "-tip-original-" + str(len(tip_originals)) + ".md", original)
            tip_originals.append(original)
            return value
        for _ in range(3):
            tips.append(observe_tip()); time.sleep(0.25)
        if len({t["hash"] for t in tips}) != 1:
            raise ValueError("relay did not quiesce after producer pause")
        before = tips[-1]
        outputs = {}
        for name, kind, options in [
            ("utxo", "utxo", ("--whole-utxo", "--output-json")),
            ("utxo-cbor", "utxo", ("--whole-utxo", "--output-cbor-hex")),
            ("ledger-state", "ledger-state", ("--output-json",)),
            ("protocol-state", "protocol-state", ("--output-json",)),
            ("parameters", "protocol-parameters", ())]:
            text = self.relay_query(kind, *options)
            self.save(label + "-" + name + ".md", text)
            outputs[name] = text
            tips.append(observe_tip())
            paused.append(self.pause_evidence())
        self.save(label + "-producer-brackets.md", json.dumps(paused, indent=2))
        self.save(label + "-tips.md", "[" + ",".join(tip_originals) + "]")
        if any((t["hash"], t["slot"], t["era"], t["epoch"]) !=
               (before["hash"], before["slot"], before["era"], before["epoch"]) for t in tips):
            raise ValueError("tip changed across bracketed state queries")
        self.save(label + "-binding.md", json.dumps({"point": before,
            "method": "paused-owned-producers-and-stable-relay-tip-brackets",
            "singleAcquiredSnapshot": False,
            "limitation": "Separate CLI acquisitions; identical tip observations do not constitute a single atomic query session."}, indent=2))
        return before, outputs

    def prepare_transfer(self):
        pass

    def submit_producer(self):
        direct = self.execute("cardano-cli", "conway", "transaction", "submit",
            "--tx-file", "/work/transfer.signed", "--testnet-magic", "1082026",
            "--socket-path", "/work/env/socket/node1/sock")
        self.save("producer-submission.md", direct.stdout + direct.stderr)

    def scala(self):
        self.prepare_transfer()
        handshake = super().scala()
        # Confirm three-node convergence before changing only owned process states.
        convergence = []
        for _ in range(10):
            tips = [self.query("tip", i) for i in (1, 2, 3)]
            convergence.append(tips)
            if len({t["hash"] for t in tips}) == 1:
                break
            time.sleep(0.25)
        else:
            raise ValueError("three-node convergence unavailable")
        self.save("pre-pause-convergence.md", json.dumps(convergence, indent=2))
        # The relay must have no forging credentials. Verify the actual startup observation.
        relay = self.read("logs/node3/stdout.log")
        if "shelleyKESSource = Nothing" not in relay or "shelleyVRFFile = Nothing" not in relay:
            raise ValueError("node3 is not a verified non-producing relay")
        self.save("relay-role.md", relay.splitlines()[0])
        try:
            self.producers("STOP")
            pre, before = self.snapshot("pre")
            if pre["slotInEpoch"] > 180:
                raise ValueError("insufficient same-epoch transfer observation window")
            genesis = self.read("shelley-genesis.json")
            self.save("transfer-genesis.md", genesis)
            params = json.loads(before["parameters"])
            utxo = json.loads(before["utxo"])
            addresses = []
            for i in (1, 2):
                addresses.append(self.execute("cardano-cli", "address", "build",
                    "--payment-verification-key-file", f"/work/env/utxo-keys/utxo{i}/utxo.vkey",
                    "--testnet-magic", "1082026").stdout.strip())
            selected = [(key, value) for key, value in utxo.items() if value["address"] == addresses[0]]
            if len(selected) != 1:
                raise ValueError("exactly one disposable source UTxO required")
            txin, value = selected[0]
            if set(value["value"]) != {"lovelace"}:
                raise ValueError("ADA-only source UTxO required")
            amount, fee = 10000000, 200000
            change = value["value"]["lovelace"] - amount - fee
            if change < amount:
                raise ValueError("insufficient disposable source value")
            self.execute("cardano-cli", "conway", "transaction", "build-raw", "--tx-in", txin,
                "--tx-out", addresses[1] + "+" + str(amount), "--tx-out", addresses[0] + "+" + str(change),
                "--fee", str(fee), "--out-file", "/work/transfer.body")
            self.execute("cardano-cli", "conway", "transaction", "sign", "--tx-body-file", "/work/transfer.body",
                "--signing-key-file", "/work/env/utxo-keys/utxo1/utxo.skey", "--testnet-magic", "1082026",
                "--out-file", "/work/transfer.signed")
            signed = json.loads(self.execute("cat", "/work/transfer.signed").stdout)
            self.save("signed-transaction-cbor.md", signed["cborHex"])
            txid_result = self.execute("cardano-cli", "conway", "transaction", "txid", "--tx-file", "/work/transfer.signed", "--output-text")
            self.save("transaction-id.md", txid_result.stdout + txid_result.stderr)
            txid = txid_result.stdout.strip()
            if len(txid) != 64 or any(c not in "0123456789abcdef" for c in txid):
                raise ValueError("unexpected transaction ID")
            self.save("transfer-selection.md", json.dumps({"input": txin, "sourceValue": value,
                "destination": addresses[1], "amount": amount, "changeAddress": addresses[0],
                "change": change, "fee": fee, "transactionId": txid}, indent=2))
            submitted = self.execute("cardano-cli", "conway", "transaction", "submit",
                "--tx-file", "/work/transfer.signed", "--testnet-magic", "1082026",
                "--socket-path", "/work/env/socket/node3/sock")
            self.save("submission.md", submitted.stdout + submitted.stderr)
        finally:
            self.producers("CONT")
        # Direct producer submission isolates ledger/inclusion from relay TxSubmission propagation.
        self.submit_producer()
        inclusion_deadline = min(self.deadline - 50, time.monotonic() + 25)
        observations = []
        while time.monotonic() < inclusion_deadline:
            observed = json.loads(self.relay_query("utxo", "--tx-in", txid + "#0", "--output-json"))
            observations.append({"tip": self.query("tip"), "outputPresent": bool(observed),
                                 "observedUnixSeconds": time.time(), "observedMonotonicSeconds": time.monotonic()})
            self.save("inclusion-observations.md", json.dumps(observations, indent=2))
            if observed:
                break
            time.sleep(0.25)
        else:
            raise TimeoutError("transfer inclusion deadline")
        self.save("inclusion-observations.md", json.dumps(observations, indent=2))
        try:
            self.producers("STOP")
            post, after = self.snapshot("post")
            if pre["epoch"] != post["epoch"] or before["parameters"] != after["parameters"]:
                raise ValueError("epoch/parameters changed across transfer scenario")
            fees_before = json.loads(before["ledger-state"])["stateBefore"]["esLState"]["utxoState"]["fees"]
            fees_after = json.loads(after["ledger-state"])["stateBefore"]["esLState"]["utxoState"]["fees"]
            self.save("fee-pot-observation.md", json.dumps({"exporter": "cardano-cli conway query ledger-state",
                "field": "stateBefore.esLState.utxoState.fees", "before": fees_before, "after": fees_after,
                "observedDelta": fees_after - fees_before, "declaredTransactionFee": fee}, indent=2))
            fields = {"format": "conway-pv9-cluster-context-v2",
                "genesisSha256": hashlib.sha256(genesis.encode()).hexdigest(),
                "parametersSha256": hashlib.sha256(before["parameters"].encode()).hexdigest(),
                "networkMagic": 1082026, "major": params["protocolVersion"]["major"],
                "minor": params["protocolVersion"]["minor"], "preHash": pre["hash"], "postHash": post["hash"],
                "preSlot": pre["slot"], "postSlot": post["slot"], "preEpoch": pre["epoch"], "postEpoch": post["epoch"],
                "feePerByte": params["txFeePerByte"], "feeFixed": params["txFeeFixed"],
                "maxTxSize": params["maxTxSize"], "feesBefore": fees_before, "feesAfter": fees_after,
                "binding": "paused-producer-tip-brackets"}
            for key, name in [("preTipsSha256", "pre-tips.md"), ("postTipsSha256", "post-tips.md"),
                              ("preLedgerSha256", "pre-ledger-state.md"), ("postLedgerSha256", "post-ledger-state.md")]:
                fields[key] = hashlib.sha256((self.out / name).read_bytes()).hexdigest()
            self.save("transfer-context.md", "".join(str(k) + "\t" + str(v) + "\n" for k, v in fields.items()))
            port = str(int(self.read("node-data/node3/port").strip()))
            result = self.docker("run", "--rm", "--pull=never", "--name", self.name + "-scala",
                "--network=container:" + self.name, "--cpus=1", "--memory=1g", "--memory-swap=1g",
                "--pids-limit=128", "--cap-drop=ALL", "--security-opt=no-new-privileges",
                "--user", "1000:1000", "--read-only", "--tmpfs", "/tmp:size=64m",
                "-v", str(self.args.scala_repo) + ":/work:ro", "-v", str(self.out) + ":/evidence:ro",
                "-w", "/work", "--entrypoint=/bin/sh", JDK, "-c",
                'exec java -XX:ActiveProcessorCount=1 -Xmx512m -cp "$(cat app/target/runtime-classpath.txt)" lab.Main cluster-transfer ' + port + " /evidence",
                check=False, timeout=70)
            self.save("scala-transfer.md", result.stdout + result.stderr)
            if result.returncode:
                raise ValueError("integrated Scala transfer comparison failed")
            reports = [json.loads(line) for line in result.stdout.splitlines() if line.startswith("{")]
            report = reports[-1] if reports else {}
            if report.get("scope") != "cluster-transfer-observation" or report.get("passed") is not True:
                raise ValueError("integrated transfer receipt missing")
        finally:
            self.producers("CONT")
        return {"handshake": handshake, "transfer": report, "transferSubmitted": True,
                "singleAcquiredSnapshot": False}



def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--reference-image", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--scala-repo", required=True)
    parser.add_argument("--seconds", type=int, default=240)
    args = parser.parse_args()
    args.capture = False
    if not 180 <= args.seconds <= 480:
        parser.error("budget must be 180..480 seconds")
    signal.signal(signal.SIGTERM, lambda *_: (_ for _ in ()).throw(KeyboardInterrupt()))
    TransferRunner(args).run()


if __name__ == "__main__": main()
