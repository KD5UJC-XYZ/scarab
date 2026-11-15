# SPDX-License-Identifier: Apache-2.0
"""The LocalStateQuery battery of the `ntc` scenario and of the `e2e` era walk (`ntceras`): the queries
`cardano-cli conway query` sends, built from the live state of the reference node, and the byte-for-byte comparison
of the two nodes' answers at one point.

Both nodes are asked for the state at the same block (the Scala node's volatile tip, acquired by point on the
reference), so the answers must be equal CBOR, not just equal after rendering. The block queries name the era of the
state the reference acquired at that point (`GetCurrentEra`), never another one.
"""
import concurrent.futures
import enum
import time
from typing import NamedTuple

from . import ledgercompare, ntcquery
from .cbor import Tag, enc
from .util import MAGIC

ERA_CONWAY = 6
# The block queries the reference's decoder refuses in an era before Conway, whatever their arguments: `requireCG`
# in `decodeShelleyQuery` (`ouroboros-consensus-cardano/src/shelley/Ouroboros/Consensus/Shelley/Ledger/Query.hs`
# 916-998, ouroboros-consensus 82ecba32). The Scala node refuses the same ones (`NtcQuery.shelleyLeaf`).
CONWAY_ONLY = frozenset({23, 25, 26, 27, 28, 30, 31, 32, 33, 35, 39})
# The block queries the Scala node answers in an era before Conway: the ledger states, the chain-dependent state and
# the UTxO (`QueryAnswers.inEveryEra`). It decodes the others there and ends the connection without an answer.
ANSWERED_BEFORE_CONWAY = frozenset({6, 7, 8, 12, 13, 15})
# Below this node-to-client version the protocol parameters and the genesis configuration have their legacy encodings
# (`currentPParamsEnDecoding`, `genesisConfigEnDecoding`, Query.hs 1098-1125, ouroboros-consensus 82ecba32:
# ShelleyNodeToClientVersion13 is V21); the battery asks them at V20 besides the version both nodes agree on.
LEGACY_VERSION = 20


def hfc(query, era=ERA_CONWAY):
    """A block query of the era `era`: `[0, [0, [era, query]]]` (BlockQuery, QueryIfCurrent)."""
    return [0, [0, [era, query]]]


def inner(query):
    """The query inside the `GetCBOR` layers (`[9, q]`) of a block query."""
    while query[0] == 9:
        query = query[1]
    return query


def inner_of(query):
    """The block query of a hard-fork query `[0, [0, [era, q]]]`, or a placeholder for any other query."""
    if isinstance(query, list) and len(query) == 2 and query[0] == 0 and isinstance(query[1], list) \
            and len(query[1]) == 2 and query[1][0] == 0 and isinstance(query[1][1], list):
        return query[1][1][1]
    return [None]


def answered(query, era):
    """Whether the Scala node answers the block query `query` in the era `era`: every one in Conway, and before it
    those of `ANSWERED_BEFORE_CONWAY`, with their `GetCBOR`."""
    return era == ERA_CONWAY or inner(query)[0] in ANSWERED_BEFORE_CONWAY


def conway_only(query, era):
    """Whether both nodes refuse the block query `query` at decode in the era `era` (`CONWAY_ONLY`)."""
    return era < ERA_CONWAY and inner(query)[0] in CONWAY_ONLY


def _order(x):
    """The `Ord` of an element of a plain set, as the reference's set decoder compares it (#709):
    a credential `[kind, hash]` puts a script (1) before a key (0) (`data Credential =
    ScriptHashObj | KeyHashObj`), an `Either` `[side, x]` its `Left` first, a hash its bytes
    unsigned; anything else its encoding."""
    if isinstance(x, list) and len(x) == 2 and x[0] in (0, 1) and isinstance(x[1], (bytes, bytearray)):
        return (0, 1 - x[0], bytes(x[1]))
    if isinstance(x, list) and len(x) == 2 and x[0] in (0, 1) and isinstance(x[1], (int, list)):
        return (1, x[0], _order(x[1]) if isinstance(x[1], list) else (0, x[1], b""))
    if isinstance(x, (bytes, bytearray)):
        return (2, 0, bytes(x))
    return (3, 0, enc(x))


def sset(items):
    """A set under tag 258, in the order a set read with cardano-binary's `fromCBOR` requires for
    the elements those queries take (credentials, `Either`s, key hashes; `_order`). A set read with
    the ledger's decoder takes its elements in any order, so its other elements (DReps, addresses,
    inputs) are ordered by encoding, not by their `Ord`."""
    return Tag(258, sorted(items, key=_order))


def eset(items, era=ERA_CONWAY):
    """A set read with the ledger's decoder at the era's lowest protocol version (`LC.fromEraCBOR @era`, `decodeSet`,
    `libs/cardano-ledger-binary/src/Cardano/Ledger/Binary/Decoding/Decoder.hs` 941-962, cardano-ledger f649f975): from
    Conway, version 9, as `sset`; before it a list without the tag, which the decoder does not permit there."""
    return sset(items) if era >= ERA_CONWAY else sorted(items, key=_order)


def cred(c):
    """A decoded credential key (kind, hash) as the list the queries take."""
    return [c[0], c[1]]


class Universe:
    """What the arguments of the queries are drawn from: the pools, stake credentials, DReps,
    addresses, inputs and governance actions of the reference chain."""

    def __init__(self, pools, creds, dreps, drep_keys, addresses, txins, actions, committee):
        self.pools, self.creds, self.dreps, self.drep_keys = pools, creds, dreps, drep_keys
        self.addresses, self.txins, self.actions, self.committee = addresses, txins, actions, committee


def _list(x):
    return x if isinstance(x, list) else list(x)


def universe(session, era=ERA_CONWAY):
    """Read the arguments' sources from an acquired session of the reference node, in the era `era` of its state.
    Before Conway the governance queries are refused (`CONWAY_ONLY`): the stake credentials are the registered ones
    (`GetStakeDelegDeposits` of the empty set answers every one) and there are no DReps, actions or committee."""
    def ask(q):
        return session.query_raw(hfc(q, era))[0]

    pools = _list(ask([16]))
    if era < ERA_CONWAY:
        creds = [cred(k) for k in ask([22, sset([])])]
        utxo = ask([7])
        return Universe(pools, sorted(set(map(tuple, creds)), key=repr), [], [], sorted({v[0] for v in utxo.values()}),
                        [[k[0], k[1]] for k in utxo], [], [])
    creds = [cred(k) for k in ask([28, sset([])])]
    deposits = [cred(k) for k in ask([22, sset([])])]
    dreps = [cred(k) for k in ask([25, sset([])])]
    drep_dist = [list(k) if isinstance(k, tuple) else k for k in ask([26, sset([])])]
    utxo = ask([7])
    addresses = sorted({v[0] for v in utxo.values()})
    txins = [[k[0], k[1]] for k in utxo]
    proposals = ask([31, sset([])])
    actions = [p[0] for p in proposals]
    committee = [cred(k) for k in ask([27, sset([]), sset([]), sset([])])[0]]
    return Universe(pools, sorted(set(map(tuple, creds + deposits)), key=repr), dreps, drep_dist,
                    addresses, txins, actions, committee)


def battery(u, era=ERA_CONWAY):
    """(label, query, implemented) for every query, its block queries of the era `era`, their arguments read with the
    ledger's decoder at its version (`eset`). `implemented` is False for the ones the Scala node does not answer in
    that era yet (`answered`): it ends the connection on them."""
    cr = [list(c) for c in u.creds]
    some_creds = cr[:3]
    some_pools = u.pools[:2]
    q = []

    def add(label, query):
        q.append((label, hfc(query, era), answered(query, era)))

    q.append(("top:GetSystemStart", [1], True))
    q.append(("top:GetChainBlockNo", [2], True))
    q.append(("top:GetChainPoint", [3], True))
    q.append(("hfc:GetInterpreter", [0, [2, [0]]], True))
    q.append(("hfc:GetCurrentEra", [0, [2, [1]]], True))
    add("GetLedgerTip", [0])
    add("GetEpochNo", [1])
    add("GetCurrentPParams", [3])
    add("GetUTxOWhole", [7])
    add("GetUTxOByAddress", [6, eset(u.addresses[:2], era)])
    add("GetUTxOByAddress/none", [6, eset([b"\x61" + b"\x00" * 28], era)])
    add("GetUTxOByTxIn", [15, eset(u.txins[:3] + [[b"\x00" * 32, 0]], era)])
    add("GetGenesisConfig", [11])
    add("GetFilteredDelegationsAndRewardAccounts", [10, eset(some_creds, era)])
    add("GetStakePools", [16])
    add("GetStakePoolParams", [17, sset(some_pools)])
    add("GetPoolState/all", [19, []])
    add("GetPoolState/some", [19, [sset(some_pools[:1])]])
    add("GetStakeSnapshots/all", [20, []])
    add("GetStakeSnapshots/some", [20, [sset(some_pools)]])
    add("GetStakeDelegDeposits/all", [22, sset([])])
    add("GetStakeDelegDeposits/some", [22, sset(some_creds)])
    add("GetConstitution", [23])
    add("GetGovState", [24])
    add("GetDRepState/all", [25, sset([])])
    add("GetDRepState/some", [25, sset(u.dreps[:2])])
    add("GetDRepStakeDistr/all", [26, eset([], era)])
    add("GetDRepStakeDistr/some", [26, eset(u.drep_keys[:2] + [[2], [3]], era)])
    add("GetCommitteeMembersState/all", [27, sset([]), sset([]), eset([], era)])
    add("GetCommitteeMembersState/active", [27, sset([]), sset([]), eset([0], era)])
    add("GetFilteredVoteDelegatees/all", [28, eset([], era)])
    add("GetFilteredVoteDelegatees/some", [28, eset(some_creds, era)])
    add("GetAccountState", [29])
    add("GetSPOStakeDistr/all", [30, eset([], era)])
    add("GetSPOStakeDistr/some", [30, eset(u.pools[:1], era)])
    add("GetProposals/all", [31, eset([], era)])
    if u.actions:
        add("GetProposals/some", [31, eset(u.actions[:1], era)])
    add("GetRatifyState", [32])
    add("GetFuturePParams", [33])
    if u.pools:
        add("QueryStakePoolDefaultVote", [35, u.pools[0]])
    add("GetPoolDistr2/all", [36, []])
    add("GetPoolDistr2/some", [36, [sset(some_pools)]])
    add("GetStakeDistribution2", [37])
    add("GetMaxMajorProtocolVersion", [38])
    add("GetDRepDelegations/all", [39, eset([], era)])
    add("GetDRepDelegations/predefined", [39, eset([[2], [3]], era)])
    add("GetCBOR/GetStakeDistribution2", [9, [37]])
    add("GetCBOR/GetCurrentPParams", [9, [3]])
    add("GetCBOR/GetUTxOWhole", [9, [7]])
    add("DebugChainDepState", [13])
    add("GetCBOR/DebugChainDepState", [9, [13]])
    # Ledger-state answers participate in the same-point exact comparison.
    add("DebugEpochState", [8])
    add("DebugNewEpochState", [12])
    add("GetCBOR/DebugEpochState", [9, [8]])
    add("GetCBOR/DebugNewEpochState", [9, [12]])
    add("GetRewardInfoPools", [18])
    add("GetRewardProvenance", [14])
    add("GetCBOR/GetRewardProvenance", [9, [14]])
    add("GetNonMyopicMemberRewards", [2, sset([[0, 1000000000]])])
    add("GetNonMyopicMemberRewards/empty", [2, sset([])])
    members = [[0, 0], [0, 1], [0, 1000000000]] + [[1, c] for c in some_creds]
    members.append([1, [0, bytes(28)]])
    add("GetNonMyopicMemberRewards/mixed", [2, sset(members)])
    add("GetLedgerPeerSnapshot", [34, 0])
    add("GetLedgerPeerSnapshot/big", [34, 1])
    add("GetLedgerPeerSnapshot/legacy", [34])
    add("GetCBOR/GetRewardInfoPools", [9, [18]])
    add("GetCBOR/GetNonMyopicMemberRewards", [9, [2, sset(members)]])
    add("GetCBOR/GetLedgerPeerSnapshot", [9, [34, 0]])
    add("GetCBOR/GetLedgerPeerSnapshot/big", [9, [34, 1]])
    add("GetCBOR/GetLedgerPeerSnapshot/legacy", [9, [34]])
    return q


def legacy_battery(era):
    """(label, query, implemented) of the forms asked again at `LEGACY_VERSION`: the two whose encoding the version
    changes there, and their `GetCBOR`, which takes the latest encoding at every version (`answerPureBlockQuery`,
    Query.hs 423-429)."""
    return [(label, hfc(query, era), answered(query, era))
            for label, query in (("GetCurrentPParams", [3]), ("GetGenesisConfig", [11]),
                                 ("GetCBOR/GetCurrentPParams", [9, [3]]), ("GetCBOR/GetGenesisConfig", [9, [11]]))]


def tip_point(session):
    """The point of the acquired volatile tip."""
    return session.query_raw([3])


class Pair:
    """Sessions of the reference and the Scala node at the same point."""

    def __init__(self, reference, scala):
        self.reference, self.scala = reference, scala

    @staticmethod
    def reference_session(name, socket):
        return ntcquery.LocalStateQuery(f"{name}-ref", socket, MAGIC)

    @staticmethod
    def scala_session(socket):
        return ntcquery.LocalStateQuery(None, socket, MAGIC, host=True)


def ask(session, query):
    """The raw bytes of the answer, or None when the node closed the connection."""
    try:
        session._send(7, enc([3, query]))
        session._recv(7)
        return session.last_raw
    except (EOFError, OSError, BrokenPipeError, ConnectionError, ValueError):
        return None


# How a node's connection ends when it does not answer a query: the end of its stream, read or written.
CLOSED = (EOFError, BrokenPipeError, ConnectionResetError)
DEADLINE = 120  # seconds a session of a round may last, from its handshake to its last answer
# Sessions acquired at once: the length of a node's queue of connections not yet accepted, 8 on the reference's local
# socket (`localSnocket`, `ouroboros-network/framework/lib/Ouroboros/Network/Snocket.hs` 578, ouroboros-network
# c45735a5) and on the Scala node's (`LocalSocket.Backlog`). A connection a full queue refuses (EAGAIN) is tried again
# after `CONNECT_PAUSE`, at most `CONNECT_TRIES` times.
ACQUIRE_WORKERS = 8
CONNECT_TRIES = 50
CONNECT_PAUSE = 0.02


class PointLost(RuntimeError):
    """A round's sessions could not all be acquired at its point, or ran out: the round is not judged."""


class Incomparable(RuntimeError):
    """The two nodes cannot be compared at the round's point: their states there are of different eras, or not of a
    Shelley-based one, or they agreed on different node-to-client versions. No block query is sent."""


class AtPoint:
    """The sessions of one node at one point and node-to-client version, acquired before the first query is sent.

    A point can be acquired only while it is among the node's last `k` blocks, and an acquired session keeps its state
    however long it is held; a node ends the connection on a query it does not answer. So a round acquires, from the
    start, a session for the first query, one for each query the node is expected to end a connection on (`spares`)
    and one for a query it ends one on unexpectedly (`wanted`); it takes the next session only when the one before
    ended, and acquires another late only when they run out, which a point no longer held makes `PointLost`. Only the
    end of a connection after a query was sent on an acquired session is the node's refusal (None from `ask`);
    anything else (a deadline, a malformed reply) is an error of the round."""

    def __init__(self, opener, point, spares=0, first=None):
        self.opener, self.point, self.spares = opener, point, spares
        self.live = [] if first is None else [first]
        self.late = 0

    @property
    def wanted(self):
        return 2 + self.spares

    def connected(self):
        """A new session of the node, its handshake done."""
        for _ in range(CONNECT_TRIES - 1):
            session = self.opener()
            try:
                return session.__enter__()
            except BlockingIOError:
                time.sleep(CONNECT_PAUSE)
        return self.opener().__enter__()

    def acquired(self):
        """A new session of the node acquired at the point."""
        session = self.connected()
        try:
            session.acquire(self.point)
        except BaseException:
            session.__exit__(None, None, None)
            raise
        return session

    def session(self):
        """The session the next query goes to: the first one left, or one acquired late."""
        if not self.live:
            self.late += 1
            try:
                self.live.append(self.acquired())
            except Exception as e:
                raise PointLost(f"no session left at {self.point!r}, and none acquired again: {e!r}") from e
        return self.live[0]

    def ask(self, query):
        """The raw bytes of the node's `MsgResult` for `query`, or None when it ended the connection on the query.
        A session that had ended before the query was sent is not the query's refusal: the next one is asked."""
        while True:
            session = self.session()
            try:
                session._send(7, enc([3, query]))
            except CLOSED:
                self.live.pop(0).__exit__(None, None, None)
                continue
            try:
                session._recv(7)
            except ntcquery.Deadline:
                raise
            except CLOSED:
                self.live.pop(0).__exit__(None, None, None)
                return None
            ntcquery.result_offset(session.last_raw)
            return session.last_raw

    def close(self):
        for session in self.live:
            session.__exit__(None, None, None)
        self.live = []


def acquire_all(sets):
    """Acquire the sessions every `AtPoint` of `sets` still wants, all at once; `PointLost`, with every session
    closed, when one could not be."""
    with concurrent.futures.ThreadPoolExecutor(max_workers=ACQUIRE_WORKERS) as pool:
        pending = [(s, pool.submit(s.acquired)) for s in sets for _ in range(s.wanted - len(s.live))]
    errors = []
    for s, future in pending:
        try:
            s.live.append(future.result())
        except Exception as e:  # each refusal, deadline or connection error of an acquisition
            errors.append(e)
    if errors:
        for s in sets:
            s.close()
        raise PointLost(f"{len(errors)} of {len(pending)} sessions not acquired: {errors[0]!r}") from errors[0]


class Row(NamedTuple):
    """One query of a round: its answers (None when the node ended the connection on it), the version they were asked
    at, whether the Scala node answers it in the round's era (`implemented`) and whether both nodes refuse it at
    decode there (`refused`)."""
    label: str
    version: int
    implemented: bool
    refused: bool
    reference: bytes | None
    scala: bytes | None


class Round(NamedTuple):
    """A round: its point, the era of both nodes' states there, the version they agreed on, its rows, and the seconds
    from the probe's acquisition to the last session's (`acquired`) and to the last answer (`answered`)."""
    point: list
    era: int
    version: int
    rows: list
    seconds: dict = {}


class Outcome(enum.Enum):
    """How one row of a round compares."""
    MATCHED = "matched"  # both answered, with the same bytes
    DIFFERS = "differs"  # both answered, with other bytes
    INCOMPLETE = "incomplete"  # only the reference answered, a query the Scala node does not answer yet
    REFUSED_BY_BOTH = "refusedByBoth"  # both ended the connection on a query both refuse in this era
    SCALA_REFUSED = "scalaRefused"  # the Scala node ended the connection on a query it answers
    SCALA_ANSWERED = "scalaAnswered"  # the Scala node answered a query the reference refused
    REFERENCE_REFUSED = "referenceRefused"  # the reference refused a query it answers in this era: a wrong query
    REFERENCE_ANSWERED = "referenceAnswered"  # the reference answered a query its decoder refuses in this era

    @property
    def passes(self):
        """A missing implementation passes as incomplete, never as a match; everything but these fails."""
        return self in (Outcome.MATCHED, Outcome.INCOMPLETE, Outcome.REFUSED_BY_BOTH)


def judge(row):
    """(Outcome, ledger-state verdict or None) of one row; the ledger states are compared by `ledgercompare`, which
    also checks their framing."""
    if row.reference is None:
        if not row.refused:
            return Outcome.REFERENCE_REFUSED, None
        return (Outcome.REFUSED_BY_BOTH if row.scala is None else Outcome.SCALA_ANSWERED), None
    if row.refused:
        return Outcome.REFERENCE_ANSWERED, None
    if row.scala is None:
        return (Outcome.SCALA_REFUSED if row.implemented else Outcome.INCOMPLETE), None
    if ledgercompare.applies(row.label):
        verdict = ledgercompare.compare(row.label, row.reference, row.scala)
        return (Outcome.MATCHED if verdict.equal else Outcome.DIFFERS), verdict
    return (Outcome.MATCHED if row.reference == row.scala else Outcome.DIFFERS), None


def same_point(name, ref_socket, scala_socket, queries_for=battery, universe_of=universe, legacy=None):
    """One round: the Scala node's volatile tip, acquired on both nodes, and every query of `queries_for` (and of
    `legacy`, at `LEGACY_VERSION`) asked of both at that point. The era of its block queries is that of the
    reference's state at the point (`GetCurrentEra`), which the Scala node's must equal (`Incomparable`), and the
    arguments are read from the reference's state there (`universe_of`). Every session is acquired before the first
    query (`AtPoint`); `PointLost` when one cannot be. Returns a `Round`."""
    def reference(version=None):
        return ntcquery.LocalStateQuery(f"{name}-ref", ref_socket, MAGIC, version=version, deadline=DEADLINE)

    def scala(version=None):
        return ntcquery.LocalStateQuery(None, scala_socket, MAGIC, host=True, version=version, deadline=DEADLINE)

    current_era = [0, [2, [1]]]
    sets = []
    try:
        probe = scala()
        probe.__enter__()
        sets.append(AtPoint(scala, None, first=probe))
        probe.acquire()
        started = time.monotonic()
        probe.query_raw([3])
        point = ntcquery.chain_point(probe.last_raw)
        if point is None:
            raise PointLost("the Scala node's tip is Origin: no block to compare a state at")
        sets[0].point = point
        try:
            main = AtPoint(reference, point, first=reference().__enter__())
        except (RuntimeError, EOFError, OSError) as e:
            raise PointLost(f"the reference session did not start: {e!r}") from e
        sets.append(main)
        try:
            main.live[0].acquire(point)
        except ntcquery.AcquireRefused as e:
            raise PointLost(f"the reference refused {point!r}: {e.failure}") from e
        era, scala_era = main.live[0].query_raw(current_era), probe.query_raw(current_era)
        if era != scala_era or era not in range(1, ERA_CONWAY + 1):
            raise Incomparable(f"at {point!r} the reference's state is of era {era!r}, the Scala node's of {scala_era!r}")
        version = main.live[0].agreed_version
        if probe.agreed_version != version:
            raise Incomparable(f"the nodes agreed on node-to-client versions {version} and {probe.agreed_version}")
        groups = [(version, queries_for(universe_of(main.live[0], era), era), sets[0], main)]
        if legacy is not None:
            groups.append((LEGACY_VERSION, legacy(era), AtPoint(lambda: scala(LEGACY_VERSION), point),
                           AtPoint(lambda: reference(LEGACY_VERSION), point)))
        for _, forms, on_scala, on_reference in groups:
            on_scala.spares = sum(1 for _, q, implemented in forms if not implemented or conway_only(inner_of(q), era))
            on_reference.spares = sum(1 for _, q, _ in forms if conway_only(inner_of(q), era))
            sets.extend(s for s in (on_scala, on_reference) if s not in sets)
        acquire_all(sets)
        acquired = time.monotonic() - started
        rows = []
        for at, forms, on_scala, on_reference in groups:
            for label, query, implemented in forms:
                rows.append(Row(label, at, implemented, conway_only(inner_of(query), era),
                                on_reference.ask(query), on_scala.ask(query)))
        return Round(point, era, version, rows,
                     {"acquired": round(acquired, 3), "answered": round(time.monotonic() - started, 3)})
    finally:
        for s in sets:
            s.close()



def compare_round(name, ref_socket, scala_socket, queries_for=battery, universe_of=universe, dump_dir=None,
                  ledger_states=None):
    """One round of the `ntc` scenario (`same_point`). Returns (point, [(label, implemented, matched,
    reference_length, scala_length)]). The two answers of a query that differs are written to `dump_dir` when it is
    given. The ledger-state queries (`ledgercompare.LABELS`) require complete wire-byte equality; `ledger_states`,
    when given, collects per-label exact comparison counts (`ledgercompare.add_to`)."""
    rnd = same_point(name, ref_socket, scala_socket, queries_for, universe_of)
    point = rnd.point
    results = []
    for row in rnd.rows:
        outcome, verdict = judge(row)
        matched = outcome is Outcome.MATCHED
        if verdict is not None and ledger_states is not None:
            ledgercompare.add_to(ledger_states.setdefault(row.label, {}), verdict)
            if verdict.problems:
                ledger_states[row.label].setdefault("problems", []).append(verdict.problems)
        if not matched and row.implemented and dump_dir is not None and row.reference is not None \
                and row.scala is not None:
            stem = f"diff-{row.label.replace('/', '_')}-{point[0]}"
            (dump_dir / f"{stem}.reference").write_bytes(row.reference)
            (dump_dir / f"{stem}.scala").write_bytes(row.scala)
        results.append((row.label, row.implemented, matched, len(row.reference or b""), len(row.scala or b"")))
    return point, results
