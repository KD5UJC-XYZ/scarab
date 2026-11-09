#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Reference verdicts of the Dijkstra BBODY, UTXOW, CERTS and GOV rules of the Leios testnet build (#54).

The cases are the Dijkstra blocks of the reference cluster's chain (`fixtures/dijkstra-devnet`), as
they are and with a header or the parameters changed, and certificates, votes and proposals in their
Dijkstra encodings. The oracle applies each rule on its own with the unchanged cardano-ledger
`9df9f98c` and records its acceptance or each predicate failure (`EncCBOR` at version 12 and `show`).

  run.py            offline: the generated cases equal the recorded ones
  run.py --replay   run the image and compare every result with the fixture
  run.py --record   run the image and rewrite the fixture and its provenance
"""
import argparse
import datetime
import hashlib
import json
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts"))

from devnet import cborspan  # noqa: E402
FIXTURE = ROOT / "fixtures/reference/dijkstra-rules"
CASES_FILE = FIXTURE / "cases.jsonl"
PROVENANCE = FIXTURE / "provenance.json"
IMAGE = "scarab-dijkstra-rules-oracle:local"
PROGRAM = "scripts/oracle/dijkstra-rules/Main.hs"
BLOCKS = ROOT / "fixtures/dijkstra-devnet/blocks.tsv"

# The devnet's parameters in its Dijkstra epoch (its genesis files), those the rules read.
PARAMS = {
    "major": 12,
    "maxBlockExUnits": [72000000, 20000000000],
    "maxRefScriptSizePerBlock": 1048576,
    "keyDeposit": 2000000,
    "poolDeposit": 500000000,
    "drepDeposit": 500000000,
    "govActionDeposit": 100000000000,
    "govActionLifetime": 120,
    "eMax": 18,
    "minPoolCost": 170000000,
    "minFeeA": 44,
    "minFeeB": 155381,
    "maxTxSize": 16384,
    "maxValSize": 5000,
    # Babbage's upgrade of Alonzo's lovelacePerUTxOWord 34482 (`coinsPerUTxOWordToCoinsPerUTxOByte`)
    "coinsPerUTxOByte": 4310,
    "maxTxExUnits": [16500000, 10000000000],
    "collateralPercentage": 150,
    "maxCollateralInputs": 3,
    "minFeeRefScriptCostPerByte": 15,
    "refScriptCostStride": 25600,
    "refScriptCostMultiplier": [6, 5],
    "prices": [[577, 10000], [721, 10000000]],
    "maxRefScriptSizePerTx": 204800,
}

# The devnet transaction's input: the Shelley genesis's initial funds of `607be6…` (its
# `extraConfig.initialFunds`), whose input is the Blake2b-256 of the address, index 0.
GENESIS_ADDRESS = bytes.fromhex("607be6f4db9b5c16681157f9c2e15e57e341e21ecf94e553896f897a44")
GENESIS_COIN = 900000000000000
DEVNET_TX_BLOCK = 37
DEVNET_TX_SLOT = 225


def head(major, n):
    m = major << 5
    if n < 24:
        return bytes([m | n])
    for code, width in ((24, 1), (25, 2), (26, 4), (27, 8)):
        if n < 1 << (8 * width):
            return bytes([m | code]) + n.to_bytes(width, "big")
    raise ValueError(n)


def uint(n):
    return head(0, n)


def bstr(b):
    return head(2, len(b)) + b


def tstr(s):
    b = s.encode()
    return head(3, len(b)) + b


def arr(*items):
    return head(4, len(items)) + b"".join(items)


def cmap(pairs):
    return head(5, len(pairs)) + b"".join(k + v for k, v in pairs)


def tagged_set(items):
    return head(6, 258) + arr(*items)


NULL = b"\xf6"


def h28(i):
    return bytes([i]) * 28


def key(i):
    return arr(uint(0), bstr(h28(i)))


def account(i, network=0):
    return bstr(bytes([0xE0 | network]) + h28(i))


def anchor():
    return arr(tstr("https://example.com"), bstr(bytes(32)))


DEPOSIT = PARAMS["keyDeposit"]
DREP = PARAMS["drepDeposit"]
GOV = PARAMS["govActionDeposit"]
TX = bytes([6]) * 32


def reg(i, deposit=DEPOSIT):
    return arr(uint(7), key(i), uint(deposit))


def unreg(i, refund=DEPOSIT):
    return arr(uint(8), key(i), uint(refund))


def reg_drep(i, deposit=DREP):
    return arr(uint(16), key(i), uint(deposit), NULL)


def proposal(action, deposit=GOV, ret=account(1)):
    return arr(uint(deposit), ret, action, anchor())


INFO = arr(uint(6))


def blocks():
    rows = [line.split("\t") for line in BLOCKS.read_text().splitlines()[1:] if line]
    return [(int(r[1]), r[4]) for r in rows if r[0] == "8"]


def generate():
    cases = []

    def case(name, kind, params=None, epoch=1, **fields):
        c = {"name": name, "kind": kind, "params": dict(PARAMS, **(params or {})), "epoch": epoch}
        c.update({k: v.hex() if isinstance(v, bytes) else v for k, v in fields.items()})
        cases.append(c)

    chain = blocks()
    for number, raw in chain:
        case(f"bbody/devnet-{number}", "bbody", block=raw)
    first = chain[0][1]
    case("bbody/major-13", "bbody", params={"major": 13}, block=first)
    case("bbody/no-ex-units", "bbody", params={"maxBlockExUnits": [0, 0]}, block=first)
    case("bbody/no-ref-scripts", "bbody", params={"maxRefScriptSizePerBlock": 0}, block=first)
    # the header's body hash (field 7 of its body) replaced: the 32 bytes after its `58 20` head
    blob = bytes.fromhex(first)
    marker = bytes.fromhex(declared_body_hash(blob))
    assert blob.count(marker) == 1
    case("bbody/other-body-hash", "bbody", block=blob.replace(marker, bytes([9]) * 32).hex())
    case("bbody/other-body-size-and-hash", "bbody",
         block=with_body_size(blob, 1).replace(marker, bytes([9]) * 32).hex())

    tx_block = bytes.fromhex(dict(chain)[DEVNET_TX_BLOCK])
    case(f"bbody/devnet-{DEVNET_TX_BLOCK}-genesis-utxo", "bbody", block=tx_block.hex(), utxo=genesis_utxo())
    body, wits, aux, flag = devnet_tx(tx_block)
    # the block with its transaction flagged phase-2-invalid (`f4` for `f5`, the last item of the
    # transaction), and the header's body hash the Blake2b-256 of the body so changed
    old_body = block_body(tx_block)
    assert old_body.count(arr(body, wits, aux, flag)) == 1 and flag == b"\xf5"
    new_body = old_body.replace(arr(body, wits, aux, flag), arr(body, wits, aux, b"\xf4"))
    old_hash = bytes.fromhex(declared_body_hash(tx_block))
    assert old_hash == hashlib.blake2b(old_body, digest_size=32).digest()
    flipped = tx_block.replace(old_body, new_body).replace(
        old_hash, hashlib.blake2b(new_body, digest_size=32).digest())
    case(f"bbody/devnet-{DEVNET_TX_BLOCK}-invalid-flag", "bbody", block=flipped.hex(), utxo=genesis_utxo())
    tx = arr(body, wits, aux, flag)
    utxow = {
        "devnet": ({}, tx, genesis_utxo()),
        "no-utxo": ({}, tx, []),
        "fee-floor": ({"minFeeB": PARAMS["minFeeB"] + 1000000}, tx, genesis_utxo()),
        "max-tx-size": ({"maxTxSize": 100}, tx, genesis_utxo()),
        "max-value-size": ({"maxValSize": 1}, tx, genesis_utxo()),
        "coins-per-byte": ({"coinsPerUTxOByte": 10**9}, tx, genesis_utxo()),
        "no-witnesses": ({}, arr(body, cmap([]), aux, flag), genesis_utxo()),
        "invalid-flag": ({}, arr(body, wits, aux, b"\xf4"), genesis_utxo()),
        "max-tx-ex-units-zero": ({"maxTxExUnits": [0, 0]}, tx, genesis_utxo()),
    }
    # A transaction spending an input locked by a native script (an enterprise script address),
    # its fee in a fixed-width head, so its size does not depend on the scripts tried.
    def locked(script):
        return bytes([0x70]) + hashlib.blake2b(b"\x00" + script, digest_size=28).digest()

    def script_tx(lock, provided, coin=10_000_000, fee=1_000_000, out_coin=None, held=None,
                  extra=(), assets=None):
        txin = arr(bstr(bytes([0x11]) * 32), uint(0))
        out = arr(bstr(GENESIS_ADDRESS), uint(coin - fee if out_coin is None else out_coin))
        body = cmap([(uint(0), tagged_set([txin])), (uint(1), arr(out)),
                     (uint(2), b"\x1a" + fee.to_bytes(4, "big"))] + list(extra))
        wits = cmap([(uint(1), tagged_set(provided))]) if provided else cmap([])
        value = uint(coin) if assets is None else arr(uint(coin), assets)
        utxo = [[txin.hex(), arr(bstr(locked(lock)), value).hex()]]
        return arr(body, wits, NULL, b"\xf5"), utxo

    all_of = arr(uint(1), arr())
    any_of = arr(uint(2), arr())
    signature = arr(uint(0), bstr(h28(7)))
    starting = arr(uint(4), uint(1000))
    for name, (lock, provided) in {
        "native-all-of": (all_of, [all_of]),
        "native-missing": (all_of, []),
        "native-extraneous": (all_of, [all_of, any_of]),
        "native-unsigned": (signature, [signature]),
        "native-time-start": (starting, [starting]),
    }.items():
        t, u = script_tx(lock, provided)
        utxow[name] = ({}, t, u)

    for name, (params, t, u) in utxow.items():
        case(f"utxow/{name}", "utxow", params=params, tx=t.hex(), utxo=u, slot=DEVNET_TX_SLOT)

    # A deregistration whose declared refund (3 ada) is not the deposit held (2 ada): Dijkstra's
    # consumed value counts the declared one (`dijkstraTotalRefundsTxCerts`, `Dijkstra/TxCert.hs`).
    # The stake credential is the script that locks the input, which witnesses both.
    script_hash = hashlib.blake2b(b"\x00" + all_of, digest_size=28).digest()
    stake = arr(uint(1), bstr(script_hash))
    registered = [arr(uint(7), stake, uint(DEPOSIT)).hex()]
    dereg = (uint(4), tagged_set([arr(uint(8), stake, uint(3_000_000))]))
    for name, out_coin in {"refund-declared-balanced": 12_000_000,
                           "refund-held-balanced": 11_000_000}.items():
        t, u = script_tx(all_of, [all_of], out_coin=out_coin, extra=[dereg])
        case(f"utxow/{name}", "utxow", tx=t.hex(), utxo=u, slot=DEVNET_TX_SLOT, setup=registered)

    # A burn of two tokens of a policy, of which the input holds one: Mary's consumed value counts
    # the minted tokens and its produced value the burned ones (`Mary/UTxO.hs:72-97`).
    token = cmap([(bstr(script_hash), cmap([(bstr(b"\x01"), uint(1))]))])
    burn = (uint(9), cmap([(bstr(script_hash), cmap([(bstr(b"\x01"), head(1, 1))]))]))
    t, u = script_tx(all_of, [all_of], extra=[burn], assets=token)
    case("utxow/burn-beyond-held", "utxow", tx=t.hex(), utxo=u, slot=DEVNET_TX_SLOT)

    certs = {
        "register": [reg(1)],
        "register-twice": [reg(1), reg(1)],
        "register-deposit-wrong": [reg(1, 3)],
        "unregister-unknown": [unreg(1)],
        "register-unregister": [reg(1), unreg(1)],
        "unregister-refund-wrong": [reg(1), unreg(1, 5)],
        "delegate-unknown-pool": [reg(1), arr(uint(2), key(1), bstr(h28(9)))],
        "delegate-unregistered": [arr(uint(9), key(1), arr(uint(2)))],
        "vote-abstain": [reg(1), arr(uint(9), key(1), arr(uint(2)))],
        "vote-unknown-drep": [reg(1), arr(uint(9), key(1), arr(uint(0), bstr(h28(5))))],
        "register-vote": [arr(uint(12), key(1), arr(uint(3)), uint(DEPOSIT))],
        "drep-register": [reg_drep(2)],
        "drep-register-twice": [reg_drep(2), reg_drep(2)],
        "drep-deposit-wrong": [reg_drep(2, 1)],
        "drep-unregister-unknown": [arr(uint(17), key(2), uint(DREP))],
        "drep-update-unknown": [arr(uint(18), key(2), NULL)],
        "committee-authorize-unknown": [arr(uint(14), key(3), key(4))],
        "committee-resign-unknown": [arr(uint(15), key(3), NULL)],
        "pool-retire-unknown": [arr(uint(4), bstr(h28(8)), uint(2))],
        "mixed-failures": [reg(1), reg(1), reg_drep(2, 1), unreg(7)],
    }
    for name, cs in certs.items():
        case(f"certs/{name}", "certs", certs=[c.hex() for c in cs])

    setup = [reg(1).hex(), reg_drep(2).hex()]

    def votes(voter, ix=0, tx=TX):
        return cmap([(voter, cmap([(arr(bstr(tx), uint(ix)), arr(uint(1), NULL))]))])

    drep_voter = arr(uint(2), bstr(h28(2)))
    gov = {
        "info": dict(proposals=tagged_set([proposal(INFO)])),
        "info-deposit-wrong": dict(proposals=tagged_set([proposal(INFO, deposit=1)])),
        "info-return-unknown": dict(proposals=tagged_set([proposal(INFO, ret=account(5))])),
        "info-return-mainnet": dict(proposals=tagged_set([proposal(INFO, ret=account(1, 1))])),
        "treasury-zero": dict(
            proposals=tagged_set([proposal(arr(uint(2), cmap([(account(1), uint(0))]), NULL))])
        ),
        "treasury-unknown-account": dict(
            proposals=tagged_set([proposal(arr(uint(2), cmap([(account(6), uint(5))]), NULL))])
        ),
        "hard-fork-13": dict(proposals=tagged_set([proposal(arr(uint(1), NULL, arr(uint(13), uint(0))))])),
        "hard-fork-13-1": dict(proposals=tagged_set([proposal(arr(uint(1), NULL, arr(uint(13), uint(1))))])),
        "hard-fork-12": dict(proposals=tagged_set([proposal(arr(uint(1), NULL, arr(uint(12), uint(0))))])),
        "committee-conflicting": dict(
            proposals=tagged_set([
                proposal(arr(uint(4), NULL, tagged_set([key(3)]), cmap([(key(3), uint(1))]),
                             head(6, 30) + arr(uint(1), uint(2))))
            ])
        ),
        "no-confidence": dict(proposals=tagged_set([proposal(arr(uint(3), NULL))])),
        "constitution": dict(proposals=tagged_set([proposal(arr(uint(5), NULL, arr(anchor(), NULL)))])),
        "vote-voter-unknown": dict(votes=votes(arr(uint(2), bstr(h28(7))))),
        "vote-action-unknown": dict(votes=votes(drep_voter)),
        "vote-own-info": dict(proposals=tagged_set([proposal(INFO)]), votes=votes(drep_voter)),
        "two-proposals-vote-second": dict(
            proposals=tagged_set([proposal(INFO), proposal(arr(uint(3), NULL))]),
            votes=votes(drep_voter, ix=1),
        ),
    }
    for name, fields in gov.items():
        case(f"gov/{name}", "gov", setup=setup, **fields)
    names = [c["name"] for c in cases]
    assert len(names) == len(set(names)), "case names must be unique"
    return cases


def genesis_utxo():
    txin = arr(bstr(hashlib.blake2b(GENESIS_ADDRESS, digest_size=32).digest()), uint(0))
    txout = arr(bstr(GENESIS_ADDRESS), uint(GENESIS_COIN))
    return [[txin.hex(), txout.hex()]]


# The parts of a block `[8, [header, body]]`, `header = [header_body, body_signature]`, as paths of
# `cborspan`, whose walker keeps an explicit stack, so a nesting of any depth in a block's
# transactions does not reach Python's recursion limit.
HEADER_BODY = [1, 0, 0]
BODY = [1, 1]
TRANSACTIONS = [1, 1, 0]


def _bytes(b, path):
    ((start, end),) = cborspan.resolve(b, path)
    return b[start:end]


def devnet_tx(block):
    """The parts of the one transaction of a block, `[body, witnesses, auxiliary data, is_valid]`
    as a Dijkstra body holds it (cardano-ledger 9df9f98c `Dijkstra/Tx.hs:175-182`)."""
    (tx,) = cborspan.resolve(block, TRANSACTIONS + ["*"])
    return [block[s:e] for s, e in cborspan.children(block, tx[0])]


def block_body(block):
    """The body of a `[8, [header, body]]` block, as its bytes."""
    return _bytes(block, BODY)


def with_body_size(block, delta):
    """The block with its header's `block_body_size` (field 6 of the header body) changed by
    `delta`."""
    ((start, end),) = cborspan.resolve(block, HEADER_BODY + [6])
    major, n, _ = cborspan.head(block, start)
    assert major == 0
    return block[:start] + uint(n + delta) + block[end:]


def declared_body_hash(block):
    """The header's `block_body_hash`: field 7 of the header body."""
    (span,) = cborspan.resolve(block, HEADER_BODY + [7])
    digest = cborspan.payload(block, span)
    assert len(digest) == 32
    return digest.hex()


def run_image(cases):
    with tempfile.TemporaryDirectory() as scratch:
        (Path(scratch) / "in.json").write_text(json.dumps(cases))
        done = subprocess.run(
            ["docker", "run", "--rm", "--network", "none", "--read-only", "-v", f"{scratch}:/in:ro", IMAGE, "/in/in.json"],
            check=True, capture_output=True)
    results = json.loads(done.stdout)
    return [dict(c, reference=r["reference"]) for c, r in zip(cases, results, strict=True)]


def sha256(path):
    return hashlib.sha256((ROOT / path).read_bytes()).hexdigest()


def recorded():
    return [json.loads(line) for line in CASES_FILE.read_text().splitlines() if line]


def strip(c):
    return {k: v for k, v in c.items() if k != "reference"}


def main():
    parser = argparse.ArgumentParser()
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--record", action="store_true")
    mode.add_argument("--replay", action="store_true")
    args = parser.parse_args()
    cases = generate()
    if args.record:
        results = run_image(cases)
        FIXTURE.mkdir(parents=True, exist_ok=True)
        CASES_FILE.write_text("".join(json.dumps(r, sort_keys=True) + "\n" for r in results))
        image = subprocess.check_output(["docker", "image", "inspect", IMAGE, "--format", "{{.Id}}"], text=True).strip()
        PROVENANCE.write_text(json.dumps({
            "schemaVersion": 1,
            "scope": "Dijkstra's BBODY, UTXOW (with UTXO and UTXOS), CERTS (with Dijkstra's CERT, POOL and "
                     "GOVCERT) and GOV of the Leios testnet build, each applied on its own with every check "
                     "run: BBODY over the devnet's Dijkstra blocks, UTXOW over the devnet transaction and its "
                     "variants, CERTS on the default certificate state, GOV after a CERTS setup on the "
                     "default store of proposals; not LEDGER on its own, ENTITIES or the SUB rules",
            "image": IMAGE,
            "imageId": image,
            "ledger": {"repository": "IntersectMBO/cardano-ledger",
                       "revision": "9df9f98cce86c5f2195142b2c02dd9b39dd9dc7c",
                       "packages": ["cardano-ledger-dijkstra 0.5.0.0"]},
            "program": PROGRAM,
            "programSha256": sha256(PROGRAM),
            "blocksSha256": sha256(BLOCKS.relative_to(ROOT)),
            "cases": len(results),
            "casesSha256": sha256(CASES_FILE.relative_to(ROOT)),
            "measuredAt": datetime.date.today().isoformat(),
        }, indent=2, sort_keys=True) + "\n")
        print(f"recorded {len(results)} cases")
        return 0
    if [strip(c) for c in recorded()] != cases:
        print("the generated cases differ from the fixture: run --record", file=sys.stderr)
        return 1
    if args.replay:
        fresh = run_image(cases)
        differing = [f["name"] for f, r in zip(fresh, recorded(), strict=True) if f["reference"] != r["reference"]]
        if differing:
            print(f"{len(differing)} results differ: {differing[:5]}", file=sys.stderr)
            return 1
    print(f"{len(cases)} cases agree")
    return 0


if __name__ == "__main__":
    sys.exit(main())
