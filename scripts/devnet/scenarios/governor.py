# SPDX-License-Identifier: Apache-2.0
"""The `governor` scenario: the peer governor with churn and the peer metrics, against the three pools.

The Scala node gets the three pools as public roots (pool 1 through a slow proxy, so that it is never first with a
header), the targets of a three-peer network (3 known, 3 established, 2 active) and a churn interval of seconds
(`--churn-interval`, a test-only flag). It must

  * reach the targets: 3 established and 2 hot, from the `/metrics` gauges of the reference's names;
  * run churn rounds: the six steps for ordinary peers in the reference's order, the active target lowered and restored
    each time, one hot peer demoted by the governor and the hot count back at 2 at the end of the step;
  * demote by the peer metrics: whenever the slow peer is hot when a round demotes, it is the peer demoted; and the hot
    set is replaced by some round;
  * handle a silent peer: the proxy goes silent while its peer is hot, the node keeps extending its chain on the others,
    loses the peer and promotes the spare one, and after the proxy is released the peer comes back after its backoff.
"""
import json
import shutil
import subprocess
import sys
import time
from ..cluster import freeze_classes, scala_container, up
from ..metrics import parse, scrape, value
from ..util import ENV, QUERY_PORT, data_root, docker, read_log, ref
from .peercut import PROXY_PORT, last_block, start_proxy
from .relay import tip_at

SLOW = f"127.0.0.1:{PROXY_PORT}"
ORDINARY_STEPS = ["DecreaseActive", "IncreaseActive", "DecreaseEstablished", "DecreaseKnown", "IncreaseKnown",
                  "IncreaseEstablished"]
GAUGES = {"hot": "cardano_node_metrics_peerSelection_Hot_int", "warm": "cardano_node_metrics_peerSelection_Warm_int",
          "established": "cardano_node_metrics_peerSelection_EstablishedPeers_int",
          "known": "cardano_node_metrics_peerSelection_KnownPeers_int",
          "root": "cardano_node_metrics_peerSelection_RootPeers_int"}


def write_files(args, root, ports):
    """The node's configuration (the devnet's own, with the targets of a three-peer network) and its topology."""
    cfg = root / "cfg"
    cfg.mkdir(exist_ok=True)
    base = json.loads((root / "genesis" / "configuration.yaml").read_text())
    for key in ("SocketPath", "TraceOptions"):
        base.pop(key, None)
    base.update({"TargetNumberOfRootPeers": 3, "TargetNumberOfKnownPeers": 3, "TargetNumberOfEstablishedPeers": 3,
                 "TargetNumberOfActivePeers": 2})
    (cfg / "config.json").write_text(json.dumps(base, indent=2))
    access = [{"address": "127.0.0.1", "port": PROXY_PORT}] + [
        {"address": "127.0.0.1", "port": ports[str(n)]} for n in (2, 3)]
    (cfg / "topology.json").write_text(json.dumps({
        "bootstrapPeers": None, "localRoots": [], "publicRoots": [{"accessPoints": access, "advertise": False}],
        "useLedgerAfterSlot": -1}, indent=2))


def start_node(args, root):
    classpath = freeze_classes(root)
    program = ["follow", "--genesis-dir", "/genesis", "--config", "/cfg/config.json", "--topology",
               "/cfg/topology.json", "--query-port", str(QUERY_PORT), "--churn-interval", f"{args.churn_interval}s",
               "--stall-minutes", str(args.stall_minutes)]
    command = scala_container(args, root, classpath, "xyz.kd5ujc.scarab.node.Main", program,
                              mounts=[f"{root / 'genesis'}:/genesis:ro", f"{root / 'cfg'}:/cfg:ro"],
                              name=f"{args.name}-scala")
    log = (root / "follower.jsonl").open("a")
    return subprocess.Popen(["docker", *command], stdout=log, stderr=subprocess.STDOUT, text=True)


def gauges(name):
    """The governor's gauges from `/metrics`, by short name; none while the node does not answer."""
    got = scrape(name, QUERY_PORT)
    if got is None:
        return None
    try:
        samples = parse(got[1])
    except ValueError:
        return None
    out = {k: value(samples, metric) for k, metric in GAUGES.items()}
    return out if all(v is not None for v in out.values()) else None


def eventually(what, timeout, read, ok, node=None, pause=1.0):
    """Read until `ok(value)`; a clear failure after `timeout` seconds or when the node exits."""
    deadline = time.monotonic() + timeout
    last = None
    while time.monotonic() < deadline:
        last = read()
        if last is not None and ok(last):
            return last
        if node is not None and node.poll() is not None:
            raise SystemExit(f"the Scala node exited with {node.returncode} while waiting for: {what}")
        time.sleep(pause)
    raise SystemExit(f"timed out after {timeout} s waiting for: {what} (last: {last})")


def hot_set(events):
    """The peers that are hot after the log so far, replayed from the governor's events."""
    hot = set()
    for e in events:
        hot = step_hot(hot, e)
    return hot


def step_hot(hot, e):
    kind, action = e.get("event"), e.get("action")
    if kind == "peerSelectionPromote" and action == "done" and e.get("to") == "hot":
        return hot | {e["peer"]}
    if kind == "peerSelectionDemote" and action in ("done", "failed", "asynchronous"):
        return hot - {e["peer"]}
    return hot


def churn_rounds(events):
    """The rounds of the churn governor in the log: for each, its steps in order and what the governor demoted
    while the active target was lowered (the peers, and the hot set just before the decision)."""
    rounds, current, hot, lowered = [], None, set(), False
    for e in events:
        if e.get("event") == "peerSelectionChurn":
            if e["action"] == "round":
                current = {"steps": [], "timeouts": [], "demoted": [], "hotAtStart": sorted(hot), "hotAfter": None}
                rounds.append(current)
                lowered = True
            elif e["action"] in ("step", "timeout") and current is not None:
                current["steps"].append(e["step"])
                if e["action"] == "timeout":
                    current["timeouts"].append(e["step"])
                if e["step"] == "IncreaseActive":
                    lowered = False
                    current["hotAfter"] = sorted(hot)
        elif (e.get("event") == "peerSelectionDemote" and e.get("action") == "decided" and e.get("from") == "hot"
              and lowered and current is not None and not e.get("local")):
            current["demoted"].append({"peers": e["peers"], "hotBefore": sorted(hot)})
        hot = step_hot(hot, e)
    return rounds


def check_rounds(rounds, failures, summary):
    complete = [r for r in rounds if r["steps"][:6] == ORDINARY_STEPS or len(r["steps"]) >= 6]
    bad_order = [r["steps"] for r in complete if r["steps"][:6] != ORDINARY_STEPS]
    if bad_order:
        failures.append(f"a churn round did not run the six steps in the reference's order: {bad_order[0]}")
    for r in complete:
        if "DecreaseActive" in r["timeouts"]:
            failures.append("a churn round lowered the active target and the governor did not demote (timeout)")
            break
    demotions = [d for r in complete for d in r["demoted"]]
    if any(len(d["peers"]) != 1 for d in demotions):
        failures.append(f"a churn round demoted other than one hot peer: {[d['peers'] for d in demotions]}")
    if any(r["hotAfter"] is not None and len(r["hotAfter"]) != 2 for r in complete):
        failures.append("the hot count was not back at 2 after a churn round raised the active target")
    ranked = [d for d in demotions if SLOW in d["hotBefore"]]
    wrong = [d for d in ranked if d["peers"] != [SLOW]]
    if wrong:
        failures.append(f"the slow peer was hot but a better one was demoted: {wrong[0]}")
    if not ranked:
        failures.append("no round demoted while the slow peer was hot: the ranking by the metrics was not exercised")
    changed = any(a["hotAfter"] != b["hotAfter"] for a, b in zip(complete, complete[1:]) if a["hotAfter"] and b["hotAfter"])
    if not changed:
        failures.append("no churn round replaced a hot peer")
    summary["churnRounds"] = len(complete)
    summary["churnDemotions"] = len(demotions)
    summary["slowPeerHotAtDemotion"] = len(ranked)
    summary["slowPeerDemotedWhenHot"] = len(ranked) - len(wrong)
    summary["hotSetChanged"] = changed
    return complete


def governor(args):
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
            time.sleep(3)
        else:
            raise SystemExit(f"the cluster did not reach block {args.backlog_blocks} in {args.backlog_timeout} s")
        freeze = start_proxy(args, root, ports["1"])
        write_files(args, root, ports)
        scala = start_node(args, root)
        events = lambda: read_log(log)[0]  # noqa: E731

        # 1. the targets, from the reference's gauges
        reached = eventually("3 established, 2 hot, 1 warm, 3 known", 180, lambda: gauges(args.name),
                             lambda g: g["established"] == 3 and g["hot"] == 2 and g["warm"] == 1 and g["known"] == 3,
                             scala)
        summary["targetsReached"] = reached

        # 2. churn rounds; keep going until the slow peer has been hot at a demotion a few times
        started = time.monotonic()
        limit = args.rounds * (args.churn_interval * 6 + 30)

        def seen():
            rounds = [r for r in churn_rounds(events()) if len(r["steps"]) >= 6]
            ranked = [d for r in rounds for d in r["demoted"] if SLOW in d["hotBefore"]]
            return rounds if len(rounds) >= args.rounds and len(ranked) >= 2 else None
        try:
            eventually(f"{args.rounds} churn rounds with the slow peer hot at two demotions", limit, seen,
                       lambda r: True, scala, pause=2)
        except SystemExit as e:
            failures.append(str(e))
        summary["churnSeconds"] = round(time.monotonic() - started)
        check_rounds(churn_rounds(events()), failures, summary)

        # 3. a silent peer: wait for the slow peer to be hot, then cut it
        eventually("the slow peer hot", 240, lambda: SLOW in hot_set(events()) or None, lambda v: True, scala)
        before = last_block(events())
        freeze.write_text("cut")
        cut_index = len(events())
        # the node loses it (keep-alive or chain-sync time out) and holds 2 hot peers again, on the others
        lost = eventually("the silent peer lost and two other peers hot", args.silent_timeout,
                          lambda: hot_set(events()) if SLOW not in hot_set(events()) else None,
                          lambda h: len(h) == 2, scala, pause=2)
        summary["hotAfterCut"] = sorted(lost)
        after_loss = last_block(events())
        # lost as the governor sees it: the connection fails under us (asynchronous), or the deactivation
        # it asked for fails and the connection is closed (failed)
        lost_events = [e for e in events()[cut_index:] if e.get("event") == "peerSelectionDemote"
                       and e.get("action") in ("asynchronous", "failed") and e.get("peer") == SLOW]
        if not lost_events:
            failures.append("the silent peer was never reported lost (an asynchronous or failed demotion)")
        summary["silentPeerLostAs"] = sorted({e["action"] for e in lost_events})
        theirs = tip_at(args.name, f"{ENV}/socket/node2/sock")
        time.sleep(args.follow_seconds)
        grown = last_block(events())
        summary.update({"blockAtCut": before, "blockWhenLost": after_loss, "blockAfter": grown,
                        "referenceTip": (theirs or {}).get("block")})
        if grown <= after_loss:
            failures.append(f"the node extended nothing in the {args.follow_seconds} s after it lost the silent peer")

        # 4. the proxy is released: the peer comes back after its backoff, as a warm peer
        freeze.unlink()
        returned = eventually("the lost peer established again (3 established, 2 hot)", args.backoff_timeout,
                              lambda: gauges(args.name),
                              lambda g: g["established"] == 3 and g["hot"] == 2, scala, pause=2)
        summary["afterRelease"] = returned

        # 5. the exported metrics agree with the log
        got = scrape(args.name, QUERY_PORT)
        samples = parse(got[1]) if got else {}
        rounds_logged = sum(1 for e in events() if e.get("event") == "peerSelectionChurn" and e["action"] == "round")
        exported = value(samples, "scarab_peer_selection_churn_rounds_total")
        if exported is None or not (rounds_logged - 1 <= exported <= rounds_logged + 1):
            failures.append(f"scarab_peer_selection_churn_rounds_total {exported} against {rounds_logged} rounds in the log")
        steps = sum(v for (n, _), v in samples.items() if n == "scarab_peer_selection_churn_steps_total")
        if steps < 6:
            failures.append(f"scarab_peer_selection_churn_steps_total is {steps}")
        board = {dict(k[1]).get("board"): v for k, v in samples.items() if k[0] == "scarab_peer_selection_peer_metrics_board_size"}
        summary["metricsBoard"] = board
        if not board or any(not 0 < v <= 180 for v in board.values()):
            failures.append(f"the peer metrics boards are empty or above 180 slots: {board}")
        if value(samples, "scarab_peer_selection_churn_wait_seconds") is None:
            failures.append("scarab_peer_selection_churn_wait_seconds is absent")

        evs = events()
        counts = {}
        for e in evs:
            counts[e["event"]] = counts.get(e["event"], 0) + 1
        summary["scalaEvents"] = {k: counts.get(k, 0) for k in (
            "extended", "reconnecting", "stalled", "error", "peerSelectionPromote", "peerSelectionDemote",
            "peerSelectionChurn")}
        if counts.get("error"):
            failures.append(f"{counts['error']} error events in the Scala log")
        if counts.get("stalled"):
            failures.append(f"{counts['stalled']} stalled event(s) although healthy peers were ahead")
        if "Submarine" in log.read_text(errors="replace"):
            failures.append("a cats-mtl Submarine reached the runtime's failure reporter")
        other = read_log(log)[1]
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
        (root / "governor-summary.json").write_text(json.dumps(summary, indent=2))
        print(json.dumps(summary, indent=2))
        if not args.keep:
            docker("rm", "-f", ref(args.name), check=False)
            shutil.rmtree(root / "classes", ignore_errors=True)
    sys.exit(0 if not failures else 1)
