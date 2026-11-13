# SPDX-License-Identifier: Apache-2.0
"""The Plutus load of the `pool` scenario (`pool --plutus-load`) and what the run requires of it.

Beside the payment load, rounds of the legacy V1 and V2 cases of `eraplutus.exercise` run against the observing
Haskell pool: per language a script that succeeds and one that fails (`error`), each submitted with the validity
flag it needs (the failing one with false, so its collateral is taken) and each with the opposite flag, which the
reference refuses (`ValidationTagMismatch`). The Scala pool's mempool receives the accepted ones from its Haskell
peers over TxSubmission and sets their flag by running the scripts with the run's evaluator (`MempoolState.admit`,
the reference's `DoNotIntervene`), so a wrong verdict there puts a wrong flag into a block the Scala pool forges,
which the Haskell pools refuse. The refused ones never leave the reference's mempool, so they are also submitted to
the Scala node's local socket, which must refuse them for the same rule.

After the run the chain is read from the observer (local ChainSync from Origin) and each transaction's flag is read
from the blocks as the Haskell pool holds them. Both variants of a case have one body and so one transaction id: a
transaction of a succeeding script must be in a block with the flag true, one of a failing script with the flag
false, and neither ever with the other flag."""
import dataclasses
import json
import threading
import time

from .. import eraload, eraplutus, ntclocal, ntcquery
from ..util import MAGIC, ref, spread

# Lovelace moved to the load's wallet once, from a delegator of a Haskell pool. A round of `exercise` costs about 30
# ada (per language 1.5 ada of fee for the success, 3 ada of collateral and 10 ada left at the failing script's
# address for the failure); 4000 ada lasts well past 100 rounds.
FUNDING = 4_000_000_000

# Submissions of a refused transaction to the Scala node while it refuses it for another rule than the reference
# did: it may not have adopted the block of the setup transaction yet.
SCALA_ATTEMPTS = 5

# What became of a Scala-forged block that is not on the observer's final chain, by the Haskell pools' own traces
# (`reference_tracing.log_view`): one named by an invalid-block trace was refused; one a pool's chain change named as
# its tip was validated and taken, and replaced later; of any other the pools said nothing (a slot battle lost before
# they validated it, or a block they never fetched): its verdict is not known.
REFUSED, ADOPTED, UNCONFIRMED = "refusedByHaskellPools", "adoptedByHaskellPools", "unconfirmed"

# Whether a case's script succeeds: its transaction belongs in a block with this flag.
SUCCEEDS = {"success": True, "reject-success-invalid": True, "collateral": False, "reject-failure-valid": False}
ACCEPTED = ("success", "collateral")


def failure_path(reason):
    """The constructor tags of the first predicate failure of a raw refusal (the reason bytes `ntcquery.submit`
    returns, `[[era, [failure, ...]]]`), from the LEDGER rule down to the first constructor that does not hold a
    single nested failure: `[1, 0, 0, 0]` is `ConwayUtxowFailure (UtxoFailure (UtxosFailure (ValidationTagMismatch
    ..)))`. None for an acceptance or for bytes that are not such a refusal."""
    if reason is None:
        return None
    try:
        failure = ntcquery.decode(reason, 0)[0][0][1][0]
    except (ntcquery.Incomplete, IndexError, KeyError, TypeError, ValueError):
        return None
    path = []
    while isinstance(failure, list) and failure and type(failure[0]) is int:
        path.append(failure[0])
        if len(failure) != 2:
            break
        failure = failure[1]
    return path


class Load(eraload.EraLoad):
    """`EraLoad` against the observing pool (`socket`, inside the reference container) that also has the Scala node
    (`scala_socket`, its local socket on the host) judge every transaction the reference refused."""

    def __init__(self, name, root, socket, scala_socket):
        self.out = (root / "plutus-load.log").open("a")
        super().__init__(name, lambda: ntcquery.LocalStateQuery(ref(name), socket, MAGIC, deadline=120),
                         log=self.write)
        self.scala_socket = scala_socket
        self.scala_refusals = []
        self.plutus_txids = set()  # every transaction with redeemers the load submitted, recorded before it went out

    def write(self, message):
        self.out.write(json.dumps(message) + "\n")
        self.out.flush()

    def submit(self, era, tx, label, expect=None):
        if ntclocal.has_redeemers(tx):
            self.plutus_txids.add(ntclocal.raw_tx_id(tx).hex())
        return super().submit(era, tx, label, expect)

    def expect_rejected(self, era, tx, label, expected, record=None, attempts=eraload.NO_VERDICT_ATTEMPTS):
        refused = super().expect_rejected(era, tx, label, expected, record, attempts)
        self.scala_judgement(tx, label)
        return refused

    def scala_judgement(self, tx, label):
        """The reference's raw refusal of `tx` over LocalTxSubmission, and the Scala node's, which must be a refusal
        for the same rule (`failure_path`); a refusal for another rule is asked again, up to `SCALA_ATTEMPTS` times.
        Each node's answer is timed from the request to the reply on an open connection (`referenceMs`, `scalaMs`):
        the mempool's admission of the transaction, its scripts run, which neither node times in a trace or metric.
        The reference's connection runs through `socat` in its container, the Scala node's is its socket on the host."""
        row = {"label": label, "tx": ntclocal.raw_tx_id(tx).hex()}
        try:
            with self.lsq_factory() as session:
                reference, row["referenceMs"] = timed(lambda: session.submit(tx))
            row["reference"] = failure_path(reference)
            for attempt in range(1, SCALA_ATTEMPTS + 1):
                with ntcquery.LocalStateQuery(None, self.scala_socket, MAGIC, host=True, deadline=60) as session:
                    scala, row["scalaMs"] = timed(lambda: session.submit(tx))
                row.update(attempts=attempt, accepted=scala is None, scala=failure_path(scala),
                           sameBytes=scala == reference)
                if scala is None or row["scala"] == row["reference"]:
                    break
                time.sleep(1)
            self.keep_tx("plutus-scala-refusals.jsonl", {**row, "tx": tx.hex(), "referenceReason": reference.hex()
                                                         if reference else None, "scalaReason": scala.hex() if scala else None})
        except (RuntimeError, EOFError, OSError) as e:  # recorded: the check fails on a judgement that did not end
            row["error"] = repr(e)[:400]
        self.scala_refusals.append(row)
        self.log({"scalaJudgement": row})


def timed(call):
    """(the result of `call()`, the milliseconds it took)."""
    started = time.perf_counter()
    result = call()
    return result, round((time.perf_counter() - started) * 1000, 3)


@dataclasses.dataclass(frozen=True)
class Evidence:
    """What the Plutus load recorded, taken by `Runner.finish` once its thread has ended: the `eraplutus` rows, the
    Scala node's judgements (`Load.scala_judgement`), the ids of the Plutus transactions it submitted, the rounds
    completed and failed, and how the thread ended (`ended`: the error that ended it early). `finished`: it ended within the deadline after the round under way;
    `stopped`: it ended at all. A load that had to be stopped mid-round, or that never stopped, is a failure of the
    run, and its evidence is what it had recorded by then."""
    rows: tuple
    refusals: tuple
    plutus_txids: frozenset
    passes: int
    errors: tuple
    ended: object
    finished: bool
    stopped: bool


class Runner:
    """The Plutus load's thread: it funds the load's wallet from stake delegator `funder`, then runs rounds of
    `round_` (`eraplutus.exercise`) on `load` until `finish`."""

    def __init__(self, load, funder, round_=None):
        self.load = load
        self.funder = funder
        self.round = round_ or eraplutus.exercise
        self.passes, self.errors, self.ended = 0, [], None
        self.finishing = threading.Event()
        self.thread = threading.Thread(target=self.run, daemon=True)

    @classmethod
    def against(cls, name, root, socket, scala_socket, funder):
        """A runner whose `Load` submits to the observing pool at `socket` and has the Scala node at `scala_socket`
        judge what the reference refuses."""
        # the load's cardano-cli submissions and queries go to the observing pool (pool 1's node may not exist)
        eraload.SOCKET = socket
        return cls(Load(name, root, socket, scala_socket), funder)

    def start(self):
        self.thread.start()
        return self

    def run(self):
        try:
            self.body()
        except BaseException as e:  # the thread's boundary: the run reports how it ended
            self.ended = repr(e)[:400]
            self.load.log({"plutusLoadEnded": self.ended})

    def body(self):
        load = self.load
        load.ready.set()
        funded = load.act("conway", "plutus-fund", load.delegator(self.funder),
                          [(load.wallets[0].address, FUNDING, None, {})])
        if not funded:
            raise RuntimeError("the Plutus load's wallet was not funded")
        while not self.finishing.is_set():
            try:
                self.round(load, "conway")
                self.passes += 1
                load.log({"plutusPass": self.passes})
            except (Exception, SystemExit) as e:  # recorded; the next round starts from fresh outputs
                self.errors.append(repr(e)[:400])
                load.log({"plutusPassFailed": repr(e)[:400]})
                time.sleep(2)

    def finish(self, timeout=180, stop_timeout=60):
        """End the rounds after the one under way and take the `Evidence`. Past `timeout` the load is asked to stop
        where it is (its `stop`), which cuts the round under way short; past `stop_timeout` more the thread is left
        to itself, and the evidence is what it had recorded by then."""
        self.finishing.set()
        self.thread.join(timeout)
        finished = not self.thread.is_alive()
        if not finished:
            self.load.stop.set()
            self.thread.join(stop_timeout)
        stopped = not self.thread.is_alive()
        self.load.stop.set()
        if stopped:
            self.load.out.close()
        return Evidence(rows=tuple(dict(r) for r in list(self.load.plutus_evidence)),
                        refusals=tuple(dict(r) for r in list(self.load.scala_refusals)),
                        plutus_txids=frozenset(self.load.plutus_txids), passes=self.passes,
                        errors=tuple(self.errors), ended=self.ended, finished=finished, stopped=stopped)


def read_chain(name, socket):
    """[(header hash hex, slot, block number, [(transaction id hex, valid, plutus)])] of the chain of the node at
    `socket` (inside the reference container), from Origin to its tip."""
    with ntclocal.Session(ref(name), socket, MAGIC, deadline=180) as session:
        return [(ident.hex(), slot, ntclocal.block_number(item), ntclocal.block_transactions(item))
                for slot, ident, item in ntclocal.chain_from_origin(session)]


def inclusion(rows, chain, scala_hashes):
    """Where the Plutus load's transactions are on the chain. `rows`: `eraplutus` evidence rows; `chain`: `read_chain`;
    `scala_hashes`: the blocks the Scala pool forged. Every occurrence of a row's transaction must carry the flag of its
    script (`SUCCEEDS`); those that do are counted by kind (`succeeding`, `flagFalse`) and language in Scala-forged and
    in other blocks."""
    flag = {r["txid"]: SUCCEEDS[r["case"]] for r in rows}
    language = {r["txid"]: r["language"] for r in rows}
    places = {}
    other = 0
    for block, _, _, txs in chain:
        for txid, valid, plutus in txs:
            if txid in flag:
                places.setdefault(txid, []).append((block, valid, block in scala_hashes))
            elif plutus and block in scala_hashes:
                other += 1

    def counted(scala):
        # only an occurrence with the right flag counts; one with the other flag is in `wrongFlag`
        found = [(t, flag[t]) for t, where in places.items() if any(s == scala and v == flag[t] for _, v, s in where)]
        by = {"succeeding": sum(1 for _, ok in found if ok), "flagFalse": sum(1 for _, ok in found if not ok)}
        by["byLanguage"] = {f"V{lang}": {"succeeding": sum(1 for t, ok in found if ok and language[t] == lang),
                                         "flagFalse": sum(1 for t, ok in found if not ok and language[t] == lang)}
                            for lang in sorted(set(language.values()))}
        return by

    accepted = sorted({r["txid"] for r in rows if r["case"] in ACCEPTED and r["passed"]})
    return {"submitted": {case: sum(1 for r in rows if r["case"] == case) for case in SUCCEEDS},
            "inScalaForgedBlocks": counted(True), "inOtherBlocks": counted(False),
            "otherPlutusInScalaForgedBlocks": other,
            "acceptedNotOnChain": [t for t in accepted if t not in places],
            "wrongFlag": [{"tx": t, "block": b, "valid": v, "scalaForged": s}
                          for t, where in places.items() for b, v, s in where if v != flag[t]]}


def lost_blocks(forged, chain, txids, refused, adopted):
    """Every Scala-forged block not on the observer's `chain`, with what became of it (`REFUSED` when a Haskell pool
    named it in an invalid-block trace, `refused`; `ADOPTED` when one took it as its tip, `adopted`; else
    `UNCONFIRMED`) and, for diagnosis, its slot, block number, the slot of the chain's block at its height and how
    much later that is, whether the chain holds its parent, whether it is later than the chain's tip (forged after
    the chain was read), and how many of the load's transactions (`txids`) it carried."""
    on = {block for block, _, _, _ in chain}
    at_height = {number: slot for _, slot, number, _ in chain}
    tip = max((slot for _, slot, _, _ in chain), default=None)
    lost = []
    for e in forged:
        if e["hash"] in on:
            continue
        other = at_height.get(e["blockNo"])
        lost.append({"hash": e["hash"], "class": REFUSED if e["hash"] in refused else ADOPTED if e["hash"] in adopted
                     else UNCONFIRMED, "slot": e["slot"], "blockNo": e["blockNo"], "chainSlotAtHeight": other,
                     "slotsLater": None if other is None else other - e["slot"],
                     "parentOnChain": e.get("prev") is None or e["prev"] in on,
                     "afterChainRead": tip is not None and e["slot"] > tip,
                     "plutusTxs": sum(1 for t in e.get("txs", ()) if t in txids)})
    return lost


WITH_PLUTUS, WITHOUT_PLUTUS, UNKNOWN = "withPlutus", "withoutPlutus", "unknown"


def block_kinds(forged, chain, evidence):
    """{hash: `WITH_PLUTUS`, `WITHOUT_PLUTUS` or `UNKNOWN`} of every Scala-forged block, from its own contents: a block
    on the observer's `chain` (or None) by its bytes, a transaction with redeemers; any other by its `forged` event's
    transaction ids against those of the Plutus transactions the load submitted (`evidence.plutus_txids`), unknown when
    the event lists none or the load's thread did not stop, so that its record may lack a submission."""
    on = {} if chain is None else {block: any(plutus for _, _, plutus in txs) for block, _, _, txs in chain}
    kinds = {}
    for e in forged:
        if e["hash"] in on:
            kinds[e["hash"]] = WITH_PLUTUS if on[e["hash"]] else WITHOUT_PLUTUS
        elif not isinstance(e.get("txs"), list):
            kinds[e["hash"]] = UNKNOWN
        elif set(e["txs"]) & evidence.plutus_txids:
            kinds[e["hash"]] = WITH_PLUTUS
        else:
            kinds[e["hash"]] = WITHOUT_PLUTUS if evidence.stopped else UNKNOWN
    return kinds


def reason_kind(reason):
    """A mempool refusal's text without the transaction id it starts with, cut before the details of a script failure:
    `violates ValidationTagMismatch: Utxow(Utxo(Utxos(ValidationTagMismatch(true` for every failing script."""
    words = reason.split(" ", 2)
    text = words[2] if len(words) == 3 and words[0] == "tx" else reason
    return text.split(",", 1)[0][:120]


def admission(refusals):
    """`spread` of the LocalTxSubmission round trips of `scala_judgement` (ms), per node and per case: the succeeding
    script with the flag false and the failing one with the flag true, each run in full before the refusal."""
    cases = {"succeedingScriptFlagFalse": "reject-success-invalid", "failingScriptFlagTrue": "reject-failure-valid"}
    return {node: {name: spread([r[key] for r in refusals if case in r["label"] and key in r], 3)
                   for name, case in cases.items()}
            for node, key in (("reference", "referenceMs"), ("scala", "scalaMs"))}


def mempool_view(events, rows, refusals):
    """What the Scala node's own log shows of the load: how many of the accepted transactions its mempool added, the
    load's transactions it refused by kind of reason (the local submissions of `scala_judgement` among them), and the
    scripts its ledger evaluated in the blocks it applied (`ledgerEpoch.phase2`)."""
    accepted = {r["txid"] for r in rows if r["case"] in ACCEPTED and r["passed"]}
    added = {e["tx"] for e in events if e["event"] == "mempoolAdded"} & accepted
    ours = {r["txid"] for r in rows}
    reasons = {}
    for e in events:
        if e["event"] == "mempoolRejected" and e.get("tx") in ours:
            kind = reason_kind(e.get("reason", ""))
            reasons[kind] = reasons.get(kind, 0) + 1
    phase2 = [e["phase2"] for e in events if e["event"] == "ledgerEpoch" and isinstance(e.get("phase2"), dict)]
    return {"accepted": len(accepted), "seenInScalaMempool": len(added),
            "localSubmissions": sum(r.get("attempts", 0) for r in refusals), "rejectedByReason": reasons,
            "scalaLedgerPhase2": {k: sum(p.get(k, 0) for p in phase2)
                                  for k in ("evaluatedSuccess", "evaluatedFailure", "scripts")}}


def check(evidence, chain, forged, events, refused=frozenset(), adopted=frozenset()):
    """(failures, summary) of the Plutus load once the chain has settled. `evidence`: `Runner.finish`; `chain`:
    `read_chain` of the observer; `forged`: the Scala node's `forged` events; `refused` and `adopted`: the blocks the
    Haskell pools' logs named as invalid and as their tip (`reference_tracing.log_view`)."""
    rows = evidence.rows
    placed = inclusion(rows, chain, {e["hash"] for e in forged})
    lost = lost_blocks(forged, chain, {r["txid"] for r in rows}, refused, adopted)
    by_class = {c: [b for b in lost if b["class"] == c] for c in (REFUSED, ADOPTED, UNCONFIRMED)}
    refusals = evidence.refusals
    no_verdict = [e for e in events if e["event"] == "blockNoVerdict"]
    summary = {"passes": evidence.passes, "passErrors": list(evidence.errors[:5]), "ended": evidence.ended,
               "finished": evidence.finished, "stopped": evidence.stopped,
               "protocolVersions": sorted({f"{r['referenceBefore']['protocolVersion']['major']}."
                                           f"{r['referenceBefore']['protocolVersion']['minor']}" for r in rows}),
               **placed,
               "scalaLocalSubmission": {"judged": len(refusals),
                                        "refusedForTheSameRule": sum(1 for r in refusals if not r.get("accepted")
                                                                     and "error" not in r and r["scala"] == r["reference"]),
                                        "sameBytes": sum(1 for r in refusals if r.get("sameBytes")),
                                        "accepted": [r for r in refusals if r.get("accepted")],
                                        "otherRuleOrError": [r for r in refusals if not r.get("accepted")
                                                             and ("error" in r or r["scala"] != r["reference"])][:5]},
               "mempool": mempool_view(events, rows, refusals), "admissionMs": admission(refusals),
               "blockNoVerdict": len(no_verdict), "lostScalaBlocks": lost,
               "lostScalaBlocksByClass": {c: len(b) for c, b in by_class.items()},
               "unconfirmedWithPlutus": sum(1 for b in by_class[UNCONFIRMED] if b["plutusTxs"])}
    failures = []
    if not evidence.stopped:
        failures.append("the Plutus load's thread did not stop when asked to; its evidence is what it had recorded")
    elif not evidence.finished:
        failures.append("the Plutus load did not end its round in time and was stopped in the middle of one")
    if evidence.ended:
        failures.append(f"the Plutus load ended: {evidence.ended}")
    if evidence.errors:
        failures.append(f"{len(evidence.errors)} Plutus rounds failed: {evidence.errors[0]}")
    if not evidence.passes:
        failures.append("no round of the Plutus load completed")
    failed_rows = [r for r in rows if not r["passed"]]
    if failed_rows:
        failures.append(f"{len(failed_rows)} Plutus cases did not pass: "
                        f"{[(r['case'], r['language'], r['errors']) for r in failed_rows[:3]]}")
    if placed["wrongFlag"]:
        failures.append(f"Plutus transactions in blocks with the other validity flag: {placed['wrongFlag'][:3]}")
    if placed["acceptedNotOnChain"]:
        failures.append(f"accepted Plutus transactions not on the chain: {placed['acceptedNotOnChain'][:3]}")
    if not placed["inScalaForgedBlocks"]["succeeding"]:
        failures.append("no Scala-forged block holds a transaction whose scripts succeed")
    if not placed["inScalaForgedBlocks"]["flagFalse"]:
        failures.append("no Scala-forged block holds a transaction with the validity flag false")
    judged = summary["scalaLocalSubmission"]
    if judged["accepted"]:
        failures.append(f"the Scala node accepted {len(judged['accepted'])} transactions the reference refused: "
                        f"{judged['accepted'][:2]}")
    if judged["otherRuleOrError"]:
        failures.append(f"the Scala node did not refuse a transaction for the reference's rule: "
                        f"{judged['otherRuleOrError'][:2]}")
    if not refusals:
        failures.append("no refused Plutus transaction was submitted to the Scala node")
    if by_class[REFUSED]:
        failures.append(f"the Haskell pools refused {len(by_class[REFUSED])} Scala-forged blocks: {by_class[REFUSED][:3]}")
    if no_verdict:
        failures.append(f"{len(no_verdict)} blockNoVerdict events: {no_verdict[:2]}")
    return failures, summary
