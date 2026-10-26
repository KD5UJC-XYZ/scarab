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
import importlib
import json
import pathlib
import subprocess

from . import ntclocal, ntcquery
from .cbor import Raw, Tag, enc
from . import eratx
from .refusalcases import babbage_corpus, corpus
from .util import ENV, MAGIC, REFERENCE_IMAGE, REPO, data_root, docker, in_ref, ref

# The hard-fork-combinator index of each era the corpus covers: the LocalTxSubmission item `[index, #6.24(tx)]`
# and the era of the LocalStateQuery queries
ERA_INDEX = {"babbage": 5, "conway": 6}
BATCH = 4
ATTEMPTS = 40
DEFAULT_OUT = REPO / "fixtures" / "reference" / "refusals"
GENESIS = ("byron-genesis.json", "shelley-genesis.json", "alonzo-genesis.json", "conway-genesis.json",
           "configuration.yaml")

# What the manifest requires per era. A family without a case is an incomplete gate in the Scala suite.
COVERAGE = {
    "conway": ["UTXO", "UTXOW", "UTXOS", "LEDGER", "CERTS", "DELEG", "POOL", "GOVCERT", "GOV", "MEMPOOL"],
    "babbage": ["UTXO", "UTXOW", "UTXOS", "LEDGER", "DELEG", "POOL"],
}
# (DELEGS of the pinned ledger has no failure of its own: it wraps DELPL's, and the withdrawal failures are
# LEDGER's, `ShelleyWithdrawalsMissingAccounts` and `ShelleyIncompleteWithdrawals`.)

# The constructor of each family as `cardano-cli` renders it (the check that `family` reads the tags right).
RENDERED = {
    "conway": {"UTXOW": "ConwayUtxowFailure", "UTXO": "UtxoFailure", "UTXOS": "UtxosFailure",
               "CERTS": "ConwayCertsFailure", "DELEG": "DelegFailure", "POOL": "PoolFailure",
               "GOVCERT": "GovCertFailure", "GOV": "ConwayGovFailure", "MEMPOOL": "ConwayMempoolFailure",
               "LEDGER": "Conway"},
    "babbage": {"UTXOW": "UtxowFailure", "UTXO": "UtxoFailure", "UTXOS": "UtxosFailure",
                "DELEGS": "DelegsFailure", "DELEG": "DelegFailure", "POOL": "PoolFailure", "LEDGER": "Shelley"},
}


class Unexpected(Exception):
    """The reference answered something a refusal corpus cannot hold (an acceptance, another era)."""


def conway_family(failure):
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


def babbage_family(failure):
    """The rule of one `ShelleyLedgerPredFailure` of Babbage (`Shelley/Rules/Ledger.hs`): 0 UTXOW (Babbage's
    2 is UTXO, whose 1, Alonzo's UTXO, has UTXOS at 7), 1 DELEGS (its DELPL failure: 0 POOL, 1 DELEG), the others
    LEDGER's own."""
    tag = failure[0]
    if tag == 0:
        utxow = failure[1]
        if utxow[0] == 2:
            utxo = utxow[1]
            return "UTXOS" if utxo[0] == 1 and utxo[1][0] == 7 else "UTXO"
        return "UTXOW"
    if tag == 1:
        delegs = failure[1]
        if delegs[0] == 1 and isinstance(delegs[1], list):
            return {0: "POOL", 1: "DELEG"}.get(delegs[1][0], "DELEGS")
        return "DELEGS"
    return "LEDGER"


FAMILY = {"conway": conway_family, "babbage": babbage_family}


def families(era, reason):
    """The family of each failure of an `ApplyTxErr` `[[era index, [failure, ...]]]` of `era`, in order."""
    value, end = ntcquery.decode(reason)
    if end != len(reason) or not isinstance(value, list) or len(value) != 1 or value[0][0] != ERA_INDEX[era]:
        raise Unexpected(f"not a {era} ApplyTxErr: {reason.hex()[:120]}")
    return [FAMILY[era](f) for f in value[0][1]]


def unnamed(era, fams, rendered):
    """The families whose constructor the reference's rendering does not name."""
    return sorted({f for f in fams if RENDERED[era][f] not in rendered})


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def item(era, tx):
    """What LocalTxSubmission carries for a transaction of `era`: `[index, #6.24(tx)]`."""
    return enc([ERA_INDEX[era], Tag(24, bytes(tx))])


class Session(ntclocal.Session):
    def point(self, era):
        """The ledger tip (GetLedgerTip) of the acquired state."""
        return self.query_raw([0, [0, [ERA_INDEX[era], [0]]]])

    def tip(self, era):
        self.acquire()
        point = self.point(era)
        self.release()
        return point

    def cbor(self, era, query):
        """`GetCBOR query` in `era`, at the acquired state: the bytes of the answer."""
        answer = self.query_raw([0, [0, [ERA_INDEX[era], [9, query]]]])
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


def judged(name, era, batch):
    """The refusals of `batch` and the state they were judged against, or None when the tip moved."""
    with Session(ref(name), f"{ENV}/socket/node1/sock", MAGIC) as s:
        s.acquire()
        point = s.point(era)
        state = s.cbor(era, [12])
        utxo = s.cbor(era, [7])
        s.release()
        slot, held = s.mempool()
        if held or slot != point[0][0] + 1:
            return None
        reasons = [s.submit_item(item(era, case.tx)) for case in batch]
        if s.tip(era) != point or s.mempool() != (slot, 0):
            return None
    return {"point": point[0], "mempoolSlot": slot, "state": state, "utxo": utxo, "reasons": reasons}


def rendering(name, era, tx):
    """What `cardano-cli transaction submit` prints for the transaction (a second submission)."""
    envelope = eratx.envelope(era, tx)
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


def capture(name, era, cases):
    """Every case judged, in batches whose state held still."""
    out, pending, attempts = [], list(cases), 0
    while pending:
        batch = pending[:BATCH]
        result = judged(name, era, batch)
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
        raise SystemExit(f"no devnet `{args.name}`: run `devnet.py --name {args.name} up` "
                         f"(or `up-eras --final-era babbage`) first")
    era = args.era
    corpus_name = args.corpus or f"{era}-devnet"
    out = pathlib.Path(args.out)
    directory = out / corpus_name
    if directory.exists():
        raise SystemExit(f"{directory} exists: remove it to refresh the corpus")
    command = f"python3 scripts/devnet.py --name <devnet> refusal-corpus --era {era} --corpus {corpus_name}"
    if args.cases:
        module, _, function = args.cases.partition(":")
        cases = getattr(importlib.import_module(f".{module}", __package__), function)(args.name)
        command += f" --cases {args.cases}"
    else:
        cases = corpus(args.name, f"{ENV}/socket/node1/sock") if era == "conway" else babbage_corpus(args.name)
    labels = [c.label for c in cases]
    if len(set(labels)) != len(labels):
        raise SystemExit("duplicate case labels")
    judged_cases = capture(args.name, era, cases)
    records, problems = [], []
    for name in GENESIS:
        write(directory / "genesis" / name, (root / "genesis" / name).read_bytes())
    for case, result, reason in judged_cases:
        state_file = f"states/{sha256(result['state'])}.cbor"
        utxo_file = f"utxo/{sha256(result['utxo'])}.cbor"
        write(directory / state_file, result["state"])
        write(directory / utxo_file, result["utxo"])
        fams = families(era, reason)
        rendered = rendering(args.name, era, case.tx)
        missing = [e for e in case.expect if e not in rendered]
        if missing or unnamed(era, fams, rendered):
            problems.append({"case": case.label, "notRendered": missing,
                             "familiesNotNamed": unnamed(era, fams, rendered), "rendered": rendered[:600]})
        record = {"label": case.label, "era": era, "families": fams, "expect": list(case.expect),
                  "item": item(era, case.tx).hex(),
                  "tip": {"slot": result["point"][0], "hash": result["point"][1].hex()},
                  "mempoolSlot": result["mempoolSlot"], "state": state_file, "utxo": utxo_file,
                  "reason": reason.hex(), "reasonSha256": sha256(reason), "rendered": rendered}
        records.append(record)
    data = "".join(json.dumps(record, separators=(",", ":")) + "\n" for record in records).encode()
    write(directory / "cases" / "cases.jsonl", data)
    files = [{"file": "cases/cases.jsonl", "sha256": sha256(data)}]
    pv = json.loads(in_ref(args.name, f"cardano-cli query protocol-parameters --testnet-magic {MAGIC} "
                                      f"--socket-path {ENV}/socket/node1/sock").stdout)["protocolVersion"]
    meta = {"format": 2, "era": era, "protocolVersion": [pv["major"], pv["minor"]],
            "provenance": provenance(args.name, command),
            "genesis": {n: sha256((directory / "genesis" / n).read_bytes()) for n in GENESIS},
            "cases": files}
    meta_bytes = (json.dumps(meta, indent=2) + "\n").encode()
    write(directory / "corpus.json", meta_bytes)
    manifest_path = out / "manifest.json"
    manifest = (json.loads(manifest_path.read_text()) if manifest_path.exists()
                else {"format": 1, "coverage": COVERAGE, "corpora": []})
    manifest["corpora"] = sorted([c for c in manifest["corpora"] if c["path"] != corpus_name] +
                                 [{"path": corpus_name, "sha256": sha256(meta_bytes)}], key=lambda c: c["path"])
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n")
    all_families = [families(era, r) for _, _, r in judged_cases]
    summary = {"corpus": corpus_name, "era": era, "cases": len(records),
               "families": sorted({f for fs in all_families for f in fs}),
               "multiFailure": sum(len(fs) > 1 for fs in all_families), "problems": problems}
    print(json.dumps(summary, indent=2))
    if problems:
        raise SystemExit("cases the reference refused for another reason than the one they were built for")
