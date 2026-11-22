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

The judge (`judge_follower`) requires: the follower's selected chain, rebuilt from its `extended` and `rolledBack`
lines (`selected_chain`), is the reference chain's prefix, a tip within `k` blocks of the end that is off it
being reported as a fork at the stop rather than failing; every certifying block of the reference chain up to the
follower's tip is on the follower's chain, and the closure of the endorser block it certifies is complete in the
follower's store; no line of an error or critical severity (the severities of `EventKind.scala`, `severities`) and
no unparsed output in the follower's log; and the same-point ledger state equal on every byte, or the comparison
incomplete, which is never a pass (CNF-2).
"""
import json
import re
import shutil
import subprocess
import sys
import time
from .. import compare
from ..cluster import database_mounts, freeze_classes, scala_container
from ..util import ENTRY, REPO, SINGLE_FOLLOWER, docker, read_final_log, read_log

BUILD = "leios-b004fe0c"
EVENT_KINDS = REPO / "node/src/main/scala/xyz/kd5ujc/scarab/node/log/EventKind.scala"
STORE_CHECK = "xyz.kd5ujc.scarab.node.tools.LeiosStoreCheckMain"
TOOLS_CLASSES = REPO / "tools/target/scala-3.9.0/classes"
FAILING = ("Error", "Critical")
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


def selected_chain(events):
    """The chain the follower selected at the end of its log: its `extended` blocks ({slot, blockNo, hash}) in order,
    each `rolledBack` cutting the chain back to its `to` point (Origin: `to` without a hash)."""
    chain = []
    for e in events:
        if e.get("event") == "extended":
            chain.append(e["block"])
        elif e.get("event") == "rolledBack":
            to = e.get("to") or {}
            if to.get("hash") is None:
                chain = []
                continue
            keep = next((i for i in range(len(chain) - 1, -1, -1) if chain[i]["hash"] == to["hash"]), None)
            chain = chain[:keep + 1] if keep is not None else []
    return chain


def follower_command(name, node, scala_cpus):
    """The `devnet.py follow` process of the follower of the Leios cluster."""
    return [sys.executable, str(ENTRY), "--name", name, "follow", "--reference-build", BUILD, "--db",
            "--local-socket", "--node", str(node), "--scala-cpus", str(scala_cpus)]


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


def judge_follower(reference, scala, store, events, other, kinds, security_param, whole=None, exit_code=None):
    """The verdict on the follower. `reference` is the chain every reference node holds in its ImmutableDB (described
    blocks, `leios_traffic.describe_leios_block`), `scala` the follower's selected chain (`selected_chain`), `store`
    what its endorser-block store holds of each EB hash (`store_lines`), `events` and `other` its log (`read_log`),
    `kinds` the severities of `severities`, `whole` the result of `compare.whole_state`.

    Returns {"verdict": "passed" | "incomplete" | "failed", "problems", "incomplete", ...counts}."""
    problems, incomplete = [], []
    by_no = {b["blockNo"]: b for b in reference}
    on_chain, fork = [], None
    for b in scala:
        ref_block = by_no.get(b["blockNo"])
        if ref_block is not None and ref_block["hash"] == b["hash"] and ref_block["slot"] == b["slot"]:
            on_chain.append(b)
            continue
        fork = b
        break
    if not scala:
        problems.append("the follower applied no block")
    elif fork is not None:
        depth = scala[-1]["blockNo"] - fork["blockNo"]
        where = ("is not the reference's" if fork["blockNo"] in by_no
                 else "is past the reference nodes' immutable chain")
        if depth >= security_param:
            problems.append(f"the follower's block {fork['blockNo']} (slot {fork['slot']}, {fork['hash'][:16]}) "
                            f"{where}, {depth} blocks below its tip")
    tip = on_chain[-1]["blockNo"] if on_chain else -1
    upto = [b for b in reference if b["blockNo"] <= tip]
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
    errors = [e for e in events if kinds.get(e.get("event")) in FAILING]
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
        if kinds.get(e.get("event")) == "Warning":
            warnings[e["event"]] = warnings.get(e["event"], 0) + 1
    ledger = whole_verdict(whole)
    if ledger["status"] == "differs":
        problems.append(f"the same-point ledger state differs: {ledger['detail']}")
    elif ledger["status"] != "matched":
        incomplete.append(f"the same-point ledger state was not compared: {ledger['detail']}")
    verdict = "failed" if problems else "incomplete" if incomplete else "passed"
    return {"verdict": verdict, "problems": problems, "incomplete": incomplete,
            "blocksFollowed": len(scala), "blocksOnReferenceChain": len(on_chain),
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

def start(args):
    """Start the follower; returns its `devnet.py follow` process. Its output goes to its own log in the run
    directory (`follower.jsonl`), which `follow` writes."""
    return subprocess.Popen(follower_command(args.name, args.follow_node, args.scala_cpus),
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def problem(root, process, kinds):
    """The first reason the follower already fails, or None: it ended, logged an error line or unparsed output."""
    events, other = read_log(SINGLE_FOLLOWER.log(root))
    errors = [e for e in events if kinds.get(e.get("event")) in FAILING]
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
        return compare.whole_state(args.name, "dijkstra", attempts=5, relay="perl", eras=LEIOS_ERAS)
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


def store_check(args, root, hashes):
    """What the stopped follower's endorser-block store holds of each of `hashes`, by `LeiosStoreCheckMain` run on
    the run's frozen classes and the `tools` classes; ({hash: {...}}, problems)."""
    if not hashes:
        return {}, []
    tools = root / "tools-classes"
    if not any(TOOLS_CLASSES.rglob("LeiosStoreCheckMain*.class")):
        return {}, [f"no compiled {STORE_CHECK}; run tools/compile in the checkout's Docker build"]
    shutil.rmtree(tools, ignore_errors=True)
    shutil.copytree(TOOLS_CLASSES, tools)
    classpath = freeze_classes(root, None, frozen=root / "check-classes") + ":/run/tools"
    db = SINGLE_FOLLOWER.directory(root, "db")
    command = scala_container(args, root, classpath, STORE_CHECK, ["--db", "/db", "--hashes", ",".join(hashes)],
                              mounts=[*database_mounts(db), f"{tools}:/run/tools:ro"], name=f"{args.name}-leios-store",
                              cpus=1, classes=root / "check-classes")
    r = subprocess.run(["docker", *command], capture_output=True, text=True, timeout=600)
    found, errors = store_lines(r.stdout)
    problems = [f"{STORE_CHECK}: {e[:300]}" for e in errors]
    if r.returncode != 0:
        problems.append(f"{STORE_CHECK} ended with {r.returncode}: {r.stderr.strip()[-600:]}")
    shutil.rmtree(tools, ignore_errors=True)
    shutil.rmtree(root / "check-classes", ignore_errors=True)
    return found, problems


def final_log(root):
    events, other, cut = read_final_log(SINGLE_FOLLOWER.log(root))
    return events, other + ([cut] if cut and not cut.startswith("{") else [])
