# SPDX-License-Identifier: Apache-2.0
"""LocalTxMonitor and local ChainSync clients (stdlib only) for the `ntc` scenario, and the
comparisons of the two nodes' answers over them.

Both protocols are spoken directly (mini-protocols 9 and 5), on top of the connection handling of
`ntcquery.LocalStateQuery`, so the raw CBOR of each answer can be compared byte for byte:

  * LocalTxMonitor: one acquired snapshot is walked on both nodes (sizes, measures, every
    transaction, membership of each held id and of an absent one, the measures again after the
    transactions were handed out). The two snapshots are comparable when they have the same slot and
    the same transactions in the same order; then every answer must be equal.
  * local ChainSync: both nodes are followed from one point; the whole blocks they send (the raw
    `#6.24(bytes)` item) must be equal for every block both sent.
"""
import hashlib
import threading
import time

from . import ntcquery
from .cbor import enc
from .ntcquery import decode

MONITOR, CHAIN_SYNC = 9, 5


def blake(data):
    return hashlib.blake2b(data, digest_size=32).digest()


class Session(ntcquery.LocalStateQuery):
    """One node-to-client connection with the LocalTxMonitor and ChainSync messages."""

    def request(self, protocol, message):
        """Send one message; the decoded reply and its raw bytes."""
        self._send(protocol, enc(message))
        reply = self._recv(protocol)
        return reply, self.last_raw

    # ---- LocalTxMonitor
    def acquire_snapshot(self):
        reply, _ = self.request(MONITOR, [1])
        assert reply[0] == 2, reply
        return reply[1]

    def await_snapshot(self):
        """AwaitAcquire: waits for the node's mempool to differ from the acquired snapshot."""
        reply, _ = self.request(MONITOR, [1])
        assert reply[0] == 2, reply
        return reply[1]

    def release_snapshot(self):
        self._send(MONITOR, enc([3]))

    def next_tx(self):
        """The raw reply `[6]` or `[6, tx]`."""
        return self.request(MONITOR, [5])[1]

    def has_tx(self, txid, era=6):
        return self.request(MONITOR, [7, [era, txid]])[1]

    def sizes(self):
        return self.request(MONITOR, [9])[1]

    def measures(self):
        return self.request(MONITOR, [11])[1]

    # ---- local ChainSync
    def find_intersect(self, points):
        """Points as (slot, hash) pairs, or None for Origin. The decoded reply."""
        wire = [[] if p is None else [p[0], p[1]] for p in points]
        return self.request(CHAIN_SYNC, [4, wire])[0]

    def request_next(self, timeout=180):
        """The next instruction as (decoded message, raw message); an AwaitReply is followed by
        the reply that ends the wait."""
        self._send(CHAIN_SYNC, enc([0]))
        reply = self._recv(CHAIN_SYNC)
        raw = self.last_raw
        if reply[0] == 1:
            reply = self._recv(CHAIN_SYNC)
            raw = self.last_raw
        return reply, raw


def raw_tx_id(tx):
    """The id of a transaction `[body, witnesses, is_valid, aux]`: the hash of the body's original bytes."""
    _, end = decode(tx, 1)  # skip the array head; the body is the first element
    return blake(tx[1:end])


def tx_id(item):
    """The transaction id of a `[6, #6.24(tx)]` item."""
    value, _ = decode(item, 0)
    return raw_tx_id(value[1].value)


def block_point(item):
    """(slot, header hash) of a block item `#6.24(bytes)` of `[era, [header, ...]]`."""
    value, _ = decode(item, 0)
    inner = value.value
    header, end = decode(inner, 3)
    return header[0][1], blake(inner[3:end])


def block_item(raw):
    """The block item of a raw `[2, block, tip]` message."""
    _, end = decode(raw, 2)
    return raw[2:end]


# ------------------------------------------------------------------ LocalTxMonitor comparison


def snapshot_transcript(session):
    """Acquire a snapshot and walk it: the slot, then the raw answers of a fixed sequence of
    requests. `txs` are the raw next-tx replies (the transactions, in order)."""
    slot = session.acquire_snapshot()
    out = {"slot": slot, "steps": []}

    def step(label, raw):
        out["steps"].append((label, raw))
        return raw

    step("sizes", session.sizes())
    step("measures", session.measures())
    step("has-absent", session.has_tx(bytes(32)))
    step("has-other-era", session.has_tx(bytes(32), era=5))
    txs = []
    while True:
        raw = session.next_tx()
        step(f"next-tx {len(txs)}", raw)
        if raw == b"\x81\x06":
            break
        txs.append(raw)
        if len(txs) == 1:
            step("measures after one next-tx", session.measures())
    out["txs"] = txs
    ids = [tx_id(raw[2:]) for raw in txs]
    out["ids"] = ids
    for i, ident in enumerate(ids):
        step(f"has {i}", session.has_tx(ident))
    step("sizes after the walk", session.sizes())
    step("measures after the walk", session.measures())
    session.release_snapshot()
    return out


def compare_snapshots(h, s):
    """(comparable, differences): the snapshots compare when slot and transactions agree; then every
    step must be equal."""
    if h["slot"] != s["slot"] or h["txs"] != s["txs"]:
        return False, []
    diffs = [(lh, rh, rs) for (lh, rh), (_, rs) in zip(h["steps"], s["steps"]) if rh != rs]
    return True, diffs


def monitor_rounds(h_session, s_session, until, every=0.02, stats=None, stop=None):
    """Compare snapshots of both nodes, back to back, until `until` (monotonic) or `stop()`."""
    stats = stats if stats is not None else {"rounds": 0, "comparable": 0, "nonEmpty": 0, "differences": [],
                                            "maxTxs": 0}
    stats.setdefault("last", (0, 0))
    while time.monotonic() < until and not (stop and stop()):
        h = snapshot_transcript(h_session)
        s = snapshot_transcript(s_session)
        stats["rounds"] += 1
        stats["last"] = (len(h["txs"]), len(s["txs"]))
        comparable, diffs = compare_snapshots(h, s)
        if comparable:
            stats["comparable"] += 1
            if h["txs"]:
                stats["nonEmpty"] += 1
                stats["maxTxs"] = max(stats["maxTxs"], len(h["txs"]))
            for label, rh, rs in diffs:
                stats["differences"].append({"step": label, "slot": h["slot"], "reference": rh.hex(),
                                             "scala": rs.hex()})
        time.sleep(every)
    return stats


def await_wakes(h_session, s_session, trigger, timeout=60):
    """AwaitAcquire on both nodes after one acquire: `trigger()` changes the mempools (and, a block
    later, the slot); both must answer with a snapshot whose slot is greater than the one held."""
    first = (h_session.acquire_snapshot(), s_session.acquire_snapshot())
    answers = {}

    def wait(key, session):
        try:
            answers[key] = session.await_snapshot()
        except Exception as e:  # noqa: BLE001 - reported below
            answers[key] = repr(e)

    threads = [threading.Thread(target=wait, args=("h", h_session)), threading.Thread(target=wait, args=("s", s_session))]
    for t in threads:
        t.start()
    trigger()
    for t in threads:
        t.join(timeout)
    return first, answers


# ------------------------------------------------------------------ local ChainSync comparison


def follow_blocks(open_session, point, count, deadline, out):
    """Follow a node from `point` for `count` blocks: {hash: (slot, raw block item)} into `out`, and the
    number of roll-backwards seen."""
    result = {"blocks": out, "rollbacks": 0, "intersect": None, "error": None, "order": [], "first": None}
    try:
        with open_session() as session:
            found = session.find_intersect([point])
            result["intersect"] = found[0]
            if found[0] != 5:
                return result
            while len(out) < count and time.monotonic() < deadline:
                reply, raw = session.request_next()
                if result["first"] is None:
                    # the first instruction after an intersection: a roll-back to the point found, and which point
                    result["first"] = [reply[0], str(reply[1]) if reply[0] == 3 else None]
                if reply[0] == 2:
                    item = block_item(raw)
                    slot, ident = block_point(item)
                    out[ident] = (slot, item)
                    result["order"].append(ident)
                elif reply[0] == 3:
                    result["rollbacks"] += 1
    except Exception as e:  # noqa: BLE001 - reported to the scenario
        result["error"] = repr(e)
    return result


def compare_follow(open_h, open_s, point, count, seconds):
    """Follow both nodes from `point` at once; the blocks sent by both must be equal."""
    deadline = time.monotonic() + seconds
    got = {"h": {}, "s": {}}
    results = {}

    def run(key, opener):
        results[key] = follow_blocks(opener, point, count, deadline, got[key])

    threads = [threading.Thread(target=run, args=("h", open_h)), threading.Thread(target=run, args=("s", open_s))]
    for t in threads:
        t.start()
    for t in threads:
        t.join(seconds + 30)
    common = [k for k in results["h"]["order"] if k in got["s"]]
    differing = [k.hex() for k in common if got["h"][k][1] != got["s"][k][1]]
    return {"compared": len(common), "differing": differing,
            "points": [(got["h"][k][0], k.hex()) for k in common],
            "reference": {k: v for k, v in results["h"].items() if k not in ("blocks", "order")},
            "scala": {k: v for k, v in results["s"].items() if k not in ("blocks", "order")},
            "sameFirstInstruction": results["h"]["first"] == results["s"]["first"],
            "referenceBlocks": len(got["h"]), "scalaBlocks": len(got["s"]),
            "bytes": sum(len(got["s"][k][1]) for k in common)}


INTERSECT_NOT_FOUND = 6


def live_follow(take_tip, follow, attempts=5):
    """Follow both nodes from the Scala node's tip, taking a fresh tip while either node no longer has
    it. The tip can be rolled back (a fork switch) before the followers ask for it. The Scala node
    follows the reference node and so trails it: the reference can drop the block first, and then it
    answers IntersectNotFound while the Scala node still serves the block, rolls back and sends the
    new branch, which the reference does not send from there. Nothing is compared in such a round,
    so it is taken again; the rounds taken are recorded as `attempts`. None without a tip."""
    result = None
    for attempt in range(1, attempts + 1):
        tip = take_tip()
        if not tip:
            break
        result = follow(tip)
        result["attempts"] = attempt
        if INTERSECT_NOT_FOUND not in (result["reference"]["intersect"], result["scala"]["intersect"]):
            break
    return result
