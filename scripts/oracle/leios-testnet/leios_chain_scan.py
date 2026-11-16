# SPDX-License-Identifier: Apache-2.0
"""The Plutus transactions of a synced Leios testnet chain, the outputs they spend, and the requests
of their scripts (#54).

Reads the database a node of the testnet's build (release rc-2026w44, cardano-node b004fe0c) wrote
while following the public Leios testnet: the ImmutableDB chunks and VolatileDB files hold the
ranking blocks (each `[tag, block]`, tag 8 a Dijkstra block), and the LeiosDB partitions
(`leios.imm.db`, `leios.vol.db`, SQLite) the endorser blocks and their closures. For a block that
carries a Leios certificate the ledger applies, before the block's own transactions, the
transactions of the endorser block its predecessor announced, in the order the endorser block lists
them (`resolveLeiosClosure` and `applyLeiosClosure`, ouroboros-consensus 2416493b; the oracle README
cites the lines). A closure transaction is the block-body encoding `[body, witnesses, auxiliary data,
is_valid]` (`serializeTxForLeios`), as a ranking block's is. Only the Python standard library is
used.

    python3 -I leios_chain_scan.py scan <db> --tip <hash> --out <dir>
    python3 -I leios_chain_scan.py sample --scan <dir> --out <dir> [--size 200]
    python3 -I leios_chain_scan.py cases <db> --tip <hash> --out <dir> --params <json> --genesis <json>
    python3 -I leios_chain_scan.py requests --cases <cases.json> --results <results.json> --out <dir>
    python3 -I leios_chain_scan.py verdicts --out <dir> --evidence <dir>

`scan` reads the selected chain from its first block to the tip named (the node's last selected tip)
and writes `summary.json` (the blocks, transactions, endorser blocks and proposals read, the tip)
and `plutus.jsonl`, one line per transaction with a redeemer in its own witness set or a
sub-transaction's. `sample` takes a stratified sample of those (`sample.jsonl`). `cases` reads the
chain again for the sampled transactions' bytes and the outputs they spend, reference and pledge as
collateral, and writes `cases.json`, the input of the chain context oracle (`chain/Main.hs`), with
the chain's protocol version, cost models, system start and slot length. `requests` turns the
contexts that oracle collected into requests of the plutus 1.71.0.0 generated-program oracle, and
`verdicts` runs that oracle on them.
"""
import argparse
import functools
import hashlib
import json
import multiprocessing
import os
import pathlib
import sqlite3
import sys

HERE = os.path.dirname(os.path.abspath(__file__))

# ---------------------------------------------------------------- CBOR (navigation with offsets)


def head(b, i):
    ib = b[i]
    major, ai = ib >> 5, ib & 31
    i += 1
    if ai < 24:
        return major, ai, i
    width = {24: 1, 25: 2, 26: 4, 27: 8}.get(ai)
    if width is None:
        if ai == 31:
            return major, None, i
        raise ValueError(f"reserved additional information {ai} at {i - 1}")
    return major, int.from_bytes(b[i:i + width], "big"), i + width


def skip(b, i):
    """The end of the item at `i`, without recursion: `open` holds, for each container being read,
    the count of its items still to read (None for an indefinite one, which ends at its break)."""
    open = [1]

    def finished():
        while open and open[-1] is not None:
            open[-1] -= 1
            if open[-1] > 0:
                return
            open.pop()

    while open:
        if open[-1] is None and b[i] == 0xFF:
            i += 1
            open.pop()
            finished()
            continue
        major, n, i = head(b, i)
        if major == 6:
            continue  # the tagged item stands in for its tag
        if major in (2, 3):
            if n is None:
                while b[i] != 0xFF:
                    _, m, i = head(b, i)
                    i += m
                i += 1
            else:
                i += n
            finished()
        elif major in (4, 5):
            count = None if n is None else n * (major - 3)
            if count == 0:
                finished()
            else:
                open.append(count)
        else:
            finished()
    return i


def untag(b, i, tags=(258,)):
    major, n, j = head(b, i)
    while major == 6 and n in tags:
        i = j
        major, n, j = head(b, i)
    return i


def items(b, i):
    """Spans of the elements of the array at `i` (a set's tag 258 is stepped over)."""
    i = untag(b, i)
    major, n, i = head(b, i)
    if major != 4:
        raise ValueError(f"not an array at {i}")
    out = []
    while (n is None and b[i] != 0xFF) or (n is not None and len(out) < n):
        j = skip(b, i)
        out.append((i, j))
        i = j
    return out


def entries(b, i):
    """Spans of the keys and values of the map at `i`."""
    i = untag(b, i, (259,))
    major, n, i = head(b, i)
    if major != 5:
        raise ValueError(f"not a map at {i}")
    out = []
    while (n is None and b[i] != 0xFF) or (n is not None and len(out) < n):
        k = skip(b, i)
        v = skip(b, k)
        out.append(((i, k), (k, v)))
        i = v
    return out


def value(b, i):
    """A small item as a Python value: integers, byte and text strings, arrays, maps (as pair
    lists), tags (as pairs), simple values."""
    major, n, j = head(b, i)
    if major == 0:
        return n
    if major == 1:
        return -1 - n
    if major in (2, 3):
        if n is None:
            parts, k = [], j
            while b[k] != 0xFF:
                _, m, k = head(b, k)
                parts.append(bytes(b[k:k + m]))
                k += m
            raw = b"".join(parts)
        else:
            raw = bytes(b[j:j + n])
        return raw if major == 2 else raw.decode()
    if major == 4:
        return [value(b, s) for s, _ in items(b, i)]
    if major == 5:
        return [(value(b, ks), value(b, vs)) for (ks, _), (vs, _) in entries(b, i)]
    if major == 6:
        return ("tag", n, value(b, j))
    return {20: False, 21: True, 22: None}.get(n, ("simple", n))


def jsonl(path):
    with open(path) as f:
        return [json.loads(line) for line in f]


def read_json(path):
    with open(path) as f:
        return json.load(f)


def blake2b(raw, size=32):
    return hashlib.blake2b(raw, digest_size=size).digest()


def field(b, span, key):
    """The span of a key's value in the map at `span`, or None."""
    for (ks, _), v in entries(b, span[0]):
        if value(b, ks) == key:
            return v
    return None


# ---------------------------------------------------------------- blocks and transactions

LANGUAGES = {3: "PlutusV1", 6: "PlutusV2", 7: "PlutusV3", 8: "PlutusV4"}
PURPOSES = {0: "spending", 1: "minting", 2: "certifying", 3: "rewarding", 4: "voting", 5: "proposing",
            6: "guarding"}
REFERENCE_SCRIPT_LANGUAGES = {0: "native", 1: "PlutusV1", 2: "PlutusV2", 3: "PlutusV3", 4: "PlutusV4"}


def txins(b, span):
    return [] if span is None else [(value(b, s)[0], value(b, s)[1]) for s, _ in items(b, span[0])]


class Block:
    def __init__(self, raw):
        self.raw = raw
        tag_span, block_span = items(raw, 0)
        self.tag = value(raw, tag_span[0])
        if self.tag != 8:
            raise ValueError(f"block of tag {self.tag}: only Dijkstra blocks (tag 8) are read")
        header, body = items(raw, block_span[0])
        self.hash = blake2b(raw[header[0]:header[1]])
        header_body = items(raw, items(raw, header[0])[0][0])
        self.number = value(raw, header_body[0][0])
        self.slot = value(raw, header_body[1][0])
        self.prev = value(raw, header_body[2][0])
        self.certifies = value(raw, header_body[10][0])
        announcement = value(raw, header_body[11][0])
        self.announces = None if announcement is None else (announcement[0], announcement[1])
        txs, cert, _peras = items(raw, body[0])
        self.txs = [raw[s:e] for s, e in items(raw, txs[0])]
        self.has_cert = value(raw, cert[0]) is not None


def blocks_in(path):
    with open(path, "rb") as f:
        raw = f.read()
    i = 0
    while i < len(raw):
        j = skip(raw, i)
        yield Block(raw[i:j])
        i = j


def numbered(names, suffix):
    return sorted((n for n in names if n.endswith(suffix)), key=lambda n: int(n.split(".")[0].split("-")[-1]))


def chunk_blocks(db, names):
    for name in names:
        yield from blocks_in(os.path.join(db, "immutable", name))


def volatile_path(db, tip, anchor):
    """The VolatileDB's blocks from the one after `anchor` (the ImmutableDB's last block's hash) to
    `tip` (a hash in hex), found by following predecessors back from `tip`."""
    by_hash = {}
    for name in numbered(os.listdir(os.path.join(db, "volatile")), ".dat"):
        for block in blocks_in(os.path.join(db, "volatile", name)):
            by_hash[block.hash] = block
    path, cursor = [], bytes.fromhex(tip)
    while cursor != anchor:
        block = by_hash.get(cursor)
        if block is None:
            raise ValueError(f"the tip's ancestor {cursor.hex()} is in neither database")
        path.append(block)
        cursor = block.prev
    return list(reversed(path))


def segments(db, tip, jobs):
    """The selected chain cut into runs that can be read apart: the ImmutableDB's chunks in
    contiguous groups, each with the chunks before it (to find its first block's predecessor), and
    last the VolatileDB's blocks to `tip`. A run is (kind, its chunks or tip, the earlier chunks)."""
    names = numbered(os.listdir(os.path.join(db, "immutable")), ".chunk")
    size = max(1, -(-len(names) // (jobs * 4)))
    runs = [("immutable", names[i:i + size], names[:i]) for i in range(0, len(names), size)]
    return runs + [("volatile", tip, names)]


def predecessor(db, earlier):
    """The last block of the last non-empty chunk of `earlier`, or None."""
    for name in reversed(earlier):
        last = None
        for block in blocks_in(os.path.join(db, "immutable", name)):
            last = block
        if last is not None:
            return last
    return None


def transactions(blocks, previous, leios):
    """For each block, the block (with None for the place and the transaction), then every
    transaction the ledger applies for it, in order, with where it came from: a certified EB's
    closure (before the certifying block's own transactions), then the block's own."""
    for block in blocks:
        if previous is not None and block.prev != previous.hash:
            raise ValueError(f"block {block.number} does not follow block {previous.number}")
        yield block, None, None
        if block.has_cert:
            if previous is None or previous.announces is None:
                raise ValueError(f"block {block.number} certifies but its predecessor announces nothing")
            eb_hash = previous.announces[0]
            for index, tx in enumerate(leios.closure(eb_hash)):
                yield block, {"source": "eb", "eb": eb_hash.hex(), "ebSlot": previous.slot, "index": index}, tx
        for index, tx in enumerate(block.txs):
            yield block, {"source": "rb", "index": index}, tx
        previous = block


def run(task):
    """One run of the chain (`segments`), read by `visit` (`ScanRun` or `CasesRun`): what it found."""
    db, (kind, which, earlier), visit = task
    previous = predecessor(db, earlier)
    if kind == "immutable":
        blocks = chunk_blocks(db, which)
    else:
        blocks = volatile_path(db, which, previous.hash if previous else None)
    found = visit()
    for block, where, raw in transactions(blocks, previous, LeiosDb(db)):
        found.add(block, where, raw)
    return found


def walk(db, tip, jobs, visit):
    """What `visit` finds in each run of the chain, in chain order, the runs read by `jobs`
    processes."""
    tasks = [(db, segment, visit) for segment in segments(db, tip, jobs)]
    with multiprocessing.Pool(jobs) as pool:
        return pool.map(run, tasks, chunksize=1)


class LeiosDb:
    """The two LeiosDB partitions, read only."""

    def __init__(self, db):
        self.dbs = [sqlite3.connect(f"file:{os.path.join(db, name)}?mode=ro", uri=True)
                    for name in ("leios.imm.db", "leios.vol.db")]

    def closure(self, eb_hash):
        """The closure of an endorser block: its transactions in the order its body lists them,
        each checked against the hash and size the body gives."""
        for db in self.dbs:
            body = db.execute("SELECT body FROM ebBodies WHERE ebHashBytes = ?", (eb_hash,)).fetchone()
            txs = db.execute("SELECT txs FROM ebClosures WHERE ebHashBytes = ?", (eb_hash,)).fetchone()
            if body is None or txs is None:
                continue
            body, blob = bytes(body[0]), bytes(txs[0])
            if blake2b(body) != eb_hash:
                raise ValueError(f"the stored body of EB {eb_hash.hex()} does not hash to it")
            out, at = [], 0
            for (ks, _), (vs, _) in entries(body, 0):
                tx_hash, size = value(body, ks), value(body, vs)
                tx = blob[at:at + size]
                if len(tx) != size or blake2b(tx) != tx_hash or skip(tx, 0) != size:
                    raise ValueError(f"EB {eb_hash.hex()}: transaction {tx_hash.hex()} is not in its closure")
                out.append(tx)
                at += size
            if at != len(blob):
                raise ValueError(f"EB {eb_hash.hex()}: {len(blob) - at} bytes after its closure")
            return out
        raise ValueError(f"EB {eb_hash.hex()} is in neither LeiosDB partition")

    def count(self):
        return {name: db.execute("SELECT count(*) FROM ebClosures").fetchone()[0]
                for name, db in zip(("leios.imm.db", "leios.vol.db"), self.dbs)}


class Tx:
    def __init__(self, raw):
        self.raw = raw
        parts = items(raw, 0)
        if len(parts) != 4:
            raise ValueError(f"a block transaction of {len(parts)} fields")
        self.body, self.wits, self.aux, valid = parts
        self.valid = value(raw, valid[0])
        self.id = blake2b(raw[self.body[0]:self.body[1]])
        subs = field(raw, self.body, 23)
        self.subs = [] if subs is None else [items(raw, s)[:2] for s, _ in items(raw, subs[0])]

    def redeemers(self):
        """(level, tag, index, execution units) of every redeemer; level is "top" or the
        sub-transaction's position."""
        out = []
        for level, wits in [("top", self.wits)] + [(n, w) for n, (_, w) in enumerate(self.subs)]:
            span = field(self.raw, wits, 5)
            if span is None:
                continue
            major, _, _ = head(self.raw, span[0])
            if major != 5:
                raise ValueError("redeemers not in map form")
            for (ks, _), (vs, _) in entries(self.raw, span[0]):
                tag, index = value(self.raw, ks)
                _, units = value(self.raw, vs)
                out.append((level, tag, index, units))
        return out

    def spends(self):
        """The inputs, collateral and reference inputs of the transaction and its
        sub-transactions."""
        out = set()
        for body, keys in [(self.body, (0, 13, 18))] + [(sb, (0, 18)) for sb, _ in self.subs]:
            for key in keys:
                out.update(txins(self.raw, field(self.raw, body, key)))
        return out

    def script_inputs(self):
        """The inputs and reference inputs of the transaction and its sub-transactions: the outputs
        whose reference scripts it is given (`getBabbageScriptsProvided`)."""
        out = set()
        for body in [self.body] + [sb for sb, _ in self.subs]:
            for key in (0, 18):
                out.update(txins(self.raw, field(self.raw, body, key)))
        return out

    def outputs(self):
        """((transaction id, index), output bytes) of every output the transaction creates: for a
        valid one its outputs and its sub-transactions', for an invalid one its collateral return
        at the index after its outputs."""
        raw = self.raw
        own = [raw[s:e] for s, e in items(raw, field(raw, self.body, 1)[0])]
        if not self.valid:
            ret = field(raw, self.body, 16)
            return [] if ret is None else [((self.id, len(own)), raw[ret[0]:ret[1]])]
        out = [((self.id, n), o) for n, o in enumerate(own)]
        for sb, _ in self.subs:
            sub_id = blake2b(raw[sb[0]:sb[1]])
            out += [((sub_id, n), raw[s:e]) for n, (s, e) in enumerate(items(raw, field(raw, sb, 1)[0]))]
        return out


SCRIPT_PREFIX = {"native": 0, "PlutusV1": 1, "PlutusV2": 2, "PlutusV3": 3, "PlutusV4": 4}


def script_hash(language, raw):
    return blake2b(bytes([SCRIPT_PREFIX[language]]) + raw, 28)


def reference_script(raw):
    """An output's reference script (key 3 of the map form, `#6.24(bytes .cbor script)`) as its
    language and hash, or None."""
    major, _, _ = head(raw, 0)
    span = field(raw, (0, len(raw)), 3) if major == 5 else None
    if span is None:
        return None
    script = value(raw, span[0])[2]
    language_span, body_span = items(script, 0)
    language = REFERENCE_SCRIPT_LANGUAGES[value(script, language_span[0])]
    body = value(script, body_span[0]) if language != "native" else script[body_span[0]:body_span[1]]
    return language, script_hash(language, body)


def witness_scripts(tx):
    """(language, hash) of the Plutus scripts in the witness sets."""
    out = []
    for wits in [tx.wits] + [w for _, w in tx.subs]:
        for (ks, _), (vs, _) in entries(tx.raw, wits[0]):
            key = value(tx.raw, ks)
            if key in LANGUAGES:
                out += [(LANGUAGES[key], script_hash(LANGUAGES[key], value(tx.raw, s))) for s, _ in items(tx.raw, vs)]
    return out


def proposals(tx):
    """(action type, the keys of a parameter change's update) of each proposal procedure."""
    span = field(tx.raw, tx.body, 20)
    out = []
    for s, _ in [] if span is None else items(tx.raw, span[0]):
        action = items(tx.raw, items(tx.raw, s)[2][0])
        kind = value(tx.raw, action[0][0])
        keys = [value(tx.raw, k) for (k, _), _ in entries(tx.raw, action[2][0])] if kind == 0 else []
        out.append([kind, keys])
    return out


def txin_bytes(ref):
    i, n = ref
    index = bytes([n]) if n < 24 else b"\x18" + bytes([n]) if n < 256 else b"\x19" + n.to_bytes(2, "big")
    return b"\x82\x58\x20" + i + index


# ---------------------------------------------------------------- commands


class ScanRun:
    """What `scan` finds in a run of the chain: counts, proposals, every transaction with a redeemer,
    and the outputs with a reference script."""

    def __init__(self):
        self.blocks = self.certifying = self.subs = self.invalid = 0
        self.transactions = {"rb": 0, "eb": 0}
        self.first = self.last = None
        self.proposals, self.rows, self.references = [], [], {}

    def add(self, block, where, raw):
        if raw is None:
            self.blocks += 1
            self.certifying += block.has_cert
            self.last = {"number": block.number, "slot": block.slot, "hash": block.hash.hex()}
            self.first = self.first or self.last
            return
        tx = Tx(raw)
        self.transactions[where["source"]] += 1
        self.subs += len(tx.subs)
        self.invalid += not tx.valid
        self.proposals += [{"id": tx.id.hex(), "slot": block.slot, "action": kind, "update": keys}
                           for kind, keys in proposals(tx)]
        redeemers = tx.redeemers()
        if redeemers:
            self.rows.append({
                "block": block.number, "slot": block.slot, "blockHash": block.hash.hex(), **where,
                "id": tx.id.hex(), "valid": tx.valid, "subtxs": len(tx.subs), "size": len(raw),
                "interval": [field(raw, tx.body, 8) is not None, field(raw, tx.body, 3) is not None],
                "witness": [[lang, h.hex()] for lang, h in witness_scripts(tx)],
                "provides": sorted([i.hex(), n] for i, n in tx.script_inputs()),
                "redeemers": [{"level": lv, "purpose": PURPOSES.get(t, t), "index": ix, "units": u}
                              for lv, t, ix, u in redeemers],
                "spends": sorted([i.hex(), n] for i, n in tx.spends())})
        for ref, output in tx.outputs():
            script = reference_script(output)
            if script is not None:
                self.references[ref] = script


def scan(args):
    """The counts of the chain, every transaction with a redeemer in its own witness set or a
    sub-transaction's (`plutus.jsonl`, with the Plutus scripts it is given: its witness scripts and
    the reference scripts of the outputs its inputs and reference inputs spend), and every proposal
    (`summary.json`)."""
    runs = walk(args.db, args.tip, args.jobs, ScanRun)
    references = {}
    for r in runs:
        references.update(r.references)
    blocks = [r for r in runs if r.blocks]
    summary = {
        "blocks": sum(r.blocks for r in runs), "certifyingBlocks": sum(r.certifying for r in runs),
        "transactions": {k: sum(r.transactions[k] for r in runs) for k in ("rb", "eb")},
        "invalidTransactions": sum(r.invalid for r in runs), "subTransactions": sum(r.subs for r in runs),
        "plutusTransactions": {k: sum(1 for r in runs for row in r.rows if row["source"] == k) for k in ("rb", "eb")},
        "firstBlock": blocks[0].first, "tip": blocks[-1].last, "referenceScriptOutputs": len(references),
        "proposals": [p for r in runs for p in r.proposals], "leiosDbClosures": LeiosDb(args.db).count()}
    os.makedirs(args.out, exist_ok=True)
    with open(os.path.join(args.out, "plutus.jsonl"), "w") as out:
        for row in (row for r in runs for row in r.rows):
            given = [{"language": lang, "hash": h, "from": "witness"} for lang, h in row.pop("witness")]
            given += [{"language": references[ref][0], "hash": references[ref][1].hex(), "from": "reference"}
                      for ref in ((bytes.fromhex(i), n) for i, n in row.pop("provides"))
                      if ref in references and references[ref][0] != "native"]
            out.write(json.dumps({**row, "scripts": given}) + "\n")
    with open(os.path.join(args.out, "summary.json"), "w") as f:
        json.dump(summary, f, indent=1)
        f.write("\n")
    print(json.dumps({k: summary[k] for k in ("blocks", "transactions", "plutusTransactions", "tip")}))


def stratum(p):
    """What a sample keeps every value of: where the transaction came from, its validity flag, the
    languages of the Plutus scripts it is given and the purposes of its redeemers, whether it has
    sub-transactions, and whether it has a validity interval."""
    return (p["source"], p["valid"], tuple(sorted({s["language"] for s in p["scripts"]})),
            tuple(sorted({r["purpose"] for r in p["redeemers"]})), p["subtxs"] > 0, any(p["interval"]))


def sample(args):
    """A stratified sample: first from each stratum the transaction with the least cost per script
    it adds to the sample (its cost is its size times its number of redeemers, which bounds the
    contexts the oracle writes for it; on a tie the earliest), then, round by round over the strata
    (smallest first), the same among the transactions of at most two redeemers and at most twice
    the median size of their stratum, until `--size` transactions or none is left. Deterministic;
    the sample is in chain order."""
    plutus = jsonl(os.path.join(args.scan, "plutus.jsonl"))
    strata = {}
    for n, p in enumerate(plutus):
        strata.setdefault(stratum(p), []).append(n)

    def cost(n):
        return plutus[n]["size"] * len(plutus[n]["redeemers"])

    median = {key: sorted(plutus[n]["size"] for n in ns)[len(ns) // 2] for key, ns in strata.items()}

    def ordinary(key, n):
        return len(plutus[n]["redeemers"]) <= 2 and plutus[n]["size"] <= 2 * median[key]

    chosen, seen = set(), set()

    def price(n):
        return cost(n) / (1 + len({s["hash"] for s in plutus[n]["scripts"]} - seen)), n

    first = True
    while len(chosen) < args.size:
        before = len(chosen)
        for key in sorted(strata, key=lambda k: (len(strata[k]), str(k))):
            left = [n for n in strata[key] if n not in chosen and (first or ordinary(key, n))]
            if not left or len(chosen) >= args.size:
                continue
            best = min(left, key=price)
            chosen.add(best)
            seen |= {s["hash"] for s in plutus[best]["scripts"]}
        first = False
        if len(chosen) == before:
            break
    with open(os.path.join(args.out, "sample.jsonl"), "w") as f:
        for n in sorted(chosen):
            f.write(json.dumps({**plutus[n], "stratum": list(stratum(plutus[n]))}) + "\n")
    print(json.dumps({"plutus": len(plutus), "strata": len(strata), "sampled": len(chosen),
                      "scripts": len({s["hash"] for p in plutus for s in p["scripts"]}), "scriptsSampled": len(seen)}))


def environment(params_path, genesis_path):
    """The chain environment the context oracle takes: the protocol version and cost models of the
    protocol parameters (`cardano-cli query protocol-parameters`), the system start, epoch size and
    slot length of the Shelley genesis."""
    params = read_json(params_path)
    genesis = read_json(genesis_path)
    version = params["protocolVersion"]
    return {"protocolVersion": [version["major"], version["minor"]], "costModels": params["costModels"],
            "systemStart": genesis["systemStart"], "epochSize": genesis["epochLength"],
            "slotLength": genesis["slotLength"]}


class CasesRun:
    """What `cases` finds in a run of the chain: the bytes of the wanted transactions and the
    wanted outputs."""

    def __init__(self, ids, refs):
        self.ids, self.refs = ids, refs
        self.txs, self.found = {}, {}

    def add(self, _block, _where, raw):
        if raw is None:
            return
        tx = Tx(raw)
        if tx.id.hex() in self.ids:
            self.txs[tx.id.hex()] = raw.hex()
        for ref, out in tx.outputs():
            if ref in self.refs:
                self.found[ref] = out


def cases(args):
    """The sampled transactions with the outputs they spend, reference and pledge as collateral,
    found in a second walk of the chain, and the chain's environment: `cases.json`, the input of the
    chain context oracle (`chain/Main.hs`)."""
    rows = jsonl(os.path.join(args.out, "sample.jsonl"))
    wanted = {(bytes.fromhex(i), n) for r in rows for i, n in r["spends"]}
    runs = walk(args.db, args.tip, args.jobs, functools.partial(CasesRun, {r["id"] for r in rows}, wanted))
    txs, found = {}, {}
    for r in runs:
        txs.update(r.txs)
        found.update(r.found)
    missing = sorted((i.hex(), n) for i, n in wanted - found.keys())
    if missing or len(txs) != len(rows):
        raise SystemExit(f"{len(missing)} spent outputs and {len(rows) - len(txs)} transactions not found")
    chain_cases = []
    for r in rows:
        utxo = [[txin_bytes((bytes.fromhex(i), n)).hex(), found[(bytes.fromhex(i), n)].hex()] for i, n in r["spends"]]
        place = {k: r[k] for k in ("block", "slot", "source", "index", "eb", "ebSlot") if k in r}
        chain_cases.append({"name": r["id"], **place, "valid": r["valid"], "bytes": txs[r["id"]], "utxo": utxo})
    with open(os.path.join(args.out, "cases.json"), "w") as f:
        json.dump({**environment(args.params, args.genesis), "cases": chain_cases}, f, indent=1)
        f.write("\n")


LANGUAGE_NUMBERS = {"PlutusV1": 1, "PlutusV2": 2, "PlutusV3": 3, "PlutusV4": 4}
LANGUAGE_NAMES = {number: name for name, number in LANGUAGE_NUMBERS.items()}


def plutus_with_context(raw):
    """The fields of a `ToCBOR PlutusWithContext` encoding (`[protocol version, [language, script],
    arguments, [memory, steps], cost model]`, cardano-ledger 9df9f98c,
    `libs/cardano-ledger-core/src/Cardano/Ledger/Plutus/Evaluate.hs:186-193`): the language's
    number (1 to 4), the script's bytes, its arguments (each `Data`, hex), the execution units and
    the cost model. PlutusV1 and PlutusV2 arguments are a list of `Data` (`legacyPlutusArgsToData`),
    a PlutusV3 or PlutusV4 argument one `Data` (`Plutus/Language.hs:408-409, 449-452, 561-584`)."""
    version, script, arguments, units, model = items(raw, 0)
    language_span, binary_span = items(raw, script[0])
    language = value(raw, language_span[0]) + 1
    if language in (1, 2):
        data = [raw[s:e].hex() for s, e in items(raw, arguments[0])]
    else:
        data = [raw[arguments[0]:arguments[1]].hex()]
    memory, steps = value(raw, units[0])
    return {"protocol": value(raw, version[0]), "language": language, "script": value(raw, binary_span[0]).hex(),
            "arguments": data, "cpu": steps, "memory": memory, "costModel": value(raw, model[0])}


SCRIPT_INFO = ("minting", "spending", "rewarding", "certifying", "voting", "proposing", "guarding")


def constructor(raw, span):
    """The constructor index of the `Data` constructor at `span`, and the spans of its fields."""
    major, tag, inner = head(raw, span[0])
    if major != 6:
        raise ValueError("not a constructor")
    if 121 <= tag <= 127:
        return tag - 121, items(raw, inner)
    if 1280 <= tag <= 1400:
        return tag - 1280 + 7, items(raw, inner)
    if tag == 102:
        index, fields = items(raw, inner)
        return value(raw, index[0]), items(raw, fields[0])
    raise ValueError(f"tag {tag} is not a constructor")


def purpose_of(language, arguments):
    """The purpose a script's arguments name: the `ScriptPurpose` of a PlutusV1 or PlutusV2
    `ScriptContext` (its second field), the `ScriptInfo` of a PlutusV3 one (its third) or of a
    PlutusV4 one, a list (its third)."""
    context = bytes.fromhex(arguments[-1])
    if language in (1, 2):
        info = constructor(context, (0, len(context)))[1][1]
    elif language == 3:
        info = constructor(context, (0, len(context)))[1][2]
    else:
        info = items(context, 0)[2]
    return SCRIPT_INFO[constructor(context, info)[0]]


def requests(args):
    """The script requests of the reference's contexts: each context, in the order the reference
    collected it, becomes a request of the generated-program oracle (`scripts/oracle/plutus/
    generated-171`) with its own script, arguments, cost model and the redeemer's execution units as
    its budget. Writes `models.jsonl` (each language's cost model, at the protocol) and
    `requests.jsonl` (with the transaction's id and recorded validity flag)."""
    chain = read_json(args.cases)
    results = read_json(args.results)
    if [c["name"] for c in chain["cases"]] != [r["name"] for r in results]:
        raise SystemExit("the results are not those of the cases")
    models, rows = [], []
    for case, result in zip(chain["cases"], results):
        outcome = result["result"]
        if not outcome["accepted"] or outcome["failure"] is not None:
            raise SystemExit(f"{case['name']}: the reference collected no contexts: {outcome}")
        levels = [("top", outcome["top"])] + [(f"sub{n}", sub) for n, sub in enumerate(outcome["subtxs"])]
        for level, collected in levels:
            for k, pwc in enumerate(collected.get("contexts", [])):
                fields = plutus_with_context(bytes.fromhex(pwc))
                expected = chain["costModels"][LANGUAGE_NAMES[fields["language"]]]
                if fields["costModel"] != expected:
                    raise SystemExit(f"{case['name']}: the context's cost model is not the chain's")
                model = {"language": fields["language"], "protocol": fields["protocol"], "costModel": fields["costModel"]}
                if model not in models:
                    models.append(model)
                rows.append({"id": f"{case['name']}/{level}/{k}", "tx": case["name"], "valid": case["valid"],
                             "purpose": purpose_of(fields["language"], fields["arguments"]),
                             "model": models.index(model), "language": fields["language"],
                             "protocol": fields["protocol"], "script": fields["script"],
                             "arguments": fields["arguments"], "cpu": fields["cpu"], "memory": fields["memory"],
                             "verbose": True})
    os.makedirs(args.out, exist_ok=True)
    for name, lines in (("models.jsonl", models), ("requests.jsonl", rows)):
        with open(os.path.join(args.out, name), "w") as f:
            f.writelines(json.dumps(line, separators=(",", ":")) + "\n" for line in lines)
    print(json.dumps({"transactions": len(results), "requests": len(rows), "models": len(models)}))


def verdicts(args):
    """The generated-program oracle of plutus 1.71.0.0 (`scripts/oracle/plutus/generated-171`, run as
    `scripts/oracle/plutus/generated/run.py` runs it) on the requests, each with its model's cost
    model: `verdicts.jsonl` in that oracle's form, the raw answers in `--evidence`, and
    `manifest.json` binding the files, the image and the harness."""
    generated = os.path.join(HERE, "..", "plutus", "generated")
    sys.path.insert(0, generated)
    import run as oracle  # noqa: E402  (the generated-program oracle's runner)

    release = oracle.RELEASES["1.71.0.0"]
    models = jsonl(os.path.join(args.out, "models.jsonl"))
    rows = jsonl(os.path.join(args.out, "requests.jsonl"))
    asked = [{k: v for k, v in {**row, "costModel": models[row["model"]]["costModel"]}.items()
              if k not in ("tx", "valid", "purpose")} for row in rows]
    os.makedirs(args.evidence, exist_ok=True)
    raw = pathlib.Path(args.evidence) / "raw.jsonl"
    results = oracle.execute(release["image"], asked, raw, "leios-plutus")
    with open(os.path.join(args.out, "verdicts.jsonl"), "w") as f:
        f.writelines(oracle.dump(oracle.verdict(r)) + "\n" for r in results)
    root = pathlib.Path(HERE).parents[2]
    harness = [root / release["harness"] / name for name in ("Main.hs", "Dockerfile")]
    files = ("models.jsonl", "requests.jsonl", "verdicts.jsonl")
    stages = {}
    for r in results:
        stages[r["reference"]["stage"]] = stages.get(r["reference"]["stage"], 0) + 1
    manifest = {
        "plutusCommit": release["pin"],
        "oracleImage": oracle.image_id(release["image"]),
        "oracleBaseImage": oracle.image_id(release["base"]),
        "requests": len(rows),
        "transactions": len({r["tx"] for r in rows}),
        "stages": dict(sorted(stages.items())),
        "sha256": {name: oracle.sha256(pathlib.Path(args.out) / name) for name in files},
        "toolSha256": {str(p.relative_to(root)): oracle.sha256(p) for p in harness},
        "rawSha256": oracle.sha256(raw),
    }
    with open(os.path.join(args.out, "manifest.json"), "w") as f:
        f.write(json.dumps(manifest, indent=2) + "\n")
    print(json.dumps({k: manifest[k] for k in ("requests", "transactions", "stages")}))


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    for name in ("scan", "cases"):
        p = sub.add_parser(name)
        p.add_argument("db")
        p.add_argument("--tip", required=True, help="the selected tip's hash, hex")
        p.add_argument("--out", required=True)
        p.add_argument("--jobs", type=int, default=os.cpu_count())
        if name == "cases":
            p.add_argument("--params", required=True, help="cardano-cli query protocol-parameters at the tip")
            p.add_argument("--genesis", required=True, help="the testnet's shelley-genesis.json")
    p = sub.add_parser("requests")
    p.add_argument("--cases", required=True)
    p.add_argument("--results", required=True)
    p.add_argument("--out", required=True)
    p = sub.add_parser("verdicts")
    p.add_argument("--out", required=True, help="the directory `requests` wrote")
    p.add_argument("--evidence", required=True, help="where the oracle's raw answers go, outside the checkout")
    p = sub.add_parser("sample")
    p.add_argument("--scan", required=True, help="the directory `scan` wrote")
    p.add_argument("--out", required=True)
    p.add_argument("--size", type=int, default=200)
    args = parser.parse_args()
    {"scan": scan, "sample": sample, "cases": cases, "requests": requests, "verdicts": verdicts}[args.command](args)


if __name__ == "__main__":
    sys.exit(main())
