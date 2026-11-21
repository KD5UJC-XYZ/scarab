# SPDX-License-Identifier: Apache-2.0
"""The `leios-traffic` scenario (#73, #54): REFERENCE ONLY, a Leios-build cluster that endorses, votes and certifies.

`up-dijkstra`'s cluster never announces an endorser block (EB): its pools have no BLS key, and its load never fills
the ranking block (RB). This scenario starts the same cluster (`dijkstra.make_network`, `dijkstra.start_nodes`) and
adds what the reference needs for each step of Linear Leios, then judges the reference nodes' chains. Scarab runs no
node here; the run is the private judge its Leios follower and forger will be checked against.

- **BLS keys.** Each pool makes a BLS key pair (`cardano-cli dijkstra node key-gen-BLS`) and its node is started with
  the signing key (`--shelley-bls-key`, cardano-node b004fe0c `Cardano/Node/Protocol/Shelley.hs:190-192`). Once the
  chain is in Dijkstra, one transaction re-registers every pool with a Dijkstra registration certificate built from
  that key (`stake-pool registration-certificate --bls-signing-key-file`, as cardano-node's
  `LeiosStakePoolRegistration` test does, lines 106-152). A re-registration is a future parameter set, made active by
  POOLREAP at the next boundary, before SNAP (cardano-ledger 9df9f98c `Dijkstra/Rules/Pool.hs:292-303`,
  `Dijkstra/Rules/PoolReap.hs:122-140`, `Dijkstra/Rules/Epoch.hs:169-171, 234-239`); SNAP seats the committee in the fresh mark snapshot, which governs the
  epoch after (`Dijkstra/Rules/Snap.hs:124-146`). So the committee holds the keys two boundaries after the
  registration's epoch (`committee_epoch`), and the load starts then.
- **Overflow.** A forger puts the oldest mempool transactions that fit the RB in the RB and announces an EB for the
  ones after them that fit the EB (ouroboros-consensus 2416493b `NodeKernel/Forge.hs:784-801`,
  `Mempool/API.hs:455-466`), and announces none when that second part is empty (`Shelley/Ledger/Forge.hs:171-177`).
  The RB holds `maxBlockBodySize - 1024` bytes of transactions (`Shelley/Ledger/Mempool.hs:207-208, 433-443`), with
  the block's execution units and reference script bytes as further limits (lines 641-650, 759-771); simple
  transactions meet only the byte limit. The load is transactions padded with metadata to about 14 KB, built
  offline from chained fan-outs. Whenever the mempool holds less than `--overflow` (1.5) RBs, a wave tops it up to
  that (`wave_size`), until a node's log shows a certifying block forged.
- **Diffusion.** A node offers an EB onward only when its slot is `LeiosMinOfferLead` or more past the node's
  immutable tip (`LeiosDemoLogic.hs:2353-2363`). The testnet's 1,200 slots exceed this cluster's immutable lag of
  about k/f slots, so no EB would leave its forger (cardano-node `Cardano/Node/Configuration/Leios.hs:45-57`); the
  cluster sets a small lead, as consensus' own Leios tests do (`Test/Consensus/Leios/NodeUnderTest.hs:115`).
- **Timing.** A vote opens three announcement periods after the announcing slot and closes a vote period later, in
  wall-clock time (`LeiosDemoTypes.hs:2819-2826`; `LeiosVoting.hs:174-212`), and the voter's selection must still
  end in the announcing RB (`LeiosVoting.hs:338-341`). Only the RB's child can certify, and only when it is at least
  `3 announcement + vote + diffusion` later (`NodeKernel/Forge.hs:345-370`, `LeiosDemoTypes.hs:2834-2852`). The
  cluster keeps the testnet's proportions: its Leios periods are the testnet's scaled by the slot length
  (`dijkstra_spec`), so the certification gap is the testnet's 14 slots, and its f is the testnet's 0.05, so an
  announcing RB's child comes late enough about half the time (`certifiable_share`).
- **Quorum.** Seats go to the largest pools, weighted by their share of the active stake, and a certificate needs
  `leiosQuorumStakeThreshold` (0.75) of it (cardano-ledger `State/LeiosCommittee.hs:58-93`; ouroboros-consensus
  `LeiosVoteState.hs:62`). Five equal pools reach it with any four.

The judge (`judge`) reads each node's ImmutableDB after the nodes stop, and requires on the chain every node holds:
an RB that announces an EB, and a certifying RB whose parent is the announcing RB; every node applied that block
(it is in its immutable chain), and every node's LeiosDB holds the certified EB's closure. The votes come from the
nodes' logs (`LeiosVoted`) and from the certificate's signer bits. Adaptive endorsements stay on as on the testnet:
while every seat votes the margin is kept and the EB capacity is not scaled
(`LeiosAdaptiveEndorsements.hs:129-152, 252-279`).
"""
import json
import math
import pathlib
import sqlite3
import subprocess
import tempfile
import time
from .. import cborspan
from ..dijkstra import (BLOCK_TAG_ERA, IMAGE_CONFIG, LEIOS_IMAGE, blake2b_256, dijkstra_plan, make_network, socket,
                        split_chunk, start_nodes, start_reference, tip, tx_spent)
from ..load import submit_all_script, submit_results
from ..util import ENV, MAGIC, data_root, in_ref, put_file, read_json, ref

CLI = "cardano-cli dijkstra"
LEIOS_PERIODS = ("leiosAnnouncementPeriodLength", "leiosVotePeriodLength", "leiosDiffusionPeriodLength")
LOAD = "/work/leios-load"
TX = "/work/tx"
# the fixed part of a block body the RB capacity leaves out (`fixedBlockBodyOverhead`, Mempool.hs:207-208)
FIXED_BLOCK_BODY_OVERHEAD = 1024
# metadata strings of 64 bytes, the most a transaction metadatum string holds; each costs 66 bytes
METADATUM = "leios-traffic padding for one ranking block overflow, 64 bytes.."
FAN_OUT_LOVELACE = 5_000_000
FAN_OUT_PER_TX = 200
# KIND names of the traces the judge counts (LeiosDemoTypes.hs:2037-2083, 2135)
TRACE_KINDS = ("LeiosBlockForged", "LeiosBlockAnnounced", "LeiosVoted", "LeiosVoteAcquired", "LeiosCertified",
               "LeiosBlockCertified", "LeiosCertifiedAndAnnounced", "LeiosNotVoted", "LeiosInvalidCertificate")


def dijkstra_spec(image_dijkstra, image_slot_length, slot_length):
    """The `--spec-dijkstra` input: the testnet's Dijkstra genesis with its Leios periods (milliseconds) scaled by
    the ratio of the cluster's slot length to the testnet's, so that every period is the same number of slots."""
    scale = slot_length / image_slot_length
    return {**image_dijkstra, **{k: round(image_dijkstra[k] * scale) for k in LEIOS_PERIODS}}


def certification_gap(spec, slot_length):
    """Slots from an announcement to the first block that may certify it: `3 announcement + vote + diffusion`,
    rounded up to whole slots (`certificationGapOfPeriods`, LeiosDemoTypes.hs:2843-2852)."""
    announcement, vote, diffusion = (spec[k] for k in LEIOS_PERIODS)
    slot_ms = round(slot_length * 1000)
    return -(-(3 * announcement + vote + diffusion) // slot_ms)


def certifiable_share(active_slots_coeff, gap):
    """The chance that an RB's child comes `gap` or more slots after it: no leader in the `gap - 1` slots between.
    With all stake active, a slot has a leader with probability f."""
    return (1 - active_slots_coeff) ** (gap - 1)


def committee_epoch(included_epoch):
    """The first epoch whose committee holds a key registered by a certificate in `included_epoch`: the future
    parameters become active at the next boundary, where SNAP seats them in the mark snapshot, which becomes the
    set snapshot (the epoch's committee) at the boundary after (Snap.hs:148-152)."""
    return included_epoch + 2


def rb_capacity(protocol_parameters):
    """Bytes of transactions one RB holds (`txsMaxBytes`, Mempool.hs:433-443)."""
    return protocol_parameters["maxBlockBodySize"] - FIXED_BLOCK_BODY_OVERHEAD


def padding_metadata(tx_bytes):
    """Transaction metadata under label 674 that brings a one-input, one-output transaction to about `tx_bytes`."""
    count = max(1, (tx_bytes - 400) // (len(METADATUM) + 2))
    return {"674": {"msg": [METADATUM] * count}}


def wave_size(mempool_bytes, target_bytes, tx_bytes, left):
    """How many load transactions to submit so that the mempool holds `target_bytes`, at most `left`."""
    if mempool_bytes >= target_bytes:
        return 0
    return min(left, -(-(target_bytes - mempool_bytes) // tx_bytes))


def bls_keygen_command(pool):
    p = f"{ENV}/pools-keys/pool{pool}"
    return f"{CLI} node key-gen-BLS --verification-key-file {p}/bls.vkey --signing-key-file {p}/bls.skey"


def registration_certificate_command(pool, cost, margin):
    """The Dijkstra re-registration of genesis pool `pool` with its own cold, VRF and reward keys, the protocol's
    minimum cost and margin, and the proof of possession the CLI derives from its BLS signing key."""
    p = f"{ENV}/pools-keys/pool{pool}"
    return (f"{CLI} stake-pool registration-certificate --testnet-magic {MAGIC} --pool-pledge 0 "
            f"--pool-cost {cost} --pool-margin {margin} --cold-verification-key-file {p}/cold.vkey "
            f"--vrf-verification-key-file {p}/vrf.vkey --pool-reward-account-verification-key-file "
            f"{p}/staking-reward.vkey --pool-owner-stake-verification-key-file {p}/staking-reward.vkey "
            f"--bls-signing-key-file {p}/bls.skey --out-file {TX}/pool{pool}.cert")


def registration_tx_command(pools, txin, address):
    """One transaction with every pool's re-registration, witnessed by the funding key and each pool's cold and
    reward keys; prints its id. Its only output is the change, `<id>#0`."""
    net = f"--testnet-magic {MAGIC} --socket-path {socket()}"
    certs = " ".join(f"--certificate-file {TX}/pool{n}.cert" for n in range(1, pools + 1))
    keys = " ".join(f"--signing-key-file {ENV}/pools-keys/pool{n}/{k}.skey"
                    for n in range(1, pools + 1) for k in ("cold", "staking-reward"))
    return (f"{CLI} transaction build {net} --tx-in {txin} --change-address {address} {certs} "
            f"--witness-override {1 + 2 * pools} --out-file {TX}/register.body && "
            f"{CLI} transaction sign --tx-body-file {TX}/register.body --signing-key-file "
            f"{ENV}/utxo-keys/utxo1/utxo.skey {keys} --testnet-magic {MAGIC} --out-file {TX}/register.tx && "
            f"{CLI} transaction submit {net} --tx-file {TX}/register.tx && "
            f"{CLI} transaction txid --output-text --tx-file {TX}/register.tx")


def fan_out_plan(total, per_tx):
    """The output counts of the chained fan-out transactions that fund `total` load transactions, at most `per_tx`
    each (a fan-out of 200 outputs stays inside the 16,384-byte `maxTxSize`)."""
    return [min(per_tx, total - start) for start in range(0, total, per_tx)]


def fan_out_command(index, txin, value, address, outputs, fee):
    """Build offline and sign fan-out `index`: `outputs` outputs of `FAN_OUT_LOVELACE`, then the change, from `txin`
    holding `value`; prints its id."""
    change = value - outputs * FAN_OUT_LOVELACE - fee
    if change < FAN_OUT_LOVELACE:
        raise ValueError(f"fan-out {index}: {value} lovelace cannot fund {outputs} outputs and the fee")
    body, signed = f"{TX}/fan{index}.body", f"{TX}/fan{index}.tx"
    outs = " ".join(f"--tx-out {address}+{FAN_OUT_LOVELACE}" for _ in range(outputs))
    return (f"{CLI} transaction build-raw --tx-in {txin} {outs} --tx-out {address}+{change} --fee {fee} "
            f"--out-file {body} && {CLI} transaction sign --tx-body-file {body} --signing-key-file "
            f"{ENV}/utxo-keys/utxo1/utxo.skey --testnet-magic {MAGIC} --out-file {signed} && "
            f"{CLI} transaction txid --output-text --tx-file {signed}"), change


def load_txs_script(address, fee):
    """A shell script that builds offline and signs one load transaction per line `<index> <txin>` of
    `LOAD/inputs`: `LOAD/t<index>.tx` spends the fan-out output `txin`."""
    return (f"while read i txin; do {CLI} transaction build-raw --tx-in $txin "
            f"--tx-out {address}+{FAN_OUT_LOVELACE - fee} --fee {fee} --metadata-json-file {LOAD}/meta.json "
            f"--out-file {LOAD}/t$i.body && {CLI} transaction sign --tx-body-file {LOAD}/t$i.body "
            f"--signing-key-file {ENV}/utxo-keys/utxo1/utxo.skey --testnet-magic {MAGIC} --out-file {LOAD}/t$i.tx "
            f"|| exit 1; done < {LOAD}/inputs")


# ---------------------------------------------------------------- the chain

def _simple(raw, span):
    """The value of a CBOR simple item: True, False or None (null)."""
    major, value, _ = cborspan.head(raw, span[0])
    if major != 7 or value not in (20, 21, 22):
        raise cborspan.Malformed(f"not a boolean or null at {span[0]}")
    return {20: False, 21: True, 22: None}[value]


def _is_null(raw, span):
    major, value, _ = cborspan.head(raw, span[0])
    return major == 7 and value == 22


def describe_leios_block(raw):
    """{era, blockNo, slot, hash, prevHash, announces, ebSize, certifies, signers} of one `[tag, block]` of the
    ImmutableDB. A Dijkstra header body has 12 fields, the last two `block_body_contains_leios_cert` and
    `eb_references_announcement = [eb_hash, eb_size] / nil`; its block body is
    `[transactions, leios_certificate / nil, peras_certificate / nil]` with `leios_certificate = [signers, signature]`
    (cardano-ledger 9df9f98c `eras/dijkstra/impl/cddl/data/dijkstra.cddl:59-117, 940-945`). `signers` counts the
    certificate's set signer bits."""
    tag_span, block_span = cborspan.children(raw, 0)
    tag = cborspan.head(raw, tag_span[0])[1]
    era = BLOCK_TAG_ERA.get(tag)
    if era is None:
        raise ValueError(f"block tag {tag} is neither Conway nor Dijkstra")
    header, body = cborspan.children(raw, block_span[0])[:2]
    header_body = cborspan.children(raw, cborspan.children(raw, header[0])[0][0])
    prev = None if _is_null(raw, header_body[2]) else cborspan.payload(raw, header_body[2]).hex()
    described = {"era": era, "blockNo": cborspan.head(raw, header_body[0][0])[1],
                 "slot": cborspan.head(raw, header_body[1][0])[1], "hash": blake2b_256(raw[header[0]:header[1]]),
                 "prevHash": prev, "announces": None, "ebSize": None, "certifies": False, "signers": None}
    if era != "dijkstra":
        return described
    if len(header_body) != 12:
        raise ValueError(f"a Dijkstra header body of {len(header_body)} fields, not 12")
    claims_cert = _simple(raw, header_body[10])
    if not _is_null(raw, header_body[11]):
        eb_hash, eb_size = cborspan.children(raw, header_body[11][0])
        described["announces"] = cborspan.payload(raw, eb_hash).hex()
        described["ebSize"] = cborspan.head(raw, eb_size[0])[1]
    cert = cborspan.children(raw, body[0])[1]
    has_cert = not _is_null(raw, cert)
    if claims_cert != has_cert:
        raise ValueError(f"block {described['blockNo']}: the header says leios cert {claims_cert}, the body {has_cert}")
    if has_cert:
        signers = cborspan.payload(raw, cborspan.children(raw, cert[0])[0])
        described["certifies"] = True
        described["signers"] = sum(bin(b).count("1") for b in signers)
    return described


def judge(chains, closures=None, kinds=None, certificates=1):
    """The verdict on the nodes' immutable chains ({node: [describe_leios_block]}), the EB closures each node's
    LeiosDB holds ({node: set of EB hashes}) and the trace counts of each node's log ({node: {kind: n}}).

    It passes when the chains agree (each is a prefix of the longest), and the part every node holds has an RB that
    announces an EB and at least `certificates` certifying RBs whose parent is an announcing RB; with `closures`,
    every node holds each certified EB's closure; with `kinds`, some node voted."""
    problems = []
    order = sorted(chains)
    longest = max((chains[n] for n in order), key=len, default=[])
    for n in order:
        if [b["hash"] for b in chains[n]] != [b["hash"] for b in longest[:len(chains[n])]]:
            problems.append(f"node {n}'s immutable chain is not a prefix of the longest")
    common = longest[:min((len(chains[n]) for n in order), default=0)]
    by_hash = {b["hash"]: b for b in common}
    announcing = [b for b in common if b["announces"]]
    certifying = [b for b in common if b["certifies"]]
    certified = []
    for b in certifying:
        parent = by_hash.get(b["prevHash"])
        if parent is None or not parent["announces"]:
            problems.append(f"certifying block {b['blockNo']} does not extend an announcing block")
            continue
        certified.append({"certifyingBlock": b["blockNo"], "slot": b["slot"], "hash": b["hash"],
                          "announcingBlock": parent["blockNo"], "announcingSlot": parent["slot"],
                          "gapSlots": b["slot"] - parent["slot"], "eb": parent["announces"],
                          "ebSize": parent["ebSize"], "signers": b["signers"]})
    if not announcing:
        problems.append("no block every node holds announces an endorser block")
    if not certified:
        problems.append("no block every node holds certifies an endorser block")
    elif len(certified) < certificates:
        problems.append(f"{len(certified)} blocks every node holds certify an endorser block, fewer than the "
                        f"{certificates} asked for")
    if closures is not None:
        for c in certified:
            missing = [n for n in order if c["eb"] not in closures.get(n, set())]
            if missing:
                problems.append(f"the closure of certified EB {c['eb'][:16]} is not in the LeiosDB of nodes {missing}")
    votes = None
    if kinds is not None:
        votes = sum(k.get("LeiosVoted", 0) for k in kinds.values())
        if not votes:
            problems.append("no node voted")
    dijkstra = [b for b in common if b["era"] == "dijkstra"]
    return {"pass": not problems, "problems": problems, "commonBlocks": len(common),
            "dijkstraBlocks": len(dijkstra), "announcingBlocks": len(announcing),
            "certifyingBlocks": len(certified), "certified": certified, "votesCast": votes,
            "certificateSigners": sum(c["signers"] for c in certified)}


def trace_kinds(lines):
    """{kind: count} of the Leios traces among the node's JSON log lines (`data.kind`), and the reasons of
    `LeiosNotVoted`; lines that are not JSON objects are skipped."""
    kinds, reasons = {}, {}
    for line in lines:
        try:
            data = json.loads(line).get("data", {})
        except (json.JSONDecodeError, AttributeError):
            continue
        kind = data.get("kind") if isinstance(data, dict) else None
        if kind in TRACE_KINDS:
            kinds[kind] = kinds.get(kind, 0) + 1
            if kind == "LeiosNotVoted":
                reason = str(data.get("reason"))
                reasons[reason] = reasons.get(reason, 0) + 1
    return kinds, reasons


# one pass over every node's log for the kinds that show progress, as `uniq -c` lines
KIND_COUNT_COMMAND = ("grep -ho '\"kind\":\"Leios\\(BlockForged\\|BlockCertified\\|Voted\\)\"' /work/node*.log "
                      "| sort | uniq -c")


def kind_counts(text):
    """{kind: count} from the `uniq -c` lines of `KIND_COUNT_COMMAND` (`   3 "kind":"LeiosVoted"`)."""
    counts = {}
    for line in text.splitlines():
        count, _, kind = line.strip().partition(" ")
        if count.isdigit() and kind.startswith('"kind":"'):
            counts[kind[len('"kind":"'):-1]] = int(count)
    return counts


def immutable_chain(name, node):
    """The described blocks of node `node`'s ImmutableDB, in order."""
    db = f"{ENV}/node-data/node{node}/db/immutable"
    blocks = []
    for chunk in in_ref(name, f"ls {db} | grep '\\.chunk$' | sort").stdout.split():
        data = subprocess.run(["docker", "exec", ref(name), "cat", f"{db}/{chunk}"],
                              capture_output=True, check=True).stdout
        blocks += [describe_leios_block(b) for b in split_chunk(data)]
    return blocks


def leios_closures(name, node, into):
    """The EB hashes whose closure node `node`'s LeiosDB holds, in its volatile or immutable partition. The files
    are copied into `into` with their write-ahead logs and read with Python's sqlite3."""
    db = f"{ENV}/node-data/node{node}/db"
    hashes = set()
    for part in ("leios.vol.db", "leios.imm.db"):
        target = into / f"node{node}"
        target.mkdir(parents=True, exist_ok=True)
        for suffix in ("", "-wal", "-shm"):
            r = subprocess.run(["docker", "exec", ref(name), "cat", f"{db}/{part}{suffix}"], capture_output=True)
            if r.returncode == 0:
                (target / f"{part}{suffix}").write_bytes(r.stdout)
        if (target / part).exists():
            with sqlite3.connect(target / part) as connection:
                hashes |= {bytes(h).hex() for (h,) in connection.execute("SELECT ebHashBytes FROM ebClosures")}
    return hashes


def stop_nodes(name, wait=60):
    """Interrupt every node by its process id and wait for them to exit, so their databases are closed."""
    pids = in_ref(name, "pidof cardano-node", check=False).stdout.split()
    if pids:
        in_ref(name, f"kill -INT {' '.join(pids)}", check=False)
    deadline = time.monotonic() + wait
    while in_ref(name, "pidof cardano-node", check=False).stdout.split():
        if time.monotonic() > deadline:
            raise SystemExit(f"the nodes did not stop within {wait} s")
        time.sleep(1)


# ---------------------------------------------------------------- the run

def _json(name, command, timeout=60):
    return json.loads(in_ref(name, command, timeout=timeout).stdout)


def leios_traffic(args):
    started = time.monotonic()
    root = data_root(args.name)
    if root.exists():
        raise SystemExit(f"{root} exists; run `devnet.py down` first")
    dijkstra_plan(args.dijkstra_epoch, args.genesis_protocol_major)
    (root / "genesis").mkdir(parents=True)
    start_reference(args.name, cpus=args.cpus, memory=args.memory, work=args.work_size)
    try:
        _drive(args, root, started)
    finally:
        _remove_reference(args.name, root, args.pools)


def _remove_reference(name, root, pools):
    """The reference container this run started, removed on every exit: its nodes, its tmpfs (databases and the
    run's throwaway keys) go with it. The nodes' logs are kept in the run directory first, for a failed run's
    diagnosis; `leios.json` is already there."""
    for n in range(1, pools + 1):
        log = subprocess.run(["docker", "exec", ref(name), "cat", f"/work/node{n}.log"], capture_output=True)
        if log.returncode == 0:
            (root / f"node{n}.log").write_bytes(log.stdout)
    subprocess.run(["docker", "rm", "-f", ref(name)], capture_output=True)


def _drive(args, root, started):
    log = lambda m: print(json.dumps(m), flush=True)  # noqa: E731
    image_slot = read_json(args.name, f"{IMAGE_CONFIG}/shelley-genesis.json")["slotLength"]
    spec = dijkstra_spec(read_json(args.name, f"{IMAGE_CONFIG}/dijkstra-genesis.json"), image_slot, args.slot_length)
    gap = certification_gap(spec, args.slot_length)
    log({"leiosPeriodsMs": {k: spec[k] for k in LEIOS_PERIODS}, "certificationGapSlots": gap,
         "certifiableShare": round(certifiable_share(args.active_slots_coeff, gap), 3)})
    in_ref(args.name, f"mkdir -p {ENV}-spec {TX} {LOAD}")
    put_file(args.name, f"{ENV}-spec/dijkstra.json", json.dumps(spec, indent=2))
    make_network(args, spec_dijkstra=f"{ENV}-spec/dijkstra.json",
                 config_extra={"LeiosMinOfferLead": args.min_offer_lead})
    for n in range(1, args.pools + 1):
        in_ref(args.name, bls_keygen_command(n))
    ports = start_nodes(args, root, bls_key=lambda n: f"{ENV}/pools-keys/pool{n}/bls.skey")

    deadline = started + args.timeout
    net = f"--testnet-magic {MAGIC} --socket-path {socket()}"

    def wait_for(what, done, poll=1.0):
        while True:
            t = tip(args.name)
            if t is not None and "epoch" in t:
                result = done(t)
                if result:
                    return result
            if time.monotonic() > deadline:
                raise SystemExit(f"{what} did not happen within {args.timeout} s (tip {t})")
            time.sleep(poll)

    entered = wait_for("Dijkstra", lambda t: t if t.get("era", "").lower() == "dijkstra" else None)
    log({"dijkstra": entered})
    pparams = _json(args.name, f"{CLI} query protocol-parameters {net}")
    capacity = rb_capacity(pparams)
    for n in range(1, args.pools + 1):
        in_ref(args.name, registration_certificate_command(n, pparams["minPoolCost"], spec["minPoolMargin"]))
    address = in_ref(args.name, f"{CLI} address build --payment-verification-key-file "
                                f"{ENV}/utxo-keys/utxo1/utxo.vkey --testnet-magic {MAGIC}").stdout.strip()
    utxo = _json(args.name, f"{CLI} query utxo --address {address} {net} --output-json")
    txin = max(utxo, key=lambda i: utxo[i]["value"]["lovelace"])
    r = in_ref(args.name, registration_tx_command(args.pools, txin, address), check=False, timeout=120)
    if r.returncode != 0:
        raise SystemExit(f"the BLS registrations were refused: {(r.stderr or r.stdout).strip()[:800]}")
    register_txid = r.stdout.strip().splitlines()[-1]
    included = wait_for("the BLS registrations' inclusion", lambda t: t if tx_spent(args.name, address, txin) else None)
    seated = committee_epoch(included["epoch"])
    pools_state = in_ref(args.name, f"{CLI} query pool-state --all-stake-pools {net} --output-json", check=False)
    log({"registered": register_txid, "includedByEpoch": included["epoch"], "committeeEpoch": seated,
         "poolState": (pools_state.stdout or pools_state.stderr).strip()[:3000]})

    # the load: chained fan-outs from the registration's change, then one padded transaction per fan-out output
    fee = pparams["txFeePerByte"] * pparams["maxTxSize"] + pparams["txFeeFixed"]
    utxo = _json(args.name, f"{CLI} query utxo --address {address} {net} --output-json")
    txin, value = f"{register_txid}#0", utxo[f"{register_txid}#0"]["value"]["lovelace"]
    fans, inputs = [], []
    for k, outputs in enumerate(fan_out_plan(args.load_txs, FAN_OUT_PER_TX)):
        command, value = fan_out_command(k, txin, value, address, outputs, fee)
        fan_txid = in_ref(args.name, command).stdout.strip().splitlines()[-1]
        fans.append(f"{TX}/fan{k}.tx")
        inputs += [f"{fan_txid}#{i}" for i in range(outputs)]
        txin = f"{fan_txid}#{outputs}"
    ok, failed = submit_results(in_ref(args.name, submit_all_script(fans, parallelism=1, cli=CLI), timeout=120,
                                       check=False).stdout)
    if failed:
        raise SystemExit(f"the fan-out transactions were refused: {failed}")
    put_file(args.name, f"{LOAD}/meta.json", json.dumps(padding_metadata(args.load_tx_bytes)))
    put_file(args.name, f"{LOAD}/inputs", "".join(f"{i} {txin_i}\n" for i, txin_i in enumerate(inputs)))
    in_ref(args.name, load_txs_script(address, fee), timeout=60 + args.load_txs)
    wait_for("the fan-outs' inclusion", lambda t: t if txin in _json(
        args.name, f"{CLI} query utxo --address {address} {net} --output-json") else None)
    tx_bytes = len(bytes.fromhex(json.loads(in_ref(args.name, f"cat {LOAD}/t0.tx").stdout)["cborHex"]))
    if tx_bytes > pparams["maxTxSize"]:
        raise SystemExit(f"a load transaction of {tx_bytes} bytes exceeds maxTxSize {pparams['maxTxSize']}")
    target = math.ceil(capacity * args.overflow)
    log({"loadTxs": args.load_txs, "loadTxBytes": tx_bytes, "rbCapacityBytes": capacity, "mempoolTargetBytes": target})

    wait_for("the committee's epoch", lambda t: t if t["epoch"] >= seated else None)
    committee = in_ref(args.name, f"{CLI} query leios-committee {net}", check=False)
    log({"committeeSeated": seated, "leiosCommittee": (committee.stdout or committee.stderr).strip()[:2000]})

    # The load runs until a node's log says it forged a certifying block; reading the chains is too slow to do
    # between waves (a full ImmutableDB of padded blocks takes seconds per node in Python). The judge reads them
    # once the certifying block is `k + 2` blocks deep on every node and the nodes have stopped.
    left = list(range(args.load_txs))
    submitted, rejected, last_check, counts = 0, 0, time.monotonic(), {}
    # what the load did since the last look at the logs: passes, waves, the mempool sizes it saw
    window = {"passes": 0, "waves": 0, "mempoolMin": None, "mempoolMax": 0}
    while time.monotonic() < deadline and left:
        size = _json(args.name, f"{CLI} query tx-mempool info {net}")["sizeInBytes"]
        window.update(passes=window["passes"] + 1, mempoolMax=max(window["mempoolMax"], size),
                      mempoolMin=size if window["mempoolMin"] is None else min(window["mempoolMin"], size))
        wave = wave_size(size, target, tx_bytes, len(left))
        if wave:
            files = [f"{LOAD}/t{i}.tx" for i in left[:wave]]
            left = left[wave:]
            ok, failed = submit_results(in_ref(args.name, submit_all_script(files, cli=CLI), timeout=120,
                                               check=False).stdout)
            window["waves"] += 1
            submitted, rejected = submitted + len(ok), rejected + len(failed)
            for f, error in failed.items():
                log({"rejected": f, "error": error})
        if time.monotonic() - last_check > args.check_seconds:
            counts = kind_counts(in_ref(args.name, KIND_COUNT_COMMAND, check=False).stdout)
            log({"submitted": submitted, "left": len(left), **window, "traces": counts})
            window = {"passes": 0, "waves": 0, "mempoolMin": None, "mempoolMax": 0}
            last_check = time.monotonic()
            if counts.get("LeiosBlockCertified", 0) >= args.certificates:
                break
        time.sleep(0.5)
    deep = max(t["block"] for t in (tip(args.name, n) for n in ports) if t) + args.security_param + 2
    log({"loadStopped": submitted, "certifyingBlocksForged": counts.get("LeiosBlockCertified", 0),
         "untilBlock": deep})
    for n in ports:
        def reached(_, n=n):
            t = tip(args.name, n)
            return t if t is not None and t.get("block", 0) >= deep else None
        wait_for(f"node {n}'s block {deep}", reached)

    stop_nodes(args.name)
    chains = {n: immutable_chain(args.name, n) for n in ports}
    with tempfile.TemporaryDirectory(dir=root) as scratch:
        closures = {n: leios_closures(args.name, n, pathlib.Path(scratch)) for n in ports}
    kinds, reasons = {}, {}
    for n in ports:
        lines = subprocess.run(["docker", "exec", ref(args.name), "cat", f"/work/node{n}.log"],
                               capture_output=True, text=True, check=True).stdout.splitlines()
        kinds[n], reasons[n] = trace_kinds(lines)
    verdict = judge(chains, closures, kinds, certificates=args.certificates)
    summary = {"referenceOnly": True, "image": LEIOS_IMAGE, "seconds": round(time.monotonic() - started),
               "verdict": verdict, "traces": kinds, "notVotedReasons": reasons,
               "loadSubmitted": submitted, "loadRejected": rejected,
               "ebClosuresPerNode": {n: len(c) for n, c in closures.items()},
               "cluster": {"pools": args.pools, "epochLength": args.epoch_length, "slotLength": args.slot_length,
                           "activeSlotsCoeff": args.active_slots_coeff, "securityParam": args.security_param,
                           "dijkstraEpoch": args.dijkstra_epoch, "committeeEpoch": seated,
                           "leiosPeriodsMs": {k: spec[k] for k in LEIOS_PERIODS}, "certificationGapSlots": gap,
                           "leiosMinOfferLead": args.min_offer_lead, "rbCapacityBytes": capacity,
                           "loadTxBytes": tx_bytes, "mempoolTargetBytes": target}}
    (root / "leios.json").write_text(json.dumps(summary, indent=2))
    print(json.dumps(summary), flush=True)
    if not verdict["pass"]:
        raise SystemExit(f"leios-traffic failed: {verdict['problems']}")
