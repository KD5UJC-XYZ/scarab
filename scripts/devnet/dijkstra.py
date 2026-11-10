# SPDX-License-Identifier: Apache-2.0
"""A reference-only cluster of the Leios testnet build that crosses from Conway into Dijkstra (#54).

REFERENCE ONLY: Scarab cannot follow a Dijkstra chain yet, so nothing here starts a Scala node. The cluster is the
evidence base for the Dijkstra codec and rule work: `up-dijkstra` starts it and drives it into Dijkstra with one
Dijkstra transaction, `capture-dijkstra` saves its chain from Origin as a fixture.

The image is the one the public Leios testnet runs (release rc-2026w44, cardano-node b004fe0c, pinned in
`docs/data/reference-pins.json`; digest and binaries in `docs/leios.md` section 12). The 11.1.3 reference cannot
enter Dijkstra: its consensus never schedules a transition out of Conway. The image has no `cardano-testnet`, so
the network comes from `cardano-cli dijkstra genesis create-testnet-data` and the nodes are started directly.

The chain starts in Conway (every earlier `Test*HardForkAtEpoch` is 0) and forks into Dijkstra at
`TestDijkstraHardForkAtEpoch`. `ExperimentalHardForksEnabled` makes the node's protocol version 12.0
(`cardano-node/src/Cardano/Node/Protocol/Cardano.hs:192-194` at b004fe0c), and the Shelley genesis carries
protocol 12.0 so the ledger is at major 12 inside Dijkstra without a governance step, as on the testnet.
"""
import hashlib
import json
import os
import pathlib
import shutil
import subprocess
import time
from datetime import datetime, timedelta, timezone
from cleanup import container_labels
from . import cborspan
from .util import ENV, MAGIC, REPO, USER, data_root, docker, in_ref, put_file, read_json, ref, without_resource_statistics

LEIOS_TAG = "ghcr.io/input-output-hk/ouroboros-leios/cardano-node-testnet:rc-2026w44"
LEIOS_DIGEST = "sha256:70439665b4b93c58ecaa8f73c8919ac7b358f938dd262b6644cd515270773fca"
LEIOS_IMAGE = f"{LEIOS_TAG.rsplit(':', 1)[0]}@{LEIOS_DIGEST}"
LEIOS_NODE_REVISION = "b004fe0c50913dcdaa827a98e68a43d0ccce152a"
IMAGE_CONFIG = "/app/config"
GENESIS_FILES = ("byron-genesis.json", "shelley-genesis.json", "alonzo-genesis.json", "conway-genesis.json",
                 "dijkstra-genesis.json")
SHELLEY_ERAS = ("shelley", "allegra", "mary", "alonzo", "babbage", "conway", "dijkstra")
# HFC block tags (`[tag, block]` of the ImmutableDB and BlockFetch): 7 is Conway, 8 Dijkstra
BLOCK_TAG_ERA = {7: "conway", 8: "dijkstra"}
TX_DIR = "/work/tx"
BASE_PORT = 3001
DEFAULT_FIXTURE = REPO / "fixtures/dijkstra-devnet"
# the Conway blocks `up-dijkstra --conway-only` waits for: the pools forge and node 1 answers queries
CONWAY_ONLY_BLOCKS = 3


def dijkstra_plan(dijkstra_epoch, major):
    """[(era, first epoch, protocol major at its start)]: Conway from epoch 0, Dijkstra from `dijkstra_epoch`.
    Both run at the genesis protocol version; the version is not raised by governance."""
    if dijkstra_epoch < 1:
        raise ValueError("Dijkstra must start after at least one Conway epoch (TestDijkstraHardForkAtEpoch >= 1)")
    return [("conway", 0, major), ("dijkstra", dijkstra_epoch, major)]


def image_digest_ok(repo_digests):
    """Whether the local image's `RepoDigests` carry the pinned digest."""
    return any(d.endswith(f"@{LEIOS_DIGEST}") for d in repo_digests or ())


def shelley_spec(image_shelley, epoch_length, slot_length, active_slots_coeff, security_param, major):
    """The `--spec-shelley` input: the testnet's Shelley genesis with its own funds, delegates and stake removed
    (`create-testnet-data` makes them) and the devnet's timing, magic and protocol version. `genDelegs` must be
    present, if empty, or the spec does not decode."""
    spec = {k: v for k, v in image_shelley.items() if k not in ("extraConfig", "initialFunds", "staking")}
    spec.update({"genDelegs": {}, "initialFunds": {}, "staking": {"pools": {}, "stake": {}},
                 "epochLength": epoch_length, "slotLength": slot_length, "activeSlotsCoeff": active_slots_coeff,
                 "securityParam": security_param, "networkMagic": MAGIC})
    spec["protocolParams"] = {**image_shelley["protocolParams"], "protocolVersion": {"major": major, "minor": 0}}
    return spec


def node_config(image_config, genesis_hashes, dijkstra_epoch):
    """The node configuration: the testnet's, with every Shelley-based era from epoch 0 but Dijkstra at
    `dijkstra_epoch`, the genesis hashes of this network, no `MinNodeVersion` (the image's names an older build),
    and tracing to stdout only: the testnet's Prometheus port and forwarder would collide between nodes."""
    config = {k: v for k, v in image_config.items() if k != "MinNodeVersion"}
    for era in SHELLEY_ERAS:
        config[f"Test{era.capitalize()}HardForkAtEpoch"] = dijkstra_epoch if era == "dijkstra" else 0
    config["ExperimentalHardForksEnabled"] = True
    config["ExperimentalProtocolsEnabled"] = True
    for file in GENESIS_FILES:
        era = file.split("-")[0].capitalize()
        config[f"{era}GenesisFile"] = file
        config[f"{era}GenesisHash"] = genesis_hashes[file]
    options = dict(config.get("TraceOptions", {}))
    options[""] = {**options.get("", {}), "backends": ["Stdout MachineFormat"]}
    config["TraceOptions"] = options
    return config


def topology(node, pools):
    """Node `node`'s topology: the other pools as trusted local roots, no bootstrap or public peers."""
    others = [{"address": "127.0.0.1", "port": BASE_PORT + n - 1} for n in range(1, pools + 1) if n != node]
    return {"bootstrapPeers": None,
            "localRoots": [{"accessPoints": others, "advertise": False, "trustable": True,
                            "valency": len(others)}],
            "publicRoots": [{"accessPoints": [], "advertise": False}],
            "useLedgerAfterSlot": -1}


def create_testnet_data_command(pools, start_time):
    return (f"cardano-cli dijkstra genesis create-testnet-data --spec-shelley {ENV}-spec/shelley.json "
            f"--spec-alonzo {IMAGE_CONFIG}/alonzo-genesis.json --spec-conway {IMAGE_CONFIG}/conway-genesis.json "
            f"--spec-dijkstra {IMAGE_CONFIG}/dijkstra-genesis.json --pools {pools} --stake-delegators {pools} "
            f"--utxo-keys 1 --total-supply 2000000000000000 --delegated-supply 1000000000000000 "
            f"--testnet-magic {MAGIC} --start-time {start_time} --out-dir {ENV}")


def socket(node=1):
    return f"{ENV}/socket/node{node}/sock"


def node_command(node):
    """`cardano-node run` of pool `node`, in its own directory (the Leios database is opened relative to it)."""
    data, pool = f"{ENV}/node-data/node{node}", f"{ENV}/pools-keys/pool{node}"
    return (f"cd {data} && cardano-node run --config {ENV}/config.json --topology {data}/topology.json "
            f"--database-path {data}/db --socket-path {socket(node)} --host-addr 127.0.0.1 "
            f"--port {BASE_PORT + node - 1} --shelley-kes-key {pool}/kes.skey --shelley-vrf-key {pool}/vrf.skey "
            f"--shelley-operational-certificate {pool}/opcert.cert > /work/node{node}.log 2>&1")


def start_reference(name):
    """The reference container, from the pinned digest; the nodes and the CLI run inside it."""
    inspected = docker("image", "inspect", "--format", "{{json .RepoDigests}}", LEIOS_TAG, check=False)
    if inspected.returncode != 0 or not image_digest_ok(json.loads(inspected.stdout or "[]")):
        raise SystemExit(f"{LEIOS_TAG} with digest {LEIOS_DIGEST} is not present; `docker pull {LEIOS_IMAGE}`")
    docker("run", "-d", "--name", ref(name), "--pull=never", *container_labels("devnet", name),
           "--cpus=3", "--memory=6g", "--user", USER, "-e", "HOME=/work",
           "--tmpfs", f"/work:rw,exec,size=2g,uid={os.getuid()},gid={os.getgid()}",
           "--entrypoint", "/bin/sh", LEIOS_IMAGE, "-c", "sleep infinity")


def genesis_hashes(name):
    """Blake2b-256 of each genesis file as the node checks it (Byron over its canonical JSON)."""
    hashes = {}
    for file in GENESIS_FILES:
        command = ("cardano-cli byron genesis print-genesis-hash --genesis-json" if file.startswith("byron")
                   else "cardano-cli hash genesis-file --genesis")
        hashes[file] = in_ref(name, f"{command} {ENV}/{file}").stdout.strip()
    return hashes


def tip(name, node=1):
    """The node's tip (`cardano-cli query tip` is answered in every era), or None while it does not answer."""
    r = in_ref(name, f"cardano-cli query tip --testnet-magic {MAGIC} --socket-path {socket(node)}", check=False)
    try:
        return json.loads(r.stdout) if r.returncode == 0 else None
    except json.JSONDecodeError:
        return None


def protocol_major(name):
    out = in_ref(name, f"cardano-cli dijkstra query protocol-parameters --testnet-magic {MAGIC} "
                       f"--socket-path {socket()}").stdout
    return json.loads(out)["protocolVersion"]["major"]


def submit_transaction(name):
    """One Dijkstra-era payment from the genesis UTxO key to itself; returns its id. The signed text envelope
    stays in `TX_DIR/dijkstra.tx` for the capture."""
    cli = "cardano-cli dijkstra"
    net = f"--testnet-magic {MAGIC} --socket-path {socket()}"
    key = f"{ENV}/utxo-keys/utxo1/utxo"
    address = in_ref(name, f"{cli} address build --payment-verification-key-file {key}.vkey "
                           f"--testnet-magic {MAGIC}").stdout.strip()
    utxo = json.loads(in_ref(name, f"{cli} query utxo --address {address} {net} --output-json").stdout)
    if not utxo:
        raise SystemExit(f"the genesis UTxO key's address {address} holds nothing")
    txin = sorted(utxo)[0]
    r = in_ref(name, f"mkdir -p {TX_DIR} && {cli} transaction build {net} --tx-in {txin} "
                     f"--tx-out {address}+5000000 --change-address {address} --out-file {TX_DIR}/dijkstra.body && "
                     f"{cli} transaction sign --tx-body-file {TX_DIR}/dijkstra.body --signing-key-file {key}.skey "
                     f"--testnet-magic {MAGIC} --out-file {TX_DIR}/dijkstra.tx && "
                     f"{cli} transaction submit {net} --tx-file {TX_DIR}/dijkstra.tx", check=False)
    if r.returncode != 0:
        raise SystemExit(f"the Dijkstra transaction was not accepted: {(r.stderr or r.stdout).strip()[:600]}")
    text = in_ref(name, f"{cli} transaction txid --tx-file {TX_DIR}/dijkstra.tx").stdout.strip()
    return parse_txid(text), txin, address


def parse_txid(text):
    """The id `transaction txid` printed: a JSON object `{"txhash": ...}` or the bare hex."""
    return json.loads(text)["txhash"] if text.startswith("{") else text


def tx_spent(name, address, txin):
    net = f"--testnet-magic {MAGIC} --socket-path {socket()}"
    out = in_ref(name, f"cardano-cli dijkstra query utxo --address {address} {net} --output-json", check=False)
    try:
        return out.returncode == 0 and txin not in json.loads(out.stdout)
    except json.JSONDecodeError:
        return False


def finished(transaction, tip_now, blocks_after, security_param):
    """Whether the block that included the transaction and `blocks_after` more are in the ImmutableDB: the tip
    is more than `security_param` blocks past the last of them. `includedBy` is a tip read after the input was
    seen spent, so the inclusion block is at most that one."""
    included = transaction.get("includedBy")
    return included is not None and tip_now["block"] > included["block"] + blocks_after + security_param


def up_dijkstra(args):
    """Start the cluster and wait until it is in Dijkstra, a Dijkstra transaction is in a block and the chain is
    `--blocks-after` blocks past it plus the security parameter (so that block is in the ImmutableDB). With
    `--conway-only` it returns once Conway has `CONWAY_ONLY_BLOCKS` blocks, and the cluster stays in Conway until
    `--dijkstra-epoch`: a Leios-build Conway cluster for `refusal-corpus --reference-build leios-b004fe0c`."""
    root = data_root(args.name)
    if root.exists():
        raise SystemExit(f"{root} exists; run `devnet.py down` first")
    plan = dijkstra_plan(args.dijkstra_epoch, args.genesis_protocol_major)
    (root / "genesis").mkdir(parents=True)
    start_reference(args.name)
    image_shelley = read_json(args.name, f"{IMAGE_CONFIG}/shelley-genesis.json")
    in_ref(args.name, f"mkdir -p {ENV}-spec")
    put_file(args.name, f"{ENV}-spec/shelley.json", json.dumps(shelley_spec(
        image_shelley, args.epoch_length, args.slot_length, args.active_slots_coeff, args.security_param,
        args.genesis_protocol_major), indent=2))
    start = (datetime.now(timezone.utc) + timedelta(seconds=20)).strftime("%Y-%m-%dT%H:%M:%SZ")
    r = in_ref(args.name, create_testnet_data_command(args.pools, start) + " > /work/create-testnet-data.log 2>&1",
               check=False)
    if r.returncode != 0:
        raise SystemExit("create-testnet-data failed; see /work/create-testnet-data.log in the reference container")
    hashes = genesis_hashes(args.name)
    config = without_resource_statistics(
        args.name, node_config(read_json(args.name, f"{IMAGE_CONFIG}/config.json"), hashes, args.dijkstra_epoch))
    put_file(args.name, f"{ENV}/config.json", json.dumps(config, indent=2))
    for n in range(1, args.pools + 1):
        in_ref(args.name, f"mkdir -p {ENV}/node-data/node{n} {ENV}/socket/node{n}")
        put_file(args.name, f"{ENV}/node-data/node{n}/topology.json", json.dumps(topology(n, args.pools), indent=2))
        docker("exec", "-d", ref(args.name), "/bin/sh", "-c", node_command(n))
    ports = {n: BASE_PORT + n - 1 for n in range(1, args.pools + 1)}
    (root / "ports.json").write_text(json.dumps(ports))
    for file in (*GENESIS_FILES, "config.json"):
        raw = subprocess.run(["docker", "exec", ref(args.name), "cat", f"{ENV}/{file}"],
                             capture_output=True, check=True).stdout
        (root / "genesis" / file).write_bytes(raw)
    timeline, majors, transaction = [], {}, None
    last, moved = None, time.monotonic()
    deadline = time.monotonic() + args.timeout
    while True:
        if time.monotonic() > deadline:
            raise SystemExit(f"the cluster did not finish within {args.timeout} s at {last}")
        t = tip(args.name)
        if t is None or "slot" not in t:
            if time.monotonic() - moved > args.stall_seconds:
                raise SystemExit(f"node 1 did not answer for {args.stall_seconds} s; see /work/node1.log")
            time.sleep(1)
            continue
        if last is None or t["block"] != last["block"]:
            moved = time.monotonic()
        elif time.monotonic() - moved > args.stall_seconds:
            raise SystemExit(f"no new block for {args.stall_seconds} s at {t}")
        era = t["era"].lower()
        if not timeline or timeline[-1]["era"] != era:
            timeline.append({"era": era, "epoch": t["epoch"], "slot": t["slot"], "block": t["block"],
                             "hash": t["hash"]})
            print(json.dumps(timeline[-1]), flush=True)
        if t["epoch"] not in majors:
            majors[t["epoch"]] = protocol_major(args.name)
            print(json.dumps({"epoch": t["epoch"], "era": era, "protocolMajor": majors[t["epoch"]]}), flush=True)
        if args.conway_only and era == "conway" and t["block"] >= CONWAY_ONLY_BLOCKS:
            break
        if era == "dijkstra" and transaction is None:
            txid, txin, address = submit_transaction(args.name)
            transaction = {"id": txid, "input": txin, "address": address, "submittedAt": t}
            print(json.dumps({"submitted": txid}), flush=True)
        if transaction is not None and "includedBy" not in transaction and tx_spent(args.name, transaction["address"],
                                                                                    transaction["input"]):
            transaction["includedBy"] = tip(args.name) or t
            print(json.dumps({"included": transaction["id"], "atOrBefore": transaction["includedBy"]["block"]}),
                  flush=True)
        if transaction and finished(transaction, t, args.blocks_after, args.security_param):
            break
        last = t
        time.sleep(0.5)
    reached = [e for e, *_ in plan][:1 if args.conway_only else None]
    if [x["era"] for x in timeline] != reached:
        raise SystemExit(f"eras {[x['era'] for x in timeline]} differ from the plan {reached}")
    summary = {"up": True, "referenceOnly": True, "conwayOnly": args.conway_only, "root": str(root), "ports": ports,
               "image": LEIOS_IMAGE,
               "plan": [{"era": e, "epoch": s, "protocolMajor": m} for e, s, m in plan], "eras": timeline,
               "protocolMajorByEpoch": majors, "transaction": transaction,
               "cluster": {"pools": args.pools, "epochLength": args.epoch_length, "slotLength": args.slot_length,
                           "activeSlotsCoeff": args.active_slots_coeff, "securityParam": args.security_param,
                           "networkMagic": MAGIC}}
    (root / "dijkstra.json").write_text(json.dumps(summary, indent=2))
    print(json.dumps(summary))


# ---------------------------------------------------------------- capture

def blake2b_256(data):
    return hashlib.blake2b(data, digest_size=32).hexdigest()


def split_chunk(data):
    """The `[tag, block]` items of an ImmutableDB chunk file, in order; an item cut short at the end (a chunk
    still being written) is dropped."""
    items, pos = [], 0
    while pos < len(data):
        try:
            end = cborspan.item_end(data, pos)
        except cborspan.Malformed:
            break
        items.append(data[pos:end])
        pos = end
    return items


def describe_block(raw):
    """{tag, era, blockNo, slot, headerHash, header (hex), txIds} of one `[tag, block]`. The header is the first
    element of the block and its hash the Blake2b-256 of its original bytes; a transaction id is the hash of its
    body's original bytes. Conway's block is `[header, bodies, witnesses, auxiliary, invalid]`, Dijkstra's
    `[header, [txs, leios_cert, peras_cert]]` with each transaction `[body, witnesses, auxiliary, is_valid]`
    (cardano-ledger 9df9f98c `eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/BlockBody/Internal.hs:185-189`)."""
    (tag_span, block_span) = cborspan.children(raw, 0)
    _, tag, _ = cborspan.head(raw, tag_span[0])
    era = BLOCK_TAG_ERA.get(tag)
    if era is None:
        raise ValueError(f"block tag {tag} is neither Conway nor Dijkstra")
    header = cborspan.resolve(raw, [0], block_span)[0]
    header_body = cborspan.children(raw, cborspan.children(raw, header[0])[0][0])
    block_no = cborspan.head(raw, header_body[0][0])[1]
    slot = cborspan.head(raw, header_body[1][0])[1]
    bodies = (cborspan.resolve(raw, [1, "*"], block_span) if era == "conway"
              else cborspan.resolve(raw, [1, 0, "*", 0], block_span))
    return {"tag": tag, "era": era, "blockNo": block_no, "slot": slot,
            "headerHash": blake2b_256(raw[header[0]:header[1]]), "header": raw[header[0]:header[1]].hex(),
            "txIds": [blake2b_256(raw[s:e]) for s, e in bodies]}


def cut_chain(blocks, txid, blocks_after):
    """The blocks from Origin to `blocks_after` blocks past the one holding `txid`, with that block's index."""
    described = [describe_block(b) for b in blocks]
    at = next((i for i, d in enumerate(described) if txid in d["txIds"]), None)
    if at is None:
        raise ValueError(f"transaction {txid} is not in the immutable chain yet")
    end = at + 1 + blocks_after
    if end > len(blocks):
        raise ValueError(f"only {len(blocks) - at - 1} immutable blocks follow the transaction's, {blocks_after} wanted")
    for prev, d in zip(described[:end], described[1:end]):
        if d["blockNo"] != prev["blockNo"] + 1:
            raise ValueError(f"block {d['blockNo']} follows block {prev['blockNo']}: the chain is not contiguous")
    return blocks[:end], described[:end], at


def blocks_tsv(described, blocks):
    """One line per block: era tag, block number, slot, header hash, the `[tag, block]` in hex."""
    rows = ["\t".join(("tag", "blockNo", "slot", "headerHash", "block"))]
    rows += ["\t".join((str(d["tag"]), str(d["blockNo"]), str(d["slot"]), d["headerHash"], b.hex()))
             for d, b in zip(described, blocks)]
    return "\n".join(rows) + "\n"


def headers_tsv(described, conway_before=3):
    """The headers across the boundary, original bytes: the last `conway_before` Conway headers and every
    Dijkstra one, as era tag, block number, slot, header hash, header hex. Every header is also in its block."""
    first = next(i for i, d in enumerate(described) if d["era"] == "dijkstra")
    rows = ["\t".join(("tag", "blockNo", "slot", "headerHash", "header"))]
    rows += ["\t".join((str(d["tag"]), str(d["blockNo"]), str(d["slot"]), d["headerHash"], d["header"]))
             for d in described[max(first - conway_before, 0):]]
    return "\n".join(rows) + "\n"


def capture_manifest(summary, described, tx_index, blocks_text, versions, captured_at):
    per_era = {era: sum(1 for d in described if d["era"] == era) for era in BLOCK_TAG_ERA.values()}
    first = next(d for d in described if d["era"] == "dijkstra")
    return {"capturedAt": captured_at, "referenceOnly": True, "image": LEIOS_IMAGE,
            "nodeRevision": LEIOS_NODE_REVISION, "versions": versions, "blocks": len(described),
            "blocksPerEra": per_era, "firstDijkstraBlock": {k: first[k] for k in ("blockNo", "slot", "headerHash")},
            "transaction": {"id": summary["transaction"]["id"], "blockNo": described[tx_index]["blockNo"],
                            "slot": described[tx_index]["slot"]},
            "plan": summary["plan"], "eras": summary["eras"], "protocolMajorByEpoch": summary["protocolMajorByEpoch"],
            "cluster": summary["cluster"],
            "blocksSha256": hashlib.sha256(blocks_text.encode()).hexdigest()}


def capture_dijkstra(args):
    """Save the running `up-dijkstra` chain: every immutable block of node 1 from Origin to `--blocks-after` blocks
    past the Dijkstra transaction, their headers, the transaction as the CLI signed it, and the genesis and
    configuration files the nodes read. No key file is copied."""
    root = data_root(args.name)
    summary_path = root / "dijkstra.json"
    if not summary_path.exists():
        raise SystemExit(f"{summary_path} is missing; run `devnet.py --name {args.name} up-dijkstra` first")
    summary = json.loads(summary_path.read_text())
    if summary.get("conwayOnly"):
        raise SystemExit(f"`{args.name}` was started with --conway-only: it has no Dijkstra chain to capture")
    db = f"{ENV}/node-data/node1/db/immutable"
    chunks = in_ref(args.name, f"ls {db} | grep '\\.chunk$' | sort").stdout.split()
    blocks = []
    for chunk in chunks:
        data = subprocess.run(["docker", "exec", ref(args.name), "cat", f"{db}/{chunk}"],
                              capture_output=True, check=True).stdout
        blocks += split_chunk(data)
    try:
        kept, described, at = cut_chain(blocks, summary["transaction"]["id"], args.blocks_after)
    except ValueError as error:
        raise SystemExit(f"cannot capture yet: {error}")
    out = pathlib.Path(args.out)
    (out / "genesis").mkdir(parents=True, exist_ok=True)
    text = blocks_tsv(described, kept)
    (out / "blocks.tsv").write_text(text)
    (out / "headers.tsv").write_text(headers_tsv(described))
    envelope = subprocess.run(["docker", "exec", ref(args.name), "cat", f"{TX_DIR}/dijkstra.tx"],
                              capture_output=True, check=True).stdout
    (out / "transaction.json").write_bytes(envelope)
    for file in (*GENESIS_FILES, "config.json"):
        shutil.copyfile(root / "genesis" / file, out / "genesis" / file)
    versions = {tool: in_ref(args.name, f"{tool} --version").stdout.strip().splitlines()
                for tool in ("cardano-node", "cardano-cli")}
    manifest = capture_manifest(summary, described, at, text, versions, time.strftime("%Y-%m-%d", time.gmtime()))
    (out / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print(json.dumps({"captured": len(kept), "blocksPerEra": manifest["blocksPerEra"],
                      "transaction": manifest["transaction"], "out": str(out)}))
