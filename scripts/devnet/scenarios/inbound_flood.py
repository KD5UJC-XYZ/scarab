# SPDX-License-Identifier: Apache-2.0
"""The `inbound-flood` scenario: connections that stay silent must not hold the node's inbound slots.

The Scala node listens next to a reference cluster. One address opens more connections than the
per-address limit allows, silent after a valid handshake or silent altogether, while a peer on another
address connects. Checked: the node holds at most the per-address limit, releases them at the protocol
idle timeout (5 s after the handshake, as the reference) or at the handshake timeout (10 s), and the
other peer connects throughout.
"""
import json
import os
import shutil
import subprocess
import sys
import time
from ..cluster import freeze_classes, scala_container, up
from ..util import ENV, MAGIC, REPO, data_root, docker, jdk_image, read_log, ref
from .relay import tip_at

LISTEN_PORT = 3500
PER_ADDRESS_LIMIT = 8
IDLE_SECONDS = 5
HANDSHAKE_SECONDS = 10


def start_node(args, root, upstream):
    classpath = freeze_classes(root)
    program = ["follow", "--genesis-dir", "/genesis", "--peer", f"127.0.0.1:{upstream}",
               "--listen", f"127.0.0.1:{LISTEN_PORT}"]
    command = scala_container(args, root, classpath, "xyz.kd5ujc.scarab.node.Main", program,
                              mounts=[f"{root / 'genesis'}:/genesis:ro"], name=f"{args.name}-scala")
    log = (root / "follower.jsonl").open("a")
    return subprocess.Popen(["docker", *command], stdout=log, stderr=subprocess.STDOUT, text=True)


def run_flood(args, mode):
    """The probe in the reference container's network namespace; its one JSON line."""
    result = docker("run", "--rm", "--pull=never", "--network", f"container:{ref(args.name)}",
                    "--user", f"{os.getuid()}:{os.getgid()}", "-v", f"{REPO / 'scripts' / 'devnet'}:/probe:ro",
                    "--entrypoint", "python3", jdk_image(), "/probe/inbound_flood.py",
                    "--target", f"127.0.0.1:{LISTEN_PORT}", "--magic", str(MAGIC),
                    "--connections", str(args.connections), "--mode", mode, timeout=120)
    return json.loads(result.stdout.strip().splitlines()[-1])


def problems(report, mode):
    found = []
    if report["accepted"] != PER_ADDRESS_LIMIT:
        found.append(f"{mode}: the node held {report['accepted']} connections from one address, "
                     f"expected the limit {PER_ADDRESS_LIMIT}")
    bounds = report["closedAfterSeconds"]
    window = (IDLE_SECONDS, IDLE_SECONDS + 3) if mode == "handshake" else (HANDSHAKE_SECONDS, HANDSHAKE_SECONDS + 3)
    if bounds is None or not (window[0] - 0.5 <= bounds["min"] and bounds["max"] <= window[1]):
        found.append(f"{mode}: held connections were released after {bounds} s, expected {window[0]} to {window[1]} s")
    if report["stillOpenAtLimit"]:
        found.append(f"{mode}: {report['stillOpenAtLimit']} connections were still open at the limit")
    for key in ("legitimateDuringFlood", "legitimateAfterFlood", "floodAddressReconnectsAfterRelease"):
        if not report[key].get("connected"):
            found.append(f"{mode}: {key} failed: {report[key]}")
    return found


def inbound_flood(args):
    root = data_root(args.name)
    up(args)
    ports = json.loads((root / "ports.json").read_text())
    node = None
    failures, summary = [], {}
    log = root / "follower.jsonl"
    try:
        deadline = time.monotonic() + args.backlog_timeout
        while time.monotonic() < deadline:
            tip = tip_at(args.name, f"{ENV}/socket/node1/sock")
            if tip and tip["block"] >= 5:
                break
            time.sleep(2)
        node = start_node(args, root, ports["1"])
        deadline = time.monotonic() + 120
        while time.monotonic() < deadline:
            events, _ = read_log(log)
            if any(e["event"] == "listening" for e in events):
                break
            if node.poll() is not None:
                raise SystemExit(f"the Scala node exited with {node.returncode}; see {log}")
            time.sleep(1)
        else:
            raise SystemExit("the Scala node did not start listening within 120 s")
        for mode in ("handshake", "silent"):
            report = run_flood(args, mode)
            summary[mode] = report
            failures += problems(report, mode)
            print(json.dumps({mode: report}), flush=True)
        events, other = read_log(log)
        summary["nodeStillRunning"] = node.poll() is None
        summary["errorEvents"] = sum(1 for e in events if e["event"] == "error")
        if node.poll() is not None:
            failures.append(f"the Scala node exited with {node.returncode}")
        if summary["errorEvents"]:
            failures.append(f"{summary['errorEvents']} error events in the Scala log")
    except SystemExit as e:
        failures.append(str(e))
    finally:
        docker("rm", "-f", f"{args.name}-scala", check=False)
        if node is not None:
            node.wait()
        summary["failures"] = failures
        summary["passed"] = not failures
        (root / "inbound-flood-summary.json").write_text(json.dumps(summary, indent=2))
        print(json.dumps(summary, indent=2))
        if not args.keep:
            docker("rm", "-f", ref(args.name), check=False)
            shutil.rmtree(root / "classes", ignore_errors=True)
    sys.exit(0 if not failures else 1)
