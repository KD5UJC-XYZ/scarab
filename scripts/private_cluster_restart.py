#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Retained-database relay process restart inside one disposable private container."""
import argparse
import hashlib
import json
import posixpath
import re
import signal
import time
from pathlib import Path
from private_cluster import Runner, BINARY_HASHES
from private_cluster_transfer import TransferRunner
from private_cluster_scenarios import unchanged
from private_cluster_restart_prerequisites import INVENTORY, START_SCRIPT, synthetic_prerequisites

ROOT = "/work/env"
DATABASE = ROOT + "/node-data/node3/db"
EXE = "/opt/reference/bin/cardano-node"
PIDFD = "/opt/reference/libexec/restart-pidfd"
IDENTITY_FILES = ("configuration.yaml", "byron-genesis.json", "shelley-genesis.json",
                  "alonzo-genesis.json", "conway-genesis.json", "node-data/node3/topology.json",
                  "node-data/node3/port")


def launch_options(argv, cwd, exe, port):
    """Accept only the observed non-producing relay command; preserve option ordering."""
    if cwd != ROOT or exe != EXE or len(argv) < 2 or posixpath.basename(argv[0]) != "cardano-node" or argv[1] != "run":
        raise ValueError("unexpected relay executable/cwd/command")
    tail = list(argv[2:])
    if "+RTS" in tail:
        at = tail.index("+RTS")
        if tail[at:] not in (["+RTS", "-N1", "-RTS"], ["+RTS", "-N2", "-RTS"], ["+RTS", "-N3", "-RTS"]):
            raise ValueError("unsupported relay RTS options")
        tail = tail[:at]
    if len(tail) % 2:
        raise ValueError("relay options must be explicit pairs")
    options = dict(zip(tail[::2], tail[1::2]))
    expected = {"--config": ROOT + "/configuration.yaml", "--topology": ROOT + "/node-data/node3/topology.json",
                "--database-path": DATABASE, "--socket-path": ROOT + "/socket/node3/sock"}
    if len(options) != len(tail) // 2 or set(options) != set(expected) | {"--host-addr", "--port"}:
        raise ValueError("unexpected, duplicate or producing relay options")
    for key, path in expected.items():
        if posixpath.normpath(posixpath.join(cwd, options[key])) != path:
            raise ValueError("relay path outside exact owned location: " + key)
    if options["--host-addr"] != "127.0.0.1" or options["--port"] != str(port) or not 1 <= port <= 65535:
        raise ValueError("unexpected relay endpoint")
    return options


def process_stat(text):
    # comm may contain spaces or parentheses; the final ')' ends the kernel field.
    fields = text[text.rfind(")") + 2:].split()
    if ")" not in text or len(fields) < 20 or not fields[19].isdigit():
        raise ValueError("malformed process identity")
    return {"state": fields[0], "startTicks": int(fields[19])}


def same_point(actual, expected):
    for point in (actual, expected):
        if point.get("era") != "Conway" or not re.fullmatch("[0-9a-f]{64}", str(point.get("hash", ""))):
            return False
        if any(type(point.get(key)) is not int or point[key] < 0 for key in ("slot", "block", "epoch")):
            return False
    return all(actual[key] == expected[key] for key in ("hash", "slot", "block", "epoch", "era"))


def relay_role(text):
    line = text.splitlines()[0] if text else ""
    required = ("byronKeyFile = Nothing", "shelleyKESSource = Nothing", "shelleyVRFFile = Nothing",
                "shelleyCertFile = Nothing", "shelleyBulkCredsFile = Nothing")
    if not line.startswith("Node configuration:") or not all(value in line for value in required):
        raise ValueError("observed node is not a credential-free relay")
    return line


class RestartRunner(TransferRunner):
    """Reuse relay generation/snapshots and base lifecycle; do not invoke transfer.scala."""
    def preflight(self):
        super().preflight()
        synthetic_prerequisites(self)

    def read(self, path):
        # Runner.scala uses node1's port name; route its localhost probe to the owned relay.
        if getattr(self, "probing_relay", False) and path == "node-data/node1/port":
            path = "node-data/node3/port"
        return super().read(path)

    def wait_for(self, label, probe, seconds=25):
        until = min(self.deadline - 120, time.monotonic() + seconds)
        rows = []
        for _ in range(120):
            if time.monotonic() >= until:
                break
            value = probe()
            rows.append({"monotonicSeconds": time.monotonic(), "ready": bool(value)})
            self.save(label + "-wait.md", rows)
            if value:
                return value
            time.sleep(0.25)
        raise TimeoutError(label + " deadline")

    def verify_pidfd_helper(self):
        self.pidfd_ready = False
        digest = self.args.pidfd_helper_sha256
        if not re.fullmatch("[0-9a-f]{64}", digest):
            raise ValueError("explicit pidfd helper SHA-256 pin required")
        if self.execute("readlink", "-f", PIDFD).stdout.strip() != PIDFD:
            raise ValueError("helper must be at exact immutable image path")
        if self.execute("stat", "-c", "%u:%g:%a:%F", PIDFD).stdout.strip() != "0:0:555:regular file":
            raise ValueError("helper must be root-owned regular image file with mode 0555")
        if self.hashes([PIDFD])[PIDFD] != digest:
            raise ValueError("image pidfd helper digest differs from explicit pin")
        mounts = self.execute("cat", "/proc/self/mountinfo").stdout
        parsed = {row.split()[4]: set(row.split()[5].split(",")) for row in mounts.splitlines()}
        if "ro" not in parsed.get("/", set()) or any("noexec" not in parsed.get(path, set()) for path in ("/work", "/tmp")):
            raise ValueError("read-only root and non-executable runtime-data mounts required")
        if any(PIDFD == path or PIDFD.startswith(path.rstrip("/") + "/") for path in parsed if path != "/"):
            raise ValueError("helper must come from root image, not an overlaid mount")
        self.pidfd_ready = True
        self.save("restart-pidfd-helper.md", {"sha256": digest, "path": PIDFD,
            "sourceSha256": hashlib.sha256(Path(__file__).with_name("private_cluster_pidfd.c").read_bytes()).hexdigest(),
            "rootOwnedMode": "0555", "runtimeUpload": False, "mountinfo": mounts,
            "method": "pidfd_open then identity check then pidfd_send_signal", "numericSignalFallback": False})

    def pid(self):
        value = self.read("logs/node3/node.pid").strip()
        if not value.isdigit() or not 1 < int(value) < 2**31:
            raise ValueError("invalid owned relay PID")
        return int(value)

    def stat(self, pid):
        result = self.execute("/bin/sh", "-c", '[ -e "/proc/$1/stat" ] || exit 3; cat "/proc/$1/stat"',
                              "restart-stat", str(pid), check=False, timeout=5)
        if result.returncode == 3:
            return None
        if result.returncode:
            raise RuntimeError("relay process inspection failed: " + result.stderr)
        return process_stat(result.stdout)

    def process(self):
        pid = self.pid()
        before = self.stat(pid)
        if before is None or before["state"] == "Z":
            raise ValueError("owned relay not running")
        exe = self.execute("readlink", "-f", f"/proc/{pid}/exe").stdout.strip()
        cwd = self.execute("readlink", "-f", f"/proc/{pid}/cwd").stdout.strip()
        command = self.execute("cat", f"/proc/{pid}/cmdline").stdout
        if not command.endswith("\0") or len(command) > 8192:
            raise ValueError("malformed or oversized relay command")
        argv = command[:-1].split("\0")
        port = int(self.read("node-data/node3/port").strip())
        options = launch_options(argv, cwd, exe, port)
        for key in ("--config", "--topology", "--database-path", "--socket-path"):
            path = posixpath.normpath(posixpath.join(cwd, options[key]))
            if self.execute("readlink", "-f", path).stdout.strip() != path:
                raise ValueError("relay path resolves outside its owned location")
        digest = self.hashes([exe])[exe]
        if digest != BINARY_HASHES["cardano-node"]:
            raise ValueError("relay executable differs from verified binary")
        after = self.stat(pid)
        if after is None or before["startTicks"] != after["startTicks"] or after["state"] == "Z" or self.pid() != pid:
            raise ValueError("relay process identity changed during inspection")
        return {"pid": pid, "startTicks": after["startTicks"], "exe": exe, "cwd": cwd,
                "argv": argv, "binarySha256": digest}

    def hashes(self, paths):
        result = self.execute("sha256sum", "--", *paths, timeout=15).stdout
        rows = {}
        for line in result.splitlines():
            digest, name = line.split("  ", 1)
            if not re.fullmatch("[0-9a-f]{64}", digest) or name in rows:
                raise ValueError("invalid/duplicate file digest")
            rows[name] = digest
        if set(rows) != set(paths):
            raise ValueError("file digest inventory differs")
        return rows

    def identity(self):
        paths = [ROOT + "/" + name for name in IDENTITY_FILES]
        for path in paths:
            if self.execute("readlink", "-f", path).stdout.strip() != path:
                raise ValueError("identity file outside exact owned location")
        return self.hashes(paths)

    def database_identity(self):
        if self.execute("readlink", "-f", DATABASE).stdout.strip() != DATABASE:
            raise ValueError("database outside exact owned location")
        identity = self.execute("stat", "-c", "%d:%i", DATABASE).stdout.strip()
        if not re.fullmatch("[0-9]+:[0-9]+", identity):
            raise ValueError("invalid database directory identity")
        return identity

    def database_inventory(self):
        original = self.execute("/bin/sh", "-c", INVENTORY, "retained-inventory", DATABASE, timeout=15).stdout
        if len(original) > 1048576:
            raise ValueError("database inventory exceeds bound")
        files = {}
        for line in original.splitlines():
            name, size = line.split("\t")
            if (not re.fullmatch(r"[A-Za-z0-9_./-]+", name) or ".." in name.split("/")
                    or name.startswith("/") or name in files or not size.isdigit()):
                raise ValueError("unsupported database entry")
            files[name] = int(size)
        if not files or len(files) > 4096 or sum(files.values()) > 512 * 1024**2:
            raise ValueError("nonempty bounded retained database required")
        chunks = sorted(DATABASE + "/" + name for name in files if name.startswith("immutable/") and name.endswith(".chunk"))
        if not chunks or not any(files[path[len(DATABASE) + 1:]] > 0 for path in chunks):
            raise ValueError("immutable chunk evidence required before restart")
        return {"directoryIdentity": self.database_identity(), "files": files,
                "immutableChunkSha256": self.hashes(chunks)}

    def stop_relay(self, original):
        if self.process() != original:
            raise ValueError("relay identity changed before stop")
        if not getattr(self, "pidfd_ready", False):
            raise ValueError("verified pidfd helper required before signalling")
        result = self.execute(PIDFD, str(original["pid"]), str(original["startTicks"]),
                              check=False, timeout=5)
        self.save("restart-stop.md", {"pid": original["pid"], "startTicks": original["startTicks"],
            "signal": "TERM", "method": "pidfd_send_signal", "returncode": result.returncode,
            "stdout": result.stdout, "stderr": result.stderr})
        if result.returncode:
            raise RuntimeError("process-bound relay stop failed; no numeric-signal fallback")
        expected = {"method": "pidfd_send_signal", "pid": original["pid"],
                    "startTicks": original["startTicks"], "signal": "TERM", "sent": True}
        if json.loads(result.stdout) != expected:
            raise ValueError("pidfd stop receipt differs from expected process")
        def stopped():
            observed = self.stat(original["pid"])
            if observed is not None and observed["startTicks"] != original["startTicks"]:
                raise ValueError("replacement identity observed during exit wait")
            return observed is None or observed["state"] == "Z"
        self.wait_for("restart-stop", stopped)
        if self.pid() != original["pid"]:
            raise ValueError("supervisor replaced relay; refusing competing launch")
        self.pause_evidence()

    def start_relay(self, original):
        # No create-env/cardano-testnet call, timestamp edit, DB removal or credentials.
        observed = self.stat(original["pid"])
        if observed is not None and (observed["startTicks"] != original["startTicks"] or observed["state"] != "Z"):
            raise ValueError("old PID is live or replaced before launch")
        if self.pid() != original["pid"]:
            raise ValueError("supervisor replacement observed before launch")
        self.restart_spawned = True
        self.docker("exec", "-d", self.name, "/bin/sh", "-c", START_SCRIPT, "restart-relay",
                    original["cwd"], original["exe"], *original["argv"][1:], timeout=5)
        def started():
            try:
                current = self.process()
                launched_pid = self.execute("cat", "/work/restart-relay.pid", timeout=5).stdout.strip()
            except (ValueError, RuntimeError):
                return None
            if (current["pid"], current["startTicks"]) == (original["pid"], original["startTicks"]):
                return None
            if launched_pid != str(current["pid"]):
                raise ValueError("relay PID differs from this restart's launch receipt")
            if any(current[key] != original[key] for key in ("cwd", "exe", "binarySha256")) or current["argv"][1:] != original["argv"][1:]:
                raise ValueError("restarted relay arguments or executable differ")
            return current
        current = self.wait_for("restart-process", started)
        self.save("restart-process-after.md", current)
        return current

    def restart(self):
        def mature():
            tips = [self.query("tip", node) for node in (1, 2, 3)]
            self.save("restart-pre-pause-tips.md", tips)
            return tips[-1] if all(same_point(tip, tips[-1]) for tip in tips) and tips[-1]["block"] >= 8 else None
        self.wait_for("restart-retained-chain", mature, seconds=40)
        try:
            self.producers("STOP")
            before = self.snapshot("restart-pre")
            # SIGSTOP prevents producers answering sockets. Their pre-pause convergence
            # is readiness evidence; the drained, stable relay point is the restart baseline.
            self.pause_evidence()
            original = self.process()
            self.save("restart-role-before.md", relay_role(self.read("logs/node3/stdout.log")))
            identity = self.identity()
            directory = self.database_identity()
            self.save("restart-process-before.md", original)
            self.save("restart-identity-before.md", identity)
            self.stop_relay(original)
            stopped = self.database_inventory()
            self.save("restart-stopped-database.md", stopped)
            if stopped["directoryIdentity"] != directory or self.identity() != identity:
                raise ValueError("database or genesis/config identity changed before restart")
            self.start_relay(original)
            def rejoined():
                self.pause_evidence()
                try:
                    tip = self.query("tip")
                except (RuntimeError, json.JSONDecodeError):
                    return None
                self.save("restart-last-tip.md", tip)
                return tip if same_point(tip, before[0]) else None
            self.wait_for("restart-rejoin", rejoined, seconds=40)
            self.save("restart-role-after.md", relay_role(self.read("logs/node3/restart-stdout.log")))
            after = self.snapshot("restart-post")
            unchanged(before, after)
            if not same_point(after[0], before[0]) or before[1]["utxo-cbor"] != after[1]["utxo-cbor"]:
                raise ValueError("restart point or exact UTxO CBOR differs")
            self.pause_evidence()
            final_identity = self.identity()
            self.save("restart-identity-after.md", final_identity)
            if final_identity != identity or self.database_identity() != directory:
                raise ValueError("restarted database/genesis/config identity differs")
            if self.hashes(list(stopped["immutableChunkSha256"])) != stopped["immutableChunkSha256"]:
                raise ValueError("retained immutable bytes changed")
            report = {"scope": "reference-relay-retained-database-process-restart", "passed": True,
                "sameChainPoint": before[0], "sameDatabaseDirectory": True, "sameGenesisAndConfiguration": True,
                "retainedImmutableChunksVerified": True, "wholeUtxoAndFeePotUnchanged": True,
                "referenceSnapshotAtomic": False, "containerRestart": False,
                "scalaAcquisitionResume": "not-tested", "scalaCrashRecovery": "not-tested"}
            self.save("restart-result.md", report)
            return report
        finally:
            self.producers("CONT")

    def probe(self, label, capture):
        self.probing_relay = True
        self.args.capture = capture
        try:
            report = Runner.scala(self)
            source = "scala-capture.md" if capture else "scala-handshake.md"
            self.save(label + ".md", (self.out / source).read_text())
            return report
        finally:
            self.probing_relay = False
            self.args.capture = False

    def scala(self):
        self.verify_pidfd_helper()
        initial = self.probe("restart-before-handshake", False)
        restart = self.restart()
        capture = self.probe("restart-after-capture", True)
        return {"initialRelayHandshake": initial, "referenceRestart": restart,
                "postRestartOriginalByteCapture": capture, "scalaAcquisitionResume": "not-tested"}

    def capture(self):
        try:
            super().capture()
        finally:
            if getattr(self, "restart_spawned", False):
                for stream in ("stdout", "stderr"):
                    try:
                        self.save("restart-relay-" + stream + ".md", self.read("logs/node3/restart-" + stream + ".log"))
                    except Exception as exc:
                        self.save("restart-relay-" + stream + "-capture-error.md", str(exc))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--reference-image", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--scala-repo", required=True)
    parser.add_argument("--pidfd-helper-sha256", required=True, help="explicit SHA-256 pin for immutable test-image helper")
    parser.add_argument("--seconds", type=int, default=420)
    parser.add_argument("--preflight-only", action="store_true", help="synthetic files/owned sleep child only; no Cardano node")
    args = parser.parse_args()
    args.capture = False
    if not 300 <= args.seconds <= 480:
        parser.error("restart budget must be 300..480 seconds")
    signal.signal(signal.SIGTERM, lambda *_: (_ for _ in ()).throw(KeyboardInterrupt()))
    runner = RestartRunner(args)
    if args.preflight_only:
        runner.overall_deadline = time.monotonic() + 600
        runner.deadline = time.monotonic() + args.seconds
        runner.preflight()
    else:
        runner.run()


if __name__ == "__main__": main()
