# SPDX-License-Identifier: Apache-2.0
"""The `e2e` scenario (and `capture-eras`): walk every era from Byron to Conway and assert the Scala node follows.

With more than one `--plutus-vm`, one follower runs per Plutus evaluator in the same cluster: each is judged as the
only one is, all are compared with the same reference blocks, and then with each other (`followers_cross_check`)."""
import json
import pathlib
import shutil
import subprocess
import sys
import threading
import time
import traceback
from .. import eraload, govextra, protocolversions, protocolwalk
from ..cluster import down, freeze_classes, keep_native_evidence, listening_ports, scala_container, up_eras
from ..compare import (PROJECTION_SCOPE, Comparisons, FollowerAgreement, agreement_failures, compare_era,
                       comparison_table, judge_comparisons, lsq)
from ..follower import follower_problem, start_follower
from ..load import start_conway_load
from ..util import (ERAS, MAGIC, SINGLE_FOLLOWER, TAG_ERA, data_root, docker, follower_plan, in_ref, read_log, ref,
                    stall_evidence, starvation_warnings)


EXTENDED_WAIT = 600  # seconds the run waits past its target epoch for extended stages that apply but are not done
CONWAY_EPOCHS = 7  # Conway epochs the e2e run follows at least: bootstrap governance, the hard forks, then full governance
# The run goes on past that target until the chain is at the highest protocol version the reference supports (the walk
# of protocolwalk.py) and the checks of the versions it passed are done, within the time the walk derives from the
# governance parameters.


def blocked_stacks(threads):
    """{name: stack} of the load threads still running after they were asked to stop: where a thread that stopped
    feeding the run (a comparison that took epochs, a hung query) was blocked. Empty when every thread ended."""
    frames = sys._current_frames()
    return {name: traceback.format_stack(frames[t.ident]) for name, t in threads.items()
            if t is not None and t.is_alive() and t.ident in frames}


LOAD_JOIN_SECONDS = 90  # how long the load threads get to end once they were asked to stop


def ended_failure(name, why):
    """The failure for a load thread that ended before the run asked it to stop (`EraLoad.ended`)."""
    tip = why.get("tip") or {}
    at = f" in {tip.get('era')} epoch {tip.get('epoch')} at slot {tip.get('slot')}" if tip else ""
    return f"the {name} load thread ended{at} before the run asked it to stop: {why['error']}"


def join_stopped(threads, timeout=LOAD_JOIN_SECONDS):
    """Wait up to `timeout` seconds in all for the load threads (name -> thread or None) to end; the names of the ones
    still running. The run summarises and removes the containers only after this, so no thread submits to a node
    that is being removed."""
    deadline = time.monotonic() + timeout
    for t in threads.values():
        if t is not None:
            t.join(max(deadline - time.monotonic(), 0))
    return sorted(name for name, t in threads.items() if t is not None and t.is_alive())


def block_era(hex_line):
    """Era of a raw `[era tag, block]` line: the tag is the second byte (`82 <tag>`)."""
    return TAG_ERA[int(hex_line[2:4], 16)]


def capture_chain(args, root, out_name, extra=()):
    """Run CaptureMain over the whole chain of node 1 (up to 10 blocks behind its tip)."""
    ports = json.loads((root / "ports.json").read_text())
    classpath = freeze_classes(root)
    program = ["capture", "--peer", f"127.0.0.1:{ports['1']}", "--magic", str(MAGIC),
               "--blocks", "all", "--out", f"/run/out/{out_name}", *extra]
    command = scala_container(args, root, classpath, "xyz.kd5ujc.scarab.node.tools.CaptureMain", program,
                              mounts=[f"{root}:/run/out"])
    r = subprocess.run(["docker", *command], text=True, capture_output=True, timeout=300)
    if r.returncode != 0:
        raise SystemExit(f"capture failed: {r.stderr.strip()[-600:]}")
    return [l for l in (root / out_name).read_text().split() if l]


def capture_eras(args):
    """Capture a running up-eras chain as fixtures: every block from Origin to a few blocks into
    Conway (header validation needs every VRF output for the nonces), plus the genesis and
    configuration files the node reads. The chain tail is cut so the fixture ends deep enough that
    no captured block can be rolled back."""
    root = data_root(args.name)
    lines = capture_chain(args, root, "blocks-all.hex")
    first_conway = next((i for i, l in enumerate(lines) if block_era(l) == "conway"), None)
    if first_conway is None:
        raise SystemExit("the captured chain has no Conway block yet; wait for up-eras to finish")
    keep = lines[: first_conway + args.conway_blocks]
    out = pathlib.Path(args.out)
    (out / "genesis").mkdir(parents=True, exist_ok=True)
    (out / "blocks.hex").write_text("\n".join(keep) + "\n")
    for f in ["shelley-genesis.json", "byron-genesis.json", "alonzo-genesis.json",
              "conway-genesis.json", "configuration.yaml"]:
        shutil.copyfile(root / "genesis" / f, out / "genesis" / f)
    eras = json.loads((root / "eras.json").read_text())
    shelley = json.loads((root / "genesis" / "shelley-genesis.json").read_text())
    counts = {e: sum(1 for l in keep if block_era(l) == e) for e in ERAS}
    manifest = {"capturedAt": time.strftime("%Y-%m-%d", time.gmtime()), "blocks": len(keep), "blocksPerEra": counts,
                "eras": eras["eras"], "protocolMajorByEpoch": eras["protocolMajorByEpoch"],
                "cluster": {k: shelley[k] for k in
                            ("epochLength", "activeSlotsCoeff", "securityParam", "slotLength", "networkMagic")}}
    (out / "eras.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print(json.dumps({"captured": len(keep), "blocksPerEra": counts, "out": str(out)}))


def summarize_e2e(events, other, expected, exit_code, plutus_vm=None):
    """Assertions and the summary over the follower's JSON log; returns (summary, failures). With `plutus_vm`
    (the `--plutus-vm` the follower was given) the evaluator its start logged (`plutusVm`) must be that one."""
    failures = []
    started = [e for e in events if e["event"] == "started"]
    transitions = []
    for e in events:
        if e["event"] != "eraTransition":
            continue
        t = {"from": e["from"], "to": e["to"], "epoch": e["epoch"], "slot": e["slot"], "block": e["block"]["blockNo"]}
        if transitions and transitions[-1]["to"] == t["to"] and any(
                x["event"] == "rolledBack" and transitions[-1]["slot"] <= x["from"]["slot"] for x in events):
            transitions[-1] = t  # the first block of the era was rolled back and the era entered again
        else:
            transitions.append(t)
    per_era = {e: {"blocks": 0, "firstEpoch": None, "lastEpoch": None, "headers": 0, "leaderThreshold": 0,
                   "overlay": 0, "leaderUnchecked": 0, "signatureVerified": 0, "offSchedule": 0, "transactions": 0,
                   "boundary": 0} for e in ERAS}
    for e in events:
        if e["event"] == "extended":
            r = per_era[e["era"].lower()]
            r["blocks"] += 1
            r["firstEpoch"] = e["epoch"] if r["firstEpoch"] is None else r["firstEpoch"]
            r["lastEpoch"] = e["epoch"]
        elif e["event"] == "epochValidated":
            r = per_era[e["era"].lower()]
            r["headers"] += e["headers"]
            if e["era"] == "Byron":
                r["boundary"] += e["boundary"]
                r["signatureVerified"] += e["signatureVerified"]
                r["offSchedule"] += e["offSchedule"]
                r["transactions"] += e["transactions"]
            else:
                r["leaderThreshold"] += e["thresholdChecked"]
                r["overlay"] += e["overlay"]
                r["leaderUnchecked"] += e["leaderUnchecked"]
    errors = [e for e in events if e["event"] == "error"]
    validation = [e for e in errors if e.get("kind") == "ValidationError"]
    fatal = [e for e in errors if e.get("kind") != "ValidationError"]
    summary = {
        "transitions": transitions,
        "expectedTransitions": expected,
        "blocksPerEra": per_era,
        "blocksApplied": sum(r["blocks"] for r in per_era.values()),
        "lastEpoch": max((e["epoch"] for e in events if e["event"] == "extended"), default=None),
        "validationErrors": validation,
        "fatalErrors": fatal,
        "unparsedOutput": other[:20],
        "rollbacks": sum(1 for e in events if e["event"] == "rolledBack"),
        "reconnects": sum(1 for e in events if e["event"] == "reconnecting"),
        "followerExit": exit_code,
        "ledgerComparison": {"status": "not run"},
    }
    if not started:
        failures.append("the follower never started")
    if [t["to"].lower() for t in transitions] != [x["era"] for x in expected]:
        failures.append(f"era transitions {[t['to'] for t in transitions]} differ from "
                        f"{[x['era'] for x in expected]}")
    else:
        for t, x in zip(transitions, expected):
            if t["epoch"] != x["epoch"]:
                failures.append(f"{x['era']} began in epoch {t['epoch']}, expected {x['epoch']}")
    if transitions and transitions[0]["from"] is not None:
        failures.append("the first transition must start from no era")
    for t, prev in zip(transitions[1:], transitions):
        if t["from"] is None or t["from"].lower() != prev["to"].lower():
            failures.append(f"transition to {t['to']} does not follow {prev['to']}")
    for era in ERAS:
        if per_era[era]["blocks"] == 0:
            failures.append(f"no {era} block was applied")
    byron = per_era["byron"]
    if byron["offSchedule"]:
        failures.append(f"{byron['offSchedule']} Byron blocks were not made by the round-robin leader of their slot")
    if byron["boundary"] == 0 or byron["signatureVerified"] == 0:
        failures.append("Byron epoch 0 was not validated (no boundary block or no verified signature)")
    if validation:
        failures.append(f"{len(validation)} validation errors: {validation[0]['message']}")
    if fatal:
        failures.append(f"{len(fatal)} fatal errors: {fatal[0]['message']}")
    if other:
        failures.append(f"unexpected non-JSON output: {other[0][:200]}")
    if exit_code not in (None, 0):
        failures.append(f"the follower exited with {exit_code}")
    if plutus_vm is not None:
        chosen = [e.get("vm") for e in events if e["event"] == "plutusVm"]
        summary["plutusVm"] = chosen
        if chosen != [plutus_vm]:
            failures.append(f"the follower logged the Plutus evaluators {chosen} at its start, not [{plutus_vm!r}]")
    return summary, failures


def reached(events, target):
    """Whether the follower log `events` applied a block of Conway epoch `target` or later."""
    applied = [e["epoch"] for e in events if e["event"] == "extended" and e["era"] == "Conway"]
    return bool(applied) and max(applied) >= target


def stop_reason(kind, sites, keyed):
    """Why the wait ended (`stoppedBecause`): `kind`, and with several followers the ones it is about."""
    return kind if not keyed else f"{kind}: {', '.join(site.key for site in sites)}"


def logged_chain(text, byron_epoch_slots):
    """The chain a follower ended with, rebuilt from its log `text` by the oracle's rules (`finality.FollowerChain`:
    `extended` and `rolledBack` lines, sessions from Origin). A last line without its newline was still being
    written when the follower stopped and is left out, as `read_log` leaves it out; a line that is not a JSON
    object is no chain event (the run fails on unparsed output already, `summarize_e2e`). Raises
    `finality.ChainRebuildError` when the lines do not describe one chain."""
    from oracle import finality
    numbered = []
    for n, line in enumerate(text.split("\n")[:-1], start=1):
        if not line.startswith("{"):
            continue
        try:
            event = json.loads(line)
        except json.JSONDecodeError:
            continue
        if isinstance(event, dict):
            numbered.append((n, event))
    return finality.FollowerChain.from_events(numbered, byron_epoch_slots)


def block_json(block):
    return None if block is None else {"slot": block.slot, "blockNo": block.block_no, "hash": block.hash}


def chain_agreement(chains, k):
    """Whether the followers' final chains (key -> `finality.FollowerChain`) agree: (report, failures).

    The followers stop a moment apart and a slot battle at the tip is normal, so their tips need not be equal.
    Every chain runs from genesis and a header hash fixes the chain before it, so two chains agree when one of them
    has at most `k` blocks after their last common block: those are its volatile part, which a node may still roll
    back, and its immutable tip is then on the other chain (the rule `finality.final_chains_agree` judges the
    oracle's chains by). The first follower's chain is set against each other one."""
    keys = list(chains)
    report = {"securityParam": k,
              "tips": {key: dict(block_json(c.tip) or {}, blocks=len(c.blocks)) for key, c in chains.items()},
              "pairs": []}
    failures = [f"the {key} follower's log holds no chain" for key, c in chains.items() if not c.blocks]
    first = chains[keys[0]]
    for key in keys[1:]:
        other = chains[key]
        common = next((i for i, (a, b) in enumerate(zip(first.blocks, other.blocks)) if a.hash != b.hash),
                      min(len(first.blocks), len(other.blocks)))
        beyond = {keys[0]: len(first.blocks) - common, key: len(other.blocks) - common}
        agreed = min(beyond.values()) <= k
        report["pairs"].append({"followers": [keys[0], key], "commonBlocks": common,
                                "lastCommon": block_json(first.blocks[common - 1]) if common else None,
                                "beyondCommon": beyond, "agreed": agreed})
        if not agreed:
            failures.append(f"the {keys[0]} and {key} followers' final chains diverge: {beyond[keys[0]]} and "
                            f"{beyond[key]} blocks after their last common block "
                            f"{block_json(first.blocks[common - 1]) if common else 'Origin'}, more than k = {k}")
    return report, failures


def followers_cross_check(root, sites, entries, agreement):
    """The followers of one run against each other, once they have stopped: (report, failures).

    The report repeats, per follower, whether it reached the target epoch and how many validation and fatal errors
    it logged (each is a failure of that follower's own already), and holds the tally of their projected states at
    the reference blocks where all were compared (`FollowerAgreement`, judged with the comparisons). The check of
    its own is that their final chains, rebuilt from their logs, agree (`chain_agreement`). Never raises: it runs in
    the cleanup of the run."""
    keys = [site.key for site in sites]
    report = {"followers": keys,
              "reachedTarget": {key: entries.get(key, {}).get("reachedTarget") for key in keys},
              "validationErrors": {key: len(entries.get(key, {}).get("validationErrors", [])) for key in keys},
              "fatalErrors": {key: len(entries.get(key, {}).get("fatalErrors", [])) for key in keys},
              "projected": agreement.report() if agreement is not None else None}
    failures, chains = [], {}
    try:
        from oracle.devnet import byron_epoch_slots, security_param
        k, byron = security_param(root), byron_epoch_slots(root)
    except Exception as e:  # the cleanup of a run that may have failed early: never replace its failure
        report["finalChain"] = {"error": repr(e)[:400]}
        failures.append(f"the followers' final chains cannot be judged without the genesis: {e!r}")
        report["failures"] = failures
        return report, failures
    for site in sites:
        try:
            chains[site.key] = logged_chain(site.log(root).read_text(errors="replace"), byron)
        except Exception as e:  # a log that is not one chain is this check's failure, never the end of the cleanup
            failures.append(f"the {site.key} follower's final chain cannot be rebuilt from its log: {e}")
    if len(chains) == len(sites):
        report["finalChain"], chain_failures = chain_agreement(chains, k)
        failures += chain_failures
    else:
        report["finalChain"] = {"error": "not every follower's chain could be rebuilt"}
    report["failures"] = failures
    return report, failures


def reference_evidence(name, root):
    """`summary["referenceEvidence"]`: what of the reference cluster's native evidence the run kept before its
    container was removed. The capture is best effort and cannot change the run's verdict: a capture that fails
    is reported next to the run's own failures, which it neither replaces nor adds to (#293)."""
    try:
        return keep_native_evidence(name, root)
    except Exception as error:  # this runs in the cleanup of a failed run: the capture must not replace its failure
        return {"kept": [], "notKept": {"all": repr(error)}}


def e2e(args):
    """Era-walking cluster with the Scala follower from the first block and era-appropriate load.

    The follower starts as soon as the nodes answer, so the Scala ledger tracks the reference
    through every era; during each epoch the load generator submits that era's transactions and the
    two ledgers are compared at several points (see `compare_pre_conway`, `compare_once`). With several
    `--plutus-vm` values one follower runs per evaluator (`util.follower_plan`): each is judged as the only one
    is, every comparison reads the reference once and compares all of them with that block, and at the end they
    are judged against each other (`followers_cross_check`)."""
    root = data_root(args.name)
    plan = follower_plan(getattr(args, "plutus_vm", None))
    sites = [site for site, _ in plan]
    keyed = len(plan) > 1
    if keyed and args.oracle:
        raise SystemExit("--oracle compares the state digests of one follower: give at most one --plutus-vm")
    if root.exists():
        if not (root / "e2e-summary.json").exists():
            raise SystemExit(f"{root} exists and is not an e2e leftover; run `devnet.py --name {args.name} down`")
        down(args, quiet=True)  # leftovers of an earlier e2e run
    followers = {site: None for site in sites}  # the `follow` process of each follower
    loader, worker, conway_load = None, None, None
    late = []  # load threads that did not end within LOAD_JOIN_SECONDS of the request to stop
    tallies, agreement = {}, None
    summary, failures = {}, []
    state = {"last_check": 0.0}

    def entry(site):
        """Where the summary keeps what is a follower's own: the summary itself for a run's only follower, else
        the follower's entry under `followers`."""
        return summary if not keyed else summary.setdefault("followers", {}).setdefault(site.key, {})

    def tagged(site, texts):
        """A follower's failures, named after it when the run has several."""
        return list(texts) if not keyed else [f"{site.role}: {text}" for text in texts]

    try:
        tallies = {site: Comparisons(root, site) for site in sites}
        agreement = FollowerAgreement(root, sites) if keyed else None

        def nodes_up(_root):
            if keyed:
                # every follower binds its ports in the reference container's network namespace; a JVM's listener
                # is an IPv6 socket, listed in tcp6 even when bound to 127.0.0.1
                taken = listening_ports(in_ref(args.name, "cat /proc/net/tcp; cat /proc/net/tcp6 2>/dev/null; true").stdout)
                clash = sorted(p for site in sites for p in (site.query_port, site.metrics_port) if p in taken)
                if clash:
                    raise SystemExit(f"the followers' ports {clash} are in use in the reference container")
            for site, vm in plan:
                followers[site] = start_follower(args.name, root, oracle=args.oracle, scala_cpus=args.scala_cpus,
                                                 consensus_mode=getattr(args, "consensus_mode", None),
                                                 local_socket=True, plutus_vm=vm, site=site)

        def pools_ready(driver):
            nonlocal loader, worker
            if args.no_load:
                return
            loader = eraload.EraLoad(args.name, lambda: lsq(args.name), log=lambda m: print(json.dumps(m), flush=True))
            loader.dynamic_forks = True  # the protocol walk drives the Conway versions
            loader.ready.set()
            compare =(lambda era, label: None) if args.no_compare else \
                (lambda era, label: compare_era(args.name, era, label, list(tallies.values()), agreement))

            def conway_hook():
                nonlocal conway_load
                conway_load = start_conway_load(args, root, loader)
                # the base governance rounds keep the chain's pace however long a comparison of the worker takes:
                # the protocol walk's hops from version 10 wait for their DRep delegations and committee (#451)
                loader.start_governance()
                loader.start_extended(full=False)  # script credentials, pool reward accounts, B5 refusals
            worker = threading.Thread(target=loader.run, args=(compare, conway_hook), daemon=True)
            worker.start()

        def on_tick(_tip):
            if time.monotonic() - state["last_check"] > 4:
                state["last_check"] = time.monotonic()
                for site, follower in followers.items():
                    problem = follower_problem(root, follower, site) if follower is not None else None
                    if problem:
                        raise SystemExit(problem)

        up_eras(args, on_nodes_up=nodes_up, on_pools_ready=pools_ready, on_tick=on_tick)
        eras = json.loads((root / "eras.json").read_text())
        config = json.loads((root / "genesis" / "configuration.yaml").read_text())
        expected = []
        for t in eras["eras"]:
            epoch = 0 if t["era"] == "byron" else config[f"Test{t['era'].capitalize()}HardForkAtEpoch"]
            if epoch != t["epoch"]:
                raise SystemExit(f"eras.json puts {t['era']} at epoch {t['epoch']}, configuration at {epoch}")
            expected.append({"era": t["era"], "epoch": epoch})
        target = args.until_epoch if args.until_epoch is not None else expected[-1]["epoch"] + CONWAY_EPOCHS
        deadline = time.monotonic() + args.timeout
        reason = "timeout"
        extended_wait = None  # when the target epoch was reached with extended stages still running
        while time.monotonic() < deadline:
            time.sleep(2)
            logs = {site: read_log(site.log(root)) for site in sites}
            erred = [site for site, (events, other) in logs.items()
                     if other or any(e["event"] == "error" for e in events)]
            if erred:
                reason = stop_reason("error", erred, keyed)
                break
            exited = [site for site in sites if followers[site].poll() is not None]
            if exited:
                reason = stop_reason("follower exited", exited, keyed)
                break
            if all(reached(events, target) for events, _ in logs.values()):
                ext = loader.ext if loader is not None else None
                # the chain is still on its way to the reference's highest version: the walk has a time of its own
                if ext is not None and ext.walk is not None and ext.walk.pending() and not ext.walk.overdue():
                    continue
                # the extended stages that apply (a protocol version reached late) get a time of their own
                if ext is not None and govextra.extended_pending(ext):
                    extended_wait = extended_wait or time.monotonic()
                    if time.monotonic() - extended_wait < EXTENDED_WAIT:
                        continue
                # let the worker finish the comparisons of the epoch before the target one
                reason = "reached"
                break
        if loader is not None:
            loader.stop.set()
        load_threads = {} if loader is None else {
            "worker": worker, "governance": loader.governance_thread, "extended": loader.ext_thread}
        late = join_stopped(load_threads)
        blocked = {} if loader is None else blocked_stacks(load_threads)
        judged = {}
        for site, vm in plan:
            exit_code = followers[site].poll()
            events, other = read_log(site.log(root))
            # the evaluator each follower logged is judged when the run has several: a run's only follower keeps
            # the verdicts and summary it always had, with one `--plutus-vm` or none
            own, own_failures = summarize_e2e(events, other, expected, exit_code, plutus_vm=vm if keyed else None)
            if keyed:
                own["reachedTarget"] = reached(events, target)
            judged[site] = (own, own_failures)
        if keyed:
            summary = {"followers": {site.key: own for site, (own, _) in judged.items()}}
            failures = [text for site, (_, own_failures) in judged.items() for text in tagged(site, own_failures)]
        else:
            summary, failures = judged[SINGLE_FOLLOWER]
        ended = {} if loader is None else dict(loader.ended)
        # a load thread that ended early is the cause of what the run then misses (comparisons, stages): first
        failures[:0] = [ended_failure(name, why) for name, why in sorted(ended.items())]
        if late:
            failures.append(f"load threads still running {LOAD_JOIN_SECONDS} s after they were asked to stop: {late}")
        summary["blockedThreads"] = blocked
        summary["endedThreads"] = ended
        summary["stoppedBecause"] = reason
        for site in sites:
            entry(site)["cpuStarvationWarnings"] = starvation_warnings(site.log(root))
        summary["targetConwayEpoch"] = target
        if reason != "reached":
            failures.append(f"stopped without reaching Conway epoch {target}: {reason}")
        if not args.no_compare:
            for site, tally in tallies.items():
                failures += tagged(site, judge_comparisons(tally, args.min_comparisons, loader is not None))
            if agreement is not None:
                failures += agreement_failures(agreement.per_era)
        if args.oracle:
            from oracle import devnet as oracle_devnet
            oracle_devnet.stop_follower(args.name, root)  # its final tip recorded; no digests during the comparison
            followers[SINGLE_FOLLOWER].wait()
            summary["oracle"], oracle_failures = oracle_devnet.run(
                args.name, root, log=lambda m: print(json.dumps(m), flush=True))
            summary["oracle"].pop("results", None)  # the per-epoch rows are in oracle-summary.json
            failures += oracle_failures
        if loader is not None:
            from ..eraplutus import coverage_failures
            failures += coverage_failures(loader.plutus_evidence)
            from ..eraauxiliary import coverage_failures as auxiliary_coverage_failures
            failures += auxiliary_coverage_failures(loader.auxiliary_evidence)
        if loader is not None and loader.ext is not None:
            extended_failures, not_applicable = govextra.extended_outcome(
                loader.ext.results, loader.ext.done, loader.ext.max_protocol, loader.stats.get("negativeUnexpected", 0))
            failures += extended_failures
            summary["extended"] = {"done": sorted(loader.ext.done), "results": loader.ext.results,
                                   "maxProtocol": loader.ext.max_protocol, "notApplicable": not_applicable,
                                   "versionChecks": protocolversions.version_check_report(
                                       loader.ext.results, loader.ext.done, loader.ext.max_protocol)}
            if loader.ext.walk is not None:
                summary["protocol"] = loader.ext.walk.summary()
                failures += protocolwalk.walk_failures(loader.ext.walk)
        elif loader is not None:
            failures.append("the extended governance stages never started")
        if loader is not None and loader.stats.get("unexpected"):
            failures.append(f"{loader.stats['unexpected']} submissions that must be rejected were accepted or "
                            f"rejected for another reason: {[e for e in loader.evidence if e['verdict'] != 'expected'][:3]}")
    except SystemExit as e:
        failures.append(str(e))
    finally:
        if loader is not None:
            loader.stop.set()
            # an exceptional exit reaches here with the threads running: end them before the containers go
            still = join_stopped({"worker": worker, "governance": loader.governance_thread,
                                  "extended": loader.ext_thread})
            unreported = [name for name in still if name not in late]
            if unreported:
                failures.append(f"load threads still running {LOAD_JOIN_SECONDS} s after they were asked to stop: "
                                f"{unreported}")
        if conway_load is not None:
            conway_load.terminate()
        for site, follower in followers.items():
            if follower is not None and follower.poll() is None:
                docker("rm", "-f", site.container(args.name), check=False)
                follower.wait()
        for site, tally in tallies.items():
            entry(site)["ledgerComparison"] = {"scope": PROJECTION_SCOPE, "perEra": tally.per_era,
                                               "mismatches": tally.mismatches,
                                               # complete same-point states but the UTxO (#433, #677)
                                               "wholeState": tally.whole}
        if keyed:
            # the followers have stopped: their logs hold their final chains
            if all(follower is not None for follower in followers.values()):
                summary["crossCheck"], cross_failures = followers_cross_check(
                    root, sites, summary.get("followers", {}), agreement)
                failures += cross_failures
            else:
                summary["crossCheck"] = {"skipped": "not every follower was started"}
        if loader is not None:
            summary["load"] = loader.stats
            summary["loadEvidence"] = loader.evidence
            summary["legacyPlutus"] = loader.plutus_evidence
            summary["auxiliaryPlutus"] = loader.auxiliary_evidence
            summary["refusals"] = loader.refusal_evidence
            # whether each expected refusal was exactly the expected failure list, or held others too
            summary["refusalMatches"] = {"mustReject": eraload.match_counts(loader.must_reject_matches),
                                         "expectRejected": eraload.match_counts(loader.negative_matches)}
        # each follower's stall evidence (state events, fiber dumps) stays with the run's summary
        for site in sites:
            evidence = stall_evidence(root, site) if root.exists() else None
            if evidence is not None:
                entry(site)["stallDiagnostics"] = evidence
        # the reference container and the tmpfs its logs live on go with the run: keep the native side's own
        # evidence first, before the summary is written and that container removed (the Scala nodes' containers,
        # removed higher up in this `finally` when they were still running). A run that passed keeps none; anything
        # that failed, or ended with an exception no failure recorded, keeps what can still be read.
        if failures or sys.exc_info()[0] is not None:
            summary["referenceEvidence"] = reference_evidence(args.name, root)
        # a run that ends by an exception with nothing in `failures` has failed too: the summary must not say
        # `passed` beside a kept evidence block and a traceback (#293)
        failed = bool(failures) or sys.exc_info()[0] is not None
        summary["failures"] = failures
        summary["passed"] = not failed
        root.mkdir(parents=True, exist_ok=True)
        (root / "e2e-summary.json").write_text(json.dumps(summary, indent=2))
        for site in sites:
            shutil.rmtree(site.directory(root, "classes"), ignore_errors=True)
        print(json.dumps(summary, indent=2))
        for site, tally in tallies.items():
            if keyed:
                print(f"{site.role}:")
            print(comparison_table(tally))
        if not args.keep:
            for c in (*(site.container(args.name) for site in sites), ref(args.name)):
                docker("rm", "-f", c, check=False)
    sys.exit(0 if not failures else 1)
