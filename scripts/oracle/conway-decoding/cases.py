#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Writes the original encodings the Conway body and witness-set decoding oracle reads.

Every case breaks one rule of the reference decoders at decoder version 9 (or 7 for the Babbage
twins) and sits next to a case that keeps that rule. The encodings are written by hand here, not
produced by Scarab: the oracle decides what the reference does with them, and the Scala suite
checks that Scarab does the same.

    python3 scripts/oracle/conway-decoding/cases.py --out fixtures/reference/conway-decoding/cases.json
"""
import argparse
import json


def head(major, n, width=None):
    """Initial byte and argument. `width` (in bytes: 1, 2, 4, 8) forces a non-minimal argument."""
    if width is None:
        if n < 24:
            return bytes([major << 5 | n])
        width = 1 if n < 256 else 2 if n < 65536 else 4 if n < 2**32 else 8
    code = {1: 24, 2: 25, 4: 26, 8: 27}[width]
    return bytes([major << 5 | code]) + n.to_bytes(width, "big")


def uint(n, width=None):
    return head(0, n, width)


def bstr(b):
    return head(2, len(b)) + b


def text(s):
    raw = s.encode()
    return head(3, len(raw)) + raw


def array(items, indef=False, width=None):
    if indef:
        return b"\x9f" + b"".join(items) + b"\xff"
    return head(4, len(items), width) + b"".join(items)


def cmap(pairs, indef=False):
    body = b"".join(k + v for k, v in pairs)
    if indef:
        return b"\xbf" + body + b"\xff"
    return head(5, len(pairs)) + body


def tag(n, inner):
    return head(6, n) + inner


NULL = b"\xf6"
TRUE = b"\xf5"


def h(byte, n):
    return bytes([byte]) * n


def txin(i, wide_array=False, wide_index=False):
    return array([bstr(h(0x10 + i, 32)), uint(0, 1 if wide_index else None)], width=1 if wide_array else None)


def txin_at(i, index):
    return array([bstr(h(0x10 + i, 32)), uint(index)])


def sset(items, tagged=False, indef=False):
    inner = array(items, indef=indef)
    return tag(258, inner) if tagged else inner


ADDRESS = bstr(b"\x61" + h(0x22, 28))
OUTPUT = array([ADDRESS, uint(2_000_000)])
KEY_HASH = h(0x33, 28)
STAKE_REGISTRATION = array([uint(0), array([uint(0), bstr(KEY_HASH)])])
STAKE_REGISTRATION_2 = array([uint(0), array([uint(0), bstr(h(0x34, 28))])])
ANCHOR = array([text("https://example.invalid/a"), bstr(h(0x44, 32))])
PROPOSAL = array([uint(1_000_000), bstr(b"\xe1" + h(0x55, 28)), array([uint(3), NULL]), ANCHOR])
PROPOSAL_2 = array([uint(2_000_000), bstr(b"\xe1" + h(0x55, 28)), array([uint(3), NULL]), ANCHOR])
VOTER = array([uint(2), bstr(KEY_HASH)])
VOTER_2 = array([uint(2), bstr(h(0x35, 28))])
ACTION = array([bstr(h(0x66, 32)), uint(0)])
ACTION_2 = array([bstr(h(0x66, 32)), uint(1)])
VOTE = array([uint(1), NULL])
WITHDRAWAL = bstr(b"\xe1" + h(0x77, 28))
MINT = cmap([(bstr(h(0x88, 28)), cmap([(bstr(b"a"), uint(1))]))])


def body(extra=None, inputs=None, drop=()):
    """A body that Conway accepts, with `extra` fields (key -> encoded value) added or replaced."""
    fields = {0: inputs if inputs is not None else sset([txin(1)]), 1: array([OUTPUT]), 2: uint(200_000)}
    fields.update(extra or {})
    for k in drop:
        fields.pop(k, None)
    return cmap([(uint(k), fields[k]) for k in sorted(fields)])


def vkey_witness(i=0):
    return array([bstr(h(0x90 + i, 32)), bstr(h(0xA0 + i, 64))])


NATIVE = array([uint(0), bstr(KEY_HASH)])
NATIVE_2 = array([uint(0), bstr(h(0x36, 28))])
BOOTSTRAP = array([bstr(h(0x91, 32)), bstr(h(0xA1, 64)), bstr(h(0x92, 32)), bstr(cmap([]))])
SCRIPT = bstr(b"\x4e\x4d\x01\x00\x00\x22\x22\x00\x11")
SCRIPT_2 = bstr(b"\x4e\x4d\x01\x00\x00\x22\x22\x00\x12")
DATUM = uint(1)
DATUM_2 = uint(2)
REDEEMER_LIST = array([array([uint(0), uint(0), uint(0), array([uint(1), uint(2)])])])
REDEEMER_LIST_DUPLICATE = array(
    [
        array([uint(0), uint(0), uint(0), array([uint(1), uint(2)])]),
        array([uint(0), uint(0), uint(1), array([uint(3), uint(4)])]),
    ]
)
REDEEMER_MAP = cmap([(array([uint(0), uint(0)]), array([uint(0), array([uint(1), uint(2)])]))])
REDEEMER_MAP_DUPLICATE = cmap(
    [
        (array([uint(0), uint(0)]), array([uint(0), array([uint(1), uint(2)])])),
        (array([uint(0), uint(0)]), array([uint(1), array([uint(3), uint(4)])])),
    ]
)


def wits(key, value):
    return cmap([(uint(key), value)])


CASES = []


def case(name, era, kind, raw, rule=None, expect=None):
    """`rule` is a fragment of the reference's failure text, `expect` the Scarab error case."""
    CASES.append(
        {"name": name, "era": era, "kind": kind, "rule": rule, "expect": expect, "bytes": raw.hex()}
    )


def conway_body(name, raw, rule=None, expect=None):
    case(name, "conway", "body", raw, rule, expect)


def conway_wits(name, raw, rule=None, expect=None):
    case(name, "conway", "witnesses", raw, rule, expect)


EMPTY_RULE = "must be non-empty when supplied"
DUP_RULE = "does not match the total count that was decoded"

# --- bodies: sets -----------------------------------------------------------------------------
conway_body("body baseline", body())
conway_body("inputs: empty set is accepted", body(inputs=sset([])))
conway_body("inputs: two distinct inputs", body(inputs=sset([txin(1), txin(2)])))
conway_body("inputs: tag 258 is accepted", body(inputs=sset([txin(1), txin(2)], tagged=True)))
conway_body("inputs: indefinite length is accepted", body(inputs=sset([txin(1), txin(2)], indef=True)))
conway_body("inputs: duplicate input", body(inputs=sset([txin(1), txin(1)])), DUP_RULE, "DuplicateElement")
conway_body(
    "inputs: duplicate input under tag 258",
    body(inputs=sset([txin(1), txin(2), txin(1)], tagged=True)),
    DUP_RULE,
    "DuplicateElement",
)
conway_body(
    "inputs: duplicate input, indefinite length",
    body(inputs=sset([txin(1), txin(1)], indef=True)),
    DUP_RULE,
    "DuplicateElement",
)
conway_body(
    "inputs: duplicate input written with a wide array header",
    body(inputs=sset([txin(1), txin(1, wide_array=True)])),
    DUP_RULE,
    "DuplicateElement",
)
conway_body(
    "inputs: duplicate input written with a wide index",
    body(inputs=sset([txin(1), txin(1, wide_index=True)])),
    DUP_RULE,
    "DuplicateElement",
)
conway_body("inputs: same id, different index", body(inputs=sset([txin_at(1, 0), txin_at(1, 1)])))
conway_body("inputs: unknown tag", body(inputs=tag(259, sset([txin(1)]))), "Expecteg tag 258 but got tag 259", "Expected")

for key, label, make in (
    (13, "collateral", lambda items, **kw: sset(items, **kw)),
    (18, "reference inputs", lambda items, **kw: sset(items, **kw)),
):
    conway_body(f"{label}: two distinct inputs", body({key: make([txin(2), txin(3)])}))
    conway_body(f"{label}: tag 258", body({key: make([txin(2), txin(3)], tagged=True)}))
    conway_body(f"{label}: empty set", body({key: make([])}), EMPTY_RULE, "Empty")
    conway_body(f"{label}: empty set under tag 258", body({key: make([], tagged=True)}), EMPTY_RULE, "Empty")
    conway_body(f"{label}: duplicate input", body({key: make([txin(2), txin(2)])}), DUP_RULE, "DuplicateElement")
    conway_body(
        f"{label}: duplicate input written with a wide index",
        body({key: make([txin(2), txin(2, wide_index=True)])}),
        DUP_RULE,
        "DuplicateElement",
    )

conway_body("required signers: two distinct", body({14: sset([bstr(h(0x41, 28)), bstr(h(0x42, 28))])}))
conway_body("required signers: empty", body({14: sset([])}), EMPTY_RULE, "Empty")
conway_body(
    "required signers: duplicate", body({14: sset([bstr(h(0x41, 28)), bstr(h(0x41, 28))])}), DUP_RULE, "DuplicateElement"
)

# --- bodies: ordered sets ---------------------------------------------------------------------
conway_body("certificates: one", body({4: array([STAKE_REGISTRATION])}))
conway_body("certificates: two distinct", body({4: array([STAKE_REGISTRATION, STAKE_REGISTRATION_2])}))
conway_body("certificates: tag 258", body({4: sset([STAKE_REGISTRATION], tagged=True)}))
conway_body("certificates: empty", body({4: array([])}), EMPTY_RULE, "Empty")
conway_body("certificates: empty under tag 258", body({4: sset([], tagged=True)}), EMPTY_RULE, "Empty")
conway_body(
    "certificates: duplicate", body({4: array([STAKE_REGISTRATION, STAKE_REGISTRATION])}), DUP_RULE, "DuplicateElement"
)
conway_body(
    "certificates: duplicate under tag 258",
    body({4: sset([STAKE_REGISTRATION, STAKE_REGISTRATION_2, STAKE_REGISTRATION], tagged=True)}),
    DUP_RULE,
    "DuplicateElement",
)
conway_body("proposals: one", body({20: array([PROPOSAL])}))
conway_body("proposals: two distinct", body({20: array([PROPOSAL, PROPOSAL_2])}))
conway_body("proposals: tag 258", body({20: sset([PROPOSAL], tagged=True)}))
conway_body("proposals: empty", body({20: array([])}), EMPTY_RULE, "Empty")
conway_body("proposals: duplicate", body({20: array([PROPOSAL, PROPOSAL])}), DUP_RULE, "DuplicateElement")

# --- bodies: maps -----------------------------------------------------------------------------
conway_body("withdrawals: one", body({5: cmap([(WITHDRAWAL, uint(5))])}))
conway_body("withdrawals: empty", body({5: cmap([])}), EMPTY_RULE, "Empty")
conway_body(
    "withdrawals: repeated account",
    body({5: cmap([(WITHDRAWAL, uint(5)), (WITHDRAWAL, uint(6))])}),
    DUP_RULE,
    "DuplicateElement",
)
conway_body(
    "withdrawals: repeated account, indefinite length",
    body({5: cmap([(WITHDRAWAL, uint(5)), (WITHDRAWAL, uint(5))], indef=True)}),
    DUP_RULE,
    "DuplicateElement",
)
conway_body("votes: one voter, one vote", body({19: cmap([(VOTER, cmap([(ACTION, VOTE)]))])}))
conway_body(
    "votes: two voters, two actions",
    body({19: cmap([(VOTER, cmap([(ACTION, VOTE), (ACTION_2, VOTE)])), (VOTER_2, cmap([(ACTION, VOTE)]))])}),
)
conway_body("votes: empty", body({19: cmap([])}), EMPTY_RULE, "Empty")
conway_body(
    "votes: repeated voter",
    body({19: cmap([(VOTER, cmap([(ACTION, VOTE)])), (VOTER, cmap([(ACTION_2, VOTE)]))])}),
    DUP_RULE,
    "DuplicateElement",
)
conway_body(
    "votes: repeated action",
    body({19: cmap([(VOTER, cmap([(ACTION, VOTE), (ACTION, VOTE)]))])}),
    DUP_RULE,
    "DuplicateElement",
)
conway_body("votes: voter without votes", body({19: cmap([(VOTER, cmap([]))])}), "didn't have any", "Empty")

# --- bodies: other guards ---------------------------------------------------------------------
conway_body("mint: one asset", body({9: MINT}))
conway_body("mint: empty", body({9: cmap([])}), EMPTY_RULE, "Empty")
conway_body("donation: positive", body({22: uint(1)}))
conway_body("donation: zero", body({22: uint(0)}), "non-zero", "Zero")
conway_body("treasury value: zero is accepted", body({21: uint(0)}))
conway_body("update (key 6): not a field of Conway", body({6: array([cmap([]), uint(0)])}), "not a valid key", "UnknownFields")
conway_body("unknown key 10", body({10: uint(0)}), "not a valid key", "UnknownFields")
conway_body("unknown key 12", body({12: uint(0)}), "not a valid key", "UnknownFields")

# --- bodies: Babbage keeps what Conway refuses (decoder version 7) ---------------------------
case("babbage body baseline", "babbage", "body", body())
case("babbage inputs: duplicate input is collapsed", "babbage", "body", body(inputs=sset([txin(1), txin(1)])))
case("babbage inputs: empty collateral is accepted", "babbage", "body", body({13: sset([])}))
case("babbage certificates: duplicate is kept", "babbage", "body", body({4: array([STAKE_REGISTRATION, STAKE_REGISTRATION])}))
case("babbage update (key 6) is a field", "babbage", "body", body({6: array([cmap([]), uint(0)])}))
case("babbage inputs: tag 258 is not read", "babbage", "body", body(inputs=sset([txin(1)], tagged=True)), "expected list len or indef", "Expected")

# --- witness sets -----------------------------------------------------------------------------
conway_wits("witnesses: empty set", cmap([]))
for key, label, one, two in (
    (0, "vkey witnesses", vkey_witness(0), vkey_witness(1)),
    (2, "bootstrap witnesses", BOOTSTRAP, array([bstr(h(0x93, 32)), bstr(h(0xA3, 64)), bstr(h(0x94, 32)), bstr(cmap([]))])),
    (1, "native scripts", NATIVE, NATIVE_2),
    (4, "datums", DATUM, DATUM_2),
):
    conway_wits(f"{label}: one", wits(key, array([one])))
    conway_wits(f"{label}: tag 258", wits(key, sset([one, two], tagged=True)))
    conway_wits(f"{label}: indefinite length", wits(key, array([one, two], indef=True)))
    conway_wits(f"{label}: duplicate is tolerated", wits(key, array([one, one])))
    conway_wits(f"{label}: duplicate under tag 258 is tolerated", wits(key, sset([one, two, one], tagged=True)))
    conway_wits(f"{label}: empty list", wits(key, array([])), "Empty list found", "Empty")
    conway_wits(f"{label}: empty set under tag 258", wits(key, sset([], tagged=True)), "Empty list found", "Empty")

conway_wits(
    "vkey witnesses: duplicate vkey with another signature is a second element",
    wits(0, array([vkey_witness(0), array([bstr(h(0x90, 32)), bstr(h(0xB0, 64))])])),
)

for key, label in ((3, "Plutus V1 scripts"), (6, "Plutus V2 scripts"), (7, "Plutus V3 scripts")):
    conway_wits(f"{label}: one", wits(key, array([SCRIPT])))
    conway_wits(f"{label}: tag 258", wits(key, sset([SCRIPT, SCRIPT_2], tagged=True)))
    conway_wits(f"{label}: indefinite length", wits(key, array([SCRIPT, SCRIPT_2], indef=True)))
    conway_wits(f"{label}: empty list", wits(key, array([])), "Empty list of scripts is not allowed", "Empty")
    conway_wits(
        f"{label}: empty set under tag 258", wits(key, sset([], tagged=True)), "Empty list of scripts is not allowed", "Empty"
    )
    conway_wits(f"{label}: duplicate script", wits(key, array([SCRIPT, SCRIPT])), DUP_RULE, "DuplicateElement")
    conway_wits(
        f"{label}: duplicate script under tag 258",
        wits(key, sset([SCRIPT, SCRIPT_2, SCRIPT], tagged=True)),
        DUP_RULE,
        "DuplicateElement",
    )

conway_wits(
    "Plutus scripts: the same bytes under V1 and V2 are two scripts",
    cmap([(uint(3), array([SCRIPT])), (uint(6), array([SCRIPT]))]),
)
conway_wits(
    "Plutus scripts: the same bytes under V1, V2 and V3 are three scripts",
    cmap([(uint(3), array([SCRIPT])), (uint(6), array([SCRIPT])), (uint(7), array([SCRIPT]))]),
)
conway_wits("Plutus scripts: a V1 script and a V2 script", cmap([(uint(3), array([SCRIPT])), (uint(6), array([SCRIPT_2]))]))

conway_wits("redeemers: list", wits(5, REDEEMER_LIST))
conway_wits("redeemers: map", wits(5, REDEEMER_MAP))
conway_wits("redeemers: indefinite list", wits(5, array([array([uint(0), uint(0), uint(0), array([uint(1), uint(2)])])], indef=True)))
conway_wits("redeemers: indefinite map", wits(5, cmap([(array([uint(0), uint(0)]), array([uint(0), array([uint(1), uint(2)])]))], indef=True)))
conway_wits("redeemers: repeated key in a list is tolerated", wits(5, REDEEMER_LIST_DUPLICATE))
conway_wits("redeemers: repeated key in a map is tolerated", wits(5, REDEEMER_MAP_DUPLICATE))
conway_wits("redeemers: empty list", wits(5, array([])), "Empty list found", "Empty")
conway_wits("redeemers: empty map", wits(5, cmap([])), "Expected redeemers map to be non-empty", "Empty")
conway_wits("redeemers: tagged list", wits(5, sset([array([uint(0), uint(0), uint(0), array([uint(1), uint(2)])])], tagged=True)), "expected list len or indef", "Expected")
conway_wits("witnesses: unknown key 8", wits(8, array([])), "not a valid key", "UnknownFields")
conway_wits(
    "witnesses: a populated set",
    cmap(
        [
            (uint(0), array([vkey_witness(0)])),
            (uint(1), array([NATIVE])),
            (uint(3), array([SCRIPT])),
            (uint(4), array([DATUM])),
            (uint(5), REDEEMER_LIST),
            (uint(6), array([SCRIPT_2])),
        ]
    ),
)

# --- whole transactions ---------------------------------------------------------------------
def transaction(b, w=None):
    return array([b, w if w is not None else cmap([]), TRUE, NULL])


case("transaction baseline", "conway", "transaction", transaction(body()))
case(
    "transaction: duplicate input in the body",
    "conway",
    "transaction",
    transaction(body(inputs=sset([txin(1), txin(1)]))),
    DUP_RULE,
    "DuplicateElement",
)
case(
    "transaction: empty vkey witness list in the witness set",
    "conway",
    "transaction",
    transaction(body(), wits(0, array([]))),
    "Empty list found",
    "Empty",
)
case(
    "transaction: duplicate Plutus script in the witness set",
    "conway",
    "transaction",
    transaction(body(), wits(3, array([SCRIPT, SCRIPT]))),
    DUP_RULE,
    "DuplicateElement",
)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", required=True)
    args = parser.parse_args()
    names = [c["name"] for c in CASES]
    assert len(names) == len(set(names)), "case names must be unique"
    with open(args.out, "w") as f:
        json.dump(CASES, f, indent=1)
        f.write("\n")


if __name__ == "__main__":
    main()
