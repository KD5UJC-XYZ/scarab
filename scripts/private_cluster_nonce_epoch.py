#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Bounded single-epoch rotation under explicit pre-anchor supplied verification keys."""
import argparse
import hashlib
import json
import signal
import time
from private_cluster import Runner, JDK
from private_cluster_coherent import FIXTURE
from private_cluster_nonce_freeze import NonceFreezeRunner, PINS
from private_cluster_transfer import converged_tips

KEY_MODE = "pre-anchor-supplied-verification-keys-v1"
PROFILE = "praos-nonce-epoch-rotation-v1"


def checked_window(pre, post):
    for tip in (pre, post):
        if (tip.get("era") != "Conway"
                or any(type(tip.get(k)) is not int or tip[k] < 0
                       for k in ("epoch", "slot", "block", "slotInEpoch"))
                or tip["epoch"] != tip["slot"] // 500
                or tip["slotInEpoch"] != tip["slot"] % 500):
            raise ValueError("fixed Conway epoch geometry required")
    if (pre["epoch"] < 1 or post["epoch"] != pre["epoch"] + 1
            or not 400 <= pre["slotInEpoch"] <= 460
            or not 40 <= post["slotInEpoch"] <= 180
            or not 2 <= post["block"] - pre["block"] <= 16):
        raise ValueError("one epoch rotation requires bounded endpoints and 2..16 complete successors")
    return {"preEpoch": pre["epoch"], "postEpoch": post["epoch"],
            "epochBoundarySlot": (pre["epoch"] + 1) * 500,
            "expectedCompleteBlocks": post["block"] - pre["block"], "maxBlocks": 16,
            "keyMode": KEY_MODE, "requiredActualEpochTransitions": 1,
            "successorRequiredInBothEpochs": True, "exactBoundaryHeaderRequired": False,
            "registrationContinuityProven": False, "leaderEligibilityChecked": False}


def checked_receipt(result, pre, post):
    rows = [json.loads(line) for line in result.stdout.splitlines() if line.startswith("{")]
    report = rows[-1] if rows else {}
    if (result.returncode or report.get("scope") != "nonce-epoch-rotation-observation"
            or report.get("profile") != PROFILE
            or report.get("anchorSlot") != pre["slot"] or report.get("lastSlot") != post["slot"]
            or report.get("capturedBlocks") != post["block"] - pre["block"]):
        raise ValueError("scoped epoch-rotation receipt missing or endpoint mismatch")
    for key in ("passed", "epochTickChecked", "rollbackReapplyChecked", "fiveNonceFieldsMatched",
                "finalCountersMatched", "previousEpochNonceKnown", "suppliedRegistrationKeysOnly"):
        if report.get(key) is not True:
            raise ValueError("required rotation receipt predicate missing: " + key)
    for key in ("leaderEligibilityChecked", "stateDerivedConsensus", "consensusValidated",
                "fullLedgerValidated", "crossEpochRegistrationContinuityProven",
                "certificateRegistrationAuthorityValidated", "coherentBranchPublished",
                "referenceSnapshotAtomic", "authenticatedSnapshot"):
        if report.get(key) is not False:
            raise ValueError("rotation receipt exceeds supported claims: " + key)
    return report


class NonceEpochRunner(NonceFreezeRunner):
    # Inherit the no-transaction execute path and short, bracketed nonce_snapshot only.
    def scala(self):
        handshake = Runner.scala(self)
        observations = []; until = min(self.deadline - 170, time.monotonic() + 230)
        while time.monotonic() < until:
            tips = [self.query("tip", i) for i in (1, 2, 3)]
            observations.append(tips); self.save("nonce-epoch-readiness.md", observations)
            tip = tips[-1]
            if (converged_tips(tips) and tip.get("era") == "Conway"
                    and tip.get("epoch", 0) >= 1 and 400 <= tip.get("slotInEpoch", 0) <= 425):
                break
            time.sleep(0.2)
        else:
            raise TimeoutError("late-epoch nonce anchor unavailable within budget")
        try:
            self.producers("STOP")
            pre = self.nonce_snapshot("pre")
            if pre["epoch"] < 1 or not 400 <= pre["slotInEpoch"] <= 460:
                raise ValueError("pre-anchor moved outside late-epoch profile")
            self.save("transfer-genesis.md", self.read("shelley-genesis.json"))
        finally:
            self.producers("CONT")
        until = min(self.deadline - 110, time.monotonic() + 40)
        progress = []
        while time.monotonic() < until:
            tip = self.query("tip")
            progress.append(tip); self.save("nonce-epoch-window-progress.md", progress)
            if tip.get("epoch", 0) > pre["epoch"] + 1:
                raise ValueError("second epoch crossing is outside the profile")
            if tip.get("epoch") == pre["epoch"] + 1 and tip.get("slotInEpoch", 0) >= 40:
                break
            time.sleep(0.2)
        else:
            raise TimeoutError("next-epoch nonce successor unavailable")
        try:
            self.producers("STOP")
            post = self.nonce_snapshot("post")
            self.save("nonce-epoch-window.md", checked_window(pre, post))
        finally:
            self.producers("CONT")
        # All producers run again before network acquisition/cryptography.
        self.save("nonce-epoch-context.md", "format\t" + PROFILE + "\nkeyMode\t" + KEY_MODE + "\n" + "".join(
            k + "\t" + hashlib.sha256((self.out / n).read_bytes()).hexdigest() + "\n"
            for k, n in sorted(PINS.items())))
        port = str(int(self.read("node-data/node3/port")))
        result = self.docker("run", "--rm", "--pull=never", "--name", self.name + "-scala",
            "--network=container:" + self.name, "--cpus=1", "--memory=1g", "--memory-swap=1g",
            "--pids-limit=128", "--cap-drop=ALL", "--security-opt=no-new-privileges", "--user", "1000:1000",
            "--read-only", "--tmpfs", "/tmp:size=64m", "-v", str(self.args.scala_repo) + ":/work:ro",
            "-v", str(self.out) + ":/evidence:ro", "-w", "/work", "--entrypoint=/bin/sh", JDK, "-c",
            'exec java -XX:ActiveProcessorCount=1 -Xmx512m -cp "$(cat app/target/runtime-classpath.txt)" lab.Main nonce-epoch ' + port + ' /evidence',
            check=False, timeout=75)
        self.save("scala-nonce-epoch.md", result.stdout + result.stderr)
        return {"handshake": handshake, "nonceEpoch": checked_receipt(result, pre, post),
                "singleAcquiredSnapshot": False}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--fixture-profile", required=True, choices=[FIXTURE])
    p.add_argument("--reference-image", required=True); p.add_argument("--output", required=True)
    p.add_argument("--scala-repo", required=True); p.add_argument("--seconds", type=int, default=480)
    args = p.parse_args(); args.capture = False
    if not 420 <= args.seconds <= 540: p.error("nonce workload budget must be 420..540 seconds")
    signal.signal(signal.SIGTERM, lambda *_: (_ for _ in ()).throw(KeyboardInterrupt()))
    NonceEpochRunner(args).run()


if __name__ == "__main__": main()
