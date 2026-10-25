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
import json
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
    block_no: int


class Verdict(enum.Enum):
    """What the oracle does with one digest. The values are the names in the summary JSON."""
    COMPARE = "compare"  # on both final chains
    ROLLED_BACK = "rolledBack"  # neither final chain holds it: the follower's log shows it rolled the block back
    NOT_FINAL_ON_FOLLOWER = "notFinalOnFollower"  # the follower still held it, less than k deep, when it stopped
    NOT_YET_IMMUTABLE = "notYetImmutable"  # after the slot of the reference's immutable tip: not final there yet
    ROLLED_BACK_ON_FOLLOWER = "rolledBackOnFollower"  # immutable on the reference, rolled back in the follower's
    #                                                   volatile part, which can still adopt it
    DIVERGED = "diverged"  # the two final (immutable) chains disagree: a failure
    UNLOGGED = "unlogged"  # the follower's log never extended the digest's block: membership unknown, a failure

    @property
    def excluded(self):
        return self not in (Verdict.COMPARE, Verdict.DIVERGED, Verdict.UNLOGGED)


class Cause(enum.Enum):
    """Why a chain cannot be rebuilt from its evidence. The values are the names in the failure text."""
    MALFORMED_LINE = "malformedLine"  # a line of the log is neither JSON nor known non-chain output
    PARTIAL_LINE = "partialLine"  # the log ends inside a line
    MALFORMED_EVENT = "malformedEvent"  # a chain event lacks a field or has one of the wrong type
    BEFORE_START = "beforeStart"  # a chain event before the first `started` line
    DISCONTINUOUS = "discontinuous"  # an extension does not follow the tip (block number, slot)
    DUPLICATE_BLOCK = "duplicateBlock"  # an extension by a block the chain already holds
    ROLLBACK_FROM = "rollbackFrom"  # a rollback whose `from` is not the tip
    ROLLBACK_TO = "rollbackTo"  # a rollback to a point the chain does not hold
    ROLLBACK_DEPTH = "rollbackDepth"  # a rollback whose depth is not the number of blocks it removes
    INTERSECTION = "intersection"  # a session that does not start from Origin (a restored database), or a
    #                                reconnection whose intersection the chain does not hold
    NO_FINAL_TIP = "noFinalTip"  # the follower's final tip was not recorded
    FINAL_TIP = "finalTip"  # the rebuilt tip is not the follower's final tip
    REFERENCE_LISTING = "referenceListing"  # the reference's block listing is not one chain ending at its tip


class ChainRebuildError(Exception):
    """A chain's evidence does not describe one chain; the oracle fails instead of guessing."""

    def __init__(self, cause, line, detail):
        super().__init__(f"{cause.value} at line {line}: {detail}")
        self.cause, self.line, self.detail = cause, line, detail


CHAIN_EVENTS = {"extended", "rolledBack", "connected"}


def parse_log(text, benign):
    """[(line number, event)] of a follower log; a line that is neither JSON nor `benign(line)` (known
    non-chain output) and a last line without its newline raise ChainRebuildError."""
    lines = text.split("\n")
    if lines[-1]:
        raise ChainRebuildError(Cause.PARTIAL_LINE, len(lines), lines[-1][:200])
    out = []
    for n, line in enumerate(lines[:-1], start=1):
        if not line.strip():
            continue
        try:
            event = json.loads(line) if line.startswith("{") else None
        except ValueError:
            event = None
        if isinstance(event, dict):
            out.append((n, event))
        elif not benign(line):
            raise ChainRebuildError(Cause.MALFORMED_LINE, n, line[:200])
    return out


def block_of(n, field):
    """The Block of an event's `block` JSON, or ChainRebuildError."""
    try:
        slot, block_hash, block_no = field["slot"], field["hash"], field["blockNo"]
    except (KeyError, TypeError):
        raise ChainRebuildError(Cause.MALFORMED_EVENT, n, f"block {field}") from None
    if not (isinstance(slot, int) and isinstance(block_no, int) and isinstance(block_hash, str)
            and re.fullmatch(r"[0-9a-f]{64}", block_hash)):
        raise ChainRebuildError(Cause.MALFORMED_EVENT, n, f"block {field}")
    return Block(slot, block_hash, block_no)


def point_of(n, field):
    """None for Origin, else (slot, hash) of an event's point JSON, or ChainRebuildError."""
    if isinstance(field, dict) and field.get("origin") is True:
        return None
    try:
        slot, block_hash = field["slot"], field["hash"]
    except (KeyError, TypeError):
        raise ChainRebuildError(Cause.MALFORMED_EVENT, n, f"point {field}") from None
    if not (isinstance(slot, int) and isinstance(block_hash, str)):
        raise ChainRebuildError(Cause.MALFORMED_EVENT, n, f"point {field}")
    return slot, block_hash


def follows(tip, b, era):
    """True when `b` can be the block after `tip` (None: Origin): the next block number, a later slot. A Byron
    epoch boundary block and the first block of its epoch share a slot (the genesis one is block 0, its successor
    block 1, both at slot 0), so a Byron block may repeat its parent's slot."""
    if tip is None:
        return b.block_no == 0
    return b.block_no == tip.block_no + 1 and (b.slot > tip.slot or (era == "Byron" and b.slot == tip.slot))


class FollowerChain:
    """The follower's chain at the end of its log.

    Every `started` line begins a session from Origin (the devnet follower keeps no database between runs); its
    first `connected` must intersect at Origin, so a session restored from a database fails (its chain is not in
    the log). `extended` appends a block that must follow the tip, `rolledBack` removes `depth` blocks from the
    tip `from` back to `to`, and a later `connected` must intersect on the chain. Any other shape raises
    ChainRebuildError: membership is never guessed from incomplete evidence."""

    def __init__(self, blocks, seen):
        self.blocks = blocks
        self.position = {b.hash: i for i, b in enumerate(blocks)}
        self.seen = seen  # every block hash the log extended, in any session

    def depth(self, block_hash):
        """Blocks after `block_hash` on the chain, or None when the chain does not hold it."""
        i = self.position.get(block_hash)
        return None if i is None else len(self.blocks) - 1 - i

    @property
    def tip(self):
        return self.blocks[-1] if self.blocks else None

    def immutable_tip(self, k):
        """The newest block with at least `k` blocks after it: the anchor of the last `k` headers, the reference's
        immutable tip ([consensus `82ecba329`, ChainDB/Impl/Query.hs:100-125](https://github.com/IntersectMBO/ouroboros-consensus/blob/82ecba329d7d054340bf707d44fe6e9ac27cec40/ouroboros-consensus/src/ouroboros-consensus/Ouroboros/Consensus/Storage/ChainDB/Impl/Query.hs#L100-L125),
        `getCurrentChain`); None while the chain holds `k` blocks or fewer. The reference counts the volatile suffix by
        weight (`takeVolatileSuffix`), which is the block count while no Peras certificate adds weight, as on the
        devnet."""
        return self.blocks[-(k + 1)] if len(self.blocks) > k else None

    @staticmethod
    def from_events(events):
        """The chain of numbered events [(line number, event)] (see `parse_log`)."""
        blocks, position, seen = None, {}, set()
        connected = False
        for n, e in events:
            kind = e.get("event")
            if kind == "started":
                blocks, position, connected = [], {}, False
                continue
            if kind not in CHAIN_EVENTS:
                continue
            if blocks is None:
                raise ChainRebuildError(Cause.BEFORE_START, n, kind)
            tip = blocks[-1] if blocks else None
            if kind == "connected":
                at = point_of(n, e.get("intersection"))
                if (at is not None and at[1] not in position) or (at is None and blocks):
                    raise ChainRebuildError(Cause.INTERSECTION, n, f"intersection {e.get('intersection')}")
                connected = True
            elif kind == "extended":
                b = block_of(n, e.get("block"))
                if not connected:
                    raise ChainRebuildError(Cause.INTERSECTION, n, "an extension before the session connected")
                if b.hash in position:
                    raise ChainRebuildError(Cause.DUPLICATE_BLOCK, n, f"{b}")
                if not follows(tip, b, e.get("era")):
                    raise ChainRebuildError(Cause.DISCONTINUOUS, n, f"{b} after {tip}")
                position[b.hash] = len(blocks)
                blocks.append(b)
                seen.add(b.hash)
            else:
                origin, to = point_of(n, e.get("from")), point_of(n, e.get("to"))
                depth = e.get("depth")
                if tip is None or origin is None or origin != (tip.slot, tip.hash):
                    raise ChainRebuildError(Cause.ROLLBACK_FROM, n, f"from {e.get('from')}, tip {tip}")
                keep = 0 if to is None else position.get(to[1])
                if keep is None or (to is not None and blocks[keep].slot != to[0]):
                    raise ChainRebuildError(Cause.ROLLBACK_TO, n, f"to {e.get('to')}")
                if to is not None:
                    keep += 1
                if not isinstance(depth, int) or depth != len(blocks) - keep:
                    raise ChainRebuildError(Cause.ROLLBACK_DEPTH, n, f"depth {depth}, removes {len(blocks) - keep}")
                for gone in blocks[keep:]:
                    del position[gone.hash]
                del blocks[keep:]
        if blocks is None:
            raise ChainRebuildError(Cause.BEFORE_START, 0, "the log has no started line")
        return FollowerChain(blocks, seen)

    def check_final_tip(self, final):
        """ChainRebuildError unless the rebuilt tip is `final`, the follower's own last tip ({slot, hash}, or None
        when it was not recorded)."""
        if final is None:
            raise ChainRebuildError(Cause.NO_FINAL_TIP, 0, "no final tip of the follower was recorded")
        tip = self.tip
        if tip is None or (tip.slot, tip.hash) != (final.get("slot"), final.get("hash")):
            raise ChainRebuildError(Cause.FINAL_TIP, 0, f"the log ends at {tip}, the follower at {final}")


SHOW_BLOCK = re.compile(r"BlockNo \(?(\d+)\)?\s+SlotNo \(?(\d+)\)?\s+.*?([0-9a-f]{64})")


def reference_chain(text):
    """The blocks of `db-analyser --show-slot-block-no` output (`BlockNo n<TAB>SlotNo s<TAB><hash>` per block)."""
    return [Block(int(m.group(2)), m.group(3), int(m.group(1)))
            for m in map(SHOW_BLOCK.search, text.splitlines()) if m]


def check_reference(chain, tip_slot):
    """ChainRebuildError unless `chain` is one chain from genesis (block numbers step by one at most, as at a Byron
    epoch boundary block; slots never decrease) whose last block is at `tip_slot` (None: an empty ImmutableDB)."""
    if tip_slot is None:
        if chain:
            raise ChainRebuildError(Cause.REFERENCE_LISTING, 0, f"{len(chain)} blocks but no immutable tip")
        return
    if not chain or chain[0].block_no != 0 or chain[-1].slot != tip_slot:
        raise ChainRebuildError(Cause.REFERENCE_LISTING, 0,
                                f"{len(chain)} blocks ending at {chain[-1] if chain else None}, tip slot {tip_slot}")
    for i, (a, b) in enumerate(zip(chain, chain[1:]), start=1):
        if b.block_no - a.block_no not in (0, 1) or b.slot < a.slot or b.hash == a.hash:
            raise ChainRebuildError(Cause.REFERENCE_LISTING, i, f"{b} after {a}")


def final_chains_agree(reference, follower, k):
    """True unless the two immutable chains conflict. Both run from genesis, and a header hash fixes its whole
    ancestry, so they agree exactly when the follower's immutable tip is on the reference's chain or the reference's
    immutable tip is on the follower's chain (either one is then a prefix of the other).

    reference: the blocks of the reference's ImmutableDB, oldest first; follower: the FollowerChain."""
    mine = follower.immutable_tip(k)
    theirs = reference[-1] if reference else None
    if mine is None or theirs is None:
        return True
    return mine.hash in {b.hash for b in reference} or follower.depth(theirs.hash) is not None


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


def classify(dump, reference, immutable_tip, follower, k, agree):
    """The verdict on one digest.

    reference: {hash: Block} of the reference's ImmutableDB; immutable_tip: the slot of its last block (None when
    it is empty); follower: the follower's final chain; k: the security parameter; agree: `final_chains_agree`."""
    if dump.hash not in follower.seen:
        return Verdict.UNLOGGED
    depth = follower.depth(dump.hash)
    if dump.hash in reference:
        if depth is not None:
            return Verdict.COMPARE
        # immutable on the reference and rolled back by the follower: final only if the follower cannot adopt it
        return Verdict.ROLLED_BACK_ON_FOLLOWER if agree else Verdict.DIVERGED
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
    "pairing": "the digest's header hash is in the ImmutableDB of reference node 1 and on the follower's final chain, "
               "rebuilt from its whole log (one session from Origin per start, every event linked to the tip) and "
               "ending at the follower's recorded final tip",
    "excluded": {v.value: doc for v, doc in (
        (Verdict.ROLLED_BACK, "neither final chain holds the block: the follower's log shows it rolled the block back"),
        (Verdict.NOT_FINAL_ON_FOLLOWER, "the reference's final chain does not hold it; the follower held it less "
                                        "than k deep when it stopped"),
        (Verdict.NOT_YET_IMMUTABLE, "after the reference's immutable tip when the comparison ran"),
        (Verdict.ROLLED_BACK_ON_FOLLOWER, "immutable on the reference; the follower rolled it back within its "
                                          "volatile part (after its immutable tip), so it can still adopt it"))},
    "failures": ["any difference or error in a compared digest",
                 "final chains that disagree: the follower's immutable tip (k blocks deep) is not on the reference's "
                 "chain and the reference's immutable tip is not on the follower's",
                 "a digest of a block the follower's log never extended",
                 "a follower log or reference listing that is not one chain", "no digest compared and matched"],
}


def rendered(row):
    """A row as JSON: its verdict by name."""
    return {**row, "verdict": row["verdict"].value}


def summarize(results, digests, k, immutable_tip, follower_tip, agree):
    """(summary, failures) over the rows of one oracle run (one row per digest, its `verdict` a `Verdict`). The
    summary is JSON: verdicts appear by name only there."""
    compared = [r for r in results if r["verdict"] is Verdict.COMPARE]
    excluded = [r for r in results if r["verdict"].excluded]
    diverged = [r for r in results if r["verdict"] is Verdict.DIVERGED]
    unlogged = [r for r in results if r["verdict"] is Verdict.UNLOGGED]
    matched = sum(1 for r in compared if r.get("matched"))
    by_reason = {}
    for r in excluded:
        by_reason[r["verdict"].value] = by_reason.get(r["verdict"].value, 0) + 1
    summary = {"scope": SCOPE, "digests": digests, "compared": len(compared), "matched": matched,
               "mismatched": sum(1 for r in compared if r.get("matched") is False and "error" not in r),
               "errors": sum(1 for r in compared if "error" in r), "diverged": len(diverged),
               "unlogged": len(unlogged), "finalChainsAgree": agree,
               "excluded": len(excluded), "excludedByReason": by_reason,
               "exclusions": [{f: rendered(r).get(f) for f in ("slot", "hash", "epoch", "verdict", "followerDepth",
                                                               "referenceBlock")} for r in excluded],
               "epochsWithoutMatch": epochs_without_match(results),
               "referenceImmutableTip": immutable_tip,
               "followerTip": follower_tip._asdict() if follower_tip else None,
               "securityParam": k, "results": [rendered(r) for r in results]}
    failures = []
    bad = [r for r in compared if not r.get("matched")]
    if bad:
        failures.append(f"oracle: {len(bad)} of {len(compared)} compared epoch-boundary states differ from the "
                        f"Haskell ledger or could not be compared (first: {rendered(bad[0])})")
    if not agree:
        failures.append(f"oracle: the final chains disagree: the follower's immutable tip ({k} blocks deep) is not on "
                        f"the reference's ImmutableDB and the reference's immutable tip is not on the follower's chain")
    if diverged:
        failures.append(f"oracle: {len(diverged)} digests are of blocks one final chain holds and the other cannot "
                        f"adopt (first: {rendered(diverged[0])})")
    if unlogged:
        failures.append(f"oracle: {len(unlogged)} digests are of blocks the follower's log never extended "
                        f"(first: {rendered(unlogged[0])})")
    if not matched:
        failures.append(f"oracle: no epoch-boundary state was compared and matched ({digests} digests, "
                        f"{len(excluded)} excluded: {by_reason})")
    if len(results) != digests:
        failures.append(f"oracle: {digests} digests but {len(results)} rows")
    return summary, failures
