#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Reference verdicts of the Dijkstra BBODY, CERTS and GOV rules of the Leios testnet build (#54).

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
}


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


def declared_body_hash(block):
    """The header's `block_body_hash`: field 7 of the header body, read with a small CBOR walker."""
    def item(b, i):
        ib = b[i]
        major, info = ib >> 5, ib & 31
        i += 1
        if info < 24:
            n = info
        else:
            width = {24: 1, 25: 2, 26: 4, 27: 8}[info]
            n = int.from_bytes(b[i:i + width], "big")
            i += width
        return major, n, i

    def skip(b, i):
        major, n, i = item(b, i)
        if major in (2, 3):
            return i + n
        if major == 4:
            for _ in range(n):
                i = skip(b, i)
            return i
        if major == 5:
            for _ in range(2 * n):
                i = skip(b, i)
            return i
        if major == 6:
            return skip(b, i)
        return i

    _, _, i = item(block, 0)          # [tag, block]
    _, _, i = item(block, i)          # 8
    _, _, i = item(block, i)          # [header, body]
    _, _, i = item(block, i)          # [header_body, signature]
    _, _, i = item(block, i)          # header_body, 12 fields
    for _ in range(7):
        i = skip(block, i)
    major, n, i = item(block, i)
    assert major == 2 and n == 32
    return block[i:i + 32].hex()


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
            "scope": "Dijkstra's BBODY, CERTS (with Dijkstra's CERT, POOL and GOVCERT) and GOV of the Leios "
                     "testnet build, each applied on its own with every check run: BBODY over the devnet's "
                     "Dijkstra blocks on the default ledger state, CERTS on the default certificate state, "
                     "GOV after a CERTS setup on the default store of proposals; not LEDGER, UTXOW, UTXO, "
                     "ENTITIES or the SUB rules",
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
