# SPDX-License-Identifier: Apache-2.0
"""The `soak` scenario: follow, load and periodically compare for a fixed time."""
import json
import os
import subprocess
import sys
import time
from .. import metrics as node_metrics
from ..cluster import up
from ..compare import compare_or_error
from ..consensus import arguments
from ..follower import own_build
from ..util import ENTRY, QUERY_PORT, data_root, docker, read_log


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


def scrape_metrics(name, root):
    """The Prometheus endpoint, at the end of the run while the follower is still up: on the query server and on the
    listener of its own (`--metrics-listen`), each checked against the follower log (series present, values
    consistent with the log, no validation errors)."""
    events = lambda: read_log(root / "follower.jsonl")[0]
    out = {}
    for label, port in (("queryServer", QUERY_PORT), ("listener", node_metrics.METRICS_PORT)):
        failures, samples, _ = node_metrics.scrape_and_check(name, port, events)
        out[label] = {"port": port, "series": len(samples), "failures": failures,
                      "blockNum": node_metrics.value(samples, "cardano_node_metrics_blockNum_int"),
                      "blocksApplied": node_metrics.total(samples, "scarab_blocks_applied_total"),
                      "validationErrors": node_metrics.total(samples, "scarab_validation_errors_total")}
    return out


def soak(args):
    """Fresh cluster, Scala follower, transaction load and periodic comparison."""
    root = data_root(args.name)
    up(args)
    follower = subprocess.Popen([sys.executable, str(ENTRY), "--name", args.name, "follow",
                                 *arguments(args),
                                 *(["--dump-state"] if getattr(args, "oracle", False) else [])],
                                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    loader = subprocess.Popen([sys.executable, str(ENTRY), "--name", args.name, "load", "--seed", str(args.seed)],
                              stdout=(root / "load.log").open("w"), stderr=subprocess.STDOUT)
    governance = subprocess.Popen([sys.executable, str(ENTRY), "--name", args.name, "govload",
                                   *(["--extended"] if getattr(args, "governance_extended", False) else [])],
                                  stdout=(root / "govload.log").open("w"), stderr=subprocess.STDOUT)
    results = []
    scraped = {}
    deadline = time.monotonic() + args.seconds
    try:
        while time.monotonic() < deadline:
            time.sleep(args.every)
            if follower.poll() is not None:
                results.append({"error": f"follower exited with {follower.returncode}"})
                break
            r = compare_or_error(args.name)
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
        if follower_alive and own_build(args):
            scraped = scrape_metrics(args.name, root)
        if getattr(args, "oracle", False):
            from oracle import devnet as oracle_devnet
            oracle_devnet.stop_follower(args.name, root)  # its final tip recorded for the oracle's check of its log
        else:
            docker("rm", "-f", f"{args.name}-scala", check=False)
        follower.wait()
    log, other = read_log(root / "follower.jsonl")
    summary = {
        "comparisons": len([r for r in results if "matched" in r]),
        "matched": len([r for r in results if r.get("matched")]),
        "comparisonErrors": len([r for r in results if "error" in r]),
        "firstMismatch": next((r for r in results if r.get("matched") is False or "error" in r), None),
        "followerAliveAtEnd": follower_alive,
        "epochsEntered": [e["epoch"] for e in log if e.get("event") == "epoch"],
        "blocksApplied": sum(1 for e in log if e.get("event") == "extended"),
        "rollbacks": [e for e in log if e.get("event") == "rolledBack"],
        "reconnects": sum(1 for e in log if e.get("event") == "reconnecting"),
        "errors": [e for e in log if e.get("event") == "error"],
        "unparsedFollowerOutput": other[:10],
        "load": (root / "load.log").read_text().strip().splitlines()[-1:],
    }
    if scraped:
        summary["metrics"] = scraped
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
    if summary["comparisonErrors"]:
        failures.append(f"{summary['comparisonErrors']} ledger comparison errors")
    if summary["matched"] != summary["comparisons"]:
        failures.append("ledger mismatch or comparison error")
    if summary["errors"]:
        failures.append("follower logged errors")
    if other:
        failures.append(f"unexpected follower output: {other[0][:200]}")
    if scraped:
        for label in ("queryServer", "listener"):
            failures += [f"metrics ({label}): {f}" for f in scraped.get(label, {}).get("failures", ["not scraped"])]
    if not follower_alive:
        failures.append("follower exited before the end")
    if extended is not None:
        if extended["refusedUnexpectedly"]:
            failures.append("a transaction the reference was expected to refuse was not refused as expected")
        if extended["missingStages"]:
            failures.append(f"extended governance stages not reached: {extended['missingStages']}")
    if failures:
        print(json.dumps({"failed": failures}), flush=True)
    sys.exit(1 if failures else 0)
