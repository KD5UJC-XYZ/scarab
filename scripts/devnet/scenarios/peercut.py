# SPDX-License-Identifier: Apache-2.0
"""The `peercut` scenario: the Scala node follows two upstream peers, one of them slow and then
silent, and must still reach the tip (issue 130)."""
import json
import os
import shutil
import subprocess
import sys
import time
from ..cluster import freeze_classes, scala_container, up
from ..util import ENV, QUERY_PORT, REPO, data_root, docker, jdk_image, read_log, ref
from .relay import tip_at

PROXY_PORT = 3400   # the slow peer: a proxy in front of pool 1


def start_proxy(args, root, upstream):
    """`lagproxy.py` in the reference container's network namespace, in front of `upstream`."""
    control = root / "proxy"
    control.mkdir(exist_ok=True)
    control.chmod(0o777)
    docker("run", "-d", "--rm", "--pull=never", "--name", f"{args.name}-proxy",
           "--network", f"container:{ref(args.name)}", "--user", f"{os.getuid()}:{os.getgid()}",
           "-v", f"{REPO / 'scripts' / 'devnet'}:/proxy:ro", "-v", f"{control}:/ctl",
           "--entrypoint", "python3", jdk_image(), "/proxy/lagproxy.py", "--listen", str(PROXY_PORT),
           "--upstream", str(upstream), "--delay", str(args.delay), "--control", "/ctl/freeze")
    return control / "freeze"


def start_follower(args, root, healthy):
    classpath = freeze_classes(root)
    program = ["follow", "--genesis-dir", "/genesis", "--query-port", str(QUERY_PORT),
               "--stall-minutes", str(args.stall_minutes),
               "--peer", f"127.0.0.1:{PROXY_PORT}", "--peer", f"127.0.0.1:{healthy}"]
    command = scala_container(args, root, classpath, "xyz.kd5ujc.scarab.node.Main", program,
                              mounts=[f"{root / 'genesis'}:/genesis:ro"], name=f"{args.name}-scala")
    log = (root / "follower.jsonl").open("a")
    return subprocess.Popen(["docker", *command], stdout=log, stderr=subprocess.STDOUT, text=True)


def last_block(events):
    blocks = [e["block"]["blockNo"] for e in events if e["event"] == "extended"]
    return blocks[-1] if blocks else 0


def peercut(args):
    """Fresh 3-pool cluster that is allowed to grow a backlog; the Scala node starts following pool 1
    through a slow proxy and pool 2 directly; once it has applied `--cut-after-blocks` blocks the
    proxy goes silent (connections stay open, nothing is forwarded). The node must reach the tip of
    the reference through pool 2, log the lost peer as a typed reconnect, show the governor's promotions and the demotion, report no escaped
    cats-mtl Submarine and no stall."""
    root = data_root(args.name)
    up(args)
    ports = json.loads((root / "ports.json").read_text())
    scala = None
    failures, summary = [], {}
    log = root / "follower.jsonl"
    try:
        deadline = time.monotonic() + args.backlog_timeout
        while time.monotonic() < deadline:
            tip = tip_at(args.name, f"{ENV}/socket/node1/sock")
            if tip and tip["block"] >= args.backlog_blocks:
                break
            time.sleep(5)
        else:
            raise SystemExit(f"the cluster did not reach block {args.backlog_blocks} in {args.backlog_timeout} s")
        freeze = start_proxy(args, root, ports["1"])
        scala = start_follower(args, root, ports["2"])
        deadline = time.monotonic() + 240
        cut_at = None
        while time.monotonic() < deadline:
            events, _ = read_log(log)
            if last_block(events) >= args.cut_after_blocks:
                freeze.write_text("cut")
                cut_at = last_block(events)
                break
            if scala.poll() is not None:
                raise SystemExit(f"the Scala node exited with {scala.returncode}; see {log}")
            time.sleep(0.5)
        else:
            raise SystemExit(f"the Scala node did not apply {args.cut_after_blocks} blocks in 240 s")
        print(json.dumps({"cutAtBlock": cut_at}), flush=True)
        reached = None
        deadline = time.monotonic() + args.sync_timeout
        while time.monotonic() < deadline:
            events, _ = read_log(log)
            theirs = tip_at(args.name, f"{ENV}/socket/node2/sock")
            if theirs and last_block(events) >= theirs["block"] - 1:
                reached = theirs["block"]
                break
            if scala.poll() is not None:
                raise SystemExit(f"the Scala node exited with {scala.returncode}; see {log}")
            time.sleep(1)
        # and keeps following new blocks after the cut
        before = last_block(read_log(log)[0])
        time.sleep(args.follow_seconds)
        events, other = read_log(log)
        after = last_block(events)
        text = log.read_text(errors="replace")
        counts = {}
        for e in events:
            counts[e["event"]] = counts.get(e["event"], 0) + 1
        reasons = sorted({e["reason"] for e in events if e["event"] == "reconnecting"})
        summary = {
            "backlogBlocks": args.backlog_blocks, "cutAtBlock": cut_at, "reachedBlock": reached,
            "blockBeforeFollowing": before, "blockAtEnd": after,
            "referenceTip": (tip_at(args.name, f"{ENV}/socket/node2/sock") or {}).get("block"),
            "reconnectReasons": reasons[:10],
            "scalaEvents": {k: counts.get(k, 0) for k in ("extended", "rolledBack", "reconnecting", "stalled", "error",
                                                         "peerSelectionPromote", "peerSelectionDemote")},
            "unparsedScalaOutput": other[:10],
        }
        if reached is None:
            failures.append(f"the Scala node did not reach the reference tip within {args.sync_timeout} s of the cut "
                            f"(at block {last_block(events)})")
        if after <= before:
            failures.append(f"the Scala node extended nothing in the {args.follow_seconds} s after reaching the tip")
        if not counts.get("reconnecting"):
            failures.append("the lost peer was never logged as a reconnect")
        elif not any(r.startswith("unavailable") for r in reasons):
            failures.append(f"the reconnect reasons are not typed peer failures: {reasons}")
        # the governor chose the peers: both were promoted to hot, and the silent one was demoted or lost
        hot = {e["peer"] for e in events if e["event"] == "peerSelectionPromote"
               and e.get("action") == "done" and e.get("to") == "hot"}
        if len(hot) < 2:
            failures.append(f"the governor made {len(hot)} peer(s) hot, not both: {sorted(hot)}")
        if not counts.get("peerSelectionDemote"):
            failures.append("the governor never demoted the silent peer")
        if "Submarine" in text:
            failures.append("a cats-mtl Submarine reached the runtime's failure reporter")
        if counts.get("stalled"):
            failures.append(f"{counts['stalled']} stalled event(s) although a healthy peer was ahead")
        if counts.get("error"):
            failures.append(f"{counts['error']} error events in the Scala log")
        if other:
            failures.append(f"unexpected Scala output: {other[0][:200]}")
    except SystemExit as e:
        failures.append(str(e))
    finally:
        docker("rm", "-f", f"{args.name}-scala", f"{args.name}-proxy", check=False)
        if scala is not None:
            scala.wait()
        summary["failures"] = failures
        summary["passed"] = not failures
        (root / "peercut-summary.json").write_text(json.dumps(summary, indent=2))
        print(json.dumps(summary, indent=2))
        if not args.keep:
            docker("rm", "-f", ref(args.name), check=False)
            shutil.rmtree(root / "classes", ignore_errors=True)
    sys.exit(0 if not failures else 1)
