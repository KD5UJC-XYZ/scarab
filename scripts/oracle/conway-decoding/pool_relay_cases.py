#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Writes the original encodings of the pool-relay decoding oracle (#436).

The reference decodes a pool registration's relays with the transaction body
(`decodeStakePoolParamsFlat`, `instance DecCBOR StakePoolRelay`,
`libs/cardano-ledger-core/src/Cardano/Ledger/State/StakePool.hs:406-421, 578-589`, cardano-ledger
f649f975): a port is a `Word16`, an IPv4 or IPv6 address is a byte string of exactly 4 or 16 bytes
read with `decodeBytes` (definite below decoder version 12), and a DNS name is a `DnsName`, definite
text of at most 64 bytes of UTF-8 below version 9 and 128 from it (`BaseTypes.hs:644-697`).

Each era from Shelley to Conway gets a registration whose relays the reference accepts, and the
same registration with one relay changed: an IPv4 address of five bytes, an IPv4 and an IPv6 address
as one indefinite chunk, a port of 65536, a DNS name as one chunk, and DNS names at and over the
era's bound (below version 9 also 128 bytes, which only version 9 takes).

The committed corpus is `fixtures/reference/pool-relays/cases.jsonl`, written in two steps as for
`definite_text_cases.py`:

    python3 scripts/oracle/conway-decoding/pool_relay_cases.py --inputs /tmp/pool-relays-inputs.json
    python3 scripts/oracle/conway-decoding/pool_relay_cases.py --inputs /tmp/pool-relays-inputs.json \\
        --reference /tmp/pool-relays-reference.json --out fixtures/reference/pool-relays/cases.jsonl
"""
import argparse
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import cases as c  # noqa: E402  (the CBOR helpers)
import definite_bytes_cases as d  # noqa: E402  (the body builder and the chunked writers)
import definite_text_cases as t  # noqa: E402  (the pool registration and the merge)

ERAS = ["shelley", "allegra", "mary", "alonzo", "babbage", "conway"]
# Scarab's error case for relays the reference refuses.
RELAY = "PoolRelay"
CASES = []

IPV4 = bytes([3, 101, 47, 133])
IPV6 = bytes(range(16))


def address(port=c.uint(3001), ipv4=None, ipv6=c.NULL):
    """`[0, port, ipv4, ipv6]`."""
    return c.array([c.uint(0), port, c.bstr(IPV4) if ipv4 is None else ipv4, ipv6])


def named(name):
    """`[1, null, dns_name]`."""
    return c.array([c.uint(1), c.NULL, name])


def multi(name):
    """`[2, dns_name]`."""
    return c.array([c.uint(2), name])


def dns(size):
    """A DNS name of exactly `size` bytes."""
    return "r" * (size - len(".example")) + ".example"


def bound(era):
    return 128 if era == "conway" else 64


def case(name, era, relays, rule=None):
    """A body case; `rule` is the fragment of the reference failure, None for an accepted one."""
    CASES.append(
        {
            "id": c.case_id(name),
            "name": name,
            "era": era,
            "kind": "body",
            "rule": rule,
            "expect": None if rule is None else RELAY,
            "bytes": d.body(era, {4: t.certs(t.pool_cert(c.array(relays), c.text(t.POOL_URL)))}).hex(),
        }
    )


for era in ERAS:
    n = bound(era)
    base = [address(), named(c.text("relay.example")), multi(c.text("srv.example"))]
    case(f"{era} body pool relays: an address, a name and an SRV name", era, base)
    case(f"{era} body pool relays: an IPv6 address", era, [address(ipv6=c.bstr(IPV6))])
    case(f"{era} body pool relays: an IPv4 address of five bytes", era,
         [address(ipv4=c.bstr(IPV4 + b"\x00"))], "while decoding decodeIPv4")
    case(f"{era} body pool relays: an IPv4 address as one chunk", era,
         [address(ipv4=d.chunked(IPV4))], "expected bytes")
    case(f"{era} body pool relays: an IPv6 address as one chunk", era,
         [address(ipv6=d.chunked(IPV6))], "expected bytes")
    case(f"{era} body pool relays: a port of 65536", era, [address(port=c.uint(65536))], "expected word16")
    case(f"{era} body pool relays: a DNS name as one chunk", era,
         [named(d.text_chunked("relay.example"))], "expected string")
    case(f"{era} body pool relays: a DNS name of {n} bytes", era, [named(c.text(dns(n)))])
    case(f"{era} body pool relays: a DNS name of {n + 1} bytes", era, [named(c.text(dns(n + 1)))],
         f"Text exceeds {n} bytes")
    case(f"{era} body pool relays: an SRV name of {n + 1} bytes", era, [multi(c.text(dns(n + 1)))],
         f"Text exceeds {n} bytes")
    if n == 64:
        case(f"{era} body pool relays: a DNS name of 128 bytes", era, [named(c.text(dns(128)))],
             "Text exceeds 64 bytes")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--inputs", help="write the array of original encodings the harness reads")
    parser.add_argument("--reference", help="the harness output for those inputs, to merge")
    parser.add_argument("--out", help="write the corpus lines (needs --reference)")
    args = parser.parse_args()
    t.CASES[:] = CASES
    names = [x["name"] for x in CASES]
    assert len(names) == len(set(names)), "case names must be unique"
    ids = [x["id"] for x in CASES]
    assert len(ids) == len(set(ids)), "case ids must be unique"
    if args.inputs:
        with open(args.inputs, "w") as f:
            json.dump(CASES, f, indent=1)
            f.write("\n")
    if args.out:
        if not args.reference:
            parser.error("--out needs --reference")
        with open(args.reference) as f:
            lines = t.merge(json.load(f))
        with open(args.out, "w") as f:
            f.write("\n".join(lines) + "\n")


if __name__ == "__main__":
    main()
