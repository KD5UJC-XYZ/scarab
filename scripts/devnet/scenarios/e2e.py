# SPDX-License-Identifier: Apache-2.0
"""The `e2e` scenario (and `capture-eras`): walk every era from Byron to Conway and assert the Scala node follows."""
import json
import pathlib
import shutil
import subprocess
import sys
import threading
import time
from .. import eraload
from ..cluster import down, freeze_classes, scala_container, up_eras
from ..compare import Comparisons, compare_era, comparison_table, judge_comparisons, lsq
from ..follower import follower_problem, start_follower
from ..load import start_conway_load
from ..util import ERAS, MAGIC, TAG_ERA, data_root, docker, read_log, ref


CONWAY_EPOCHS = 7  # Conway epochs the e2e run follows: bootstrap governance, the hard fork to 10, then full governance


def block_era(hex_line):
    """Era of a raw `[era tag, block]` line: the tag is the second byte (`82 <tag>`)."""
    return TAG_ERA[int(hex_line[2:4], 16)]


def capture_chain(args, root, out_name, extra=()):
    """Run CaptureMain over the whole chain of node 1 (up to 10 blocks behind its tip)."""
    ports = json.loads((root / "ports.json").read_text())
    classpath = freeze_classes(root)
    program = ["capture", "--peer", f"127.0.0.1:{ports['1']}", "--magic", str(MAGIC),
               "--blocks", "all", "--out", f"/run/out/{out_name}", *extra]
    command = scala_container(args, root, classpath, "lab.node.CaptureMain", program,
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
                   "overlay": 0, "leaderUnchecked": 0, "signatureVerified": 0, "signatureUnchecked": 0,
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
                r["signatureUnchecked"] += e["signatureUnchecked"]
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
    if byron["signatureUnchecked"]:
        failures.append(f"{byron['signatureUnchecked']} Byron headers had unchecked signatures")
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


def e2e(args):
    """Era-walking cluster with the Scala follower from the first block and era-appropriate load.

    The follower starts as soon as the nodes answer, so the Scala ledger tracks the reference
    through every era; during each epoch the load generator submits that era's transactions and the
    two ledgers are compared at several points (see `compare_pre_conway`, `compare_once`)."""
    root = data_root(args.name)
    if root.exists():
        if not (root / "e2e-summary.json").exists():
            raise SystemExit(f"{root} exists and is not an e2e leftover; run `devnet.py --name {args.name} down`")
        down(args, quiet=True)  # leftovers of an earlier e2e run
    follower, loader, worker, conway_load = None, None, None, None
    comparisons = None
    summary, failures = {}, []
    state = {"last_check": 0.0}
    try:
        comparisons = Comparisons(root)

        def nodes_up(_root):
            nonlocal follower
            follower = start_follower(args.name, root)

        def pools_ready(driver):
            nonlocal loader, worker
            if args.no_load:
                return
            loader = eraload.EraLoad(args.name, lambda: lsq(args.name), log=lambda m: print(json.dumps(m), flush=True))
            loader.ready.set()
            compare = (lambda era, label: None) if args.no_compare else \
                (lambda era, label: compare_era(args.name, era, label, comparisons))

            def conway_hook():
                nonlocal conway_load
                conway_load = start_conway_load(args, root, loader)
            worker = threading.Thread(target=loader.run, args=(compare, conway_hook), daemon=True)
            worker.start()

        def on_tick(_tip):
            if time.monotonic() - state["last_check"] > 4:
                state["last_check"] = time.monotonic()
                problem = follower_problem(root, follower)
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
        log = root / "follower.jsonl"
        deadline = time.monotonic() + args.timeout
        reason = "timeout"
        while time.monotonic() < deadline:
            time.sleep(2)
            events, other = read_log(log)
            applied = [e["epoch"] for e in events if e["event"] == "extended" and e["era"] == "Conway"]
            if any(e["event"] == "error" for e in events) or other:
                reason = "error"
                break
            if follower.poll() is not None:
                reason = "follower exited"
                break
            if applied and max(applied) >= target:
                # let the worker finish the comparisons of the epoch before the target one
                reason = "reached"
                break
        if loader is not None:
            loader.stop.set()
            worker.join(timeout=90)
        exit_code = follower.poll()
        events, other = read_log(log)
        summary, failures = summarize_e2e(events, other, expected, exit_code)
        summary["stoppedBecause"] = reason
        summary["targetConwayEpoch"] = target
        if reason != "reached":
            failures.append(f"stopped without reaching Conway epoch {target}: {reason}")
        if not args.no_compare:
            failures += judge_comparisons(comparisons, args.min_comparisons, loader is not None)
    except SystemExit as e:
        failures.append(str(e))
    finally:
        if loader is not None:
            loader.stop.set()
        if conway_load is not None:
            conway_load.terminate()
        if follower is not None and follower.poll() is None:
            docker("rm", "-f", f"{args.name}-scala", check=False)
            follower.wait()
        if comparisons is not None:
            summary["ledgerComparison"] = {"perEra": comparisons.per_era, "mismatches": comparisons.mismatches}
        if loader is not None:
            summary["load"] = loader.stats
        summary["failures"] = failures
        summary["passed"] = not failures
        root.mkdir(parents=True, exist_ok=True)
        (root / "e2e-summary.json").write_text(json.dumps(summary, indent=2))
        shutil.rmtree(root / "classes", ignore_errors=True)
        print(json.dumps(summary, indent=2))
        if comparisons is not None:
            print(comparison_table(comparisons))
        if not args.keep:
            for c in (f"{args.name}-scala", ref(args.name)):
                docker("rm", "-f", c, check=False)
    sys.exit(0 if not failures else 1)
