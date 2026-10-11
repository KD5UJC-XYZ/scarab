# SPDX-License-Identifier: Apache-2.0
"""Transaction load generators (ADA transfers, staking, governance) against the reference cluster."""
import argparse
import json
import random
import subprocess
import sys
import time
from . import eraload
from .compare import lsq
from .encoding import address_hex
from .util import CLI, ENTRY, ENV, LOAD, MAGIC, SOCKET, docker, in_ref, ref


def load_wallets(args):
    """Create (idempotently) the load wallets; returns (wallets, stakers, address, stake_address)."""
    in_ref(args.name, f"mkdir -p {LOAD}")
    wallets = [] if getattr(args, "fresh_wallets_only", False) else [f"{ENV}/utxo-keys/utxo{i}/utxo" for i in (1, 2, 3)]
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
    return wallets, stakers, address, stake_address


def govload(args):
    """Conway governance and Plutus scenarios (`EraLoad.conway_features`), one stage per epoch, on a
    Conway-only cluster."""
    loader = eraload.EraLoad(args.name, lambda: lsq(args.name), log=lambda m: print(json.dumps(m), flush=True))
    loader.ready.set()
    seen = set()
    deadline = time.monotonic() + args.seconds if args.seconds else None
    while deadline is None or time.monotonic() < deadline:
        try:
            tip = loader.epoch_info()
            loader.tip = tip
            if tip.get("slotInEpoch", 0) >= 3 and tip["epoch"] not in seen:
                seen.add(tip["epoch"])
                loader.conway_features(tip["epoch"])
        except Exception as e:  # a scenario problem must not stop the soak
            print(json.dumps({"govloadFailed": repr(e)}), flush=True)
        time.sleep(0.7)
    print(json.dumps(loader.stats), flush=True)


def load(args):
    """Randomized ledger activity: ADA transfers among genesis keys and fresh wallets, plus staking
    wallets that register (stake + vote delegation), re-delegate, withdraw rewards and deregister.

    Each wallet acts only once its previous transaction is confirmed, so submissions never
    conflict in the mempool.
    """
    global SOCKET
    if getattr(args, "socket", None):
        SOCKET = f"--testnet-magic {MAGIC} --socket-path {args.socket}"
    rng = random.Random(args.seed)
    deposit = json.loads(in_ref(args.name, f"{CLI} query protocol-parameters {SOCKET}").stdout)["stakeAddressDeposit"]
    pools = json.loads(in_ref(args.name, f"{CLI} query stake-pools {SOCKET} --output-json").stdout)
    wallets, stakers, address, stake_address = load_wallets(args)
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
        if balance < args.min_balance:
            time.sleep(0.2)
            continue
        ins = list(utxo.keys())
        rng.shuffle(ins)
        ins = ins[: rng.randint(1, min(3, len(ins)))]
        if sum(utxo[i]["value"]["lovelace"] for i in ins) < args.min_balance:
            ins = list(utxo.keys())
        kind, extra, skeys = "transfers", "", [f"{w}.skey"]
        if w in stakers and rng.random() < args.stake_rate:
            action = stake_action(w)
            if action:
                kind, extra, skeys = action
        outs = " ".join(f"--tx-out {address[rng.choice(wallets)]}+{rng.randint(1, args.max_out) * 1_000_000}"
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
        if (stats["submitted"] + stats["rejected"]) % getattr(args, "stats_every", 20) == 0:
            print(json.dumps(stats), flush=True)
        time.sleep(args.interval)
    print(json.dumps(stats), flush=True)


def start_conway_load(args, root, loader):
    """Fund the Conway load wallets from stake delegator 3 and start the cardano-cli load generator."""
    largs = argparse.Namespace(name=args.name, wallets=4, stakers=3, fresh_wallets_only=True)
    wallets, _, address, _ = load_wallets(largs)
    funder = loader.delegator(3)
    outs = [(bytes.fromhex(address_hex(address[w])), 250_000_000, None, {}) for w in wallets]
    loader.act("conway", "conway-fund", funder, outs)
    command = [sys.executable, str(ENTRY), "--name", args.name, "load", "--wallets", "4", "--stakers", "3",
               "--stake-rate", "0.5", "--min-balance", "20000000", "--max-out", "5", "--fresh-wallets-only",
               "--interval", "1.0", "--seed", "3"]
    return subprocess.Popen(command, stdout=(root / "load-conway.log").open("w"), stderr=subprocess.STDOUT)
