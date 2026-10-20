# SPDX-License-Identifier: Apache-2.0
"""Starting, inspecting and removing the Haskell reference cluster (plain and era-walking)."""
import json
import os
import pathlib
import shutil
import subprocess
import time
from . import util
from .util import ENV, cache_dir, jdk_image, MAGIC, REFERENCE_IMAGE, REPO, TX, USER, data_root, docker, in_ref, put_file, query, read_json, ref


def start_reference(name):
    docker("run", "-d", "--name", ref(name), "--pull=never",
           "--cpus=3", "--memory=6g", "--user", USER,
           "--tmpfs", f"/work:rw,exec,size=2g,uid={os.getuid()},gid={os.getgid()}",
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


def era_plan(args):
    """ERA_PLAN with `--shelley-epochs` epochs at protocol 2. The pools produce blocks from the third
    epoch (the overlay is switched off in the second, the pool distribution lags two boundaries), so
    with three Shelley epochs the pre-Allegra reward rules pay out: blocks of epoch 3, the update made
    in epoch 4 under the epoch 3 parameters (protocol 2), paid at the boundary into epoch 5."""
    n = getattr(args, "shelley_epochs", 1)
    return [ERA_PLAN[0]] * n + ERA_PLAN[1:]


STABILITY_FACTOR = 6  # a protocol-parameter update must land 2 * 3k/f slots before the epoch ends


def era_tip(name, node=1):
    r = in_ref(name, f"cardano-cli query tip {util.SOCKET.replace('node1', f'node{node}')}")
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
    for i, (era, _) in enumerate(era_plan(args)):
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
    shelley["protocolParams"]["minUTxOValue"] = getattr(args, "min_utxo_value", 0)
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


def start_nodes(name, which=(1, 2, 3)):
    ports = {}
    for n in which:
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
        self.plan = era_plan(args)
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
        out = in_ref(self.name, f"cardano-cli query utxo --address {address} {util.SOCKET} --output-json").stdout
        return {k: v["value"]["lovelace"] for k, v in json.loads(out).items()}

    def submit(self, era, build, label):
        """Build with `compatible <era> ... signed-transaction`, submit, and wait for the inputs to be spent."""
        r = in_ref(self.name, f"{build} && cardano-cli conway transaction submit {util.SOCKET} --tx-file {TX}/{label}.tx")
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
        # --cross-rewards: pool 1 pays its leader reward to delegator 2 (a member of pool 2, which delegator 1
        # owns) and pool 2 pays to delegator 3 (who owns and leads pool 3), so accounts earn several rewards
        cross = getattr(self.args, "cross_rewards", False)
        owner_of = {1: 1, 2: 1, 3: 3} if cross else {1: 1, 2: 2, 3: 3}
        reward_of = {1: 2, 2: 3, 3: 3} if cross else {1: 1, 2: 2, 3: 3}
        for n in (1, 2, 3):
            d, pool = f"{ENV}/stake-delegators/delegator{n}", f"{ENV}/pools-keys/pool{n}"
            owner = f"{ENV}/stake-delegators/delegator{owner_of[n]}"
            reward = f"{ENV}/stake-delegators/delegator{reward_of[n]}"
            in_ref(self.name, f"{cli} stake-address registration-certificate --stake-verification-key-file {d}/staking.vkey "
                              f"--out-file {TX}/reg{n}.cert && "
                              f"{cli} stake-pool registration-certificate --cold-verification-key-file {pool}/cold.vkey "
                              f"--vrf-verification-key-file {pool}/vrf.vkey --pool-pledge 0 --pool-cost 0 "
                              f"--pool-margin {getattr(self.args, 'pool_margin', '0')} "
                              f"--pool-reward-account-verification-key-file {reward}/staking.vkey "
                              f"--pool-owner-stake-verification-key-file {owner}/staking.vkey --testnet-magic {MAGIC} "
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
        # a step that stays at the same protocol version proposes only the parameter change
        version = f"--protocol-major-version {major} --protocol-minor-version 0" if major else ""
        in_ref(self.name, f"{cli} governance action create-protocol-parameters-update --epoch {epoch} {keys} "
                          f"{version}{extra} --out-file {TX}/pp{major or 'same'}-{epoch}.proposal")
        (txin, lovelace), = sorted(self.utxo(self.address[1]).items(), key=lambda kv: -kv[1])[:1]
        fee = 300_000
        signers = [f"--signing-key-file {ENV}/stake-delegators/delegator1/payment.skey"]
        signers += [f"--signing-key-file {ENV}/pools-keys/pool{n}/cold.skey" for n in (1, 2, 3)]
        build = (f"{cli} transaction signed-transaction --tx-in {txin} --tx-out {self.address[1]}+{lovelace - fee} "
                 f"--update-proposal-file {TX}/pp{major or 'same'}-{epoch}.proposal {' '.join(signers)} --testnet-magic {MAGIC} "
                 f"--fee {fee} --out-file {TX}/pp{major or 'same'}-{epoch}.tx")
        r = self.submit(era, build, f"pp{major or 'same'}-{epoch}")
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
        pv = json.loads(in_ref(self.name, f"cardano-cli query protocol-parameters {util.SOCKET}").stdout)["protocolVersion"]
        self.protocol[epoch] = pv["major"]
        print(json.dumps({"epoch": epoch, "era": era, "protocolMajor": pv["major"]}), flush=True)
        if index == 0:
            self.setup_pools(era)
        plan = self.plan
        if index + 1 < len(plan):
            decentralise = index == 1
            major = plan[index + 1][1]
            if major != plan[index][1] or decentralise:
                if tip["slotInEpoch"] > self.epoch_length - STABILITY_FACTOR * 5 / self.args.active_slots_coeff - 20:
                    raise SystemExit(f"epoch {epoch} is too far gone to submit an update proposal: {tip}")
                self.propose(era, epoch, major if major != plan[index][1] else None, decentralise=decentralise)
        return index == len(plan) - 1 and era == plan[-1][0]


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


def freeze_classes(root, previous=None):
    """Copy the compiled classes so later builds cannot change code under a running node.

    Returns the container classpath (the copy is mounted at /run/classes). With `previous`, the
    directory of a copy made earlier (`<run dir>/classes` of a run of another build) is used instead of the
    checkout's classes, for explicit comparisons of pinned builds. This does not imply that the
    current node supports an earlier build's persisted formats; snapshot retirement is independent.
    """
    classpath = (REPO / "node/target/runtime-classpath.txt").read_text().strip().split(":")
    sources = []
    for entry in classpath:
        if entry.endswith("/classes"):
            path = pathlib.PurePosixPath(entry)
            if not path.is_relative_to("/work"):
                raise SystemExit(f"classpath classes must be built under /work: {entry}; "
                                 "run node/runtimeClasspathFile in the checkout's Docker build")
            relative = path.relative_to("/work")
            module = relative.parts[0]
            source = pathlib.Path(previous) / module if previous else REPO / relative
            if not source.is_dir() or not any(source.rglob("*.class")):
                raise SystemExit(f"missing compiled classes: {source}; "
                                 "run node/runtimeClasspathFile in the checkout's Docker build")
            sources.append((module, source))
    if not sources:
        raise SystemExit("runtime classpath contains no compiled classes; run node/runtimeClasspathFile")
    frozen = root / "classes"
    shutil.rmtree(frozen, ignore_errors=True)
    for module, source in sources:
        shutil.copytree(source, frozen / module)
    entries = []
    for entry in classpath:
        if entry.endswith("/classes"):
            module = entry[len("/work/"):].split("/")[0]
            entries.append(f"/run/classes/{module}")
        else:
            entries.append(entry)
    return ":".join(entries)


def database_mounts(db):
    """Bind the database and the same stable sibling lease used by host-side commands.

    `/db` has an unwritable container parent; binding the lease explicitly preserves coordination
    with an offline tool that opens the host database path without granting access to its parent.
    """
    canonical = db.resolve()
    lock = canonical.with_name(f".{canonical.name}.scarab-lock")
    with lock.open("a"):
        pass
    return [f"{db}:/db", f"{lock}:/.db.scarab-lock"]


def scala_container(args, root, classpath, main_class, program_args, mounts=(), name=None, cpus=None):
    """`docker run` arguments for a JVM of the frozen classes in the reference network namespace.

    The container gets `cpus` CPUs, else `--scala-cpus` of the scenario, else 2.
    """
    cpus = cpus if cpus is not None else getattr(args, "scala_cpus", 2)
    # MALLOC_ARENA_MAX=2: glibc's per-thread arenas fragment (roadmap K6); also set in the image
    command = ["run", "--rm", "--pull=never", "--network", f"container:{ref(args.name)}",
               f"--cpus={cpus}", "--memory=2g", "--user", USER, "-e", "MALLOC_ARENA_MAX=2",
               "-v", f"{REPO}:/work:ro", "-v", f"{cache_dir()}:/work/.cache:ro",
               "-v", f"{root / 'classes'}:/run/classes:ro"]
    if name:
        command += ["--name", name]
    for mount in mounts:
        command += ["-v", mount]
    # G1 with a short pause goal also with one CPU, where the JVM would pick the serial collector:
    # a collector pause stops every thread, so it is the timers' lateness (roadmap D8).
    jvm = ["-Xmx768m", "-XX:+UseG1GC", "-XX:MaxGCPauseMillis=20"]
    return command + ["--entrypoint", "java", jdk_image(), *jvm, "-cp", classpath, main_class, *program_args]


def down(args, quiet=False):
    for c in (f"{args.name}-scala", f"{args.name}-cli", ref(args.name)):
        docker("rm", "-f", c, check=False)
    shutil.rmtree(data_root(args.name), ignore_errors=True)
    if not quiet:
        print(json.dumps({"down": True}))
