#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Writes compact ChainSync input recipes and consumes executed native observations (#755).

Each case is a message `[4, points]` (`MsgFindIntersect`) or a neighbour, as the server reads it
in `StIdle`. A large one is written as `prefix`, `count` copies of `unit` and `suffix`, so that the
corpus stays small; `bytes` expands it. The reference reads the points with `decodeList` (a definite
or indefinite list, `ChainSync/Codec.hs:209-214`, ouroboros-network 2fb69829) and each with
`decodePoint (decodeRawHash p)`: `[]` or `[slot, hash]`, the hash a byte string of exactly 32 bytes.
The NtN driver bounds incoming messages at 65535 bytes; the plain NtC driver and the encoder do not.

    python3 scripts/oracle/chainsync-find-intersect/find_intersect_cases.py --inputs /tmp/fi-inputs.txt
    docker run --rm -i --network none -v "$out:/observer" --entrypoint /observer/find-intersect-observer \\
        scarab-33-genesis-reference-cache:local < /tmp/fi-inputs.txt > /tmp/fi-reference.jsonl
    python3 scripts/oracle/chainsync-find-intersect/find_intersect_cases.py --reference /tmp/fi-reference.jsonl \\
        --out fixtures/reference/chainsync-find-intersect/cases.jsonl
"""
import argparse
import hashlib
import json
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "conway-decoding"))
import cases as c  # noqa: E402  (the CBOR helpers)

CASES = []
LIMIT = 65535
SELECTIONS = [f"select 2160 {n} origin" for n in [0, 1, 2, 3, 4, 5, 8, 13, 16, 21, 34, 64, 2160]]
SELECTIONS += ["select 5 9 origin", "select 2160 0 block", "select 2160 5 block", "offsets 2147483647 2147483647"]


def case(name, prefix, unit=b"", count=0, suffix=b"", transport="ntn", kind="points"):
    CASES.append({"name": name, "kind": kind, "transport": transport, "prefix": prefix.hex(), "unit": unit.hex(), "count": count, "suffix": suffix.hex()})


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

for count in [65536, 70000]:
    case(f"NtC {count} origins", *definite(count, ORIGIN), transport="ntc")
case("NtC 70000 points beyond 2.5 MB", *definite(70000, P), transport="ntc")
case("NtC 70000 points indefinite beyond 2.5 MB", *indefinite(70000, P), transport="ntc")
case("NtC invalid hash after 70000 origins", FIND + c.head(4, 70001), ORIGIN, 70000,
     point(1000, c.h(0xab, 31)), transport="ntc")
case("NtC nested point is not a point", FIND + c.head(4, 1), b"\x81", 128,
     ORIGIN, transport="ntc")
for size in [2**31, 2**63, 2**64 - 1]:
    for suffix, label in [(b"", "incomplete"), (b"\xf6", "invalid first point")]:
        case(f"NtC declared {size} points, {label}", FIND + c.head(4, size), suffix=suffix,
             transport="ntc", kind="prefix")
    case(f"NtC declared {size} hash bytes, incomplete", FIND + b"\x81\x82\x00" + c.head(2, size),
         transport="ntc", kind="prefix")
    case(f"NtC declared {size} opaque payload bytes, incomplete", b"\x83\x02" + c.head(2, size),
         transport="ntc", kind="block-prefix")
case("NtC declared 65536 hash bytes, incomplete", FIND + b"\x81\x82\x00" + c.head(2, 65536),
     transport="ntc", kind="prefix")
case("NtC a complete 65536-byte hash is not a Cardano hash", FIND + b"\x81\x82\x00" + c.head(2, 65536),
     b"\x00", 65536, transport="ntc")
case("opaque point canonical output crosses NtN receive limit", bytes.fromhex("82048159fff9"),
     b"\x00", 65529, kind="opaque")
case("NtC opaque RawBlock beyond 2.5 MB", b"\x83\x02\xd8\x18" + c.head(2, 2600000),
     b"\x00", 2600000, b"\x82\x80\x00", transport="ntc", kind="block")
for name, raw in [
    ("missing tag", "8302"),
    ("wrong tag without its body", "8302d819"),
    ("wrong tag before an incomplete array", "8302d8199a80000000"),
    ("correct tag without a byte-string head", "8302d818"),
    ("indefinite byte string without its break", "8302d8185f"),
    ("array in place of bytes without its contents", "8302d8189a80000000"),
    ("definite byte string without its contents", "8302d81841"),
    ("complete empty payload without its tip", "8302d81840"),
    ("complete payload with an invalid tip", "8302d8184100f6"),
]:
    case("NtC serialized block: " + name, bytes.fromhex(raw), transport="ntc", kind="serialised-prefix")
case("NtC complete empty serialized block", bytes.fromhex("8302d81840828000"), transport="ntc", kind="block")
for count, kind in [(65, "point"), (1025, "point"), (70000, "point"), (65536, "origin")]:
    CASES.append({"name": f"encode {count} {kind}s", "kind": "encode", "count": count, "pointKind": kind})


def request(row):
    if row["kind"] == "encode":
        return f'encode {row["count"]} {row["pointKind"]}'
    mode = "decode" if row["kind"] == "points" else row["kind"]
    return mode + " " + expand(row).hex()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--inputs", help="write one hex message a line, for the oracle")
    parser.add_argument("--reference", help="the oracle's verdicts, one JSON line a case")
    parser.add_argument("--out", help="write the corpus lines (needs --reference)")
    parser.add_argument("--selection-inputs", help="write native selection requests")
    parser.add_argument("--selection-reference", help="executed native selection observations")
    parser.add_argument("--selection-out", help="write the compact selection fixture")
    args = parser.parse_args()
    names = [x["name"] for x in CASES]
    assert len(names) == len(set(names)), "case names must be unique"
    for x in CASES:
        if x.get("transport") == "ntn":
            assert len(expand(x)) <= LIMIT, x["name"]
    if args.inputs:
        with open(args.inputs, "w") as f:
            f.writelines(request(x) + "\n" for x in CASES)
    if args.selection_inputs:
        with open(args.selection_inputs, "w") as f:
            f.writelines(x + "\n" for x in SELECTIONS)
    if args.selection_out:
        if not args.selection_reference:
            parser.error("--selection-out needs --selection-reference")
        with open(args.selection_reference) as f:
            lines = [line for line in f if line.strip()]
        assert len(lines) == len(SELECTIONS), "the selection oracle answered a different number of cases"
        for line in lines:
            json.loads(line)
        with open(args.selection_out, "w") as f:
            f.writelines(lines)
    if args.out:
        if not args.reference:
            parser.error("--out needs --reference")
        with open(args.reference) as f:
            verdicts = [json.loads(line) for line in f if line.strip()]
        assert len(verdicts) == len(CASES), "the oracle answered a different number of cases"
        with open(args.out, "w") as f:
            for x, v in zip(CASES, verdicts):
                row = dict(x, **v)
                if x["kind"] != "encode":
                    raw = expand(x)
                    row["input"] = {"bytes": len(raw), "sha256": hashlib.sha256(raw).hexdigest()}
                f.write(json.dumps(row, separators=(",", ":")) + "\n")


if __name__ == "__main__":
    main()
