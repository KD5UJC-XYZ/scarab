# SPDX-License-Identifier: Apache-2.0
"""Refresh the reference-refusal corpus (#401) against the devnet's Haskell node.

    python3 scripts/devnet.py --name <yours> refusal-corpus [--corpus conway-devnet] [--out fixtures/reference/refusals]
    python3 scripts/devnet.py --name <yours> down

Every transaction of `refusalcases` is submitted over node-to-client LocalTxSubmission to reference node 1,
and its complete rejection (the hard-fork-combinator `ApplyTxErr`, every predicate failure in the order the
reference accumulated them) is kept as the expected bytes. Next to it the corpus keeps what an offline
replay needs to put the same transaction to Scarab's mempool: the reference's ledger state at the tip the
transaction was judged at (`GetCBOR DebugNewEpochState`; with UTxO-HD its UTxO is empty, so the whole UTxO
is kept beside it from `GetCBOR GetUTxOWhole`) and the genesis files of the cluster.

A batch counts only when, on one connection, the ledger tip and the mempool's snapshot slot (LocalTxMonitor,
the tip's successor, with no transaction held) are the same before the state is read and after the last
refusal: the reference then judged every transaction of the batch against that state. Otherwise the batch is
taken again. Nothing is written by hand: `families` is read from the reference's bytes and checked against the
names in its `cardano-cli` rendering, which is kept only as a diagnostic (it comes from a second submission).
"""
import datetime
import hashlib
import json
import pathlib
import subprocess

from . import ntclocal, ntcquery
from .cbor import Raw, Tag, enc
from .refusalcases import corpus
from .util import ENV, MAGIC, REFERENCE_IMAGE, REPO, data_root, docker, in_ref, ref

CONWAY = 6
BATCH = 4
ATTEMPTS = 40
DEFAULT_OUT = REPO / "fixtures" / "reference" / "refusals"
GENESIS = ("byron-genesis.json", "shelley-genesis.json", "alonzo-genesis.json", "conway-genesis.json",
           "configuration.yaml")

# What the manifest requires per era. A family without a case is an incomplete gate in the Scala suite.
COVERAGE = {
    "conway": ["UTXO", "UTXOW", "UTXOS", "LEDGER", "CERTS", "DELEG", "POOL", "GOVCERT", "GOV", "MEMPOOL"],
    "babbage": ["UTXO", "UTXOW", "UTXOS", "DELEGS", "DELEG", "POOL"],
}

# The constructor of each family as `cardano-cli` renders it (the check that `family` reads the tags right).
RENDERED = {"UTXOW": "ConwayUtxowFailure", "UTXO": "UtxoFailure", "UTXOS": "UtxosFailure",
            "CERTS": "ConwayCertsFailure", "DELEG": "DelegFailure", "POOL": "PoolFailure",
            "GOVCERT": "GovCertFailure", "GOV": "ConwayGovFailure", "MEMPOOL": "ConwayMempoolFailure",
            "LEDGER": "Conway"}


class Unexpected(Exception):
    """The reference answered something a refusal corpus cannot hold (an acceptance, another era)."""


def family(failure):
    """The rule of one `ConwayLedgerPredFailure` (`Conway/Rules/Ledger.hs`), from its constructor tags:
    1 UTXOW (its 0 is UTXO, whose 0 is UTXOS), 2 CERTS (its 1 is CERT: 1 DELEG, 2 POOL, 3 GOVCERT), 3 GOV,
    7 the mempool's text, the others LEDGER's own."""
    tag = failure[0]
    if tag == 1:
        utxow = failure[1]
        if utxow[0] == 0:
            return "UTXOS" if utxow[1][0] == 0 else "UTXO"
        return "UTXOW"
    if tag == 2:
        certs = failure[1]
        if certs[0] == 1:
            return {1: "DELEG", 2: "POOL", 3: "GOVCERT"}[certs[1][0]]
        return "CERTS"
    return {3: "GOV", 7: "MEMPOOL"}.get(tag, "LEDGER")


def families(reason):
    """The family of each failure of a Conway `ApplyTxErr` `[[6, [failure, ...]]]`, in order."""
    value, end = ntcquery.decode(reason)
    if end != len(reason) or not isinstance(value, list) or len(value) != 1 or value[0][0] != CONWAY:
        raise Unexpected(f"not a Conway ApplyTxErr: {reason.hex()[:120]}")
    return [family(f) for f in value[0][1]]


def unnamed(fams, rendered):
    """The families whose constructor the reference's rendering does not name."""
    return sorted({f for f in fams if RENDERED[f] not in rendered})


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def item(tx):
    """What LocalTxSubmission carries for a Conway transaction: `[6, #6.24(tx)]`."""
    return enc([CONWAY, Tag(24, bytes(tx))])


class Session(ntclocal.Session):
    def tip(self):
        self.acquire()
        point = self.query_raw([0, [0, [CONWAY, [0]]]])
        self.release()
        return point

    def cbor(self, query):
        """`GetCBOR query` in Conway, at the acquired state: the bytes of the answer."""
        answer = self.query_raw([0, [0, [CONWAY, [9, query]]]])
        if not isinstance(answer, list) or len(answer) != 1 or not isinstance(answer[0], ntcquery.Tagged):
            raise Unexpected(f"era mismatch: {str(answer)[:200]}")
        return bytes(answer[0].value)

    def mempool(self):
        """(snapshot slot, transactions held) from LocalTxMonitor."""
        slot = self.acquire_snapshot()
        sizes, _ = ntcquery.decode(self.sizes())
        self.release_snapshot()
        return slot, sizes[1][2]

    def submit_item(self, payload):
        self._send(6, enc([0, Raw(payload)]))
        reply = self._recv(6)
        if reply[0] == 1:
            return None
        return self._reason(self.last_raw)


def judged(name, batch):
    """The refusals of `batch` and the state they were judged against, or None when the tip moved."""
    with Session(ref(name), f"{ENV}/socket/node1/sock", MAGIC) as s:
        s.acquire()
        point = s.query_raw([0, [0, [CONWAY, [0]]]])
        state = s.cbor([12])
        utxo = s.cbor([7])
        s.release()
        slot, held = s.mempool()
        if held or slot != point[0][0] + 1:
            return None
        reasons = [s.submit_item(item(case.tx)) for case in batch]
        if s.tip() != point or s.mempool() != (slot, 0):
            return None
    return {"point": point[0], "mempoolSlot": slot, "state": state, "utxo": utxo, "reasons": reasons}


def rendering(name, tx):
    """What `cardano-cli transaction submit` prints for the transaction (a second submission)."""
    envelope = json.dumps({"type": "Tx ConwayEra", "description": "", "cborHex": tx.hex()})
    subprocess.run(["docker", "exec", "-i", ref(name), "/bin/sh", "-c", "cat > /work/refusal.tx"],
                   input=envelope, text=True, check=True)
    r = docker("exec", ref(name), "/bin/sh", "-c",
               f"cardano-cli conway transaction submit --testnet-magic {MAGIC} "
               f"--socket-path {ENV}/socket/node1/sock --tx-file /work/refusal.tx", check=False)
    text = (r.stdout + r.stderr).strip()
    return next((line for line in text.splitlines() if line.startswith("Error:")), text)[:4000]


def provenance(name, command):
    def git(*args):
        return subprocess.run(["git", "-C", str(REPO), *args], capture_output=True, text=True).stdout.strip()
    node = in_ref(name, "cardano-node --version").stdout.strip().splitlines()
    image = docker("image", "inspect", REFERENCE_IMAGE, "--format", "{{.Id}}").stdout.strip()
    return {"reference": {"node": node[0], "revision": node[1].removeprefix("git rev ").strip(),
                          "image": REFERENCE_IMAGE, "imageId": image},
            "command": command, "scarabCommit": git("rev-parse", "HEAD"),
            "scarabDirty": bool(git("status", "--porcelain", "--", "scripts")),
            "recorded": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")}


def write(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)


def capture(name, cases):
    """Every case judged, in batches whose state held still."""
    out, pending, attempts = [], list(cases), 0
    while pending:
        batch = pending[:BATCH]
        result = judged(name, batch)
        if result is None:
            attempts += 1
            if attempts > ATTEMPTS:
                raise Unexpected("the reference's tip did not hold still for a batch")
            continue
        accepted = [c.label for c, r in zip(batch, result["reasons"]) if r is None]
        if accepted:
            raise Unexpected(f"the reference accepted {accepted}: the cases are wrong")
        out += [(c, result, r) for c, r in zip(batch, result["reasons"])]
        pending = pending[BATCH:]
        print(json.dumps({"judged": [c.label for c in batch], "slot": result["point"][0]}), flush=True)
    return out


def refusal_corpus(args):
    root = data_root(args.name)
    if not (root / "genesis").is_dir():
        raise SystemExit(f"no devnet `{args.name}`: run `devnet.py --name {args.name} up` first")
    out = pathlib.Path(args.out)
    directory = out / args.corpus
    if directory.exists():
        raise SystemExit(f"{directory} exists: remove it to refresh the corpus")
    command = f"python3 scripts/devnet.py --name <devnet> refusal-corpus --corpus {args.corpus}"
    cases = corpus(args.name, f"{ENV}/socket/node1/sock")
    labels = [c.label for c in cases]
    if len(set(labels)) != len(labels):
        raise SystemExit("duplicate case labels")
    judged_cases = capture(args.name, cases)
    files, problems = [], []
    for name in GENESIS:
        write(directory / "genesis" / name, (root / "genesis" / name).read_bytes())
    for case, result, reason in judged_cases:
        state_file = f"states/{sha256(result['state'])}.cbor"
        utxo_file = f"utxo/{sha256(result['utxo'])}.cbor"
        write(directory / state_file, result["state"])
        write(directory / utxo_file, result["utxo"])
        fams = families(reason)
        rendered = rendering(args.name, case.tx)
        missing = [e for e in case.expect if e not in rendered]
        if missing or unnamed(fams, rendered):
            problems.append({"case": case.label, "notRendered": missing, "familiesNotNamed": unnamed(fams, rendered),
                             "rendered": rendered[:600]})
        record = {"label": case.label, "era": "conway", "families": fams, "expect": list(case.expect),
                  "item": item(case.tx).hex(), "tip": {"slot": result["point"][0], "hash": result["point"][1].hex()},
                  "mempoolSlot": result["mempoolSlot"], "state": state_file, "utxo": utxo_file,
                  "reason": reason.hex(), "reasonSha256": sha256(reason), "rendered": rendered}
        data = (json.dumps(record, indent=2) + "\n").encode()
        write(directory / "cases" / f"{case.label}.json", data)
        files.append({"file": f"cases/{case.label}.json", "sha256": sha256(data)})
    pv = json.loads(in_ref(args.name, f"cardano-cli conway query protocol-parameters --testnet-magic {MAGIC} "
                                      f"--socket-path {ENV}/socket/node1/sock").stdout)["protocolVersion"]
    meta = {"format": 1, "era": "conway", "protocolVersion": [pv["major"], pv["minor"]],
            "provenance": provenance(args.name, command),
            "genesis": {n: sha256((directory / "genesis" / n).read_bytes()) for n in GENESIS},
            "cases": files}
    meta_bytes = (json.dumps(meta, indent=2) + "\n").encode()
    write(directory / "corpus.json", meta_bytes)
    manifest_path = out / "manifest.json"
    manifest = (json.loads(manifest_path.read_text()) if manifest_path.exists()
                else {"format": 1, "coverage": COVERAGE, "corpora": []})
    manifest["corpora"] = sorted([c for c in manifest["corpora"] if c["path"] != args.corpus] +
                                 [{"path": args.corpus, "sha256": sha256(meta_bytes)}], key=lambda c: c["path"])
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n")
    summary = {"corpus": str(directory.relative_to(REPO)) if directory.is_relative_to(REPO) else args.corpus,
               "cases": len(files), "families": sorted({f for _, _, r in judged_cases for f in families(r)}),
               "multiFailure": sum(len(families(r)) > 1 for _, _, r in judged_cases), "problems": problems}
    print(json.dumps(summary, indent=2))
    if problems:
        raise SystemExit("cases the reference refused for another reason than the one they were built for")
