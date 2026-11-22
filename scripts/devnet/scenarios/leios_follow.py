# SPDX-License-Identifier: Apache-2.0
"""`leios-traffic --follow` (#73, #54): the Scala node follows the reference Leios cluster, and is judged against it.

The follower is the plain `devnet.py follow` of the cluster (`follower.py`), started with the build the cluster runs
(`--reference-build leios-b004fe0c`), a chain database (`--db`, under which the Leios build keeps its endorser-block
store, `LeiosStorage.follows`) and a node-to-client socket (`--local-socket`). It reads the genesis directory
`start_nodes` writes, whose `config.json` is the cluster's own, `LeiosMinOfferLead` included. Its container joins the
reference container's network namespace (`cluster.scala_container`), so it reaches reference node `--follow-node` at
`127.0.0.1:<port>` of `ports.json`, starting while the chain is still in Conway and crossing into Dijkstra live.

The end of the run is ordered so that the whole chain the Scala node selected lies in the reference nodes'
ImmutableDBs, which `leios_traffic` reads after the nodes stop: once every reference node is past the block the load
stopped at plus `k + 2`, the follower is waited for until its own tip is that far too (`--follow-catch-up` seconds
at most); then, while both run, the same-point ledger state of both is asked (`compare.whole_state`, Dijkstra
included); then the follower is stopped, and the reference nodes run on until every one is `k + 2` blocks past the
follower's last block. Its store is read afterwards, once its process has released the store's lock, by the tool
`LeiosStoreCheckMain` (`tools`), for every endorser block the reference chain announces up to the follower's tip.

The judge (`judge_follower`) passes only when the follower's selected chain, rebuilt from its `extended` and
`rolledBack` lines (`follower_chain`), is the reference chain's prefix, compared position by position from Origin;
when the closure of the endorser block each certifying block of that matched prefix certifies is complete in the
follower's store; when its log has no line written at an error severity or above (`effective_severity`: a library
line's own `level`, the node's overrides by kind and field, else the kind's severity in `EventKind.scala`) and no
unparsed output; and when the same-point ledger state is equal on every byte. A fork within `k` blocks of the tip
(kept as `forkAtStop`), a gap in the follower's chain, a roll-back to a point it did not hold, an uncompared ledger
state or no certifying block is incomplete, never a pass (CNF-2); a deeper fork fails.
"""
import json
import re
import shutil
import subprocess
import sys
import time
from .. import compare, ledgercompare
from ..cluster import database_mounts, freeze_classes, scala_container
from ..util import ENTRY, REPO, SINGLE_FOLLOWER, docker, read_final_log, read_log

BUILD = "leios-b004fe0c"
EVENT_KINDS = REPO / "node/src/main/scala/xyz/kd5ujc/scarab/node/log/EventKind.scala"
STORE_CHECK = "xyz.kd5ujc.scarab.node.tools.LeiosStoreCheckMain"
TOOLS_CLASSES = REPO / "tools/target/scala-3.9.0/classes"
FAILING = ("Error", "Critical", "Alert", "Emergency")
# The lines the node writes at another severity than their kind's (`Line.at`), by their kind and the field that tells
# them apart: `PeerSelectionLines`, `Traced` and `NodeEvent` of node/src/main/.../log. A library line (`slf4j`) carries
# its own as `level`.
OVERRIDES = {
    ("ledgerPeers", "action", "snapshotVerificationFailed"): "Error",
    ("ledgerPeers", "action", "snapshotIgnored"): "Warning",
    ("ledgerPeers", "action", "poolSkipped"): "Warning",
    ("ledgerPeers", "action", "unreadable"): "Warning",
    ("connection", "what", "refused"): "Warning",
    ("connection", "what", "acceptHardLimit"): "Warning",
    ("inbound", "state", "refused"): "Warning",
    ("peerSelectionRoots", "action", "failure"): "Warning",
    ("peerSelectionPromote", "action", "failed"): "Warning",
    ("peerSelectionDemote", "action", "failed"): "Warning",
}
# the hard-fork era indexes of a block query: Shelley to Conway, and Dijkstra (7) under the Leios build
LEIOS_ERAS = range(1, 8)
STOP_SECONDS = 30


# ---------------------------------------------------------------- pure helpers

def severities(source):
    """{event name: severity} of the `case <Kind> extends EventKind(Area.<area>, Severity.<severity>)` lines of
    `EventKind.scala`; the event name of a line is the case name with a lower-case initial (`EventKind.name`)."""
    table = {}
    for kind, severity in re.findall(r"case (\w+) extends EventKind\(Area\.\w+, Severity\.(\w+)\)", source):
        table[kind[0].lower() + kind[1:]] = severity
    return table


def effective_severity(event, kinds):
    """The severity a log line was written at: a library line's own `level`, else the override of its kind and
    distinguishing field (`OVERRIDES`), else its kind's (`kinds`, `severities`); None for a kind not in the table."""
    name = event.get("event")
    if name == "slf4j" and isinstance(event.get("level"), str):
        return event["level"]
    for (kind, field, value), severity in OVERRIDES.items():
        if name == kind and event.get(field) == value:
            return severity
    return kinds.get(name)


def failing(events, kinds):
    """The lines of `events` written at an error severity or above (`effective_severity`)."""
    return [e for e in events if effective_severity(e, kinds) in FAILING]


def follower_chain(events):
    """(chain, unrecognised): the chain the follower selected at the end of its log, its `extended` blocks
    ({slot, blockNo, hash}) in order, each `rolledBack` cutting it back to its `to` point (Origin: `to` without a
    hash); and every roll-back whose point is not on the chain rebuilt so far, which leaves the chain unknown from
    there (it is then cut to nothing)."""
    chain, unrecognised = [], []
    for e in events:
        if e.get("event") == "extended":
            chain.append(e["block"])
        elif e.get("event") == "rolledBack":
            to = e.get("to") or {}
            if to.get("hash") is None:
                chain = []
                continue
            keep = next((i for i in range(len(chain) - 1, -1, -1) if chain[i]["hash"] == to["hash"]), None)
            if keep is None:
                unrecognised.append(to)
            chain = chain[:keep + 1] if keep is not None else []
    return chain, unrecognised


def selected_chain(events):
    """The chain of `follower_chain`, alone."""
    return follower_chain(events)[0]


def follower_command(name, node, scala_cpus, voter=None):
    """The `devnet.py follow` process of the follower of the Leios cluster; with `voter` (its listening address and
    the directory of a pool's keys) it listens and votes."""
    extra = [] if voter is None else ["--listen", voter[0], "--voter-keys", str(voter[1])]
    return [sys.executable, str(ENTRY), "--name", name, "follow", "--reference-build", BUILD, "--db",
            "--local-socket", "--node", str(node), "--scala-cpus", str(scala_cpus), *extra]


def tip_block(events):
    """The block number of the follower's selected tip, or None before its first block."""
    chain = selected_chain(events)
    return chain[-1]["blockNo"] if chain else None


def store_lines(text):
    """{hash: {"body", "closureTxs"}} of `LeiosStoreCheckMain`'s JSON lines, and its error lines."""
    found, errors = {}, []
    for line in text.splitlines():
        try:
            row = json.loads(line)
        except json.JSONDecodeError:
            if line.strip():
                errors.append(line)
            continue
        if isinstance(row, dict) and "hash" in row:
            found[row["hash"]] = {"body": row.get("body"), "closureTxs": row.get("closureTxs")}
        else:
            errors.append(line)
    return found, errors


def matched_prefix(reference, scala):
    """How many blocks of the follower's chain are the reference chain's, position by position from Origin (block,
    slot and hash), and the first block that is not, or None."""
    for i, b in enumerate(scala):
        r = reference[i] if i < len(reference) else None
        if r is None or (r["blockNo"], r["slot"], r["hash"]) != (b["blockNo"], b["slot"], b["hash"]):
            return i, b
    return len(scala), None


def judge_follower(reference, scala, store, events, other, kinds, security_param, whole=None, exit_code=None,
                   unrecognised=()):
    """The verdict on the follower. `reference` is the chain every reference node holds in its ImmutableDB (described
    blocks from Origin, `leios_traffic.describe_leios_block`), `scala` the follower's selected chain and
    `unrecognised` the roll-backs to a point it did not hold (`follower_chain`), `store` what its endorser-block store
    holds of each EB hash (`store_lines`), `events` and `other` its log (`read_log`), `kinds` the severities of
    `severities`, `whole` the result of `compare.whole_state`.

    The follower's chain is compared with the reference's position by position from Origin (`matched_prefix`), and
    only the matched part counts: a certifying block counts once the follower's chain holds it at its position. A
    first unmatched block that is a reference block at another position is a gap in the follower's chain, one past the
    reference's immutable chain is not judged yet, and one off the reference's chain is a fork: within `k` blocks of
    the follower's tip it is kept as `forkAtStop` and the evidence is incomplete, deeper it fails. A roll-back to a
    point the follower did not hold leaves its chain unknown: incomplete.

    Returns {"verdict": "passed" | "incomplete" | "failed", "problems", "incomplete", ...counts}."""
    problems, incomplete = [], []
    matched, fork = matched_prefix(reference, scala)
    positions = {b["hash"]: i for i, b in enumerate(reference)}
    if not scala:
        problems.append("the follower applied no block")
    elif fork is not None:
        depth = len(scala) - 1 - matched
        where = f"the follower's block {fork['blockNo']} (slot {fork['slot']}, {fork['hash'][:16]}) at position {matched}"
        if fork["hash"] in positions:
            incomplete.append(f"{where} is the reference's block at position {positions[fork['hash']]}: a gap in the "
                              f"follower's chain")
        elif matched >= len(reference):
            incomplete.append(f"{where} is past the reference nodes' immutable chain")
        elif depth >= security_param:
            problems.append(f"{where} is not the reference's, {depth} blocks below its tip")
        else:
            incomplete.append(f"{where} is not the reference's, {depth} blocks below its tip: a fork at the stop")
    for to in unrecognised:
        incomplete.append(f"the follower rolled back to {to}, a point its log does not show it holding")
    upto = reference[:matched]
    by_no = {b["blockNo"]: b for b in upto}
    announced = [b for b in upto if b["announces"]]
    certifying = []
    for b in upto:
        if not b["certifies"]:
            continue
        parent = by_no.get(b["blockNo"] - 1)
        eb = parent["announces"] if parent is not None and parent["hash"] == b["prevHash"] else None
        held = store.get(eb, {}) if eb else {}
        row = {"block": b["blockNo"], "slot": b["slot"], "eb": eb, "closureTxs": held.get("closureTxs")}
        certifying.append(row)
        if eb is None:
            problems.append(f"certifying block {b['blockNo']} does not extend an announcing block")
        elif held.get("closureTxs") is None:
            problems.append(f"the follower applied certifying block {b['blockNo']}, but the closure of EB "
                            f"{eb[:16]} is not complete in its store (body {'held' if held.get('body') else 'absent'})")
    if not certifying:
        incomplete.append("no certifying block is on the follower's chain")
    errors = failing(events, kinds)
    for e in errors[:5]:
        problems.append(f"the follower logged {e.get('event')}: {json.dumps(e)[:600]}")
    if len(errors) > 5:
        problems.append(f"{len(errors) - 5} more error lines in the follower's log")
    if other:
        problems.append(f"unparsed follower output: {other[0][:300]}")
    if exit_code is not None and exit_code not in (0, 143):
        problems.append(f"the follower ended with {exit_code}")
    warnings = {}
    for e in events:
        if effective_severity(e, kinds) == "Warning":
            warnings[e["event"]] = warnings.get(e["event"], 0) + 1
    ledger = whole_verdict(whole)
    if ledger["status"] == "differs":
        problems.append(f"the same-point ledger state differs: {ledger['detail']}")
    elif ledger["status"] != "matched":
        incomplete.append(f"the same-point ledger state was not compared: {ledger['detail']}")
    verdict = "failed" if problems else "incomplete" if incomplete else "passed"
    return {"verdict": verdict, "problems": problems, "incomplete": incomplete,
            "blocksFollowed": len(scala), "blocksOnReferenceChain": matched,
            "dijkstraBlocks": sum(1 for b in upto if b["era"] == "dijkstra"),
            "forkAtStop": None if fork is None else {"blockNo": fork["blockNo"], "slot": fork["slot"],
                                                     "hash": fork["hash"]},
            "tip": scala[-1] if scala else None,
            "announcedEbs": len(announced),
            "ebBodiesHeld": sum(1 for b in announced if store.get(b["announces"], {}).get("body")),
            "ebClosuresHeld": sum(1 for b in announced if store.get(b["announces"], {}).get("closureTxs") is not None),
            "certifyingBlocksApplied": sum(1 for c in certifying if c["closureTxs"] is not None),
            "certifying": certifying, "errorLines": len(errors), "warnings": warnings, "ledger": ledger}


def whole_verdict(whole):
    """{"status": "matched" | "differs" | "not compared", "detail"} of a `compare.whole_state` result."""
    if whole is None:
        return {"status": "not compared", "detail": "not asked"}
    if "answers" not in whole:
        return {"status": "not compared",
                "detail": {k: whole.get(k) for k in ("skipped", "error", "kind", "stage", "point") if k in whole}}
    unequal = {label: r for label, r in whole["answers"].items() if not r.get("matched")}
    if not unequal:
        return {"status": "matched", "detail": {"slot": whole.get("slot"), "era": whole.get("era"),
                                                "queries": sorted(whole["answers"])}}
    return {"status": "differs", "detail": {"slot": whole.get("slot"), "era": whole.get("era"),
                                            "queries": {k: v.get("problems") for k, v in unequal.items()}}}


# ---------------------------------------------------------------- the follower's run

def start(args, voter=None):
    """Start the follower; returns its `devnet.py follow` process. Its output goes to its own log in the run
    directory (`follower.jsonl`), which `follow` writes."""
    return subprocess.Popen(follower_command(args.name, args.follow_node, args.scala_cpus, voter),
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def problem(root, process, kinds):
    """The first reason the follower already fails, or None: it ended, logged an error line or unparsed output."""
    events, other = read_log(SINGLE_FOLLOWER.log(root))
    errors = failing(events, kinds)
    if errors:
        return f"the follower logged {errors[0].get('event')}: {json.dumps(errors[0])[:600]}"
    if other:
        return f"unparsed follower output: {other[0][:300]}"
    if process.poll() is not None:
        return f"the follower ended with {process.returncode}"
    return None


def catch_up(root, process, kinds, target, seconds, log):
    """Wait until the follower's tip is at block `target` or past it, at most `seconds`; returns the reason it did
    not (None when it did). Never raises."""
    deadline, last, reported = time.monotonic() + seconds, None, 0.0
    while True:
        events, _ = read_log(SINGLE_FOLLOWER.log(root))
        last = tip_block(events)
        if last is not None and last >= target:
            return None
        failed = problem(root, process, kinds)
        if failed:
            return failed
        if time.monotonic() > deadline:
            return f"the follower's tip is block {last}, not block {target}, after {seconds} s"
        if time.monotonic() - reported > 30:
            log({"followerTip": last, "waitingFor": target})
            reported = time.monotonic()
        time.sleep(2)


def ledger_state(args):
    """The same-point ledger state of the follower and reference node 1, at the follower's tip; never raises."""
    try:
        return compare.whole_state(args.name, "dijkstra", attempts=5, relay="perl", eras=LEIOS_ERAS,
                                   new_epoch_fields=ledgercompare.LEIOS_NEW_EPOCH_STATE_FIELDS)
    except Exception as e:  # a defect of the harness is a result to report, not the end of the run
        return {"error": repr(e)[:600], "kind": "harness"}


def stop(args, process):
    """Stop the follower with SIGTERM (`docker stop`), so it ends its log and releases its store; returns the exit
    status of its `follow` process (None if it did not end)."""
    docker("stop", "-t", str(STOP_SECONDS), SINGLE_FOLLOWER.container(args.name), check=False,
           timeout=STOP_SECONDS + 60)
    try:
        return process.wait(timeout=60)
    except subprocess.TimeoutExpired:
        return None


def remove(name):
    """The follower's container, removed (it lives in the reference container's network namespace)."""
    subprocess.run(["docker", "rm", "-f", SINGLE_FOLLOWER.container(name)], capture_output=True)


STORE_CHECK_SECONDS = 600


def store_check(args, root, hashes, run=subprocess.run):
    """What the stopped follower's endorser-block store holds of each of `hashes`, by `LeiosStoreCheckMain` run on
    the run's frozen classes and the `tools` classes; ({hash: {...}}, problems). The helper container's id is
    written to a file (`docker run --cidfile`), and on every exit, a timeout or an interruption too, that container
    (by its id, or by its name before docker wrote one) and the copies of the classes are removed. `run` runs the
    docker commands (`subprocess.run`)."""
    if not hashes:
        return {}, []
    if not any(TOOLS_CLASSES.rglob("LeiosStoreCheckMain*.class")):
        return {}, [f"no compiled {STORE_CHECK}; run tools/compile in the checkout's Docker build"]
    tools, checks, cidfile = root / "tools-classes", root / "check-classes", root / "leios-store.cid"
    name = f"{args.name}-leios-store"
    try:
        cidfile.unlink(missing_ok=True)
        shutil.rmtree(tools, ignore_errors=True)
        shutil.copytree(TOOLS_CLASSES, tools)
        classpath = freeze_classes(root, None, frozen=checks) + ":/run/tools"
        db = SINGLE_FOLLOWER.directory(root, "db")
        command = scala_container(args, root, classpath, STORE_CHECK, ["--db", "/db", "--hashes", ",".join(hashes)],
                                  mounts=[*database_mounts(db), f"{tools}:/run/tools:ro"], name=name, cpus=1,
                                  classes=checks)
        try:
            r = run(["docker", command[0], "--cidfile", str(cidfile), *command[1:]], capture_output=True, text=True,
                    timeout=STORE_CHECK_SECONDS)
        except subprocess.TimeoutExpired:
            return {}, [f"{STORE_CHECK} did not end within {STORE_CHECK_SECONDS} s"]
        found, errors = store_lines(r.stdout)
        problems = [f"{STORE_CHECK}: {e[:300]}" for e in errors]
        if r.returncode != 0:
            problems.append(f"{STORE_CHECK} ended with {r.returncode}: {r.stderr.strip()[-600:]}")
        return found, problems
    finally:
        helper = cidfile.read_text().strip() if cidfile.exists() else ""
        run(["docker", "rm", "-f", helper or name], capture_output=True, text=True)
        cidfile.unlink(missing_ok=True)
        shutil.rmtree(tools, ignore_errors=True)
        shutil.rmtree(checks, ignore_errors=True)


def final_log(root):
    events, other, cut = read_final_log(SINGLE_FOLLOWER.log(root))
    return events, other + ([cut] if cut and not cut.startswith("{") else [])
