# SPDX-License-Identifier: Apache-2.0
"""The Scala follower process: run it, watch it, detect validation problems."""
import json
import os
import subprocess
import sys
import time
from .cluster import database_mounts, freeze_classes, scala_container
from .consensus import configuration
from .util import ENTRY, SINGLE_FOLLOWER, FollowerSite, data_root, docker, read_log, stall_dump_arguments

STOP_SECONDS = 30  # `docker stop -t`: SIGTERM, a kill after this long; the node ends far sooner (issue 89)


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


def stop_followers(name, root, followers, settle_seconds=2, attempts=15, node=1):
    """Stop a run's followers (site -> `follow` process) on purpose, so that each one's log is whole and its final tip
    recorded before it is judged (`site.final_tip`, as `oracle.devnet.stop_follower` records the oracle's).

    A follower whose process has already ended exited before the run stopped it: that is a premature exit, kept
    with its status. For the others, their only peer, reference node `node`, is frozen (SIGSTOP by PID), so no
    block or rollback reaches them, and a follower's tip is final once two readings of its `/tip`, `settle_seconds`
    apart, agree (at most `attempts` readings after the first). Each is then stopped with SIGTERM (`docker stop`), so
    its node ends its log itself and its `follow` process writes every line, and the node is continued in every
    case. A tip is recorded only for a follower that ended. Returns ({site: {"premature": status or None,
    "finalTip": tip or None, "exit": status after the stop or None}}, problems): what went wrong with the stop
    itself (a follower that did not end, a node that could not be continued)."""
    from oracle import devnet as oracle_devnet
    report = {site: {"premature": process.poll(), "finalTip": None, "exit": None}
              for site, process in followers.items()}
    running = [site for site in followers if report[site]["premature"] is None]
    for site in followers:
        site.final_tip(root).unlink(missing_ok=True)
    problems, pid, resumed = [], None, True
    try:
        try:
            pid = oracle_devnet.node_pid(name, node)
        except (subprocess.TimeoutExpired, SystemExit):
            pid = None
        if pid is not None:
            resumed = False  # from here a SIGSTOP may have been delivered, whatever docker reports
            if oracle_devnet.signal_node(name, pid, "STOP"):
                last, settled = {site: oracle_devnet.follower_tip(name, site) for site in running}, {}
                for _ in range(attempts):
                    if len(settled) == len(running):
                        break
                    time.sleep(settle_seconds)
                    for site in running:
                        if site not in settled:
                            now = oracle_devnet.follower_tip(name, site)
                            if now is not None and now == last[site]:
                                settled[site] = now
                            last[site] = now
                for site, tip in settled.items():
                    report[site]["finalTip"] = tip
        if running:
            try:
                docker("stop", "-t", str(STOP_SECONDS), *(site.container(name) for site in running), check=False,
                       timeout=STOP_SECONDS + 60)
            except subprocess.TimeoutExpired:
                problems.append(f"docker stop of the followers did not return within {STOP_SECONDS + 60} s")
        for site in running:
            try:
                report[site]["exit"] = followers[site].wait(timeout=60)
            except subprocess.TimeoutExpired:
                problems.append(f"the {site.role} did not end within {STOP_SECONDS + 60} s of its stop")
    finally:
        if pid is not None and not resumed:
            resumed = any(oracle_devnet.signal_node(name, pid, "CONT") for _ in range(3))
    if not resumed:
        problems.append(f"reference node {node} (pid {pid}) could not be confirmed continued after SIGSTOP")
    for site in running:
        if report[site]["finalTip"] is not None and report[site]["exit"] is not None:
            site.final_tip(root).write_text(json.dumps(report[site]["finalTip"]))
    return report, problems
