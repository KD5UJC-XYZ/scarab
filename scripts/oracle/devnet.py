# SPDX-License-Identifier: Apache-2.0
"""The oracle comparison on a devnet cluster: db-analyser inside the reference container against the Scala digests.

Requires a run whose follower wrote state digests (`devnet.py e2e --oracle`, `soak --oracle`, or `follow
--dump-state`) and a reference container that is still up (`--keep`). The ImmutableDB of reference node 1 is
copied inside the container (the node keeps running), db-analyser stores the ledger state at the slot of each
Scala digest, and the projection of each snapshot is compared with the digest. Only digests whose block (header
hash) is on both final chains are compared; the others are excluded with a reason (`finality`, issue 91). The
follower must have been stopped by `stop_follower`, which records its final tip for the check of its log.
"""
import json
import subprocess
import time

from devnet.util import ENV, QUERY_PORT, docker, is_starvation_warning, ref
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


FINAL_TIP = "follower-tip.json"


def benign(line):
    """Known non-chain output of the follower: the SLF4J banner and the runtime's starvation warnings."""
    return line.startswith("SLF4J") or is_starvation_warning(line)


def byron_epoch_slots(root):
    """Slots of a Byron epoch, ten times the Byron `k`, or None without a Byron genesis."""
    genesis = root / "genesis" / "byron-genesis.json"
    return 10 * json.loads(genesis.read_text())["protocolConsts"]["k"] if genesis.exists() else None


def follower_chain(root):
    """The follower's final chain, rebuilt from `follower.jsonl` and checked against the recorded final tip."""
    events = finality.parse_log((root / "follower.jsonl").read_text(errors="strict"), benign)
    chain = finality.FollowerChain.from_events(events, byron_epoch_slots(root))
    recorded = root / FINAL_TIP
    chain.check_final_tip(json.loads(recorded.read_text()) if recorded.exists() else None)
    return chain


def follower_tip(name):
    """The follower's tip ({slot, hash}) from its query server's `/tip`, or None when it does not answer."""
    url = f"http://127.0.0.1:{QUERY_PORT}/tip"
    script = f"import urllib.request,sys;sys.stdout.write(urllib.request.urlopen('{url}',timeout=10).read().decode())"
    try:
        r = docker("exec", f"{name}-scala", "python3", "-c", script, check=False, timeout=30)
    except subprocess.TimeoutExpired:
        return None
    try:
        point = json.loads(r.stdout)["point"] if r.returncode == 0 else None
    except (ValueError, KeyError, TypeError):
        return None
    return {"slot": point["slot"], "hash": point["hash"]} if isinstance(point, dict) and "hash" in point else None


def node_pid(name, node):
    """The PID, inside the reference container, of reference node `node`'s `cardano-node run` (by its database
    path), or None unless exactly one process matches."""
    listing = in_ref(name, 'for p in /proc/[0-9]*; do echo "${p#/proc/} $(tr "\\0" " " < $p/cmdline 2>/dev/null)"; '
                           'done', timeout=30)
    database = f"{NODE_DB.replace('node1', f'node{node}')} "
    rows = [line.split(None, 1) for line in listing.stdout.splitlines()]
    # the node itself, not the shell that started it (whose arguments hold the same command line)
    found = [int(pid) for pid, args in (row for row in rows if len(row) == 2)
             if args.startswith("cardano-node run") and database in args + " "]
    return found[0] if len(found) == 1 else None


def signal_node(name, pid, sig):
    """Send `sig` (STOP, CONT) to `pid` in the reference container; True when docker confirms it."""
    try:
        return docker("exec", ref(name), "/bin/sh", "-c", f"kill -{sig} {pid}", check=False,
                      timeout=30).returncode == 0
    except subprocess.TimeoutExpired:
        return False


def remove_follower(name, timeout=60):
    """Remove the follower's container; True only when it is confirmed gone."""
    container = f"{name}-scala"
    try:
        removed = docker("rm", "-f", container, check=False, timeout=timeout)
        left = docker("ps", "-aq", "--filter", f"name=^{container}$", check=False, timeout=30)
    except subprocess.TimeoutExpired:
        return False
    return removed.returncode == 0 and left.returncode == 0 and not left.stdout.strip()


def stop_follower(name, root, settle_seconds=2, attempts=15, node=1, remove_timeout=60, resume_attempts=3):
    """Stop the follower, recording its final tip in `root/follower-tip.json`.

    The follower's only peer, reference node `node`, is stopped (SIGSTOP, by PID; the other two nodes keep the
    chain growing), so no block or rollback reaches the follower; the tip is final once two readings of its `/tip`
    `settle_seconds` apart agree. The follower is then removed and the node continued, both in cleanup: SIGCONT is
    sent whenever a SIGSTOP may have been delivered, even after an error or a timeout. The tip is recorded only
    with the follower confirmed gone; without it the comparison fails (`Cause.NO_FINAL_TIP`). Raises SystemExit
    when the follower is not confirmed gone or the node not confirmed continued."""
    recorded = root / FINAL_TIP
    recorded.unlink(missing_ok=True)
    pid, tip, removed, resumed = None, None, False, True
    try:
        try:
            pid = node_pid(name, node)
        except (subprocess.TimeoutExpired, SystemExit):
            pid = None
        if pid is not None:
            resumed = False  # from here a SIGSTOP may have been delivered, whatever docker reports
            if signal_node(name, pid, "STOP"):
                last = follower_tip(name)
                for _ in range(attempts):
                    time.sleep(settle_seconds)
                    now = follower_tip(name)
                    if now is not None and now == last:
                        tip = now
                        break
                    last = now
    finally:
        removed = remove_follower(name, remove_timeout)
        if pid is not None:
            resumed = any(signal_node(name, pid, "CONT") for _ in range(resume_attempts))
    if not removed:
        raise SystemExit(f"the follower container {name}-scala could not be confirmed removed")
    if not resumed:
        raise SystemExit(f"reference node {node} (pid {pid}) could not be confirmed continued after SIGSTOP")
    if tip is not None:
        recorded.write_text(json.dumps(tip))


def run(name, root, log=print, wait_seconds=240):
    """Compare every digest in `root/dump` whose block is on both final chains (`finality`); returns (summary
    dict, failures list). The summary counts the compared, matched and excluded digests and names the reason
    for each exclusion; `results` holds one row per digest."""
    dumps = finality.load(root / "dump")
    if not dumps:
        return {"digests": 0}, ["the follower wrote no state digests (was it started with --dump-state?)"]
    try:
        follower = follower_chain(root)
    except (finality.ChainRebuildError, OSError, ValueError) as e:
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
    try:
        finality.check_reference(chain, tip)
    except finality.ChainRebuildError as e:
        return {"digests": len(dumps)}, [f"oracle: the reference's ImmutableDB listing is not one chain: {e}"]
    reference = {b.hash: b for b in chain}
    agree = finality.final_chains_agree(chain, follower, k)
    analyser = Analyser(lambda args: ["docker", "exec", ref(name), "db-analyser", *args], DB,
                        f"{ENV}/configuration.yaml", threshold=1)
    scratch = root / "oracle"
    results = []
    previous = None
    for dump in sorted(dumps):
        verdict = finality.classify(dump, reference, tip, follower, k, agree)
        entry = {"slot": dump.slot, "hash": dump.hash, "epoch": dumped_epoch(dump), "verdict": verdict,
                 "followerDepth": follower.depth(dump.hash),
                 "referenceBlock": finality.first_reference_block(chain, dump.slot)}
        if verdict is finality.Verdict.COMPARE:
            entry.update(compare_one(analyser, name, dump, previous, scratch))
            previous = entry.get("stored", previous)
        results.append(entry)
        log({"oracle": dump.slot, "hash": dump.hash[:16], "verdict": verdict.value, "epoch": entry.get("epoch"),
             "era": entry.get("era"), "matched": entry.get("matched"), "differences": entry.get("differenceCount"),
             "error": entry.get("error")})
    summary, failures = finality.summarize(results, len(dumps), k, tip, follower.tip, agree)
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
