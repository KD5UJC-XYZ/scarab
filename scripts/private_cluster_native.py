#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Bounded funded native-script spend; reference-only funding stays separate."""
import argparse
import hashlib
import json
import signal
import shutil
import time
from private_cluster import JDK
from private_cluster_coherent import CoherentRunner, FIXTURE, complete_utxo
from private_cluster_interval import IntervalRunner, interval_plan, interval_flags
from private_cluster_relay import RelayRunner
from private_cluster_scenarios import ScenarioRunner, unchanged, fees
from private_cluster_transfer import TransferRunner

PRE_FILES = {"genesisSha256": "transfer-genesis.md", "parametersSha256": "pre-parameters.md",
             "preTipsSha256": "pre-tips.md", "preLedgerSha256": "pre-ledger-state.md",
             "preUtxoCborSha256": "pre-utxo-cbor.md"}
REFERENCE_REASONS = {"missing-script": "MissingScriptWitnessesUTXOW",
                     "wrong-script": "MissingScriptWitnessesUTXOW",
                     "unrelated-signer": "ScriptWitnessNotValidatingUTXOW"}
CASES = (("missing-script", "MissingScripts"), ("wrong-script", "WrongScriptHashes"),
         ("unrelated-signer", "FailedScripts"))


def checked_diagnosis(row, expected, raw):
    if (row.get("scope") != "native-spending-diagnostic" or row.get("outcome") != "Rejected"
            or row.get("passed") is not False or row.get("predicate") != expected
            or row.get("originalTransactionSha256") != hashlib.sha256(raw).hexdigest()
            or row.get("fullLedgerValidated") is not False):
        raise ValueError("native diagnosis does not bind the exact rejected transaction")
    return row


def checked_funding(before, after, source, txid, script_address, change_address, amount, fee):
    old = json.loads(before[1]["utxo"]); new = json.loads(after[1]["utxo"])
    if source not in old or source in new or set(new) != (set(old) - {source}) | {txid + "#0", txid + "#1"}:
        raise ValueError("funding input/output set differs")
    for key in set(old) - {source}:
        if old[key] != new[key]:
            raise ValueError("unrelated funding output changed")
    change = old[source]["value"]["lovelace"] - amount - fee
    for index, address, value in ((0, script_address, amount), (1, change_address, change)):
        output = new[txid + "#" + str(index)]
        if output["address"] != address or output["value"] != {"lovelace": value}:
            raise ValueError("funding output differs")
        if any(output.get(k) is not None for k in ("datum", "datumhash", "inlineDatum", "referenceScript")):
            raise ValueError("unexpected funding output feature")
    if fees(after) - fees(before) != fee:
        raise ValueError("funding fee pot differs")
    return {"scope": "reference-only-funding-setup", "transactionId": txid,
            "beforeEntries": len(old), "afterEntries": len(new), "fee": fee,
            "completeReferenceStateChecked": True, "scalaFundingValidated": False}


class NativeRunner(CoherentRunner):
    comparison_command = "native-spending observe"
    comparison_scope = "cluster-native-transfer-observation"

    def execute(self, *args, **kwargs):
        # Preserve exact submitted-byte receipts and relay routing; no interval argument rewriting.
        return ScenarioRunner.execute(self, *args, **kwargs)

    def snapshot(self, label):
        result = TransferRunner.snapshot(self, label)
        if label == "pre":
            self.pre_snapshot = result
            self.plan = interval_plan(result[0]["slot"])
            self.save("interval-plan.md", self.plan)
        return result

    def key_address(self, index):
        return self.execute("cardano-cli", "address", "build", "--payment-verification-key-file",
            f"/work/env/utxo-keys/utxo{index}/utxo.vkey", "--testnet-magic", "1082026").stdout.strip()

    def sign(self, body, output, key=1):
        self.execute("cardano-cli", "conway", "transaction", "sign", "--tx-body-file", body,
            "--signing-key-file", f"/work/env/utxo-keys/utxo{key}/utxo.skey",
            "--testnet-magic", "1082026", "--out-file", output)

    def prepare_transfer(self):
        self.negative_started = True
        IntervalRunner.prepare_transfer(self)
        self.addresses = [self.key_address(i) for i in (1, 2)]
        for index in (1, 2):
            keyhash = self.execute("cardano-cli", "address", "key-hash", "--payment-verification-key-file",
                f"/work/env/utxo-keys/utxo{index}/utxo.vkey").stdout.strip()
            script = {"type": "sig", "keyHash": keyhash}
            self.write_json("../native" + str(index) + ".json", script)
            self.save("native-script-" + str(index) + ".md", script)
        self.script_address = self.execute("cardano-cli", "address", "build",
            "--payment-script-file", "/work/native1.json", "--testnet-magic", "1082026").stdout.strip()
        self.save("native-script-address.md", self.execute("cardano-cli", "address", "info",
            "--address", self.script_address).stdout)
        try:
            self.producers("STOP")
            before = self.snapshot("funding-pre")
            outputs = json.loads(before[1]["utxo"]); decoded = {}
            for index, output in enumerate(outputs.values()):
                text = self.execute("cardano-cli", "address", "info", "--address", output["address"]).stdout
                self.save("funding-genesis-address-" + str(index) + ".md", text)
                decoded[output["address"]] = json.loads(text)["base16"]
            self.save("funding-complete-genesis.md", complete_utxo(self.effective_shelley, outputs, decoded))
            selected = [(k, v) for k, v in outputs.items() if v["address"] == self.addresses[0]]
            if len(selected) != 1:
                raise ValueError("one source genesis output required")
            source, value = selected[0]; amount = 20000000; fee = 200000
            change = value["value"]["lovelace"] - amount - fee
            self.execute("cardano-cli", "conway", "transaction", "build-raw", "--tx-in", source,
                "--tx-out", self.script_address + "+" + str(amount), "--tx-out", self.addresses[0] + "+" + str(change),
                "--fee", str(fee), "--out-file", "/work/funding.body")
            self.sign("/work/funding.body", "/work/funding.signed")
            raw = self.transaction_bytes("/work/funding.signed")
            self.save("funding-transaction-cbor.md", raw.hex())
            txid = self.execute("cardano-cli", "conway", "transaction", "txid", "--tx-file",
                "/work/funding.signed", "--output-text").stdout.strip()
            self.execute("cardano-cli", "conway", "transaction", "submit", "--tx-file", "/work/funding.signed",
                "--testnet-magic", "1082026", "--socket-path", "/work/env/socket/node3/sock")
        finally:
            self.producers("CONT")
        until = min(self.deadline - 160, time.monotonic() + 30)
        while time.monotonic() < until:
            if json.loads(self.relay_query("utxo", "--tx-in", txid + "#0", "--output-json")):
                break
            time.sleep(0.25)
        else:
            raise TimeoutError("funding inclusion deadline")
        try:
            self.producers("STOP")
            after = self.snapshot("funding-post")
            self.save("funding-result.md", checked_funding(before, after, source, txid,
                self.script_address, self.addresses[0], amount, fee))
        finally:
            self.producers("CONT")
        self.funded_input = txid + "#0"
        self.run_negatives()
        IntervalRunner.prepare_transfer(self)

    def build_spend(self, path, script):
        flags = ["--tx-in-script-file", script] if script else []
        self.execute("cardano-cli", "conway", "transaction", "build-raw", "--tx-in", self.funded_input,
            *flags, "--tx-out", self.addresses[1] + "+10000000", "--tx-out", self.addresses[0] + "+9800000",
            "--fee", "200000", *interval_flags(self.plan["positive"]), "--out-file", path)

    def construct_transaction(self, pre, before):
        output = json.loads(before["utxo"])[self.funded_input]
        if output["address"] != self.script_address or output["value"] != {"lovelace": 20000000}:
            raise ValueError("complete prestate lacks exact funded script output")
        self.build_spend("/work/transfer.body", "/work/native1.json")
        self.sign("/work/transfer.body", "/work/transfer.signed")
        return {"input": self.funded_input, "sourceValue": output, "destination": self.addresses[1],
                "amount": 10000000, "changeAddress": self.addresses[0], "change": 9800000, "fee": 200000}

    def diagnose(self, label, expected, pre_directory, slot):
        cmd = ('exec java -XX:ActiveProcessorCount=1 -Xmx512m -cp "$(cat app/target/runtime-classpath.txt)" '
               'lab.Main native-spending diagnose /evidence/' + pre_directory + ' /evidence/' + label + '-transaction-cbor.md ' + str(slot))
        result = self.docker("run", "--rm", "--pull=never", "--name", self.name + "-scala", "--network=none",
            "--cpus=1", "--memory=1g", "--memory-swap=1g", "--pids-limit=128", "--cap-drop=ALL",
            "--security-opt=no-new-privileges", "--user", "1000:1000", "--read-only", "--tmpfs", "/tmp:size=64m",
            "-v", str(self.args.scala_repo) + ":/work:ro", "-v", str(self.out) + ":/evidence:ro", "-w", "/work",
            "--entrypoint=/bin/sh", JDK, "-c", cmd, check=False, timeout=25)
        self.save(label + "-scala.md", result.stdout + result.stderr)
        rows = [json.loads(s) for s in result.stdout.splitlines() if s.startswith("{")]
        if result.returncode != (2 if expected else 0) or len(rows) != 1:
            raise ValueError("native prestate diagnosis differs: " + label)
        raw = bytes.fromhex((self.out / (label + "-transaction-cbor.md")).read_text().strip())
        if expected:
            return checked_diagnosis(rows[0], expected, raw)
        row = rows[0]
        if (row.get("scope") != "native-spending-diagnostic" or row.get("outcome") != "ScopedDerived"
                or row.get("passed") is not True or row.get("independentStateDerived") is not True
                or row.get("originalTransactionSha256") != hashlib.sha256(raw).hexdigest()
                or row.get("referencePostStateMatched") is not False or row.get("fullLedgerValidated") is not False):
            raise ValueError("positive diagnostic control did not derive from exact prestate")
        return row

    def before_submit(self):
        pass  # Rejections use separate pauses; accepted spending gets its own fresh prestate.

    def run_negatives(self):
        for label, predicate in CASES:
            try:
                self.producers("STOP")
                before = self.snapshot(label + "-pre")
                self.plan = interval_plan(before[0]["slot"])
                pre_directory = "negative-" + label + "-input"
                target = self.out / pre_directory; target.mkdir(exist_ok=False)
                (target / "transfer-genesis.md").write_text(self.read("shelley-genesis.json"))
                for name in PRE_FILES.values():
                    if name != "transfer-genesis.md":
                        shutil.copy2(self.out / (label + "-" + name), target / name)
                (target / "native-spending-pre.md").write_text("format\tnative-spending-pre-v1\n" + "".join(
                    k + "\t" + hashlib.sha256((target / n).read_bytes()).hexdigest() + "\n" for k, n in sorted(PRE_FILES.items())))
                control = label + "-control"
                self.build_spend("/work/" + control + ".body", "/work/native1.json")
                self.sign("/work/" + control + ".body", "/work/" + control + ".signed")
                self.save(control + "-transaction-cbor.md", self.transaction_bytes("/work/" + control + ".signed").hex())
                control_id = self.execute("cardano-cli", "conway", "transaction", "txid", "--tx-file",
                    "/work/" + control + ".signed", "--output-text").stdout.strip()
                body = "/work/" + label + ".body"; path = "/work/" + label + ".signed"
                script = None if label == "missing-script" else "/work/native2.json" if label == "wrong-script" else "/work/native1.json"
                self.build_spend(body, script); self.sign(body, path, 2 if label == "unrelated-signer" else 1)
                raw = self.transaction_bytes(path); self.save(label + "-transaction-cbor.md", raw.hex())
                txid = self.execute("cardano-cli", "conway", "transaction", "txid", "--tx-file", path, "--output-text").stdout.strip()
                if txid != control_id:
                    raise ValueError("negative differs from its valid control body identity")
                result = self.execute("cardano-cli", "conway", "transaction", "submit", "--tx-file", path,
                    "--testnet-magic", "1082026", "--socket-path", "/work/env/socket/node3/sock", check=False)
                if self.last_submission["digest"] != hashlib.sha256(raw).hexdigest():
                    raise ValueError("submitted transaction differs from diagnosed original")
                submission_evidence = self.last_submission["evidence"]
                after = self.snapshot(label + "-post"); unchanged(before, after)
                if before[1]["utxo-cbor"] != after[1]["utxo-cbor"]:
                    raise ValueError("negative changed complete UTxO bytes")
                receipt = {"scope": "native-local-rejection-observation", "transactionId": txid,
                    "transactionCborSha256": hashlib.sha256(raw).hexdigest(), "controlTransactionId": control_id,
                    "returncode": result.returncode, "stdout": result.stdout, "stderr": result.stderr,
                    "stateUnchanged": True, "referenceEvaluationSlotEstablished": False,
                    "fullLedgerValidated": False, "referencePredicateOrderAgreement": False,
                    "expectedReferenceReason": REFERENCE_REASONS[label], "submissionEvidence": submission_evidence}
                self.save(label + "-result.md", receipt)
                if result.returncode == 0 or REFERENCE_REASONS[label] not in result.stdout + result.stderr:
                    raise ValueError("reference ledger submission rejection missing")
            finally:
                self.producers("CONT")
            receipt["controlScala"] = self.diagnose(control, None, pre_directory, before[0]["slot"])
            receipt["scala"] = self.diagnose(label, predicate, pre_directory, before[0]["slot"])
            self.save(label + "-result.md", receipt)
            until = min(self.deadline - 100, time.monotonic() + 15)
            while time.monotonic() < until:
                tip = self.query("tip")
                if tip.get("hash") != before[0]["hash"]:
                    self.save(label + "-resumed-tip.md", tip)
                    break
                time.sleep(0.25)
            else:
                raise TimeoutError("reference did not advance after short negative pause")

    def scala(self):
        result = RelayRunner.scala(self)
        report = result["transfer"]
        if report.get("credentialBound") is not True or report.get("independentStateDerived") is not True:
            raise ValueError("native derived credential-bound receipt missing")
        result.update(nativeNegatives=3, fundingScope="reference-only-setup", fullLedgerValidated=False)
        return result


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--fixture-profile", required=True, choices=[FIXTURE])
    p.add_argument("--reference-image", required=True); p.add_argument("--output", required=True)
    p.add_argument("--scala-repo", required=True); p.add_argument("--seconds", type=int, default=540)
    args = p.parse_args(); args.capture = False
    if not 480 <= args.seconds <= 540:
        p.error("native workload budget must be 480..540 seconds")
    signal.signal(signal.SIGTERM, lambda *_: (_ for _ in ()).throw(KeyboardInterrupt()))
    NativeRunner(args).run()


if __name__ == "__main__":
    main()
