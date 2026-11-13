#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Writes the original encodings of the era decoders corpus.

Each case is a part an era's decoder reads in one form and its neighbour's in another:
- a certificate of each tag in a body of Shelley to Babbage, which read `ShelleyTxCert` (tags 0 to
  6), and Conway's tags 5, 6 and 7 (`ConwayTxCert`);
- a native script with each time-lock tag in a witness set of Shelley (`MultiSig`, tags 0 to 3) and
  Allegra (`Timelock`, tags 0 to 5);
- a block body of Mary to Conway whose auxiliary data map names an index once, twice, or two
  indices out of order (`decodeIntMap`, which refuses a repeated key from decoder version 9);
- a block body of Alonzo to Conway whose invalid-transaction list is increasing, repeats an index
  or decreases (`alignedValidFlags`).

The harness (`Main.hs`) reads every case of an input file in one process. A list that repeats an
index or decreases makes it end with an exception instead of a verdict, so each of those is written
to an input file of its own; the others go together in `batch.json`. Three lines of the detached
records corpus are replayed beside them as controls, with their committed verdicts in
`controls-expected.json`.

The committed corpus is `fixtures/reference/era-decoders/`: the input files under `inputs/`, and
`reference-verdicts.jsonl`, a line per case with the verdict. Two steps write them: `--inputs`
writes the input files, and `--outputs` reads the harness output for each (`batch.json`, and the
standard error of each single case, whose output is empty) into the lines:

    python3 scripts/oracle/conway-decoding/era_decoder_cases.py --inputs /tmp/era-decoders/inputs
    # run the harness on each input file, writing /tmp/era-decoders/out/<name>.json and .stderr
    python3 scripts/oracle/conway-decoding/era_decoder_cases.py --inputs /tmp/era-decoders/inputs \\
        --outputs /tmp/era-decoders/out --out /tmp/era-decoders/reference-verdicts.jsonl
"""
import argparse
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", "..", ".."))
sys.path.insert(0, HERE)
import cases as c  # noqa: E402  (the CBOR helpers)
import definite_bytes_cases as d  # noqa: E402  (the era bodies)

PRE = ["shelley", "allegra", "mary", "alonzo", "babbage"]
CRED = c.array([c.uint(0), c.bstr(c.KEY_HASH)])
CRED2 = c.array([c.uint(0), c.bstr(c.h(0x34, 28))])
POOL = c.bstr(c.h(0x61, 28))
ABSTAIN = c.array([c.uint(2)])
COIN = c.uint(2_000_000)
AUX = c.cmap([(c.uint(674), c.text("x"))])

CERTS = {
    0: [CRED],
    7: [CRED, COIN],
    8: [CRED, COIN],
    9: [CRED, ABSTAIN],
    10: [CRED, POOL, ABSTAIN],
    11: [CRED, POOL, COIN],
    12: [CRED, ABSTAIN, COIN],
    13: [CRED, POOL, ABSTAIN, COIN],
    14: [CRED, CRED2],
    15: [CRED, c.NULL],
    16: [CRED, COIN, c.NULL],
    17: [CRED, COIN],
    18: [CRED, c.NULL],
}

batch = []
singles = []


def case(store, era, kind, name, raw):
    store.append({"name": f"{era} {kind} {name}", "era": era, "kind": kind, "bytes": raw.hex(), "ids": True})


def cert(tag, fields):
    return c.array([c.uint(tag)] + fields)


# Certificate tags in a body, Shelley to Babbage; Conway's tags 5 and 6 beside its 7.
for era in PRE:
    for tag, fields in CERTS.items():
        case(batch, era, "body", f"certificate tag {tag}", d.body(era, {4: c.array([cert(tag, fields)])}))
for tag, fields in {
    5: [c.bstr(c.h(0x71, 28)), c.bstr(c.h(0x72, 28)), c.bstr(c.h(0x73, 32))],
    6: [c.array([c.uint(0), c.uint(1000)])],
    7: [CRED, COIN],
}.items():
    case(batch, "conway", "body", f"certificate tag {tag}", d.body("conway", {4: c.array([cert(tag, fields)])}))

# Time-lock native scripts in a witness set; Allegra reads them.
for era in ["shelley", "allegra"]:
    for name, script in [
        ("native script tag 0", c.array([c.uint(0), c.bstr(c.KEY_HASH)])),
        ("native script tag 4", c.array([c.uint(4), c.uint(100)])),
        ("native script tag 5", c.array([c.uint(5), c.uint(100)])),
        ("native script all-of with tag 5", c.array([c.uint(1), c.array([c.array([c.uint(5), c.uint(100)])])])),
    ]:
        case(batch, era, "witnesses", name, c.cmap([(c.uint(1), c.array([script]))]))


def block_body(era, n, aux, invalid=None):
    parts = [c.array([d.body(era)] * n), c.array([c.cmap([])] * n), aux]
    if era in ("alonzo", "babbage", "conway"):
        parts.append(c.array([c.uint(i) for i in (invalid or [])]))
    return b"".join(parts)


# The auxiliary data map of a block body.
for era in ["mary", "alonzo", "babbage", "conway"]:
    case(batch, era, "blockbody", "aux index 0 once", block_body(era, 1, c.cmap([(c.uint(0), AUX)])))
    case(batch, era, "blockbody", "aux index 0 twice", block_body(era, 1, c.cmap([(c.uint(0), AUX), (c.uint(0), AUX)])))
    case(batch, era, "blockbody", "aux index 1 then 0", block_body(era, 2, c.cmap([(c.uint(1), AUX), (c.uint(0), AUX)])))

# Invalid-transaction lists: the increasing ones in the batch, the others each alone.
for era in ["alonzo", "babbage", "conway"]:
    case(batch, era, "blockbody", "invalid [0,1]", block_body(era, 2, c.cmap([]), [0, 1]))
    case(batch, era, "blockbody", "invalid [1]", block_body(era, 2, c.cmap([]), [1]))
    case(singles, era, "blockbody", "invalid [1,0]", block_body(era, 2, c.cmap([]), [1, 0]))
    case(singles, era, "blockbody", "invalid [0,0]", block_body(era, 2, c.cmap([]), [0, 0]))
    case(singles, era, "blockbody", "invalid [1,1]", block_body(era, 2, c.cmap([]), [1, 1]))

# Controls: three committed lines of the detached records corpus, replayed.
fixture = os.path.join(REPO, "fixtures", "reference", "recd-detached", "cases.jsonl")
rows = [json.loads(line) for line in open(fixture)]
for want in ("conway blockbody plain", "conway blockbody detached records"):
    r = next(r for r in rows if r["name"] == want)
    batch.append({"name": "control " + r["name"], "era": r["era"], "kind": r["kind"], "bytes": r["bytes"], "ids": True})
refused = next(r for r in rows if r["name"] == "babbage body update without its epoch")
batch.append({"name": "control " + refused["name"], "era": refused["era"], "kind": refused["kind"],
              "bytes": refused["bytes"], "ids": True})
expected = {f"control {r['name']}": r["accepted"] for r in rows}


def write_inputs(out):
    os.makedirs(out, exist_ok=True)
    with open(os.path.join(out, "batch.json"), "w") as f:
        json.dump(batch, f, indent=1)
    for i, s in enumerate(singles):
        with open(os.path.join(out, f"single-{i:02d}.json"), "w") as f:
            json.dump([s], f)
    with open(os.path.join(out, "controls-expected.json"), "w") as f:
        json.dump({k: v for k, v in expected.items() if any(b["name"] == k for b in batch)}, f, indent=1)


def verdict(case, accepted, failure):
    return json.dumps({"name": case["name"], "era": case["era"], "kind": case["kind"], "accepted": accepted,
                       "failure": failure, "bytes": case["bytes"]})


def read_outputs(outputs):
    """The verdict lines: the batch's from its output, then each single case's, whose harness wrote
    no verdict and ended with the exception on the first line of its standard error."""
    by_name = {x["name"]: x for x in batch}
    with open(os.path.join(outputs, "batch.json")) as f:
        results = json.load(f)
    assert [r["name"] for r in results] == [x["name"] for x in batch], "the batch output is in input order"
    lines = [verdict(by_name[r["name"]], r["result"]["accepted"], r["result"].get("failure")) for r in results]
    for i, s in enumerate(singles):
        base = os.path.join(outputs, f"single-{i:02d}")
        assert os.path.getsize(base + ".json") == 0, f"{s['name']}: the harness wrote a verdict"
        with open(base + ".stderr") as f:
            thrown = f.read().splitlines()[0]
        lines.append(verdict(s, False, "reference throws: " + thrown))
    return lines


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--inputs", required=True, help="the directory of the input files the harness reads")
    parser.add_argument("--outputs", help="the directory of the harness output for each input file, to read")
    parser.add_argument("--out", help="write the verdict lines (needs --outputs)")
    args = parser.parse_args()
    names = [x["name"] for x in batch + singles]
    assert len(names) == len(set(names)), "case names must be unique"
    write_inputs(args.inputs)
    if args.out:
        if not args.outputs:
            parser.error("--out needs --outputs")
        with open(args.out, "w") as f:
            f.write("\n".join(read_outputs(args.outputs)) + "\n")


if __name__ == "__main__":
    main()
