# SPDX-License-Identifier: Apache-2.0
"""The cluster's fork depth, from the pools' logs: the deepest rollback any pool would need to follow the longest tip.

    python3 -I forktree.py STATE LOG...

Each log is a pool's log in /logs: cardano-node's JSON traces or Scarab's JSON events. Every forged block is logged
with its parent by the pool that forged it (cardano-node `Forge.Loop.ForgedBlock`: `block`, `blockNo`, `blockPrev`;
Scarab `forged`: `hash`, `blockNo`, `prev`), and every change of a pool's tip by that pool (cardano-node
`ChainDB.AddBlockEvent.AddedToCurrentChain` and `SwitchedToAFork`, `newtip` "hash@slot"; Scarab `extended` and
`rolledBack`). From the parents the blocks form a tree; a pool's fork depth is its tip's block number less that of
its tip's last common ancestor with the longest tip, so a pool whose tip is an ancestor of the longest tip is behind,
not forked. The cluster-wide depth is the largest; past Praos's k, a fork is unrecoverable by protocol.

STATE (JSON) keeps the tree, each pool's tip and how far each log has been read, across the test commands' runs in a
timeline; an exclusive lock on STATE with the suffix .lock makes each run's read, update and write one
transaction. The result is one JSON line: {"depth", "pools": {pool: depth or null}, "longest", "forgedOnLongest":
{pool: blocks it forged on the longest chain}, "forgedOnChain": {pool: {forger: blocks on that pool's own chain}},
"blocks"}. A tip whose ancestry is not yet known (its forger's log
not yet read that far) counts as null and is left out.

This mirrors the fork tree of cardano-foundation/cardano-node-antithesis (`components/tracer-sidecar`,
`ForkTree.hs`), which takes the parents from `AddedToCurrentChain`; the forge events name them directly.
"""
import fcntl
import json
import os
import pathlib
import sys


def read_new(log, offset):
    """The complete lines of LOG past OFFSET, and the offset after the last complete line."""
    with open(log, "rb") as f:
        f.seek(0, os.SEEK_END)
        size = f.tell()
        if offset > size:
            offset = 0  # the log was started again
        f.seek(offset)
        data = f.read()
    end = data.rfind(b"\n") + 1
    return data[:end].decode("utf-8", "replace").splitlines(), offset + end


def absorb(state, pool, line):
    try:
        e = json.loads(line)
    except ValueError:
        return
    if not isinstance(e, dict):
        return
    parents, numbers, tips = state["parents"], state["numbers"], state["tips"]
    forgers = state.setdefault("forgers", {})
    ns = e.get("ns", "")
    if ns == "Forge.Loop.ForgedBlock":
        d = e.get("data", {})
        prev = d.get("blockPrev")
        parents[d["block"]] = None if prev == "GenesisHash" else prev
        numbers[d["block"]] = d.get("blockNo")
        forgers[d["block"]] = pool
    elif ns.endswith(("AddedToCurrentChain", "SwitchedToAFork")):
        tip = e.get("data", {}).get("newtip")
        if tip:
            tips[pool] = tip.split("@")[0]
    elif e.get("event") == "forged":
        parents[e["hash"]] = e.get("prev")
        numbers[e["hash"]] = e.get("blockNo")
        forgers[e["hash"]] = pool
    elif e.get("event") == "extended":
        tips[pool] = e["block"]["hash"]
    elif e.get("event") == "rolledBack":
        to = e.get("to", {})
        tips[pool] = None if to.get("origin") else to.get("hash")


def ancestry(state, tip):
    """The chain from TIP back to genesis, as a list of hashes, or None if a parent is not known yet."""
    parents, chain, seen = state["parents"], [], set()
    block = tip
    while block is not None:
        if block not in parents or block in seen:
            return None
        chain.append(block)
        seen.add(block)
        block = parents[block]
    return chain


def depths(state):
    chains = {pool: ancestry(state, tip) for pool, tip in state["tips"].items() if tip}
    known = {pool: c for pool, c in chains.items() if c is not None}
    if not known:
        return {"depth": None, "pools": {pool: None for pool in chains}, "longest": None,
                "forgedOnLongest": {}, "forgedOnChain": {}, "blocks": len(state["parents"])}
    numbers = state["numbers"]
    longest_pool = max(known, key=lambda p: (numbers.get(known[p][0]) or 0, p))
    longest = set(known[longest_pool])
    pools = {}
    for pool, chain in chains.items():
        if chain is None:
            pools[pool] = None
            continue
        common = next(b for b in chain if b in longest) if any(b in longest for b in chain) else None
        tip_no = numbers.get(chain[0]) or 0
        pools[pool] = tip_no - ((numbers.get(common) or 0) if common else -1)
    known_depths = [d for d in pools.values() if d is not None]
    forgers = state.get("forgers", {})

    def forged_on(chain):
        counts = {}
        for block in chain:
            forger = forgers.get(block)
            if forger:
                counts[forger] = counts.get(forger, 0) + 1
        return counts

    return {"depth": max(known_depths) if known_depths else None, "pools": pools,
            "longest": longest_pool, "forgedOnLongest": forged_on(known[longest_pool]),
            "forgedOnChain": {pool: forged_on(chain) for pool, chain in known.items()},
            "blocks": len(state["parents"])}


def main():
    state_file = pathlib.Path(sys.argv[1])
    # Copies of the test commands run at once: the whole read, update and write is one transaction under a lock,
    # or an older state could replace a newer one.
    with open(state_file.with_suffix(".lock"), "w") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        update(state_file)


def update(state_file):
    try:
        state = json.loads(state_file.read_text())
    except (OSError, ValueError):
        state = {"parents": {}, "numbers": {}, "tips": {}, "offsets": {}}
    for log in sys.argv[2:]:
        if not os.path.exists(log):
            continue
        pool = pathlib.Path(log).stem
        lines, offset = read_new(log, state["offsets"].get(log, 0))
        for line in lines:
            absorb(state, pool, line)
        state["offsets"][log] = offset
    tmp = state_file.with_suffix(".tmp")
    tmp.write_text(json.dumps(state))
    tmp.replace(state_file)
    print(json.dumps(depths(state)))


if __name__ == "__main__":
    main()
