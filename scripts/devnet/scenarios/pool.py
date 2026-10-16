# SPDX-License-Identifier: Apache-2.0
"""The `pool` scenario: the Scala node as a block-producing pool among Haskell pools."""
import calendar
import datetime
import json
import os
import shutil
import subprocess
import sys
import time
from .. import metrics as node_metrics
from ..cluster import freeze_classes, scala_container, start_nodes, start_reference
from ..compare import compare_once
from ..scenarios.relay import SCALA_PORT, log_counts, tip_at
from .. import util
from ..util import CLI, ENTRY, ENV, MAGIC, QUERY_PORT, data_root, docker, in_ref, put_file, read_json, read_log, ref, scheduling_summary


# ---------------------------------------------------------------- the Scala node as a block producer

POOL_KES_SLOTS = 250   # slotsPerKESPeriod of the pool scenario (the sandbox default, 129600, never changes period)


def pool_metrics_failures(name, before, fetch):
    """The forger's Prometheus series (the Haskell node's names) against its own `/forging` counters, read before
    and after the scrape: a counter lies between the two readings."""
    got = node_metrics.scrape(name, QUERY_PORT)
    if got is None:
        return ["GET /metrics gave no answer"]
    try:
        samples = node_metrics.parse(got[1])
    except ValueError as e:
        return [f"GET /metrics is not well formed: {e}"]
    after = json.loads(docker("exec", f"{name}-scala", "python3", "-c", fetch, check=False, timeout=60).stdout)["total"]
    failures = []
    for metric, key in (("cardano_node_metrics_Forge_about_to_lead_counter", "slotsChecked"),
                        ("cardano_node_metrics_Forge_node_is_leader_counter", "leaderSlots"),
                        ("cardano_node_metrics_Forge_forged_counter", "forged"),
                        ("cardano_node_metrics_Forge_adopted_counter", "adopted"),
                        ("cardano_node_metrics_Forge_didnt_adopt_counter", "lostSlotBattles")):
        v = node_metrics.value(samples, metric)
        if v is None or not before[key] <= v <= after[key]:
            failures.append(f"{metric} is {v}, /forging gave {before[key]} before and {after[key]} after")
    for metric in ("cardano_node_metrics_forging_enabled_int", "cardano_node_metrics_currentKESPeriod_int",
                   "cardano_node_metrics_remainingKESPeriods_int",
                   "cardano_node_metrics_operationalCertificateStartKESPeriod_int",
                   "cardano_node_metrics_operationalCertificateExpiryKESPeriod_int",
                   "cardano_node_metrics_forgedSlotLast_int"):
        if node_metrics.value(samples, metric) is None:
            failures.append(f"missing series {metric}")
    return failures


def pool_topology(name, others, ports):
    """Each remaining Haskell pool peers with the other remaining pools and with the Scala node."""
    for n in others:
        peers = [p for m, p in ports.items() if m != n]
        groups = [{"accessPoints": [{"address": "127.0.0.1", "port": p} for p in peers],
                   "advertise": False, "behindFirewall": False, "trustable": True,
                   "diffusionMode": "InitiatorAndResponder", "hotValency": len(peers), "warmValency": len(peers)},
                  {"accessPoints": [{"address": "127.0.0.1", "port": SCALA_PORT}],
                   "advertise": False, "behindFirewall": False, "trustable": True,
                   "diffusionMode": "InitiatorAndResponder", "hotValency": 1, "warmValency": 1}]
        topology = {"bootstrapPeers": None, "localRoots": groups,
                    "publicRoots": [{"accessPoints": [], "advertise": False}], "useLedgerAfterSlot": -1}
        put_file(name, f"{ENV}/node-data/node{n}/topology.json", json.dumps(topology, indent=2))


def pool_environment(args):
    """create-env, a short KES period and a system start a few seconds ahead; returns the node ports and the genesis as the network's clock sees it."""
    in_ref(args.name, f"cardano-testnet create-env --num-pool-nodes 3 --testnet-magic {MAGIC} "
                      f"--epoch-length {args.epoch_length} --output {ENV} > /work/create-env.log 2>&1")
    start = int(time.time()) + args.start_delay
    shelley = read_json(args.name, f"{ENV}/shelley-genesis.json")
    shelley["systemStart"] = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(start))
    shelley["slotsPerKESPeriod"] = POOL_KES_SLOTS
    # The reference nodes start their clock at the Byron genesis' startTime, not at the Shelley systemStart
    # (create-env writes the Byron one a moment before this function writes the Shelley one: they used to
    # differ by whole seconds, which is the slot-clock offset of roadmap H6). Both are written here;
    # `--byron-start-offset` skews the Byron one on purpose to check that the Scala node follows the Byron one.
    byron = read_json(args.name, f"{ENV}/byron-genesis.json")
    byron["startTime"] = start + args.byron_start_offset
    put_file(args.name, f"{ENV}/byron-genesis.json", json.dumps(byron, indent=2))
    put_file(args.name, f"{ENV}/shelley-genesis.json", json.dumps(shelley, indent=2))
    # what the rest of the scenario measures slots against: the network's clock
    shelley = dict(shelley, systemStart=time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(byron["startTime"])))
    for n in (1, 2, 3):  # the load generator's wallets: the genesis keys' enterprise addresses
        in_ref(args.name, f"{CLI} address build --payment-verification-key-file {ENV}/utxo-keys/utxo{n}/utxo.vkey "
                          f"--testnet-magic {MAGIC} > {ENV}/utxo-keys/utxo{n}/utxo.addr")
    return {n: int(in_ref(args.name, f"cat {ENV}/node-data/node{n}/port").stdout) for n in (1, 2, 3)}, shelley


def pool_chain(events):
    """The Scala node's final chain (block hashes in order), replayed from `extended` and `rolledBack`."""
    chain = []
    for e in events:
        if e["event"] == "extended":
            chain.append(e["block"]["hash"])
        elif e["event"] == "rolledBack":
            to = e["to"].get("hash")
            if to is None:
                chain = []
            elif to in chain:
                chain = chain[:chain.index(to) + 1]
    return chain


def pool_blocks_made(comparisons, pool_id):
    """Blocks of `pool_id` on the reference chain, per epoch: the final count of a finished epoch is the
    `blocksBefore` of the next one; the last epoch's is the largest `blocksCurrent` seen."""
    final, partial = {}, {}
    for c in comparisons:
        epoch = c["tip"]["epoch"]
        partial[epoch] = max(partial.get(epoch, 0), c["blocksCurrent"].get(pool_id, 0))
        if epoch > 0:
            final[epoch - 1] = c["blocksBefore"].get(pool_id, 0)
    per_epoch = {e: final.get(e, partial.get(e, 0)) for e in sorted(set(partial) | set(final))}
    return per_epoch


def leadership_schedule(name, socket, pool_number):
    """The slots the pool leads in the epoch of `socket`'s node, from the reference node's own
    `query leadership-schedule --current` (the oracle for the Scala forger), or None when it cannot answer."""
    keys = f"{ENV}/pools-keys/pool{pool_number}"
    r = docker("exec", ref(name), "/bin/sh", "-c",
               f"cardano-cli conway query leadership-schedule --testnet-magic {MAGIC} --socket-path {socket} "
               f"--genesis {ENV}/shelley-genesis.json --stake-pool-verification-key-file {keys}/cold.vkey "
               f"--vrf-signing-key-file {keys}/vrf.skey --current --output-json", check=False, timeout=60)
    try:
        return [int(e["slotNumber"]) for e in json.loads(r.stdout)]
    except (json.JSONDecodeError, KeyError, TypeError):
        return None


def slot_at(shelley, stamp):
    """The slot of an ISO timestamp of the log (the pool scenario's genesis has one slot length throughout)."""
    system_start = calendar.timegm(time.strptime(shelley["systemStart"], "%Y-%m-%dT%H:%M:%SZ"))
    at = datetime.datetime.fromisoformat(stamp.replace("Z", "+00:00")).timestamp()
    return int((at - system_start) / shelley["slotLength"])


def slot_clock(events, shelley):
    """How the Scala node's slot clock stands against the reference nodes', in milliseconds after the slot's start
    by the genesis: when the Scala node logged a block it forged (`forgeLatencyMs`, normally a few ms) and when it
    received a block another pool forged (`arrivalMs`, normally a few ms). A forge latency of a second or more
    with normal arrivals means the Scala node believes it is an earlier slot than the network does, so its
    leader slots find the tip already past them and are silently skipped."""
    system_start = calendar.timegm(time.strptime(shelley["systemStart"], "%Y-%m-%dT%H:%M:%SZ"))
    length = shelley["slotLength"]

    def late(e, slot):
        at = datetime.datetime.fromisoformat(e["at"].replace("Z", "+00:00")).timestamp()
        return (at - system_start - slot * length) * 1000

    def median(values):
        values = sorted(values)
        return round(values[len(values) // 2]) if values else None

    mine = {e["hash"] for e in events if e["event"] == "forged"}
    return {"forgeLatencyMs": median([late(e, e["slot"]) for e in events if e["event"] == "forged"]),
            "arrivalMs": median([late(e, e["block"]["slot"]) for e in events
                                 if e["event"] == "extended" and e["block"]["hash"] not in mine])}


def missed_leader_slots(events, schedules, shelley):
    """Check the Scala pool's forging against the reference node's `leadership-schedule`.

    A scheduled slot is accounted for when the pool forged in it, or when another pool's block occupies the slot
    (a slot battle it could not win, or a block that arrived first). Only the window in which the forger ran counts:
    from the slot after its first synced block to two slots before the last log line. Returns a dict with the
    scheduled count in the window, the missed slots, the slots lost to another pool's block, and the epochs of the
    window without a schedule."""
    forged = {e["hash"]: e["slot"] for e in events if e["event"] == "forged"}
    others = {e["block"]["slot"] for e in events if e["event"] == "extended" and e["block"]["hash"] not in forged}
    first = next((slot_at(shelley, e["at"]) for e in events if e["event"] == "extended"), None)
    if first is None:
        return {"window": None, "scheduled": 0, "missed": [], "missedReasons": {}, "occupied": [], "absentEpochs": []}
    window = (first + 1, slot_at(shelley, events[-1]["at"]) - 2)
    length = shelley["epochLength"]
    scheduled = sorted(s for slots in schedules.values() for s in slots if window[0] <= s <= window[1])
    mine = set(forged.values())
    skipped = {e["slot"]: e["reason"] for e in events if e["event"] == "forgeSkipped"}
    return {"window": list(window), "scheduled": len(scheduled),
            "missed": [s for s in scheduled if s not in mine and s not in others],
            "missedReasons": {s: skipped.get(s, "not reported") for s in scheduled
                              if s not in mine and s not in others},
            "occupied": [s for s in scheduled if s not in mine and s in others],
            "absentEpochs": [e for e in range(window[0] // length, window[1] // length + 1) if e not in schedules]}


def host_load():
    """The host's load average (1 and 5 minutes) and CPU count: a 0.1 s slot on one CPU is only a fair test on a host
    that is not oversubscribed."""
    one, five = open("/proc/loadavg").read().split()[:2]
    return {"load1": float(one), "load5": float(five), "cpus": os.cpu_count()}


def overloaded(*loads):
    return any(l["load1"] > l["cpus"] for l in loads)


def pool(args):
    """Fresh 3-pool cluster in which pool `--pool` is the Scala node. Its Haskell node is never started
    (so no two producers ever hold the keys), the other two pools get the Scala node in their topology, and
    the Scala node follows them and forges with the pool's VRF key, KES key and operational certificate."""
    root = data_root(args.name)
    if root.exists():
        raise SystemExit(f"{root} exists; run `devnet.py --name {args.name} down` first")
    (root / "genesis").mkdir(parents=True)
    (root / "keys").mkdir(mode=0o700)
    start_reference(args.name)
    ours = args.pool
    others = [n for n in (1, 2, 3) if n != ours]
    observer = others[0]
    util.SOCKET = f"--testnet-magic {MAGIC} --socket-path {ENV}/socket/node{observer}/sock"
    failures, summary, scala, loader = [], {}, None, None
    load_at_start = host_load()
    comparisons, samples = [], []
    schedules = {}  # epoch -> the slots the oracle says the Scala pool leads
    try:
        ports, shelley = pool_environment(args)
        (root / "ports.json").write_text(json.dumps(ports))
        pool_topology(args.name, others, {n: ports[n] for n in others})
        for f in ["shelley-genesis.json", "byron-genesis.json", "alonzo-genesis.json",
                  "conway-genesis.json", "configuration.yaml"]:
            raw = subprocess.run(["docker", "exec", ref(args.name), "cat", f"{ENV}/{f}"],
                                 capture_output=True, check=True).stdout
            (root / "genesis" / f).write_bytes(raw)
        for key in ("vrf.skey", "kes.skey", "opcert.cert"):
            raw = subprocess.run(["docker", "exec", ref(args.name), "cat", f"{ENV}/pools-keys/pool{ours}/{key}"],
                                 capture_output=True, check=True).stdout
            path = root / "keys" / key
            path.write_bytes(raw)
            path.chmod(0o400)
        start_nodes(args.name, tuple(others))
        classpath = freeze_classes(root)
        db = root / "scala-db"
        db.mkdir()
        db.chmod(0o777)
        program = ["follow", "--genesis-dir", "/genesis", "--query-port", str(QUERY_PORT),
                   "--listen", f"127.0.0.1:{SCALA_PORT}", "--db", "/db",
                   "--forge", "--vrf-skey", "/keys/vrf.skey", "--kes-skey", "/keys/kes.skey",
                   "--opcert", "/keys/opcert.cert"]
        for n in others:
            program += ["--peer", f"127.0.0.1:{ports[n]}"]
        program += args.scala_flags.split()
        command = scala_container(args, root, classpath, "xyz.kd5ujc.scarab.node.Main", program,
                                  mounts=[f"{root / 'genesis'}:/genesis:ro", f"{db}:/db",
                                          f"{root / 'keys'}:/keys:ro"], name=f"{args.name}-scala")
        log = root / "follower.jsonl"
        scala = subprocess.Popen(["docker", *command], stdout=log.open("a"), stderr=subprocess.STDOUT, text=True)
        deadline = time.monotonic() + 180
        while time.monotonic() < deadline:
            tip = tip_at(args.name, f"{ENV}/socket/node{observer}/sock")
            counts, _, _ = log_counts(log)
            if tip and tip["block"] > 0 and counts.get("forgeStarted") and counts.get("extended"):
                break
            if scala.poll() is not None:
                raise SystemExit(f"the Scala node exited with {scala.returncode}; see {log}")
            time.sleep(1)
        else:
            raise SystemExit("the cluster or the Scala node did not come up within 180 s")
        print(json.dumps({"up": True, "ports": ports, "pool": ours, "observer": observer}), flush=True)
        loader = subprocess.Popen([sys.executable, str(ENTRY), "--name", args.name, "load", "--seed", str(args.seed),
                                   "--socket", f"{ENV}/socket/node{observer}/sock", "--interval", str(args.interval),
                                   "--stats-every", "5"],
                                  stdout=(root / "load.log").open("w"), stderr=subprocess.STDOUT)
        started = time.monotonic()
        stop = started + args.seconds
        next_compare = started + 20
        while time.monotonic() < stop:
            time.sleep(args.every)
            if scala.poll() is not None:
                failures.append(f"the Scala node exited with {scala.returncode}")
                break
            sample = {"t": round(time.monotonic() - started, 1)}
            for n in others:
                sample[f"pool{n}"] = tip_at(args.name, f"{ENV}/socket/node{n}/sock")
            samples.append(sample)
            seen = sample[f"pool{observer}"]
            if seen and seen["epoch"] not in schedules:
                got = leadership_schedule(args.name, f"{ENV}/socket/node{observer}/sock", ours)
                if got is not None:
                    schedules[seen["epoch"]] = got
                    print(json.dumps({"schedule": {"epoch": seen["epoch"], "slots": len(got)}}), flush=True)
            counts, _, _ = log_counts(log)
            print(json.dumps({"t": sample["t"], **{k: v and (v["epoch"], v["block"], v["hash"][:10])
                                                  for k, v in sample.items() if k != "t"},
                              **{k: counts.get(k, 0) for k in ("forged", "adopted", "lost")}}), flush=True)
            if time.monotonic() >= next_compare:
                next_compare = time.monotonic() + args.compare_every
                r = compare_once(args.name)
                if r is not None:
                    comparisons.append(r)
                    print(json.dumps({k: r.get(k) for k in ("tip", "matched", "differenceCount", "error")}), flush=True)
        loader.terminate()
        loader.wait()
        loader = None
        # let the chain settle; every node must agree on the tip
        def settled_tips():
            deadline_ = time.monotonic() + 90
            tips = {}
            while time.monotonic() < deadline_:
                tips = {f"pool{n}": tip_at(args.name, f"{ENV}/socket/node{n}/sock") for n in others}
                if all(tips.values()) and len({t["hash"] for t in tips.values()}) == 1:
                    break
                time.sleep(1)
            return tips

        final = settled_tips()
        for _ in range(3):  # one last bracketed comparison at the settled tip
            r = compare_once(args.name)
            if r is not None:
                comparisons.append(r)
                print(json.dumps({k: r.get(k) for k in ("tip", "matched", "differenceCount", "error")}), flush=True)
                break
        final = settled_tips()
        time.sleep(3)
        # the forger's own per-epoch counters (H5), from the query server (the Scala container's loopback)
        fetch = (f"import urllib.request,sys;sys.stdout.write(urllib.request.urlopen("
                 f"'http://127.0.0.1:{QUERY_PORT}/forging',timeout=10).read().decode())")
        got = docker("exec", f"{args.name}-scala", "python3", "-c", fetch, check=False, timeout=60)
        try:
            forging_stats = json.loads(got.stdout)
        except ValueError:
            forging_stats = None
            failures.append(f"GET /forging gave no JSON: {got.stdout[:100]!r} {got.stderr[:200]!r}")
        events, other = read_log(log)
        counts = {}
        for e in events:
            counts[e["event"]] = counts.get(e["event"], 0) + 1
        pool_id = next(e["pool"] for e in events if e["event"] == "forgeStarted")
        forged = [e for e in events if e["event"] == "forged"]
        adopted = {e["block"]["hash"] for e in events if e["event"] == "adopted"}
        lost = [e for e in events if e["event"] == "lost"]
        chain = pool_chain(events)
        on_chain = [e for e in forged if e["hash"] in set(chain)]
        per_epoch = pool_blocks_made(comparisons, pool_id)
        haskell_count = sum(per_epoch.values())
        last_compared = comparisons[-1]["tip"]["hash"] if comparisons else None
        forged_hashes = {e["hash"] for e in forged}
        upto_compared = (len([h for h in chain[:chain.index(last_compared) + 1] if h in forged_hashes])
                         if last_compared in chain else None)
        # expected share: slots the Scala node could forge in, times its win probability at its stake
        f = shelley["activeSlotsCoeff"]
        stake_fraction = [e["stake"] / e["totalStake"] for e in forged if e["totalStake"]]
        sigma = sum(stake_fraction) / len(stake_fraction) if stake_fraction else 0
        first_slot = min((e["slot"] for e in forged), default=None)
        started_event = next(e for e in events if e["event"] == "forgeStarted")
        system_start = calendar.timegm(time.strptime(shelley["systemStart"], "%Y-%m-%dT%H:%M:%SZ"))
        started_at = datetime.datetime.fromisoformat(started_event["at"].replace("Z", "+00:00")).timestamp()
        tip_slot = max((c["tip"]["slot"] for c in comparisons), default=0)
        active_slots = max(0, tip_slot - max(0, int((started_at - system_start) / shelley["slotLength"])))
        expected = active_slots * (1 - (1 - f) ** sigma)
        tolerance = 4 * expected ** 0.5 + 2
        txs = [t for e in on_chain for t in e["txs"]]
        mempool_added = {e["tx"] for e in events if e["event"] == "mempoolAdded"}
        relative = sorted({e["relativeKesPeriod"] for e in forged})
        kes_periods = sorted({e["kesPeriod"] for e in forged})
        fork_depth = max((e["depth"] for e in events if e["event"] == "rolledBack"), default=0)
        consecutive, worst = 0, 0
        for sample_ in samples:
            tips = [sample_.get(f"pool{n}") for n in others]
            consecutive = consecutive + 1 if all(tips) and len({t["hash"] for t in tips}) > 1 else 0
            worst = max(worst, consecutive)
        scala_tip = chain[-1] if chain else None
        k = shelley["securityParam"]
        load_lines = (root / "load.log").read_text().strip().splitlines()
        load_stats = next((json.loads(l) for l in reversed(load_lines) if l.startswith("{") and "submitted" in l), {})
        matched = [c for c in comparisons if c.get("matched")]
        oracle = missed_leader_slots(events, schedules, shelley)
        summary = {
            "oracle": oracle,
            "pool": {"id": pool_id, "number": ours, "stakeFraction": round(sigma, 4)},
            "slotsPerKesPeriod": POOL_KES_SLOTS,
            "forging": {"forged": len(forged), "adopted": len(adopted), "lost": len(lost),
                        "onFinalChain": len(on_chain), "forgeFailed": counts.get("forgeFailed", 0),
                        "kesEvolved": counts.get("kesEvolved", 0)},
            "haskellBlocksMadeByPool": {"total": haskell_count, "perEpoch": per_epoch,
                                        "scalaBlocksOnChainUpToLastComparedTip": upto_compared},
            "expectedBlocks": round(expected, 1), "tolerance": round(tolerance, 1), "activeSlots": active_slots,
            "kes": {"relativePeriodsUsed": relative, "absolutePeriodsUsed": kes_periods,
                    "periodBoundariesCrossed": max(0, len(relative) - 1)},
            "transactions": {"inScalaForgedBlocks": len(txs), "distinctTxs": len(set(txs)),
                             "alsoSeenInScalaMempool": len(set(txs) & mempool_added),
                             "blocksWithTxs": sum(1 for e in on_chain if e["txs"]),
                             "loadSubmitted": load_stats.get("submitted")},
            "comparisons": {"total": len(comparisons), "matched": len(matched),
                            "mismatched": [c for c in comparisons if c.get("matched") is False][:2],
                            "errors": [c for c in comparisons if "error" in c][:2]},
            "forks": {"deepestRollback": fork_depth, "k": k, "longestDisagreementSamples": worst,
                      "finalTips": {n: v and (v["block"], v["hash"][:12]) for n, v in final.items()},
                      "scalaTip": scala_tip and scala_tip[:12]},
            "scalaEvents": {k_: counts.get(k_, 0) for k_ in ("extended", "rolledBack", "reconnecting", "error",
                                                             "inbound", "mempoolAdded", "mempoolAdopted",
                                                             "mempoolRejected", "mempoolDropped")},
            "unparsedScalaOutput": other[:10],
            "scheduling": scheduling_summary(events, log, args),
            "slotClock": slot_clock(events, shelley),
            "forgerStats": forging_stats,
        }
        if forging_stats:
            total = forging_stats["total"]
            budget = 5 * shelley["slotLength"] * 1000  # a block is adopted within five slots of its slot's start
            # the counters were read a moment before the log: the node may have forged one more block since
            if not 0 <= len(forged) - total["forged"] <= 2 or total["leaderSlots"] < total["forged"]:
                failures.append(f"the forger's counters ({total['forged']} forged of {total['leaderSlots']} led slots) "
                                f"disagree with the log ({len(forged)} forged blocks)")
            if not 0 <= len(adopted) - total["adopted"] <= 2:
                failures.append(f"the forger's counters say {total['adopted']} adopted, the log {len(adopted)}")
            slowest = total["forgeLatencyMs"]["max"]
            if slowest is not None and slowest > budget:
                failures.append(f"a block took {slowest} ms from its slot's start to adoption (budget {budget:.0f} ms)")
            print(json.dumps({"forgerStats": forging_stats}), flush=True)
            failures += [f"metrics: {f}" for f in pool_metrics_failures(args.name, total, fetch)]
        if not forged:
            failures.append("the Scala node forged no block")
        if len(adopted) < len(forged) - len(lost):
            failures.append("a forged block is neither adopted nor lost")
        if forged and haskell_count == 0:
            failures.append("the Haskell ledger shows no block made by the Scala pool")
        if oracle["missed"]:
            failures.append(f"{len(oracle['missed'])} of {oracle['scheduled']} scheduled leader slots were neither "
                            f"forged nor taken by another block: {dict(list(oracle['missedReasons'].items())[:10])}")
        if oracle["absentEpochs"] or not oracle["scheduled"]:
            failures.append(f"no leadership schedule for epochs {oracle['absentEpochs']} "
                            f"({oracle['scheduled']} scheduled slots in the window)")
        if len(on_chain) < 0.8 * len(forged) - 1:
            failures.append(f"only {len(on_chain)} of {len(forged)} forged blocks are on the final chain")
        if upto_compared != haskell_count:
            failures.append(f"Scala has {upto_compared} of its blocks on the chain up to the last compared tip, "
                            f"the Haskell ledger counts {haskell_count}")
        if not txs:
            failures.append("no transaction was included in a Scala-forged block")
        if len(relative) < 2:
            failures.append("the KES key stayed in one period")
        if not (all(final.values()) and len({t["hash"] for t in final.values()}) == 1):
            failures.append(f"the Haskell pools disagree about the tip at the end: {summary['forks']['finalTips']}")
        if final.get(f"pool{observer}") and final[f"pool{observer}"]["hash"] not in chain:
            failures.append("the Haskell tip is not on the Scala node's chain")
        if fork_depth > k:
            failures.append(f"a rollback of {fork_depth} blocks (k = {k})")
        if worst > 3:
            failures.append(f"the Haskell pools disagreed about the tip for {worst} samples in a row")
        if len(matched) < args.min_comparisons or any(c.get("matched") is False or "error" in c for c in comparisons):
            failures.append(f"ledger comparisons: {len(matched)} matched of {len(comparisons)} "
                            f"(need {args.min_comparisons}, none may differ)")
        if counts.get("error"):
            failures.append(f"{counts['error']} error events in the Scala log")
        if counts.get("forgeFailed"):
            failures.append(f"{counts['forgeFailed']} forgeFailed events: "
                            f"{[e for e in events if e['event'] == 'forgeFailed'][:2]}")
        if other:
            failures.append(f"unexpected Scala output: {other[0][:200]}")
        clock = summary["slotClock"]
        if oracle["missed"] and clock["forgeLatencyMs"] is not None and abs(clock["forgeLatencyMs"]) > 500:
            failures.append(f"the Scala node's slot clock is {clock['forgeLatencyMs']} ms behind the genesis' while the "
                            f"reference's blocks arrive {clock['arrivalMs']} ms into their slots: its leader slots find the "
                            f"tip already past them (not a scheduling problem; see roadmap D8)")
        limit = getattr(args, "max_starvation_warnings", None)
        if limit is not None and summary["scheduling"]["cpuStarvationWarnings"] > limit:
            failures.append(f"{summary['scheduling']['cpuStarvationWarnings']} cats-effect CPU-starvation warnings "
                            f"(allowed {limit}) with the Scala node on {args.scala_cpus} CPUs")
    except SystemExit as e:
        failures.append(str(e))
    finally:
        if loader is not None:
            loader.terminate()
        docker("rm", "-f", f"{args.name}-scala", check=False)
        if scala is not None:
            scala.wait()
        summary["schedules"] = {str(e): v for e, v in schedules.items()}
        summary["failures"] = failures
        summary["passed"] = not failures
        summary["host"] = {"atStart": load_at_start, "atEnd": host_load()}
        # misses that are all "the forger was busy" on an oversubscribed host say nothing about the slot clock
        busy_only = (summary.get("oracle", {}).get("missed") and
                     set(summary["oracle"]["missedReasons"].values()) == {"the forger was busy with an earlier slot"} and
                     all("scheduled leader slots were neither" in f for f in failures))
        inconclusive = bool(failures) and busy_only and overloaded(load_at_start, summary["host"]["atEnd"])
        summary["verdict"] = ("inconclusive: host overloaded (load average above the CPU count) and the only misses are "
                              "slots the forger was busy for" if inconclusive else
                              "passed" if not failures else "failed")
        if root.exists():
            (root / "pool-summary.json").write_text(json.dumps(summary, indent=2))
        print(json.dumps(summary, indent=2))
        if not args.keep:
            docker("rm", "-f", ref(args.name), check=False)
            shutil.rmtree(root / "classes", ignore_errors=True)
            shutil.rmtree(root / "keys", ignore_errors=True)
    sys.exit(0 if not failures else 2 if inconclusive else 1)
