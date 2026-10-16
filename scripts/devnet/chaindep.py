# SPDX-License-Identifier: Apache-2.0
"""`sample-chain-dep`: the reference node's DebugChainDepState (the "protocol-state" query) while an
`up-eras` cluster walks the eras.

Run it next to `up-eras` (it waits for the cluster). Every ~second it opens a LocalStateQuery session
on node 1, acquires the volatile tip ONCE and asks, in that one session, GetCurrentEra, GetChainPoint,
GetLedgerTip and DebugChainDepState wrapped in the era's `QueryIfCurrent`, so the tip and the answer
are for the same state. Points are kept at era and epoch changes, at a regular slot spacing, and a few
while the wall clock is in an epoch the tip has not entered (the epoch after a hard fork, before the
first block of the new era is applied). Output: a JSON list of `{era, epoch, slot, hash, query,
answer, ...}`, `query` and `answer` as hex in the form of fixtures/devnet/lsq/golden.json (the answer
is the item after the `[4, ...]` of MsgResult, so it still carries the `[result]` of the
QueryIfCurrent).
"""
import calendar
import json
import pathlib
import time

from . import ntcquery
from .cbor import enc
from .eraview import ERA_INDEX, Q_CHAIN_DEP_STATE, Q_LEDGER_TIP
from .util import ENV, MAGIC, data_root, ref

ERA_NAMES = {0: "byron", **{i: e for e, i in ERA_INDEX.items()}}
Q_GET_CURRENT_ERA, Q_GET_CHAIN_POINT = [0, [2, [1]]], [3]


def epoch_of(slot, shelley, byron_epochs):
    """Epoch of a slot: `byron_epochs` Byron epochs of 10k slots, then epochs of `epochLength` slots."""
    byron_len = 10 * shelley["securityParam"]
    if slot < byron_epochs * byron_len:
        return slot // byron_len
    return byron_epochs + (slot - byron_epochs * byron_len) // shelley["epochLength"]


def wall_slot(elapsed, shelley, byron_epochs, byron_slot):
    """The slot the clock is in `elapsed` seconds after the system start (Byron slots are `byron_slot` seconds)."""
    byron_slots = byron_epochs * 10 * shelley["securityParam"]
    if elapsed < byron_slots * byron_slot:
        return int(elapsed / byron_slot)
    return byron_slots + int((elapsed - byron_slots * byron_slot) / shelley["slotLength"])


def sample_once(name, shelley, byron_epochs, byron_slot, start):
    """One acquire of the volatile tip and the questions about it (era 0 or an empty chain: no answer)."""
    with ntcquery.LocalStateQuery(ref(name), f"{ENV}/socket/node1/sock", MAGIC) as q:
        q.acquire()
        wall = wall_slot(time.time() - start, shelley, byron_epochs, byron_slot)
        era = q.query_raw(Q_GET_CURRENT_ERA)
        point = q.query_raw(Q_GET_CHAIN_POINT)
        if era == 0 or not point:
            return None
        ledger_tip = q.ledger_query(era, [Q_LEDGER_TIP])
        query = [0, [0, [era, [Q_CHAIN_DEP_STATE]]]]
        q.query_raw(query)
        raw = q.last_raw  # [4, result]
        assert raw[0] == 0x82 and raw[1] == 0x04
        slot, block = point[0], point[1]
        assert (ledger_tip[0], ledger_tip[1]) == (slot, block), (ledger_tip, point)
        return {"era": ERA_NAMES[era], "epoch": epoch_of(slot, shelley, byron_epochs), "slot": slot,
                "hash": block.hex(), "wallSlot": wall, "wallEpoch": epoch_of(wall, shelley, byron_epochs),
                "query": enc(query).hex(), "answer": raw[2:].hex()}


def keep(point, recorded, spacing, dense_per_epoch):
    """Whether a sample is a point of interest, given those already kept."""
    if any(r["hash"] == point["hash"] for r in recorded):
        return False
    if not recorded:
        return True
    last = recorded[-1]
    if (point["era"], point["epoch"]) != (last["era"], last["epoch"]):
        return True
    if point["wallEpoch"] > point["epoch"]:  # the clock is in the next epoch, the tip is not: the boundary window
        return sum(1 for r in recorded if r["wallEpoch"] > r["epoch"] == point["epoch"]) < dense_per_epoch
    return point["slot"] >= last["slot"] + spacing


def sample(args):
    root = data_root(args.name)
    out = pathlib.Path(args.out)
    deadline = time.monotonic() + args.timeout
    genesis = root / "genesis" / "shelley-genesis.json"  # copied once the nodes answer
    while not genesis.exists():
        if time.monotonic() > deadline:
            raise SystemExit("the cluster did not come up")
        time.sleep(1)
    shelley = json.loads(genesis.read_text())
    start = calendar.timegm(time.strptime(shelley["systemStart"], "%Y-%m-%dT%H:%M:%SZ"))
    byron_slot = int(json.loads((root / "genesis" / "byron-genesis.json").read_text())["blockVersionData"]["slotDuration"]) / 1000
    recorded, seen, last_error = [], 0, None
    while time.monotonic() < deadline:
        began = time.monotonic()
        try:
            point = sample_once(args.name, shelley, args.byron_epochs, byron_slot, start)
        except (EOFError, RuntimeError, OSError, SystemExit, ValueError, IndexError) as e:
            if str(e) != last_error:
                print(json.dumps({"skipped": str(e)[:200]}), flush=True)
                last_error = str(e)
            point = None
        if point:
            seen += 1
            if keep(point, recorded, args.spacing, args.dense_per_epoch):
                recorded.append(point)
                out.write_text(json.dumps(recorded, indent=1))
                print(json.dumps({k: point[k] for k in ("era", "epoch", "slot", "wallSlot")}), flush=True)
            if point["epoch"] >= args.until_epoch:
                break
        time.sleep(max(0.0, args.interval - (time.monotonic() - began)))
    out.write_text(json.dumps(recorded, indent=1))
    print(json.dumps({"samples": seen, "recorded": len(recorded), "out": str(out)}))


def block_hash(line):
    """Hash of a raw `[era tag, block]` line (Shelley-based eras): Blake2b-256 of the block's header bytes."""
    from . import cborspan
    from .eratx import blake2b
    b = bytes.fromhex(line)
    start, _ = cborspan.children(b, 0)[1]
    header_start, header_end = cborspan.children(b, start)[0]
    return blake2b(b[header_start:header_end]).hex()


def assemble(args):
    """Keep the points of a `sample-chain-dep` run whose block is in a captured chain (`blocks.hex` of
    `capture-eras`; the others were rolled back or captured too late) and write `answers.json`."""
    out = pathlib.Path(args.out)
    hashes = set()
    for line in (out / "blocks.hex").read_text().split():
        try:
            hashes.add(block_hash(line))
        except Exception:  # a Byron block: no sample is taken there
            pass
    points = json.loads(pathlib.Path(args.points).read_text())
    kept = [p for p in points if p["hash"] in hashes]
    fields = ("era", "epoch", "slot", "hash", "wallSlot", "query", "answer")
    (out / "answers.json").write_text(json.dumps([{k: p[k] for k in fields} for p in kept], indent=1) + "\n")
    print(json.dumps({"points": len(points), "kept": len(kept), "dropped": [p["slot"] for p in points if p["hash"] not in hashes]}))
