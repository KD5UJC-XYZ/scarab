#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Bounded local certificate capture with explicit non-atomic reference state exports."""
import argparse
import hashlib
import json
from pathlib import Path
import signal
import time
from private_cluster import JDK, Runner
from private_cluster_transfer import TransferRunner, converged_tips


SOURCES = {
    "genesisSha256": "transfer-genesis.md",
    "preTipsSha256": "pre-tips.md", "postTipsSha256": "post-tips.md",
    "preLedgerSha256": "pre-ledger-state.md", "postLedgerSha256": "post-ledger-state.md",
    "preProtocolSha256": "pre-protocol-state.md", "postProtocolSha256": "post-protocol-state.md",
    "preParametersSha256": "pre-parameters.md", "postParametersSha256": "post-parameters.md",
}


def checked_window(pre, post):
    for tip in (pre, post):
        if (tip.get("era") != "Conway" or any(type(tip.get(k)) is not int or tip[k] < 0
                for k in ("block", "slot", "epoch")) or
                not converged_tips([tip, tip, tip])):
            raise ValueError("complete Conway point required")
    count = post["block"] - pre["block"]
    if pre["epoch"] != post["epoch"] or pre["slot"] >= post["slot"] or not 4 <= count <= 8:
        raise ValueError("same-epoch four-to-eight-block window required")
    return count


def manifest(directory):
    result = {"format": "certificate-context-v1"}
    for key, name in SOURCES.items():
        raw = (Path(directory) / name).read_bytes()
        if not raw or len(raw) > 4 * 1024 * 1024:
            raise ValueError("bounded original source required")
        result[key] = hashlib.sha256(raw).hexdigest()
    return "".join(k + "\t" + v + "\n" for k, v in result.items())


class CertificateRunner(TransferRunner):
    def execute(self, *args, **kwargs):
        if args[:3] == ("cardano-cli", "conway", "transaction"):
            raise ValueError("certificate capture forbids transaction operations")
        return super().execute(*args, **kwargs)

    def scala(self):
        handshake = Runner.scala(self)
        observations = []
        until = min(self.deadline - 200, time.monotonic() + 110)
        while time.monotonic() < until:
            tips = [self.query("tip", i) for i in (1, 2, 3)]
            observations.append(tips)
            self.save("certificate-readiness.md", observations)
            if (converged_tips(tips) and all(t.get("era") == "Conway" and
                    t.get("epoch", 0) >= 1 and t.get("slotInEpoch", 501) <= 30 for t in tips)):
                break
            time.sleep(0.5)
        else:
            raise TimeoutError("certificate stable early-epoch anchor unavailable")
        relay = self.read("logs/node3/stdout.log")
        if "shelleyKESSource = Nothing" not in relay or "shelleyVRFFile = Nothing" not in relay:
            raise ValueError("non-producing relay not established")
        self.save("relay-role.md", relay.splitlines()[0])
        try:
            self.producers("STOP")
            pre, _ = self.snapshot("pre")
            self.save("transfer-genesis.md", self.read("shelley-genesis.json"))
        finally:
            self.producers("CONT")
        growth = []
        until = min(self.deadline - 140, time.monotonic() + 55)
        while time.monotonic() < until:
            tip = self.query("tip")
            growth.append(tip)
            self.save("certificate-range-growth.md", growth)
            if tip.get("epoch") != pre["epoch"]:
                raise ValueError("certificate registration epoch changed")
            if tip.get("block", 0) >= pre["block"] + 4:
                break
            time.sleep(0.25)
        else:
            raise TimeoutError("certificate multi-block growth deadline")
        try:
            self.producers("STOP")
            post, _ = self.snapshot("post")
            count = checked_window(pre, post)
            self.save("certificate-context.md", manifest(self.out))
            repo = Path(self.args.scala_repo).resolve()
            source_names = ["app/src/main/scala/lab/CertificateCapture.scala",
                            "app/src/main/scala/lab/CertificateBranch.scala",
                            "core/src/main/scala/lab/header/PraosCertificateState.scala"]
            classes = list((repo / "app/target/scala-3.3.8/classes/lab").glob("Certificate*.class"))
            core_classes = list((repo / "core/target/scala-3.3.8/classes/lab/header").glob("PraosCertificateState*.class"))
            if not classes or not core_classes:
                raise ValueError("compiled certificate classes missing")
            classes += core_classes
            self.save("certificate-source-receipt.md", {
                "sources": {n: hashlib.sha256((repo / n).read_bytes()).hexdigest() for n in source_names},
                "classes": {str(p.relative_to(repo)): hashlib.sha256(p.read_bytes()).hexdigest() for p in classes},
                "manifestSha256": hashlib.sha256((self.out / "certificate-context.md").read_bytes()).hexdigest(),
                "singleAcquiredSnapshot": False,
                "limitation": "Separate original CLI exports under paused-producer/stable-tip brackets; not an authenticated atomic snapshot."})
            port = self.read("node-data/node3/port").strip()
            if not port.isdecimal() or not 1 <= int(port) <= 65535:
                raise ValueError("invalid relay port")
            result = self.docker("run", "--rm", "--pull=never", "--name", self.name + "-scala",
                "--network=container:" + self.name, "--cpus=1", "--memory=1g", "--memory-swap=1g",
                "--pids-limit=128", "--cap-drop=ALL", "--security-opt=no-new-privileges",
                "--user", "1000:1000", "--read-only", "--tmpfs", "/tmp:size=64m",
                "-v", str(repo) + ":/work:ro", "-v", str(self.out) + ":/evidence:ro",
                "-w", "/work", "--entrypoint=/bin/sh", JDK, "-c",
                'exec java -XX:ActiveProcessorCount=1 -Xmx512m -cp "$(cat app/target/runtime-classpath.txt)" '
                'lab.CertificateCapture ' + port + " /evidence", check=False, timeout=70)
            self.save("scala-certificate.md", result.stdout + result.stderr)
            if result.returncode:
                raise ValueError("certificate capture/replay failed")
            records = [json.loads(line) for line in result.stdout.splitlines() if line.startswith("{")]
            report = check_report(records, count)
            self.save("certificate-assessment.md", report)
            return {"handshake": handshake, "certificate": report,
                    "singleAcquiredSnapshot": False, "transactionsSubmitted": False}
        finally:
            self.producers("CONT")


def check_report(records, count):
    report = records[-1] if records else {}
    if (report.get("scope") != "experimental-praos-certificate-state-v1" or
            report.get("passed") is not True or report.get("blockCount") != count or
            any(report.get(k) is not True for k in ("opCertSignaturesChecked", "kesSignaturesChecked",
                "registrationChecked", "finalCountersMatched", "rollbackReapplyMatched")) or
            any(report.get(k) is not False for k in ("consensusValidated", "fullLedgerValidated",
                "vrfEligibilityChecked", "referenceSnapshotAtomic"))):
        raise ValueError("complete scoped certificate receipt required")
    originals = [r for r in records if r.get("record") == "transfer-range-block"]
    if len(originals) != count:
        raise ValueError("original-byte capture count mismatch")
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--reference-image", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--scala-repo", required=True)
    parser.add_argument("--seconds", type=int, default=420)
    args = parser.parse_args()
    args.capture = False
    if not 300 <= args.seconds <= 480:
        parser.error("certificate workload budget must be 300..480 seconds")
    signal.signal(signal.SIGTERM, lambda *_: (_ for _ in ()).throw(KeyboardInterrupt()))
    CertificateRunner(args).run()


if __name__ == "__main__": main()
