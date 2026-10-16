# SPDX-License-Identifier: Apache-2.0
"""The `soak` scenario: follow, load and periodically compare for a fixed time."""
import json
import os
import subprocess
import sys
import time
from ..cluster import up
from ..compare import compare_once
from ..util import ENTRY, data_root, docker


# stages of govextra.py that a `soak --governance-extended` of 900 s must complete
EXTENDED_STAGES = ["xfund", "xrewards", "xscriptreg", "xpc1", "xscriptvote", "xscriptwithdraw", "xvrf", "xminsize",
                   "xstuck"]


def extended_summary(root):
    """What the extended governance scenarios did, from govload.log: stages completed, transactions the
    reference refused as expected (and any it did not), and what its ratification enacted per epoch."""
    events = []
    for line in (root / "govload.log").read_text().splitlines():
        if line.startswith("{"):
            try:
                events.append(json.loads(line))
            except json.JSONDecodeError:
                pass
    done = [e["stage"] for e in events if e.get("extended") and e.get("ok")]
    negatives = [e for e in events if "refusedAsExpected" in e]
    return {
        "stagesDone": done,
        "missingStages": [s for s in EXTENDED_STAGES if s not in done],
        "refusedAsExpected": sum(1 for e in negatives if e["refusedAsExpected"]),
        "refusedUnexpectedly": sum(1 for e in negatives if not e["refusedAsExpected"]),
        "counted": {e["counted"]: e["total"] for e in events if "counted" in e},
        "refused": sorted(e["negative"].rsplit("-", 1)[0] for e in negatives if e["refusedAsExpected"]),
        "enactedByEpoch": {e["trace"]: e["enacted"] for e in events if "trace" in e and e["enacted"]},
        "expiredByEpoch": {e["trace"]: e["expired"] for e in events if "trace" in e and e["expired"]},
    }


def mirror_summary(log):
    """What the follower's `--mirror-check` found: the checks that held, the eras and gaps they saw, and the
    ones that did not hold or were refused."""
    checked = [e for e in log if e.get("event") == "mirrorChecked"]
    failed = [e for e in log if e.get("event") == "mirrorCheckFailed"]
    return {
        "checked": len(checked),
        "ok": sum(1 for e in checked if e["ok"]),
        "failed": failed,
        "notOk": [e for e in checked if not e["ok"]],
        "eras": sorted({e["era"] for e in checked}),
        "epochs": sorted({e["epoch"] for e in checked}),
        "gaps": sorted({g for e in checked for g in e["gaps"]}),
        "maxMillis": max((e["millis"] for e in checked), default=0),
    }


def differential_summary(log):
    """What the follower's `--rules differential` found: the comparisons made, what the conversion cost,
    and any divergence."""
    sampled = [e for e in log if e.get("event") == "differentialSampled"]
    diverged = [e for e in log if e.get("event") in ("rulesDiverged", "rulesHalted")]
    return {
        "sampled": len(sampled),
        "diverged": diverged,
        "eras": sorted({e["era"] for e in sampled}),
        "maxToMirrorMillis": max((e["toMirrorMillis"] for e in sampled), default=0),
    }


def soak(args):
    """Fresh cluster, Scala follower, transaction load and periodic comparison."""
    root = data_root(args.name)
    up(args)
    follower = subprocess.Popen([sys.executable, str(ENTRY), "--name", args.name, "follow",
                                 *(["--dump-state"] if getattr(args, "oracle", False) else []),
                                 *(["--mirror-check"] if getattr(args, "mirror_check", False) else [])],
                                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    loader = subprocess.Popen([sys.executable, str(ENTRY), "--name", args.name, "load", "--seed", str(args.seed)],
                              stdout=(root / "load.log").open("w"), stderr=subprocess.STDOUT)
    governance = subprocess.Popen([sys.executable, str(ENTRY), "--name", args.name, "govload",
                                   *(["--extended"] if getattr(args, "governance_extended", False) else [])],
                                  stdout=(root / "govload.log").open("w"), stderr=subprocess.STDOUT)
    results = []
    deadline = time.monotonic() + args.seconds
    try:
        while time.monotonic() < deadline:
            time.sleep(args.every)
            if follower.poll() is not None:
                results.append({"error": f"follower exited with {follower.returncode}"})
                break
            r = compare_once(args.name)
            if r is not None:
                results.append(r)
                print(json.dumps({k: r.get(k) for k in ("tip", "matched", "differenceCount", "error")}), flush=True)
                with (root / "compare.jsonl").open("a") as f:
                    f.write(json.dumps(r) + "\n")
    finally:
        loader.terminate()
        loader.wait()
        governance.terminate()
        governance.wait()
        follower_alive = follower.poll() is None
        docker("rm", "-f", f"{args.name}-scala", check=False)
        follower.wait()
    log = [json.loads(l) for l in (root / "follower.jsonl").read_text().splitlines() if l.startswith("{")]
    summary = {
        "comparisons": len([r for r in results if "matched" in r]),
        "matched": len([r for r in results if r.get("matched")]),
        "firstMismatch": next((r for r in results if r.get("matched") is False or "error" in r), None),
        "followerAliveAtEnd": follower_alive,
        "epochsEntered": [e["epoch"] for e in log if e.get("event") == "epoch"],
        "blocksApplied": sum(1 for e in log if e.get("event") == "extended"),
        "rollbacks": [e for e in log if e.get("event") == "rolledBack"],
        "reconnects": sum(1 for e in log if e.get("event") == "reconnecting"),
        "errors": [e for e in log if e.get("event") == "error"],
        "load": (root / "load.log").read_text().strip().splitlines()[-1:],
    }
    mirror = mirror_summary(log) if getattr(args, "mirror_check", False) else None
    if mirror is not None:
        summary["mirrorCheck"] = mirror
    rules = getattr(args, "rules", None) or os.environ.get("SCARAB_RULES")
    differential = differential_summary(log) if rules == "differential" else None
    if differential is not None:
        summary["differential"] = differential
    extended = extended_summary(root) if getattr(args, "governance_extended", False) else None
    if extended is not None:
        summary["governanceExtended"] = extended
    failures = []
    if getattr(args, "oracle", False):
        from oracle import devnet as oracle_devnet
        summary["oracle"], oracle_failures = oracle_devnet.run(
            args.name, root, log=lambda m: print(json.dumps(m), flush=True))
        summary["oracle"].pop("results", None)  # the per-epoch rows are in oracle-summary.json
        failures += oracle_failures
    (root / "summary.json").write_text(json.dumps(summary, indent=2))
    print(json.dumps(summary, indent=2))
    if not summary["comparisons"]:
        failures.append("no ledger comparison completed")
    if summary["matched"] != summary["comparisons"]:
        failures.append("ledger mismatch or comparison error")
    if summary["errors"]:
        failures.append("follower logged errors")
    if not follower_alive:
        failures.append("follower exited before the end")
    if mirror is not None and (not mirror["checked"] or mirror["failed"] or mirror["notOk"]):
        failures.append(f"mirror check: {mirror['checked']} checked, {len(mirror['failed'])} refused, "
                        f"{len(mirror['notOk'])} not ok")
    if differential is not None and (not differential["sampled"] or differential["diverged"]):
        failures.append(f"differential rules: {differential['sampled']} compared, "
                        f"{len(differential['diverged'])} diverged")
    if extended is not None:
        if extended["refusedUnexpectedly"]:
            failures.append("a transaction the reference was expected to refuse was not refused as expected")
        if extended["missingStages"]:
            failures.append(f"extended governance stages not reached: {extended['missingStages']}")
    if failures:
        print(json.dumps({"failed": failures}), flush=True)
    sys.exit(1 if failures else 0)
