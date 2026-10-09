#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Same-epoch candidate-freeze observation, with independent preseed and post oracle."""
import argparse
import hashlib
import json
import signal
import time
from private_cluster import Runner, JDK
from private_cluster_coherent import CoherentRunner, FIXTURE
from private_cluster_transfer import TransferRunner, converged_tips

PINS = {"genesisSha256": "transfer-genesis.md"}
for side in ("pre", "post"):
    for field, name in (("Tips", "tips"), ("Protocol", "protocol-state"),
                        ("Ledger", "ledger-state"), ("Parameters", "parameters")):
        PINS[side + field + "Sha256"] = side + "-" + name + ".md"


def checked_window(pre, post):
    for tip in (pre, post):
        if (tip.get("era") != "Conway" or type(tip.get("epoch")) is not int
                or type(tip.get("slot")) is not int or type(tip.get("block")) is not int
                or tip["epoch"] != tip["slot"] // 500 or tip.get("slotInEpoch") != tip["slot"] % 500):
            raise ValueError("fixed Conway epoch geometry required")
    if (pre["epoch"] != post["epoch"] or not 1 <= pre["slotInEpoch"] <= 40
            or not 110 <= post["slotInEpoch"] <= 200
            or not 2 <= post["block"] - pre["block"] <= 16):
        raise ValueError("same-epoch freeze window requires 2..16 complete successors")
    return {"epoch": pre["epoch"], "candidateCutoffRelativeSlot": 100,
            "firstSlotNextEpoch": (pre["epoch"] + 1) * 500, "stabilizationWindow": 400,
            "expectedCompleteBlocks": post["block"] - pre["block"], "maxBlocks": 16,
            "exactBoundaryHeaderRequired": False, "epochTickChecked": False}


class NonceFreezeRunner(CoherentRunner):
    def execute(self, *args, **kwargs):
        # No transaction construction/submission hook participates in this capture.
        return TransferRunner.execute(self, *args, **kwargs)

    def nonce_snapshot(self, label):
        pauses = [self.pause_evidence()]; tips = []; originals = []
        def tip():
            raw = self.relay_query("tip"); value = json.loads(raw)
            self.save(label + "-tip-original-" + str(len(tips)) + ".md", raw)
            tips.append(value); originals.append(raw); return value
        first = tip(); time.sleep(0.25); tip()
        for name, query, flags in (("protocol-state", "protocol-state", ("--output-json",)),
                                   ("ledger-state", "ledger-state", ("--output-json",)),
                                   ("parameters", "protocol-parameters", ())):
            self.save(label + "-" + name + ".md", self.relay_query(query, *flags))
            tip(); pauses.append(self.pause_evidence())
        fields = ("hash", "slot", "block", "epoch", "era")
        if any(tuple(t[k] for k in fields) != tuple(first[k] for k in fields) for t in tips):
            raise ValueError("nonce snapshot point changed")
        self.save(label + "-tips.md", "[" + ",".join(originals) + "]")
        self.save(label + "-producer-brackets.md", pauses)
        self.save(label + "-binding.md", {"point": first, "singleAcquiredSnapshot": False,
            "method": "paused-owned-producers-and-stable-relay-tip-brackets"})
        return first

    def scala(self):
        handshake = Runner.scala(self)
        # Seek actual early-epoch headers, not a presumed block at an exact slot.
        observations = []; until = min(self.deadline - 150, time.monotonic() + 210)
        while time.monotonic() < until:
            tips = [self.query("tip", i) for i in (1, 2, 3)]
            observations.append(tips); self.save("nonce-readiness.md", observations)
            tip = tips[-1]
            if (converged_tips(tips) and tip.get("era") == "Conway" and tip.get("epoch", 0) >= 1
                    and 1 <= tip.get("slotInEpoch", 501) <= 25):
                break
            time.sleep(0.2)
        else:
            raise TimeoutError("early-epoch nonce anchor unavailable")
        try:
            self.producers("STOP")
            pre = self.nonce_snapshot("pre")
            if not 1 <= pre["slotInEpoch"] <= 40:
                raise ValueError("anchor no longer precedes candidate cutoff with headroom")
            self.save("transfer-genesis.md", self.read("shelley-genesis.json"))
        finally:
            self.producers("CONT")
        until = min(self.deadline - 110, time.monotonic() + 25)
        progress = []
        while time.monotonic() < until:
            tip = self.query("tip")
            progress.append(tip); self.save("nonce-window-progress.md", progress)
            if tip.get("epoch") != pre["epoch"]:
                raise ValueError("epoch tick is outside the capture profile")
            if tip.get("slotInEpoch", 0) >= 110:
                break
            time.sleep(0.2)
        else:
            raise TimeoutError("same-epoch candidate-freeze successor unavailable")
        try:
            self.producers("STOP")
            post = self.nonce_snapshot("post")
            self.save("nonce-window.md", checked_window(pre, post))
        finally:
            self.producers("CONT")
        # Never hold producers during network acquisition or cryptographic replay.
        self.save("nonce-freeze-context.md", "format\tpraos-nonce-candidate-freeze-v1\n" + "".join(
            k + "\t" + hashlib.sha256((self.out / n).read_bytes()).hexdigest() + "\n" for k, n in sorted(PINS.items())))
        port = str(int(self.read("node-data/node3/port")))
        result = self.docker("run", "--rm", "--pull=never", "--name", self.name + "-scala",
            "--network=container:" + self.name, "--cpus=1", "--memory=1g", "--memory-swap=1g",
            "--pids-limit=128", "--cap-drop=ALL", "--security-opt=no-new-privileges", "--user", "1000:1000",
            "--read-only", "--tmpfs", "/tmp:size=64m", "-v", str(self.args.scala_repo) + ":/work:ro",
            "-v", str(self.out) + ":/evidence:ro", "-w", "/work", "--entrypoint=/bin/sh", JDK, "-c",
            'exec java -XX:ActiveProcessorCount=1 -Xmx512m -cp "$(cat app/target/runtime-classpath.txt)" lab.Main nonce-freeze ' + port + ' /evidence',
            check=False, timeout=75)
        self.save("scala-nonce-freeze.md", result.stdout + result.stderr)
        rows = [json.loads(line) for line in result.stdout.splitlines() if line.startswith("{")]
        report = rows[-1] if rows else {}
        if (result.returncode or report.get("scope") != "nonce-candidate-freeze-observation"
                or report.get("passed") is not True or report.get("candidateUpdatedBeforeCutoff") is not True
                or report.get("candidateFrozenAfterCutoff") is not True or report.get("rollbackReapplyChecked") is not True
                or report.get("leaderEligibilityChecked") is not False or report.get("stateDerivedConsensus") is not False
                or report.get("epochTickChecked") is not False):
            raise ValueError("scoped candidate-freeze replay receipt missing")
        return {"handshake": handshake, "nonceFreeze": report, "singleAcquiredSnapshot": False}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--fixture-profile", required=True, choices=[FIXTURE])
    p.add_argument("--reference-image", required=True); p.add_argument("--output", required=True)
    p.add_argument("--scala-repo", required=True); p.add_argument("--seconds", type=int, default=480)
    args = p.parse_args(); args.capture = False
    if not 420 <= args.seconds <= 540: p.error("nonce workload budget must be 420..540 seconds")
    signal.signal(signal.SIGTERM, lambda *_: (_ for _ in ()).throw(KeyboardInterrupt()))
    NonceFreezeRunner(args).run()


if __name__ == "__main__": main()
