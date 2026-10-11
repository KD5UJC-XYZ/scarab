# SPDX-License-Identifier: Apache-2.0
"""The reference ledger of a Shelley..Babbage chain, projected onto the Scala node's `/state` shape.

cardano-cli 11.2.3 cannot print the ledger state before Conway, so the reference side comes from
the node's own LocalStateQuery answers (`ntcquery`): DebugNewEpochState (the ledger without its
UTxO), the whole UTxO, DebugChainDepState (nonces and opcert counters) and the ledger tip.
Protocol parameters come from `cardano-cli query protocol-parameters`, which works in every era.
"""
from fractions import Fraction

from . import eratx
from . import ntcquery

ERA_INDEX = {"shelley": 1, "allegra": 2, "mary": 3, "alonzo": 4, "babbage": 5, "conway": 6}

Q_LEDGER_TIP, Q_UTXO_WHOLE, Q_NEW_EPOCH_STATE, Q_CHAIN_DEP_STATE = 0, 7, 12, 13


def cred(c):
    """A CBOR credential `[0|1, hash]` (the first tuple form of decoded map keys) as the ledger's JSON key."""
    kind, h = c
    return f"{'keyHash' if kind == 0 else 'scriptHash'}-{h.hex()}"


def nonce(n):
    return n[1].hex() if n and n[0] == 1 else None


def ratio(f):
    f = Fraction(f)
    return f"{f.numerator}/{f.denominator}"


def tup(x):
    return ntcquery._hashable(x)


def untuple(x):
    return list(x) if isinstance(x, tuple) else x


def pool_params(p):
    """Pool state [vrf, pledge, cost, margin, [net, reward], owners, relays, metadata, deposit, delegators]
    or, for future parameters, plain parameters [operator, vrf, pledge, cost, margin, reward account, ...]."""
    if len(p) == 9:  # StakePoolParams: operator first, reward account as 29 raw bytes
        rw = p[5][1:] if isinstance(p[5], bytes) else p[5][1]
        p = [p[1], p[2], p[3], p[4], [0, rw], p[6]]
    return {"pledge": p[1], "cost": p[2], "margin": ratio(p[3]), "rewardAccount": p[4][1].hex(),
            "owners": sorted(o.hex() for o in p[5])}


def snapshot(s):
    stake, delegations = {}, {}
    for c, (coin, pool) in s[0].items():
        stake[cred(c)] = coin
        delegations[cred(c)] = pool.hex()
    return {"stake": stake, "delegations": delegations, "pools": sorted(p.hex() for p in s[1])}


def tx_out(o):
    """Output in either the legacy array form or the Babbage map form, as the Scala projection."""
    if isinstance(o, dict):
        address, v, datum, script = o.get(0), o.get(1), o.get(2), o.get(3)
    else:
        address, v, datum, script = o[0], o[1], o[2] if len(o) > 2 else None, None
        if datum is not None:
            datum = [0, datum]
    out = {"address": address.hex()}
    if isinstance(v, list):
        out["lovelace"] = v[0]
        assets = {p.hex(): {n.hex(): q for n, q in names.items() if q} for p, names in v[1].items()}
        assets = {p: n for p, n in assets.items() if n}
        if assets:
            out["assets"] = assets
    else:
        out["lovelace"] = v
    if datum is not None:
        if datum[0] == 0:
            out["datumHash"] = datum[1].hex()
        else:
            out["inlineDatum"] = datum[1].value.hex() if isinstance(datum[1], ntcquery.Tagged) else datum[1].hex()
    if script is not None:
        raw = script.value if isinstance(script, ntcquery.Tagged) else script
        kind, body = ntcquery.decode(raw)[0]
        payload = eratx.enc(_to_enc(body)) if kind == 0 else body
        out["scriptRef"] = eratx.blake2b(bytes([kind]) + payload, 28).hex()
    return out


def _to_enc(x):
    if isinstance(x, tuple):
        return [_to_enc(i) for i in x]
    if isinstance(x, list):
        return [_to_enc(i) for i in x]
    return x


def updates(m):
    """{genesis hash: {param index: canonical CBOR hex}} (the Scala side keeps each field's original bytes)."""
    out = {}
    for g, fields in m.items():
        out[g.hex()] = {str(i): eratx.enc(_cbor_value(v)).hex() for i, v in sorted(fields.items())}
    return out


def _cbor_value(v):
    if isinstance(v, Fraction):
        return eratx.Tag(30, [v.numerator, v.denominator])
    if isinstance(v, list):
        return [_cbor_value(i) for i in v]
    if isinstance(v, dict):
        return {k: _cbor_value(x) for k, x in v.items()}
    return v


def reference_pre_conway(era, session, protocol_params):
    """The comparable view of the acquired state. `session` is an acquired LocalStateQuery."""
    index = ERA_INDEX[era]
    tip = session.ledger_query(index, [Q_LEDGER_TIP])
    nes = session.ledger_query(index, [Q_NEW_EPOCH_STATE])
    utxo = session.ledger_query(index, [Q_UTXO_WHOLE])
    dep = session.ledger_query(index, [Q_CHAIN_DEP_STATE])
    epoch, before, current, es, ru, distr = nes[0], nes[1], nes[2], nes[3], nes[4], nes[5]
    (treasury, reserves), ls, snaps = es[0], es[1], es[2]
    cert, ust = ls[0], ls[1]
    pstate, dstate = cert[0], cert[1]
    accounts = {cred(c): {"balance": a[1], "deposit": a[2], "spool": a[3].hex() if a[3] else None, "drep": None}
                for c, a in dstate[0][0].items()}
    mir = dstate[3]
    govs = ust[3]
    future_gen = [{"slot": k[0], "genesisKey": k[1].hex(), "delegate": v[0].hex(), "vrf": v[1].hex()}
                  for k, v in dstate[1].items()]
    rewards = None
    if ru and len(ru) == 1:
        body = ru[0]
        r = body[1] if isinstance(body, list) and len(body) == 2 else body
        if isinstance(r, list) and len(r) >= 4:
            rs = {}
            pre_allegra = previous_major(govs, index) in (0, 1, 2)
            for c, entries in r[2].items():
                rs[cred(c)] = rs.get(cred(c), 0) + (aggregated_pre_allegra(entries) if pre_allegra
                                                    else sum(e[2] for e in entries))
            rewards = {"deltaT": r[0], "deltaR": -r[1], "deltaF": -r[3], "rs": {k: v for k, v in rs.items() if v}}
    view = {
        "epoch": epoch,
        "utxo": {f"{k[0].hex()}#{k[1]}": tx_out(v) for k, v in utxo.items()},
        "fees": ust[2], "deposited": ust[1], "treasury": treasury, "reserves": reserves,
        "accounts": accounts,
        "pools": {p.hex(): {"deposit": v[8], "vrf": v[0].hex()} for p, v in pstate[1].items()},
        "poolParams": {p.hex(): pool_params(v) for p, v in pstate[1].items()},
        "futurePools": {p.hex(): pool_params(v) for p, v in pstate[2].items()},
        "retiring": {p.hex(): e for p, e in pstate[3].items()},
        "dreps": {}, "numDormantEpochs": 0,
        "blocksCurrent": {p.hex(): n for p, n in current.items()},
        "blocksBefore": {p.hex(): n for p, n in before.items()},
        "snapshots": {"mark": snapshot(snaps[0]), "set": snapshot(snaps[1]), "go": snapshot(snaps[2]),
                      "fee": snaps[3]},
        "rewardUpdate": rewards,
        "stakeDistrib": {"total": distr[1], "pools": {p.hex(): v[1] for p, v in distr[0].items()}},
        "genDelegs": {g.hex(): {"delegate": v[0].hex(), "vrf": v[1].hex()} for g, v in dstate[2].items()},
        "futureGenDelegs": sorted(future_gen, key=lambda f: (f["slot"], f["genesisKey"])),
        "ppup": {"current": updates(govs[0]), "future": updates(govs[1])},
        "mir": {"reserves": {cred(c): v for c, v in mir[0].items() if v},
                "treasury": {cred(c): v for c, v in mir[1].items() if v},
                "deltaReserves": mir[2], "deltaTreasury": mir[3]},
        "params": params_view(protocol_params, index),
        "protocolState": chain_dep_state(dep, index),
    }
    return {"slot": tip[0], "hash": tip[1].hex()}, view


def previous_major(govs, index):
    """Protocol major version of the previous epoch's parameters (`sgsPrevPParams`), which decide how
    the reward update is applied; None when the layout is not the expected one."""
    try:
        pp = govs[3]
        return pp[14][0] if index < ERA_INDEX["babbage"] else pp[12][0]
    except (IndexError, TypeError, KeyError):
        return None


def aggregated_pre_allegra(entries):
    """Before Allegra an account keeps one reward of its set: `Set.deleteFindMin` under `Ord Reward`, where a
    leader reward (type 1) sorts before a member reward (type 0), then pools compare by id."""
    return min(entries, key=lambda e: (0 if e[0] == 1 else 1, e[1]))[2]


def params_view(pp, index):
    """cardano-cli's JSON, minus the cost models, with the Alonzo per-word cost under the Scala name."""
    out = {k: v for k, v in pp.items() if k != "costModels"}
    if index < ERA_INDEX["babbage"] and "utxoCostPerByte" in out:
        out["utxoCostPerWord"] = out.pop("utxoCostPerByte")
    return {k: _float(v) for k, v in out.items()}


def _float(v):
    if isinstance(v, float):
        return round(v, 10)
    if isinstance(v, dict):
        return {k: _float(x) for k, x in v.items()}
    return v


def chain_dep_state(dep, index):
    """TPraos (Shelley..Alonzo) or Praos (Babbage) chain-dependent state."""
    state = dep[1]
    if index < ERA_INDEX["babbage"]:
        state = state[1]
        counters = state[0][0]
        evolving, candidate = state[0][1], state[0][2]
        epoch_nonce, last_block = state[1][0], state[1][1]
        lab = state[2]
    else:
        counters, evolving, candidate, epoch_nonce, lab, last_block = state[1], state[2], state[3], state[4], state[6], state[7]
    return {"epochNonce": nonce(epoch_nonce), "evolvingNonce": nonce(evolving), "candidateNonce": nonce(candidate),
            "labNonce": nonce(lab), "lastEpochBlockNonce": nonce(last_block),
            "oCertCounters": {p.hex(): n for p, n in counters.items()}}
