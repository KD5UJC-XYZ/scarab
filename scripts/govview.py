# SPDX-License-Identifier: Apache-2.0
"""Project the reference node's Conway governance queries onto the Scala node's `gov` state.

`cardano-cli conway query gov-state / committee-state / drep-stake-distribution` (ratify-state is
part of gov-state as `nextRatifyState`) are reshaped to the JSON `Projection.gov` produces, so the
two can be diffed path by path. Rationals are reduced fractions "n/d" on both sides.
"""
from fractions import Fraction

VOTE = {"VoteYes": "Yes", "VoteNo": "No", "Abstain": "Abstain"}

# parameter-update field names of the Conway protocol parameters, by index
PARAM_INDEX = {
    "txFeePerByte": 0, "txFeeFixed": 1, "maxBlockBodySize": 2, "maxTxSize": 3, "maxBlockHeaderSize": 4,
    "stakeAddressDeposit": 5, "stakePoolDeposit": 6, "poolRetireMaxEpoch": 7, "stakePoolTargetNum": 8,
    "poolPledgeInfluence": 9, "monetaryExpansion": 10, "treasuryCut": 11, "minPoolCost": 16,
    "utxoCostPerByte": 17, "costModels": 18, "executionUnitPrices": 19, "maxTxExecutionUnits": 20,
    "maxBlockExecutionUnits": 21, "maxValueSize": 22, "collateralPercentage": 23, "maxCollateralInputs": 24,
    "poolVotingThresholds": 25, "dRepVotingThresholds": 26, "committeeMinSize": 27,
    "committeeMaxTermLength": 28, "govActionLifetime": 29, "govActionDeposit": 30, "dRepDeposit": 31,
    "dRepActivity": 32, "minFeeRefScriptCostPerByte": 33,
}


def ratio(x):
    """A JSON number or {numerator, denominator} as a reduced fraction string."""
    if isinstance(x, dict):
        f = Fraction(x["numerator"], x["denominator"])
    else:
        f = Fraction(str(x))
    return f"{f.numerator}/{f.denominator}"


def cred(c):
    """{'keyHash': h} / {'scriptHash': h} / 'keyHash-h' as 'keyHash-h'."""
    if isinstance(c, str):
        return c
    (k, v), = c.items()
    return f"{k}-{v}"


def action_id(a):
    return f"{a['txId']}#{a['govActionIx']}"


def opt_id(a):
    return None if a is None else action_id(a)


def anchor(a):
    return {"url": a["url"], "hash": a["dataHash"]}


def action(g):
    tag = g["tag"]
    c = g.get("contents")
    if tag == "InfoAction":
        return {"tag": "InfoAction"}
    if tag == "ParameterChange":
        prev, update, policy = c
        return {"tag": tag, "prev": opt_id(prev), "keys": sorted(PARAM_INDEX[k] for k in update),
                "policy": policy}
    if tag == "HardForkInitiation":
        prev, version = c
        return {"tag": tag, "prev": opt_id(prev), "version": [version["major"], version["minor"]]}
    if tag == "TreasuryWithdrawals":
        withdrawals, policy = c
        out = {}
        for account, amount in withdrawals:
            key = cred(account["credential"])
            out[key] = out.get(key, 0) + amount
        return {"tag": tag, "withdrawals": out, "policy": policy}
    if tag == "NoConfidence":
        return {"tag": tag, "prev": opt_id(c if not isinstance(c, list) else c[0])}
    if tag == "UpdateCommittee":
        prev, removed, added, threshold = c
        return {"tag": tag, "prev": opt_id(prev), "removed": sorted(cred(r) for r in removed),
                "added": {cred(k): v for k, v in added.items()}, "threshold": ratio(threshold)}
    if tag == "NewConstitution":
        prev, constitution = c
        return {"tag": tag, "prev": opt_id(prev), "anchor": anchor(constitution["anchor"]),
                "script": constitution.get("script")}
    raise ValueError(f"unknown governance action {tag}")


def votes(m, key=lambda k: k):
    return {key(k): VOTE[v] for k, v in m.items()}


def proposal(p):
    pp = p["proposalProcedure"]
    return {
        "id": action_id(p["actionId"]),
        "deposit": pp["deposit"],
        "returnAccount": cred(pp["returnAddr"]["credential"]),
        "anchor": anchor(pp["anchor"]),
        "action": action(pp["govAction"]),
        "committeeVotes": votes(p["committeeVotes"], cred),
        "drepVotes": votes(p["dRepVotes"], cred),
        "poolVotes": votes(p["stakePoolVotes"]),
        "proposedIn": p["proposedIn"],
        "expiresAfter": p["expiresAfter"],
    }


def committee(c):
    if c is None:
        return None
    return {"members": {cred(k): v for k, v in c["members"].items()}, "threshold": ratio(c["threshold"])}


def constitution(c):
    return {"anchor": anchor(c["anchor"]), "script": c.get("script")}


def prev_ids(p):
    return {"ParamUpdate": opt_id(p["PParamUpdate"]), "HardFork": opt_id(p["HardFork"]),
            "Committee": opt_id(p["Committee"]), "Constitution": opt_id(p["Constitution"])}


def committee_state(cs):
    out = {}
    for k, v in cs.get("committee", {}).items():
        auth = v["hotCredsAuthStatus"]
        if auth["tag"] == "MemberAuthorized":
            out[cred(k)] = {"hot": cred(auth["contents"])}
        elif auth["tag"] == "MemberResigned":
            out[cred(k)] = {"resigned": True, "anchor": None if auth.get("contents") is None
                            else anchor(auth["contents"])}
    return out


def reference_gov(gov_state, committee_state_json, drep_distr):
    """The Scala `gov` projection, from the three reference queries."""
    ratify = gov_state["nextRatifyState"]
    enact = ratify["nextEnactState"]
    return {
        "proposals": [proposal(p) for p in gov_state["proposals"]],
        "committee": committee(gov_state["committee"]),
        "constitution": constitution(gov_state["constitution"]),
        "committeeState": committee_state(committee_state_json),
        "ratify": {
            "enacted": [action_id(g["actionId"]) for g in ratify["enactedGovActions"]],
            "expired": sorted(action_id(a) for a in ratify["expiredGovActions"]),
            "delayed": ratify["ratificationDelayed"],
            "committee": committee(enact["committee"]),
            "constitution": constitution(enact["constitution"]),
            "prevGovActionIds": prev_ids(enact["prevGovActionIds"]),
            "protocolVersion": [enact["curPParams"]["protocolVersion"]["major"],
                                enact["curPParams"]["protocolVersion"]["minor"]],
        },
        "drepDistr": drep_distr,
    }


def scala_gov(gov):
    """The part of the Scala `gov` projection the reference queries can also produce."""
    out = {k: gov[k] for k in ("proposals", "committee", "constitution", "committeeState", "drepDistr")}
    out["ratify"] = {k: gov["ratify"][k] for k in (
        "enacted", "expired", "delayed", "committee", "constitution", "prevGovActionIds", "protocolVersion")}
    return out
