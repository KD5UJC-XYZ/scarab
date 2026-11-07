#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Reference upgrade of Conway outputs into Dijkstra by the Leios testnet build (#54).

Each case is the CBOR of a Conway output, most with a native reference script written in a form the
Conway decoder accepts but Dijkstra's `upgradeTimelock` does not keep: indefinite or definite lists
on the wrong side of 23 elements, integers in longer heads than needed, a deep nesting. The oracle
decodes the output in Conway, applies Dijkstra's `upgradeTxOut` and records both outputs, the
reference script's bytes and its hash before and after.

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
FIXTURE = ROOT / "fixtures/reference/dijkstra-translation"
CASES_FILE = FIXTURE / "cases.jsonl"
PROVENANCE = FIXTURE / "provenance.json"
IMAGE = "scarab-dijkstra-translation-oracle:local"
PROGRAM = "scripts/oracle/dijkstra-translation/Main.hs"


def head(major, n, width=None):
    """A CBOR head; `width` forces a longer argument (1, 2, 4 or 8 bytes) than the shortest."""
    m = major << 5
    if width is None:
        if n < 24:
            return bytes([m | n])
        width = 1 if n < 0x100 else 2 if n < 0x10000 else 4 if n < 0x100000000 else 8
    code = {1: 24, 2: 25, 4: 26, 8: 27}[width]
    return bytes([m | code]) + n.to_bytes(width, "big")


def bstr(b, width=None):
    return head(2, len(b), width) + b


def keyhash(i):
    return bytes([i]) * 28


def sig(i, tag=b"\x00"):
    return b"\x82" + tag + bstr(keyhash(i))


def definite(items):
    return head(4, len(items)) + b"".join(items)


def indefinite(items):
    return b"\x9f" + b"".join(items) + b"\xff"


def all_of(lst):
    return b"\x82\x01" + lst


def any_of(lst):
    return b"\x82\x02" + lst


def n_of_k(m, lst):
    return b"\x83\x03" + m + lst


def output(script=None, datum=None):
    """A map-form output to an enterprise address, of one ada, with an optional inline datum and
    reference script (`#6.24` of the script's CBOR); the legacy array when it has neither."""
    address = bstr(b"\x61" + bytes(28))
    coin = head(0, 1_000_000)
    if script is None and datum is None:
        return b"\x82" + address + coin
    entries = [b"\x00" + address, b"\x01" + coin]
    if datum is not None:
        entries.append(b"\x02\x82\x01" + head(6, 24) + bstr(datum))
    if script is not None:
        entries.append(b"\x03" + head(6, 24) + bstr(script))
    return head(5, len(entries)) + b"".join(entries)


def native(n):
    return b"\x82\x00" + n


def deep(depth):
    inner = sig(1)
    for _ in range(depth):
        inner = all_of(indefinite([inner]))
    return inner


def generate():
    sigs24 = [sig(i) for i in range(24)]
    sigs23 = [sig(i) for i in range(23)]
    scripts = {
        "signature": native(sig(1)),
        "allOf/indefinite-short": native(all_of(indefinite([sig(1), sig(2)]))),
        "allOf/definite-24": native(all_of(definite(sigs24))),
        "anyOf/indefinite-24": native(any_of(indefinite(sigs24))),
        "anyOf/definite-23": native(any_of(definite(sigs23))),
        "anyOf/indefinite-empty": native(any_of(indefinite([]))),
        "nOfK/m-long-head": native(n_of_k(head(0, 2, 1), definite([sig(1), sig(2)]))),
        "nOfK/m-negative": native(n_of_k(head(1, 0), definite([]))),
        "nOfK/m-negative-long-head": native(n_of_k(head(1, 4, 8), definite([sig(3)]))),
        "timeStart/slot-long-head": native(b"\x82\x04" + head(0, 5, 4)),
        "timeExpire/slot-past-word32": native(b"\x82\x05" + head(0, 2**32)),
        "timeExpire/slot-word64-max": native(b"\x82\x05" + head(0, 2**64 - 1)),
        "signature/tag-long-head": native(b"\x82" + head(0, 0, 1) + bstr(keyhash(1))),
        "signature/keyhash-long-head": native(b"\x82\x00" + bstr(keyhash(1), 2)),
        "nested/mixed": native(all_of(indefinite([
            any_of(indefinite([sig(1), b"\x82\x04" + head(0, 7, 2)])),
            n_of_k(head(0, 1, 1), definite([sig(2), sig(3)])),
        ]))),
        "nested/depth-1000": native(deep(1000)),
        "plutusV2": b"\x82\x02" + bstr(bytes.fromhex("4d01000033222220051200120011")),
    }
    cases = [{"name": f"refScript/{name}", "txOut": output(script).hex()} for name, script in scripts.items()]
    cases.append({"name": "noScript", "txOut": output().hex()})
    cases.append({
        "name": "inlineDatum/allOf-indefinite",
        "txOut": output(native(all_of(indefinite([sig(4)]))), datum=b"\x9f\x01\xff").hex(),
    })
    names = [c["name"] for c in cases]
    assert len(names) == len(set(names)), "case names must be unique"
    return cases


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
            "scope": "Dijkstra's upgradeTxOut of the Leios testnet build on Conway outputs, most with a native "
                     "reference script in a form upgradeTimelock does not keep: the Conway output decoded, the "
                     "upgraded output's CBOR and the reference script's bytes and hash before and after; not the "
                     "rest of the NewEpochState translation",
            "image": IMAGE,
            "imageId": image,
            "ledger": {"repository": "IntersectMBO/cardano-ledger",
                       "revision": "9df9f98cce86c5f2195142b2c02dd9b39dd9dc7c",
                       "packages": ["cardano-ledger-dijkstra 0.5.0.0"]},
            "program": PROGRAM,
            "programSha256": sha256(PROGRAM),
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
