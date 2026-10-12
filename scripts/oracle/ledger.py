# SPDX-License-Identifier: Apache-2.0
"""The reference ledger state of a db-analyser snapshot, projected onto the Scala node's `/state` shape.

A snapshot directory holds `state` (the CBOR `ExtLedgerState` without the UTxO), `tables` (the UTxO,
see `mempack`) and `meta`. The ledger part follows the encodings of cardano-ledger (`EncCBOR` of
`NewEpochState` and below) as shipped in cardano-node 11.1.3; the pre-Conway layouts are the ones
`devnet/eraview.py` reads from LocalStateQuery, which this module reuses for the pieces they share.
Large maps are reduced to digests (`digest.reduce`) by the caller, the UTxO is streamed.
"""
import concurrent.futures
import json
import pathlib
import sys
from decimal import Decimal, ROUND_HALF_EVEN, getcontext
from fractions import Fraction

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))

from devnet import ntcquery  # noqa: E402
from . import digest, mempack  # noqa: E402

ERA_NAMES = ["byron", "shelley", "allegra", "mary", "alonzo", "babbage", "conway"]
VOTE = {0: "No", 1: "Yes", 2: "Abstain"}


# ---------------------------------------------------------------- small helpers

def cred(c):
    """A credential `[0|1, hash]` (decoded as a tuple key or a list) as the Scala `Credential.render`."""
    kind, h = c
    return f"{'keyHash' if kind == 0 else 'scriptHash'}-{h.hex()}"


def drep(d):
    d = tuple(d)
    if d[0] == 0:
        return f"drep-keyHash-{d[1].hex()}"
    if d[0] == 1:
        return f"drep-scriptHash-{d[1].hex()}"
    return "drep-alwaysAbstain" if d[0] == 2 else "drep-alwaysNoConfidence"


def nonce(n):
    return n[1].hex() if n and n[0] == 1 else None


def ratio(f):
    f = Fraction(f[0], f[1]) if isinstance(f, (list, tuple)) else Fraction(f)
    return f"{f.numerator}/{f.denominator}"


def double(f):
    """`Projection.double`: numerator / denominator in decimal64 (16 digits, half even), as a double."""
    f = Fraction(f[0], f[1]) if isinstance(f, (list, tuple)) else Fraction(f)
    getcontext().prec = 16
    getcontext().rounding = ROUND_HALF_EVEN
    return round(float(Decimal(f.numerator) / Decimal(f.denominator)), 10)


def strict_maybe(x):
    """`StrictMaybe` encoded as a list of zero or one elements."""
    return x[0] if x else None


def hexs(b):
    return b.hex()


# ---------------------------------------------------------------- protocol parameters

SHELLEY_PP = ["txFeePerByte", "txFeeFixed", "maxBlockBodySize", "maxTxSize", "maxBlockHeaderSize",
              "stakeAddressDeposit", "stakePoolDeposit", "poolRetireMaxEpoch", "stakePoolTargetNum",
              "poolPledgeInfluence", "monetaryExpansion", "treasuryCut", "decentralization", "extraPraosEntropy",
              "protocolVersion", "minUTxOValue", "minPoolCost"]
ALONZO_PP = SHELLEY_PP[:15] + ["minPoolCost", "utxoCostPerWord", "costModels", "executionUnitPrices",
                               "maxTxExecutionUnits", "maxBlockExecutionUnits", "maxValueSize",
                               "collateralPercentage", "maxCollateralInputs"]
BABBAGE_PP = SHELLEY_PP[:12] + ["protocolVersion", "minPoolCost", "utxoCostPerByte", "costModels",
                                "executionUnitPrices", "maxTxExecutionUnits", "maxBlockExecutionUnits",
                                "maxValueSize", "collateralPercentage", "maxCollateralInputs"]
CONWAY_PP = BABBAGE_PP + ["poolVotingThresholds", "dRepVotingThresholds", "committeeMinSize",
                          "committeeMaxTermLength", "govActionLifetime", "govActionDeposit", "dRepDeposit",
                          "dRepActivity", "minFeeRefScriptCostPerByte"]
POOL_THRESHOLDS = ["motionNoConfidence", "committeeNormal", "committeeNoConfidence", "hardForkInitiation",
                   "ppSecurityGroup"]
DREP_THRESHOLDS = ["motionNoConfidence", "committeeNormal", "committeeNoConfidence", "updateToConstitution",
                   "hardForkInitiation", "ppNetworkGroup", "ppEconomicGroup", "ppTechnicalGroup", "ppGovGroup",
                   "treasuryWithdrawal"]
RATIONAL_PARAMS = {"poolPledgeInfluence", "monetaryExpansion", "treasuryCut", "decentralization",
                   "minFeeRefScriptCostPerByte"}


def params(era, pp):
    """`Projection.params` names and values of the era's parameters (the keys the era has)."""
    names = {1: SHELLEY_PP, 2: SHELLEY_PP, 3: SHELLEY_PP, 4: ALONZO_PP, 5: BABBAGE_PP, 6: CONWAY_PP}[era]
    if len(pp) != len(names):
        raise ValueError(f"era {era} parameters have {len(pp)} fields, expected {len(names)}")
    out = {}
    for name, v in zip(names, pp):
        if name == "costModels":
            continue
        if name in RATIONAL_PARAMS:
            out[name] = double(v)
        elif name == "extraPraosEntropy":
            out[name] = nonce(v)
        elif name == "protocolVersion":
            out[name] = {"major": v[0], "minor": v[1]}
        elif name == "executionUnitPrices":
            out[name] = {"priceMemory": double(v[0]), "priceSteps": double(v[1])}
        elif name in ("maxTxExecutionUnits", "maxBlockExecutionUnits"):
            out[name] = {"memory": v[0], "steps": v[1]}
        elif name == "poolVotingThresholds":
            out[name] = {k: double(x) for k, x in zip(POOL_THRESHOLDS, v)}
        elif name == "dRepVotingThresholds":
            out[name] = {k: double(x) for k, x in zip(DREP_THRESHOLDS, v)}
        else:
            out[name] = v
    return out


# ---------------------------------------------------------------- pieces shared by all eras

def pool_params(p):
    """Pool state [vrf, pledge, cost, margin, [net, reward], owners, relays, metadata, deposit, delegators]
    or, for future parameters, plain `StakePoolParams` [operator, vrf, pledge, cost, margin, reward account, ...]."""
    if len(p) == 9:
        rw = p[5][1:] if isinstance(p[5], bytes) else p[5][1]
        p = [p[1], p[2], p[3], p[4], [0, rw], p[6]]
    return {"pledge": p[1], "cost": p[2], "margin": ratio(p[3]), "rewardAccount": p[4][1].hex(),
            "owners": sorted(o.hex() for o in p[5])}


def snapshot(s):
    """Stake snapshot [stake {cred: [coin, pool]}, pools {pool: ...}] (Conway: pool snapshots, same shape)."""
    stake, delegations = {}, {}
    for c, (coin, pool) in s[0].items():
        stake[cred(c)] = coin
        delegations[cred(c)] = pool.hex()
    return {"stake": stake, "delegations": delegations, "pools": sorted(p.hex() for p in s[1])}


def previous_major(govs, index):
    try:
        pp = govs[3]
        return pp[14][0] if index < 5 else pp[12][0]
    except (IndexError, TypeError, KeyError):
        return None


def aggregated_pre_allegra(entries):
    return min(entries, key=lambda e: (0 if e[0] == 1 else 1, e[1]))[2]


def reward_update(ru, govs, index):
    """The pending reward update (`possibleRewardUpdate`); None while there is none or it still pulses."""
    if not (ru and len(ru) == 1):
        return None
    body = ru[0]
    r = body[1] if isinstance(body, list) and len(body) == 2 else body
    if not (isinstance(r, list) and len(r) >= 4):
        return None
    pre_allegra = previous_major(govs, index) in (0, 1, 2)
    rs = {}
    for c, entries in r[2].items():
        kept = aggregated_pre_allegra(entries) if pre_allegra else sum(e[2] for e in entries)
        rs[cred(c)] = rs.get(cred(c), 0) + kept
    return {"deltaT": r[0], "deltaR": -r[1], "deltaF": -r[3], "rs": {k: v for k, v in rs.items() if v}}


def chain_dep_state(dep, index):
    """Nonces and opcert counters of the TPraos (Shelley..Alonzo) or Praos (Babbage, Conway) state."""
    state = dep
    if index < 5:
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


# ---------------------------------------------------------------- Conway governance

def anchor(a):
    return {"url": a[0], "hash": a[1].hex()}


def maybe_anchor(x):
    """An anchor, or none: `null`, or a `StrictMaybe` list of zero or one anchors, or the anchor itself."""
    if x is None or x == []:
        return None
    if len(x) == 1 and isinstance(x[0], list):
        return anchor(x[0])
    return anchor(x)


def reward_account_cred(raw):
    """The credential of a reward account (header byte 0xe0/0xe1 key hash, 0xf0/0xf1 script hash)."""
    return f"{'scriptHash' if raw[0] & 0x10 else 'keyHash'}-{raw[1:].hex()}"


def action_id(a):
    return f"{a[0].hex()}#{a[1]}"


def opt_id(a):
    return None if a is None else action_id(a)


def purpose_id(x):
    """A `StrictMaybe GovPurposeId` (list of zero or one ids)."""
    return None if not x else action_id(x[0])


def gov_action(g):
    tag = g[0]
    if tag == 6:
        return {"tag": "InfoAction"}
    if tag == 0:
        return {"tag": "ParameterChange", "prev": opt_id(g[1]), "keys": sorted(g[2]),
                "policy": g[3].hex() if g[3] else None}
    if tag == 1:
        return {"tag": "HardForkInitiation", "prev": opt_id(g[1]), "version": [g[2][0], g[2][1]]}
    if tag == 2:
        withdrawals = {}
        for account, amount in g[1].items():
            key = reward_account_cred(account)
            withdrawals[key] = withdrawals.get(key, 0) + amount
        return {"tag": "TreasuryWithdrawals", "withdrawals": withdrawals, "policy": g[2].hex() if g[2] else None}
    if tag == 3:
        return {"tag": "NoConfidence", "prev": opt_id(g[1])}
    if tag == 4:
        return {"tag": "UpdateCommittee", "prev": opt_id(g[1]), "removed": sorted(cred(c) for c in g[2]),
                "added": {cred(c): e for c, e in g[3].items()}, "threshold": ratio(g[4])}
    if tag == 5:
        return {"tag": "NewConstitution", "prev": opt_id(g[1]), "anchor": anchor(g[2][0]),
                "script": g[2][1].hex() if g[2][1] else None}
    raise ValueError(f"unknown governance action {tag}")


def votes(m, key):
    return {key(k): VOTE[v] for k, v in m.items()}


def proposal(g):
    """GovActionState [id, committeeVotes, drepVotes, poolVotes, proposalProcedure, proposedIn, expiresAfter]."""
    pp = g[4]
    return {"id": action_id(g[0]), "deposit": pp[0], "returnAccount": reward_account_cred(pp[1]),
            "anchor": anchor(pp[3]), "action": gov_action(pp[2]),
            "committeeVotes": votes(g[1], cred), "drepVotes": votes(g[2], cred),
            "poolVotes": votes(g[3], lambda k: k.hex()), "proposedIn": g[5], "expiresAfter": g[6]}


def committee(c):
    """`StrictMaybe Committee`: a list of zero or one [members {cred: epoch}, threshold]."""
    c = strict_maybe(c)
    if c is None:
        return None
    return {"members": {cred(k): v for k, v in c[0].items()}, "threshold": ratio(c[1])}


def constitution(c):
    return {"anchor": anchor(c[0]), "script": c[1].hex() if c[1] else None}


def prev_ids(p):
    return {"ParamUpdate": purpose_id(p[0]), "HardFork": purpose_id(p[1]),
            "Committee": purpose_id(p[2]), "Constitution": purpose_id(p[3])}


def committee_state(cs):
    out = {}
    for k, v in cs.items():
        if v[0] == 0:
            out[cred(k)] = {"hot": cred(v[1])}
        else:
            out[cred(k)] = {"resigned": True, "anchor": maybe_anchor(v[1])}
    return out


def gov(govs, vstate, donation):
    """The Scala `gov` projection from ConwayGovState [proposals, committee, constitution, cur, prev, future, pulser]."""
    proposals, comm, const, pulser = govs[0], govs[1], govs[2], govs[6]
    snapshot_part, ratify = pulser[0], pulser[1]
    enact = ratify[0]
    return {
        "proposals": [proposal(g) for g in proposals[1]],
        "committee": committee(comm),
        "constitution": constitution(const),
        "committeeState": committee_state(vstate[1]),
        "prevGovActionIds": prev_ids(proposals[0]),
        "ratify": {
            "enacted": [action_id(g[0]) for g in ratify[1]],
            "expired": sorted(action_id(a) for a in ratify[2]),
            "delayed": ratify[3],
            "committee": committee(enact[0]),
            "constitution": constitution(enact[1]),
            "prevGovActionIds": prev_ids(enact[6]),
            "withdrawals": {cred(c): v for c, v in enact[5].items()},
            "protocolVersion": [enact[2][12][0], enact[2][12][1]],
        },
        "drepDistr": {drep(k): v for k, v in snapshot_part[1].items()},
        "donation": donation,
    }


# ---------------------------------------------------------------- the snapshot

class Snapshot:
    """A db-analyser snapshot directory, decoded."""

    def __init__(self, path):
        self.path = pathlib.Path(path)
        self.meta = json.loads((self.path / "meta").read_text())
        decoded, _ = ntcquery.decode((self.path / "state").read_bytes())
        if decoded[0] != 1:
            raise ValueError(f"unsupported ExtLedgerState version {decoded[0]}")
        ledger, header = decoded[1]
        self.era = len(ledger) - 1  # telescope: the past eras, then the current one
        self.state = ledger[-1][1][1]  # [tip, NewEpochState, transition, ...]
        self.dep = header[1][-1][1][1]
        self.header_tip = header[0]

    def utxo_map(self):
        """The whole UTxO as {txin: output} (drill-down on small states)."""
        buf = (self.path / "tables").read_bytes()
        return {key: mempack.tx_out(self.era, value) for key, value in mempack.read_tables(buf)}

    def utxo_digest(self, workers=1):
        """The digest of the UTxO; `workers` > 1 splits the file into byte ranges digested in processes."""
        buf = (self.path / "tables").read_bytes()
        offsets, end = mempack.table_entries(buf)
        if workers <= 1 or len(offsets) < 20000:
            d = digest.Digest()
            for key, value in mempack.read_entries(buf, offsets[0] if offsets else end, end):
                d.add(key, digest.canon(mempack.tx_out(self.era, value)))
            return d.json()
        cuts = [offsets[i * len(offsets) // (workers * 4)] for i in range(workers * 4)] + [end]
        total = digest.Digest()
        with concurrent.futures.ProcessPoolExecutor(max_workers=workers) as pool:
            jobs = [pool.submit(_digest_range, str(self.path / "tables"), self.era, a, b)
                    for a, b in zip(cuts, cuts[1:]) if a < b]
            for job in jobs:
                total.merge(job.result())
        return total.json()

    def projection(self, with_utxo=True, workers=1):
        """The comparable projection (large maps are not reduced yet)."""
        era = self.era
        tip, nes = self.state[0], self.state[1]
        epoch, before, current, es, ru, distr = nes[0], nes[1], nes[2], nes[3], nes[4], nes[5]
        (treasury, reserves), ls, snaps = es[0], es[1], es[2]
        cert, ust = ls[0], ls[1]
        govs = ust[3]
        view = {
            "point": {"slot": tip[0][0], "hash": tip[0][2].hex()},
            "epoch": epoch,
            "fees": ust[2], "deposited": ust[1], "treasury": treasury, "reserves": reserves,
            "blocksCurrent": {p.hex(): n for p, n in current.items()},
            "blocksBefore": {p.hex(): n for p, n in before.items()},
            "snapshots": {"mark": snapshot(snaps[0]), "set": snapshot(snaps[1]), "go": snapshot(snaps[2]),
                          "fee": snaps[3]},
            "rewardUpdate": reward_update(ru, govs, era),
            "stakeDistrib": {"total": distr[1], "pools": {p.hex(): v[1] for p, v in distr[0].items()}},
            "protocolState": chain_dep_state(self.dep, era),
        }
        if era >= 6:
            vstate, pstate, dstate = cert
            accounts = {cred(c): {"balance": a[0], "deposit": a[1], "spool": a[2].hex() if a[2] else None,
                                  "drep": drep(a[3]) if a[3] else None} for c, a in dstate[0].items()}
            view["accounts"] = accounts
            view["dreps"] = {cred(c): {"deposit": d[2], "expiry": d[0],
                                       "anchor": None if not d[1] else anchor(d[1][0]),
                                       "delegators": sorted(cred(x) for x in d[3])} for c, d in vstate[0].items()}
            view["numDormantEpochs"] = vstate[2]
            view["params"] = params(era, govs[3])
            view["gov"] = gov(govs, vstate, ust[5])
            fgen, gen, mir = dstate[1], dstate[2], None
        else:
            pstate, dstate = cert
            view["accounts"] = {cred(c): {"balance": a[1], "deposit": a[2], "spool": a[3].hex() if a[3] else None,
                                          "drep": None} for c, a in dstate[0][0].items()}
            view["dreps"] = {}
            view["numDormantEpochs"] = 0
            view["params"] = params(era, govs[2])
            fgen, gen, mir = dstate[1], dstate[2], dstate[3]
            view["ppup"] = {"current": self.updates(govs[0]), "future": self.updates(govs[1])}
            view["mir"] = {"reserves": {cred(c): v for c, v in mir[0].items() if v},
                           "treasury": {cred(c): v for c, v in mir[1].items() if v},
                           "deltaReserves": mir[2], "deltaTreasury": mir[3]}
        view["genDelegs"] = {g.hex(): {"delegate": v[0].hex(), "vrf": v[1].hex()} for g, v in gen.items()}
        view["futureGenDelegs"] = sorted(
            ({"slot": k[0], "genesisKey": k[1].hex(), "delegate": v[0].hex(), "vrf": v[1].hex()} for k, v in fgen.items()),
            key=lambda f: (f["slot"], f["genesisKey"]))
        pools_index = 1  # PState: [vrf key hashes, stake pools, future pool parameters, retiring]
        pools = pstate[pools_index]
        view["pools"] = {p.hex(): {"deposit": v[8], "vrf": v[0].hex()} for p, v in pools.items()}
        view["poolParams"] = {p.hex(): pool_params(v) for p, v in pools.items()}
        view["futurePools"] = {p.hex(): pool_params(v) for p, v in pstate[pools_index + 1].items()}
        view["retiring"] = {p.hex(): e for p, e in pstate[pools_index + 2].items()}
        if with_utxo:
            view["utxo"] = self.utxo_digest(workers)
        return view

    @staticmethod
    def updates(m):
        from devnet import eraview
        return eraview.updates(m)


def _digest_range(path, era, start, end):
    """Worker: the digest state of the UTxO entries in a byte range of a tables file."""
    buf = pathlib.Path(path).read_bytes()
    d = digest.Digest()
    for key, value in mempack.read_entries(buf, start, end):
        d.add(key, digest.canon(mempack.tx_out(era, value)))
    return d.state()


def reduced(snapshot_dir, workers=1):
    """The projection of a snapshot with its large maps reduced to digests (the UTxO already is one)."""
    s = Snapshot(snapshot_dir)
    view = s.projection(workers=workers)
    utxo = view.pop("utxo")
    out = digest.reduce(view)
    out["utxo"] = utxo
    out["era"] = ERA_NAMES[s.era]
    return out
