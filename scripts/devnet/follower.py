# SPDX-License-Identifier: Apache-2.0
"""The Scala follower process: run it, watch it, detect validation problems."""
import json
import subprocess
import sys
from .cluster import freeze_classes, scala_container
from .util import ENTRY, QUERY_PORT, data_root, read_log


def follow(args):
    root = data_root(args.name)
    ports = json.loads((root / "ports.json").read_text())
    classpath = freeze_classes(root, getattr(args, "classes", None))
    log = root / "follower.jsonl"
    program = ["follow", "--genesis-dir", "/genesis", "--peer", f"127.0.0.1:{ports[str(args.node)]}",
               "--query-port", str(QUERY_PORT)]
    if args.seconds:
        program += ["--seconds", str(args.seconds)]
    if getattr(args, "mirror_check", False):
        # the round trip through the mirror of the Haskell state on the live state at each epoch boundary
        program += ["--mirror-check"]
    mounts = [f"{root / 'genesis'}:/genesis:ro"]
    if getattr(args, "db", False):
        # the chain database lives in the run directory, so a later `follow --db` restarts from its snapshot
        (root / "db").mkdir(exist_ok=True)
        program += ["--db", "/db", "--snapshot-every", getattr(args, "snapshot_every", None) or "1epochs"]
        mounts.append(f"{root / 'db'}:/db")
    if getattr(args, "dump_state", False):
        # state digests at every epoch boundary for the db-analyser comparison (`devnet.py oracle`)
        (root / "dump").mkdir(exist_ok=True)
        program += ["--dump-state", "/dump", "--dump-state-at", "epoch-boundaries"]
        mounts.append(f"{root / 'dump'}:/dump")
    command = scala_container(args, root, classpath, "xyz.kd5ujc.scarab.node.Main", program,
                              mounts=mounts, name=f"{args.name}-scala")
    with log.open("a") as out:
        proc = subprocess.Popen(["docker", *command], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        for line in proc.stdout:
            out.write(line)
            out.flush()
            sys.stdout.write(line)
        sys.exit(proc.wait())


def start_follower(name, root, oracle=False):
    return subprocess.Popen([sys.executable, str(ENTRY), "--name", name, "follow",
                             *(["--dump-state"] if oracle else [])],
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def follower_problem(root, follower):
    """A reason to abort the run if the follower died or logged an error, else None."""
    events, other = read_log(root / "follower.jsonl")
    errors = [e for e in events if e["event"] == "error"]
    if errors:
        return f"follower error: {errors[0].get('message', errors[0])}"
    if other:
        return f"unexpected follower output: {other[0][:200]}"
    if follower.poll() is not None:
        return f"follower exited with {follower.returncode}"
    return None
