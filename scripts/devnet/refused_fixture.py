# SPDX-License-Identifier: Apache-2.0
"""Assemble `fixtures/devnet/refused-txs.jsonl` from the run directory of a `soak --governance-extended`.

    python3 scripts/devnet.py refused-fixture --name scarab-devnet [--out fixtures/devnet/refused-txs.jsonl]

The run directory holds `negative-txs.jsonl` (every transaction the reference refused as expected) and
`vrf-txs.jsonl` (the whole VRF sequence, accepted and refused, in order, after a context line). The fixture
keeps the VRF sequence, the unelected-voter refusals and the pre-11 reference-input refusal; the Scala
`DevnetRefusedTxSuite` replays them.
"""
import json
import pathlib
import sys

from .util import REPO, data_root


def read(path):
    return [json.loads(line) for line in path.read_text().splitlines() if line.strip()]


def build(run_dir):
    """The fixture lines for the run directory `run_dir`."""
    negatives = read(run_dir / "negative-txs.jsonl")
    vrf = read(run_dir / "vrf-txs.jsonl")
    lines = []
    for entry in vrf:
        lines.append({"scenario": "vrf", **entry})
    for entry in negatives:
        label = entry["label"]
        if label.startswith("xunelected-"):
            lines.append({"scenario": "unelected", **entry})
        elif label.startswith("xrefoverlap-pv10"):
            lines.append({"scenario": "refoverlap", **entry})
    return lines


RAW_PLUTUS_OUT = REPO / "fixtures" / "devnet" / "refused-raw-plutus.jsonl"


def raw_plutus_fixture(args):
    """The raw reasons `erarefusal` kept in an `e2e` run, as they were recorded (one line per refused case)."""
    rows = read(data_root(args.name) / "refused-raw-plutus.jsonl")
    out = pathlib.Path(RAW_PLUTUS_OUT if args.out == str(DEFAULT_OUT) else args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text("".join(json.dumps(row, sort_keys=True) + "\n" for row in rows))
    print(json.dumps({"written": str(out), "lines": len(rows)}))


def refused_fixture(args):
    if args.raw_plutus:
        return raw_plutus_fixture(args)
    out = pathlib.Path(args.out)
    lines = build(data_root(args.name))
    kinds = {}
    for line in lines:
        kinds[line["scenario"]] = kinds.get(line["scenario"], 0) + 1
    if not all(k in kinds for k in ("vrf", "unelected", "refoverlap")):
        sys.exit(f"the run lacks a scenario: {kinds}")
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text("".join(json.dumps(line, sort_keys=True) + "\n" for line in lines))
    print(json.dumps({"written": str(out), "lines": kinds}))


DEFAULT_OUT = REPO / "fixtures" / "devnet" / "refused-txs.jsonl"
