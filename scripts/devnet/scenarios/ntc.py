# SPDX-License-Identifier: Apache-2.0
"""The `ntc` scenario: the Scala node's node-to-client interface against the Haskell node's.

Three comparisons on one cluster, the Scala node following it with `--local-socket`:

  1. LocalStateQuery, byte for byte: while a transaction load runs, each round asks the Scala node for
     its volatile tip, acquires the same block on the reference node and sends every query of the
     battery (`ntcqueries`) to both; the answers must be equal CBOR. Reported per query, matched / total.
  2. `cardano-cli conway query ...` against both sockets: the commands run back to back and a pair
     counts only when the tip read before and after is the same block on both nodes, so the two
     outputs describe one state. Reported per command, matched / total.
  3. Submission: a valid transaction through the Scala socket (it must reach a block), one with the
     validity flag false (it must take its collateral on both nodes), and each
     refused transaction of `ntccases` to both nodes, comparing the raw rejection and the text
     `cardano-cli transaction submit` prints.
  4. Local ChainSync, byte for byte: while the load runs, both sockets are followed from Origin (the
     Scala node serves its immutable store and then its volatile chain), then from the tip, and
     again from a recent block (a backlog); the whole blocks (`#6.24(bytes)` items) must be equal.
  4b. `cardano-cli latest` as a user runs it against the Scala socket (`ntccli`): `query tip` (also with
     `CARDANO_NODE_SOCKET_PATH`), protocol-parameters, utxo, era-history, slot-number, and a
     `transaction build` with a change address (the body byte-equal to the reference node's) that is signed,
     submitted through the Scala socket and must reach a block; no connection may end for another
     reason than the client finishing.
  5. LocalTxMonitor, byte for byte: bursts of chained transactions are submitted through the Scala
     socket (it forwards them to the reference node); one snapshot of each node is walked (sizes,
     measures, every transaction, membership) and, when both hold the same slot and transactions,
     every raw answer must be equal. `cardano-cli conway query tx-mempool info|next-tx|tx-exists`
     is compared as text on both sockets, and AwaitAcquire must wake on both.
"""
import json
import re
import subprocess
import sys
import time

from .. import cborspan, gapmask, ntccli, ntcquery
from ..ntclocal import Session, compare_follow, live_follow, monitor_rounds, await_wakes, tx_id
from ..cluster import freeze_classes, scala_container, up
from ..ntccases import CLI, Builder, corpus
from ..ntcqueries import compare_round
from ..ntcversions import compare_versions
from ..util import ENTRY, ENV, MAGIC, QUERY_PORT, REFERENCE_IMAGE, USER, data_root, docker, in_ref, query, read_log, ref
import threading

SCALA_CLI = "/opt/reference/bin/cardano-cli"

# `cardano-cli conway query` commands compared as text; {addr} is a funded address, {pool} a pool id
CLI_QUERIES = [
    ("protocol-parameters", "protocol-parameters"),
    ("utxo --whole-utxo", "utxo --whole-utxo"),
    ("utxo --address", "utxo --address {addr}"),
    ("utxo --tx-in", "utxo --tx-in {txin}"),
    ("stake-distribution", "stake-distribution"),
    ("stake-pools", "stake-pools"),
    # the wire answer holds no delegators (`stakePoolStateToStakePoolParams`): both nodes print `"spsDelegators": []`
    # for pools that have delegators (the genesis delegators), which is #114
    ("pool-params", "pool-params --all-stake-pools"),
    ("pool-state", "pool-state --all-stake-pools"),
    ("stake-snapshot", "stake-snapshot --all-stake-pools"),
    ("stake-address-info", "stake-address-info --address {stake}"),
    ("gov-state", "gov-state"),
    ("drep-state", "drep-state --all-dreps"),
    ("drep-stake-distribution", "drep-stake-distribution --all-dreps"),
    ("committee-state", "committee-state"),
    ("constitution", "constitution"),
    ("ratify-state", "ratify-state"),
    ("future-pparams", "future-pparams"),
    ("treasury", "treasury"),
    ("spo-stake-distribution", "spo-stake-distribution --all-spos"),
    ("era-history", "era-history"),
    ("tip", "tip"),
    ("slot-number", "slot-number 2026-01-01T00:00:00Z"),
    ("protocol-state", "protocol-state"),
    ("ledger-state", "ledger-state"),
]

# `cardano-cli query ledger-state` prints the whole state: the Scala node answers it since the mirror (S9) but
# differs from the reference inside the documented gaps (`gapmask`), which the LocalStateQuery comparison masks
# and a text comparison cannot. Reported, not failed on.
NOT_IMPLEMENTED = {"ledger-state"}


def mempool_bytes(args):
    """The Scala mempool capacity that LocalTxMonitor reports as the reference does by default: two
    block units (`2 * (maxBlockBodySize - 1024)`), the capacity `cardano-node` computes without an override."""
    return 2 * (query(args.name, "protocol-parameters")["maxBlockBodySize"] - 1024)


def start_scala(args, root):
    """The Scala node following pool 1 with a node-to-client socket the host can reach."""
    classpath = freeze_classes(root)
    sock = root / "scala-sock"
    sock.mkdir(exist_ok=True)
    sock.chmod(0o777)
    db = root / "scala-db"
    db.mkdir(exist_ok=True)
    db.chmod(0o777)
    ports = json.loads((root / "ports.json").read_text())
    program = ["follow", "--genesis-dir", "/genesis", "--query-port", str(QUERY_PORT), "--db", "/db",
               "--mempool-bytes", str(mempool_bytes(args)),
               "--local-socket", "/sock/node.socket", "--peer", f"127.0.0.1:{ports['1']}"]
    command = scala_container(args, root, classpath, "xyz.kd5ujc.scarab.node.Main", program,
                              mounts=[f"{root / 'genesis'}:/genesis:ro", f"{db}:/db", f"{sock}:/sock"],
                              name=f"{args.name}-scala")
    log = (root / "follower.jsonl").open("a")
    return subprocess.Popen(["docker", *command], stdout=log, stderr=subprocess.STDOUT, text=True)


def start_cli_container(args, root):
    """A long-lived reference-image container with the Scala socket mounted, to run `cardano-cli` in."""
    docker("run", "-d", "--name", f"{args.name}-cli", "--pull=never", "--user", USER,
           "-v", f"{root / 'scala-sock'}:/sock", "--entrypoint", "/bin/sh", REFERENCE_IMAGE, "-c", "sleep infinity")


def cli(args, which, text, timeout=90):
    """(exit code, stdout + stderr) of `cardano-cli conway <text>` against one node's socket."""
    if which == "h":
        script = f"{CLI} {text} --testnet-magic {MAGIC} --socket-path {ENV}/socket/node1/sock"
        r = docker("exec", ref(args.name), "/bin/sh", "-c", script, check=False, timeout=timeout)
    else:
        r = docker("exec", f"{args.name}-cli", "/bin/sh", "-c",
                   f"{SCALA_CLI} conway {text} --testnet-magic {MAGIC} --socket-path /sock/node.socket",
                   check=False, timeout=timeout)
    return r.returncode, (r.stdout + r.stderr).strip()


def point_of(args, which, root):
    """The volatile tip as (slot, hash hex), through LocalStateQuery."""
    try:
        if which == "h":
            session = ntcquery.LocalStateQuery(ref(args.name), f"{ENV}/socket/node1/sock", MAGIC)
        else:
            session = ntcquery.LocalStateQuery(None, str(root / "scala-sock" / "node.socket"), MAGIC, host=True)
        with session as s:
            s.acquire()
            p = s.query_raw([3])
            return (p[0], p[1].hex()) if p else None
    except (RuntimeError, EOFError, OSError):
        return None


def wait_following(args, root, scala, seconds=180):
    log = root / "follower.jsonl"
    deadline = time.monotonic() + seconds
    sock = root / "scala-sock" / "node.socket"
    while time.monotonic() < deadline:
        events, _ = read_log(log)
        if sock.exists() and sum(1 for e in events if e["event"] == "extended") >= 5:
            return
        if scala.poll() is not None:
            raise SystemExit(f"the Scala node exited with {scala.returncode}; see {log}")
        time.sleep(1)
    raise SystemExit("the Scala node did not follow the chain within the time limit")


def settle(args, root, seconds=90):
    """Wait until both nodes have the same tip."""
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        h, s = point_of(args, "h", root), point_of(args, "s", root)
        if h and s and h == s:
            return h
        time.sleep(0.5)
    return None


def query_rounds(args, root, stats, until, every, masks=None):
    rounds = 0
    while time.monotonic() < until:
        try:
            point, results = compare_round(args.name, f"{ENV}/socket/node1/sock",
                                           str(root / "scala-sock" / "node.socket"), dump_dir=root,
                                           masks=masks)
        except (RuntimeError, EOFError, OSError) as e:
            print(json.dumps({"round": "failed", "error": str(e)[:200]}), flush=True)
            time.sleep(1)
            continue
        rounds += 1
        for label, implemented, matched, n_ref, n_scala in results:
            row = stats.setdefault(label, {"implemented": implemented, "matched": 0, "total": 0, "lastDiff": None})
            row["total"] += 1
            if matched:
                row["matched"] += 1
            else:
                row["lastDiff"] = {"slot": point[0], "referenceBytes": n_ref, "scalaBytes": n_scala}
        print(json.dumps({"round": rounds, "slot": point[0], "matched": sum(1 for r in results if r[2]),
                          "queries": len(results)}), flush=True)
        for label, implemented, matched, n_ref, n_scala in results:
            if implemented and not matched:
                print(json.dumps({"differs": label, "slot": point[0], "referenceBytes": n_ref,
                                  "scalaBytes": n_scala}), flush=True)
        time.sleep(every)
    return rounds


def cli_comparison(args, root, subjects, attempts=20):
    """Run each command against both sockets until a pair is bracketed by identical tips."""
    out = {}
    for label, text in subjects:
        record = {"matched": 0, "total": 0, "unbracketed": 0, "scalaFailed": 0}
        outcome = None
        for _ in range(attempts):
            before = (point_of(args, "h", root), point_of(args, "s", root))
            h, s = cli(args, "h", text), cli(args, "s", text)
            after = (point_of(args, "h", root), point_of(args, "s", root))
            if not (before[0] and before[0] == before[1] == after[0] == after[1]):
                record["unbracketed"] += 1
                continue
            outcome = (h, s)
            break
        if outcome is not None:
            record["total"] = 1
            record["matched"] = int(outcome[0] == outcome[1])
            if outcome[0] != outcome[1]:
                record["reference"] = outcome[0][1][:400]
                record["scala"] = outcome[1][1][:400]
        out[label] = record
        print(json.dumps({"cli": label, **{k: v for k, v in record.items() if k in ("matched", "total", "unbracketed")}}),
              flush=True)
    return out


def submit_scala_cli(args, tx):
    """`cardano-cli transaction submit` of the bytes through the Scala socket, and the same through the reference."""
    text = json.dumps({"type": "Tx ConwayEra", "description": "", "cborHex": tx.hex()})
    out = {}
    for which, container in (("h", ref(args.name)), ("s", f"{args.name}-cli")):
        subprocess.run(["docker", "exec", "-i", container, "/bin/sh", "-c", "cat > /tmp/tx.json"], input=text,
                       text=True, check=True)
    out["h"] = cli(args, "h", "transaction submit --tx-file /tmp/tx.json")
    out["s"] = cli(args, "s", "transaction submit --tx-file /tmp/tx.json")
    return out


def rendered(text, rule=""):
    """The error line `cardano-cli` prints (without its call stack). The slot a validity interval is
    judged at is the node's current slot, which differs between two submissions in time, so it is masked."""
    line = next((l for l in text.splitlines() if l.startswith("Error:")), text.splitlines()[0] if text else "")
    if rule == "OutsideValidityIntervalUTxO":
        line = re.sub(r"\(SlotNo \d+\)(\)+) :\|", r"(SlotNo <current>)\1 :|", line)
    return line


def reasons(args, root, tx):
    """The raw rejection of each node for one transaction, over LocalTxSubmission."""
    got = {}
    for which in ("h", "s"):
        try:
            if which == "h":
                session = ntcquery.LocalStateQuery(ref(args.name), f"{ENV}/socket/node1/sock", MAGIC)
            else:
                session = ntcquery.LocalStateQuery(None, str(root / "scala-sock" / "node.socket"), MAGIC, host=True)
            with session as s:
                got[which] = s.submit(tx)
        except (RuntimeError, EOFError, OSError) as e:
            got[which] = f"ERR {e}".encode()
    return got


def first_failure(reason):
    """The bytes of the first failure of a rejection `[[era, [failure, ...]]]` (None if it is not one)."""
    try:
        outer = cborspan.children(reason, 0)
        era_and_failures = cborspan.children(reason, outer[0][0])
        failures = cborspan.children(reason, era_and_failures[1][0])
        return reason[failures[0][0]:failures[0][1]]
    except (cborspan.Malformed, IndexError):
        return None


def submissions(args, root):
    cases = corpus(args.name, f"{ENV}/socket/node1/sock")
    results = []
    for case in cases:
        raw = reasons(args, root, case.tx)
        text = submit_scala_cli(args, case.tx)
        # the slot a transaction is judged at differs by a block or two between the nodes
        same_text = rendered(text["h"][1], case.ledger_rule) == rendered(text["s"][1], case.ledger_rule)
        same_raw = raw["h"] == raw["s"]
        if case.first_only:
            same_text = rendered(text["h"][1]).split(" :| ")[0] == rendered(text["s"][1]).split(" :| ")[0]
            same_raw = first_failure(raw["h"]) == first_failure(raw["s"])
        named = case.ledger_rule in text["h"][1]
        row = {"case": case.label, "rule": case.ledger_rule, "referenceNamesRule": named, "rawEqual": same_raw,
               "textEqual": same_text}
        if case.label.startswith("withdrawal-network"):
            row["referenceRaw"] = raw["h"].hex()  # kept as a test vector of the failure encoding
        if not (same_raw and same_text):
            row["reference"] = rendered(text["h"][1])[:500]
            row["scala"] = rendered(text["s"][1])[:500]
        results.append(row)
        print(json.dumps(row), flush=True)
    return results


def valid_submission(args, root):
    """A valid transaction through the Scala socket: it must be accepted and reach a block. The
    wallets the load used may have transactions in flight, so a refusal for spent inputs tries
    the next wallet."""
    from ..ntccases import Builder
    b = Builder(args.name, f"{ENV}/socket/node1/sock")
    target = b.sh(f"{CLI} address build --payment-verification-key-file {ENV}/utxo-keys/utxo1/utxo.vkey "
                  f"--testnet-magic {MAGIC}")
    result = {}
    for number in (3, 2, 1):
        w = b.wallet(number)
        tx = b.tx(w, outs=[(target, 12345678), (w["addr"], w["lovelace"] - 200000 - 12345678)])
        txid = in_ref(args.name, f"{CLI} transaction txid --tx-file /work/case{b.n}.signed --output-text").stdout.strip()
        text = submit_scala_cli(args, tx)
        accepted = text["s"][0] == 0
        included = False
        deadline = time.monotonic() + 60
        while accepted and time.monotonic() < deadline:
            utxo = in_ref(args.name, f"{CLI} query utxo --tx-in {txid}#0 --testnet-magic {MAGIC} "
                                     f"--socket-path {ENV}/socket/node1/sock --output-json").stdout
            if json.loads(utxo):
                included = True
                break
            time.sleep(1)
        result = {"txid": txid, "wallet": number, "acceptedByScala": accepted, "reachedABlock": included,
                  "output": text["s"][1][:300]}
        if accepted:
            break
        result["referenceHolds"] = cli(args, "h", f"query utxo --tx-in {w['txin']} --output-json")[1][:200]
        result["scalaHolds"] = cli(args, "s", f"query utxo --tx-in {w['txin']} --output-json")[1][:200]
        result["tips"] = {"reference": point_of(args, "h", root), "scala": point_of(args, "s", root)}
        print(json.dumps({"validSubmissionRetry": result}), flush=True)
    return result


def flagged_submission(args):
    """A transaction with the validity flag false and a withdrawal on the ledger's own network, through
    the Scala socket: it must be accepted and reach a block of the reference node (which validates it),
    where its collateral is taken and the input it spends stays; the Scala node must hold the same."""
    from ..ntccases import FAILS, Builder, script_tx
    b = Builder(args.name, f"{ENV}/socket/node1/sock")
    result = {}
    # the wallets the load uses may have transactions in flight: a refusal for spent inputs tries the next
    for spend, pay in ((3, 2), (2, 3), (1, 3)):
        w, c = b.wallet(spend), b.wallet(pay)
        tx, collateral = script_tx(b, w, c, FAILS, invalid=True)
        text = submit_scala_cli(args, tx)
        accepted = text["s"][0] == 0
        result = {"spend": w["txin"], "collateral": collateral, "acceptedByScala": accepted,
                  "output": text["s"][1][:300], "collateralTaken": False, "spendKept": False}
        deadline = time.monotonic() + 90
        while accepted and time.monotonic() < deadline:
            held = {which: json.loads(cli(args, which, f"query utxo --tx-in {collateral} --output-json")[1] or "{}")
                    for which in ("h", "s")}
            if not held["h"] and not held["s"]:
                result["collateralTaken"] = True
                break
            time.sleep(1)
        if result["collateralTaken"]:
            kept = {which: cli(args, which, f"query utxo --tx-in {w['txin']} --output-json")[1] for which in ("h", "s")}
            result["spendKept"] = all(w["txin"] in text for text in kept.values())
        if accepted:
            break
        print(json.dumps({"flaggedSubmissionRetry": result}), flush=True)
    return result


def open_h(args):
    return Session(ref(args.name), f"{ENV}/socket/node1/sock", MAGIC)


def open_s(root):
    return Session(None, str(root / "scala-sock" / "node.socket"), MAGIC, host=True)


def chain_sync_comparison(args, root, out):
    """Local ChainSync on both sockets: from Origin (the Scala node reads its immutable store, then its
    volatile chain), from the tip as it moves, and from a recent block (served as a backlog)."""
    def opener_h():
        return open_h(args)

    def opener_s():
        return open_s(root)

    try:
        out["fromOrigin"] = compare_follow(opener_h, opener_s, None, args.chain_sync_blocks, 150)
        live = live_follow(lambda: point_of(args, "s", root),
                           lambda tip: compare_follow(opener_h, opener_s, (tip[0], bytes.fromhex(tip[1])), 12, 150))
        if live:
            out["live"] = live
        if "live" in out:
            points = out["live"]["points"]
            if len(points) >= 5:
                slot, hash_hex = points[-5]
                out["backlog"] = compare_follow(opener_h, opener_s, (slot, bytes.fromhex(hash_hex)), 4, 60)
    except Exception as e:  # noqa: BLE001 - reported as a failure of the scenario
        out["error"] = repr(e)
    for key in ("fromOrigin", "live", "backlog"):
        if key in out:
            out[key].pop("points", None)
    print(json.dumps({"chainSync": out}), flush=True)


def mempool_chain(builder, wallet_number, count):
    """`count` chained transactions of one wallet: each spends the output of the one before it, so all of
    them are valid in one mempool."""
    from ..ntclocal import raw_tx_id
    w = builder.wallet(wallet_number)
    txin, lovelace, txs = w["txin"], w["lovelace"], []
    for _ in range(count):
        tx = builder.tx({**w, "txin": txin, "lovelace": lovelace}, fee=200000)
        txs.append(tx)
        txin, lovelace = f"{raw_tx_id(tx).hex()}#0", lovelace - 200000
    return txs


def cli_script(args, which, commands):
    """The outputs of several `cardano-cli conway` commands run in one `docker exec` against one socket."""
    if which == "h":
        calls = [f"{CLI} {c} --testnet-magic {MAGIC} --socket-path {ENV}/socket/node1/sock 2>&1" for c in commands]
        container = ref(args.name)
    else:
        calls = [f"{SCALA_CLI} conway {c} --testnet-magic {MAGIC} --socket-path /sock/node.socket 2>&1" for c in commands]
        container = f"{args.name}-cli"
    script = "( " + "; echo @@@; ".join(calls) + " )"
    r = docker("exec", container, "/bin/sh", "-c", script, check=False, timeout=120)
    return [part.strip() for part in r.stdout.split("@@@")]


def cli_mempool(args, ident):
    """`cardano-cli conway query tx-mempool info | next-tx | tx-exists` on both sockets, run at the same
    time (one `docker exec` each): counted when `info` is the same on both before and after, so the
    mempool did not change in between, and then every output must be equal."""
    labels = ["info", "next-tx", "tx-exists held", "tx-exists absent", "info after"]
    commands = ["query tx-mempool info", "query tx-mempool next-tx", f"query tx-mempool tx-exists {ident}",
                f"query tx-mempool tx-exists {'0' * 64}", "query tx-mempool info"]
    outputs = {}
    threads = [threading.Thread(target=lambda w=w: outputs.__setitem__(w, cli_script(args, w, commands))) for w in "hs"]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    h, s = outputs["h"], outputs["s"]
    complete = len(h) == len(s) == len(commands)
    bracketed = complete and h[0] == s[0] == h[-1] == s[-1]
    return {"bracketed": bracketed, "nonEmpty": complete and '"numberOfTxs": 0' not in h[0],
            "equal": {label: h[i] == s[i] for i, label in enumerate(labels)} if complete else {},
            "answers": {label: [h[i][:160], s[i][:160]] for i, label in enumerate(labels)} if complete else {"h": h, "s": s}}


def monitor_comparison(args, root, out):
    """LocalTxMonitor on both sockets: empty mempools, then bursts of chained transactions submitted
    through the Scala socket (it forwards them to the reference node)."""
    builder = Builder(args.name, f"{ENV}/socket/node1/sock")
    bursts = [mempool_chain(builder, n, args.monitor_burst) for n in (1, 2, 3)]
    stats = {"rounds": 0, "comparable": 0, "nonEmpty": 0, "differences": [], "maxTxs": 0}
    clis = []
    with open_h(args) as h, open_s(root) as s:
        monitor_rounds(h, s, time.monotonic() + 4, stats=stats)
        out["quiet"] = {"comparable": stats["comparable"], "rounds": stats["rounds"]}
        clis.append({"state": "quiet", **cli_mempool(args, "0" * 64)})
        for number, burst in enumerate(bursts, 1):
            with open_s(root) as submitter:
                refused = [r for r in (submitter.submit(tx) for tx in burst) if r is not None]
            ids = [tx_id_of(tx) for tx in burst]
            before = stats["nonEmpty"]
            deadline = time.monotonic() + 30
            # The transactions reach the reference node by TxSubmission2, which it paces itself (it pulls
            # them a request at a time, a fraction of a second behind the Scala node): the cardano-cli
            # queries start only once the monitor sees the whole burst in the reference node, so that
            # `info` before and after can agree. They run while the monitor keeps polling; a block may
            # still empty the pools first.
            submitted = time.monotonic()
            monitor_rounds(h, s, submitted + 10, stats=stats, stop=lambda: stats["last"][0] == len(burst))
            settle = time.monotonic() - submitted
            attempt = {}
            cli_thread = threading.Thread(target=lambda: attempt.update(cli_mempool(args, ids[0])))
            cli_thread.start()
            monitor_rounds(h, s, deadline, stats=stats,
                           stop=lambda: stats["nonEmpty"] > before + 2 and stats["last"] == (0, 0))
            cli_thread.join()
            clis.append({"state": f"burst {number}", "secondsToFill": round(settle, 2), **attempt})
            print(json.dumps({"monitorBurst": number, "refused": len(refused), "rounds": stats["rounds"],
                              "comparable": stats["comparable"], "nonEmpty": stats["nonEmpty"]}), flush=True)
    out.update({"rounds": stats["rounds"], "comparable": stats["comparable"], "nonEmpty": stats["nonEmpty"],
                "maxTxs": stats["maxTxs"], "differences": stats["differences"][:5], "cli": clis})
    # AwaitAcquire: after one acquire, both wait; a transaction through the Scala socket changes both pools
    extra = mempool_chain(builder, 1, 1)
    with open_h(args) as h2, open_s(root) as s2:
        def trigger():
            time.sleep(1)
            with open_s(root) as submitter:
                submitter.submit(extra[0])
        first, answers = await_wakes(h2, s2, trigger)
    out["awaitAcquire"] = {"slotsHeld": list(first), "answers": {k: v for k, v in answers.items()}}
    print(json.dumps({"monitor": out}, default=str), flush=True)


def tx_id_of(tx):
    from ..ntclocal import raw_tx_id
    return raw_tx_id(tx).hex()


def ntc(args):
    root = data_root(args.name)
    up(args)
    scala, loaders = None, []
    failures, summary = [], {}
    try:
        scala = start_scala(args, root)
        start_cli_container(args, root)
        wait_following(args, root, scala)
        for command in (["load", "--seed", str(args.seed), "--interval", "0.2"], ["govload"]):
            log = root / f"{command[0]}.log"
            loaders.append(subprocess.Popen([sys.executable, str(ENTRY), "--name", args.name, *command],
                                            stdout=log.open("w"), stderr=subprocess.STDOUT))
        stats, masks = {}, {}
        started = time.monotonic()
        # let the load build some state (stake, pools, DReps, proposals) before the first round
        time.sleep(args.warmup)
        chain_sync = {}
        follower_thread = threading.Thread(target=chain_sync_comparison, args=(args, root, chain_sync))
        follower_thread.start()
        rounds = query_rounds(args, root, stats, started + args.seconds, args.every, masks)
        follower_thread.join(300)
        for loader in loaders:
            loader.terminate()
            loader.wait()
        loaders = []
        print(json.dumps({"settled": settle(args, root)}), flush=True)
        versions = compare_versions(args.name, f"{ENV}/socket/node1/sock",
                                   str(root / "scala-sock" / "node.socket"), root)
        print(json.dumps({"queryVersions": versions}), flush=True)
        if versions["matched"] != versions["compared"] or versions["rejected"] != versions["expectedRejections"] \
                or not all(versions["boundary"].values()):
            failures.append("node-to-client version-specific query answers or support gates differ")
        # the last queries describe the final state, and the CLI comparisons run on a quiet pool of wallets
        info = {}
        funded = json.loads(in_ref(args.name, f"{CLI} query utxo --whole-utxo --testnet-magic {MAGIC} "
                                              f"--socket-path {ENV}/socket/node1/sock --output-json").stdout)
        info["txin"] = next(iter(funded))
        info["addr"] = next(v["address"] for v in funded.values())
        delegator = f"{ENV}/stake-delegators/delegator1/staking.vkey"
        info["stake"] = in_ref(args.name, f"{CLI} stake-address build --stake-verification-key-file {delegator} "
                                          f"--testnet-magic {MAGIC}").stdout.strip()
        commands = [(label, "query " + text.format(**info)) for label, text in CLI_QUERIES]
        cli_results = cli_comparison(args, root, commands)
        sub_results = submissions(args, root)
        valid = valid_submission(args, root)
        print(json.dumps({"validSubmission": valid}), flush=True)
        flagged = flagged_submission(args)
        print(json.dumps({"flaggedSubmission": flagged}), flush=True)
        # what a user runs against the Scala socket: queries, then build, sign and submit
        mark = len(read_log(root / "follower.jsonl")[0])
        user_queries = ntccli.queries(args, info["addr"], info["txin"])
        user_build = ntccli.build_and_submit(args)
        user_closes = ntccli.unexpected_closes(read_log(root / "follower.jsonl")[0][mark:])
        print(json.dumps({"userCli": {"build": user_build, "closes": len(user_closes)}}), flush=True)
        failures.extend(ntccli.failures(user_queries, user_build, user_closes))
        monitor = {}
        try:
            monitor_comparison(args, root, monitor)
        except (RuntimeError, EOFError, OSError, AssertionError) as e:
            monitor["error"] = repr(e)

        implemented = {k: v for k, v in stats.items() if v["implemented"]}
        gaps = {k: v for k, v in stats.items() if not v["implemented"]}
        summary = {
            "rounds": rounds,
            "queryVersions": versions,
            "queries": {"matched": sum(v["matched"] for v in implemented.values()),
                        "total": sum(v["total"] for v in implemented.values()),
                        "perQuery": {k: f"{v['matched']}/{v['total']}" for k, v in sorted(implemented.items())},
                        "notImplemented": {k: f"{v['matched']}/{v['total']}" for k, v in sorted(gaps.items())}},
            "cli": {k: (f"{v['matched']}/{v['total']}" if v["total"] else f"unbracketed x{v['unbracketed']}")
                    for k, v in cli_results.items()},
            "cliDetails": {k: v for k, v in cli_results.items() if v["total"] and not v["matched"]},
            "submissions": {"cases": len(sub_results),
                            "rawEqual": sum(r["rawEqual"] for r in sub_results),
                            "textEqual": sum(r["textEqual"] for r in sub_results),
                            "referenceNamedTheRule": sum(r["referenceNamesRule"] for r in sub_results),
                            "differences": [r for r in sub_results if not (r["rawEqual"] and r["textEqual"])]},
            "ledgerState": {"gaps": list(gapmask.GAPS), "perQuery": masks},
            "validSubmission": valid,
            "flaggedSubmission": flagged,
            "userCli": {"queries": user_queries, "build": user_build,
                        "unexpectedCloses": [c["reason"] for c in user_closes]},
            "chainSync": chain_sync,
            "txMonitor": monitor,
        }
        mismatched = [k for k, v in implemented.items() if v["matched"] != v["total"]]
        if mismatched:
            failures.append(f"queries that differ from the reference: {mismatched}")
        if not rounds:
            failures.append("no comparison round completed")
        for label in gapmask.LABELS:
            row = masks.get(label)
            if row is None or row.get("equalMasked") != row.get("answers") or row.get("problems"):
                failures.append(f"{label} is not equal to the reference's outside the documented gaps: {row}")
        badcli = [k for k, v in cli_results.items()
                  if v["total"] and not v["matched"] and k not in NOT_IMPLEMENTED]
        if badcli:
            failures.append(f"cardano-cli outputs that differ: {badcli}")
        if any(r["case"] and not r["referenceNamesRule"] for r in sub_results):
            failures.append("a refusal was not the rule the case is built for (the case is wrong)")
        # the raw bytes of a validity-interval refusal hold the node's current slot, which moves
        # between two submissions; the text is compared with the slot masked
        if any(not r["textEqual"] or (not r["rawEqual"] and r["rule"] != "OutsideValidityIntervalUTxO")
               for r in sub_results):
            failures.append("a rejection differs from the reference's")
        if not (valid["acceptedByScala"] and valid["reachedABlock"]):
            failures.append(f"the valid transaction did not reach a block: {valid}")
        if not (flagged["acceptedByScala"] and flagged["collateralTaken"] and flagged["spendKept"]):
            failures.append(f"the transaction with the flag false did not take its collateral: {flagged}")
        for key in ("fromOrigin", "live", "backlog"):
            part = chain_sync.get(key)
            if part is None or part["differing"] or part["compared"] == 0 or part["scala"]["error"] \
                    or part["reference"]["error"] or not part["sameFirstInstruction"] \
                    or part["scala"]["first"][0] != 3:
                failures.append(f"local ChainSync {key}: {part}")
        if chain_sync.get("error"):
            failures.append(f"local ChainSync: {chain_sync['error']}")
        if monitor.get("error") or monitor.get("differences") or monitor.get("nonEmpty", 0) < 3:
            failures.append(f"LocalTxMonitor: {json.dumps(monitor, default=str)[:600]}")
        wake = monitor.get("awaitAcquire", {}).get("answers", {})
        if not all(isinstance(wake.get(k), int) for k in ("h", "s")):
            failures.append(f"AwaitAcquire did not wake on both nodes: {wake}")
        counted = [c for c in monitor.get("cli", []) if c["bracketed"]]
        if not any(c["nonEmpty"] for c in counted) or any(not all(c["equal"].values()) for c in counted):
            failures.append(f"tx-mempool cardano-cli outputs: {monitor.get('cli')}")
    finally:
        for loader in loaders:
            loader.terminate()
        docker("rm", "-f", f"{args.name}-scala", f"{args.name}-cli", check=False)
        if scala is not None:
            scala.wait()
    (root / "ntc-summary.json").write_text(json.dumps(summary, indent=2))
    print(json.dumps(summary, indent=2))
    if failures:
        print(json.dumps({"failed": failures}), flush=True)
    sys.exit(1 if failures else 0)
