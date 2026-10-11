#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Local devnet: a 3-pool reference cluster plus the Scala node, all in Docker.

    devnet.py up                   create genesis and start the reference cluster
    devnet.py tip [--node N]       reference tip as JSON
    devnet.py follow [--seconds N] run the Scala follower against node1 (logs to the run dir)
    devnet.py load [--seconds N]   submit randomized ADA transfers between wallets
    devnet.py compare              compare the Scala ledger with the reference at one block
    devnet.py soak [--seconds N]   up + follow + load + periodic compare, then a summary
    devnet.py relay [--seconds N]  up + the Scala node as a relay (follows the pools, serves a
                                   Haskell relay) + load through that relay; tips and propagation
    devnet.py up-eras              reference cluster that walks Byron, Shelley ... Conway
    devnet.py capture-eras         capture the up-eras chain as test fixtures (fixtures/eras)
    devnet.py e2e [--keep]         up-eras + Scala follower from Origin; assert every era and
                                   era transition was followed with no validation error
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
import threading
import time

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import eraload  # noqa: E402
import eraview  # noqa: E402
import ntcquery  # noqa: E402
import eratx  # noqa: E402
import govview  # noqa: E402

REPO = pathlib.Path(__file__).resolve().parent.parent
REFERENCE_IMAGE = "cardano-reference-11.1.3:local"
JDK_IMAGE = "cardano-public-v023-check:local"
MAGIC = 1082026
ENV = "/work/env"
QUERY_PORT = 7071
CONWAY_EPOCHS = 7  # Conway epochs the e2e run follows: bootstrap governance, the hard fork to 10, then full governance


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
        self.on_pools_ready = None
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
        if self.on_pools_ready:
            self.on_pools_ready()

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


def up_eras(args, on_nodes_up=None, on_pools_ready=None, on_tick=None):
    """Start the era-walking cluster and drive it to the first Conway block. `on_nodes_up(root)` runs
    once the nodes answer (the e2e starts its follower there) and `on_pools_ready(driver)` once the
    pools are registered (the e2e starts its load there)."""
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
    if on_pools_ready:
        driver.on_pools_ready = lambda: on_pools_ready(driver)
    if on_nodes_up:
        on_nodes_up(root)
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
        if on_tick:
            on_tick(tip)
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


def freeze_classes(root):
    """Copy the compiled classes so later builds cannot change code under a running node.

    Returns the container classpath (the copy is mounted at /run/classes).
    """
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
    return ":".join(entries)


def scala_container(args, root, classpath, main_class, program_args, mounts=(), name=None):
    """`docker run` arguments for a JVM of the frozen classes in the reference network namespace."""
    command = ["run", "--rm", "--pull=never", "--network", f"container:{ref(args.name)}",
               "--cpus=1", "--memory=1g", "--user", "1000:1000",
               "-v", f"{REPO}:/work:ro", "-v", f"{root / 'classes'}:/run/classes:ro"]
    if name:
        command += ["--name", name]
    for mount in mounts:
        command += ["-v", mount]
    return command + ["--entrypoint", "java", JDK_IMAGE, "-Xmx768m", "-cp", classpath,
                      main_class, *program_args]


def follow(args):
    root = data_root(args.name)
    ports = json.loads((root / "ports.json").read_text())
    classpath = freeze_classes(root)
    log = root / "follower.jsonl"
    program = ["follow", "--genesis-dir", "/genesis", "--peer", f"127.0.0.1:{ports[str(args.node)]}",
               "--query-port", str(QUERY_PORT)]
    if args.seconds:
        program += ["--seconds", str(args.seconds)]
    command = scala_container(args, root, classpath, "lab.node.Main", program,
                              mounts=[f"{root / 'genesis'}:/genesis:ro"], name=f"{args.name}-scala")
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
    block arriving meanwhile is detected). Returns {key: parsed JSON} for the keys of BUNDLE, 'before'
    and 'after', or None if a query failed."""
    q = "/work/q"
    lines = [f"mkdir -p {q}", f"{CLI} query tip {SOCKET} > {q}/before"]
    for key, command in BUNDLE:
        extra = " --output-json" if key == "utxo" else ""
        lines.append(f"{CLI} {command} {SOCKET}{extra} > {q}/{key} 2> {q}/{key}.err &")
    lines += ["wait", f"{CLI} query tip {SOCKET} > {q}/after"]
    keys = ["before", "after"] + [k for k, _ in BUNDLE]
    lines.append(f"for f in {' '.join(keys)}; do echo \"@@$f\"; cat {q}/$f; done")
    out = in_ref(name, "\n".join(lines), timeout=180).stdout
    parts = {}
    for chunk in out.split("@@")[1:]:
        key, _, body = chunk.partition("\n")
        parts[key.strip()] = body
    try:
        return {k: json.loads(parts[k]) for k in keys}
    except (KeyError, json.JSONDecodeError):
        return None


def compare_once(name, attempts=10, scala_wait=30):
    """One bracketed comparison. Returns a result dict, or None if no stable bracket was found."""
    for attempt in range(attempts):
        bundle = query_bundle(name)
        if bundle is None:
            continue
        before, after = bundle["before"], bundle["after"]
        ls, utxo, protocol = bundle["ls"], bundle["utxo"], bundle["protocol"]
        gov = (bundle["gov"], bundle["committee"], bundle["drep"])
        if before["hash"] != after["hash"]:
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
            return {"tip": after, "error": "scala node never reached the reference tip"}
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
    governance = subprocess.Popen([sys.executable, __file__, "--name", args.name, "govload"],
                                  stdout=(root / "govload.log").open("w"), stderr=subprocess.STDOUT)
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
        governance.terminate()
        governance.wait()
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


# ---------------------------------------------------------------- the Scala node as a relay

SCALA_PORT = 3350   # the Scala node's node-to-node listener (loopback, shared network namespace)
RELAY_PORT = 3351   # the Haskell relay, whose only upstream peer is the Scala node
RELAY_DIR = "/work/relay"
RELAY_SOCKET = f"{RELAY_DIR}/sock"


def start_scala_relay(args, root, peers):
    """The Scala node following `peers` (reference node ports) and listening for node-to-node
    connections, with an on-disk chain so it can serve the whole chain to a syncing peer."""
    classpath = freeze_classes(root)
    db = root / "scala-db"
    db.mkdir(exist_ok=True)
    db.chmod(0o777)
    sock = root / "scala-sock"
    sock.mkdir(exist_ok=True)
    sock.chmod(0o777)
    program = ["follow", "--genesis-dir", "/genesis", "--query-port", str(QUERY_PORT),
               "--listen", f"127.0.0.1:{SCALA_PORT}", "--db", "/db", "--local-socket", "/sock/node.socket"]
    for port in peers:
        program += ["--peer", f"127.0.0.1:{port}"]
    command = scala_container(args, root, classpath, "lab.node.Main", program,
                              mounts=[f"{root / 'genesis'}:/genesis:ro", f"{db}:/db", f"{sock}:/sock"],
                              name=f"{args.name}-scala")
    log = (root / "follower.jsonl").open("a")
    return subprocess.Popen(["docker", *command], stdout=log, stderr=subprocess.STDOUT, text=True)


def start_haskell_relay(args):
    """A non-producing reference node whose topology names only the Scala node."""
    in_ref(args.name, f"mkdir -p {RELAY_DIR}/db")
    topology = {"bootstrapPeers": None,
                "localRoots": [{"accessPoints": [{"address": "127.0.0.1", "port": SCALA_PORT}],
                                "advertise": False, "behindFirewall": False, "trustable": True,
                                "diffusionMode": args.relay_mode, "hotValency": 1, "warmValency": 1}],
                "publicRoots": [{"accessPoints": [], "advertise": False}], "useLedgerAfterSlot": -1}
    put_file(args.name, f"{RELAY_DIR}/topology.json", json.dumps(topology))
    docker("exec", "-d", ref(args.name), "/bin/sh", "-c",
           f"cd {ENV} && cardano-node run --config {ENV}/configuration.yaml --topology {RELAY_DIR}/topology.json "
           f"--database-path {RELAY_DIR}/db --socket-path {RELAY_SOCKET} --port {RELAY_PORT} "
           f"--host-addr 127.0.0.1 > {RELAY_DIR}/node.log 2>&1")


def tip_at(name, socket):
    """`cardano-cli query tip` against one node socket, or None while it cannot answer."""
    r = docker("exec", ref(name), "/bin/sh", "-c",
               f"cardano-cli query tip --testnet-magic {MAGIC} --socket-path {socket}", check=False, timeout=30)
    try:
        tip = json.loads(r.stdout)
        return tip if "hash" in tip else None
    except json.JSONDecodeError:
        return None


def log_counts(path):
    """Event counts of the Scala node's JSON log."""
    counts = {}
    events, other = read_log(path)
    for e in events:
        counts[e["event"]] = counts.get(e["event"], 0) + 1
    return counts, events, other


def submit_through_local_socket(args, root):
    """`cardano-cli transaction submit` against the Scala node's node-to-client Unix socket (run in a
    throwaway container of the reference image that shares the socket directory). The transaction
    is built and signed through the Haskell relay. Returns what happened; the caller checks that it
    reached the mempool and a block."""
    tx = f"{root / 'ntc'}"
    pathlib.Path(tx).mkdir(exist_ok=True)
    pathlib.Path(tx).chmod(0o777)
    utxo_dir = f"{ENV}/utxo-keys/utxo3"
    address = in_ref(args.name, f"{CLI} address build --payment-verification-key-file {utxo_dir}/utxo.vkey "
                                f"--testnet-magic {MAGIC}").stdout.strip()
    utxos = json.loads(in_ref(args.name, f"{CLI} query utxo --address {address} --socket-path {RELAY_SOCKET} "
                                         f"--testnet-magic {MAGIC} --output-json").stdout)
    txin = max(utxos, key=lambda k: utxos[k]["value"]["lovelace"])
    target = in_ref(args.name, f"{CLI} address build --payment-verification-key-file "
                               f"{ENV}/utxo-keys/utxo1/utxo.vkey --testnet-magic {MAGIC}").stdout.strip()
    in_ref(args.name, f"{CLI} transaction build --testnet-magic {MAGIC} --socket-path {RELAY_SOCKET} --tx-in {txin} "
                      f"--tx-out {target}+12345678 --change-address {address} --out-file /work/ntc.body && "
                      f"{CLI} transaction sign --tx-body-file /work/ntc.body --signing-key-file {utxo_dir}/utxo.skey "
                      f"--testnet-magic {MAGIC} --out-file /work/ntc.signed")
    txid = in_ref(args.name, f"{CLI} transaction txid --tx-file /work/ntc.signed --output-text").stdout.strip()
    signed = subprocess.run(["docker", "exec", ref(args.name), "cat", "/work/ntc.signed"], capture_output=True, check=True).stdout
    (pathlib.Path(tx) / "ntc.signed").write_bytes(signed)
    r = docker("run", "--rm", "--pull=never", "--user", "1000:1000", "-v", f"{root / 'scala-sock'}:/sock",
               "-v", f"{tx}:/tx:ro", "--entrypoint", "/opt/reference/bin/cardano-cli", REFERENCE_IMAGE,
               "conway", "transaction", "submit", "--testnet-magic", str(MAGIC),
               "--socket-path", "/sock/node.socket", "--tx-file", "/tx/ntc.signed", check=False, timeout=60)
    return {"txid": txid, "txin": txin, "exitCode": r.returncode, "stdout": r.stdout.strip()[:300],
            "stderr": r.stderr.strip()[:600]}


def relay(args):
    """Fresh 3-pool cluster, the Scala node as a relay in front of a Haskell relay, and a load
    generator that submits through the Haskell relay.

    Transactions travel: Haskell relay -> (TxSubmission2) -> Scala mempool -> (TxSubmission2) -> pools
    -> blocks -> Scala node (ChainSync/BlockFetch from the pools) -> Haskell relay (from the Scala node)."""
    root = data_root(args.name)
    up(args)
    ports = json.loads((root / "ports.json").read_text())
    peers = [ports[str(n)] for n in sorted(map(int, ports))][: args.peers]
    scala, loader = None, None
    samples, failures, summary = [], [], {}
    try:
        scala = start_scala_relay(args, root, peers)
        log = root / "follower.jsonl"
        deadline = time.monotonic() + 120
        while time.monotonic() < deadline:
            counts, _, _ = log_counts(log)
            if counts.get("listening") and counts.get("extended"):
                break
            if scala.poll() is not None:
                raise SystemExit(f"the Scala node exited with {scala.returncode}; see {log}")
            time.sleep(1)
        else:
            raise SystemExit("the Scala node did not start listening and following within 120 s")
        # let the chain grow past k so the relay has to be served from the Scala node's stored blocks too
        grow_until = time.monotonic() + 180
        while time.monotonic() < grow_until:
            counts, _, _ = log_counts(log)
            if counts.get("extended", 0) >= args.relay_after_blocks:
                break
            time.sleep(1)
        relay_blocks = log_counts(log)[0].get("extended", 0)
        start_haskell_relay(args)
        relay_started = time.monotonic()
        reference = f"{ENV}/socket/node1/sock"
        synced_at = None
        deadline = time.monotonic() + args.sync_timeout
        while time.monotonic() < deadline:
            mine, theirs = tip_at(args.name, RELAY_SOCKET), tip_at(args.name, reference)
            if mine and theirs and mine["block"] >= theirs["block"] - 1 and mine["block"] > 0:
                synced_at = time.monotonic()
                break
            time.sleep(1)
        if synced_at is None:
            raise SystemExit(f"the Haskell relay did not sync from the Scala node within {args.sync_timeout} s")
        print(json.dumps({"relaySynced": tip_at(args.name, RELAY_SOCKET)}), flush=True)
        local = submit_through_local_socket(args, root)
        print(json.dumps({"localSubmit": local}), flush=True)
        command = [sys.executable, __file__, "--name", args.name, "load", "--seed", str(args.seed),
                   "--socket", RELAY_SOCKET, "--interval", str(args.interval), "--stats-every", "1"]
        loader = subprocess.Popen(command, stdout=(root / "load.log").open("w"), stderr=subprocess.STDOUT)
        stop = time.monotonic() + args.seconds
        while time.monotonic() < stop:
            time.sleep(args.every)
            if scala.poll() is not None:
                failures.append(f"the Scala node exited with {scala.returncode}")
                break
            sample = {"t": round(time.monotonic() - synced_at, 1),
                      "relay": tip_at(args.name, RELAY_SOCKET),
                      "pool1": tip_at(args.name, reference)}
            samples.append(sample)
            r, p = sample["relay"], sample["pool1"]
            print(json.dumps({"t": sample["t"], "relay": r and (r["block"], r["hash"][:12]),
                              "pool1": p and (p["block"], p["hash"][:12])}), flush=True)
        loader.terminate()
        loader.wait()
        loader = None
        # let the chain settle, then compare every node's tip
        final = {}
        settle = time.monotonic() + 60
        while time.monotonic() < settle:
            final = {"relay": tip_at(args.name, RELAY_SOCKET)}
            for n in (1, 2, 3):
                final[f"pool{n}"] = tip_at(args.name, f"{ENV}/socket/node{n}/sock")
            if all(final.values()) and len({t["hash"] for t in final.values()}) == 1:
                break
            time.sleep(1)
        final_hashes = {k: (v["block"], v["hash"]) if v else None for k, v in final.items()}
        counts, events, other = log_counts(log)
        scala_blocks = [e for e in events if e["event"] == "extended"]
        scala_tip = scala_blocks[-1]["block"]["hash"] if scala_blocks else None
        agree = len({v[1] for v in final_hashes.values() if v}) == 1 and all(final_hashes.values())
        lag = [p["pool1"]["block"] - p["relay"]["block"] for p in samples if p["relay"] and p["pool1"]]
        load_lines = (root / "load.log").read_text().strip().splitlines()
        load_stats = next((json.loads(l) for l in reversed(load_lines) if l.startswith("{") and "submitted" in l), {})
        epochs = sorted({p["pool1"]["epoch"] for p in samples if p["pool1"]})
        relay_log = in_ref(args.name, f"cat {RELAY_DIR}/node.log | tail -c 3000").stdout
        local["adopted"] = any(e["event"] == "mempoolAdopted" and e["tx"] == local["txid"] for e in events)
        local["addedToMempool"] = any(e["event"] == "mempoolAdded" and e["tx"] == local["txid"] for e in events)
        summary = {
            "localSubmit": local,
            "relaySyncSeconds": round(synced_at - relay_started, 1),
            "scalaBlocksWhenRelayStarted": relay_blocks,
            "upstreamPeers": len(peers),
            "samples": len(samples),
            "epochsObserved": epochs,
            "relayLagBlocks": {"max": max(lag, default=None), "atTip": sum(1 for x in lag if x <= 1), "of": len(lag)},
            "finalTips": final_hashes,
            "scalaTip": scala_tip,
            "allTipsEqual": agree,
            "load": load_stats,
            "propagation": {
                "submittedToHaskellRelay": load_stats.get("submitted"),
                "arrivedInScalaMempool": counts.get("mempoolAdded", 0),
                "rejectedByScalaMempool": counts.get("mempoolRejected", 0),
                "includedInBlocks": counts.get("mempoolAdopted", 0),
                "droppedAfterAdding": counts.get("mempoolDropped", 0)},
            "scalaEvents": {k: counts.get(k, 0) for k in
                            ("extended", "rolledBack", "reconnecting", "error", "inbound", "mempoolAdded",
                             "mempoolAdopted", "mempoolRejected", "mempoolDropped")},
            "unparsedScalaOutput": other[:10],
        }
        if not agree:
            failures.append(f"final tips differ: {final_hashes}")
        if scala_tip and final.get("pool1") and scala_tip != final["pool1"]["hash"]:
            failures.append(f"the Scala node's last block {scala_tip[:12]} is not the final tip")
        if lag and sum(1 for x in lag if x <= 1) < 0.9 * len(lag):
            failures.append("the Haskell relay was more than one block behind in over 10% of samples")
        if len(epochs) < args.min_epochs:
            failures.append(f"only {len(epochs)} epochs observed")
        if not load_stats.get("submitted"):
            failures.append("no transaction was submitted through the Haskell relay")
        elif summary["propagation"]["arrivedInScalaMempool"] < 0.9 * load_stats["submitted"]:
            failures.append("fewer than 90% of the submitted transactions reached the Scala mempool")
        if summary["propagation"]["includedInBlocks"] == 0:
            failures.append("no transaction passing through the Scala mempool was included in a block")
        if local["exitCode"] != 0 or not local["addedToMempool"] or not local["adopted"]:
            failures.append(f"the transaction submitted over the node-to-client socket did not reach a block: {local}")
        if counts.get("error"):
            failures.append(f"{counts['error']} error events in the Scala log")
        if other:
            failures.append(f"unexpected Scala output: {other[0][:200]}")
        summary["relayLogTail"] = relay_log[-600:] if failures else ""
    except SystemExit as e:
        failures.append(str(e))
    finally:
        if loader is not None:
            loader.terminate()
        docker("rm", "-f", f"{args.name}-scala", check=False)
        if scala is not None:
            scala.wait()
        summary["failures"] = failures
        summary["passed"] = not failures
        (root / "relay-summary.json").write_text(json.dumps(summary, indent=2))
        print(json.dumps(summary, indent=2))
        if not args.keep:
            docker("rm", "-f", ref(args.name), check=False)
            shutil.rmtree(root / "classes", ignore_errors=True)
            if args.keep_data:
                pass
    sys.exit(0 if not failures else 1)


# ---------------------------------------------------------------- era fixtures and e2e

ERAS = ["byron", "shelley", "allegra", "mary", "alonzo", "babbage", "conway"]
TAG_ERA = {0: "byron", 1: "byron", 2: "shelley", 3: "allegra", 4: "mary", 5: "alonzo", 6: "babbage", 7: "conway"}


def block_era(hex_line):
    """Era of a raw `[era tag, block]` line: the tag is the second byte (`82 <tag>`)."""
    return TAG_ERA[int(hex_line[2:4], 16)]


def capture_chain(args, root, out_name, extra=()):
    """Run CaptureMain over the whole chain of node 1 (up to 10 blocks behind its tip)."""
    ports = json.loads((root / "ports.json").read_text())
    classpath = freeze_classes(root)
    program = ["capture", "--peer", f"127.0.0.1:{ports['1']}", "--magic", str(MAGIC),
               "--blocks", "all", "--out", f"/run/out/{out_name}", *extra]
    command = scala_container(args, root, classpath, "lab.node.CaptureMain", program,
                              mounts=[f"{root}:/run/out"])
    r = subprocess.run(["docker", *command], text=True, capture_output=True, timeout=300)
    if r.returncode != 0:
        raise SystemExit(f"capture failed: {r.stderr.strip()[-600:]}")
    return [l for l in (root / out_name).read_text().split() if l]


def capture_eras(args):
    """Capture a running up-eras chain as fixtures: every block from Origin to a few blocks into
    Conway (header validation needs every VRF output for the nonces), plus the genesis and
    configuration files the node reads. The chain tail is cut so the fixture ends deep enough that
    no captured block can be rolled back."""
    root = data_root(args.name)
    lines = capture_chain(args, root, "blocks-all.hex")
    first_conway = next((i for i, l in enumerate(lines) if block_era(l) == "conway"), None)
    if first_conway is None:
        raise SystemExit("the captured chain has no Conway block yet; wait for up-eras to finish")
    keep = lines[: first_conway + args.conway_blocks]
    out = pathlib.Path(args.out)
    (out / "genesis").mkdir(parents=True, exist_ok=True)
    (out / "blocks.hex").write_text("\n".join(keep) + "\n")
    for f in ["shelley-genesis.json", "byron-genesis.json", "alonzo-genesis.json",
              "conway-genesis.json", "configuration.yaml"]:
        shutil.copyfile(root / "genesis" / f, out / "genesis" / f)
    eras = json.loads((root / "eras.json").read_text())
    shelley = json.loads((root / "genesis" / "shelley-genesis.json").read_text())
    counts = {e: sum(1 for l in keep if block_era(l) == e) for e in ERAS}
    manifest = {"capturedAt": time.strftime("%Y-%m-%d", time.gmtime()), "blocks": len(keep), "blocksPerEra": counts,
                "eras": eras["eras"], "protocolMajorByEpoch": eras["protocolMajorByEpoch"],
                "cluster": {k: shelley[k] for k in
                            ("epochLength", "activeSlotsCoeff", "securityParam", "slotLength", "networkMagic")}}
    (out / "eras.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print(json.dumps({"captured": len(keep), "blocksPerEra": counts, "out": str(out)}))


# ---------------------------------------------------------------- per-era ledger comparison

def lsq(name):
    return ntcquery.LocalStateQuery(ref(name), f"{ENV}/socket/node1/sock", MAGIC)


def protocol_parameters(name):
    return json.loads(in_ref(name, f"cardano-cli query protocol-parameters {SOCKET}").stdout)


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
        scala_view = {k: v for k, v in scala.items() if k in reference}
        if reference["rewardUpdate"] is None:
            scala_view["rewardUpdate"] = None  # reference pulser incomplete: nothing to compare
        scala_view["params"] = {k: eraview._float(v) for k, v in scala_view["params"].items()
                                if k in reference["params"]}
        differences = diff(reference, scala_view)
        return {"tip": tip, "epoch": reference["epoch"], "utxoSize": len(reference["utxo"]),
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
        self.per_era["byron"]["note"] = ("not compared: cardano-cli 11.2.3 cannot query a Byron ledger and the "
                                         "Scala node keeps no Byron UTxO")
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
                          "note": result.get("skipped") or result.get("error")}), flush=True)


def compare_era(name, era, label, comparisons):
    """Compare the Scala ledger with the reference in `era`; never raises."""
    try:
        if era == "conway":
            result = compare_once(name, attempts=12, scala_wait=3) or {"error": "no stable bracket"}
        else:
            result = compare_pre_conway(name, era)
    except Exception as e:  # a comparison problem must not stop the cluster walk
        import traceback
        result = {"error": repr(e), "traceback": traceback.format_exc()[-1500:]}
    comparisons.record(era, label, result)


def read_log(path):
    """(events, other lines) of the follower log."""
    events, other = [], []
    if path.exists():
        text = path.read_text(errors="replace")
        lines = text.split("\n")[:-1]  # a last line without newline is still being written
        for line in lines:
            if line.startswith("{"):
                try:
                    events.append(json.loads(line))
                    continue
                except json.JSONDecodeError:
                    pass
            if line.strip() and not line.startswith("SLF4J"):
                other.append(line)
    return events, other


def summarize_e2e(events, other, expected, exit_code):
    """Assertions and the summary over the follower's JSON log; returns (summary, failures)."""
    failures = []
    started = [e for e in events if e["event"] == "started"]
    transitions = []
    for e in events:
        if e["event"] != "eraTransition":
            continue
        t = {"from": e["from"], "to": e["to"], "epoch": e["epoch"], "slot": e["slot"], "block": e["block"]["blockNo"]}
        if transitions and transitions[-1]["to"] == t["to"] and any(
                x["event"] == "rolledBack" and transitions[-1]["slot"] <= x["from"]["slot"] for x in events):
            transitions[-1] = t  # the first block of the era was rolled back and the era entered again
        else:
            transitions.append(t)
    per_era = {e: {"blocks": 0, "firstEpoch": None, "lastEpoch": None, "headers": 0, "leaderThreshold": 0,
                   "overlay": 0, "leaderUnchecked": 0, "signatureVerified": 0, "signatureUnchecked": 0,
                   "boundary": 0} for e in ERAS}
    for e in events:
        if e["event"] == "extended":
            r = per_era[e["era"].lower()]
            r["blocks"] += 1
            r["firstEpoch"] = e["epoch"] if r["firstEpoch"] is None else r["firstEpoch"]
            r["lastEpoch"] = e["epoch"]
        elif e["event"] == "epochValidated":
            r = per_era[e["era"].lower()]
            r["headers"] += e["headers"]
            if e["era"] == "Byron":
                r["boundary"] += e["boundary"]
                r["signatureVerified"] += e["signatureVerified"]
                r["signatureUnchecked"] += e["signatureUnchecked"]
            else:
                r["leaderThreshold"] += e["thresholdChecked"]
                r["overlay"] += e["overlay"]
                r["leaderUnchecked"] += e["leaderUnchecked"]
    errors = [e for e in events if e["event"] == "error"]
    validation = [e for e in errors if e.get("kind") == "ValidationError"]
    fatal = [e for e in errors if e.get("kind") != "ValidationError"]
    summary = {
        "transitions": transitions,
        "expectedTransitions": expected,
        "blocksPerEra": per_era,
        "blocksApplied": sum(r["blocks"] for r in per_era.values()),
        "lastEpoch": max((e["epoch"] for e in events if e["event"] == "extended"), default=None),
        "validationErrors": validation,
        "fatalErrors": fatal,
        "unparsedOutput": other[:20],
        "rollbacks": sum(1 for e in events if e["event"] == "rolledBack"),
        "reconnects": sum(1 for e in events if e["event"] == "reconnecting"),
        "followerExit": exit_code,
        "ledgerComparison": {"status": "not run"},
    }
    if not started:
        failures.append("the follower never started")
    if [t["to"].lower() for t in transitions] != [x["era"] for x in expected]:
        failures.append(f"era transitions {[t['to'] for t in transitions]} differ from "
                        f"{[x['era'] for x in expected]}")
    else:
        for t, x in zip(transitions, expected):
            if t["epoch"] != x["epoch"]:
                failures.append(f"{x['era']} began in epoch {t['epoch']}, expected {x['epoch']}")
    if transitions and transitions[0]["from"] is not None:
        failures.append("the first transition must start from no era")
    for t, prev in zip(transitions[1:], transitions):
        if t["from"] is None or t["from"].lower() != prev["to"].lower():
            failures.append(f"transition to {t['to']} does not follow {prev['to']}")
    for era in ERAS:
        if per_era[era]["blocks"] == 0:
            failures.append(f"no {era} block was applied")
    byron = per_era["byron"]
    if byron["signatureUnchecked"]:
        failures.append(f"{byron['signatureUnchecked']} Byron headers had unchecked signatures")
    if byron["boundary"] == 0 or byron["signatureVerified"] == 0:
        failures.append("Byron epoch 0 was not validated (no boundary block or no verified signature)")
    if validation:
        failures.append(f"{len(validation)} validation errors: {validation[0]['message']}")
    if fatal:
        failures.append(f"{len(fatal)} fatal errors: {fatal[0]['message']}")
    if other:
        failures.append(f"unexpected non-JSON output: {other[0][:200]}")
    if exit_code not in (None, 0):
        failures.append(f"the follower exited with {exit_code}")
    return summary, failures


def start_follower(name, root):
    return subprocess.Popen([sys.executable, __file__, "--name", name, "follow"],
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def follower_problem(root, follower):
    """A reason to abort the run if the follower died or logged an error, else None."""
    events, other = read_log(root / "follower.jsonl")
    errors = [e for e in events if e["event"] == "error"]
    if errors:
        return f"follower error: {errors[0].get('message', errors[0])}"
    if other:
        return f"unexpected follower output: {other[0][:200]}"
    if follower.poll() is not None:
        return f"follower exited with {follower.returncode}"
    return None


def start_conway_load(args, root, loader):
    """Fund the Conway load wallets from stake delegator 3 and start the cardano-cli load generator."""
    largs = argparse.Namespace(name=args.name, wallets=4, stakers=3, fresh_wallets_only=True)
    wallets, _, address, _ = load_wallets(largs)
    funder = loader.delegator(3)
    outs = [(bytes.fromhex(address_hex(address[w])), 250_000_000, None, {}) for w in wallets]
    loader.act("conway", "conway-fund", funder, outs)
    command = [sys.executable, __file__, "--name", args.name, "load", "--wallets", "4", "--stakers", "3",
               "--stake-rate", "0.5", "--min-balance", "20000000", "--max-out", "5", "--fresh-wallets-only",
               "--interval", "1.0", "--seed", "3"]
    return subprocess.Popen(command, stdout=(root / "load-conway.log").open("w"), stderr=subprocess.STDOUT)


def e2e(args):
    """Era-walking cluster with the Scala follower from the first block and era-appropriate load.

    The follower starts as soon as the nodes answer, so the Scala ledger tracks the reference
    through every era; during each epoch the load generator submits that era's transactions and the
    two ledgers are compared at several points (see `compare_pre_conway`, `compare_once`)."""
    root = data_root(args.name)
    if root.exists():
        if not (root / "e2e-summary.json").exists():
            raise SystemExit(f"{root} exists and is not an e2e leftover; run `devnet.py --name {args.name} down`")
        down(args, quiet=True)  # leftovers of an earlier e2e run
    follower, loader, worker, conway_load = None, None, None, None
    comparisons = None
    summary, failures = {}, []
    state = {"last_check": 0.0}
    try:
        comparisons = Comparisons(root)

        def nodes_up(_root):
            nonlocal follower
            follower = start_follower(args.name, root)

        def pools_ready(driver):
            nonlocal loader, worker
            if args.no_load:
                return
            loader = eraload.EraLoad(args.name, lambda: lsq(args.name), log=lambda m: print(json.dumps(m), flush=True))
            loader.ready.set()
            compare = (lambda era, label: None) if args.no_compare else \
                (lambda era, label: compare_era(args.name, era, label, comparisons))

            def conway_hook():
                nonlocal conway_load
                conway_load = start_conway_load(args, root, loader)
            worker = threading.Thread(target=loader.run, args=(compare, conway_hook), daemon=True)
            worker.start()

        def on_tick(_tip):
            if time.monotonic() - state["last_check"] > 4:
                state["last_check"] = time.monotonic()
                problem = follower_problem(root, follower)
                if problem:
                    raise SystemExit(problem)

        up_eras(args, on_nodes_up=nodes_up, on_pools_ready=pools_ready, on_tick=on_tick)
        eras = json.loads((root / "eras.json").read_text())
        config = json.loads((root / "genesis" / "configuration.yaml").read_text())
        expected = []
        for t in eras["eras"]:
            epoch = 0 if t["era"] == "byron" else config[f"Test{t['era'].capitalize()}HardForkAtEpoch"]
            if epoch != t["epoch"]:
                raise SystemExit(f"eras.json puts {t['era']} at epoch {t['epoch']}, configuration at {epoch}")
            expected.append({"era": t["era"], "epoch": epoch})
        target = args.until_epoch if args.until_epoch is not None else expected[-1]["epoch"] + CONWAY_EPOCHS
        log = root / "follower.jsonl"
        deadline = time.monotonic() + args.timeout
        reason = "timeout"
        while time.monotonic() < deadline:
            time.sleep(2)
            events, other = read_log(log)
            applied = [e["epoch"] for e in events if e["event"] == "extended" and e["era"] == "Conway"]
            if any(e["event"] == "error" for e in events) or other:
                reason = "error"
                break
            if follower.poll() is not None:
                reason = "follower exited"
                break
            if applied and max(applied) >= target:
                # let the worker finish the comparisons of the epoch before the target one
                reason = "reached"
                break
        if loader is not None:
            loader.stop.set()
            worker.join(timeout=90)
        exit_code = follower.poll()
        events, other = read_log(log)
        summary, failures = summarize_e2e(events, other, expected, exit_code)
        summary["stoppedBecause"] = reason
        summary["targetConwayEpoch"] = target
        if reason != "reached":
            failures.append(f"stopped without reaching Conway epoch {target}: {reason}")
        if not args.no_compare:
            failures += judge_comparisons(comparisons, args.min_comparisons, loader is not None)
    except SystemExit as e:
        failures.append(str(e))
    finally:
        if loader is not None:
            loader.stop.set()
        if conway_load is not None:
            conway_load.terminate()
        if follower is not None and follower.poll() is None:
            docker("rm", "-f", f"{args.name}-scala", check=False)
            follower.wait()
        if comparisons is not None:
            summary["ledgerComparison"] = {"perEra": comparisons.per_era, "mismatches": comparisons.mismatches}
        if loader is not None:
            summary["load"] = loader.stats
        summary["failures"] = failures
        summary["passed"] = not failures
        root.mkdir(parents=True, exist_ok=True)
        (root / "e2e-summary.json").write_text(json.dumps(summary, indent=2))
        shutil.rmtree(root / "classes", ignore_errors=True)
        print(json.dumps(summary, indent=2))
        if comparisons is not None:
            print(comparison_table(comparisons))
        if not args.keep:
            for c in (f"{args.name}-scala", ref(args.name)):
                docker("rm", "-f", c, check=False)
    sys.exit(0 if not failures else 1)


def judge_comparisons(comparisons, minimum, loaded):
    """Failures from the comparison tally: any mismatch, or an era with too few matched comparisons."""
    failures = []
    for era in ERAS[1:]:
        row = comparisons.per_era[era]
        if row["mismatched"]:
            failures.append(f"{era}: {row['mismatched']} of {row['matched'] + row['mismatched']} ledger comparisons differ")
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


def down(args, quiet=False):
    for c in (f"{args.name}-scala", ref(args.name)):
        docker("rm", "-f", c, check=False)
    shutil.rmtree(data_root(args.name), ignore_errors=True)
    if not quiet:
        print(json.dumps({"down": True}))


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--name", help="default csl-devnet, or csl-eras for up-eras")
    sub = p.add_subparsers(dest="command", required=True)
    sub.add_parser("up").set_defaults(run=up)
    eras_options = argparse.ArgumentParser(add_help=False)
    eras_options.add_argument("--epoch-length", type=int, default=500)
    eras_options.add_argument("--active-slots-coeff", type=float, default=0.2)
    eras_options.add_argument("--byron-epochs", type=int, default=1)
    eras_options.add_argument("--byron-slot-ms", type=int, default=1000)
    eras_options.add_argument("--stall-seconds", type=int, default=90)
    sub.add_parser("up-eras", parents=[eras_options],
                   help="start in Byron and walk Shelley..Conway (one epoch per era step)").set_defaults(run=up_eras)
    ce = sub.add_parser("capture-eras", help="capture the running up-eras chain as fixtures")
    ce.add_argument("--out", default=str(REPO / "fixtures/eras"))
    ce.add_argument("--conway-blocks", type=int, default=3)
    ce.set_defaults(run=capture_eras)
    e2 = sub.add_parser("e2e", parents=[eras_options],
                        help="up-eras, follow from Origin, assert every era and transition")
    e2.add_argument("--timeout", type=int, default=1500)
    e2.add_argument("--until-epoch", type=int,
                    help=f"Conway epoch to reach (default: first Conway epoch + {CONWAY_EPOCHS})")
    e2.add_argument("--keep", action="store_true", help="leave the cluster running")
    e2.add_argument("--no-load", action="store_true", help="no era-specific transactions (blocks only)")
    e2.add_argument("--no-compare", action="store_true", help="skip the per-era ledger comparisons")
    e2.add_argument("--min-comparisons", type=int, default=3,
                    help="matched ledger comparisons required in each Shelley-based era")
    e2.set_defaults(run=e2e)
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
    ld.add_argument("--max-out", type=int, default=200, help="largest output in ADA")
    ld.add_argument("--min-balance", type=int, default=300_000_000)
    ld.add_argument("--fresh-wallets-only", action="store_true", help="skip the genesis utxo-keys wallets")
    ld.add_argument("--stats-every", type=int, default=20, help="print the running totals every N transactions")
    ld.add_argument("--socket", help="node socket (inside the reference container) to build and submit through")
    ld.set_defaults(run=load)
    gl = sub.add_parser("govload", help="Conway governance and Plutus scenarios on a Conway-only cluster")
    gl.add_argument("--seconds", type=int)
    gl.set_defaults(run=govload)
    sub.add_parser("compare").set_defaults(run=compare)
    sk = sub.add_parser("soak")
    sk.add_argument("--seconds", type=int, default=600)
    sk.add_argument("--every", type=int, default=20)
    sk.add_argument("--seed", type=int, default=1)
    sk.set_defaults(run=soak)
    rl = sub.add_parser("relay", help="Scala node as a relay serving a Haskell relay; load through the relay")
    rl.add_argument("--seconds", type=int, default=300, help="load phase length")
    rl.add_argument("--every", type=int, default=5, help="seconds between tip samples")
    rl.add_argument("--seed", type=int, default=1)
    rl.add_argument("--interval", type=float, default=0.5, help="load generator pause between transactions")
    rl.add_argument("--peers", type=int, default=3, help="reference nodes the Scala node follows")
    rl.add_argument("--sync-timeout", type=int, default=240)
    rl.add_argument("--relay-after-blocks", type=int, default=30,
                    help="start the Haskell relay once the Scala node has applied this many blocks")
    rl.add_argument("--min-epochs", type=int, default=4)
    rl.add_argument("--relay-mode", choices=["InitiatorAndResponder", "InitiatorOnly"], default="InitiatorAndResponder")
    rl.add_argument("--keep", action="store_true", help="leave the cluster running")
    rl.add_argument("--keep-data", action="store_true")
    rl.set_defaults(run=relay)
    sub.add_parser("down").set_defaults(run=down)
    args = p.parse_args()
    args.name = args.name or ("csl-eras" if args.command in ("up-eras", "capture-eras", "e2e") else "csl-devnet")
    args.run(args)


if __name__ == "__main__":
    main()
