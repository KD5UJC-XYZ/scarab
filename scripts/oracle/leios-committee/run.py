#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Compare Leios committee selection with the pinned Leios ledger build; --record captures native results."""
import argparse
import datetime
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts"))
from cleanup import container_labels

HERE = Path(__file__).resolve().parent
FIXTURE = ROOT / "fixtures/reference/leios-committee"
IMAGE = "scarab-leios-testnet:local"
LEDGER = "9df9f98cce86c5f2195142b2c02dd9b39dd9dc7c"
BASE = "c1e1ed8d7ae39c111c73fc5f59ac37ce5933b83c"
WORD64 = (1 << 64) - 1


def head(major, n):
    if n < 24:
        return bytes([(major << 5) | n])
    width = next(w for w in (1, 2, 4, 8) if n < 1 << (8 * w))
    return bytes([(major << 5) | {1: 24, 2: 25, 4: 26, 8: 27}[width]]) + n.to_bytes(width, "big")


def array(*items):
    return head(4, len(items)) + b"".join(items)


def bstr(value):
    return head(2, len(value)) + value


def mapping(entries):
    return head(5, len(entries)) + b"".join(k + v for k, v in entries)


def ratio(numerator, denominator):
    return b"\xd8\x1e" + array(head(0, numerator), head(0, denominator))


KEYS = [line.split("\t") for line in (ROOT / "fixtures/leios/bls/devnet-pools.tsv").read_text().splitlines()
        if line and not line.startswith("#")]


def key(i, proof_of=None):
    """The devnet pool i's verification key with its own proof, or with pool proof_of's proof."""
    return array(bstr(bytes.fromhex(KEYS[i][2])), bstr(bytes.fromhex(KEYS[i if proof_of is None else proof_of][3])))


def pool_id(first, fill=1):
    return bytes([first]) + bytes([fill]) * 27


def pool(stake, *, bls=None, registered=0, delegators=1, weight=(1, 3)):
    """A Leios-layout StakePoolSnapShot, array(11) (ledger 9df9f98c State/SnapShots.hs:276-292)."""
    return array(
        head(0, stake), ratio(*weight), b"\xd9\x01\x02\x80", b"\x00", bstr(bytes([3]) * 32),
        array(array(bls, head(0, registered))) if bls is not None else array(),
        b"\x00", b"\x00", ratio(1, 3), head(0, delegators), array(b"\x00", bstr(bytes([2]) * 28)))


def snapshot(pools):
    active = mapping([(array(b"\x00", bstr(bytes([2]) * 28)), array(head(0, 210), bstr(pool_id(1))))])
    return array(active, mapping(sorted((bstr(i), p) for i, p in pools)))


def snapshots(pools, *, epoch=7, size=3, age=10):
    """SnapShots in the Leios layout: mark [snapshot, epoch, size, age], set [snapshot, committee],
    go [snapshot], fee (State/SnapShots.hs:414-432, 485-505, 525-535, 576-582)."""
    empty = array(b"\xa0", b"\xa0")
    mark = array(snapshot(pools), head(0, epoch), head(0, size), head(0, age))
    return array(mark, array(empty, b"\x80"), array(empty), b"\x00")


def cases():
    a, b, c = pool_id(0x10), pool_id(0x20), pool_id(0x30)
    keyed = [(a, pool(300, bls=key(0))), (b, pool(200, bls=key(1))), (c, pool(100, bls=key(2)))]
    rows = []

    def add(name, raw):
        rows.append({"name": name, "cbor": raw.hex()})

    add("size-zero", snapshots(keyed, size=0))
    add("no-pools", snapshots([], size=5))
    add("keys-seated-by-stake", snapshots(keyed))
    add("size-below-pools", snapshots(keyed, size=2))
    add("size-above-pools", snapshots(keyed, size=10))
    add("size-word16-maximum", snapshots(keyed, size=0xFFFF))
    add("size-above-word16", snapshots(keyed, size=0x10000))
    add("age-word32-maximum", snapshots(keyed, age=0xFFFFFFFF))
    add("age-above-word32", snapshots(keyed, age=0x100000000))
    add("equal-stake-by-pool-id",
        snapshots([(pool_id(0x80), pool(5, bls=key(0))), (pool_id(0x7F), pool(5, bls=key(1))),
                   (pool_id(0x01), pool(5, bls=key(2)))]))
    add("stake-unsigned-order",
        snapshots([(a, pool((1 << 63) - 1, bls=key(0))), (b, pool(1 << 63, bls=key(1))), (c, pool(WORD64, bls=key(2)))]))
    add("zero-stake-and-no-delegators",
        snapshots([(a, pool(0, bls=key(0), delegators=0)), (b, pool(9, bls=key(1), delegators=0)), (c, pool(0))]))
    add("absent-key", snapshots([(a, pool(300)), (b, pool(200, bls=key(1)))]))
    add("proof-of-another-key", snapshots([(a, pool(300, bls=key(0, proof_of=1))), (b, pool(200, bls=key(1)))]))
    add("key-without-valid-encoding", snapshots([(a, pool(300, bls=array(bstr(bytes(96)), bstr(bytes(48)))))]))
    # The committee governs mark epoch + 1; a key is honoured while that epoch < registered + age.
    add("key-age-last-honoured", snapshots([(a, pool(300, bls=key(0), registered=5))], epoch=13, age=10))
    add("key-age-first-aged-out", snapshots([(a, pool(300, bls=key(0), registered=5))], epoch=14, age=10))
    add("key-age-zero", snapshots([(a, pool(300, bls=key(0), registered=8))], epoch=7, age=0))
    add("key-registered-after-the-epoch", snapshots([(a, pool(300, bls=key(0), registered=50))], epoch=7, age=1))
    add("key-age-sum-wraps", snapshots([(a, pool(300, bls=key(0), registered=WORD64 - 1))], epoch=7, age=5))
    add("mark-epoch-maximum-wraps", snapshots([(a, pool(300, bls=key(0), registered=0))], epoch=WORD64, age=1))
    add("mark-epoch-maximum-age-zero", snapshots([(a, pool(300, bls=key(0), registered=1))], epoch=WORD64, age=0))
    add("weights-as-stored",
        snapshots([(a, pool(300, bls=key(0), weight=(2, 4))), (b, pool(200, bls=key(1), weight=(0, 1))),
                   (c, pool(100, bls=key(2), weight=(3, 2)))]))
    return rows


# The pinned sources the native result depends on, hashed inside the image at record time; each hash
# was compared with the file at its pinned upstream revision (ledger LEDGER, cardano-base BASE).
SOURCES = {
    "LeiosCommittee.hs": "*/libs/cardano-ledger-core/src/Cardano/Ledger/State/LeiosCommittee.hs",
    "SnapShots.hs": "*/libs/cardano-ledger-core/src/Cardano/Ledger/State/SnapShots.hs",
    "Leios.hs": "*/cardano-crypto-leios/src/Cardano/Crypto/Leios.hs",
    "BLS12381/Internal.hs": "*/cardano-crypto-class/src/Cardano/Crypto/DSIGN/BLS12381/Internal.hs",
}


def source_hashes(name):
    script = "; ".join(f"sha256sum $(find /oracle/leios-testnet/dist-newstyle/src -path '{pattern}')"
                       for pattern in SOURCES.values())
    output = subprocess.check_output(
        ["docker", "run", "--rm", "--name", name, *container_labels("oracle", name), "--network", "none",
         "--entrypoint", "sh", IMAGE, "-c", script], text=True).splitlines()
    if len(output) != len(SOURCES):
        raise SystemExit(f"expected one copy of each pinned source in the image, found {output}")
    return {label: line.split()[0] for label, line in zip(SOURCES, output, strict=True)}


def native(rows, name):
    with tempfile.TemporaryDirectory(prefix="scarab-leios-committee-") as tmp:
        inputs = Path(tmp) / "inputs.jsonl"
        inputs.write_text("".join(json.dumps(row) + "\n" for row in rows))
        cmd = ["docker", "run", "--rm", "--name", name, *container_labels("oracle", name),
               "--network", "none", "--cpus", "2", "--memory", "4g", "--tmpfs", "/build:exec",
               "-v", f"{HERE}:/probe:ro", "-v", f"{inputs}:/inputs.jsonl:ro", "--entrypoint", "sh", IMAGE, "-c",
               "cabal exec -- ghc -Wall -Werror -L/usr/local/lib -optl-Wl,-rpath,/usr/local/lib "
               "-package cardano-ledger-core -package cardano-ledger-binary -package cardano-ledger-dijkstra "
               "-package aeson -package base16-bytestring -package text -outputdir /build /probe/Main.hs "
               "-o /build/oracle >&2 && /build/oracle < /inputs.jsonl"]
        result = subprocess.run(cmd, capture_output=True, text=True)
        if result.returncode:
            raise SystemExit(result.stderr)
        outputs = [json.loads(line) for line in result.stdout.splitlines()]
    for row, output in zip(rows, outputs, strict=True):
        if row["name"] != output["name"]:
            raise SystemExit("native result order changed")
    return [{**row, "reference": output["reference"]} for row, output in zip(rows, outputs, strict=True)]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--record", action="store_true")
    parser.add_argument("--name", default="leios-committee-reference")
    args = parser.parse_args()
    rows = native(cases(), args.name)
    path = FIXTURE / "cases.jsonl"
    if args.record:
        FIXTURE.mkdir(parents=True, exist_ok=True)
        path.write_text("".join(json.dumps(row, sort_keys=True) + "\n" for row in rows))
        provenance = {
            "schemaVersion": 1,
            "capturedAt": datetime.datetime.now(datetime.timezone.utc).date().isoformat(),
            "scope": "Native SnapShots decoding at protocol 12 with the mark snapshot's memoized Leios committee "
                     "(mkMarkSnapShot, selectLeiosCommittee, mkLeiosCommittee) and its rotation into the set "
                     "position (mkSetSnapShot): exact accepted bytes and acceptance parity; not SNAP, the key-age "
                     "derivation (maxKeyAgeEpochs), genesis or epoch transitions.",
            "build": "leios-b004fe0c", "image": IMAGE, "ledgerSourceCommit": LEDGER, "cardanoBase": BASE,
            "imageId": subprocess.check_output(["docker", "image", "inspect", IMAGE, "--format", "{{.Id}}"],
                                               text=True).strip(),
            "cases": len(rows),
            "sourceSha256": source_hashes(args.name),
            "harnessSha256": hashlib.sha256((HERE / "Main.hs").read_bytes()).hexdigest(),
            "casesSha256": hashlib.sha256(path.read_bytes()).hexdigest(),
        }
        (FIXTURE / "provenance.json").write_text(json.dumps(provenance, indent=2) + "\n")
    else:
        expected = [json.loads(line) for line in path.read_text().splitlines()]
        if rows != expected:
            raise SystemExit("native committees differ from the recorded corpus")
    print(f"Compared {len(rows)} native Leios committee results")


if __name__ == "__main__":
    main()
