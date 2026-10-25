# SPDX-License-Identifier: Apache-2.0
"""The oracle comparison on a devnet cluster: db-analyser inside the reference container against the Scala digests.

Requires a run whose follower wrote state digests (`devnet.py e2e --oracle`, `soak --oracle`, or `follow
--dump-state`) and a reference container that is still up (`--keep`). The ImmutableDB of reference node 1 is
copied inside the container (the node keeps running), db-analyser stores the ledger state at the slot of each
Scala digest, and the projection of each snapshot is compared with the digest. Only digests whose block (header
hash) is on both final chains are compared; the others are excluded with a reason (`finality`, issue 91).
"""
import json
import subprocess
import time

from devnet.util import ENV, docker, read_log, ref
from . import compare, finality, ledger
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


def reference_blocks(name):
    """The blocks of the copied ImmutableDB of reference node 1: the reference's final chain."""
    r = docker("exec", ref(name), "db-analyser", "--db", DB, "--config", f"{ENV}/configuration.yaml", "--in-mem",
               "--show-slot-block-no", "--threshold", "1", check=False, timeout=300)
    if r.returncode != 0:
        raise AnalyserError(f"db-analyser --show-slot-block-no failed ({r.returncode}): {(r.stdout + r.stderr)[-1500:]}")
    return finality.reference_chain(r.stdout + r.stderr)


def security_param(root):
    return json.loads((root / "genesis" / "shelley-genesis.json").read_text())["securityParam"]


def dumped_epoch(dump):
    """The ledger epoch the digest records (the state after its block), or None when the file has none."""
    try:
        return json.loads(dump.path.read_text()).get("epoch")
    except (OSError, ValueError):
        return None


def run(name, root, log=print, wait_seconds=240):
    """Compare every digest in `root/dump` whose block is on both final chains (`finality`); returns (summary
    dict, failures list). The summary counts the compared, matched and excluded digests and names the reason
    for each exclusion; `results` holds one row per digest."""
    dumps = finality.load(root / "dump")
    if not dumps:
        return {"digests": 0}, ["the follower wrote no state digests (was it started with --dump-state?)"]
    events, _ = read_log(root / "follower.jsonl")
    try:
        follower = finality.FollowerChain.from_events(events)
    except finality.ChainRebuildError as e:
        return {"digests": len(dumps)}, [f"oracle: the follower's final chain cannot be rebuilt from its log: {e}"]
    k = security_param(root)
    newest = max(d.slot for d in dumps)
    deadline = time.monotonic() + wait_seconds
    while True:
        copy_db(name)
        tip = immutable_tip_slot(name)
        if tip is not None and tip >= newest:
            break
        if time.monotonic() > deadline:
            log({"oracle": "immutable tip behind the newest digest", "tip": tip})
            break
        time.sleep(10)
    try:
        chain = reference_blocks(name)
    except (AnalyserError, subprocess.SubprocessError) as e:
        return {"digests": len(dumps)}, [f"oracle: the reference's final chain cannot be listed: {e!r}"]
    if tip is not None and (not chain or chain[-1].slot != tip):
        return {"digests": len(dumps)}, [f"oracle: db-analyser listed {len(chain)} reference blocks ending at "
                                         f"{chain[-1] if chain else None}, the immutable tip is at slot {tip}"]
    reference = {b.hash: b for b in chain}
    analyser = Analyser(lambda args: ["docker", "exec", ref(name), "db-analyser", *args], DB,
                        f"{ENV}/configuration.yaml", threshold=1)
    scratch = root / "oracle"
    results = []
    previous = None
    for dump in sorted(dumps):
        verdict = finality.classify(dump, reference, tip, follower, k)
        entry = {"slot": dump.slot, "hash": dump.hash, "epoch": dumped_epoch(dump), "verdict": verdict.value,
                 "followerDepth": follower.depth(dump.hash),
                 "referenceBlock": finality.first_reference_block(chain, dump.slot)}
        if verdict is finality.Verdict.COMPARE:
            entry.update(compare_one(analyser, name, dump, previous, scratch))
            previous = entry.get("stored", previous)
        results.append(entry)
        log({"oracle": dump.slot, "hash": dump.hash[:16], "verdict": verdict.value, "epoch": entry.get("epoch"),
             "era": entry.get("era"), "matched": entry.get("matched"), "differences": entry.get("differenceCount"),
             "error": entry.get("error")})
    summary, failures = finality.summarize(results, len(dumps), k, tip, follower.tip)
    (root / "oracle-summary.json").write_text(json.dumps(summary, indent=2))
    return summary, failures


def compare_one(analyser, name, dump, previous, scratch):
    """Store the reference state after the digest's block, project it and compare; returns the row's fields."""
    entry = {}
    try:
        stored = analyser.store(dump.slot, analyse_from=previous)
        if stored is not None:
            entry["stored"] = stored
        if stored != dump.slot:
            # the block is on the reference's immutable chain, so the first block at or after its slot is that block
            entry["error"] = f"db-analyser stored the state at slot {stored}, the digest is at {dump.slot}"
            return entry
        out = scratch / f"{dump.slot}-{dump.hash}"
        export_snapshot(name, dump.slot, out)
        reference = ledger.reduced(out)
        if reference["point"]["hash"] != dump.hash:
            entry["error"] = f"the reference state at slot {dump.slot} is after block {reference['point']['hash']}"
        differences = compare.compare_files(reference, dump.path)
        entry["era"] = reference["era"]
        entry["ledgerEpoch"] = reference["epoch"]
        entry["utxoSize"] = reference["utxo"]["count"]
        entry["matched"] = not differences and "error" not in entry
        entry["differenceCount"] = len(differences)
        entry["differences"] = [{"path": p, "reference": str(a)[:300], "scala": str(b)[:300]}
                                for p, a, b in differences[:25]]
    except (AnalyserError, subprocess.SubprocessError, OSError, ValueError, AssertionError, KeyError, IndexError) as e:
        import traceback
        entry["error"] = repr(e)
        entry["traceback"] = traceback.format_exc()[-1200:]
    return entry
