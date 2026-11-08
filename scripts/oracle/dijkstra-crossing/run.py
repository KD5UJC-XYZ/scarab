#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Reference translation of Conway transactions into Dijkstra by the Leios testnet build (#54).

Each case is a whole Conway transaction in its mempool form, `[body, witnesses, is_valid, auxiliary
data / nil]`. The oracle decodes it at Conway's version and carries it into Dijkstra in the two ways
the build has: the ledger's `TranslateEra DijkstraEra (Tx TopTx)`, which decodes each part again at
Dijkstra's version, and the hard-fork mempool's `rematchValidatedTxs`, which writes the transaction
with Conway's `toCBOR` and reads it whole with Dijkstra's decoder.

The cases are the transactions of the Preview Conway blocks in `fixtures/preview` as their blocks
hold them, Conway bodies and witness sets the Conway decoder
accepts from `fixtures/reference/conway-decoding`, each beside the other's baseline, and the baseline
body with an empty witness set and its flag false.

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

FIXTURE = ROOT / "fixtures/reference/dijkstra-crossing"
CASES_FILE = FIXTURE / "cases.jsonl"
PROVENANCE = FIXTURE / "provenance.json"
IMAGE = "scarab-dijkstra-crossing-oracle:local"
PROGRAM = "scripts/oracle/dijkstra-crossing/Main.hs"
BLOCKS = ["fixtures/preview/conway.hex", "fixtures/preview/conway-governance.hex"]
DECODING = "fixtures/reference/conway-decoding/cases.json"


def envelope(body, witnesses, valid, aux):
    """The Conway mempool form of a transaction's parts (`toCBORForMempoolSubmission`)."""
    return b"\x84" + body + witnesses + (b"\xf5" if valid else b"\xf4") + (aux if aux is not None else b"\xf6")


def unsigned(b, span):
    major, value, _ = cborspan.head(b, span[0])
    assert major == 0, f"not an unsigned integer at {span[0]}"
    return value


def block_transactions(raw):
    """The transactions of a Conway block `[7, [header, bodies, witnesses, auxiliary, invalid]]`, each with
    its parts' original bytes and the flag its block gives it."""
    _, block = cborspan.children(raw, 0)
    _, bodies, witnesses, auxiliary, invalid = cborspan.children(raw, block[0])
    kids = cborspan.children(raw, auxiliary[0])
    aux = {unsigned(raw, kids[i]): raw[kids[i + 1][0]:kids[i + 1][1]] for i in range(0, len(kids), 2)}
    bad = {unsigned(raw, s) for s in cborspan.children(raw, invalid[0])}
    pairs = zip(cborspan.children(raw, bodies[0]), cborspan.children(raw, witnesses[0]), strict=True)
    return [
        (raw[b[0]:b[1]], raw[w[0]:w[1]], i not in bad, aux.get(i))
        for i, (b, w) in enumerate(pairs)
    ]


def blake2b_256(data):
    return hashlib.blake2b(data, digest_size=32).hexdigest()


def generate():
    cases = []
    for path in BLOCKS:
        for line in (ROOT / path).read_text().split():
            for body, wits, valid, aux in block_transactions(bytes.fromhex(line)):
                name = f"preview/{blake2b_256(body)}"
                if all(c["name"] != name for c in cases):
                    cases.append({"name": name, "tx": envelope(body, wits, valid, aux).hex()})
    decoding = json.loads((ROOT / DECODING).read_text())
    accepted = [c for c in decoding if c["era"] == "conway" and c["expect"] is None]
    baseline = {c["kind"]: bytes.fromhex(c["bytes"]) for c in accepted if c["name"].endswith(" baseline")}
    empty_witnesses = b"\xa0"
    cases.append({"name": "flag/false", "tx": envelope(baseline["body"], empty_witnesses, False, None).hex()})
    for c in accepted:
        raw = bytes.fromhex(c["bytes"])
        if c["kind"] == "body":
            tx = envelope(raw, empty_witnesses, True, None)
        elif c["kind"] == "witnesses":
            tx = envelope(baseline["body"], raw, True, None)
        else:
            tx = raw
        cases.append({"name": f"decoding/{c['id']}", "tx": tx.hex()})
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
            "scope": "Conway transactions carried into Dijkstra by the Leios testnet build: the ledger's "
                     "TranslateEra DijkstraEra (Tx TopTx) and the hard-fork mempool's rematchValidatedTxs, "
                     "each part's bytes, the flag, the id and the mempool form, or the refusal and the part "
                     "it names; not the mempool's reapplication of the translated transaction",
            "image": IMAGE,
            "imageId": image,
            "ledger": {"repository": "IntersectMBO/cardano-ledger",
                       "revision": "9df9f98cce86c5f2195142b2c02dd9b39dd9dc7c",
                       "packages": ["cardano-ledger-dijkstra 0.5.0.0"]},
            "consensus": {"repository": "IntersectMBO/ouroboros-consensus",
                          "revision": "2416493bfdbe93b761b3a4bb1ccf2b4a913e5e4b"},
            "program": PROGRAM,
            "programSha256": sha256(PROGRAM),
            "inputs": {path: sha256(path) for path in BLOCKS + [DECODING]},
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
