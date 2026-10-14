# SPDX-License-Identifier: Apache-2.0
"""The oracle comparison on a devnet cluster: db-analyser inside the reference container against the Scala digests.

Requires a run whose follower wrote state digests (`devnet.py e2e --oracle`, `soak --oracle`, or `follow
--dump-state`) and a reference container that is still up (`--keep`). The ImmutableDB of reference node 1 is
copied inside the container (the node keeps running), db-analyser stores the ledger state at the slot of each
Scala digest, and the projection of each snapshot is compared with the digest.
"""
import json
import subprocess
import time

from devnet.util import ENV, docker, ref
from . import compare, ledger
from .analyse import Analyser, AnalyserError

WORK = "/work/oracle"
DB = f"{WORK}/db"
NODE_DB = f"{ENV}/node-data/node1/db"


def in_ref(name, script, timeout=300):
    return docker("exec", ref(name), "/bin/sh", "-c", script, timeout=timeout)


def copy_db(name):
    in_ref(name, f"rm -rf {WORK}; mkdir -p {DB}/ledger {DB}/volatile && cp -r {NODE_DB}/immutable {DB}/immutable "
                 f"&& cp {NODE_DB}/protocolMagicId {DB}/")


def immutable_tip_slot(name):
    """Slot of the last block in the copied ImmutableDB (db-analyser --show-slot-block-no prints every block)."""
    r = docker("exec", ref(name), "db-analyser", "--db", DB, "--config", f"{ENV}/configuration.yaml", "--in-mem",
               "--count-blocks", "--threshold", "1", check=False, timeout=300)
    for line in (r.stdout + r.stderr).splitlines():
        if line.startswith("ImmutableDB tip"):
            return int(line.split("SlotNo")[1].split(",")[0].strip().rstrip(")}"))
    return None


def export_snapshot(name, slot, out):
    out.mkdir(parents=True, exist_ok=True)
    for f in ("meta", "state", "tables"):
        data = subprocess.run(["docker", "exec", ref(name), "cat", f"{DB}/ledger/{slot}_db-analyser/{f}"],
                              capture_output=True, check=True).stdout
        (out / f).write_bytes(data)


def run(name, root, log=print, wait_seconds=240):
    """Compare every digest in `root/dump`; returns (summary dict, failures list)."""
    dumps = compare.load_dumps(root / "dump")
    if not dumps:
        return {"digests": 0}, ["the follower wrote no state digests (was it started with --dump-state?)"]
    wanted = sorted(dumps)
    deadline = time.monotonic() + wait_seconds
    while True:
        copy_db(name)
        tip = immutable_tip_slot(name)
        if tip is not None and tip >= wanted[-1]:
            break
        if time.monotonic() > deadline:
            wanted = [s for s in wanted if tip is not None and s <= tip]
            log({"oracle": "immutable tip behind the newest digest", "tip": tip})
            break
        time.sleep(10)
    analyser = Analyser(lambda args: ["docker", "exec", ref(name), "db-analyser", *args], DB,
                        f"{ENV}/configuration.yaml", threshold=1)
    scratch = root / "oracle"
    results, failures = [], []
    previous = None
    for slot in wanted:
        entry = {"slot": slot}
        try:
            stored = analyser.store(slot, analyse_from=previous)
            if stored != slot:
                entry["error"] = f"db-analyser stored the state at slot {stored}, the digest is at {slot}"
            else:
                export_snapshot(name, slot, scratch / str(slot))
                reference = ledger.reduced(scratch / str(slot))
                differences = []
                for block_hash, path in dumps[slot]:
                    differences = compare.compare_files(reference, path)
                    entry["hash"] = block_hash
                    if reference["point"]["hash"] != block_hash:
                        entry["error"] = f"the reference block at slot {slot} is {reference['point']['hash']}"
                entry["era"] = reference["era"]
                entry["epoch"] = reference["epoch"]
                entry["utxoSize"] = reference["utxo"]["count"]
                entry["matched"] = not differences and "error" not in entry
                entry["differenceCount"] = len(differences)
                entry["differences"] = [{"path": p, "reference": str(a)[:300], "scala": str(b)[:300]}
                                        for p, a, b in differences[:25]]
            previous = slot
        except (AnalyserError, subprocess.SubprocessError, OSError, ValueError, AssertionError, KeyError, IndexError) as e:
            import traceback
            entry["error"] = repr(e)
            entry["traceback"] = traceback.format_exc()[-1200:]
        results.append(entry)
        log({"oracle": slot, "epoch": entry.get("epoch"), "era": entry.get("era"), "matched": entry.get("matched"),
             "differences": entry.get("differenceCount"), "error": entry.get("error")})
    matched = sum(1 for r in results if r.get("matched"))
    summary = {"digests": len(dumps), "compared": len(results), "matched": matched,
               "mismatched": sum(1 for r in results if r.get("matched") is False and "error" not in r),
               "errors": sum(1 for r in results if "error" in r),
               "notYetImmutable": len(dumps) - len(wanted), "results": results}
    (root / "oracle-summary.json").write_text(json.dumps(summary, indent=2))
    if matched != len(dumps):
        failures.append(f"oracle: {matched} of {len(dumps)} epoch-boundary states match the Haskell ledger "
                        f"(first: {next((r for r in results if not r.get('matched')), None)})")
    return summary, failures
