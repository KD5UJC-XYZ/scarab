# SPDX-License-Identifier: Apache-2.0
"""The `e2e` scenario (and `capture-eras`): walk every era from Byron to Conway and assert the Scala node follows."""
import json
import pathlib
import shutil
import subprocess
import sys
import threading
import time
import traceback
from .. import eraload, govextra, ntceras, protocolversions, protocolwalk
from ..cluster import down, freeze_classes, keep_native_evidence, scala_container, up_eras
from ..compare import PROJECTION_SCOPE, Comparisons, compare_era, comparison_table, judge_comparisons, lsq
from ..follower import follower_problem, start_follower, stop_failures, stop_followers
from ..load import start_conway_load
from ..util import (ERAS, MAGIC, SINGLE_FOLLOWER, TAG_ERA, data_root, docker, read_final_log, read_log, ref,
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


def summarize_e2e(events, other, expected, exit_code):
    """Assertions and the summary over the follower's JSON log; returns (summary, failures)."""
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
    return summary, failures


def run_verdict(failed, incomplete):
    """The run's verdict: `failed` when anything failed, else `incomplete` when a gate is incomplete (CNF-2: a
    missing implementation is not a pass), else `passed`."""
    return "failed" if failed else "incomplete" if incomplete else "passed"


def reached(events, target):
    """Whether the follower log `events` applied a block of Conway epoch `target` or later."""
    applied = [e["epoch"] for e in events if e["event"] == "extended" and e["era"] == "Conway"]
    return bool(applied) and max(applied) >= target


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
    two ledgers are compared at several points (see `compare_pre_conway`, `compare_once`). At each era step a thread
    of its own asks the same-point node-to-client battery of the follower's local socket and of the reference
    (`ntceras.EraBattery`)."""
    root = data_root(args.name)
    site = SINGLE_FOLLOWER
    if root.exists():
        if not (root / "e2e-summary.json").exists():
            raise SystemExit(f"{root} exists and is not an e2e leftover; run `devnet.py --name {args.name} down`")
        down(args, quiet=True)  # leftovers of an earlier e2e run
    follower = None  # the follower's `follow` process
    loader, worker, conway_load = None, None, None
    battery = None  # the node-to-client battery at each era step (`ntceras`), with the load and the comparisons
    late = []  # load threads that did not end within LOAD_JOIN_SECONDS of the request to stop
    tally = None
    summary, failures = {}, []
    incomplete = []  # the incomplete gates (CNF-2): no failure, and not a pass
    state = {"last_check": 0.0}

    try:
        tally = Comparisons(root, site)

        def nodes_up(_root):
            nonlocal follower
            follower = start_follower(args.name, root, oracle=args.oracle, scala_cpus=args.scala_cpus,
                                      consensus_mode=getattr(args, "consensus_mode", None), local_socket=True)

        def pools_ready(driver):
            nonlocal loader, worker, battery
            if args.no_load:
                return
            loader = eraload.EraLoad(args.name, lambda: lsq(args.name), log=lambda m: print(json.dumps(m), flush=True))
            loader.dynamic_forks = True  # the protocol walk drives the Conway versions
            loader.ready.set()
            if not args.no_compare:
                battery = ntceras.EraBattery(args.name, root, [site], loader.stop,
                                             log=lambda m: print(json.dumps(m), flush=True))
                battery.thread.start()

            def compare_and_ask(era, label):
                compare_era(args.name, era, label, [tally])
                if label == "after-batch":
                    battery.request(era)  # on the battery's own thread: the walk goes on
            compare = (lambda era, label: None) if args.no_compare else compare_and_ask

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
                problem = follower_problem(root, follower) if follower is not None else None
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
            events, other = read_log(site.log(root))
            if other or any(e["event"] == "error" for e in events):
                reason = "error"
                break
            if follower.poll() is not None:
                reason = "follower exited"
                break
            if reached(events, target):
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
            "worker": worker, "governance": loader.governance_thread, "extended": loader.ext_thread,
            "ntcEras": battery.thread if battery is not None else None}
        late = join_stopped(load_threads)
        blocked = {} if loader is None else blocked_stacks(load_threads)
        if conway_load is not None:
            conway_load.terminate()  # it submits to the node the stop below freezes
        # the follower is stopped on purpose before it is judged, so the verdict is over its whole log (an error or
        # an exit after the wait above counts) and its final tip is recorded (`stop_followers`)
        if args.oracle:
            from oracle import devnet as oracle_devnet
            premature = follower.poll()
            # the oracle removes the follower by force (`docker rm -f`) once its final tip is recorded: the status of
            # that end is not judged, the log it left is
            oracle_devnet.stop_follower(args.name, root)
            stops = {site: {"premature": premature, "endedBy": "itself" if premature is not None else "oracle",
                            "endedAt": "before the freeze" if premature is not None else None, "finalTip": None,
                            "exit": follower.wait()}}
            stop_problems = []
        else:
            stops, stop_problems = stop_followers(args.name, root, {site: follower})
        # the follower has ended: its log is complete, and a last line without its newline is a cut one
        events, other, unterminated = read_final_log(site.log(root))
        # an end before the stop is the follower's own failure, and so is an end on the stop with another status than
        # a SIGTERM gives
        summary, failures = summarize_e2e(events, other, expected, stops[site]["premature"])
        failures += stop_failures(stops).get(site, [])
        if unterminated is not None:
            failures.append(f"the follower's log ends in a line without its newline: {unterminated[:200]}")
        failures += stop_problems
        ended = {} if loader is None else dict(loader.ended)
        # a load thread that ended early is the cause of what the run then misses (comparisons, stages): first
        failures[:0] = [ended_failure(name, why) for name, why in sorted(ended.items())]
        if late:
            failures.append(f"load threads still running {LOAD_JOIN_SECONDS} s after they were asked to stop: {late}")
        summary["blockedThreads"] = blocked
        summary["endedThreads"] = ended
        summary["stoppedBecause"] = reason
        summary["cpuStarvationWarnings"] = starvation_warnings(site.log(root))
        summary["targetConwayEpoch"] = target
        if reason != "reached":
            failures.append(f"stopped without reaching Conway epoch {target}: {reason}")
        if not args.no_compare:
            failures += judge_comparisons(tally, args.min_comparisons, loader is not None)
            if battery is not None:
                failures += battery.failures(site)
                incomplete += battery.incomplete(site)
        if args.oracle:
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
                                  "extended": loader.ext_thread,
                                  "ntcEras": battery.thread if battery is not None else None})
            unreported = [name for name in still if name not in late]
            if unreported:
                failures.append(f"load threads still running {LOAD_JOIN_SECONDS} s after they were asked to stop: "
                                f"{unreported}")
        if conway_load is not None:
            conway_load.terminate()
        if follower is not None and follower.poll() is None:
            docker("rm", "-f", site.container(args.name), check=False)
            follower.wait()
        if tally is not None:
            summary["ledgerComparison"] = {"scope": PROJECTION_SCOPE, "perEra": tally.per_era,
                                           "mismatches": tally.mismatches,
                                           # complete same-point states but the UTxO (#433, #677)
                                           "wholeState": tally.whole}
        if battery is not None:
            summary["ntcEras"] = battery.report(site)
        if loader is not None:
            summary["load"] = loader.stats
            summary["loadEvidence"] = loader.evidence
            summary["legacyPlutus"] = loader.plutus_evidence
            summary["auxiliaryPlutus"] = loader.auxiliary_evidence
            summary["refusals"] = loader.refusal_evidence
            # whether each expected refusal was exactly the expected failure list, or held others too
            summary["refusalMatches"] = {"mustReject": eraload.match_counts(loader.must_reject_matches),
                                         "expectRejected": eraload.match_counts(loader.negative_matches)}
        # the follower's stall evidence (state events, fiber dumps) stays with the run's summary
        evidence = stall_evidence(root, site) if root.exists() else None
        if evidence is not None:
            summary["stallDiagnostics"] = evidence
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
        # an incomplete gate is not a pass (CNF-2): the run's verdict says so, and so does `passed`
        summary["incomplete"] = incomplete
        summary["verdict"] = run_verdict(failed, incomplete)
        summary["passed"] = summary["verdict"] == "passed"
        root.mkdir(parents=True, exist_ok=True)
        (root / "e2e-summary.json").write_text(json.dumps(summary, indent=2))
        shutil.rmtree(site.directory(root, "classes"), ignore_errors=True)
        print(json.dumps(summary, indent=2))
        if tally is not None:
            print(comparison_table(tally))
            if battery is not None:
                print(ntceras.table(battery.report(site)))
        if not args.keep:
            for c in (site.container(args.name), ref(args.name)):
                docker("rm", "-f", c, check=False)
        if summary["verdict"] == "incomplete":
            print(f"INCOMPLETE: no failure, and not a pass: {'; '.join(incomplete)}", flush=True)
    # a run whose only outcome is an incomplete gate exits 0, held by the battery's ratchet: an incomplete set that
    # grows, or a row treated as answered that falls back, is a failure (`ntceras.ratchet`), so only the expected
    # incomplete rows exit 0
    sys.exit(0 if not failures else 1)
