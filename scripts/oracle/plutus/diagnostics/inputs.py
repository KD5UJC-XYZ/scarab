#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Deterministic typed-context inputs; the native oracle alone supplies expected text."""
from dataclasses import dataclass
import json


@dataclass(frozen=True)
class Constr:
    tag: int
    fields: tuple


@dataclass(frozen=True)
class Assoc:
    entries: tuple


def c(tag, *fields):
    return Constr(tag, fields)


def m(*entries):
    return Assoc(entries)


def head(major, n):
    if n < 24:
        return bytes([major * 32 + n])
    for size, marker in ((1, 24), (2, 25), (4, 26), (8, 27)):
        if n < 1 << (size * 8):
            return bytes([major * 32 + marker]) + n.to_bytes(size, "big")
    magnitude = n.to_bytes((n.bit_length() + 7) // 8, "big")
    return head(6, 2 if major == 0 else 3) + encode(magnitude)


def encode(d):
    if isinstance(d, Constr):
        if d.tag < 7:
            return head(6, 121 + d.tag) + encode(list(d.fields))
        if d.tag < 128:
            return head(6, 1280 + d.tag - 7) + encode(list(d.fields))
        return head(6, 102) + encode([d.tag, list(d.fields)])
    if isinstance(d, Assoc):
        return head(5, len(d.entries)) + b"".join(encode(k) + encode(v) for k, v in d.entries)
    if isinstance(d, list):
        return head(4, len(d)) + b"".join(map(encode, d))
    if isinstance(d, bytes):
        return head(2, len(d)) + d
    if isinstance(d, int):
        return head(0, d) if d >= 0 else head(1, -1 - d)
    raise TypeError(type(d))


def inputs():
    h = bytes(range(28))
    txid = bytes(range(32))
    cred, script_cred = c(0, h), c(1, h[::-1])
    staking = c(0, cred)
    pointer = c(1, 4, 5, 6)
    nothing = c(1)
    just = lambda x: c(0, x)
    address = c(0, cred, just(staking))
    pointer_address = c(0, script_cred, just(pointer))
    plain_address = c(0, cred, nothing)
    value = m((b"", m((b"", 120))), (h, m((b"token", -2), (b"\x00\xff", 0))))
    mint = m((h, m((b"token", -2))))
    datum = c(129, -123456789012345678901234567890, b"\x00\x0eH\xff9", [1, 2], m((1, 2), (1, 3)))
    outref1, outref3 = c(0, c(0, txid), 7), c(0, txid, 7)
    out1 = c(0, address, value, just(h))
    outs = [c(0, addr, value, od, ref) for addr, od, ref in (
        (address, c(0), nothing), (pointer_address, c(1, h), just(h)),
        (plain_address, c(2, datum), nothing))]
    certs = [c(0, staking), c(1, c(0, script_cred)), c(2, pointer, h),
             c(3, h, h[::-1]), c(4, h, 90), c(5), c(6)]
    finite = c(0, c(0, c(1, -123), c(1)), c(0, c(1, 567), c(0)))
    infinite = c(0, c(0, c(0), c(0)), c(0, c(2), c(1)))
    purposes1 = [c(0, h), c(1, outref1), c(2, staking), c(3, certs[2])]
    info1 = c(0, [c(0, outref1, out1)], [out1, c(0, pointer_address, value, nothing)],
              value, value, certs, [c(0, staking, 12), c(0, pointer, 34)], finite,
              [h, h[::-1]], [c(0, h, datum)], c(0, txid))
    info2 = c(0, [c(0, outref1, outs[0])], [c(0, outref1, outs[1])], outs, value,
              value, certs, m((staking, 12), (pointer, 34)), infinite, [h],
              m(*[(p, datum) for p in purposes1]), m((h, datum)), c(0, txid))
    drep = c(0, script_cred)
    delegates = [c(0, h), c(1, drep), c(2, h, c(1)), c(1, c(2))]
    txcerts = [c(0, cred, nothing), c(0, cred, just(42)), c(1, script_cred, just(43))]
    txcerts += [c(2, cred, d) for d in delegates]
    txcerts += [c(3, cred, delegates[2], 44), c(4, cred, 45), c(5, script_cred),
                c(6, cred, 46), c(7, h, h[::-1]), c(8, h, 90), c(9, cred, script_cred), c(10, cred)]
    gid = c(0, txid, 9)
    actions = [c(0, nothing, datum, just(h)), c(1, just(gid), c(0, 11, 2)),
               c(2, m((cred, 123)), nothing), c(3, just(gid)),
               c(4, just(gid), [cred, script_cred], m((cred, 42)), c(0, 2, 3)),
               c(5, nothing, c(0, just(h))), c(6)]
    proposals = [c(0, 100, cred, action) for action in actions]
    voters = [c(0, cred), c(1, script_cred), c(2, h)]
    purposes3 = [c(0, h), c(1, outref3), c(2, cred), c(3, 1, txcerts[3]),
                 c(4, voters[0]), c(5, 2, proposals[0])]
    info3 = c(0, [c(0, outref3, outs[0])], [c(0, outref3, outs[1])], outs,
              200, mint, txcerts, m((cred, 12)), finite, [h],
              m(*[(p, datum) for p in purposes3]), m((h, datum)), txid,
              m(*[(v, m((gid, c(i)))) for i, v in enumerate(voters)]),
              proposals, just(123), nothing)
    script_infos = [c(0, h), c(1, outref3, nothing), c(1, outref3, just(datum)),
                    c(2, script_cred), c(3, 1, txcerts[2]), c(4, voters[1]), c(5, 2, proposals[5])]
    cases = [(f"v1-purpose-{i}", "ContextV1", c(0, info1, p)) for i, p in enumerate(purposes1)]
    cases += [(f"v2-purpose-{i}", "ContextV2", c(0, info2, p)) for i, p in enumerate(purposes1)]
    cases += [(f"v3-script-info-{i}", "ContextV3", c(0, info3, datum, p)) for i, p in enumerate(script_infos)]
    for version, info in ((1, info1), (2, info2)):
        ctx = c(0, info, purposes1[1])
        cases += [(f"v{version}-arguments-{len(args)}", f"ArgumentsV{version}", args)
                  for args in ([datum, ctx], [datum, datum, ctx])]
    for closed in (False, True):
        for tag in range(3):
            bound = c(tag, -123) if tag == 1 else c(tag)
            interval = c(0, c(0, bound, c(int(closed))), c(0, bound, c(int(closed))))
            fields = list(info1.fields)
            fields[6] = interval
            cases.append((f"v1-bound-{tag}-{closed}", "ContextV1", c(0, c(0, *fields), purposes1[0])))
    cases += [("data-layout", "RawData", datum), ("data-empty", "RawData", c(42)),
              ("data-long", "RawData", [bytes(range(60))] * 4)]
    return [dict(name=name, kind=kind, dataCbor=encode(d).hex()) for name, kind, d in cases]


if __name__ == "__main__":
    print(json.dumps(inputs(), indent=2))
