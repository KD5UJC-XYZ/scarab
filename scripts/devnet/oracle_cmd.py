# SPDX-License-Identifier: Apache-2.0
"""`devnet.py oracle`: db-analyser comparison on an existing cluster (see `scripts/oracle/devnet.py`)."""
import json
import sys

from .util import data_root, docker


def oracle(args):
    from oracle import devnet as oracle_devnet
    root = data_root(args.name)
    running = docker("ps", "-q", "--filter", f"name=^{args.name}-scala$", check=False).stdout.strip()
    if running:
        # the follower's log is final only once it stops; its last tip is recorded for the check of the log
        print(json.dumps({"oracle": "stopping the follower", "container": f"{args.name}-scala"}), flush=True)
        oracle_devnet.stop_follower(args.name, root)
    summary, failures = oracle_devnet.run(args.name, root, log=lambda m: print(json.dumps(m), flush=True))
    summary.pop("results", None)  # the per-epoch rows are in <run dir>/oracle-summary.json
    print(json.dumps({**summary, "failures": failures}, indent=2))
    sys.exit(1 if failures else 0)
