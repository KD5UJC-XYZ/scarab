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

    Their only peer, reference node `node`, is frozen (SIGSTOP by PID), so no block or rollback reaches them, and a
    follower's tip is taken as final once two readings of its `/tip`, `settle_seconds` apart, agree (at most
    `attempts` readings after the first): a heuristic, as a node that gets no block cannot say its tip is final. Just
    before the stop is asked for, every follower is checked again: one whose process has ended by then ended by
    itself (`endedBy` `itself`, before the freeze or while frozen), a premature exit kept with its status. The others
    are stopped with SIGTERM (`docker stop -t STOP_SECONDS`, whose own status is checked), so each node ends its
    log itself and its `follow` process writes every line; they ended on the run's request (`endedBy` `stop`). The
    node is continued in every case. A tip is recorded only for a follower that ended on the request. Each record is
    also written to the run dir (`follower-stop.json`, or `follower-stop-<key>.json`). Returns ({site: {"premature":
    status or None, "endedBy", "endedAt", "finalTip", "exit"}}, problems): what went wrong with the stop itself (a
    stop command that failed, a follower that did not end, a node that could not be continued); `stop_failures`
    judges the statuses."""
    from oracle import devnet as oracle_devnet
    report = {site: {"premature": process.poll(), "endedBy": None, "endedAt": None, "finalTip": None, "exit": None}
              for site, process in followers.items()}
    for site, record in report.items():
        if record["premature"] is not None:
            record.update(endedBy="itself", endedAt="before the freeze")
        site.final_tip(root).unlink(missing_ok=True)
    problems, pid, resumed, running = [], None, True, []
    try:
        try:
            pid = oracle_devnet.node_pid(name, node)
        except (subprocess.TimeoutExpired, SystemExit):
            pid = None
        alive = [site for site in followers if report[site]["premature"] is None]
        if pid is not None:
            resumed = False  # from here a SIGSTOP may have been delivered, whatever docker reports
            if oracle_devnet.signal_node(name, pid, "STOP"):
                last, settled = {site: oracle_devnet.follower_tip(name, site) for site in alive}, {}
                for _ in range(attempts):
                    if len(settled) == len(alive):
                        break
                    time.sleep(settle_seconds)
                    for site in alive:
                        if site not in settled:
                            now = oracle_devnet.follower_tip(name, site)
                            if now is not None and now == last[site]:
                                settled[site] = now
                            last[site] = now
                for site, tip in settled.items():
                    report[site]["finalTip"] = tip
        # liveness again just before the request: an end until now is the follower's own, not the stop's
        for site in alive:
            status = followers[site].poll()
            if status is None:
                running.append(site)
            else:
                report[site].update(premature=status, endedBy="itself", endedAt="while frozen", finalTip=None)
        if running:
            try:
                command = docker("stop", "-t", str(STOP_SECONDS), *(site.container(name) for site in running),
                                 check=False, timeout=STOP_SECONDS + 60)
                if command.returncode != 0:
                    problems.append(f"docker stop of the followers returned {command.returncode}: "
                                    f"{(command.stderr or '').strip()[:300]}")
            except subprocess.TimeoutExpired:
                problems.append(f"docker stop of the followers did not return within {STOP_SECONDS + 60} s")
        for site in running:
            try:
                report[site].update(exit=followers[site].wait(timeout=60), endedBy="stop")
            except subprocess.TimeoutExpired:
                problems.append(f"the {site.role} did not end within {STOP_SECONDS + 60} s of its stop")
    finally:
        if pid is not None and not resumed:
            resumed = any(oracle_devnet.signal_node(name, pid, "CONT") for _ in range(3))
    if not resumed:
        problems.append(f"reference node {node} (pid {pid}) could not be confirmed continued after SIGSTOP")
    for site, record in report.items():
        if record["endedBy"] != "stop":
            record["finalTip"] = None
        elif record["finalTip"] is not None:
            site.final_tip(root).write_text(json.dumps(record["finalTip"]))
        (root / f"{site.named('follower-stop')}.json").write_text(json.dumps(record))
    return report, problems


# The status a follower's `follow` process ends with when the run's SIGTERM ended its node: the container's, the
# JVM's own once its shutdown hooks have run, 128 + 15. Every stop of the e2e runs so far ended so; 137 is a kill
# after `STOP_SECONDS`, and any other status is not the end of a SIGTERM.
STOP_STATUS = 143


def stop_failures(stops):
    """{site: [failure]} of a stop report (`stop_followers`): a follower that ended on the run's request with another
    status than `STOP_STATUS`, and one that ended by itself with 0, which an e2e follower never does (a premature
    end with any other status is the follower's own failure already, `summarize_e2e`)."""
    out = {}
    for site, record in stops.items():
        if record["endedBy"] == "stop" and record["exit"] != STOP_STATUS:
            killed = " (killed after the stop's limit)" if record["exit"] == 137 else ""
            out[site] = [f"the follower ended with {record['exit']} on the run's stop{killed}, not {STOP_STATUS}, the "
                         f"end of a SIGTERM"]
        elif record["endedBy"] == "itself" and record["premature"] == 0:
            out[site] = [f"the follower ended by itself with 0 {record['endedAt']}, before the run asked it to stop"]
    return out
