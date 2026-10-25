# SPDX-License-Identifier: Apache-2.0
"""Compare the Scala ledger with the reference node: projections, diffs and per-era comparisons.

These comparisons are projections, not whole-state equality: `PROJECTION_SCOPE` lists what they compare and
what they leave out, and every result names the Scala fields the reference side has no counterpart for
(`scalaOnlyKeys`). #433 tracks replacing them with complete-state comparisons.
"""
import json
import subprocess
import threading
import time
from . import eraview
from . import ntcquery
from . import govview
from . import pinnedquery
from .encoding import address_hex
from . import util
from .util import CLI, ENV, ERAS, MAGIC, QUERY_PORT, docker, in_ref, ref


PROJECTION_SCOPE = {
    "kind": "projection",
    "issue": 433,
    "compared": [
        "epoch", "utxo (address, lovelace, assets, datum hash or inline datum, reference-script hash over the "
        "original script bytes)", "fees",
        "deposited", "treasury", "reserves", "accounts (balance, deposit, pool, DRep)", "pools (deposit, VRF)",
        "pool parameters before Conway (pledge, cost, margin, reward credential hash, owners; future ones too)",
        "retiring", "dreps (deposit, expiry)", "numDormantEpochs", "blocksCurrent", "blocksBefore",
        "stake snapshots (stake, delegations, pool ids, fee)", "reward update (deltas, summed rewards per account)",
        "stake distribution (total, stake per pool)", "genesis delegations and future ones before Conway",
        "parameter update proposals (values) and MIR before Conway",
        "protocol parameters before Conway, without cost models",
        "Conway governance (govview), including the proposal roots (prevGovActionIds)",
        "protocol state (nonces, operational certificate counters)"],
    # what the projection leaves out, by a stable name: each nested omission has a control test in
    # scripts/tests/test_devnet_pure.py showing two states that differ only there project identically.
    # #433 removes an entry once it is compared.
    "notCompared": {
        "costModels": "cost models (protocol parameters and their updates compare without them)",
        "conwayProtocolParameters": "Conway current and future protocol parameters (only the protocol version)",
        "preConwayPreviousAndFutureParameters": "the previous and future protocol parameters before Conway",
        "parameterChangeValues": "the proposed values of a ParameterChange action (only which parameters)",
        "rewardAccountNetwork": "the network of reward accounts in future pool parameters, proposal return "
                                "accounts and treasury withdrawals (they compare the credential; withdrawals to "
                                "one credential are summed); a registered pool state holds only a credential",
        "poolRewardCredentialKind": "whether a pool's reward credential is a key or a script hash (the Scala "
                                    "/state shows the hash only, as do the recorded replay projections)",
        "poolRelaysAndMetadata": "pool relays and metadata",
        "conwayPoolParameters": "pool parameters in Conway (only deposit and VRF)",
        "poolDelegators": "the delegator set of each pool state",
        "accountPointers": "account pointers",
        "snapshotPoolParameters": "pool parameters inside the stake snapshots",
        "stakeDistributionDetail": "the stake fraction and VRF key hash of each pool in the stake distribution",
        "drepAnchorsAndDelegators": "DRep anchors and delegator sets (only deposit and expiry)",
        "committeeMemberStatus": "committee members' expiration and status, and members with no hot key",
        "enactWithdrawals": "the enact state's treasury withdrawals",
        "treasuryDonation": "the Conway treasury donation",
        "nonMyopic": "nonMyopic",
        "rewardDetail": "individual reward entries and the pulsing reward state",
        "instantaneousStake": "instantaneous stake",
        "avvmAndByronState": "AVVM and Byron state",
        "byronLedger": "the Byron ledger (its UTxO is checked at the first Shelley boundary)",
    },
}


def scala_only(scala, reference):
    """The top-level Scala `/state` fields the reference projection has no counterpart for (not compared)."""
    return sorted(set(scala) - set(reference))


def conway_output(v):
    out = {"address": address_hex(v["address"]), "lovelace": v["value"]["lovelace"]}
    assets = {p: {n: q for n, q in names.items()} for p, names in v["value"].items() if p != "lovelace"}
    if assets:
        out["assets"] = assets
    return out


def reference_view(ls, utxo):
    """Project `query ledger-state` + `query utxo` onto the Scala node's /state shape."""
    es = ls["stateBefore"]
    lstate = es["esLState"]
    d = lstate["delegationState"]
    snaps = es["esSnapshots"]

    def snapshot(s):
        active = s.get("activeStake", {})
        return {"stake": {c: v["swdStake"] for c, v in active.items()},
                "delegations": {c: v["swdDelegation"] for c, v in active.items()},
                "pools": sorted(s.get("stakePoolsSnapShot", {}))}

    ru = ls.get("possibleRewardUpdate")
    rewards = None
    if isinstance(ru, dict) and "rs" in ru:
        rs = {}
        for cred, entries in ru["rs"].items():
            rs[cred] = rs.get(cred, 0) + sum(e["rewardAmount"] for e in entries)
        rewards = {"deltaT": ru["deltaT"], "deltaR": ru["deltaR"], "deltaF": ru["deltaF"],
                   "rs": {k: v for k, v in rs.items() if v}}
    return {
        "epoch": ls["lastEpoch"],
        "utxo": {k: conway_output(v) for k, v in utxo.items()},
        "fees": lstate["utxoState"]["fees"],
        "deposited": lstate["utxoState"]["deposited"],
        "treasury": es["esChainAccountState"]["treasury"],
        "reserves": es["esChainAccountState"]["reserves"],
        "accounts": {c: {"balance": a["balance"], "deposit": a["deposit"], "spool": a.get("spool"),
                         "drep": a.get("drep")} for c, a in d["dstate"]["accounts"].items()},
        "pools": {p: {"deposit": v["spsDeposit"], "vrf": v["spsVrf"]} for p, v in d["pstate"]["stakePools"].items()},
        "retiring": d["pstate"]["retiring"],
        "dreps": {c: {"deposit": v["deposit"], "expiry": v["expiry"]} for c, v in d["vstate"]["dreps"].items()},
        "numDormantEpochs": d["vstate"]["numDormantEpochs"],
        "blocksCurrent": ls["blocksCurrent"],
        "blocksBefore": ls["blocksBefore"],
        "snapshots": {"mark": snapshot(snaps["pstakeMark"]), "set": snapshot(snaps["pstakeSet"]),
                      "go": snapshot(snaps["pstakeGo"]), "fee": snaps["feeSS"]},
        "rewardUpdate": rewards,
        "stakeDistrib": {"total": ls["stakeDistrib"]["pdTotalActiveStake"],
                         "pools": {p: v["individualTotalPoolStake"]
                                   for p, v in ls["stakeDistrib"]["unPoolDistr"].items()}},
    }


def diff(ref_view, scala, path=""):
    """Paths where the two views differ, with both values (truncated)."""
    out = []
    if isinstance(ref_view, dict) and isinstance(scala, dict):
        for k in sorted(set(ref_view) | set(scala)):
            if k not in ref_view:
                out.append((f"{path}/{k}", "<absent>", scala[k]))
            elif k not in scala:
                out.append((f"{path}/{k}", ref_view[k], "<absent>"))
            else:
                out += diff(ref_view[k], scala[k], f"{path}/{k}")
    elif ref_view != scala:
        out.append((path, ref_view, scala))
    return out


def scala_state(name, tip):
    url = f"http://127.0.0.1:{QUERY_PORT}/state?slot={tip['slot']}&hash={tip['hash']}&utxo=full"
    script = f"import urllib.request,sys;sys.stdout.write(urllib.request.urlopen('{url}',timeout=20).read().decode())"
    r = docker("exec", f"{name}-scala", "python3", "-c", script, check=False, timeout=60)
    return json.loads(r.stdout) if r.returncode == 0 and r.stdout else None


BUNDLE = [("ls", "query ledger-state"), ("utxo", "query utxo --whole-utxo"), ("protocol", "query protocol-state"),
          ("gov", "query gov-state"), ("committee", "query committee-state"),
          ("drep", "query drep-stake-distribution --all-dreps")]


def reference_socket():
    """The socket the reference queries use: the one `util.SOCKET` names (a scenario may point it at another pool)."""
    return util.SOCKET.split("--socket-path ", 1)[1].split()[0]


def query_bundle(name):
    """The reference queries of one comparison, all reading the ledger state at one block. The tip is read
    once; the queries of BUNDLE then run in parallel through `pinnedquery.Pin`, which makes each
    `cardano-cli` connection acquire that block instead of the tip of the moment. Returns
    (bundle, None) with the parsed JSON of every key of BUNDLE and the block read under 'tip', or
    (None, why): the query that failed (a missing, malformed or nonzero exit status fails it even if
    its output parses), with its exit status, stderr tail, wall time and acquire count, or the keys that
    were never pinned. A block that has left the node's volatile window by then is refused by the node,
    which is a failed query."""
    pin = pinnedquery.Pin(ref(name), reference_socket(), [k for k, _ in BUNDLE])
    keys = [k for k, _ in BUNDLE]
    try:
        pin.start()
        tip = json.loads(in_ref(name, f"{CLI} query tip {util.SOCKET}").stdout)
        pin.point = [tip["slot"], bytes.fromhex(tip["hash"])]
        q = "/work/q"
        lines = [f"mkdir -p {q}", pin.ready_script()]
        for key, command in BUNDLE:
            extra = " --output-json" if key == "utxo" else ""
            socket = f"--testnet-magic {MAGIC} --socket-path {pin.path(key)}"
            lines.append(f"(s=$(date +%s%N); {CLI} {command} {socket}{extra} > {q}/{key} 2> {q}/{key}.err; "
                         f"echo $? > {q}/{key}.rc; echo $(( ($(date +%s%N) - s) / 1000000 )) > {q}/{key}.ms) &")
        lines.append("wait")
        lines.append(f"for f in {' '.join(keys)}; do for s in '' .err .rc .ms; do echo \"@@$f$s\"; cat {q}/$f$s; done; done")
        out = in_ref(name, "\n".join(lines), timeout=180).stdout
        acquires = pin.acquires()
        foreign = pin.foreign()
    finally:
        pin.close()
    parts = {}
    for chunk in out.split("@@")[1:]:
        key, _, body = chunk.partition("\n")
        parts[key.strip()] = body
    other = {k: n for k, n in foreign.items() if n}
    if other:
        return None, {"foreignAcquires": other, "slot": tip["slot"]}
    bundle = {"tip": tip}
    for k in keys:
        status = parts.get(f"{k}.rc", "").strip()
        record = {"query": k, "exit": status or None, "stderr": parts.get(f"{k}.err", "").strip()[-400:],
                  "ms": parts.get(f"{k}.ms", "").strip() or None, "slot": tip["slot"], "acquires": acquires.get(k)}
        # fail closed: only a recorded exit status of 0 accepts the output, however well it parses
        if status != "0":
            return None, {**record, "failed": "exit status " + ("missing" if not status else "not zero" if status.isdigit() else "malformed")}
        try:
            bundle[k] = json.loads(parts[k])
        except (KeyError, json.JSONDecodeError) as e:
            return None, {**record, "unparsable": type(e).__name__}
    unpinned = sorted(k for k, n in acquires.items() if n == 0)
    if unpinned:
        return None, {"unpinned": unpinned, "slot": tip["slot"]}
    return bundle, None


REFERENCE_UTXO_DEADLINE = 120  # seconds for the whole LocalStateQuery session of one UTxO read


Q_GOV_STATE = 24  # Conway `GetGovState`: ConwayGovState [proposals, committee, constitution, cur, prev, future, pulser]


def reference_utxo(name, tip):
    """The reference's whole UTxO and its proposal roots at the block `tip`, over one LocalStateQuery session
    (Conway `GetUTxOWhole` and `GetGovState`). The UTxO is in the projection of `eraview.tx_out`: unlike
    `cardano-cli query utxo` it keeps the datum and the reference-script hash in the Scala node's shape. The
    roots (`Proposals` encodes `(roots, proposals)`, cardano-ledger f649f975 Conway/Governance/Proposals.hs:385-388)
    are absent from the CLI's gov-state JSON, which shows only the proposals. It asks the socket the bundle's
    queries used. Returns (utxo, roots)."""
    with ntcquery.LocalStateQuery(ref(name), reference_socket(), MAGIC, deadline=REFERENCE_UTXO_DEADLINE) as session:
        session.acquire([tip["slot"], bytes.fromhex(tip["hash"])])
        utxo = session.ledger_query(eraview.ERA_INDEX["conway"], [eraview.Q_UTXO_WHOLE])
        gov_state = session.ledger_query(eraview.ERA_INDEX["conway"], [Q_GOV_STATE])
    return {f"{k[0].hex()}#{k[1]}": eraview.tx_out(v) for k, v in utxo.items()}, govview.proposal_roots(gov_state[0][0])


def utxo_sources_agree(cli_utxo, lsq_utxo):
    """The CLI's UTxO and the LocalStateQuery one at the same block hold the same outputs (addresses,
    lovelace and assets; the CLI JSON carries no comparable datum or script form)."""
    def plain(view):
        return {a: b for a, b in view.items() if a in ("address", "lovelace", "assets")}
    return {k: conway_output(v) for k, v in cli_utxo.items()} == {k: plain(v) for k, v in lsq_utxo.items()}


def compare_once_detailed(name, attempts=10, scala_wait=30):
    """One comparison at one reference block. Returns (result dict, None), or (None, failures) with one record
    per attempt that could not read the reference at that block (a failed query with its stderr)."""
    failures = []
    for attempt in range(attempts):
        bundle, why = query_bundle(name)
        if bundle is None:
            failures.append(why)
            if "foreignAcquires" in why:
                return None, failures  # the harness asked the node for another state: not a retryable failure
            continue
        after = bundle["tip"]
        try:
            full_utxo, roots = reference_utxo(name, after)
        except (ntcquery.Deadline, RuntimeError, EOFError, OSError, subprocess.TimeoutExpired) as e:
            # the block may have left the reference's volatile window since the bundle was read, or the
            # reference did not answer within the deadline: a failed attempt, never a comparison
            failures.append({"query": "GetUTxOWhole/GetGovState", "slot": after["slot"], "error": repr(e)[:300]})
            continue
        if not utxo_sources_agree(bundle["utxo"], full_utxo):
            return {"tip": after, "error": "the CLI and LocalStateQuery UTxO of one block differ"}, None
        ls, utxo, protocol = bundle["ls"], bundle["utxo"], bundle["protocol"]
        gov = (bundle["gov"], bundle["committee"], bundle["drep"])
        scala = None
        for _ in range(scala_wait):
            scala = scala_state(name, after)
            if scala is not None:
                break
            time.sleep(1)
        if scala is None:
            if attempt + 1 < attempts:
                continue  # the node may have moved past the retained window while the queries ran
            return {"tip": after, "error": "scala node never reached the reference tip"}, None
        reference = reference_view(ls, utxo)
        reference["utxo"] = full_utxo
        reference["gov"] = govview.reference_gov(*gov)
        reference["gov"]["prevGovActionIds"] = roots
        reference["protocolState"] = {k: protocol[k] for k in (
            "epochNonce", "evolvingNonce", "candidateNonce", "labNonce", "lastEpochBlockNonce", "oCertCounters")}
        scala_view = {k: v for k, v in scala.items() if k in reference}
        scala_view["gov"] = govview.scala_gov(scala["gov"])
        scala_view["dreps"] = {c: {"deposit": d["deposit"], "expiry": d["expiry"]}
                               for c, d in scala_view["dreps"].items()}
        differences = diff(reference, scala_view)
        return {"tip": {k: after[k] for k in ("slot", "block", "epoch", "hash")}, "utxoSize": len(utxo),
                "utxoWithDatumOrScript": sum(1 for v in full_utxo.values()
                                             if {"datumHash", "inlineDatum", "scriptRef"} & set(v)),
                "scalaOnlyKeys": scala_only(scala, reference),
                "blocksCurrent": reference["blocksCurrent"], "blocksBefore": reference["blocksBefore"],
                "poolStake": reference["stakeDistrib"],
                "matched": not differences, "differences": [
                    {"path": p, "reference": str(a)[:200], "scala": str(b)[:200]} for p, a, b in differences[:25]],
                "differenceCount": len(differences)}, None
    return None, failures


def compare_once(name, attempts=10, scala_wait=30):
    """`compare_once_detailed` without the failure records: the result, or None if the reference could not be read at a chosen block."""
    return compare_once_detailed(name, attempts, scala_wait)[0]


def compare_or_error(name, attempts=10, scala_wait=30):
    """`compare_once_detailed` for the long scenarios: always a result dict, so a comparison that could not be taken
    is an `error` their gates count, never a silently skipped sample."""
    result, failures = compare_once_detailed(name, attempts, scala_wait)
    return result or {"error": "reference queries failed at the chosen block", "queryFailures": failures[-3:],
                      "queryFailureCount": len(failures)}


def compare(args):
    print(json.dumps(compare_once(args.name)))


# ---------------------------------------------------------------- per-era ledger comparison

def lsq(name):
    return ntcquery.LocalStateQuery(ref(name), f"{ENV}/socket/node1/sock", MAGIC)


def protocol_parameters(name):
    return json.loads(in_ref(name, f"cardano-cli query protocol-parameters {util.SOCKET}").stdout)


def compare_pre_conway(name, era, attempts=10):
    """One comparison in a Shelley..Babbage era: the reference ledger from LocalStateQuery (atomic at
    the acquired tip) against the Scala node's `/state` at the same block. Returns a result dict, or
    {"skipped": reason} when the chain left the era while querying."""
    for attempt in range(attempts):
        try:
            before = protocol_parameters(name)
            with lsq(name) as session:
                session.acquire()
                tip, reference = eraview.reference_pre_conway(era, session, before)
            after = protocol_parameters(name)
        except (RuntimeError, EOFError, json.JSONDecodeError, subprocess.TimeoutExpired) as e:
            if "mismatch" in str(e):
                return {"skipped": f"the chain left {era} while querying"}
            time.sleep(0.5)
            continue
        if before != after:
            continue  # an epoch boundary passed between the parameter reads and the state read
        scala = None
        for _ in range(8):
            scala = scala_state(name, tip)
            if scala is not None:
                break
            time.sleep(0.4)
        if scala is None:
            continue  # the node moved past the retained window (k blocks) while the queries ran
        evidence = reference.pop("_evidence", None)  # what the comparison exercised, not compared
        scala_view = {k: v for k, v in scala.items() if k in reference}
        scala_view["params"] = {k: v for k, v in scala_view["params"].items()
                                if k in reference["params"]}
        reference["ppup"], reference_encodings = eraview.proposal_values(reference["ppup"])
        scala_view["ppup"], scala_encodings = eraview.proposal_values(scala_view["ppup"])
        differences = diff(reference, scala_view)
        return {"tip": tip, "epoch": reference["epoch"], "utxoSize": len(reference["utxo"]),
                "scalaOnlyKeys": scala_only(scala, reference),
                "scalaOnlyParams": scala_only(scala.get("params", {}), reference["params"]),
                "evidence": {**(evidence or {}),
                    "ppupCostModelEncodings": {"reference": reference_encodings, "scala": scala_encodings},
                    "mirPending": {
                    "reserves": len(reference["mir"]["reserves"]), "treasury": len(reference["mir"]["treasury"]),
                    "deltaReserves": reference["mir"]["deltaReserves"], "deltaTreasury": reference["mir"]["deltaTreasury"]},
                    "rewardUpdate": {**(evidence or {}).get("rewardUpdate", {}),
                                     "rewards": len((reference["rewardUpdate"] or {}).get("rs", {}))}},
                "matched": not differences, "differenceCount": len(differences),
                "differences": [{"path": p, "reference": str(a)[:300], "scala": str(b)[:300]}
                                for p, a, b in differences[:25]]}
    return {"error": "no stable comparison within the attempts"}


class Comparisons:
    """Thread-safe tally of ledger comparisons per era; every result is appended to compare.jsonl."""

    def __init__(self, root):
        self.lock = threading.Lock()
        self.file = root / "compare.jsonl"
        self.per_era = {e: {"matched": 0, "mismatched": 0, "errors": 0, "skipped": 0} for e in ERAS}
        self.per_era["byron"]["note"] = ("not compared: cardano-cli 11.2.3 cannot query a Byron ledger; the "
                                         "Byron UTxO is compared through the first Shelley epoch")
        self.mismatches = []

    def record(self, era, label, result):
        with self.lock:
            row = self.per_era[era]
            if result.get("skipped"):
                row["skipped"] += 1
            elif "matched" not in result:
                row["errors"] += 1
            elif result["matched"]:
                row["matched"] += 1
            else:
                row["mismatched"] += 1
                if len(self.mismatches) < 20:
                    self.mismatches.append({"era": era, "label": label, **result})
            with self.file.open("a") as f:
                f.write(json.dumps({"era": era, "label": label, **result}) + "\n")
        print(json.dumps({"compare": era, "label": label, "matched": result.get("matched"),
                          "slot": (result.get("tip") or {}).get("slot"),
                          "differences": result.get("differenceCount"),
                          "note": result.get("skipped") or result.get("error"),
                          "queryFailures": result.get("queryFailures")}), flush=True)


def compare_era(name, era, label, comparisons):
    """Compare the Scala ledger with the reference in `era`; never raises."""
    try:
        if era == "conway":
            result, failures = compare_once_detailed(name, attempts=12, scala_wait=3)
            result = result or {"error": "reference queries failed at the chosen block", "queryFailures": failures[-3:],
                                "queryFailureCount": len(failures)}
        else:
            result = compare_pre_conway(name, era)
    except Exception as e:  # a comparison problem must not stop the cluster walk
        import traceback
        result = {"error": repr(e), "traceback": traceback.format_exc()[-1500:]}
    comparisons.record(era, label, result)


def judge_comparisons(comparisons, minimum, loaded):
    """Failures from the comparison tally: any mismatch, or an era with too few matched comparisons."""
    failures = []
    for era in ERAS[1:]:
        row = comparisons.per_era[era]
        if row["mismatched"]:
            failures.append(f"{era}: {row['mismatched']} of {row['matched'] + row['mismatched']} ledger comparisons differ")
        if row["errors"]:
            failures.append(f"{era}: {row['errors']} ledger comparison errors")
        if row["matched"] < minimum:
            failures.append(f"{era}: only {row['matched']} matched ledger comparisons (need {minimum})")
    return failures


def comparison_table(comparisons):
    lines = [f"projected ledger comparisons (not whole states; scope in e2e-summary.json, "
             f"#{PROJECTION_SCOPE['issue']})",
             "era       matched  mismatched  errors  skipped"]
    for era in ERAS:
        r = comparisons.per_era[era]
        if era == "byron":
            lines.append(f"{era:<9} not compared ({r['note']})")
        else:
            lines.append(f"{era:<9} {r['matched']:>7}  {r['mismatched']:>10}  {r['errors']:>6}  {r['skipped']:>7}")
    return "\n".join(lines)
