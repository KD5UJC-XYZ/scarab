# SPDX-License-Identifier: Apache-2.0
"""Compare the Scala ledger with the reference node: projections, diffs and per-era comparisons.

These comparisons are projections, not whole-state equality: `PROJECTION_SCOPE` lists what they compare and
what they leave out, and every result names the Scala fields the reference side has no counterpart for
(`scalaOnlyKeys`). In `e2e`, `pool` and `soak` each one is also a complete same-point comparison of the ledger
state (`whole_state`): `DebugNewEpochState`, whose UTxO is an empty map on both nodes, and `GetUTxOWhole`, every
byte of each, and the complete comparison is what covers the fields the projection leaves out (#433).
"""
import hashlib
import json
import pathlib
import subprocess
import threading
import time
from typing import NamedTuple
from . import cborspan
from . import eraview
from . import ledgercompare
from . import ntcquery
from . import govview
from . import pinnedquery
from .encoding import address_hex
from . import util
from .util import CLI, ENV, ERAS, MAGIC, SINGLE_FOLLOWER, docker, in_ref, ref


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


def scala_state(name, tip, site=SINGLE_FOLLOWER):
    """The `/state` of the follower of `site` at the block `tip`, or None when it does not answer for that block."""
    url = f"http://127.0.0.1:{site.query_port}/state?slot={tip['slot']}&hash={tip['hash']}&utxo=full"
    script = f"import urllib.request,sys;sys.stdout.write(urllib.request.urlopen('{url}',timeout=20).read().decode())"
    r = docker("exec", site.container(name), "python3", "-c", script, check=False, timeout=60)
    return json.loads(r.stdout) if r.returncode == 0 and r.stdout else None


def awaited_state(name, tip, site, tries, pause):
    """`scala_state`, asked up to `tries` times `pause` seconds apart while the follower has not reached `tip`."""
    for _ in range(tries):
        scala = scala_state(name, tip, site)
        if scala is not None:
            return scala
        time.sleep(pause)
    return None


class Sample(NamedTuple):
    """One follower's comparison at a reference block: the result as recorded, and the digest of the follower's
    projected state (the part of its `/state` the reference view was compared with), None when none was taken."""
    result: dict
    projection: str | None = None


def projection_digest(view):
    """SHA-256 of a projected state in a canonical JSON form (keys sorted, each tagged with its type), so two
    followers' projections at one block can be judged equal without keeping either."""
    def canonical(v):
        if isinstance(v, dict):
            return {f"{type(k).__name__}:{k}": canonical(x) for k, x in v.items()}
        if isinstance(v, (list, tuple)):
            return [canonical(x) for x in v]
        return v
    text = json.dumps(canonical(view), sort_keys=True, separators=(",", ":"), default=repr)
    return hashlib.sha256(text.encode()).hexdigest()


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
        # A failed tip read is a failed attempt, as a failed query of the bundle is. `in_ref` would raise
        # SystemExit, which a load thread's handlers do not catch and which ends that thread silently (#591).
        read = in_ref(name, f"{CLI} query tip {util.SOCKET}", check=False)
        if read.returncode != 0:
            return None, {"query": "tip", "exit": str(read.returncode), "stderr": read.stderr.strip()[-400:]}
        try:
            tip = json.loads(read.stdout)
        except json.JSONDecodeError as e:
            return None, {"query": "tip", "unparsable": type(e).__name__}
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
        run = in_ref(name, "\n".join(lines), timeout=180, check=False)
        if run.returncode != 0:
            return None, {"query": "bundle", "exit": str(run.returncode), "stderr": run.stderr.strip()[-400:],
                          "slot": tip["slot"]}
        out = run.stdout
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
    samples, failures = compare_followers_detailed(name, (SINGLE_FOLLOWER,), attempts, scala_wait)
    return (samples[0].result, None) if samples else (None, failures)


def compare_followers_detailed(name, sites, attempts=10, scala_wait=30):
    """`compare_once_detailed` for several followers. Each attempt reads the reference at one block and asks every
    follower not judged yet for that block. A follower that answers is judged there and then, and its judgment
    stands: a later attempt never replaces it, so a difference is never retried away. Only the followers that did
    not answer are asked again, at the next attempt's block; after the last one each still without a judgment has
    an error of its own. Followers judged at different blocks are not compared with each other
    (`FollowerAgreement`). Returns ([Sample per site], failures), or (None, failures) when no attempt read the
    reference."""
    failures, judged, asked = [], {}, None
    for attempt in range(attempts):
        pending = [site for site in sites if site not in judged]
        if not pending:
            break
        bundle, why = query_bundle(name)
        if bundle is None:
            failures.append(why)
            if "foreignAcquires" in why:
                break  # the harness asked the node for another state: not a retryable failure
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
            for site in pending:
                judged[site] = Sample({"tip": after, "error": "the CLI and LocalStateQuery UTxO of one block differ"})
            break
        asked = after
        ls, utxo, protocol = bundle["ls"], bundle["utxo"], bundle["protocol"]
        gov = (bundle["gov"], bundle["committee"], bundle["drep"])
        # a follower that has not reached the block yet, or has moved past its retained window, is asked again at the
        # next attempt's block; the others are judged at this one
        states = {site: awaited_state(name, after, site, scala_wait, 1) for site in pending}
        if all(scala is None for scala in states.values()):
            continue
        reference = reference_view(ls, utxo)
        reference["utxo"] = full_utxo
        reference["gov"] = govview.reference_gov(*gov)
        reference["gov"]["prevGovActionIds"] = roots
        reference["protocolState"] = {k: protocol[k] for k in (
            "epochNonce", "evolvingNonce", "candidateNonce", "labNonce", "lastEpochBlockNonce", "oCertCounters")}
        for site, scala in states.items():
            if scala is not None:
                judged[site] = conway_sample(after, utxo, full_utxo, reference, scala)
    if not judged and asked is None:
        return None, failures
    return [judged.get(site) or Sample({"tip": asked, "error": "scala node never reached the reference tip"})
            for site in sites], failures


def conway_sample(after, utxo, full_utxo, reference, scala):
    """The Sample of one follower's `/state` (`scala`) against the reference view of the Conway block `after`."""
    scala_view = {k: v for k, v in scala.items() if k in reference}
    scala_view["gov"] = govview.scala_gov(scala["gov"])
    scala_view["dreps"] = {c: {"deposit": d["deposit"], "expiry": d["expiry"]}
                           for c, d in scala_view["dreps"].items()}
    differences = diff(reference, scala_view)
    return Sample({"tip": {k: after[k] for k in ("slot", "block", "epoch", "hash")}, "utxoSize": len(utxo),
                   "utxoWithDatumOrScript": sum(1 for v in full_utxo.values()
                                                if {"datumHash", "inlineDatum", "scriptRef"} & set(v)),
                   "scalaOnlyKeys": scala_only(scala, reference),
                   "blocksCurrent": reference["blocksCurrent"], "blocksBefore": reference["blocksBefore"],
                   "poolStake": reference["stakeDistrib"],
                   "matched": not differences, "differences": [
                       {"path": p, "reference": str(a)[:200], "scala": str(b)[:200]} for p, a, b in differences[:25]],
                   "differenceCount": len(differences)}, projection_digest(scala_view))


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

# The era index of the hard-fork combinator, as `QueryIfCurrent` names it.
ERA_INDEX = {"shelley": 1, "allegra": 2, "mary": 3, "alonzo": 4, "babbage": 5, "conway": 6}
# The complete same-point answers `whole_state` compares, by label: the ledger query (`QueryIfCurrent` of the
# era) and its judge. The new-epoch state holds the UTxO as an empty map on both nodes (UTxO-HD keeps it in the
# ledger's tables), so the whole UTxO is asked beside it.
WHOLE_QUERIES = {
    "DebugNewEpochState": (12, lambda reference, scala: ledgercompare.compare("DebugNewEpochState", reference, scala)),
    ledgercompare.UTXO_LABEL: (7, ledgercompare.compare_utxo),
}


def first_difference(a, b):
    """Where two CBOR items first differ: the byte offset, and the path of child indexes to the deepest
    item that holds the difference (a map's keys and values count alternately); None when they are equal."""
    if a == b:
        return None
    path, pos_a, pos_b = [], 0, 0
    while True:
        try:
            kids_a, kids_b = cborspan.children(a, pos_a), cborspan.children(b, pos_b)
        except cborspan.Malformed:
            break
        if not kids_a or not kids_b or a[pos_a:kids_a[0][0]] != b[pos_b:kids_b[0][0]]:
            break  # a leaf, or the heads differ
        step = next((i for i, (x, y) in enumerate(zip(kids_a, kids_b)) if a[x[0]:x[1]] != b[y[0]:y[1]]), None)
        if step is None:
            break  # the children agree as far as both go; the lengths differ
        path.append(step)
        pos_a, pos_b = kids_a[step][0], kids_b[step][0]
    offset = next((i for i, (x, y) in enumerate(zip(a, b)) if x != y), min(len(a), len(b)))
    return {"offset": offset, "path": path}


def ask_raw(session, query):
    """The complete raw bytes of the answer to `query` on an acquired session."""
    session._send(7, ntcquery.enc([3, query]))
    reply = session._recv(7)
    if reply[0] != 4:
        raise RuntimeError(f"query failed: {str(reply)[:200]}")
    return session.last_raw


def whole_state(name, era, scala_socket=None, anchor="scala", attempts=1, site=SINGLE_FOLLOWER):
    """The complete same-point ledger state of both nodes: each of `WHOLE_QUERIES`, asked over node-to-client on
    one session per node acquired at one point, and judged on every byte (`ledgercompare`). The point is the volatile
    tip of `anchor`: the Scala node's when it follows, as every block it holds is the reference's; the reference's
    when the Scala node forges too, as a block it has just forged may not have reached the reference yet. The other
    node is asked at that point; while it has not reached it, the comparison is taken again after a pause, up to
    `attempts` times. `scala_socket` is the Scala node's node-to-client socket (`scala-sock/node.socket` of the run
    dir by default, the socket directory of the follower `site`). With more than one attempt, a point the other
    node never reaches is given up once for a new one. Returns {"slot", "answers": {label: result}}, or {"skipped"}
    or {"error"} for all of them. A difference keeps both answers in the run dir (`whole-state`, the follower's
    own). Never raises."""
    root = util.data_root(name)
    scala_socket = pathlib.Path(scala_socket) if scala_socket else site.directory(root, "scala-sock") / "node.socket"
    if not scala_socket.exists():
        return {"skipped": "the Scala node has no node-to-client socket"}
    queries = {label: [0, [0, [ERA_INDEX[era], [q]]]] for label, (q, _) in WHOLE_QUERIES.items()}

    def scala():
        return ntcquery.LocalStateQuery(None, str(scala_socket), MAGIC, host=True, deadline=60)

    def reference():
        return ntcquery.LocalStateQuery(ref(name), reference_socket(), MAGIC, deadline=60)

    first, second = (scala, reference) if anchor == "scala" else (reference, scala)

    def at_anchor():
        with first() as session:
            session.acquire()
            point = session.query_raw([3])
            return point, {label: ask_raw(session, q) for label, q in queries.items()}

    def at(point):
        with second() as session:
            session.acquire(point)
            return {label: ask_raw(session, q) for label, q in queries.items()}

    def not_reached(error):
        return isinstance(error, RuntimeError) and str(error).startswith("acquire failed")

    # the other node is asked again at the same point while it has not reached it; a point it never reaches
    # (a block of the anchor's that lost a slot battle) is given up once for a new one
    picks = 2 if attempts > 1 else 1
    try:
        for pick in range(picks):
            point, anchored = at_anchor()
            for attempt in range(attempts):
                try:
                    other = at(point)
                    break
                except RuntimeError as e:
                    if not not_reached(e) or (pick + 1 == picks and attempt + 1 == attempts):
                        raise
                    if attempt + 1 < attempts:
                        time.sleep(2)
            else:
                continue  # every attempt at this point was refused: a new point
            break
    except (RuntimeError, EOFError, OSError, ntcquery.Deadline, ntcquery.Incomplete) as e:
        return {"error": repr(e)[:300]}
    got, expected = (anchored, other) if anchor == "scala" else (other, anchored)
    slot = point[0] if isinstance(point, list) and point else None
    answers = {}
    for label, (_, judge) in WHOLE_QUERIES.items():
        verdict = judge(expected[label], got[label])
        result = {"matched": verdict.equal, "byteEqual": verdict.raw_equal, "problems": verdict.problems,
                  "referenceBytes": len(expected[label]), "scalaBytes": len(got[label])}
        if not verdict.equal:
            result["firstDifference"] = first_difference(expected[label], got[label])
            dump = site.directory(root, "whole-state")
            dump.mkdir(exist_ok=True)
            stem = dump / f"{era}-{slot}-{label}"
            stem.with_suffix(".reference").write_bytes(expected[label])
            stem.with_suffix(".scala").write_bytes(got[label])
            result["kept"] = str(stem)
        answers[label] = result
    return {"slot": slot, "answers": answers}


def lsq(name):
    return ntcquery.LocalStateQuery(ref(name), f"{ENV}/socket/node1/sock", MAGIC)


def protocol_parameters(name):
    return json.loads(in_ref(name, f"cardano-cli query protocol-parameters {util.SOCKET}").stdout)


def compare_pre_conway(name, era, attempts=10):
    """One comparison in a Shelley..Babbage era: the reference ledger from LocalStateQuery (atomic at
    the acquired tip) against the Scala node's `/state` at the same block. Returns a result dict, or
    {"skipped": reason} when the chain left the era while querying."""
    return compare_pre_conway_followers(name, era, (SINGLE_FOLLOWER,), attempts)[0].result


def compare_pre_conway_followers(name, era, sites, attempts=10):
    """`compare_pre_conway` for several followers, as `compare_followers_detailed`: each attempt reads the reference
    at one block, every follower not judged yet is asked for it, a judgment stands once made, and only the
    followers that did not answer are asked again. Returns [Sample per site]."""
    judged = {}
    for attempt in range(attempts):
        pending = [site for site in sites if site not in judged]
        if not pending:
            break
        try:
            before = protocol_parameters(name)
            with lsq(name) as session:
                session.acquire()
                tip, reference = eraview.reference_pre_conway(era, session, before)
            after = protocol_parameters(name)
        except (RuntimeError, EOFError, json.JSONDecodeError, subprocess.TimeoutExpired) as e:
            if "mismatch" in str(e):
                for site in pending:
                    judged[site] = Sample({"skipped": f"the chain left {era} while querying"})
                break
            time.sleep(0.5)
            continue
        if before != after:
            continue  # an epoch boundary passed between the parameter reads and the state read
        # a follower that moved past the retained window (k blocks) while the queries ran is asked again at the next
        # attempt's block; the others are judged at this one
        states = {site: awaited_state(name, tip, site, 8, 0.4) for site in pending}
        if all(scala is None for scala in states.values()):
            continue
        evidence = reference.pop("_evidence", None)  # what the comparison exercised, not compared
        reference["ppup"], reference_encodings = eraview.proposal_values(reference["ppup"])
        for site, scala in states.items():
            if scala is not None:
                judged[site] = pre_conway_sample(tip, reference, reference_encodings, evidence, scala)
    return [judged.get(site) or Sample({"error": "no stable comparison within the attempts"}) for site in sites]


def pre_conway_sample(tip, reference, reference_encodings, evidence, scala):
    """The Sample of one follower's `/state` (`scala`) against the reference view of a pre-Conway block `tip`
    (its proposals already in `eraview.proposal_values` form, their original encodings `reference_encodings`)."""
    scala_view = {k: v for k, v in scala.items() if k in reference}
    scala_view["params"] = {k: v for k, v in scala_view["params"].items()
                            if k in reference["params"]}
    scala_view["ppup"], scala_encodings = eraview.proposal_values(scala_view["ppup"])
    differences = diff(reference, scala_view)
    return Sample({"tip": tip, "epoch": reference["epoch"], "utxoSize": len(reference["utxo"]),
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
                                   for p, a, b in differences[:25]]}, projection_digest(scala_view))


class Comparisons:
    """Thread-safe tally of one follower's ledger comparisons per era; every result is appended to compare.jsonl
    (`compare-<key>.jsonl` for a keyed follower, `site`)."""

    def __init__(self, root, site=SINGLE_FOLLOWER):
        self.lock = threading.Lock()
        self.site = site
        self.file = root / f"{site.named('compare')}.jsonl"
        # what the printed progress lines say of the follower: nothing for a run's only one
        self.tag = {} if site.key is None else {"follower": site.key}
        self.per_era = {e: {"matched": 0, "mismatched": 0, "errors": 0, "skipped": 0} for e in ERAS}
        self.per_era["byron"]["note"] = ("not compared: cardano-cli 11.2.3 cannot query a Byron ledger; the "
                                         "Byron UTxO is compared through the first Shelley epoch")
        self.mismatches = []
        # same-point complete answers (`whole_state`) per query and era, judged with the projection
        self.whole = {q: {e: {"matched": 0, "mismatched": 0, "errors": 0, "skipped": 0} for e in ERAS if e != "byron"}
                      for q in WHOLE_QUERIES}

    def record_whole(self, era, label, result):
        """Tally one `whole_state` result: each query's answer, or its skip or error for every query."""
        answers = result.get("answers") or {q: result for q in WHOLE_QUERIES}
        with self.lock:
            for query, answer in answers.items():
                row = self.whole[query][era]
                outcome = ("skipped" if answer.get("skipped") else "errors" if "matched" not in answer
                           else "matched" if answer["matched"] else "mismatched")
                row[outcome] += 1
                if outcome == "mismatched" and len(row.setdefault("differences", [])) < 5:
                    row["differences"].append({"label": label, "slot": result.get("slot"), **{
                        k: answer.get(k) for k in ("firstDifference", "referenceBytes", "scalaBytes", "kept")}})
            with self.file.open("a") as f:
                f.write(json.dumps({"era": era, "label": label, "wholeState": result}) + "\n")
        print(json.dumps({"wholeState": era, "label": label, **self.tag,
                          "matched": {q: a.get("matched") for q, a in answers.items()},
                          "note": result.get("skipped") or result.get("error")}), flush=True)

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
        print(json.dumps({"compare": era, "label": label, **self.tag, "matched": result.get("matched"),
                          "slot": (result.get("tip") or {}).get("slot"),
                          "differences": result.get("differenceCount"),
                          "note": result.get("skipped") or result.get("error"),
                          "queryFailures": result.get("queryFailures")}), flush=True)


class FollowerAgreement:
    """Thread-safe tally of the followers' projected states against each other, per era: at each reference block
    where `compare_era` compared every follower with the reference, whether all their projections (the part of
    `/state` the reference view is compared with) are equal, by `projection_digest`. Fields the reference has no
    counterpart for are not compared here either. Every point is appended to `compare-followers.jsonl`."""

    def __init__(self, root, sites):
        self.lock = threading.Lock()
        self.file = root / "compare-followers.jsonl"
        self.keys = [site.key for site in sites]
        self.per_era = {e: {"agreed": 0, "differed": 0, "notCompared": 0} for e in ERAS}
        self.differences = []

    def record(self, era, label, samples):
        """Tally one point: `samples`, one Sample per follower in the order of the sites. Only a point where every
        follower was judged, all at one block, compares them; any other is `notCompared`, with each one's block."""
        digests = {key: sample.projection for key, sample in zip(self.keys, samples)}
        blocks = {key: ((s.result.get("tip") or {}).get("slot"), (s.result.get("tip") or {}).get("hash"))
                  for key, s in zip(self.keys, samples)}
        one_block = len(set(blocks.values())) == 1
        outcome = ("notCompared" if any(d is None for d in digests.values()) or not one_block
                   else "agreed" if len(set(digests.values())) == 1 else "differed")
        slot, block_hash = next(iter(blocks.values())) if one_block else (None, None)
        row = {"era": era, "label": label, "slot": slot, "hash": block_hash, "outcome": outcome,
               "projections": digests, **({} if one_block else {"blocks": blocks})}
        with self.lock:
            self.per_era[era][outcome] += 1
            if outcome == "differed" and len(self.differences) < 10:
                self.differences.append(row)
            with self.file.open("a") as f:
                f.write(json.dumps(row) + "\n")

    def report(self):
        return {"perEra": self.per_era, "differences": self.differences}


def agreement_failures(per_era, minimum=1):
    """Failures from a `FollowerAgreement` tally: any point where the followers' projections differ, or a
    Shelley-based era with fewer than `minimum` points where every follower was compared and all agreed."""
    failures = []
    for era in ERAS[1:]:
        row = per_era[era]
        if row["differed"]:
            failures.append(f"{era}: the followers' projected states differ at {row['differed']} of "
                            f"{row['agreed'] + row['differed']} reference blocks where every follower was compared")
        if row["agreed"] < minimum:
            failures.append(f"{era}: only {row['agreed']} reference blocks where every follower was compared and all "
                            f"projected states agreed (need {minimum})")
    return failures


def compare_era(name, era, label, comparisons, agreement=None):
    """Compare the Scala ledger of each follower with the reference in `era`, all with one reference block:
    `comparisons` holds one `Comparisons` per follower (its `site`), and `agreement` (a `FollowerAgreement`, with
    several followers) tallies their projections against each other. Then each follower's complete same-point
    state (`whole_state`, at its own tip). Never raises."""
    sites = [tally.site for tally in comparisons]
    try:
        if era == "conway":
            samples, failures = compare_followers_detailed(name, sites, attempts=12, scala_wait=3)
            samples = samples or [Sample({"error": "reference queries failed at the chosen block",
                                          "queryFailures": failures[-3:], "queryFailureCount": len(failures)})
                                  for _ in sites]
        else:
            samples = compare_pre_conway_followers(name, era, sites)
    except (Exception, SystemExit) as e:  # a comparison problem must not stop the cluster walk
        # SystemExit too: the devnet helpers raise it for a failed command, and in a load thread it would end the
        # thread without a trace (threading ignores SystemExit), so the walk would stop comparing unseen (#591)
        import traceback
        samples = [Sample({"error": repr(e), "traceback": traceback.format_exc()[-1500:]}) for _ in sites]
    for tally, sample in zip(comparisons, samples):
        tally.record(era, label, sample.result)
    if agreement is not None:
        agreement.record(era, label, samples)
    for tally in comparisons:
        try:
            whole = whole_state(name, era, site=tally.site)
        except (Exception, SystemExit) as e:  # nor may the whole-state comparison stop it
            whole = {"error": repr(e)[:300]}
        tally.record_whole(era, label, whole)


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
        failures += whole_failures(comparisons, era, minimum)
    return failures


def whole_failures(comparisons, era, minimum):
    """Failures from the same-point complete comparisons of `era` (`whole_state`): a difference, an error, a
    skipped comparison (the Scala node had no node-to-client socket) or fewer than `minimum` matches, and never
    fewer than one, of any of `WHOLE_QUERIES`."""
    failures = []
    minimum = max(minimum, 1)
    for query in WHOLE_QUERIES:
        whole = comparisons.whole[query][era]
        if whole["mismatched"]:
            failures.append(f"{era}: {whole['mismatched']} same-point {query} answers differ: "
                            f"{whole.get('differences', [])[:2]}")
        if whole["errors"]:
            failures.append(f"{era}: {whole['errors']} same-point {query} comparison errors")
        if whole["skipped"]:
            failures.append(f"{era}: {whole['skipped']} same-point {query} comparisons skipped")
        if whole["matched"] < minimum:
            failures.append(f"{era}: only {whole['matched']} matched same-point {query} answers (need {minimum})")
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
    for query, per_era in comparisons.whole.items():
        lines += [f"same-point {query}, every byte", "era       matched  mismatched  errors  skipped"]
        for era, r in per_era.items():
            lines.append(f"{era:<9} {r['matched']:>7}  {r['mismatched']:>10}  {r['errors']:>6}  {r['skipped']:>7}")
    return "\n".join(lines)
