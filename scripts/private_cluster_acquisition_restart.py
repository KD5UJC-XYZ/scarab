#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Separate Scala process A/B acceptance; reference nodes are never restarted."""
import argparse
import hashlib
import json
from pathlib import Path
import signal
import subprocess

from private_cluster import JDK, Runner

PROFILE = "conway-pv9-header11.2-acquisition-v1"
GENESIS = ("byron-genesis.json", "shelley-genesis.json", "alonzo-genesis.json", "conway-genesis.json")


def assess_phases(a, b, context):
    for phase, report, loaded, published in (("a", a, 0, 2), ("b", b, 2, 4)):
        if (report.get("scope") != "bounded-acquisition-process-phase" or report.get("phase") != phase or
                report.get("complete") is not True or report.get("loadedCount") != loaded or
                report.get("publishedCount") != published or report.get("ledgerValidated") is not False or
                report.get("consensusValidated") is not False or report.get("segmentStoreReused") is not False):
            raise ValueError("incomplete or overstated phase evidence")
        for key in ("upstreamSource", "genesisDigest", "profile", "anchor", "networkMagic"):
            if report.get(key) != context[key]:
                raise ValueError("phase context differs from independent pin: " + key)
    if (b["loadedGeneration"] != a["publishedGeneration"] or b["loadedRevision"] != a["publishedRevision"] or
            b["loadedSource"] != a["publishedSource"] or b["publishedSource"] == b["loadedSource"] or
            b["publishedGeneration"] <= b["loadedGeneration"]):
        raise ValueError("revision/source extension evidence differs")


class AcquisitionRestartRunner(Runner):
    def await_phase(self, cid, args, label):
        return "0"

    def scala_run(self, args, label, network):
        repo = Path(self.args.scala_repo).resolve()
        command = ('exec java -XX:ActiveProcessorCount=1 -Xmx512m -cp "$(cat app/target/runtime-classpath.txt)" '
                   'lab.AcquisitionRestartCapture "$@"')
        cid = self.docker("run", "-d", "--pull=never", "--name", self.name + "-scala", "--network", network,
            "--cpus=1", "--memory=1g", "--memory-swap=1g", "--pids-limit=128", "--cap-drop=ALL",
            "--security-opt=no-new-privileges", "--user", "1000:1000", "--read-only", "--tmpfs", "/tmp:size=64m",
            "-v", str(repo) + ":/work:ro", "-v", str(self.out / "acquisition") + ":/checkpoint:rw",
            "-w", "/work", "--entrypoint=/bin/sh", JDK, "-c", command, "acquisition-phase", *args).stdout.strip()
        try:
            before = json.loads(self.docker("inspect", cid).stdout)[0]
            self.save(label + "-container-before.md", before)
            expected_status = self.await_phase(cid, args, label)
            status = self.docker("wait", cid, timeout=65).stdout.strip()
            after = json.loads(self.docker("inspect", cid).stdout)[0]
            self.save(label + "-container-after.md", after)
            captured = self.docker("logs", cid)
            logs = captured.stdout + captured.stderr
            self.save(label + ".md", logs)
            if status != expected_status or after["State"]["Running"] or after["State"]["ExitCode"] != int(expected_status):
                raise ValueError("Scala phase failed or has not exited: " + label)
            records = [json.loads(line) for line in logs.splitlines() if line.startswith("{")]
            records = [r for r in records if r.get("record") != "acknowledged-hold"]
            return records, {"containerId": cid, "hostPid": before["State"]["Pid"],
                             "startedAt": before["State"]["StartedAt"], "finishedAt": after["State"]["FinishedAt"]}
        finally:
            self.docker("rm", "-f", cid, check=False)

    def preflight(self):
        super().preflight()
        repo = Path(self.args.scala_repo).resolve()
        revision = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=repo, text=True).strip()
        if subprocess.check_output(["git", "status", "--porcelain"], cwd=repo, text=True):
            raise ValueError("live acquisition source must be committed and clean")
        names = subprocess.check_output(["git", "ls-files", "-z"], cwd=repo).decode().split("\0")
        classes = repo / "app/target/scala-3.3.8/classes/lab"
        hashes = {str(p.relative_to(repo)): hashlib.sha256(p.read_bytes()).hexdigest()
                  for pattern in ("AcquisitionCheckpoint*.class", "NioAcquisitionCheckpointStore*.class",
                                  "BoundedChainFollower*.class", "AcquisitionRestartCapture*.class")
                  for p in sorted(classes.glob(pattern))}
        if not hashes: raise ValueError("compiled checkpoint classes missing")
        self.save("source-receipt.md", {"gitCommit": revision, "classSha256": hashes,
            "trackedSha256": {name: hashlib.sha256((repo / name).read_bytes()).hexdigest() for name in names if name}})
        (self.out / "acquisition").mkdir(mode=0o700)
        rows, _ = self.scala_run(["probe"], "atomic-probe", "none")
        if rows != [{"atomicPublicationProbe": True, "directoryForceCompleted": True, "networkExecuted": False}]:
            raise ValueError("atomic publication preflight missing")

    def independent_context(self):
        hashes = {name: hashlib.sha256(self.read(name).encode()).hexdigest()
                  for name in (*GENESIS, "configuration.yaml", "node-data/node1/topology.json")}
        return hashes, json.loads(self.docker("inspect", self.name).stdout)[0]["Id"]

    def scala(self):
        port = str(int(self.read("node-data/node1/port").strip()))
        anchor = self.query("tip")
        if anchor.get("era") != "Conway" or type(anchor.get("slot")) is not int or anchor["slot"] < 0:
            raise ValueError("concrete Conway anchor required")
        hashes, cid = self.independent_context()
        genesis_recipe = "".join(name + "\t" + hashes[name] + "\n" for name in GENESIS)
        genesis = hashlib.sha256(genesis_recipe.encode()).hexdigest()
        recipe = {"referenceContainerId": cid, "referenceImage": self.image, "referenceHashes": hashes,
                  "genesisDigest": genesis, "profile": PROFILE, "networkMagic": 1082026,
                  "anchor": {"slot": anchor["slot"], "hash": anchor["hash"]}}
        source = hashlib.sha256(json.dumps(recipe, sort_keys=True, separators=(",", ":")).encode()).hexdigest()
        context = {"upstreamSource": source, "genesisDigest": genesis, "profile": PROFILE,
                   "networkMagic": 1082026, "anchor": recipe["anchor"]}
        self.save("independent-context.md", {"recipe": recipe, "genesisRecipe": genesis_recipe, "expected": context})
        self.phase_context = context
        common = [port, "1082026", str(anchor["slot"]), anchor["hash"], source, genesis]
        rows_a, proc_a = self.scala_run(["a", *common, "-", "-"], "process-a", "container:" + self.name)
        a = rows_a[-1]
        # Receipt stays outside the checkpoint directory and is never read from a mutable cursor.
        expected = {"generation": a["publishedGeneration"], "digest": a["publishedRevision"]}
        self.save("expected-revision-for-b.md", expected)
        self.save("checkpoint-a-sha256.md", hashlib.sha256((self.out / "acquisition/state/checkpoint.bin").read_bytes()).hexdigest())
        if self.independent_context() != (hashes, cid):
            raise ValueError("reference identity changed between processes")
        rows_b, proc_b = self.scala_run(["b", *common, str(expected["generation"]), expected["digest"]], "process-b", "container:" + self.name)
        b = rows_b[-1]
        assess_phases(a, b, context)
        original = lambda rows, stage: [(r["headerEnvelopeHex"], r["blockHex"]) for r in rows if r.get("record") == "original" and r.get("stage") == stage]
        prefix = original(rows_a, "published")
        if len(prefix) != 2 or original(rows_b, "loaded") != prefix or original(rows_b, "published")[:2] != prefix:
            raise ValueError("reopened/published original prefix differs")
        pa = next(r for r in rows_a if r.get("record") == "process")
        pb = next(r for r in rows_b if r.get("record") == "process")
        if (proc_a["containerId"] == proc_b["containerId"] or pa["nonce"] == pb["nonce"] or
                proc_a["hostPid"] <= 0 or proc_b["hostPid"] <= 0):
            raise ValueError("separate process identity evidence missing")
        if self.independent_context() != (hashes, cid): raise ValueError("reference identity changed after reopen")
        self.save("checkpoint-b-sha256.md", hashlib.sha256((self.out / "acquisition/state/checkpoint.bin").read_bytes()).hexdigest())
        report = {"scope": "bounded-acquisition-separate-process-restart", "passed": True,
                  "processA": proc_a, "processB": proc_b, "expectedRevisionForB": expected,
                  "newRevision": {"generation": b["publishedGeneration"], "digest": b["publishedRevision"]},
                  "upstreamSource": source, "sourceA": a["publishedSource"], "sourceB": b["publishedSource"],
                  "retainedOriginals": 2, "publishedOriginals": 4, "prefixBytesIdentical": True,
                  "segmentStoreReused": False, "ledgerRecovery": False, "powerLossRecovery": False}
        self.save("acquisition-restart-result.md", report)
        return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--reference-image", required=True)
    parser.add_argument("--scala-repo", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--seconds", type=int, default=420)
    args = parser.parse_args()
    if not 300 <= args.seconds <= 480: parser.error("budget must be 300..480 seconds")
    args.capture = False
    signal.signal(signal.SIGTERM, lambda *_: (_ for _ in ()).throw(KeyboardInterrupt()))
    AcquisitionRestartRunner(args).run()


if __name__ == "__main__": main()
