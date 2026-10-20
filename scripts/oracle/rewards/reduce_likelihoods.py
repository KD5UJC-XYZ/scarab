#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Reduce original Conway reward API observations to public pool scalars and Float bits.

Full captured states remain outside the checkout. This extracts fields without changing
any input to the native calculation. The optional history result must come from replacing
only EpochState.NonMyopic with the first native completed NonMyopic, as the fixture README
specifies. Run from the checkout; no third-party Python packages are required.
"""

import argparse
import gzip
import json
import math
from pathlib import Path
import struct
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from devnet import cborspan, ntcquery  # noqa: E402


def item(encoded, path):
    start, end = cborspan.resolve(encoded, path)[0]
    return ntcquery.decode_all(encoded[start:end])


def bits(likelihoods):
    result = []
    for pool, weights in likelihoods.items():
        if len(pool) != 28 or len(weights) != 100 or not all(map(math.isfinite, weights)):
            raise ValueError("expected a pool hash and 100 finite native samples")
        result.append({"pool": pool.hex(), "bits": [
            struct.unpack(">I", struct.pack(">f", weight))[0] for weight in weights
        ]})
    return result


def completed(row):
    transitions = row["transitions"]
    if len(transitions) != 1 or transitions[0]["operation"] != "complete":
        raise ValueError("expected exactly one original complete operation")
    return bytes.fromhex(transitions[0]["state"])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("inputs", type=Path)
    parser.add_argument("results", type=Path)
    parser.add_argument("history_results", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    inputs = json.loads(args.inputs.read_text())
    results = json.loads(args.results.read_text())
    histories = json.loads(args.history_results.read_text())
    if len(inputs) != 1 or len(results) != 1 or len(histories) != 1:
        raise ValueError("expected one captured input and one result per run")
    source, row, history = inputs[0], results[0], histories[0]
    if source["era"] != "Conway" or source["name"] != row["name"]:
        raise ValueError("expected the matching original Conway observation")
    es = bytes.fromhex(source["epochState"])
    pools = item(es, (2, 2, 1))
    blocks = ntcquery.decode_all(bytes.fromhex(source["blocksMade"]))
    scalars = {
        "totalStake": source["supply"] - item(es, (0, 1)),
        "pools": [{"pool": pool.hex(), "stake": snapshot[0], "blocks": blocks.get(pool, 0)}
                  for pool, snapshot in pools.items()],
    }
    original = bytes.fromhex(row["initial"])
    generated = item(original, (1, 6))
    first = item(completed(row), (1, 4, 0))
    second = item(completed(history), (1, 4, 0))
    if set(pools) != set(generated) or set(pools) != set(first) or set(pools) != set(second):
        raise ValueError("native likelihood pool sets differ from the captured snapshot")
    args.output.mkdir(parents=True, exist_ok=True)
    for name, value in (
        ("preview-pools.json", scalars),
        ("preview-likelihood-bits.json", bits(generated)),
        ("preview-completed-bits.json", bits(first)),
        ("preview-history-completed-bits.json", bits(second)),
    ):
        raw = json.dumps(value, separators=(",", ":")).encode()
        (args.output / (name + ".gz")).write_bytes(gzip.compress(raw, mtime=0))


if __name__ == "__main__":
    main()
