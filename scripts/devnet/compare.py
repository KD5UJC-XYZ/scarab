# SPDX-License-Identifier: Apache-2.0
"""Compare the Scala ledger with the reference node: projections, diffs and per-era comparisons."""
import json
import subprocess
import threading
import time
from . import eraview
from . import ntcquery
from . import govview
from .encoding import address_hex
from . import util
from .util import CLI, ENV, ERAS, MAGIC, QUERY_PORT, docker, in_ref, ref


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
    url = f"http://127.0.0.1:{QUERY_PORT}/state?slot={tip['slot']}&hash={tip['hash']}"
    script = f"import urllib.request,sys;sys.stdout.write(urllib.request.urlopen('{url}',timeout=20).read().decode())"
    r = docker("exec", f"{name}-scala", "python3", "-c", script, check=False, timeout=60)
    return json.loads(r.stdout) if r.returncode == 0 and r.stdout else None


BUNDLE = [("ls", "query ledger-state"), ("utxo", "query utxo --whole-utxo"), ("protocol", "query protocol-state"),
          ("gov", "query gov-state"), ("committee", "query committee-state"),
          ("drep", "query drep-stake-distribution --all-dreps")]


def query_bundle(name):
    """The reference queries of one comparison, run in parallel in one shell between two tip reads (so a
    block arriving meanwhile is detected). Returns ({key: parsed JSON} for the keys of BUNDLE, 'before' and
    'after', None), or (None, why) where `why` names the failed query with its exit status and stderr."""
    q = "/work/q"
    lines = [f"mkdir -p {q}", f"{CLI} query tip {util.SOCKET} > {q}/before 2> {q}/before.err; echo $? > {q}/before.rc"]
    for key, command in BUNDLE:
        extra = " --output-json" if key == "utxo" else ""
        lines.append(f"({CLI} {command} {util.SOCKET}{extra} > {q}/{key} 2> {q}/{key}.err; echo $? > {q}/{key}.rc) &")
    lines += ["wait", f"{CLI} query tip {util.SOCKET} > {q}/after 2> {q}/after.err; echo $? > {q}/after.rc"]
    keys = ["before", "after"] + [k for k, _ in BUNDLE]
    lines.append(f"for f in {' '.join(keys)}; do for s in '' .err .rc; do echo \"@@$f$s\"; cat {q}/$f$s; done; done")
    out = in_ref(name, "\n".join(lines), timeout=180).stdout
    parts = {}
    for chunk in out.split("@@")[1:]:
        key, _, body = chunk.partition("\n")
        parts[key.strip()] = body
    bundle = {}
    for k in keys:
        rc = parts.get(f"{k}.rc", "").strip()
        try:
            bundle[k] = json.loads(parts[k])
        except (KeyError, json.JSONDecodeError) as e:
            return None, {"query": k, "exit": rc or None, "stderr": parts.get(f"{k}.err", "").strip()[-400:],
                          "unparsable": type(e).__name__}
    return bundle, None


def compare_once_detailed(name, attempts=10, scala_wait=30):
    """One bracketed comparison. Returns (result dict, None), or (None, failures) with one record per
    attempt that could not be bracketed: a failed reference query (with its stderr) or a moving tip."""
    failures = []
    for attempt in range(attempts):
        bundle, why = query_bundle(name)
        if bundle is None:
            failures.append(why)
            continue
        before, after = bundle["before"], bundle["after"]
        ls, utxo, protocol = bundle["ls"], bundle["utxo"], bundle["protocol"]
        gov = (bundle["gov"], bundle["committee"], bundle["drep"])
        if before["hash"] != after["hash"]:
            failures.append({"tipMoved": {"from": before["slot"], "to": after["slot"]}})
            continue
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
        reference["gov"] = govview.reference_gov(*gov)
        reference["protocolState"] = {k: protocol[k] for k in (
            "epochNonce", "evolvingNonce", "candidateNonce", "labNonce", "lastEpochBlockNonce", "oCertCounters")}
        scala_view = {k: v for k, v in scala.items() if k in reference}
        scala_view["gov"] = govview.scala_gov(scala["gov"])
        scala_view["dreps"] = {c: {"deposit": d["deposit"], "expiry": d["expiry"]}
                               for c, d in scala_view["dreps"].items()}
        # the Conway load makes no datums or reference scripts; the CLI JSON shapes for them are not compared
        scala_view["utxo"] = {k: {a: b for a, b in v.items() if a in ("address", "lovelace", "assets")}
                              for k, v in scala_view["utxo"].items()}
        differences = diff(reference, scala_view)
        return {"tip": {k: after[k] for k in ("slot", "block", "epoch", "hash")}, "utxoSize": len(utxo),
                "blocksCurrent": reference["blocksCurrent"], "blocksBefore": reference["blocksBefore"],
                "poolStake": reference["stakeDistrib"],
                "matched": not differences, "differences": [
                    {"path": p, "reference": str(a)[:200], "scala": str(b)[:200]} for p, a, b in differences[:25]],
                "differenceCount": len(differences)}, None
    return None, failures


def compare_once(name, attempts=10, scala_wait=30):
    """`compare_once_detailed` without the failure records: the result, or None if no stable bracket was found."""
    return compare_once_detailed(name, attempts, scala_wait)[0]


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
                          "bracketFailures": result.get("bracketFailures")}), flush=True)


def compare_era(name, era, label, comparisons):
    """Compare the Scala ledger with the reference in `era`; never raises."""
    try:
        if era == "conway":
            result, failures = compare_once_detailed(name, attempts=12, scala_wait=3)
            result = result or {"error": "no stable bracket", "bracketFailures": failures[-3:],
                                "bracketFailureCount": len(failures)}
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
    lines = ["era       matched  mismatched  errors  skipped"]
    for era in ERAS:
        r = comparisons.per_era[era]
        if era == "byron":
            lines.append(f"{era:<9} not compared ({r['note']})")
        else:
            lines.append(f"{era:<9} {r['matched']:>7}  {r['mismatched']:>10}  {r['errors']:>6}  {r['skipped']:>7}")
    return "\n".join(lines)
