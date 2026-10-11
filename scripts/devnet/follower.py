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
    classpath = freeze_classes(root)
    log = root / "follower.jsonl"
    program = ["follow", "--genesis-dir", "/genesis", "--peer", f"127.0.0.1:{ports[str(args.node)]}",
               "--query-port", str(QUERY_PORT)]
    if args.seconds:
        program += ["--seconds", str(args.seconds)]
    command = scala_container(args, root, classpath, "lab.node.Main", program,
                              mounts=[f"{root / 'genesis'}:/genesis:ro"], name=f"{args.name}-scala")
    with log.open("a") as out:
        proc = subprocess.Popen(["docker", *command], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        for line in proc.stdout:
            out.write(line)
            out.flush()
            sys.stdout.write(line)
        sys.exit(proc.wait())


def start_follower(name, root):
    return subprocess.Popen([sys.executable, str(ENTRY), "--name", name, "follow"],
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
