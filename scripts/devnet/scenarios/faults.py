# SPDX-License-Identifier: Apache-2.0
"""The `faults` scenario (issue 224): a seeded, randomized network-fault run of the devnet.

The Scala node is pool 1 of a three-pool cluster (as in `pool`) next to two Haskell reference pools. Every
link between two nodes runs through a proxy port of its own (`netfaults.py`), so that Linux `tc netem` can
add latency and loss to one link, or cut it for a partition. A schedule drawn from `--seed` (`faultplan.py`)
also crashes and restarts the Scala node (from its database) and the Haskell pools. The properties are read
from the nodes' own state: tips, the Scala node's event log and the Haskell nodes' logs. A failure prints the
command that repeats the run.
"""
import gzip
import json
import shutil
import subprocess
import sys
import time
from .. import faultplan, netfaults
from ..cluster import database_mounts, freeze_classes, scala_container, start_reference
from ..util import ENV, QUERY_PORT, data_root, docker, in_ref, put_file, read_log, ref
from .pool import pool_environment
from .relay import SCALA_PORT, log_counts, tip_at

POOL = 1                 # the number of the pool whose keys the Scala node takes
HASKELL = {"p2": 2, "p3": 3}
# The Haskell pools' diagnostic traces, so that a failed run shows why a pool stopped following a peer: its
# peer-selection decisions, connection and inbound-governor transitions, ChainSync and BlockFetch clients
# (trace-dispatcher namespaces of cardano-node 11.1.3). `maxFrequency` bounds the fetch decisions, which the
# decision loop can emit every 10 ms. A non-empty `TraceOptions` replaces the node's default selection rather
# than adding to it, so the namespaces an empty one logged (forging, the ChainDB, the remote connection and
# inbound-governor transitions, start-up) are listed too.
NATIVE_TRACES = {
    "Forge": {"severity": "Info"},
    "ChainDB": {"severity": "Info"},
    "Net.ConnectionManager.Remote": {"severity": "Info"},
    "Net.InboundGovernor.Remote": {"severity": "Info"},
    "Startup": {"severity": "Info"},
    "Net.PeerSelection": {"severity": "Debug"},
    "Net.ConnectionManager.Local": {"severity": "Info"},
    "Net.InboundGovernor.Local": {"severity": "Info"},
    "ChainSync.Client": {"severity": "Info"},
    "BlockFetch.Client": {"severity": "Info"},
    "BlockFetch.Decision": {"severity": "Debug", "maxFrequency": 2.0},
}
# next to the cluster's configuration, whose genesis file names are relative to it
POOL_CONFIG = f"{ENV}/faults-configuration.yaml"


def pool_configuration(raw):
    """The cluster's configuration (JSON) with `NATIVE_TRACES` added, for the Haskell pools."""
    config = json.loads(raw)
    options = config.setdefault("TraceOptions", {})
    for namespace, option in NATIVE_TRACES.items():
        options.setdefault(namespace, {}).update(option)
    return json.dumps(config, indent=2)
SETTLE_POLL = 2          # seconds between tip readings while settling
SAMPLE_EVERY = 4         # seconds between tip readings during the run


def topology(own_dials):
    """A Haskell pool's topology: its peers are the proxy ports that reach the other two nodes."""
    ports = list(own_dials.values())
    return {"bootstrapPeers": None,
            "localRoots": [{"accessPoints": [{"address": "127.0.0.1", "port": p} for p in ports],
                            "advertise": False, "behindFirewall": False, "trustable": True,
                            "diffusionMode": "InitiatorAndResponder", "hotValency": len(ports), "warmValency": len(ports)}],
            "publicRoots": [{"accessPoints": [], "advertise": False}], "useLedgerAfterSlot": -1}


def node_script(name, n, port, append=False):
    """Start a Haskell pool; the shell records its pid first, so a crash is `kill -9` of that pid and no pattern."""
    pool = f"{ENV}/pools-keys/pool{n}"
    return (f"cd {ENV} && echo $$ > /work/node{n}.pid && exec cardano-node run --config {POOL_CONFIG} "
            f"--topology {ENV}/node-data/node{n}/topology.json --database-path {ENV}/node-data/node{n}/db "
            f"--shelley-kes-key {pool}/kes.skey --shelley-vrf-key {pool}/vrf.skey "
            f"--shelley-operational-certificate {pool}/opcert.cert "
            f"--byron-delegation-certificate {pool}/byron-delegation.cert "
            f"--byron-signing-key {pool}/byron-delegate.key "
            f"--socket-path {ENV}/socket/node{n}/sock --port {port} --host-addr 127.0.0.1 "
            f"{'>>' if append else '>'} /work/node{n}.log 2>&1")


class Run:
    """The running devnet and what the schedule may do to it."""

    def __init__(self, args, root):
        self.args, self.root, self.name = args, root, args.name
        # The control arm runs pool 1 as a reference node with the same keys, port and links as the Scala node,
        # so that a schedule's outcome can be compared with the reference's own under the same faults.
        self.control = getattr(args, "pool1", "scala") == "reference"
        self.ports = {}
        self.scala = None
        self.scala_command = None
        self.shaper = netfaults.Shaper(args.name, root / "tc")
        self.crashed = set()          # nodes down by the schedule (a node exiting otherwise is a failure)
        self.restarts = []            # the Scala restarts, for the rejoin check
        self.executed = []            # what actually ran, with the time
        self.problems = []            # a schedule action that could not be carried out

    # ------------------------------------------------------------ nodes
    def start_haskell(self, node, append=False):
        n = HASKELL[node]
        in_ref(self.name, f"mkdir -p {ENV}/socket/node{n}; rm -f {ENV}/socket/node{n}/sock")
        docker("exec", "-d", ref(self.name), "/bin/sh", "-c", node_script(self.name, n, self.ports[n], append))

    def haskell_alive(self, node):
        """Whether the pool's recorded pid is a live process (`kill -0`; a failure answers False, it does not raise)."""
        done = docker("exec", ref(self.name), "/bin/sh", "-c", f"kill -0 $(cat /work/node{HASKELL[node]}.pid)", check=False)
        return done.returncode == 0

    def start_scala(self):
        log = (self.root / "follower.jsonl").open("a")
        self.scala = subprocess.Popen(["docker", *self.scala_command], stdout=log, stderr=subprocess.STDOUT, text=True)

    def start_pool1(self, append=False):
        """Pool 1 in the control arm: the reference node on the Scala node's port, dialling its proxy ports."""
        in_ref(self.name, f"mkdir -p {ENV}/socket/node{POOL}; rm -f {ENV}/socket/node{POOL}/sock")
        docker("exec", "-d", ref(self.name), "/bin/sh", "-c", node_script(self.name, POOL, SCALA_PORT, append))

    def pool1_alive(self):
        done = docker("exec", ref(self.name), "/bin/sh", "-c", f"kill -0 $(cat /work/node{POOL}.pid)", check=False)
        return done.returncode == 0

    # ------------------------------------------------------------ faults
    def apply(self, action, t):
        op = action["op"]
        if op == "shape":
            self.shaper.shape(action["link"], action["delayMs"], action["jitterMs"], action["lossPercent"])
        elif op == "heal":
            self.shaper.heal([action["link"]])
        elif op == "kill" and action["node"] == "scala" and self.control:
            self.crashed.add("scala")
            done = docker("exec", ref(self.name), "/bin/sh", "-c", f"kill -9 $(cat /work/node{POOL}.pid)", check=False)
            if done.returncode != 0:
                self.problems.append(f"the crash of pool 1 at {round(t, 1)} s found no live process: {done.stderr.strip()[:200]}")
        elif op == "kill" and action["node"] == "scala":
            gone = self.scala.poll()
            if gone is not None:
                self.problems.append(f"the Scala node had exited with {gone} before its scheduled crash at {round(t, 1)} s")
            done = docker("kill", f"{self.name}-scala", check=False)
            if gone is None and done.returncode != 0:
                self.problems.append(f"the crash of the Scala node at {round(t, 1)} s was refused: {done.stderr.strip()[:200]}")
            self.crashed.add("scala")
            self.scala.wait()
        elif op == "kill":
            self.crashed.add(action["node"])
            done = docker("exec", ref(self.name), "/bin/sh", "-c", f"kill -9 $(cat /work/node{HASKELL[action['node']]}.pid)", check=False)
            if done.returncode != 0:
                self.problems.append(f"the crash of {action['node']} at {round(t, 1)} s found no live process: {done.stderr.strip()[:200]}")
        elif op == "start" and action["node"] == "scala" and self.control:
            self.restarts.append({"at": round(t, 1)})
            self.start_pool1(append=True)
            self.crashed.discard("scala")
        elif op == "start" and action["node"] == "scala":
            self.restarts.append({"at": round(t, 1), "eventsBefore": len(read_log(self.root / "follower.jsonl")[0])})
            docker("rm", "-f", f"{self.name}-scala", check=False)
            self.start_scala()
            self.crashed.discard("scala")
        elif op == "start":
            self.start_haskell(action["node"], append=True)
            self.crashed.discard(action["node"])
        self.executed.append({"t": round(t, 1), **action})

    def scala_lost(self):
        """The exit status of the Scala node if it ended without the schedule's crash, else None; its log outlives it."""
        if "scala" in self.crashed:
            return None
        if self.control:
            return None if self.pool1_alive() else "a dead process"
        if self.scala is None:
            return None
        return self.scala.poll()

    def tips(self):
        """(block number, hash) of every node's tip, or None for a node that does not answer."""
        if self.control:
            tip = tip_at(self.name, f"{ENV}/socket/node{POOL}/sock") if "scala" not in self.crashed else None
            out = {"scala": (tip["block"], tip["hash"]) if tip else None}
        else:
            events, _ = read_log(self.root / "follower.jsonl")
            out = {"scala": scala_tip(events)}
        for node, n in HASKELL.items():
            tip = tip_at(self.name, f"{ENV}/socket/node{n}/sock") if node not in self.crashed else None
            out[node] = (tip["block"], tip["hash"]) if tip else None
        return out


def scala_tip(events):
    """(block number, hash) of the Scala node's tip, replayed from one log snapshot with its rollbacks (the height
    and the hash of the same block), or None while it has no chain."""
    chain = []
    for e in events:
        if e["event"] == "extended":
            chain.append((e["block"]["blockNo"], e["block"]["hash"]))
        elif e["event"] == "rolledBack":
            to = e["to"].get("hash")
            if to is None:
                chain = []
            else:
                kept = [i for i, (_, h) in enumerate(chain) if h == to]
                # an unknown target leaves the tip unknown until later `extended` events rebuild a chain
                chain = chain[:kept[0] + 1] if kept else []
    return chain[-1] if chain else None


def liveness_failures(run):
    """Failures of the processes that must be running: the Scala node and the Haskell pools the schedule did not crash."""
    failures = []
    lost = run.scala_lost()
    if lost is not None:
        failures.append(f"the Scala node exited with {lost} while the cluster settled or at the end of the run")
    failures += [f"{n} exited without being crashed by the schedule" for n in HASKELL if n not in run.crashed and not run.haskell_alive(n)]
    return failures


def write_keys(root, name):
    """The Scala pool's keys, from the reference environment (the Haskell node of this pool is never started)."""
    (root / "keys").mkdir(mode=0o700)
    for key in ("vrf.skey", "kes.skey", "opcert.cert"):
        raw = subprocess.run(["docker", "exec", ref(name), "cat", f"{ENV}/pools-keys/pool{POOL}/{key}"],
                             capture_output=True, check=True).stdout
        path = root / "keys" / key
        path.write_bytes(raw)
        path.chmod(0o600 if key == "kes.skey" else 0o400)   # the node writes the evolved KES key back


def load_plan(args, shelley):
    inputs = (shelley["securityParam"], shelley["activeSlotsCoeff"], shelley["slotLength"])
    if args.schedule:
        plan = json.loads(open(args.schedule).read())
        problem = faultplan.validate(plan)
        if problem:
            raise SystemExit(f"{args.schedule}: {problem}")
        return plan
    return faultplan.generate(args.seed, args.duration, *inputs)


def settle(run, seconds, before):
    """Poll until every node reports the same tip hash and a block newer than its baseline, at most `seconds`;
    returns the last tips and the baselines. `before` holds the tips when the faults were healed; a node that
    had no answer then takes its first answer as the baseline, so it too must advance. The Scala node must stay
    alive: its tip is read from its log, which a dead process leaves behind, so an exit ends the wait (the caller
    reports it)."""
    base = dict(before)
    deadline = time.monotonic() + seconds
    tips = run.tips()
    while True:
        base.update({n: t for n, t in tips.items() if base.get(n) is None and t is not None})
        if (run.scala_lost() is not None
                or not (faultplan.convergence_failures(tips) or faultplan.progress_failures(base, tips))
                or time.monotonic() >= deadline):
            return tips, base
        time.sleep(SETTLE_POLL)
        tips = run.tips()


def start_scala_pool(args, root, run):
    """Pool 1 as the Scala node: the frozen classes in the reference namespace, with pool 1's keys; returns once
    the cluster and the Scala node are running."""
    classpath = freeze_classes(root)
    db = root / "scala-db"
    db.mkdir()
    db.chmod(0o777)
    program = ["follow", "--genesis-dir", "/genesis", "--query-port", str(QUERY_PORT),
               "--listen", f"127.0.0.1:{SCALA_PORT}", "--db", "/db",
               "--forge", "--vrf-skey", "/keys/vrf.skey", "--kes-skey", "/keys/kes.skey", "--opcert", "/keys/opcert.cert"]
    for port in netfaults.peer_ports("scala").values():
        program += ["--peer", f"127.0.0.1:{port}"]
    run.scala_command = scala_container(args, root, classpath, "xyz.kd5ujc.scarab.node.Main", program,
                                        mounts=[f"{root / 'genesis'}:/genesis:ro", *database_mounts(db), f"{root / 'keys'}:/keys"],
                                        name=f"{args.name}-scala")
    run.start_scala()
    log = root / "follower.jsonl"
    deadline = time.monotonic() + 180
    while time.monotonic() < deadline:
        tip = tip_at(args.name, f"{ENV}/socket/node2/sock")
        counts, _, _ = log_counts(log)
        if tip and tip["block"] > 0 and counts.get("forgeStarted") and counts.get("extended"):
            break
        if run.scala.poll() is not None:
            raise SystemExit(f"the Scala node exited with {run.scala.returncode}; see {log}")
        time.sleep(1)
    else:
        raise SystemExit("the cluster or the Scala node did not come up within 180 s")


def faults(args):
    """Fresh 3-pool cluster, the Scala node as pool 1, the schedule of `--seed` over `--duration` seconds, then
    heal and settle; the run fails on a property violation. Exit status 0 only if every property held."""
    root = data_root(args.name)
    if root.exists():
        raise SystemExit(f"{root} exists; run `devnet.py --name {args.name} down` first")
    (root / "genesis").mkdir(parents=True)
    (root / "proxy").mkdir()
    (root / "proxy").chmod(0o777)
    (root / "tc").mkdir()
    run = Run(args, root)
    failures, summary, plan = [], {}, None
    completed = False    # set only after every check has run; an aborted run never reports `passed`
    started = None
    try:
        start_reference(args.name)
        run.ports, shelley = pool_environment(args)
        (root / "ports.json").write_text(json.dumps(run.ports))
        plan = load_plan(args, shelley)
        (root / "fault-schedule.json").write_text(faultplan.render(plan))
        print(json.dumps({"seed": plan["seed"], "duration": plan["duration"], "episodes": len(plan["episodes"]),
                          "schedule": str(root / "fault-schedule.json"),
                          "replay": faultplan.replay_command(args.name, plan, args.schedule)}), flush=True)
        node_ports = {"p2": run.ports[2], "p3": run.ports[3]}
        for node, n in HASKELL.items():
            put_file(args.name, f"{ENV}/node-data/node{n}/topology.json", json.dumps(topology(netfaults.peer_ports(node)), indent=2))
        for f in ["shelley-genesis.json", "byron-genesis.json", "alonzo-genesis.json", "conway-genesis.json", "configuration.yaml"]:
            raw = subprocess.run(["docker", "exec", ref(args.name), "cat", f"{ENV}/{f}"], capture_output=True, check=True).stdout
            (root / "genesis" / f).write_bytes(raw)
            if f == "configuration.yaml":
                put_file(args.name, POOL_CONFIG, pool_configuration(raw))
        write_keys(root, args.name)
        run.shaper.install()
        netfaults.start_proxies(args.name, root, node_ports)
        for node in HASKELL:
            run.start_haskell(node)
        if run.control:
            put_file(args.name, f"{ENV}/node-data/node{POOL}/topology.json",
                     json.dumps(topology(netfaults.peer_ports("scala")), indent=2))
            run.start_pool1()
            deadline = time.monotonic() + 180
            while time.monotonic() < deadline:
                tips = [tip_at(args.name, f"{ENV}/socket/node{n}/sock") for n in (POOL, 2)]
                if all(t and t["block"] > 0 for t in tips):
                    break
                if not run.pool1_alive():
                    raise SystemExit("pool 1 (reference) exited during start-up; see its log")
                time.sleep(1)
            else:
                raise SystemExit("the cluster did not come up within 180 s")
        else:
            start_scala_pool(args, root, run)
        print(json.dumps({"up": True, "pool1": "reference" if run.control else "scala", "ports": run.ports}), flush=True)
        # ------------------------------------------------ the schedule
        started = time.monotonic()
        pending = list(plan["actions"])
        samples = []
        next_sample = 0
        while True:
            t = time.monotonic() - started
            while pending and pending[0]["at"] <= t:
                action = pending.pop(0)
                run.apply(action, t)
                print(json.dumps({"t": round(t, 1), "do": action}), flush=True)
            if t >= next_sample:
                next_sample = t + SAMPLE_EVERY
                tips = run.tips()
                samples.append({"t": round(t, 1), "heights": {n: v and v[0] for n, v in tips.items()}})
                print(json.dumps({"t": round(t, 1), "tips": {n: v and (v[0], v[1][:10]) for n, v in tips.items()}}), flush=True)
            lost = run.scala_lost()
            if lost is not None:
                failures.append(f"pool 1 (reference) exited at {round(t, 1)} s" if run.control
                                else f"the Scala node exited with {lost} at {round(t, 1)} s")
                break
            if t >= plan["duration"] and not pending:
                break
            time.sleep(1)
        run.shaper.heal()
        healed = run.tips()
        final, baseline = settle(run, args.settle, healed)
        pools = {**HASKELL, **({"pool1": POOL} if run.control else {})}
        events, other = ([], []) if run.control else read_log(root / "follower.jsonl")
        text = "" if run.control else (root / "follower.jsonl").read_text(errors="replace")
        logs = {n: in_ref(args.name, f"cat /work/node{pools[n]}.log").stdout for n in pools}
        k = plan["inputs"]["k"]
        failures += run.problems
        failures += faultplan.growth_failures(samples, plan["quiet"])
        failures += faultplan.convergence_failures(final)
        failures += faultplan.progress_failures(baseline, final)
        failures += [f"{n} is still down at the end of the run" for n in sorted(run.crashed)]
        # the Scala node's own event log is read only when it is pool 1; the control arm reports those checks as
        # not applicable rather than passed
        if not run.control:
            failures += faultplan.rollback_failures(events, k)
            failures += faultplan.scala_failures(events, other, text)
            failures += faultplan.rejoin_failures(run.restarts, events)
        failures += faultplan.haskell_failures(logs, [])
        counts = {}
        for e in events:
            counts[e["event"]] = counts.get(e["event"], 0) + 1
        summary = {
            "seed": plan["seed"], "duration": plan["duration"], "inputs": plan["inputs"], "pool1": "reference" if run.control else "scala",
            "notApplicable": ["rollback depth", "Scala node log", "rejoin after restart"] if run.control else [],
            "episodes": [{key: e[key] for key in e if key in ("kind", "start", "end", "links", "node", "groups")}
                         for e in plan["episodes"]],
            "executedActions": len(run.executed),
            "finalTips": {n: v and {"block": v[0], "hash": v[1]} for n, v in final.items()},
            "restarts": run.restarts,
            "deepestRollback": max((e["depth"] for e in events if e["event"] == "rolledBack"), default=0),
            "scalaEvents": {key: counts.get(key, 0) for key in ("extended", "rolledBack", "reconnecting", "stalled", "error",
                                                                "forged", "adopted", "lost")},
            "samples": len(samples),
        }
        # the log collection above took a while: every process must still be running right before the teardown
        failures += [f for f in liveness_failures(run) if f not in failures]
        completed = True
    except SystemExit as e:
        failures.append(str(e))
    except BaseException as e:
        # a timeout, a failed copy or a bug: the receipt records it and the exception still propagates
        failures.append(f"the run aborted before its checks finished: {type(e).__name__}: {e}")
        raise
    finally:
        # guaranteed cleanup: the qdiscs first (a fault must not outlive its run), then the containers
        try:
            run.shaper.remove()
        except Exception as e:   # the namespace goes with its container anyway
            print(f"tc cleanup failed: {e}", file=sys.stderr)
        docker("rm", "-f", f"{args.name}-scala", f"{args.name}-proxy", check=False)
        if run.scala is not None:
            run.scala.wait()
        if plan is not None and docker("inspect", ref(args.name), check=False).returncode == 0:
            # Setup may fail before a pool creates its log. Diagnostic collection must not hide
            # that failure or prevent the summary and remaining cleanup.
            for n in [*HASKELL.values(), *([POOL] if run.control else [])]:
                result = docker("exec", ref(args.name), "/bin/sh", "-c",
                                f"tail -c 200000 /work/node{n}.log", check=False, timeout=60)
                (root / f"node{n}.log").write_text(result.stdout)
                # a failure keeps the whole log: the traces that explain it are seldom in the tail
                if failures:
                    whole = docker("exec", ref(args.name), "cat", f"/work/node{n}.log", check=False, timeout=300)
                    with gzip.open(root / f"node{n}.log.gz", "wt") as out:
                        out.write(whole.stdout)
        summary["failures"] = failures
        summary["completed"] = completed
        summary["passed"] = completed and not failures
        if plan is not None:
            summary["replay"] = faultplan.replay_command(args.name, plan, args.schedule)
        (root / "faults-summary.json").write_text(json.dumps(summary, indent=2))
        print(json.dumps(summary, indent=2))
        if failures and plan is not None:
            print(f"FAILED; repeat with: {faultplan.replay_command(args.name, plan, args.schedule)}", file=sys.stderr)
        if not args.keep:
            docker("rm", "-f", ref(args.name), check=False)
        shutil.rmtree(root / "classes", ignore_errors=True)
    sys.exit(0 if not failures else 1)
