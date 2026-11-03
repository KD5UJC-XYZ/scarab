#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Writes the node-to-node ChainSync client messages the find-intersect oracle reads (#755).

Each case is a message `[4, points]` (`MsgFindIntersect`) or a neighbour, as the server reads it
in `StIdle`. A large one is written as `prefix`, `count` copies of `unit` and `suffix`, so that the
corpus stays small; `bytes` expands it. The reference reads the points with `decodeList` (a definite
or indefinite list, `ChainSync/Codec.hs:209-214`, ouroboros-network 2fb69829) and each with
`decodePoint (decodeRawHash p)`: `[]` or `[slot, hash]`, the hash a byte string of exactly 32 bytes.
Nothing bounds the number of points but the codec's 65535 bytes a message.

    python3 scripts/oracle/chainsync-find-intersect/find_intersect_cases.py --inputs /tmp/fi-inputs.txt
    docker run --rm -i --network none -v "$out:/observer" --entrypoint /observer/find-intersect-observer \\
        scarab-33-genesis-reference-cache:local < /tmp/fi-inputs.txt > /tmp/fi-reference.jsonl
    python3 scripts/oracle/chainsync-find-intersect/find_intersect_cases.py --reference /tmp/fi-reference.jsonl \\
        --out fixtures/reference/chainsync-find-intersect/cases.jsonl
"""
import argparse
import json
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "conway-decoding"))
import cases as c  # noqa: E402  (the CBOR helpers)

CASES = []
LIMIT = 65535


def case(name, prefix, unit=b"", count=0, suffix=b""):
    CASES.append({"name": name, "prefix": prefix.hex(), "unit": unit.hex(), "count": count, "suffix": suffix.hex()})


def expand(row):
    return bytes.fromhex(row["prefix"]) + bytes.fromhex(row["unit"]) * row["count"] + bytes.fromhex(row["suffix"])


def point(slot, hash_):
    return c.array([c.uint(slot), c.bstr(hash_)])


H = c.h(0xab, 32)
P = point(1000, H)
ORIGIN = c.array([])
FIND = b"\x82\x04"


def definite(n, unit):
    """`[4, [unit, ...]]` of `n` points, its list head definite."""
    return FIND + c.head(4, n), unit, n, b""


def indefinite(n, unit):
    return FIND + b"\x9f", unit, n, b"\xff"


case("no points", FIND + c.array([]))
case("one point", FIND + c.array([P]))
case("origin", FIND + c.array([ORIGIN]))
case("64 points", *definite(64, P))
case("65 points", *definite(65, P))
case("65 points, indefinite", *indefinite(65, P))
# the most 38-byte points (slot 1000, a 32-byte hash) one message of 65535 bytes holds
MOST = (LIMIT - len(FIND) - 3) // len(P)
case(f"{MOST} points, the most that fit", *definite(MOST, P))
case("9000 origins, more items than 8192", *definite(9000, ORIGIN))
case("65000 origins, indefinite", *indefinite(65000, ORIGIN))
case("a point at the largest slot", FIND + c.array([point(2**64 - 1, H)]))
for size in [0, 31, 33, 64]:
    case(f"a hash of {size} bytes", FIND + c.array([point(1000, c.h(0xab, size))]))
case("a hash with a non-minimal length head", FIND + c.array([c.array([c.uint(1000), b"\x59\x00\x20" + H])]))
case("a hash written as two chunks", FIND + c.array([c.array([c.uint(1000), b"\x5f" + c.bstr(H[:16]) + c.bstr(H[16:]) + b"\xff"])]))
case("a point of one field", FIND + c.array([c.array([c.uint(1000)])]))
case("a point of three fields", FIND + c.array([c.array([c.uint(1000), c.bstr(H), c.uint(0)])]))
case("a point as an indefinite list", FIND + c.array([b"\x9f" + c.uint(1000) + c.bstr(H) + b"\xff"]))
case("a negative slot", FIND + c.array([c.array([b"\x20", c.bstr(H)])]))
case("points not a list", FIND + c.cmap([]))
case("the message as an indefinite list", b"\x9f\x04" + c.array([P]) + b"\xff")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--inputs", help="write one hex message a line, for the oracle")
    parser.add_argument("--reference", help="the oracle's verdicts, one JSON line a case")
    parser.add_argument("--out", help="write the corpus lines (needs --reference)")
    args = parser.parse_args()
    names = [x["name"] for x in CASES]
    assert len(names) == len(set(names)), "case names must be unique"
    for x in CASES:
        assert len(expand(x)) <= LIMIT, x["name"]
    if args.inputs:
        with open(args.inputs, "w") as f:
            f.writelines(expand(x).hex() + "\n" for x in CASES)
    if args.out:
        if not args.reference:
            parser.error("--out needs --reference")
        with open(args.reference) as f:
            verdicts = [json.loads(line) for line in f if line.strip()]
        assert len(verdicts) == len(CASES), "the oracle answered a different number of cases"
        with open(args.out, "w") as f:
            for x, v in zip(CASES, verdicts):
                row = dict(x, accepted=v["accepted"])
                if v["accepted"]:
                    row["points"] = len(v["points"])
                    row["first"] = v["points"][0] if v["points"] else None
                else:
                    row["failure"] = v["failure"]
                f.write(json.dumps(row, separators=(",", ":")) + "\n")


if __name__ == "__main__":
    main()
