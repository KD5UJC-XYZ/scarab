# SPDX-License-Identifier: Apache-2.0
"""The LocalStateQuery battery of the `ntc` scenario: the queries `cardano-cli conway query` sends,
built from the live state of the reference node, and the byte-for-byte comparison of the two nodes'
answers at one point.

Both nodes are asked for the state at the same block (the Scala node's volatile tip, acquired by
point on the reference), so the answers must be equal CBOR, not just equal after rendering.
"""
from . import ntcquery
from .cbor import Tag, enc
from .util import MAGIC

ERA_CONWAY = 6


def hfc(query):
    """A Conway block query: `[0, [0, [era, query]]]` (BlockQuery, QueryIfCurrent)."""
    return [0, [0, [ERA_CONWAY, query]]]


def sset(items):
    """A ledger set: tag 258 around the sorted elements."""
    return Tag(258, sorted(items, key=enc))


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


def universe(session):
    """Read the arguments' sources from an acquired session of the reference node."""
    def ask(q):
        return session.query_raw(hfc(q))[0]

    pools = _list(ask([16]))
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


def battery(u):
    """(label, query, implemented) for every query. `implemented` is False for the ones the Scala
    node answers with a closed connection (roadmap F1)."""
    cr = [list(c) for c in u.creds]
    some_creds = cr[:3]
    some_pools = u.pools[:2]
    q = []

    def add(label, query, implemented=True):
        q.append((label, hfc(query), implemented))

    q.append(("top:GetSystemStart", [1], True))
    q.append(("top:GetChainBlockNo", [2], True))
    q.append(("top:GetChainPoint", [3], True))
    q.append(("hfc:GetInterpreter", [0, [2, [0]]], True))
    q.append(("hfc:GetCurrentEra", [0, [2, [1]]], True))
    add("GetLedgerTip", [0])
    add("GetEpochNo", [1])
    add("GetCurrentPParams", [3])
    add("GetUTxOWhole", [7])
    add("GetUTxOByAddress", [6, sset(u.addresses[:2])])
    add("GetUTxOByAddress/none", [6, sset([b"\x61" + b"\x00" * 28])])
    add("GetUTxOByTxIn", [15, sset(u.txins[:3] + [[b"\x00" * 32, 0]])])
    add("GetGenesisConfig", [11])
    add("GetFilteredDelegationsAndRewardAccounts", [10, sset(some_creds)])
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
    add("GetDRepStakeDistr/all", [26, sset([])])
    add("GetDRepStakeDistr/some", [26, sset(u.drep_keys[:2] + [[2], [3]])])
    add("GetCommitteeMembersState/all", [27, sset([]), sset([]), sset([])])
    add("GetCommitteeMembersState/active", [27, sset([]), sset([]), sset([0])])
    add("GetFilteredVoteDelegatees/all", [28, sset([])])
    add("GetFilteredVoteDelegatees/some", [28, sset(some_creds)])
    add("GetAccountState", [29])
    add("GetSPOStakeDistr/all", [30, sset([])])
    add("GetSPOStakeDistr/some", [30, sset(u.pools[:1])])
    add("GetProposals/all", [31, sset([])])
    if u.actions:
        add("GetProposals/some", [31, sset(u.actions[:1])])
    add("GetRatifyState", [32])
    add("GetFuturePParams", [33])
    if u.pools:
        add("QueryStakePoolDefaultVote", [35, u.pools[0]])
    add("GetPoolDistr2/all", [36, []])
    add("GetPoolDistr2/some", [36, [sset(some_pools)]])
    add("GetStakeDistribution2", [37])
    add("GetMaxMajorProtocolVersion", [38])
    add("GetDRepDelegations/all", [39, sset([])])
    add("GetDRepDelegations/predefined", [39, sset([[2], [3]])])
    add("GetCBOR/GetStakeDistribution2", [9, [37]])
    add("GetCBOR/GetCurrentPParams", [9, [3]])
    add("GetCBOR/GetUTxOWhole", [9, [7]])
    add("DebugChainDepState", [13])
    add("GetCBOR/DebugChainDepState", [9, [13]])
    # not answered yet
    add("DebugNewEpochState", [12], False)
    add("GetCBOR/DebugNewEpochState", [9, [12]], False)
    add("GetRewardInfoPools", [18], False)
    add("GetNonMyopicMemberRewards", [2, sset([[0, 1000000000]])], False)
    add("GetLedgerPeerSnapshot", [34, 0], False)
    return q


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


def compare_round(name, ref_socket, scala_socket, queries_for=battery, universe_of=universe, dump_dir=None):
    """One round: acquire the Scala node's volatile tip on both nodes, ask the whole battery.
    Returns (point, [(label, implemented, matched, reference_length, scala_length)]). The two answers
    of a query that differs are written to `dump_dir` when it is given."""
    with ntcquery.LocalStateQuery(None, scala_socket, MAGIC, host=True) as probe:
        probe.acquire()
        point = tip_point(probe)
    results = []
    with ntcquery.LocalStateQuery(f"{name}-ref", ref_socket, MAGIC) as ref:
        ref.acquire(point)
        qs = queries_for(universe_of(ref))
        for label, query, implemented in qs:
            expected = ask(ref, query)
            # one connection per query: the Scala node ends the connection on a query it does not answer
            try:
                with ntcquery.LocalStateQuery(None, scala_socket, MAGIC, host=True) as s:
                    s.acquire(point)
                    got = ask(s, query)
            except (RuntimeError, EOFError, OSError):
                got = None
            matched = expected is not None and expected == got
            if not matched and implemented and dump_dir is not None and expected is not None and got is not None:
                stem = f"diff-{label.replace('/', '_')}-{point[0]}"
                (dump_dir / f"{stem}.reference").write_bytes(expected)
                (dump_dir / f"{stem}.scala").write_bytes(got)
            results.append((label, implemented, matched, len(expected or b""), len(got or b"")))
    return point, results
