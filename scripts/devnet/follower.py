# SPDX-License-Identifier: Apache-2.0
"""The Scala follower process: run it, watch it, detect validation problems."""
import json
import os
import subprocess
import sys
from .cluster import database_mounts, freeze_classes, scala_container
from .consensus import configuration
from .util import ENTRY, SINGLE_FOLLOWER, FollowerSite, data_root, read_log, stall_dump_arguments


def own_build(args):
    """True when the follower runs the classes of this checkout, not another build's (`--classes`,
    `SCARAB_FOLLOW_CLASSES`)."""
    return not (getattr(args, "classes", None) or os.environ.get("SCARAB_FOLLOW_CLASSES"))


def follow(args):
    root = data_root(args.name)
    ports = json.loads((root / "ports.json").read_text())
    # `--instance`: one of several followers of the run, each with names, directories and ports of its own
    site = FollowerSite(getattr(args, "instance", None))
    classes = site.directory(root, "classes")
    classpath = freeze_classes(root, getattr(args, "classes", None) or os.environ.get("SCARAB_FOLLOW_CLASSES"),
                               frozen=classes)
    log = site.log(root)
    mode = getattr(args, "consensus_mode", None)
    port = ports[str(args.node)]
    program = ["follow", "--genesis-dir", "/genesis", "--query-port", str(site.query_port)]
    if mode != "GenesisMode":
        program += ["--peer", f"127.0.0.1:{port}"]
    program += configuration(root, mode, [port])
    if own_build(args):
        # the Prometheus endpoint on a listener of its own (an older build, run for a baseline, has no such flag)
        program += ["--metrics-listen", f"127.0.0.1:{site.metrics_port}"]
        # a stall leaves its pipeline state and a fiber dump in the run directory (issue 371)
        stall_flags, stall_mount = stall_dump_arguments(root, site)
        program += stall_flags
    if getattr(args, "plutus_vm", None):
        program += ["--plutus-vm", args.plutus_vm]
    if args.seconds:
        program += ["--seconds", str(args.seconds)]
    mounts = [f"{root / 'genesis'}:/genesis:ro"]
    if own_build(args):
        mounts.append(stall_mount)
    if getattr(args, "db", False):
        # the chain database lives in the run directory, so a later `follow --db` restarts from its snapshot
        db = site.directory(root, "db")
        db.mkdir(exist_ok=True)
        program += ["--db", "/db", "--snapshot-every", getattr(args, "snapshot_every", None) or "1epochs"]
        mounts.extend(database_mounts(db))
    if getattr(args, "local_socket", False):
        # node-to-client, for the same-point whole-state comparison (`compare.whole_state`)
        sock = site.directory(root, "scala-sock")
        sock.mkdir(exist_ok=True)
        sock.chmod(0o777)
        program += ["--local-socket", "/sock/node.socket"]
        mounts.append(f"{sock}:/sock")
    if getattr(args, "dump_state", False):
        # state digests at every epoch boundary for the db-analyser comparison (`devnet.py oracle`)
        dump = site.directory(root, "dump")
        dump.mkdir(exist_ok=True)
        program += ["--dump-state", "/dump", "--dump-state-at", "epoch-boundaries"]
        mounts.append(f"{dump}:/dump")
    command = scala_container(args, root, classpath, "xyz.kd5ujc.scarab.node.Main", program,
                              mounts=mounts, name=site.container(args.name), classes=classes)
    with log.open("a") as out:
        proc = subprocess.Popen(["docker", *command], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        for line in proc.stdout:
            out.write(line)
            out.flush()
            sys.stdout.write(line)
        sys.exit(proc.wait())


def start_follower(name, root, oracle=False, scala_cpus=None, consensus_mode=None, local_socket=False, plutus_vm=None,
                   site=SINGLE_FOLLOWER):
    """The `devnet.py follow` process of one follower of the run (`site`: its names and ports)."""
    return subprocess.Popen([sys.executable, str(ENTRY), "--name", name, "follow",
                             *(["--consensus-mode", consensus_mode] if consensus_mode is not None else []),
                             *(["--dump-state"] if oracle else []),
                             *(["--local-socket"] if local_socket else []),
                             *(["--scala-cpus", str(scala_cpus)] if scala_cpus is not None else []),
                             *(["--plutus-vm", plutus_vm] if plutus_vm is not None else []),
                             *(["--instance", site.key] if site.key is not None else [])],
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def follower_problem(root, follower, site=SINGLE_FOLLOWER):
    """A reason to abort the run if the follower of `site` died or logged an error, else None."""
    events, other = read_log(site.log(root))
    errors = [e for e in events if e["event"] == "error"]
    if errors:
        return f"{site.role} error: {errors[0].get('message', errors[0])}"
    if other:
        return f"unexpected {site.role} output: {other[0][:200]}"
    if follower.poll() is not None:
        return f"{site.role} exited with {follower.returncode}"
    return None
