#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Local Docker reference smoke. No acquisition, public peers, transactions or key export."""
import argparse
import json
import os
from pathlib import Path
import signal
import subprocess
import threading
import time
import uuid

JDK = "eclipse-temurin@sha256:b9142586f9712700c6c9e07adcedfb18608b1a3a056e4001423a3354adfa9d80"
BINARY_HASHES = {
    "cardano-node": "ee396604345cc07c7169391ef3f5364886d4b7fe0d539aff6919b470e7a336c3",
    "cardano-cli": "0ac45e874599fac4ee6ca4fd9602c0ddb9854a62be5f365dfa425eff9f4bd0ed",
    "cardano-testnet": "124e10b604f2653ce8e736f46da2b104aa17325e0f23034cb7dbb5f96f95b149",
}
PUBLIC_FILES = ["configuration.yaml", "byron-genesis.json", "shelley-genesis.json",
                "alonzo-genesis.json", "conway-genesis.json", "current-stake-pools.json"]


def profile(genesis, config, topologies):
    """Fail closed on an unexpected generator profile; never accept external peers."""
    if (genesis["epochLength"], genesis["slotLength"], genesis["securityParam"],
            genesis["activeSlotsCoeff"]) != (500, 0.1, 5, 0.05):
        raise ValueError("unexpected development timing parameters")
    if genesis["networkMagic"] != 1082026:
        raise ValueError("unexpected private network magic")
    genesis["protocolParams"]["protocolVersion"] = {"major": 9, "minor": 0}
    for era in ["Shelley", "Allegra", "Mary", "Alonzo", "Babbage", "Conway"]:
        if config.get("Test" + era + "HardForkAtEpoch") != 0:
            raise ValueError("expected epoch-zero transition: " + era)
    if any("DijkstraHardFork" in key for key in config):
        raise ValueError("Dijkstra transition forbidden")
    config.pop("DijkstraGenesisFile", None)
    config["PeerSharing"] = False
    # Generated trace dispatcher uses local stdout; no trace forwarding/metrics added.
    allowed = {"AlonzoGenesisFile", "ByronGenesisFile", "ConwayGenesisFile",
               "ExperimentalProtocolsEnabled", "LastKnownBlockVersion-Alt",
               "LastKnownBlockVersion-Major", "LastKnownBlockVersion-Minor",
               "MaxConcurrencyBulkSync", "MaxConcurrencyDeadline", "PBftSignatureThreshold",
               "PeerSharing", "Protocol", "RequiresNetworkMagic", "ShelleyGenesisFile",
               "SocketPath", "TraceOptions"}
    allowed.update("Test" + era + "HardForkAtEpoch" for era in
                   ["Shelley", "Allegra", "Mary", "Alonzo", "Babbage", "Conway"])
    if set(config) - allowed or config.get("TraceOptions") != {}:
        raise ValueError("unexpected configuration/trace destinations")
    for topology in topologies:
        if set(topology) != {"bootstrapPeers", "localRoots", "publicRoots", "useLedgerAfterSlot"}:
            raise ValueError("unexpected topology fields")
        if topology["bootstrapPeers"] is not None or topology["useLedgerAfterSlot"] != -1:
            raise ValueError("peer discovery must be disabled")
        if any(root["accessPoints"] for root in topology["publicRoots"]):
            raise ValueError("public peers forbidden")
        for root in topology["localRoots"]:
            if root["advertise"] or any(peer["address"] != "127.0.0.1" or
                    not 1 <= peer["port"] <= 65535 for peer in root["accessPoints"]):
                raise ValueError("only non-advertised loopback peers allowed")
    return genesis, config, topologies


def assess(rows, parameters):
    tips = [tip for row in rows for tip in row]
    if not tips or any(tip.get("era") != "Conway" for tip in tips):
        raise ValueError("live Conway evidence missing")
    if parameters.get("protocolVersion") != {"major": 9, "minor": 0}:
        raise ValueError("live ledger protocol version is not 9.0")
    if any(len(row) != 3 for row in rows):
        raise ValueError("three node observations required in every sample")
    for node in range(3):
        if rows[-1][node]["epoch"] - rows[0][node]["epoch"] < 2 or rows[-1][node]["block"] <= rows[0][node]["block"]:
            raise ValueError("each node must show two epoch boundaries and block growth")
    epochs = [tip["epoch"] for tip in tips]
    if max(epochs) - min(epochs) < 2 or tips[-1]["block"] <= tips[0]["block"]:
        raise ValueError("insufficient epoch/block growth")
    if not any(len(row) == 3 and len({tip["hash"] for tip in row}) == 1 for row in rows[-3:]):
        raise ValueError("no recent sampled three-node convergence")
    return {"passed": True, "firstBlock": tips[0]["block"], "lastBlock": tips[-1]["block"],
            "firstEpoch": min(epochs), "lastEpoch": max(epochs), "ledgerProtocolVersion": "9.0",
            "era": "Conway", "scalaLedgerConformance": False}


class Runner:
    def __init__(self, args):
        self.args = args
        self.name = "cardano-private-" + uuid.uuid4().hex[:12]
        self.out = Path(args.output).resolve()
        repo = Path(__file__).resolve().parent.parent
        if self.out.is_relative_to(repo):
            raise ValueError("evidence must remain outside the source checkout")
        self.out.mkdir(parents=True, exist_ok=False)
        self.commands = self.out / "commands.md"
        self.deadline = None
        self.image = None
        self.endpoint = None
        self.overall_deadline = None

    def docker(self, *args, data=None, check=True, timeout=20):
        limit = self.deadline if self.deadline is not None else self.overall_deadline
        if limit is not None:
            timeout = min(timeout, max(0.1, limit - time.monotonic()))
        with self.commands.open("a") as log:
            log.write("\n```json\n" + json.dumps(["docker"] + (["--host", self.endpoint] if self.endpoint else []) + list(args)) + "\n```\n")
        command = ["docker"] + (["--host", self.endpoint] if self.endpoint else []) + list(args)
        result = subprocess.run(command, input=data, text=True,
                                capture_output=True, timeout=timeout)
        if check and result.returncode:
            raise RuntimeError(result.stderr or result.stdout)
        return result

    def save(self, name, value):
        text = value if isinstance(value, str) else json.dumps(value, indent=2)
        (self.out / name).write_text(text)

    def execute(self, *args, **kwargs):
        return self.docker("exec", self.name, *args, **kwargs)

    def read(self, path):
        return self.execute("cat", "/work/env/" + path).stdout

    def write_json(self, path, obj):
        self.docker("exec", "-i", self.name, "/bin/sh", "-c",
                    "cat > /work/env/" + path, data=json.dumps(obj))

    def query(self, kind, node=1):
        return json.loads(self.execute("cardano-cli", "conway", "query", kind,
            "--testnet-magic", "1082026", "--socket-path",
            f"/work/env/socket/node{node}/sock").stdout)

    def preflight(self):
        if os.environ.get("DOCKER_HOST") or os.environ.get("DOCKER_CONTEXT"):
            raise ValueError("clear Docker endpoint overrides; inspect the intended local context")
        context = json.loads(self.docker("context", "inspect").stdout)[0]
        endpoint = context["Endpoints"]["docker"]["Host"]
        if endpoint not in ("unix:///var/run/docker.sock", "npipe:////./pipe/dockerDesktopLinuxEngine"):
            raise ValueError("local Docker Desktop/Unix socket required")
        self.endpoint = endpoint
        info = json.loads(self.docker("info", "--format", "{{json .}}").stdout)
        if info["OSType"] != "linux" or info["NCPU"] < 4 or info["MemTotal"] < 9 * 1024**3:
            raise ValueError("Linux engine with at least 4 CPUs/9 GiB required")
        self.save("engine.md", "```json\n" + json.dumps({"endpoint": endpoint,
            "os": info["OSType"], "cpus": info["NCPU"], "memory": info["MemTotal"]}) + "\n```")
        self.image = self.docker("image", "inspect", self.args.reference_image,
                                 "--format", "{{.Id}}").stdout.strip()
        self.save("image.md", self.image)
        checks = "; ".join("sha256sum /opt/reference/bin/" + name for name in BINARY_HASHES)
        try:
            result = self.docker("run", "--rm", "--name", self.name + "-preflight", "--pull=never", "--network=none", "--cpus=1",
            "--memory=1g", "--memory-swap=1g", "--pids-limit=64", "--cap-drop=ALL", "--security-opt=no-new-privileges",
            "--entrypoint=/bin/sh", self.image, "-c", checks)
        finally:
            self.docker("rm", "-f", self.name + "-preflight", check=False)
        actual = dict((line.split()[1].split("/")[-1], line.split()[0]) for line in result.stdout.splitlines())
        if actual != BINARY_HASHES:
            raise ValueError("reference binary hashes differ from verified official archive")
        self.save("binary-hashes.md", result.stdout)

    def capture(self):
        # Explicit public-file allowlist: never copy whole environment, keys, wallets or dbs.
        for path in PUBLIC_FILES + [f"node-data/node{i}/topology.json" for i in range(1, 4)]:
            self.save(path.replace("/", "-") + ".md", "```json\n" + self.read(path) + "\n```")
        for i in range(1, 4):
            for stream in ["stdout", "stderr"]:
                self.save(f"node{i}-{stream}.md", "```text\n" +
                          self.read(f"logs/node{i}/{stream}.log") + "\n```")
        self.save("supervisor.md", self.execute("cat", "/work/cluster.log").stdout)

    def scala(self):
        repo = Path(self.args.scala_repo).resolve()
        port = self.read("node-data/node1/port").strip()
        int(port)
        scenario = "reference-handshake " + port + " 1082026"
        evidence_name = "scala-handshake.md"
        if self.args.capture:
            anchor = self.query("tip")
            if anchor.get("era") != "Conway" or not isinstance(anchor.get("slot"), int):
                raise ValueError("Conway anchor query required")
            anchor_hash = anchor["hash"]
            if len(anchor_hash) != 64 or any(c not in "0123456789abcdef" for c in anchor_hash):
                raise ValueError("invalid queried anchor hash")
            self.save("capture-anchor.md", json.dumps(anchor, indent=2))
            scenario = "reference-capture " + port + " 1082026 " + str(anchor["slot"]) + " " + anchor_hash
            evidence_name = "scala-capture.md"
        # Same isolated network namespace, no host network or published ports; no keys mounted.
        result = self.docker("run", "--rm", "--pull=never", "--name", self.name + "-scala",
            "--network=container:" + self.name, "--cpus=1", "--memory=1g", "--memory-swap=1g", "--pids-limit=128",
            "--cap-drop=ALL", "--security-opt=no-new-privileges", "--user", "1000:1000",
            "--read-only", "--tmpfs", "/tmp:size=64m", "-v", str(repo) + ":/work:ro",
            "-w", "/work", "--entrypoint=/bin/sh", JDK, "-c",
            'exec java -XX:ActiveProcessorCount=1 -Xmx512m -cp "$(cat app/target/runtime-classpath.txt)" lab.Main ' + scenario,
            check=False, timeout=60 if self.args.capture else 30)
        self.save(evidence_name, result.stdout + result.stderr)
        if result.returncode:
            raise ValueError("Scala live scenario failed; see " + evidence_name)
        records = [json.loads(line) for line in result.stdout.splitlines() if line.startswith("{")]
        report = records[-1] if records else {}
        if self.args.capture:
            if report.get("scope") != "header-block-byte-comparison" or report.get("passed") is not True:
                raise ValueError("capture comparison evidence missing")
        elif report.get("negotiated") is not True:
            raise ValueError("handshake evidence missing")
        return report

    def cleanup(self):
        # Every attempt is independent. A failing receipt write must not precede removals.
        attempts = []
        self.deadline = None
        for cmd in [("rm", "-f", self.name + "-preflight"),
                    ("rm", "-f", self.name + "-scala"),
                    ("stop", "--timeout", "5", self.name),
                    ("rm", "-f", self.name), ("network", "rm", self.name)]:
            try:
                r = self.docker(*cmd, check=False, timeout=8 if cmd[0] == "stop" else 5)
                attempts.append({"command": cmd, "code": r.returncode, "output": r.stdout + r.stderr})
            except BaseException as exc:
                attempts.append({"command": cmd, "error": type(exc).__name__ + ": " + str(exc)})
        remaining = {}
        for kind, cmd in [("containers", ("ps", "-aq", "--filter", "name=" + self.name)),
                          ("networks", ("network", "ls", "-q", "--filter", "name=" + self.name))]:
            try:
                r = self.docker(*cmd, check=False, timeout=5)
                remaining[kind] = r.stdout.strip() if r.returncode == 0 else "UNKNOWN: " + r.stderr
            except BaseException as exc:
                remaining[kind] = "UNKNOWN: " + str(exc)
        self.save("cleanup.md", json.dumps({"commands": attempts, "remaining": remaining}, indent=2))
        if any(remaining.values()):
            raise RuntimeError("owned resource cleanup incomplete or unverified; see cleanup.md")

    def watchdog(self):
        attempts = []
        if self.endpoint is None:
            return
        for tail in [("rm", "-f", self.name + "-preflight"),
                     ("rm", "-f", self.name + "-scala"), ("stop", "--timeout", "5", self.name)]:
            try:
                r = subprocess.run(["docker", "--host", self.endpoint, *tail], capture_output=True,
                                   text=True, timeout=8)
                attempts.append({"command": tail, "code": r.returncode, "output": r.stdout + r.stderr})
            except BaseException as exc:
                attempts.append({"command": tail, "error": str(exc)})
        self.save("watchdog.md", json.dumps(attempts, indent=2))

    def run(self):
        started = time.monotonic()
        self.overall_deadline = started + 600
        self.deadline = started + self.args.seconds
        network_created = False
        timer = threading.Timer(self.args.seconds, self.watchdog)
        timer.daemon = True
        timer.start()
        rows = []
        error = None
        result = None
        try:
            self.preflight()
            network_created = True
            self.docker("network", "create", "--internal", self.name)
            self.docker("run", "-d", "--pull=never", "--name", self.name, "--network", self.name,
                "--cpus=3", "--memory=6g", "--memory-swap=6g", "--pids-limit=384", "--cap-drop=ALL",
                "--security-opt=no-new-privileges", "--user", "1000:1000", "--read-only",
                "--tmpfs", "/work:rw,nosuid,nodev,size=2g,uid=1000,gid=1000,mode=0700",
                "--tmpfs", "/tmp:rw,nosuid,nodev,size=128m,mode=1777",
                "-e", "CARDANO_CLI=/opt/reference/bin/cardano-cli",
                "-e", "CARDANO_NODE=/opt/reference/bin/cardano-node",
                "--entrypoint=/bin/sh", self.image, "-c", "umask 077; sleep " + str(max(1, int(self.deadline - time.monotonic()))))
            r = self.execute("/bin/sh", "-c", "umask 077; cardano-testnet create-env --num-pool-nodes 3 --testnet-magic 1082026 --output /work/env", timeout=60)
            self.save("create-env.md", r.stdout + r.stderr)
            g, c, ts = profile(json.loads(self.read("shelley-genesis.json")),
                json.loads(self.read("configuration.yaml")),
                [json.loads(self.read(f"node-data/node{i}/topology.json")) for i in range(1, 4)])
            self.write_json("shelley-genesis.json", g)
            self.write_json("configuration.yaml", c)
            self.docker("exec", "-d", self.name, "/bin/sh", "-c",
                "umask 077; cd /work/env; cardano-testnet cardano --node-env /work/env > /work/cluster.log 2>&1")
            ready_until = min(self.deadline - 30, time.monotonic() + 90)
            while True:
                try:
                    first = self.query("tip")
                    if first.get("era") == "Conway" and first.get("block", 0) > 0:
                        break
                except (RuntimeError, json.JSONDecodeError):
                    pass
                if time.monotonic() > ready_until:
                    raise TimeoutError("reference readiness deadline")
                time.sleep(2)
            parameters = self.query("protocol-parameters")
            if parameters["protocolVersion"] != {"major": 9, "minor": 0}:
                raise ValueError("refusing non-PV9 ledger")
            scala_report = self.scala() if self.args.scala_repo else None
            while time.monotonic() < self.deadline - 25:
                row = [self.query("tip", i) for i in range(1, 4)]
                rows.append(row)
                self.save("observations.md", "```json\n" + json.dumps(rows, indent=2) + "\n```")
                print(json.dumps(row), flush=True)
                if len(rows) > 1 and row[0]["epoch"] >= rows[0][0]["epoch"] + 2:
                    try:
                        assess(rows, parameters)
                        break
                    except ValueError:
                        pass
                time.sleep(5)
            parameters = self.query("protocol-parameters")
            self.save("protocol-parameters.md", "```json\n" + json.dumps(parameters, indent=2) + "\n```")
            result = assess(rows, parameters)
            result["scalaHandshake"] = bool(self.args.scala_repo)
            result["scalaObservation"] = scala_report

        except BaseException as exc:
            error = exc
            self.save("failure.md", type(exc).__name__ + ": " + str(exc))
        finally:
            # Capture and receipt failures (including SIGTERM/KeyboardInterrupt) cannot skip
            # owned-resource cleanup or watchdog cancellation.
            try:
                if network_created:
                    try:
                        self.capture()
                        self.save("container.md", self.docker("inspect", self.name).stdout)
                    except BaseException as exc:
                        if error is None:
                            error = exc
                        self.save("capture-error.md", str(exc))
            finally:
                # Covers partially created resources and preflight failures as well as normal runs.
                try:
                    if self.endpoint is not None:
                        self.cleanup()
                except BaseException as exc:
                    if error is None:
                        error = exc
                finally:
                    timer.cancel()
            self.save("timing.md", json.dumps({"elapsedSeconds": time.monotonic() - started,
                "workloadBudgetSeconds": self.args.seconds, "overallBudgetSeconds": 600}, indent=2))
            if error:
                self.save("failure.md", type(error).__name__ + ": " + str(error))
                raise error
        self.save("result.md", "```json\n" + json.dumps(result, indent=2) + "\n```")
        print("Evidence: " + str(self.out))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--reference-image", required=True, help="locally prepared verified release image")
    parser.add_argument("--output", required=True, help="new nonsecret evidence directory outside Git")
    parser.add_argument("--seconds", type=int, default=240)
    parser.add_argument("--capture", action="store_true", help="capture one live successor header and exact block; requires --scala-repo")
    parser.add_argument("--scala-repo", help="optional precompiled repo mounted read-only at /work")
    args = parser.parse_args()
    if args.capture and not args.scala_repo:
        parser.error("--capture requires --scala-repo")
    if not 180 <= args.seconds <= 480:
        parser.error("budget must be 180..480 seconds, including preflight (120 seconds reserved within ten minutes)")
    signal.signal(signal.SIGTERM, lambda *_: (_ for _ in ()).throw(KeyboardInterrupt()))
    Runner(args).run()


if __name__ == "__main__":
    main()
