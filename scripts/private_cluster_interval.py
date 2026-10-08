#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Separated local interval observations; never an exact wall-clock boundary proof."""
import argparse
import json
import signal
import time
from private_cluster_transfer import TransferRunner
from private_cluster_scenarios import ScenarioRunner
from private_cluster_relay import RelayRunner

PROFILE = "conway-pv9-cluster-ada-interval-transition-v1"
MAX_SLOT = (1 << 64) - 1


def interval_plan(slot):
    if type(slot) is not int or not 2 <= slot <= MAX_SLOT - 100000:
        raise ValueError("bounded positive observed slot required")
    return {"observedPreTipSlot": slot,
            "positive": {"lower": max(0, slot - 100), "upper": slot + 1000},
            "expired": {"lower": None, "upper": slot - 1},
            "not-yet-valid": {"lower": slot + 100000, "upper": None},
            "referenceEvaluationSlotEstablished": False,
            "exactReferenceBoundaryProof": False}


def interval_flags(bounds):
    flags = []
    for key, flag in (("lower", "--invalid-before"), ("upper", "--invalid-hereafter")):
        value = bounds[key]
        if value is not None:
            if type(value) is not int or not 0 <= value <= MAX_SLOT:
                raise ValueError("interval bound must be uint64")
            flags.extend((flag, str(value)))
    return flags


def check_inclusion_report(records, plan, transfer):
    selected = [row for row in records if row.get("record") == "transaction-validity-interval"]
    if len(selected) != 1:
        raise ValueError("one original containing-block interval receipt required")
    row = selected[0]
    slot = row.get("slot")
    expected = plan["positive"]
    if (row.get("profile") != PROFILE or row.get("slotSource") != "containing-block"
            or row.get("satisfied") is not True or row.get("fullLedgerValidated") is not False
            or type(slot) is not int or not expected["lower"] <= slot < expected["upper"]
            or row.get("lower") != expected["lower"] or row.get("upper") != expected["upper"]
            or row.get("transactionId") != transfer.get("transactionId")
            or transfer.get("profile") != PROFILE or transfer.get("validityIntervalChecked") is not True):
        raise ValueError("actual containing-block interval receipt differs")
    return row


class IntervalRunner(ScenarioRunner, RelayRunner):
    # Reuse full-CBOR submission receipts/reject guards, not the other scenario's scala method.
    def prepare_transfer(self):
        RelayRunner.prepare_transfer(self)
        until = min(self.deadline - 180, time.monotonic() + 70)
        observations = []
        while time.monotonic() < until:
            try:
                tip = self.query("tip")
                observations.append(tip)
                self.save("interval-readiness.md", observations)
                if (isinstance(tip.get("hash"), str) and len(tip["hash"]) == 64
                        and tip.get("era") == "Conway" and type(tip.get("slot")) is int
                        and tip["slot"] >= 2 and type(tip.get("slotInEpoch")) is int
                        and 0 <= tip["slotInEpoch"] <= 30):
                    return
            except (RuntimeError, json.JSONDecodeError) as exc:
                observations.append({"unready": str(exc)})
                self.save("interval-readiness.md", observations)
            time.sleep(0.5)
        raise TimeoutError("early-epoch local interval readiness unavailable")

    def snapshot(self, label):
        result = super().snapshot(label)
        if label == "pre":
            self.plan = interval_plan(result[0]["slot"])
            self.save("interval-plan.md", self.plan)
        return result

    def execute(self, *args, **kwargs):
        if (args[:4] == ("cardano-cli", "conway", "transaction", "build-raw")
                and "--out-file" in args and args[args.index("--out-file") + 1] == "/work/transfer.body"):
            if "--invalid-before" in args or "--invalid-hereafter" in args:
                raise ValueError("positive interval already supplied")
            args = (*args, *interval_flags(self.plan["positive"]))
        return super().execute(*args, **kwargs)

    def before_submit(self):
        selection = json.loads((self.out / "transfer-selection.md").read_text())
        for label in ("expired", "not-yet-valid"):
            path = "/work/interval-" + label
            self.execute("cardano-cli", "conway", "transaction", "build-raw",
                "--tx-in", selection["input"], "--tx-out",
                selection["destination"] + "+" + str(selection["amount"]), "--tx-out",
                selection["changeAddress"] + "+" + str(selection["change"]),
                "--fee", str(selection["fee"]), *interval_flags(self.plan[label]),
                "--out-file", path + ".body")
            self.execute("cardano-cli", "conway", "transaction", "sign",
                "--tx-body-file", path + ".body", "--signing-key-file",
                "/work/env/utxo-keys/utxo1/utxo.skey", "--testnet-magic", "1082026",
                "--out-file", path + ".signed")
            receipt = self.reject(label, path + ".signed", "OutsideValidityIntervalUTxO", False)
            receipt.update(expectedInterval=self.plan[label],
                observedPreTipSlot=self.plan["observedPreTipSlot"],
                referenceEvaluationSlotEstablished=False, exactReferenceBoundaryProof=False)
            self.save(label + "-result.md", receipt)

    def scala(self):
        self.negative_started = True  # Disable the inherited wrong-key submission hook.
        result = RelayRunner.scala(self)
        records = [json.loads(line) for line in (self.out / "scala-transfer.md").read_text().splitlines()
                   if line.startswith("{")]
        receipt = check_inclusion_report(records, self.plan, result["transfer"])
        self.save("interval-inclusion.md", receipt)
        result.update(intervalNegatives="two exact reference error categories with stable paused state",
                      referenceAdmissionSlotEstablished=False, exactReferenceBoundaryProof=False)
        return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--reference-image", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--scala-repo", required=True)
    parser.add_argument("--seconds", type=int, default=420)
    args = parser.parse_args()
    args.capture = False
    if not 300 <= args.seconds <= 480:
        parser.error("interval workload budget must be 300..480 seconds")
    signal.signal(signal.SIGTERM, lambda *_: (_ for _ in ()).throw(KeyboardInterrupt()))
    IntervalRunner(args).run()


if __name__ == "__main__": main()
