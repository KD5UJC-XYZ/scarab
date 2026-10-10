#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Local devnet: a 3-pool reference cluster plus the Scala node, all in Docker.

    devnet.py up                   create genesis and start the reference cluster
    devnet.py tip [--node N]       reference tip as JSON
    devnet.py follow [--seconds N] run the Scala follower against node1 (logs to the run dir)
    devnet.py load [--seconds N]   submit randomized ADA transfers between wallets
    devnet.py compare              compare the Scala ledger with the reference at one block
    devnet.py soak [--seconds N]   up + follow + load + periodic compare, then a summary
    devnet.py down                 remove containers and the run directory

Run data lives in ${CSL_DATA_DIR:-/tmp/cardano-scala-lab}/<name>. The Scala node shares the
reference container's network namespace because the reference nodes bind to loopback.
"""
import argparse
import json
import os
import pathlib
import random
import shutil
import subprocess
import sys
import time

REPO = pathlib.Path(__file__).resolve().parent.parent
REFERENCE_IMAGE = "cardano-reference-11.1.3:local"
JDK_IMAGE = "cardano-public-v023-check:local"
MAGIC = 1082026
ENV = "/work/env"
QUERY_PORT = 7071


def data_root(name):
    return pathlib.Path(os.environ.get("CSL_DATA_DIR", "/tmp/cardano-scala-lab")) / name


def docker(*args, check=True, capture=True, timeout=None):
    result = subprocess.run(["docker", *args], text=True, capture_output=capture, timeout=timeout)
    if check and result.returncode != 0:
        raise SystemExit(f"docker {' '.join(args[:3])} failed: {result.stderr.strip()}")
    return result


def ref(name):
    return f"{name}-ref"


def in_ref(name, script, timeout=60):
    return docker("exec", ref(name), "/bin/sh", "-c", script, timeout=timeout)


def query(name, kind, node=1):
    out = in_ref(name, f"cardano-cli conway query {kind} --testnet-magic {MAGIC} "
                       f"--socket-path {ENV}/socket/node{node}/sock").stdout
    return json.loads(out)


def up(args):
    root = data_root(args.name)
    if root.exists():
        raise SystemExit(f"{root} exists; run `devnet.py down` first")
    (root / "genesis").mkdir(parents=True)
    docker("run", "-d", "--name", ref(args.name), "--pull=never",
           "--cpus=3", "--memory=6g", "--user", "1000:1000",
           "--tmpfs", "/work:rw,exec,size=2g,uid=1000,gid=1000",
           "-e", "PATH=/opt/reference/bin:/usr/local/bin:/usr/bin:/bin",
           "-e", "CARDANO_CLI=/opt/reference/bin/cardano-cli",
           "-e", "CARDANO_NODE=/opt/reference/bin/cardano-node",
           "--entrypoint", "/bin/sh", REFERENCE_IMAGE, "-c", "sleep infinity")
    in_ref(args.name, f"cardano-testnet create-env --num-pool-nodes 3 --testnet-magic {MAGIC} "
                      f"--output {ENV} > /work/create-env.log 2>&1")
    ports = {n: int(in_ref(args.name, f"cat {ENV}/node-data/node{n}/port").stdout) for n in (1, 2, 3)}
    (root / "ports.json").write_text(json.dumps(ports))
    docker("exec", "-d", ref(args.name), "/bin/sh", "-c",
           f"cd {ENV} && cardano-testnet cardano --node-env {ENV} > /work/cluster.log 2>&1")
    deadline = time.monotonic() + 120
    while time.monotonic() < deadline:
        try:
            tip = query(args.name, "tip")
            if tip.get("block", 0) > 0:
                # Copy genesis only now: starting the cluster rewrites systemStart. Bytes must be
                # exact because the initial Praos nonce is the hash of the Shelley genesis file.
                for f in ["shelley-genesis.json", "byron-genesis.json", "alonzo-genesis.json",
                          "conway-genesis.json", "configuration.yaml"]:
                    raw = subprocess.run(["docker", "exec", ref(args.name), "cat", f"{ENV}/{f}"],
                                         capture_output=True, check=True).stdout
                    (root / "genesis" / f).write_bytes(raw)
                print(json.dumps({"up": True, "ports": ports, "tip": tip, "root": str(root)}))
                return
        except (SystemExit, json.JSONDecodeError):
            pass
        time.sleep(2)
    raise SystemExit("reference cluster produced no block within 120 s; see /work/cluster.log")


def tip(args):
    print(json.dumps(query(args.name, "tip", args.node)))


def follow(args):
    root = data_root(args.name)
    ports = json.loads((root / "ports.json").read_text())
    # Freeze the compiled classes so later builds cannot change code under a running node.
    frozen = root / "classes"
    shutil.rmtree(frozen, ignore_errors=True)
    entries = []
    for entry in (REPO / "node/target/runtime-classpath.txt").read_text().strip().split(":"):
        if entry.endswith("/classes"):
            module = entry[len("/work/"):].split("/")[0]
            shutil.copytree(REPO / entry[len("/work/"):], frozen / module)
            entries.append(f"/run/classes/{module}")
        else:
            entries.append(entry)
    classpath = ":".join(entries)
    log = root / "follower.jsonl"
    command = ["run", "--rm", "--name", f"{args.name}-scala", "--pull=never",
               "--network", f"container:{ref(args.name)}", "--cpus=1", "--memory=1g",
               "--user", "1000:1000", "-v", f"{REPO}:/work:ro", "-v", f"{root / 'genesis'}:/genesis:ro",
               "-v", f"{frozen}:/run/classes:ro",
               "--entrypoint", "java", JDK_IMAGE, "-Xmx768m", "-cp", classpath, "lab.node.Main", "follow",
               "--genesis-dir", "/genesis", "--peer", f"127.0.0.1:{ports[str(args.node)]}",
               "--query-port", str(QUERY_PORT)]
    if args.seconds:
        command += ["--seconds", str(args.seconds)]
    with log.open("a") as out:
        proc = subprocess.Popen(["docker", *command], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        for line in proc.stdout:
            out.write(line)
            out.flush()
            sys.stdout.write(line)
        sys.exit(proc.wait())


CLI = "cardano-cli conway"
SOCKET = f"--testnet-magic {MAGIC} --socket-path {ENV}/socket/node1/sock"
LOAD = "/work/load"


def load(args):
    """Randomized ledger activity: ADA transfers among genesis keys and fresh wallets, plus staking
    wallets that register (stake + vote delegation), re-delegate, withdraw rewards and deregister.

    Each wallet acts only once its previous transaction is confirmed, so submissions never
    conflict in the mempool.
    """
    rng = random.Random(args.seed)
    deposit = json.loads(in_ref(args.name, f"{CLI} query protocol-parameters {SOCKET}").stdout)["stakeAddressDeposit"]
    pools = json.loads(in_ref(args.name, f"{CLI} query stake-pools {SOCKET} --output-json").stdout)
    in_ref(args.name, f"mkdir -p {LOAD}")
    wallets = [f"{ENV}/utxo-keys/utxo{i}/utxo" for i in (1, 2, 3)]
    stakers = []
    for i in range(args.wallets):
        w = f"{LOAD}/w{i}"
        staking = i < args.stakers
        stake_part = (f" && {CLI} stake-address key-gen --verification-key-file {w}.stake.vkey "
                      f"--signing-key-file {w}.stake.skey && {CLI} stake-address build "
                      f"--stake-verification-key-file {w}.stake.vkey --testnet-magic {MAGIC} > {w}.stake.addr")
        in_ref(args.name, f"test -f {w}.addr || ({CLI} address key-gen --verification-key-file {w}.vkey "
                          f"--signing-key-file {w}.skey{stake_part if staking else ''} && {CLI} address build "
                          f"--payment-verification-key-file {w}.vkey "
                          f"{f'--stake-verification-key-file {w}.stake.vkey ' if staking else ''}"
                          f"--testnet-magic {MAGIC} > {w}.addr)")
        wallets.append(w)
        if staking:
            stakers.append(w)
    address = {w: in_ref(args.name, f"cat {w}.addr").stdout.strip() for w in wallets}
    stake_address = {w: in_ref(args.name, f"cat {w}.stake.addr").stdout.strip() for w in stakers}
    pending = {}  # wallet -> txid awaiting confirmation
    stats = {"submitted": 0, "rejected": 0, "transfers": 0, "registrations": 0, "delegations": 0,
             "withdrawals": 0, "deregistrations": 0}

    def stake_action(w):
        """Extra build arguments and signing keys for one staking operation, or None."""
        info = json.loads(in_ref(args.name, f"{CLI} query stake-address-info --address {stake_address[w]} "
                                            f"{SOCKET}").stdout)
        cert = f"{LOAD}/cert-{stats['submitted'] + stats['rejected']}"
        skeys = [f"{w}.skey", f"{w}.stake.skey"]
        if not info:
            in_ref(args.name, f"{CLI} stake-address registration-stake-and-vote-delegation-certificate "
                              f"--stake-verification-key-file {w}.stake.vkey --stake-pool-id {rng.choice(pools)} "
                              f"--always-abstain --key-reg-deposit-amt {deposit} --out-file {cert}")
            return "registrations", f"--certificate-file {cert}", skeys
        balance = info[0].get("rewardAccountBalance", 0)
        roll = rng.random()
        if balance > 0 and roll < 0.6:
            return "withdrawals", f"--withdrawal {stake_address[w]}+{balance}", skeys
        if balance == 0 and roll < 0.1:
            in_ref(args.name, f"{CLI} stake-address deregistration-certificate --stake-verification-key-file "
                              f"{w}.stake.vkey --key-reg-deposit-amt {deposit} --out-file {cert}")
            return "deregistrations", f"--certificate-file {cert}", skeys
        if roll < 0.8:
            in_ref(args.name, f"{CLI} stake-address stake-delegation-certificate --stake-verification-key-file "
                              f"{w}.stake.vkey --stake-pool-id {rng.choice(pools)} --out-file {cert}")
            return "delegations", f"--certificate-file {cert}", skeys
        return None

    deadline = time.monotonic() + args.seconds if args.seconds else None
    while deadline is None or time.monotonic() < deadline:
        w = rng.choice(wallets)
        utxo = json.loads(in_ref(args.name, f"{CLI} query utxo --address {address[w]} {SOCKET} --output-json").stdout)
        if pending.get(w) and not any(k.startswith(pending[w]) for k in utxo):
            time.sleep(0.2)
            continue
        pending.pop(w, None)
        balance = sum(v["value"]["lovelace"] for v in utxo.values())
        if balance < 300_000_000:
            continue
        ins = list(utxo.keys())
        rng.shuffle(ins)
        ins = ins[: rng.randint(1, min(3, len(ins)))]
        if sum(utxo[i]["value"]["lovelace"] for i in ins) < 300_000_000:
            ins = list(utxo.keys())
        kind, extra, skeys = "transfers", "", [f"{w}.skey"]
        if w in stakers and rng.random() < args.stake_rate:
            action = stake_action(w)
            if action:
                kind, extra, skeys = action
        outs = " ".join(f"--tx-out {address[rng.choice(wallets)]}+{rng.randint(1, 200) * 1_000_000}"
                        for _ in range(rng.randint(1, 3)))
        tx = f"{LOAD}/tx-{stats['submitted'] + stats['rejected']}"
        script = (f"{CLI} transaction build {SOCKET} {' '.join('--tx-in ' + i for i in ins)} {outs} {extra} "
                  f"--change-address {address[w]} --witness-override {len(skeys)} --out-file {tx}.body && "
                  f"{CLI} transaction sign --tx-body-file {tx}.body "
                  f"{' '.join('--signing-key-file ' + k for k in skeys)} "
                  f"--testnet-magic {MAGIC} --out-file {tx}.signed && "
                  f"{CLI} transaction submit {SOCKET} --tx-file {tx}.signed && "
                  f"{CLI} transaction txid --tx-file {tx}.signed --output-text && rm -f {tx}.body {tx}.signed")
        r = docker("exec", ref(args.name), "/bin/sh", "-c", script, check=False, timeout=60)
        if r.returncode == 0:
            pending[w] = r.stdout.strip().splitlines()[-1].strip()
            stats["submitted"] += 1
            stats[kind] += 1
        else:
            stats["rejected"] += 1
            print(json.dumps({"rejected": kind, "error": r.stderr.strip()[:400]}), flush=True)
        if (stats["submitted"] + stats["rejected"]) % 20 == 0:
            print(json.dumps(stats), flush=True)
        time.sleep(args.interval)
    print(json.dumps(stats), flush=True)


# ---------------------------------------------------------------- comparison

BECH32 = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"


def bech32_bytes(text):
    data = [BECH32.index(c) for c in text[text.rindex("1") + 1:-6]]
    acc, bits, out = 0, 0, []
    for v in data:
        acc = (acc << 5) | v
        bits += 5
        while bits >= 8:
            bits -= 8
            out.append((acc >> bits) & 0xff)
    return bytes(out)


def base58_bytes(text):
    alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    n = 0
    for c in text:
        n = n * 58 + alphabet.index(c)
    body = n.to_bytes((n.bit_length() + 7) // 8, "big") if n else b""
    return b"\0" * (len(text) - len(text.lstrip("1"))) + body


def address_hex(a):
    return (bech32_bytes(a) if a.startswith(("addr", "stake")) else base58_bytes(a)).hex()


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
        "utxo": {k: {"address": address_hex(v["address"]), "lovelace": v["value"]["lovelace"]}
                 for k, v in utxo.items()},
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


def compare_once(name, attempts=10):
    """One bracketed comparison. Returns a result dict, or None if no stable bracket was found."""
    for _ in range(attempts):
        before = query(name, "tip")
        ls = json.loads(in_ref(name, f"{CLI} query ledger-state {SOCKET}", timeout=120).stdout)
        utxo = json.loads(in_ref(name, f"{CLI} query utxo --whole-utxo {SOCKET} --output-json", timeout=120).stdout)
        protocol = query(name, "protocol-state")
        after = query(name, "tip")
        if before["hash"] != after["hash"]:
            continue
        scala = None
        for _ in range(30):
            scala = scala_state(name, after)
            if scala is not None:
                break
            time.sleep(1)
        if scala is None:
            return {"tip": after, "error": "scala node never reached the reference tip"}
        reference = reference_view(ls, utxo)
        reference["protocolState"] = {k: protocol[k] for k in (
            "epochNonce", "evolvingNonce", "candidateNonce", "labNonce", "lastEpochBlockNonce", "oCertCounters")}
        scala_view = {k: v for k, v in scala.items() if k != "point"}
        if reference["rewardUpdate"] is None:
            scala_view["rewardUpdate"] = None  # reference still pulsing: nothing to compare
        differences = diff(reference, scala_view)
        return {"tip": {k: after[k] for k in ("slot", "block", "epoch", "hash")}, "utxoSize": len(utxo),
                "matched": not differences, "differences": [
                    {"path": p, "reference": str(a)[:200], "scala": str(b)[:200]} for p, a, b in differences[:25]],
                "differenceCount": len(differences)}
    return None


def compare(args):
    print(json.dumps(compare_once(args.name)))


def soak(args):
    """Fresh cluster, Scala follower, transaction load and periodic comparison."""
    root = data_root(args.name)
    up(args)
    follower = subprocess.Popen([sys.executable, __file__, "--name", args.name, "follow"],
                                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    loader = subprocess.Popen([sys.executable, __file__, "--name", args.name, "load", "--seed", str(args.seed)],
                              stdout=(root / "load.log").open("w"), stderr=subprocess.STDOUT)
    results = []
    deadline = time.monotonic() + args.seconds
    try:
        while time.monotonic() < deadline:
            time.sleep(args.every)
            if follower.poll() is not None:
                results.append({"error": f"follower exited with {follower.returncode}"})
                break
            r = compare_once(args.name)
            if r is not None:
                results.append(r)
                print(json.dumps({k: r.get(k) for k in ("tip", "matched", "differenceCount", "error")}), flush=True)
                with (root / "compare.jsonl").open("a") as f:
                    f.write(json.dumps(r) + "\n")
    finally:
        loader.terminate()
        loader.wait()
        follower_alive = follower.poll() is None
        docker("rm", "-f", f"{args.name}-scala", check=False)
        follower.wait()
    log = [json.loads(l) for l in (root / "follower.jsonl").read_text().splitlines() if l.startswith("{")]
    summary = {
        "comparisons": len([r for r in results if "matched" in r]),
        "matched": len([r for r in results if r.get("matched")]),
        "firstMismatch": next((r for r in results if r.get("matched") is False or "error" in r), None),
        "followerAliveAtEnd": follower_alive,
        "epochsEntered": [e["epoch"] for e in log if e.get("event") == "epoch"],
        "blocksApplied": sum(1 for e in log if e.get("event") == "extended"),
        "rollbacks": [e for e in log if e.get("event") == "rolledBack"],
        "reconnects": sum(1 for e in log if e.get("event") == "reconnecting"),
        "errors": [e for e in log if e.get("event") == "error"],
        "load": (root / "load.log").read_text().strip().splitlines()[-1:],
    }
    (root / "summary.json").write_text(json.dumps(summary, indent=2))
    print(json.dumps(summary, indent=2))


def down(args):
    for c in (f"{args.name}-scala", ref(args.name)):
        docker("rm", "-f", c, check=False)
    shutil.rmtree(data_root(args.name), ignore_errors=True)
    print(json.dumps({"down": True}))


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--name", default="csl-devnet")
    sub = p.add_subparsers(dest="command", required=True)
    sub.add_parser("up").set_defaults(run=up)
    t = sub.add_parser("tip")
    t.add_argument("--node", type=int, default=1)
    t.set_defaults(run=tip)
    f = sub.add_parser("follow")
    f.add_argument("--seconds", type=int)
    f.add_argument("--node", type=int, default=1)
    f.set_defaults(run=follow)
    ld = sub.add_parser("load")
    ld.add_argument("--seconds", type=int)
    ld.add_argument("--wallets", type=int, default=10)
    ld.add_argument("--stakers", type=int, default=4)
    ld.add_argument("--stake-rate", type=float, default=0.3)
    ld.add_argument("--interval", type=float, default=0.5)
    ld.add_argument("--seed", type=int, default=1)
    ld.set_defaults(run=load)
    sub.add_parser("compare").set_defaults(run=compare)
    sk = sub.add_parser("soak")
    sk.add_argument("--seconds", type=int, default=600)
    sk.add_argument("--every", type=int, default=20)
    sk.add_argument("--seed", type=int, default=1)
    sk.set_defaults(run=soak)
    sub.add_parser("down").set_defaults(run=down)
    args = p.parse_args()
    args.run(args)


if __name__ == "__main__":
    main()
