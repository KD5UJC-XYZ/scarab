#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""The inputs of the UTxO answer oracle (`Main.hs`): for each Shelley-based era, outputs of every form
the era's `TxOut` holds, with the addresses a chain carries (Byron bootstrap, pointer, script), and
outputs with a part only a later era's `TxOut` holds, which the era's decoder refuses. Writes the
cases as JSON to stdout."""
import json
import struct
import sys
import zlib


def head(major, n):
    if n < 24:
        return bytes([major << 5 | n])
    for limit, code, fmt in ((0xFF, 24, ">B"), (0xFFFF, 25, ">H"), (0xFFFFFFFF, 26, ">I")):
        if n <= limit:
            return bytes([major << 5 | code]) + struct.pack(fmt, n)
    return bytes([major << 5 | 27]) + struct.pack(">Q", n)


def uint(n):
    return head(0, n)


def bstr(b):
    return head(2, len(b)) + b


def arr(*xs):
    return head(4, len(xs)) + b"".join(xs)


def cmap(pairs):
    return head(5, len(pairs)) + b"".join(k + v for k, v in pairs)


def tag(t, x):
    return head(6, t) + x


def h(n, fill):
    return bytes([fill]) * n


def txin(fill, ix):
    return arr(bstr(h(32, fill)), uint(ix))


BASE = bytes([0x00]) + h(28, 0x11) + h(28, 0x22)
ENTERPRISE = bytes([0x60]) + h(28, 0x33)
SCRIPT_BASE = bytes([0x10]) + h(28, 0x44) + h(28, 0x55)
# a pointer address: slot 2,000,000, transaction 3, certificate 1, each a variable-length natural
POINTER = bytes([0x40]) + h(28, 0x66) + bytes([0x80 | 0x7A, 0x89, 0x00, 0x03, 0x01])


def byron():
    payload = arr(bstr(h(28, 0x77)), cmap([]), uint(0))
    return arr(tag(24, bstr(payload)), uint(zlib.crc32(payload)))


BYRON = byron()
COIN = uint(1_500_000)
MAX_COIN = uint(2**64 - 1)
ASSETS = cmap([
    (bstr(h(28, 0xA1)), cmap([(bstr(b""), uint(1)), (bstr(b"token"), uint(2**63))])),
    (bstr(h(28, 0xA0)), cmap([(bstr(b"z"), uint(7)), (bstr(b"a"), uint(9))])),
])
MULTI = arr(uint(2_000_000), ASSETS)
DETACHED = b"\x9f\xff" + uint(2_000_000) + ASSETS
DETACHED_COIN = b"\x9f\xff" + uint(1_500_000) + cmap([])
DATUM_HASH = h(32, 0xDA)
PLUTUS_DATA = arr(uint(42), bstr(b"datum"))
NATIVE_SCRIPT = arr(uint(0), arr(uint(0), bstr(h(28, 0x5C))))


def shelley(addr, coin=COIN):
    return arr(bstr(addr), coin)


def legacy(addr, value, datum_hash=None):
    return arr(bstr(addr), value, *([bstr(datum_hash)] if datum_hash else []))


def keyed(addr, value, datum=None, script=None):
    pairs = [(uint(0), bstr(addr)), (uint(1), value)]
    if datum is not None:
        pairs.append((uint(2), datum))
    if script is not None:
        pairs.append((uint(3), tag(24, bstr(script))))
    return cmap(pairs)


def hash_option(dh):
    return arr(uint(0), bstr(dh))


def inline_option(data):
    return arr(uint(1), tag(24, bstr(data)))


def indefinite(*xs):
    return b"\x9f" + b"".join(xs) + b"\xff"


def wrapped(script_bytes):
    return tag(24, script_bytes)


def keyed_raw(addr, value, datum=None, script_item=None):
    """The keyed form with the datum and script items as given, already encoded."""
    pairs = [(uint(0), bstr(addr)), (uint(1), value)]
    if datum is not None:
        pairs.append((uint(2), datum))
    if script_item is not None:
        pairs.append((uint(3), script_item))
    return cmap(pairs)


# a reference script and a datum option in forms the decoders take but the encoders do not write:
# the reference writes the datum option and the script pair anew, and keeps the data's and a native
# script's own bytes (a chunked byte string in these places is refused by the decoder)
NONCANONICAL = [
    ("inline-indefinite-option", lambda: keyed_raw(BASE, COIN, indefinite(uint(1), tag(24, bstr(PLUTUS_DATA))))),
    ("hash-indefinite-option", lambda: keyed_raw(BASE, COIN, indefinite(uint(0), bstr(DATUM_HASH)),
                                                 wrapped(bstr(NATIVE_SCRIPT)))),
    ("script-indefinite-pair", lambda: keyed_raw(
        BASE, COIN, script_item=wrapped(bstr(indefinite(uint(0), arr(uint(0), bstr(h(28, 0x5C)))))))),
    ("script-indefinite-native", lambda: keyed_raw(
        BASE, COIN, script_item=wrapped(bstr(arr(uint(0), indefinite(uint(0), bstr(h(28, 0x5C)))))))),
    ("script-plutus", lambda: keyed_raw(BASE, COIN, script_item=wrapped(bstr(arr(uint(2), bstr(PLUTUS_V2)))))),
]
# a well-formed Plutus V2 script: the flat encoding of `(program 1.0.0 (con unit ()))`
PLUTUS_V2 = bytes.fromhex("4c01000022222222222222222222")


def case(name, era, entries):
    return {"name": name, "era": era, "entries": [[i.hex(), o.hex()] for i, o in entries]}


def cases():
    out = []
    addresses = [("base", BASE), ("enterprise", ENTERPRISE), ("script-base", SCRIPT_BASE),
                 ("pointer", POINTER), ("byron", BYRON)]
    eras = ["shelley", "allegra", "mary", "alonzo", "babbage", "conway"]
    for era in eras:
        for label, addr in addresses:
            out.append(case(f"{era}-{label}", era, [(txin(0x01, 0), shelley(addr))]))
        out.append(case(f"{era}-max-coin", era, [(txin(0x01, 1), shelley(BASE, MAX_COIN))]))
        # entries out of order, in more than one transaction: the answer is in input order
        unordered = [(txin(0x09, 2), shelley(BASE)), (txin(0x02, 7), shelley(ENTERPRISE)),
                     (txin(0x09, 0), shelley(BYRON)), (txin(0x02, 300), shelley(POINTER))]
        out.append(case(f"{era}-order", era, unordered))
        # more than 23 entries: the map's length is past what one head byte holds
        out.append(case(f"{era}-many", era, [(txin(0x30 + n % 5, n), shelley(BASE, uint(n + 1)))
                                             for n in range(30)]))
        out.append(case(f"{era}-empty", era, []))
        multi = [(txin(0x03, 0), legacy(BASE, MULTI))]
        dh = [(txin(0x04, 0), legacy(ENTERPRISE, COIN, DATUM_HASH))]
        out.append(case(f"{era}-multi-asset", era, multi))
        out.append(case(f"{era}-datum-hash", era, dh))
        out.append(case(f"{era}-multi-asset-datum-hash", era,
                        [(txin(0x04, 1), legacy(SCRIPT_BASE, MULTI, DATUM_HASH))]))
        # the keyed form of what an Alonzo output holds is Babbage's wire form only: before Babbage it
        # is not a different output but no output, so it is asked from Babbage on
        if era in ("babbage", "conway"):
            out.append(case(f"{era}-keyed-plain", era, [(txin(0x05, 0), keyed(BASE, MULTI))]))
            out.append(case(f"{era}-keyed-datum-hash", era,
                            [(txin(0x05, 1), keyed(BASE, COIN, hash_option(DATUM_HASH)))]))
        out.append(case(f"{era}-keyed-inline", era,
                        [(txin(0x05, 2), keyed(SCRIPT_BASE, COIN, inline_option(PLUTUS_DATA)))]))
        out.append(case(f"{era}-keyed-script", era,
                        [(txin(0x05, 3), keyed(BASE, MULTI, script=NATIVE_SCRIPT))]))
        out.append(case(f"{era}-keyed-inline-script", era,
                        [(txin(0x05, 4), keyed(BYRON, COIN, inline_option(PLUTUS_DATA), NATIVE_SCRIPT))]))
        out.append(case(f"{era}-keyed-hash-script", era,
                        [(txin(0x05, 5), keyed(POINTER, MULTI, hash_option(DATUM_HASH), NATIVE_SCRIPT))]))
        if era in ("babbage", "conway"):
            for n, (label, output) in enumerate(NONCANONICAL):
                out.append(case(f"{era}-{label}", era, [(txin(0x06, n), output())]))
        # a value written as an empty indefinite list with its fields after it, as the decoders read
        # a `RecD` record below version 12 (#700): the answer writes the value as decoded, a definite
        # list, or the bare coin when it holds no asset; before Mary no value is a record
        out.append(case(f"{era}-detached-value", era, [(txin(0x07, 0), legacy(BASE, DETACHED))]))
        out.append(case(f"{era}-detached-coin", era, [(txin(0x07, 1), legacy(ENTERPRISE, DETACHED_COIN))]))
        if era in ("alonzo", "babbage", "conway"):
            out.append(case(f"{era}-detached-value-datum-hash", era,
                            [(txin(0x07, 2), legacy(SCRIPT_BASE, DETACHED, DATUM_HASH))]))
        if era in ("babbage", "conway"):
            out.append(case(f"{era}-keyed-detached-value", era,
                            [(txin(0x07, 3), keyed(BASE, DETACHED, script=NATIVE_SCRIPT))]))
    return out


if __name__ == "__main__":
    json.dump(cases(), sys.stdout, separators=(",", ":"))
