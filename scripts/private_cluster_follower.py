#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Bounded acquisition/resume exercise using the existing isolated cluster lifecycle."""
import argparse
import hashlib
import json
from pathlib import Path
import signal
import subprocess

from private_cluster import JDK, Runner


class FollowerRunner(Runner):
    def scala(self):
        repo = Path(self.args.scala_repo).resolve()
        port = self.read("node-data/node1/port").strip()
        if not port.isdecimal() or not 1 <= int(port) <= 65535:
            raise ValueError("invalid reference port")
        anchor = self.query("tip")
        anchor_hash = anchor.get("hash", "")
        if (anchor.get("era") != "Conway" or type(anchor.get("slot")) is not int or
                anchor["slot"] < 0 or len(anchor_hash) != 64 or
                any(c not in "0123456789abcdef" for c in anchor_hash)):
            raise ValueError("invalid Conway anchor")
        self.save("follower-anchor.md", anchor)
        revision = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=repo, text=True).strip()
        status = subprocess.check_output(["git", "status", "--porcelain"], cwd=repo, text=True)
        if status:
            raise ValueError("live follower source checkout must be clean")
        tracked = subprocess.check_output(["git", "ls-files", "-z"], cwd=repo).decode().split("\0")
        hashes = {name: hashlib.sha256((repo / name).read_bytes()).hexdigest()
                  for name in tracked if name}
        compiled = repo / "app/target/scala-3.3.8/classes/lab"
        class_hashes = {str(p.relative_to(repo)): hashlib.sha256(p.read_bytes()).hexdigest()
                        for pattern in ("BoundedChainFollower*.class", "BoundedFollowerCapture*.class")
                        for p in sorted(compiled.glob(pattern))}
        if not class_hashes:
            raise ValueError("compiled follower classes missing")
        self.save("source-receipt.md", {"gitCommit": revision, "trackedSha256": hashes,
                                       "followerClassSha256": class_hashes})
        args = [port, "1082026", str(anchor["slot"]), anchor_hash, "2", "4"]
        result = self.docker("run", "--rm", "--pull=never", "--name", self.name + "-scala",
            "--network=container:" + self.name, "--cpus=1", "--memory=1g", "--memory-swap=1g",
            "--pids-limit=128", "--cap-drop=ALL", "--security-opt=no-new-privileges",
            "--user", "1000:1000", "--read-only", "--tmpfs", "/tmp:size=64m",
            "-v", str(repo) + ":/work:ro", "-w", "/work", "--entrypoint=/bin/sh", JDK, "-c",
            'exec java -XX:ActiveProcessorCount=1 -Xmx512m -cp "$(cat app/target/runtime-classpath.txt)" '
            'lab.BoundedFollowerCapture ' + " ".join(args), check=False, timeout=80)
        self.save("scala-follower.md", result.stdout + result.stderr)
        if result.returncode:
            raise ValueError("bounded follower failed; see scala-follower.md")
        records = [json.loads(line) for line in result.stdout.splitlines() if line.startswith("{")]
        report = check_report(records)
        self.save("follower-assessment.md", report)
        return report


def check_report(records):
    report = records[-1] if records else {}
    if (report.get("scope") != "bounded-private-cluster-acquisition" or
            report.get("complete") is not True or report.get("initialCount") != 2 or
            report.get("resumedCount") != 4 or report.get("disconnectInjectedLocally") is not True or
            report.get("resumeAttempts") != 2 or report.get("ledgerValidated") is not False or
            report.get("consensusValidated") is not False or
            report.get("initialPrefixPresentAtEnd") is not True):
        raise ValueError("complete acquisition/reconnect evidence missing")
    originals = [r for r in records if r.get("record") == "acquisition-original"]
    initial = [r for r in originals if r.get("phase") == "initial"]
    resumed = [r for r in originals if r.get("phase") == "resumed"]
    if len(initial) != 2 or len(resumed) != 4:
        raise ValueError("original-byte evidence count mismatch")
    if any((a.get("headerEnvelopeHex"), a.get("blockHex")) !=
           (b.get("headerEnvelopeHex"), b.get("blockHex")) for a, b in zip(initial, resumed)):
        raise ValueError("resumed original prefix differs")
    offers = [r for r in records if r.get("record") == "resume-intersection-offer"]
    selected = [r for r in records if r.get("record") == "resume-intersection-selected"]
    if ([r.get("attempt") for r in offers] != [0, 1] or
            [r.get("attempt") for r in selected] != [0, 1] or
            len(offers[0].get("points", [])) != 3 or offers[0]["points"] != offers[1].get("points") or
            any(r.get("point") != offers[0]["points"][0] for r in selected)):
        raise ValueError("retained-tip reintersection evidence missing")
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--reference-image", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--scala-repo", required=True)
    parser.add_argument("--seconds", type=int, default=240)
    args = parser.parse_args()
    if not 180 <= args.seconds <= 480:
        parser.error("budget must be 180..480 seconds")
    args.capture = False
    signal.signal(signal.SIGTERM, lambda *_: (_ for _ in ()).throw(KeyboardInterrupt()))
    FollowerRunner(args).run()


if __name__ == "__main__":
    main()
