#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Compare pool distributions with both pinned ledger builds; --record captures native results."""
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
FIXTURE = ROOT / "fixtures/reference/pool-distribution"
BUILDS = {
    "11.1": ("scarab-plutus176-context-reference:local", "f649f9751074d2ab3de033fc3912f29c9862c1f5"),
    "leios-b004fe0c": ("scarab-leios-testnet:local", "9df9f98cce86c5f2195142b2c02dd9b39dd9dc7c"),
}


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


def cases(build):
    leios = build != "11.1"
    pool = bstr(bytes([1]) * 28)
    credential = array(b"\x00", bstr(bytes([2]) * 28))
    vrf = bstr(bytes([3]) * 32)
    ratio = b"\xd8\x1e" + array(b"\x01", b"\x03")
    keys = [line.split("\t") for line in (ROOT / "fixtures/leios/bls/devnet-pools.tsv").read_text().splitlines()
            if line and not line.startswith("#")]
    key = array(bstr(bytes.fromhex(keys[0][2])), bstr(bytes.fromhex(keys[0][3])))
    other_proof = array(bstr(bytes.fromhex(keys[0][2])), bstr(bytes.fromhex(keys[1][3])))
    rows = []

    def add(name, kind, raw):
        rows.append({"name": name, "kind": kind, "cbor": raw.hex(), "build": build})

    add("empty", "snapshot", array(b"\xa0", b"\xa0"))
    variants = [("no-key", None, 1, 0), ("no-delegators", None, 0, 0), ("stored-ratio", None, 1, 0)]
    if leios:
        variants += [("key", key, 1, 0), ("old-key", key, 1, 123456),
                     ("key-without-delegators", key, 0, 0), ("unverified-proof", other_proof, 1, 0)]
    for name, bls, count, epoch in variants:
        stored_ratio = b"\xd8\x1e" + array(b"\x01", b"\x02") if name == "stored-ratio" else ratio
        fields = [head(0, 70), stored_ratio, b"\xd9\x01\x02\x80", b"\x00", vrf]
        if leios:
            fields.append(array(array(bls, head(0, epoch))) if bls else array())
        fields += [b"\x00", b"\x00", ratio, head(0, count), credential]
        active = mapping([(credential, array(head(0, 210), pool))])
        add(name, "snapshot", array(active, mapping([(pool, array(*fields))])))
    base = [ratio, head(0, 70), vrf]
    for name, fields in [("absent", base), ("empty-fourth", base + [array()]),
                         ("present", base + [array(key)])]:
        for indefinite in (False, True):
            raw = b"\x9f" + b"".join(fields) + b"\xff" if indefinite else array(*fields)
            add(name + ("-indefinite" if indefinite else "-definite"), "individual", raw)
    return rows


def native(build, rows, name):
    image, _ = BUILDS[build]
    with tempfile.TemporaryDirectory(prefix="scarab-pool-distribution-") as tmp:
        inputs = Path(tmp) / "inputs.jsonl"
        inputs.write_text("".join(json.dumps(row) + "\n" for row in rows))
        cmd = ["docker", "run", "--rm", "--name", name, *container_labels("oracle", name),
               "--network", "none", "--cpus", "2", "--memory", "4g", "--tmpfs", "/build:exec",
               "-v", f"{HERE}:/probe:ro", "-v", f"{inputs}:/inputs.jsonl:ro", "--entrypoint", "sh", image, "-c",
               "cabal exec -- ghc -Wall -Werror -L/usr/local/lib -optl-Wl,-rpath,/usr/local/lib "
               "-package cardano-ledger-core -package cardano-ledger-binary -package aeson "
               "-package base16-bytestring -package text -outputdir /build /probe/Main.hs "
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
    parser.add_argument("--name", default="pool-distribution-reference")
    args = parser.parse_args()
    rows = [row for build in BUILDS for row in native(build, cases(build), args.name)]
    path = FIXTURE / "cases.jsonl"
    if args.record:
        FIXTURE.mkdir(parents=True, exist_ok=True)
        path.write_text("".join(json.dumps(row, sort_keys=True) + "\n" for row in rows))
        provenance = {"schemaVersion": 1, "capturedAt": datetime.datetime.now(datetime.timezone.utc).date().isoformat(),
                      "scope": "Native calculatePoolDistr and IndividualPoolStake decoding/encoding at protocol 12; exact accepted bytes and acceptance parity, not ledger transitions or committee selection.",
                      "ledgerSourceCommit": BUILDS["11.1"][1], "cases": len(rows),
                      "builds": {build: {"image": image, "ledger": pin,
                                 "imageId": subprocess.check_output(["docker", "image", "inspect", image,
                                                                    "--format", "{{.Id}}"], text=True).strip()}
                                 for build, (image, pin) in BUILDS.items()},
                      "harnessSha256": hashlib.sha256((HERE / "Main.hs").read_bytes()).hexdigest(),
                      "casesSha256": hashlib.sha256(path.read_bytes()).hexdigest()}
        (FIXTURE / "provenance.json").write_text(json.dumps(provenance, indent=2) + "\n")
    else:
        expected = [json.loads(line) for line in path.read_text().splitlines()]
        if rows != expected:
            raise SystemExit("native pool distributions differ from the recorded corpus")
    print(f"Compared {len(rows)} native pool-distribution results across both builds")


if __name__ == "__main__":
    main()
