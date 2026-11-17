# SPDX-License-Identifier: Apache-2.0
"""LocalTxMonitor in each Shelley-based era of the `e2e` walk (#934).

Once per era, from a wallet of the load, on the Scala follower's local socket and on the reference's:

* a payment of the era, submitted to both, is listed by `NextTx` under the era's index, and the two nodes list the
  same bytes (`tip`);
* from Allegra, a payment written as the era before writes it and submitted under that era's index is taken by both
  and listed under the current era, as the reference's mempool translates it, again the same bytes on both
  (`earlier`);
* before Conway, a payment of the next era, under its index, is refused by both, with the same reply
  (`later`): the hard-fork combinator's era mismatch.

Each snapshot is acquired right after its submission, before a block can take the transaction; a transaction that a
node does not list (a block took it first) is retried once with a new payment, and otherwise the check is not
observed (`notObserved`): incomplete, never a pass (CNF-2). The walk waits for the reference to take each payment in a
block, so that the load's next transactions see its output spent.
"""
from . import compare, eratx
from .ntcquery import decode
from .eraload import MAGIC
from .ntclocal import Session, raw_tx_id

ORDER = ["shelley", "allegra", "mary", "alonzo", "babbage", "conway"]
INDEX = {era: i + 1 for i, era in enumerate(ORDER)}
DONE = b"\x81\x06"  # MsgReplyNextTx with no transaction


def listed(session):
    """The `NextTx` items of a fresh snapshot of `session`, each the raw `[era, #6.24(tx)]`, by transaction id."""
    session.acquire_snapshot()
    items = {}
    while True:
        raw = session.next_tx()
        if raw == DONE:
            break
        item = raw[2:]
        value, _ = decode(item, 0)
        items[raw_tx_id(value[1].value).hex()] = item
    session.release_snapshot()
    return items


def item_era(item):
    """The era index of a `NextTx` item."""
    value, _ = decode(item, 0)
    return value[0]


def judge(era, kind, txid, scala, reference, reply_scala=None, reply_reference=None):
    """The verdict of one check (`tip`, `earlier` or `later`) as a dict: `passed`, `failed` with its problems, or
    `notObserved` (a node did not list the transaction). `scala` and `reference` are the items each listed by id."""
    row = {"era": era, "kind": kind, "tx": txid}
    problems = []
    if kind == "later":
        if reply_scala is None or reply_reference is None:
            problems.append(f"accepted: Scala {reply_scala is None}, reference {reply_reference is None}")
        elif reply_scala != reply_reference:
            problems.append(f"the replies differ: Scala {reply_scala.hex()}, reference {reply_reference.hex()}")
        row["reply"] = None if reply_reference is None else reply_reference.hex()
    else:
        ours, theirs = scala.get(txid), reference.get(txid)
        if ours is None or theirs is None:
            row.update(verdict="notObserved", scalaListed=ours is not None, referenceListed=theirs is not None)
            return row
        row["listedEra"] = item_era(theirs)
        if ours != theirs:
            problems.append(f"the listed bytes differ: Scala {ours.hex()[:200]}, reference {theirs.hex()[:200]}")
        row["itemBytes"] = len(theirs)
    row["verdict"] = "failed" if problems else "passed"
    if problems:
        row["problems"] = problems
    return row


def payment(load, era, wallet, written):
    """A payment of `wallet` to itself written as `written` writes it, in the chain's era `era`, not submitted: its
    bytes and id, or None."""
    return load.act(era, f"monitor-{written}-in-{era}", wallet, [(wallet.address, 2_000_000, None, {})],
                    build_only=written)


def exchange(scala_socket, reference_socket, name, index, tx):
    """Submit `tx` under the era index `index` to the Scala node, then to the reference, each followed at once by a
    snapshot: the two replies (None for an acceptance) and the two listings."""
    with Session(None, scala_socket, MAGIC, host=True) as s, \
            Session(f"{name}-ref", reference_socket, MAGIC) as r:
        reply_scala = s.submit(tx, index)
        scala = listed(s)
        reply_reference = r.submit(tx, index)
        reference = listed(r)
    return reply_scala, reply_reference, scala, reference


def walk(load, era, scala_socket, reference_socket=None):
    """The checks of `era` (a name of `ORDER`), appended to `load.monitor_evidence`."""
    reference_socket = reference_socket or compare.reference_socket()
    wallet = load.wallets[0]
    position = ORDER.index(era)
    checks = [("tip", era)]
    if position > 0:
        checks.append(("earlier", ORDER[position - 1]))
    if era != "conway":
        checks.append(("later", ORDER[position + 1]))
    for kind, written in checks:
        row = None
        for _ in range(2):
            # a later era's transaction is one of the chain's own once the chain has entered that era
            if load.reading().get("era", "").lower() != era:
                row = {"era": era, "kind": kind, "verdict": "notObserved", "reason": "the chain left the era"}
                break
            built = payment(load, era, wallet, written)
            if built is None:
                row = {"era": era, "kind": kind, "verdict": "notObserved", "reason": "no payment could be built"}
                break
            tx, txid = built
            reply_scala, reply_reference, scala, reference = exchange(
                scala_socket, reference_socket, load.name, INDEX[written], tx)
            row = judge(era, kind, txid, scala, reference, reply_scala, reply_reference)
            if kind != "later":
                load.confirm(txid)
            if row["verdict"] != "notObserved":
                break
        load.monitor_evidence.append(row)
        load.log({"monitor": row})


def kinds(era):
    """The checks of `era`."""
    return {"tip"} | ({"earlier"} if era != "shelley" else set()) | ({"later"} if era != "conway" else set())


def failures(evidence):
    """The run's failures of the walk: each failed check."""
    return [f"LocalTxMonitor {r['era']} {r['kind']}: {'; '.join(r.get('problems', []))}"
            for r in evidence if r["verdict"] == "failed"]


def incomplete(evidence, eras):
    """Each era of `eras` (the Shelley-based ones the run walked) with a check of its kinds that never passed: not a
    failure, and not a pass either (CNF-2)."""
    out = []
    for era in eras:
        passed = {r["kind"] for r in evidence if r["era"] == era and r["verdict"] == "passed"}
        if kinds(era) - passed:
            out.append(f"LocalTxMonitor {era}: no passed check of {', '.join(sorted(kinds(era) - passed))}")
    return out
