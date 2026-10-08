#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Controlled submission negatives around the existing real transfer comparison."""
import argparse
import json
import signal
import time
from private_cluster_transfer import TransferRunner


def fees(snapshot):
    return json.loads(snapshot[1]["ledger-state"])["stateBefore"]["esLState"]["utxoState"]["fees"]


def unchanged(before, after):
    """Separate acquired observations, never an atomic ledger snapshot claim."""
    for key in ("hash", "slot", "epoch", "era"):
        if before[0][key] != after[0][key]:
            raise ValueError("scenario point changed: " + key)
    for key in ("utxo", "parameters"):
        if json.loads(before[1][key]) != json.loads(after[1][key]):
            raise ValueError("scenario state changed: " + key)
    if fees(before) != fees(after):
        raise ValueError("scenario fee pot changed")


class ScenarioRunner(TransferRunner):
    """No shared runner patch: intercept only the first exact transfer submission."""
    def execute(self, *args, **kwargs):
        if args[:4] != ("cardano-cli", "conway", "transaction", "submit"):
            return super().execute(*args, **kwargs)
        tx_file = args[args.index("--tx-file") + 1]
        if tx_file == "/work/transfer.signed" and not getattr(self, "negative_started", False):
            self.negative_started = True
            self.wrong_key()
        index = getattr(self, "submission_index", 0)
        self.submission_index = index + 1
        label = "scenario-submission-" + str(index)
        checked = kwargs.pop("check", True)
        started = time.monotonic()
        try:
            result = super().execute(*args, check=False, **kwargs)
        except BaseException as exc:
            self.save(label + ".md", {"command": args, "outcome": "process-exception",
                "exception": type(exc).__name__, "detail": str(exc),
                "startedMonotonicSeconds": started})
            raise
        self.save(label + ".md", {"command": args, "returncode": result.returncode,
            "stdout": result.stdout, "stderr": result.stderr,
            "startedMonotonicSeconds": started, "completedMonotonicSeconds": time.monotonic()})
        if checked and result.returncode:
            raise RuntimeError(result.stderr or result.stdout)
        return result

    def reject(self, label, path, reason, expected_present):
        before = self.snapshot(label + "-pre")
        txid = self.execute("cardano-cli", "conway", "transaction", "txid",
            "--tx-file", path, "--output-text").stdout.strip()
        if len(txid) != 64 or any(c not in "0123456789abcdef" for c in txid):
            raise ValueError("unexpected scenario transaction ID")
        envelope = json.loads(self.execute("cat", path).stdout)
        self.save(label + "-transaction-cbor.md", envelope["cborHex"])
        result = self.execute("cardano-cli", "conway", "transaction", "submit",
            "--tx-file", path, "--testnet-magic", "1082026",
            "--socket-path", "/work/env/socket/node3/sock", check=False)
        # Preserve observed state even when the expected rejection did not happen.
        after = self.snapshot(label + "-post")
        present = [key for key in json.loads(after[1]["utxo"]) if key.split("#")[0] == txid]
        recognized = result.returncode != 0 and reason in result.stdout + result.stderr
        receipt = {"scope": "reference-local-submission-observation", "transactionId": txid,
            "returncode": result.returncode, "stdout": result.stdout, "stderr": result.stderr,
            "expectedReason": reason, "recognizedLedgerRejection": recognized,
            "observedOutputs": present, "expectedPreviouslyIncluded": expected_present,
            "singleAcquiredSnapshot": False, "invalidBlockRejection": "unsupported",
            "scalaNegativeComparison": "unsupported", "fullLedgerValidity": "unsupported",
            "nonInclusionScope": "stable paused-producer observation window only",
            "passed": False, "stableStateVerified": False}
        self.save(label + "-result.md", receipt)
        unchanged(before, after)
        receipt["stableStateVerified"] = True
        self.save(label + "-result.md", receipt)
        if bool(present) != expected_present:
            raise ValueError("unexpected scenario output presence")
        if not recognized:
            raise ValueError("expected ledger rejection missing: " + label)
        receipt["passed"] = True
        self.save(label + "-result.md", receipt)
        return receipt

    def wrong_key(self):
        self.execute("cardano-cli", "conway", "transaction", "sign",
            "--tx-body-file", "/work/transfer.body", "--signing-key-file",
            "/work/env/utxo-keys/utxo2/utxo.skey", "--testnet-magic", "1082026",
            "--out-file", "/work/scenario-wrong-key.signed")
        self.reject("wrong-key", "/work/scenario-wrong-key.signed", "MissingVKeyWitnessesUTXOW", False)

    def scala(self):
        positive = super().scala()
        selection = json.loads((self.out / "transfer-selection.md").read_text())
        try:
            self.producers("STOP")
            repeated = self.reject("repeated-included", "/work/transfer.signed", "BadInputsUTxO", True)
            self.execute("cardano-cli", "conway", "transaction", "build-raw",
                "--tx-in", selection["input"], "--tx-out",
                selection["destination"] + "+" + str(selection["amount"]), "--tx-out",
                selection["changeAddress"] + "+" + str(selection["change"] - 1),
                "--fee", str(selection["fee"] + 1), "--out-file", "/work/scenario-conflict.body")
            self.execute("cardano-cli", "conway", "transaction", "sign",
                "--tx-body-file", "/work/scenario-conflict.body", "--signing-key-file",
                "/work/env/utxo-keys/utxo1/utxo.skey", "--testnet-magic", "1082026",
                "--out-file", "/work/scenario-conflict.signed")
            conflict = self.reject("conflicting-spend", "/work/scenario-conflict.signed", "BadInputsUTxO", False)
            if conflict["transactionId"] == repeated["transactionId"]:
                raise ValueError("conflicting transaction must have distinct body identity")
        finally:
            self.producers("CONT")
        return {"positiveTransfer": positive, "repeated": repeated, "conflict": conflict,
                "wrongKey": "recognized rejection before valid submission",
                "scalaNegativeComparison": "unsupported"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--reference-image", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--scala-repo", required=True)
    parser.add_argument("--seconds", type=int, default=360)
    args = parser.parse_args()
    args.capture = False
    if not 240 <= args.seconds <= 480:
        parser.error("scenario budget must be 240..480 seconds")
    signal.signal(signal.SIGTERM, lambda *_: (_ for _ in ()).throw(KeyboardInterrupt()))
    ScenarioRunner(args).run()


if __name__ == "__main__": main()
