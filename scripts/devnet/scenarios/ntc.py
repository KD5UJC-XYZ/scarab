# SPDX-License-Identifier: Apache-2.0
"""The `ntc` scenario: the Scala node's node-to-client interface against the Haskell node's.

Three comparisons on one cluster, the Scala node following it with `--local-socket`:

  1. LocalStateQuery, byte for byte: while a transaction load runs, each round asks the Scala node for
     its volatile tip, acquires the same block on the reference node and sends every query of the
     battery (`ntcqueries`) to both; the answers must be equal CBOR. Reported per query, matched / total.
  2. `cardano-cli conway query ...` against both sockets: the commands run back to back and a pair
     counts only when the tip read before and after is the same block on both nodes, so the two
     outputs describe one state. Reported per command, matched / total.
  3. Submission: a valid transaction through the Scala socket (it must reach a block), and each
     refused transaction of `ntccases` to both nodes, comparing the raw rejection and the text
     `cardano-cli transaction submit` prints.
"""
import json
import subprocess
import sys
import time

from .. import ntcquery
from ..cluster import freeze_classes, scala_container, up
from ..ntccases import CLI, corpus
from ..ntcqueries import compare_round
from ..util import ENTRY, ENV, JDK_IMAGE, MAGIC, QUERY_PORT, REFERENCE_IMAGE, USER, data_root, docker, in_ref, read_log, ref

SCALA_CLI = "/opt/reference/bin/cardano-cli"

# `cardano-cli conway query` commands compared as text; {addr} is a funded address, {pool} a pool id
CLI_QUERIES = [
    ("protocol-parameters", "protocol-parameters"),
    ("utxo --whole-utxo", "utxo --whole-utxo"),
    ("utxo --address", "utxo --address {addr}"),
    ("utxo --tx-in", "utxo --tx-in {txin}"),
    ("stake-distribution", "stake-distribution"),
    ("stake-pools", "stake-pools"),
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

# commands the Scala node does not answer yet (roadmap F1): reported, not failed on
NOT_IMPLEMENTED = {"ledger-state"}


def start_scala(args, root):
    """The Scala node following pool 1 with a node-to-client socket the host can reach."""
    classpath = freeze_classes(root)
    sock = root / "scala-sock"
    sock.mkdir(exist_ok=True)
    sock.chmod(0o777)
    ports = json.loads((root / "ports.json").read_text())
    program = ["follow", "--genesis-dir", "/genesis", "--query-port", str(QUERY_PORT),
               "--local-socket", "/sock/node.socket", "--peer", f"127.0.0.1:{ports['1']}"]
    command = scala_container(args, root, classpath, "lab.node.Main", program,
                              mounts=[f"{root / 'genesis'}:/genesis:ro", f"{sock}:/sock"], name=f"{args.name}-scala")
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


def query_rounds(args, root, stats, until, every):
    rounds = 0
    while time.monotonic() < until:
        try:
            point, results = compare_round(args.name, f"{ENV}/socket/node1/sock", str(root / "scala-sock" / "node.socket"))
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


def rendered(text):
    """The error line `cardano-cli` prints (without its call stack)."""
    return next((l for l in text.splitlines() if l.startswith("Error:")), text.splitlines()[0] if text else "")


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


def submissions(args, root):
    cases = corpus(args.name, f"{ENV}/socket/node1/sock")
    results = []
    for case in cases:
        raw = reasons(args, root, case.tx)
        text = submit_scala_cli(args, case.tx)
        # the slot a transaction is judged at differs by a block or two between the nodes
        same_text = rendered(text["h"][1]) == rendered(text["s"][1])
        same_raw = raw["h"] == raw["s"]
        named = case.ledger_rule in text["h"][1]
        row = {"case": case.label, "rule": case.ledger_rule, "referenceNamesRule": named, "rawEqual": same_raw,
               "textEqual": same_text}
        if not (same_raw and same_text) and case.ledger_rule != "OutsideValidityIntervalUTxO":
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
        stats = {}
        started = time.monotonic()
        # let the load build some state (stake, pools, DReps, proposals) before the first round
        time.sleep(args.warmup)
        rounds = query_rounds(args, root, stats, started + args.seconds, args.every)
        for loader in loaders:
            loader.terminate()
            loader.wait()
        loaders = []
        print(json.dumps({"settled": settle(args, root)}), flush=True)
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

        implemented = {k: v for k, v in stats.items() if v["implemented"]}
        gaps = {k: v for k, v in stats.items() if not v["implemented"]}
        summary = {
            "rounds": rounds,
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
            "validSubmission": valid,
        }
        mismatched = [k for k, v in implemented.items() if v["matched"] != v["total"]]
        if mismatched:
            failures.append(f"queries that differ from the reference: {mismatched}")
        if not rounds:
            failures.append("no comparison round completed")
        badcli = [k for k, v in cli_results.items()
                  if v["total"] and not v["matched"] and k not in NOT_IMPLEMENTED]
        if badcli:
            failures.append(f"cardano-cli outputs that differ: {badcli}")
        if any(r["case"] and not r["referenceNamesRule"] for r in sub_results):
            failures.append("a refusal was not the rule the case is built for (the case is wrong)")
        ignored = {"OutsideValidityIntervalUTxO"}
        if any(not (r["rawEqual"] and r["textEqual"]) and r["rule"] not in ignored for r in sub_results):
            failures.append("a rejection differs from the reference's")
        if not (valid["acceptedByScala"] and valid["reachedABlock"]):
            failures.append(f"the valid transaction did not reach a block: {valid}")
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
