#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Bounded same-epoch empty/two-transaction atomic sequence reference fixture."""
import argparse
from contextlib import contextmanager
import json
import signal
import time
from private_cluster import Runner, JDK
from private_cluster_coherent import CoherentRunner, FIXTURE, INPUTS, sha
from private_cluster_relay import RelayRunner, events
from private_cluster_transfer import TransferRunner, converged_tips

PRE_PINS = {k: v for k, v in INPUTS.items() if k not in ("transactionSha256", "captureSha256")}
ORACLE_PINS = {"postUtxoCborSha256": "post-utxo-cbor.md",
    "postLedgerSha256": "post-ledger-state.md", "postProtocolSha256": "post-protocol-state.md",
    "postTipsSha256": "post-tips.md", "postParametersSha256": "post-parameters.md",
    "transaction0Sha256": "signed-transaction-0-cbor.md", "transaction1Sha256": "signed-transaction-1-cbor.md",
    "captureSha256": "scala-sequence-capture.md"}


def checked_window(pre, post, minimum=2):
    for tip in (pre, post):
        if (tip.get("era") != "Conway" or any(type(tip.get(k)) is not int for k in
                ("epoch", "slot", "block", "slotInEpoch")) or tip["slot"] < 0
                or tip["block"] < 0 or tip["epoch"] != tip["slot"] // 500
                or tip["slotInEpoch"] != tip["slot"] % 500):
            raise ValueError("fixed Conway epoch geometry required")
    count = post["block"] - pre["block"]
    if (pre["epoch"] != post["epoch"] or not 1 <= pre["slotInEpoch"] <= 80
            or post["slot"] <= pre["slot"] or not minimum <= count <= 8
            or post["slotInEpoch"] > 420):
        raise ValueError("same-epoch complete sequence window requires bounded successors")
    return {"epoch": pre["epoch"], "expectedCompleteBlocks": count, "maxBlocks": 8,
            "epochTickChecked": False, "registrationContinuityProven": False}


def checked_report(report, count):
    if (report.get("scope") != "coherent-sequence-observation"
            or type(report.get("capturedBlocks")) is not int or report["capturedBlocks"] != count
            or type(report.get("transactionCount")) is not int or report["transactionCount"] != 2
            or type(report.get("emptyBlocks")) is not int or report["emptyBlocks"] < 1):
        raise ValueError("actual original sequence grouping receipt required")
    for key in ("passed", "scopedSuccess", "referencePostStateMatched", "wholeTupleRollbackReapply",
                "transactionGroupingVerified", "finalReferenceCompared", "preStateSupplied",
                "finalCountersMatched", "fiveNonceFieldsMatched", "stakeRegistrationEndpointsEqual",
                "parameterEndpointsEqual"):
        if report.get(key) is not True:
            raise ValueError("missing scoped sequence check: " + key)
    for key in ("fullLedgerValidated", "consensusValidated", "prefixReferenceCompared",
                "endpointEqualityProvesContinuity"):
        if report.get(key) is not False:
            raise ValueError("unsupported sequence claim: " + key)
    for key, expected in (("internalInvariantPrefixes", count + 1), ("initialRevision", 0),
                          ("appliedRevision", count), ("finalReplayRevision", count + count * (count + 1))):
        if type(report.get(key)) is not int or report[key] != expected:
            raise ValueError("bounded internal rollback/replay coverage mismatch: " + key)
    indices = report.get("submissionIndexesInBlockOrder")
    if not isinstance(indices, list) or any(type(i) is not int for i in indices) or sorted(indices) != [0, 1]:
        raise ValueError("exact two submitted transaction ordinals required")
    return report


def pair_admissions(evidence, transaction_ids):
    if (len(transaction_ids) != 2 or len(set(transaction_ids)) != 2
            or len({txid[:8] for txid in transaction_ids}) != 2):
        raise ValueError("two distinct transaction identities and unambiguous eight-character log prefixes required")
    receipts = {}
    for txid in transaction_ids:
        admissions = {str(i): [event for event in evidence.get(str(i), [])
            if event.get("ns") == "Mempool.AddedTx" and
            event.get("data", {}).get("tx", {}).get("txid") in (txid, txid[:8])]
            for i in (1, 2, 3)}
        if not admissions["3"] or not (admissions["1"] or admissions["2"]):
            raise ValueError("each transaction requires relay and producer admission")
        receipts[txid] = admissions
    return receipts


def workload_budget(seconds):
    if type(seconds) is not int or not 420 <= seconds <= 540:
        raise ValueError("sequence workload budget must be 420..540 seconds")
    return seconds


class SequenceRunner(CoherentRunner):
    def execute(self, *args, **kwargs):
        # Bypass inherited interval and negative-submission hooks entirely.
        if args[:4] == ("cardano-cli", "conway", "transaction", "submit"):
            if ("--socket-path" not in args or args[args.index("--socket-path") + 1]
                    != "/work/env/socket/node3/sock"):
                raise ValueError("sequence submission is relay-only")
        return TransferRunner.execute(self, *args, **kwargs)

    def producers(self, action):
        # No inherited continuous-pause deferral applies to this fixture.
        return TransferRunner.producers(self, action)

    @contextmanager
    def paused(self, label):
        started = time.monotonic()
        previous_deadline = self.deadline
        self.deadline = min(previous_deadline, started + (8 if label == "submission" else 20))
        try:
            self.producers("STOP")
            yield
        finally:
            self.deadline = previous_deadline
            try:
                self.producers("CONT")
            finally:
                self.save(label + "-pause-timing.md", {"elapsedSeconds": time.monotonic() - started,
                    "resumeAttempted": True, "singleAcquiredSnapshot": False})

    def manifest(self, filename, profile, pins):
        self.save(filename, "format\t" + profile + "\n" + "".join(
            k + "\t" + sha((self.out / n).read_bytes()) + "\n" for k, n in sorted(pins.items())))

    def build_pair(self):
        before = json.loads(self.relay_query("utxo", "--whole-utxo", "--output-json"))
        addresses = [self.execute("cardano-cli", "address", "build",
            "--payment-verification-key-file", f"/work/env/utxo-keys/utxo{i}/utxo.vkey",
            "--testnet-magic", "1082026").stdout.strip() for i in (1, 2)]
        selections = []
        for index, address in enumerate(addresses):
            found = [(ref, value) for ref, value in before.items() if value["address"] == address]
            if len(found) != 1 or set(found[0][1]["value"]) != {"lovelace"}:
                raise ValueError("one independent ADA-only genesis input per disposable key required")
            ref, output = found[0]; amount = 10000000; fee = 200000
            change = output["value"]["lovelace"] - amount - fee
            if change < amount:
                raise ValueError("insufficient disposable input")
            path = "/work/sequence-" + str(index)
            self.execute("cardano-cli", "conway", "transaction", "build-raw", "--tx-in", ref,
                "--tx-out", addresses[1-index] + "+" + str(amount), "--tx-out", address + "+" + str(change),
                "--fee", str(fee), "--out-file", path + ".body")
            self.execute("cardano-cli", "conway", "transaction", "sign", "--tx-body-file", path + ".body",
                "--signing-key-file", f"/work/env/utxo-keys/utxo{index+1}/utxo.skey",
                "--testnet-magic", "1082026", "--out-file", path + ".signed")
            signed = json.loads(self.execute("cat", path + ".signed").stdout)["cborHex"]
            if len(bytes.fromhex(signed)) > 65536:
                raise ValueError("transaction exceeds memo bound")
            self.save(f"signed-transaction-{index}-cbor.md", signed)
            txid = self.execute("cardano-cli", "conway", "transaction", "txid", "--tx-file",
                path + ".signed", "--output-text").stdout.strip()
            if len(txid) != 64 or any(c not in "0123456789abcdef" for c in txid):
                raise ValueError("canonical transaction id required")
            self.save(f"transaction-{index}-id.md", txid)
            selections.append({"input": ref, "originalInput": output, "transactionId": txid,
                "signedPath": path + ".signed", "amount": amount, "fee": fee, "change": change})
        if len({s["input"] for s in selections}) != 2 or len({s["transactionId"] for s in selections}) != 2:
            raise ValueError("two independent distinct transactions required")
        if len({s["transactionId"][:8] for s in selections}) != 2:
            raise ValueError("ambiguous truncated transaction log identities")
        self.save("sequence-transaction-log-identities.md", {"eightCharacterPrefixesDistinct": True,
            "identities": {s["transactionId"]: s["transactionId"][:8] for s in selections},
            "matching": "exact full transaction ID or unique eight-character prefix"})
        self.save("sequence-transactions.md", selections)
        return selections

    def observer(self, command, network, filename):
        result = self.docker("run", "--rm", "--pull=never", "--name", self.name + "-scala",
            "--network=" + network, "--cpus=1", "--memory=1g", "--memory-swap=1g",
            "--pids-limit=128", "--cap-drop=ALL", "--security-opt=no-new-privileges", "--user", "1000:1000",
            "--read-only", "--tmpfs", "/tmp:size=64m", "-v", str(self.args.scala_repo) + ":/work:ro",
            "-v", str(self.out) + ":/evidence:ro", "-w", "/work", "--entrypoint=/bin/sh", JDK, "-c",
            'exec java -XX:ActiveProcessorCount=1 -Xmx512m -cp "$(cat app/target/runtime-classpath.txt)" lab.Main coherent-sequence ' + command,
            check=False, timeout=65)
        self.save(filename, result.stdout)
        self.save(filename + ".stderr.md", result.stderr)
        if result.returncode:
            raise ValueError("sequence observer failed: " + filename)
        return [json.loads(line) for line in result.stdout.splitlines() if line.startswith("{")]

    def scala(self):
        handshake = Runner.scala(self)
        RelayRunner.prepare_transfer(self)
        pair = self.build_pair()  # All expensive construction occurs with producers running.
        relay = self.read("logs/node3/stdout.log")
        if "shelleyKESSource = Nothing" not in relay or "shelleyVRFFile = Nothing" not in relay:
            raise ValueError("verified nonproducing relay required")
        self.save("relay-role.md", relay.splitlines()[0])
        readiness = []; until = min(self.deadline - 190, time.monotonic() + 75)
        while time.monotonic() < until:
            tips = [self.query("tip", i) for i in (1, 2, 3)]
            readiness.append(tips); self.save("sequence-readiness.md", readiness)
            if converged_tips(tips) and tips[-1].get("epoch", 0) >= 1 and 1 <= tips[-1].get("slotInEpoch", 501) <= 30:
                break
            time.sleep(0.2)
        else:
            raise TimeoutError("early same-epoch sequence anchor unavailable")
        with self.paused("pre"):
            pre, before = self.snapshot("pre")
            if not 1 <= pre["slotInEpoch"] <= 80:
                raise ValueError("early pre-anchor window lost")
            utxo = json.loads(before["utxo"])
            if any(utxo.get(s["input"]) != s["originalInput"] for s in pair):
                raise ValueError("prepared independent inputs differ from exact pre-state")
            self.save("transfer-genesis.md", self.read("shelley-genesis.json"))
        self.manifest("coherent-sequence-context.md", "coherent-sequence-context-v1", PRE_PINS)
        until = min(self.deadline - 170, time.monotonic() + 16)
        progress = []
        while time.monotonic() < until:
            tip = self.query("tip"); progress.append(tip); self.save("sequence-before-submit.md", progress)
            if tip["epoch"] != pre["epoch"] or tip["block"] - pre["block"] > 4:
                raise ValueError("pre-submission sequence window lost")
            if tip["block"] > pre["block"]:
                checked_window(pre, tip, minimum=1)
                break
            time.sleep(0.2)
        else:
            raise TimeoutError("provisional empty successor unavailable")
        # Tip advancement is provisional; the original block observer must prove emptiness.
        with self.paused("submission"):
            for index, selection in enumerate(pair):
                started = time.monotonic()
                result = self.execute("cardano-cli", "conway", "transaction", "submit", "--tx-file",
                    selection["signedPath"], "--testnet-magic", "1082026", "--socket-path",
                    "/work/env/socket/node3/sock", timeout=3)
                self.save(f"sequence-submission-{index}.md", result.stdout + result.stderr)
                self.save(f"sequence-submission-{index}-timing.md", {"startedMonotonicSeconds": started,
                    "completedMonotonicSeconds": time.monotonic(), "transactionId": selection["transactionId"]})
            until = min(self.deadline, time.monotonic() + 2)
            while time.monotonic() < until:
                rows = events(self.read("logs/node3/stdout.log"))
                admitted = {s["transactionId"]: [e for e in rows if e["ns"] == "Mempool.AddedTx"
                    and e.get("data", {}).get("tx", {}).get("txid") in (s["transactionId"],s["transactionId"][:8])]
                    for s in pair}
                self.save("sequence-relay-admissions.md", admitted)
                if all(admitted.values()):
                    break
                time.sleep(0.1)
            else:
                raise ValueError("both relay admissions required before resuming producers")
        until = min(self.deadline - 135, time.monotonic() + 20); inclusion = []
        while time.monotonic() < until:
            observed = json.loads(self.relay_query("utxo", "--whole-utxo", "--output-json"))
            tip = self.query("tip")
            present = [s["transactionId"] + "#0" in observed for s in pair]
            inclusion.append({"tip": tip, "outputsPresent": present}); self.save("sequence-inclusion.md", inclusion)
            checked_window(pre, tip, minimum=1)
            if all(present) and tip["block"] - pre["block"] >= 2:
                break
            time.sleep(0.2)
        else:
            raise TimeoutError("bounded pair inclusion unavailable")
        evidence = {str(i): [e for e in events(self.read(f"logs/node{i}/stdout.log"))
            if e["ns"].startswith("Mempool.") or e["ns"].startswith("TxSubmission.")] for i in (1,2,3)}
        self.save("sequence-transaction-events.md", evidence)
        self.save("sequence-pair-admissions.md", pair_admissions(evidence, [s["transactionId"] for s in pair]))
        with self.paused("post"):
            post, after = self.snapshot("post")
            window = checked_window(pre, post)
            if before["parameters"] != after["parameters"]:
                raise ValueError("parameters changed")
            self.save("sequence-window.md", window)
        # Full snapshots bracket endpoints only; prefix rollback checks are internal observations.
        port = str(int(self.read("node-data/node3/port")))
        self.observer("capture " + port + " /evidence /evidence", "container:" + self.name, "scala-sequence-capture.md")
        self.manifest("coherent-sequence-oracle.md", "coherent-sequence-oracle-v1", ORACLE_PINS)
        rows = self.observer("observe /evidence /evidence", "none", "scala-sequence-observation.md")
        report = checked_report(rows[-1] if rows else {}, window["expectedCompleteBlocks"])
        if report.get("transactionIdsInBlockOrder") != [pair[i]["transactionId"]
                for i in report["submissionIndexesInBlockOrder"]]:
            raise ValueError("original block IDs differ from exact submitted transaction identities")

        return {"handshake": handshake, "sequence": report, "fixtureProfile": FIXTURE,
                "singleAcquiredSnapshot": False, "relayOnlySubmission": True,
                "prefixRollbackReferenceCompared": False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fixture-profile", required=True, choices=[FIXTURE])
    parser.add_argument("--reference-image", required=True); parser.add_argument("--output", required=True)
    parser.add_argument("--scala-repo", required=True); parser.add_argument("--seconds", type=int, default=480)
    args = parser.parse_args(); args.capture = False
    try: workload_budget(args.seconds)
    except ValueError as exc: parser.error(str(exc))
    signal.signal(signal.SIGTERM, lambda *_: (_ for _ in ()).throw(KeyboardInterrupt()))
    SequenceRunner(args).run()


if __name__ == "__main__": main()
