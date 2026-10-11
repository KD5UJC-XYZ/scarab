# SPDX-License-Identifier: Apache-2.0
"""The `soak` scenario: follow, load and periodically compare for a fixed time."""
import json
import subprocess
import sys
import time
from ..cluster import up
from ..compare import compare_once
from ..util import ENTRY, data_root, docker


def soak(args):
    """Fresh cluster, Scala follower, transaction load and periodic comparison."""
    root = data_root(args.name)
    up(args)
    follower = subprocess.Popen([sys.executable, str(ENTRY), "--name", args.name, "follow"],
                                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    loader = subprocess.Popen([sys.executable, str(ENTRY), "--name", args.name, "load", "--seed", str(args.seed)],
                              stdout=(root / "load.log").open("w"), stderr=subprocess.STDOUT)
    governance = subprocess.Popen([sys.executable, str(ENTRY), "--name", args.name, "govload"],
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
    (root / "summary.json").write_text(json.dumps(summary, indent=2))
    print(json.dumps(summary, indent=2))
    failures = []
    if not summary["comparisons"]:
        failures.append("no ledger comparison completed")
    if summary["matched"] != summary["comparisons"]:
        failures.append("ledger mismatch or comparison error")
    if summary["errors"]:
        failures.append("follower logged errors")
    if not follower_alive:
        failures.append("follower exited before the end")
    if failures:
        print(json.dumps({"failed": failures}), flush=True)
    sys.exit(1 if failures else 0)
