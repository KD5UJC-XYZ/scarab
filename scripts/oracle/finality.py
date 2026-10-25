# SPDX-License-Identifier: Apache-2.0
"""Which epoch-boundary digests the devnet oracle may compare: those whose block is on both final chains.

A digest is named `<slot>-<header hash>.json` (`StateDump.write`), and "the same block" means the same header
hash on both sides, never the same slot or block number. Both sides hash the header's original bytes: Scarab's
`Header.hash` is Blake2b-256 of the raw header (`ledger/.../Header.scala`), the reference's `ShelleyHash` is
`headerHash hdr` ([consensus `82ecba329`, Shelley/Protocol/Abstract.hs:74-76](https://github.com/IntersectMBO/ouroboros-consensus/blob/82ecba329d7d054340bf707d44fe6e9ac27cec40/ouroboros-consensus-cardano/src/shelley/Ouroboros/Consensus/Shelley/Protocol/Abstract.hs#L74-L76),
[Praos.hs:49](https://github.com/IntersectMBO/ouroboros-consensus/blob/82ecba329d7d054340bf707d44fe6e9ac27cec40/ouroboros-consensus-cardano/src/shelley/Ouroboros/Consensus/Shelley/Protocol/Praos.hs#L49)).
A header hash commits to the whole chain before it, so the two states after one hash are states of one chain.

The reference's final chain is the ImmutableDB of reference node 1 (blocks at least `k` deep), listed by
`db-analyser --show-slot-block-no` ([Analysis.hs:239-244, 306-314](https://github.com/IntersectMBO/ouroboros-consensus/blob/82ecba329d7d054340bf707d44fe6e9ac27cec40/ouroboros-consensus-cardano/src/unstable-cardano-tools/Cardano/Tools/DBAnalyser/Analysis.hs#L306-L314)).
The follower's final chain is rebuilt from its log (`extended` and `rolledBack` lines). A digest whose block is
not on both chains is excluded with a reason, never dropped silently; a block one side holds at least `k` deep
and the other side does not is a divergence of the final chains, a failure.
"""
import enum
import pathlib
import re
from typing import NamedTuple


class Dump(NamedTuple):
    """One digest file of the follower (several may share a slot: a slot battle, or a fork)."""
    slot: int
    hash: str
    path: pathlib.Path


class Block(NamedTuple):
    slot: int
    hash: str


class Verdict(enum.Enum):
    """What the oracle does with one digest. The values are the names in the summary JSON."""
    COMPARE = "compare"  # on both final chains
    ROLLED_BACK = "rolledBack"  # neither final chain holds it: the follower dumped a block it later rolled back
    NOT_FINAL_ON_FOLLOWER = "notFinalOnFollower"  # the follower still held it, less than k deep, when it stopped
    NOT_YET_IMMUTABLE = "notYetImmutable"  # after the slot of the reference's immutable tip: not final there yet
    ROLLED_BACK_ON_FOLLOWER = "rolledBackOnFollower"  # immutable on the reference, the follower moved away < k ago
    DIVERGED = "diverged"  # one side holds it at least k deep, the other side's final chain does not: a failure

    @property
    def excluded(self):
        return self not in (Verdict.COMPARE, Verdict.DIVERGED)


class ChainRebuildError(Exception):
    """The follower's log does not describe one chain (a rollback to a point it never held)."""

    def __init__(self, point, line):
        super().__init__(f"the follower rolled back to {point}, which it does not hold (log event {line})")
        self.point, self.line = point, line


class FollowerChain:
    """The follower's chain at the end of its log: `extended` appends a block, `rolledBack` truncates to `to`."""

    def __init__(self, blocks):
        self.blocks = blocks
        self.position = {b.hash: i for i, b in enumerate(blocks)}

    def depth(self, block_hash):
        """Blocks after `block_hash` on the chain, or None when the chain does not hold it."""
        i = self.position.get(block_hash)
        return None if i is None else len(self.blocks) - 1 - i

    @property
    def tip(self):
        return self.blocks[-1] if self.blocks else None

    @staticmethod
    def from_events(events):
        blocks, position = [], {}
        for n, e in enumerate(events):
            kind = e.get("event")
            if kind == "extended":
                b = Block(int(e["block"]["slot"]), e["block"]["hash"])
                position[b.hash] = len(blocks)
                blocks.append(b)
            elif kind == "rolledBack":
                to = e["to"]
                keep = 0 if to.get("origin") else position.get(to.get("hash"))
                if keep is None:
                    raise ChainRebuildError(to, n)
                if not to.get("origin"):
                    keep += 1
                for b in blocks[keep:]:
                    del position[b.hash]
                del blocks[keep:]
        return FollowerChain(blocks)


SHOW_BLOCK = re.compile(r"BlockNo \(?(\d+)\)?\s+SlotNo \(?(\d+)\)?\s+.*?([0-9a-f]{64})")


def reference_chain(text):
    """The blocks of `db-analyser --show-slot-block-no` output (`BlockNo n<TAB>SlotNo s<TAB><hash>` per block)."""
    return [Block(int(m.group(2)), m.group(3)) for m in map(SHOW_BLOCK.search, text.splitlines()) if m]


def load(directory):
    """The digests under `directory`, one per file (names `<slot>-<hash>.json`), by slot."""
    out = []
    for f in sorted(pathlib.Path(directory).glob("*.json")):
        slot, _, block_hash = f.stem.partition("-")
        out.append(Dump(int(slot), block_hash, f))
    return out


def first_reference_block(blocks, slot):
    """The first block of `blocks` (in slot order) at or after `slot`, as JSON, or None."""
    b = next((b for b in blocks if b.slot >= slot), None)
    return None if b is None else {"slot": b.slot, "hash": b.hash}


def classify(dump, reference, immutable_tip, follower, k):
    """The verdict on one digest.

    reference: {hash: Block} of the reference's ImmutableDB; immutable_tip: the slot of its last block (None when
    it is empty); follower: the follower's final chain; k: the security parameter."""
    on_reference = dump.hash in reference
    depth = follower.depth(dump.hash)
    if on_reference:
        if depth is not None:
            return Verdict.COMPARE
        # the reference holds it at least k deep; the follower's tip is past it on another chain
        moved = [b for b in follower.blocks if b.slot > dump.slot]
        return Verdict.DIVERGED if len(moved) >= k else Verdict.ROLLED_BACK_ON_FOLLOWER
    if immutable_tip is None or dump.slot > immutable_tip:
        return Verdict.NOT_YET_IMMUTABLE
    if depth is None:
        return Verdict.ROLLED_BACK
    return Verdict.DIVERGED if depth >= k else Verdict.NOT_FINAL_ON_FOLLOWER


def epochs_without_match(rows):
    """The epochs that have digests and none of them compared and matched (a boundary the oracle did not check)."""
    epochs = {}
    for r in rows:
        if r.get("epoch") is not None:
            epochs.setdefault(r["epoch"], []).append(bool(r.get("matched")))
    return sorted(e for e, matched in epochs.items() if not any(matched))


SCOPE = {
    "kind": "epoch-boundary digests on both final chains",
    "issue": 91,
    "pairing": "the digest's header hash is in the ImmutableDB of reference node 1 and on the follower's final chain",
    "excluded": {v.value: doc for v, doc in (
        (Verdict.ROLLED_BACK, "neither final chain holds the block (a fork the follower dumped before it rolled back)"),
        (Verdict.NOT_FINAL_ON_FOLLOWER, "the reference's final chain does not hold it; the follower held it less "
                                        "than k deep when it stopped"),
        (Verdict.NOT_YET_IMMUTABLE, "after the reference's immutable tip when the comparison ran"),
        (Verdict.ROLLED_BACK_ON_FOLLOWER, "immutable on the reference; the follower moved to another chain less "
                                          "than k blocks before it stopped"))},
    "failures": ["any difference or error in a compared digest", "a block one side holds at least k deep and the "
                 "other side's final chain does not", "no digest compared and matched"],
}


def summarize(results, digests, k, immutable_tip, follower_tip):
    """(summary, failures) over the rows of one oracle run (one row per digest, with its `verdict`)."""
    compared = [r for r in results if r["verdict"] == Verdict.COMPARE.value]
    excluded = [r for r in results if Verdict(r["verdict"]).excluded]
    diverged = [r for r in results if r["verdict"] == Verdict.DIVERGED.value]
    matched = sum(1 for r in compared if r.get("matched"))
    by_reason = {}
    for r in excluded:
        by_reason[r["verdict"]] = by_reason.get(r["verdict"], 0) + 1
    summary = {"scope": SCOPE, "digests": digests, "compared": len(compared), "matched": matched,
               "mismatched": sum(1 for r in compared if r.get("matched") is False and "error" not in r),
               "errors": sum(1 for r in compared if "error" in r), "diverged": len(diverged),
               "excluded": len(excluded), "excludedByReason": by_reason,
               "exclusions": [{f: r.get(f) for f in ("slot", "hash", "epoch", "verdict", "followerDepth",
                                                     "referenceBlock")} for r in excluded],
               "epochsWithoutMatch": epochs_without_match(results),
               "referenceImmutableTip": immutable_tip,
               "followerTip": follower_tip._asdict() if follower_tip else None,
               "securityParam": k, "results": results}
    failures = []
    bad = [r for r in compared if not r.get("matched")]
    if bad:
        failures.append(f"oracle: {len(bad)} of {len(compared)} compared epoch-boundary states differ from the "
                        f"Haskell ledger or could not be compared (first: {bad[0]})")
    if diverged:
        failures.append(f"oracle: {len(diverged)} digests are of blocks that one side holds at least {k} deep and "
                        f"the other side's final chain does not (first: {diverged[0]})")
    if not matched:
        failures.append(f"oracle: no epoch-boundary state was compared and matched ({digests} digests, "
                        f"{len(excluded)} excluded: {by_reason})")
    if len(results) != digests:
        failures.append(f"oracle: {digests} digests but {len(results)} rows")
    return summary, failures
