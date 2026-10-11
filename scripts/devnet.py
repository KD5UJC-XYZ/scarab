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


def start_reference(name):
    docker("run", "-d", "--name", ref(name), "--pull=never",
           "--cpus=3", "--memory=6g", "--user", "1000:1000",
           "--tmpfs", "/work:rw,exec,size=2g,uid=1000,gid=1000",
           "-e", "PATH=/opt/reference/bin:/usr/local/bin:/usr/bin:/bin",
           "-e", "CARDANO_CLI=/opt/reference/bin/cardano-cli",
           "-e", "CARDANO_NODE=/opt/reference/bin/cardano-node",
           "--entrypoint", "/bin/sh", REFERENCE_IMAGE, "-c", "sleep infinity")


def up(args):
    root = data_root(args.name)
    if root.exists():
        raise SystemExit(f"{root} exists; run `devnet.py down` first")
    (root / "genesis").mkdir(parents=True)
    start_reference(args.name)
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


# ---------------------------------------------------------------- era-walking cluster

# One epoch per step after the Byron epochs: (era, protocol major version at the start of the epoch).
# Alonzo and Babbage get a second epoch because each spans two protocol versions on mainnet.
ERA_PLAN = [("shelley", 2), ("allegra", 3), ("mary", 4), ("alonzo", 5), ("alonzo", 6),
            ("babbage", 7), ("babbage", 8), ("conway", 9)]
STABILITY_FACTOR = 6  # a protocol-parameter update must land 2 * 3k/f slots before the epoch ends
TX = "/work/tx"


def put_file(name, path, text):
    r = subprocess.run(["docker", "exec", "-i", ref(name), "/bin/sh", "-c", f"cat > {path}"],
                       input=text, text=True, capture_output=True)
    if r.returncode != 0:
        raise SystemExit(f"cannot write {path}: {r.stderr.strip()}")


def read_json(name, path):
    return json.loads(in_ref(name, f"cat {path}").stdout)


def era_tip(name, node=1):
    r = in_ref(name, f"cardano-cli query tip {SOCKET.replace('node1', f'node{node}')}")
    return json.loads(r.stdout)


def prepare_eras_env(name, args):
    """Rewrite the generated Conway-only environment so the chain starts in Byron and walks the eras.

    cardano-testnet itself cannot supervise a Byron start (its block watcher aborts with "Byron era is
    not supported"), so this only borrows `create-env` and the node command lines.
    """
    in_ref(name, f"cardano-testnet create-env --num-pool-nodes 3 --testnet-magic {MAGIC} "
                 f"--epoch-length {args.epoch_length} --active-slots-coeff {args.active_slots_coeff} "
                 f"--output {ENV} > /work/create-env.log 2>&1")
    start = int(time.time()) + 10
    config = read_json(name, f"{ENV}/configuration.yaml")
    first = {}
    for i, (era, _) in enumerate(ERA_PLAN):
        first.setdefault(era, args.byron_epochs + i)
    for era in ("shelley", "allegra", "mary", "alonzo", "babbage", "conway"):
        config[f"Test{era.capitalize()}HardForkAtEpoch"] = first[era]
    put_file(name, f"{ENV}/configuration.yaml", json.dumps(config, indent=2))
    byron = read_json(name, f"{ENV}/byron-genesis.json")
    byron["startTime"] = start
    byron["blockVersionData"]["slotDuration"] = str(args.byron_slot_ms)
    put_file(name, f"{ENV}/byron-genesis.json", json.dumps(byron, indent=2))
    shelley = read_json(name, f"{ENV}/shelley-genesis.json")
    shelley.pop("extraConfig")  # funds and pools are only read from here when the chain starts in Conway
    shelley["systemStart"] = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(start))
    shelley["protocolParams"]["protocolVersion"] = {"major": ERA_PLAN[0][1], "minor": 0}
    # The overlay (decentralisation = 1) is the only block source until the pools hold stake, so the
    # genesis delegates are the pool operators themselves: the same cold, VRF and KES keys serve both.
    delegs = {}
    for n in (1, 2, 3):
        genesis_hash = in_ref(name, f"cardano-cli conway genesis key-hash --verification-key-file "
                                    f"{ENV}/genesis-keys/genesis{n}/key.vkey").stdout.strip()
        pool_hash = in_ref(name, f"cardano-cli conway stake-pool id --output-format hex --cold-verification-key-file "
                                 f"{ENV}/pools-keys/pool{n}/cold.vkey").stdout.strip()
        vrf_hash = in_ref(name, f"cardano-cli conway node key-hash-VRF --verification-key-file "
                                f"{ENV}/pools-keys/pool{n}/vrf.vkey").stdout.strip()
        delegs[genesis_hash] = {"delegate": pool_hash, "vrf": vrf_hash}
    shelley["genDelegs"] = delegs
    put_file(name, f"{ENV}/shelley-genesis.json", json.dumps(shelley, indent=2))
    return shelley


def start_nodes(name):
    ports = {}
    for n in (1, 2, 3):
        ports[n] = int(in_ref(name, f"cat {ENV}/node-data/node{n}/port").stdout)
        in_ref(name, f"mkdir -p {ENV}/socket/node{n}")
        pool = f"{ENV}/pools-keys/pool{n}"
        docker("exec", "-d", ref(name), "/bin/sh", "-c",
               f"cd {ENV} && cardano-node run --config {ENV}/configuration.yaml "
               f"--topology {ENV}/node-data/node{n}/topology.json --database-path {ENV}/node-data/node{n}/db "
               f"--shelley-kes-key {pool}/kes.skey --shelley-vrf-key {pool}/vrf.skey "
               f"--shelley-operational-certificate {pool}/opcert.cert "
               f"--byron-delegation-certificate {pool}/byron-delegation.cert "
               f"--byron-signing-key {pool}/byron-delegate.key "
               f"--socket-path {ENV}/socket/node{n}/sock --port {ports[n]} --host-addr 127.0.0.1 "
               f"> /work/node{n}.log 2>&1")
    return ports


class EraDriver:
    """Submits the transactions that move the chain through the eras and records each transition.

    Shelley: one transaction spends the Byron genesis balances into three staked addresses and
    registers three pools. The update proposals then raise the protocol version once per epoch (the
    Test<Era>HardForkAtEpoch flags place the era boundaries, the proposals make the ledger report the
    matching version) and, in the Allegra epoch, switch off the overlay so the pools take over.
    """

    def __init__(self, args, shelley):
        self.args, self.name = args, args.name
        self.key_deposit = shelley["protocolParams"]["keyDeposit"]
        self.epoch_length = shelley["epochLength"]
        self.pool_deposit = shelley["protocolParams"]["poolDeposit"]
        self.done = set()
        self.timeline = []
        self.address = {n: in_ref(self.name, f"cardano-cli conway address build --testnet-magic {MAGIC} "
                                             f"--payment-verification-key-file {ENV}/stake-delegators/delegator{n}/payment.vkey "
                                             f"--stake-verification-key-file {ENV}/stake-delegators/delegator{n}/staking.vkey"
                                  ).stdout.strip() for n in (1, 2, 3)}
        in_ref(self.name, f"mkdir -p {TX}")

    def utxo(self, address):
        out = in_ref(self.name, f"cardano-cli query utxo --address {address} {SOCKET} --output-json").stdout
        return {k: v["value"]["lovelace"] for k, v in json.loads(out).items()}

    def submit(self, era, build, label):
        """Build with `compatible <era> ... signed-transaction`, submit, and wait for the inputs to be spent."""
        r = in_ref(self.name, f"{build} && cardano-cli conway transaction submit {SOCKET} --tx-file {TX}/{label}.tx")
        return r

    def byron_inputs(self):
        inputs = []
        for n in (1, 2, 3):
            raw = f"{ENV}/pools-keys/pool{n}/byron-delegate.key"
            key = f"{TX}/byron{n}.skey"  # the raw delegate key as a Byron payment text envelope, for bootstrap witnesses
            in_ref(self.name, f"cardano-cli key convert-byron-key --byron-payment-key-type "
                              f"--byron-signing-key-file {raw} --out-file {key}")
            addr = in_ref(self.name, f"cardano-cli byron key signing-key-address --byron-formats --testnet-magic {MAGIC} "
                                     f"--secret {raw}").stdout.splitlines()[0].strip()
            (txin, lovelace), = self.utxo(addr).items()
            inputs.append((txin, lovelace, key, addr))
        return inputs

    def setup_pools(self, era):
        """Spend the Byron balances into three base addresses; register stake keys, pools, delegations."""
        inputs = self.byron_inputs()
        fee = 300_000
        deposits = 3 * self.key_deposit + 3 * self.pool_deposit
        spendable = sum(i[1] for i in inputs) - fee - deposits
        outs = [spendable // 3 + spendable % 3, spendable // 3, spendable // 3]
        certs = []
        cli = f"cardano-cli compatible {era}"
        for n in (1, 2, 3):
            d, pool = f"{ENV}/stake-delegators/delegator{n}", f"{ENV}/pools-keys/pool{n}"
            in_ref(self.name, f"{cli} stake-address registration-certificate --stake-verification-key-file {d}/staking.vkey "
                              f"--out-file {TX}/reg{n}.cert && "
                              f"{cli} stake-pool registration-certificate --cold-verification-key-file {pool}/cold.vkey "
                              f"--vrf-verification-key-file {pool}/vrf.vkey --pool-pledge 0 --pool-cost 0 --pool-margin 0 "
                              f"--pool-reward-account-verification-key-file {d}/staking.vkey "
                              f"--pool-owner-stake-verification-key-file {d}/staking.vkey --testnet-magic {MAGIC} "
                              f"--out-file {TX}/pool{n}.cert && "
                              f"{cli} stake-address stake-delegation-certificate --stake-verification-key-file {d}/staking.vkey "
                              f"--cold-verification-key-file {pool}/cold.vkey --out-file {TX}/deleg{n}.cert")
        for kind in ("reg", "pool", "deleg"):
            certs += [f"--certificate-file {TX}/{kind}{n}.cert" for n in (1, 2, 3)]
        signers = [f"--signing-key-file {i[2]} --address {i[3]}" for i in inputs]
        signers += [f"--signing-key-file {ENV}/stake-delegators/delegator{n}/staking.skey" for n in (1, 2, 3)]
        signers += [f"--signing-key-file {ENV}/pools-keys/pool{n}/cold.skey" for n in (1, 2, 3)]
        build = (f"{cli} transaction signed-transaction {' '.join('--tx-in ' + i[0] for i in inputs)} "
                 f"{' '.join(f'--tx-out {self.address[n]}+{outs[n - 1]}' for n in (1, 2, 3))} "
                 f"{' '.join(certs)} {' '.join(signers)} --testnet-magic {MAGIC} --fee {fee} --out-file {TX}/setup.tx")
        r = self.submit(era, build, "setup")
        if r.returncode != 0:
            raise SystemExit(f"pool setup transaction failed: {r.stderr.strip()[:600]}")
        self.wait_spent(self.address[1], None, [i[0] for i in inputs])

    def wait_spent(self, address, _unused, spent, timeout=60):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            utxo = self.utxo(self.address[1]) if address == self.address[1] else self.utxo(address)
            if utxo and not any(s in utxo for s in spent):
                return
            time.sleep(0.5)
        raise SystemExit("transaction was not confirmed within 60 s")

    def propose(self, era, epoch, major, decentralise):
        """One update proposal from all three genesis delegates, adopted at the next epoch boundary."""
        cli = f"cardano-cli compatible {era}"
        keys = " ".join(f"--genesis-verification-key-file {ENV}/genesis-keys/genesis{n}/key.vkey" for n in (1, 2, 3))
        extra = " --decentralization-parameter 0" if decentralise else ""
        in_ref(self.name, f"{cli} governance action create-protocol-parameters-update --epoch {epoch} {keys} "
                          f"--protocol-major-version {major} --protocol-minor-version 0{extra} --out-file {TX}/pp{major}.proposal")
        (txin, lovelace), = sorted(self.utxo(self.address[1]).items(), key=lambda kv: -kv[1])[:1]
        fee = 300_000
        signers = [f"--signing-key-file {ENV}/stake-delegators/delegator1/payment.skey"]
        signers += [f"--signing-key-file {ENV}/pools-keys/pool{n}/cold.skey" for n in (1, 2, 3)]
        build = (f"{cli} transaction signed-transaction --tx-in {txin} --tx-out {self.address[1]}+{lovelace - fee} "
                 f"--update-proposal-file {TX}/pp{major}.proposal {' '.join(signers)} --testnet-magic {MAGIC} "
                 f"--fee {fee} --out-file {TX}/pp{major}.tx")
        r = self.submit(era, build, f"pp{major}")
        if r.returncode != 0:
            raise SystemExit(f"update proposal for protocol {major} failed: {r.stderr.strip()[:600]}")
        self.wait_spent(self.address[1], None, [txin])

    def step(self, tip):
        """Act on one tip observation; return True once the final era's first block is seen."""
        era, epoch = tip["era"].lower(), tip["epoch"]
        index = epoch - self.args.byron_epochs
        if not self.timeline or self.timeline[-1]["era"] != era:
            self.timeline.append({"era": era, "epoch": epoch, "slot": tip["slot"], "block": tip["block"],
                                  "hash": tip["hash"], "previousBlock": self.last_block})
            print(json.dumps({"era": era, "epoch": epoch, "slot": tip["slot"], "hash": tip["hash"]}), flush=True)
        if index < 0 or ("epoch", epoch) in self.done:
            return False
        self.done.add(("epoch", epoch))
        pv = json.loads(in_ref(self.name, f"cardano-cli query protocol-parameters {SOCKET}").stdout)["protocolVersion"]
        self.protocol[epoch] = pv["major"]
        print(json.dumps({"epoch": epoch, "era": era, "protocolMajor": pv["major"]}), flush=True)
        if index == 0:
            self.setup_pools(era)
        if index + 1 < len(ERA_PLAN):
            if tip["slotInEpoch"] > self.epoch_length - STABILITY_FACTOR * 5 / self.args.active_slots_coeff - 20:
                raise SystemExit(f"epoch {epoch} is too far gone to submit an update proposal: {tip}")
            self.propose(era, epoch, ERA_PLAN[index + 1][1], decentralise=(index == 1))
        return index == len(ERA_PLAN) - 1 and era == ERA_PLAN[-1][0]


def up_eras(args):
    root = data_root(args.name)
    if root.exists():
        raise SystemExit(f"{root} exists; run `devnet.py down` first")
    (root / "genesis").mkdir(parents=True)
    start_reference(args.name)
    shelley = prepare_eras_env(args.name, args)
    ports = start_nodes(args.name)
    (root / "ports.json").write_text(json.dumps(ports))
    deadline = time.monotonic() + 90
    while True:
        try:
            era_tip(args.name)
            break
        except (SystemExit, json.JSONDecodeError, subprocess.TimeoutExpired):
            if time.monotonic() > deadline:
                raise SystemExit("reference nodes did not answer queries within 90 s; see /work/node1.log")
            time.sleep(1)
    for f in ["shelley-genesis.json", "byron-genesis.json", "alonzo-genesis.json",
              "conway-genesis.json", "configuration.yaml"]:
        raw = subprocess.run(["docker", "exec", ref(args.name), "cat", f"{ENV}/{f}"],
                             capture_output=True, check=True).stdout
        (root / "genesis" / f).write_bytes(raw)
    driver = EraDriver(args, shelley)
    driver.last_block = None
    driver.protocol = {}
    stalled = time.monotonic()
    last = None
    while True:
        try:
            tip = era_tip(args.name)
        except (SystemExit, json.JSONDecodeError, subprocess.TimeoutExpired):
            time.sleep(0.5)
            continue
        if "slot" not in tip:
            time.sleep(0.5)
            continue
        if last is None or tip["block"] != last["block"]:
            stalled = time.monotonic()
        elif time.monotonic() - stalled > args.stall_seconds:
            raise SystemExit(f"no new block for {args.stall_seconds} s at {tip}")
        finished = driver.step(tip)
        driver.last_block = tip["block"]
        last = tip
        if finished:
            break
        time.sleep(0.4)
    summary = {"up": True, "root": str(root), "ports": ports, "eras": driver.timeline,
               "protocolMajorByEpoch": driver.protocol}
    (root / "eras.json").write_text(json.dumps(summary, indent=2))
    print(json.dumps(summary))


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
    p.add_argument("--name", help="default csl-devnet, or csl-eras for up-eras")
    sub = p.add_subparsers(dest="command", required=True)
    sub.add_parser("up").set_defaults(run=up)
    ue = sub.add_parser("up-eras", help="start in Byron and walk Shelley..Conway (one epoch per era step)")
    ue.add_argument("--epoch-length", type=int, default=500)
    ue.add_argument("--active-slots-coeff", type=float, default=0.2)
    ue.add_argument("--byron-epochs", type=int, default=1)
    ue.add_argument("--byron-slot-ms", type=int, default=1000)
    ue.add_argument("--stall-seconds", type=int, default=90)
    ue.set_defaults(run=up_eras)
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
    args.name = args.name or ("csl-eras" if args.command == "up-eras" else "csl-devnet")
    args.run(args)


if __name__ == "__main__":
    main()
