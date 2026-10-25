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
import re


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


# The cases a test names directly keep an id that a rewording of their name does not change; every
# other case is identified by its name, lower-cased with runs of other characters as one "-".
STABLE_IDS = {
    "inputs: duplicate input": "inputs-duplicate",
    "inputs: tag 258 is accepted": "inputs-tag-258",
    "vkey witnesses: duplicate is tolerated": "vkeys-duplicate-tolerated",
    "Plutus scripts: the same bytes under V1 and V2 are two scripts": "plutus-same-bytes-two-languages",
}


def case_id(name):
    return STABLE_IDS.get(name) or re.sub(r"[^a-z0-9]+", "-", name.lower()).strip("-")


def case(name, era, kind, raw, rule=None, expect=None):
    """`rule` is a fragment of the reference's failure text, `expect` the Scarab error case."""
    CASES.append(
        {
            "id": case_id(name),
            "name": name,
            "era": era,
            "kind": kind,
            "rule": rule,
            "expect": expect,
            "bytes": raw.hex(),
        }
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

# --- bodies: ordered sets of elements whose decoded value is not its encoding ------------------
# The reference compares the decoded elements under their derived `Ord` (OSet, Strict.hs), so two
# encodings of one pool registration or one governance action are one element and a repeat fails.


HALF = tag(30, array([uint(1), uint(2)]))
HALF_UNREDUCED = tag(30, array([uint(2), uint(4)]))
POOL_ID = h(0x61, 28)
RELAY_ADDRESS = array([uint(0), uint(3001), bstr(b"\x7f\x00\x00\x01"), NULL])
RELAY_ADDRESS_WIDE = array([uint(0), uint(3001, 4), bstr(b"\x7f\x00\x00\x01"), NULL], width=2)
RELAY_NAME = array([uint(1), NULL, text("relay.example.invalid")])
RELAY_MULTI = array([uint(2), text("srv.example.invalid")])
METADATA_URL = "https://example.invalid/pool.json"
METADATA = array([text(METADATA_URL), bstr(h(0x65, 32))])


def pool_cert(
    operator=POOL_ID,
    pledge=uint(1),
    margin=HALF,
    owners=None,
    relays=None,
    metadata=NULL,
):
    return array(
        [
            uint(3),
            bstr(operator),
            bstr(h(0x63, 32)),
            pledge,
            uint(340_000_000),
            margin,
            bstr(b"\xe0" + h(0x64, 28)),
            owners if owners is not None else sset([bstr(h(0x62, 28))]),
            relays if relays is not None else array([]),
            metadata,
        ]
    )


def certs(*items, tagged=False):
    return body({4: sset(list(items), tagged=tagged)})


def repeat(name, raw):
    conway_body(name, raw, DUP_RULE, "DuplicateElement")


POOL = pool_cert()
conway_body("pool registrations: one", certs(POOL))
conway_body("pool registrations: distinct operators", certs(POOL, pool_cert(operator=h(0x66, 28))))
conway_body("pool registrations: distinct pledge", certs(POOL, pool_cert(pledge=uint(2))))
conway_body(
    "pool registrations: distinct relays",
    certs(pool_cert(relays=array([RELAY_ADDRESS])), pool_cert(relays=array([RELAY_NAME]))),
)
conway_body(
    "pool registrations: the same relays in another order",
    certs(
        pool_cert(relays=array([RELAY_ADDRESS, RELAY_NAME])),
        pool_cert(relays=array([RELAY_NAME, RELAY_ADDRESS])),
    ),
)
conway_body(
    "pool registrations: distinct metadata",
    certs(
        pool_cert(metadata=METADATA),
        pool_cert(metadata=array([text("https://example.invalid/other.json"), bstr(h(0x65, 32))])),
    ),
)
conway_body("pool registrations: with and without metadata", certs(pool_cert(metadata=METADATA), POOL))
repeat("pool registrations: exact repeat", certs(POOL, POOL))
repeat(
    "pool registrations: repeat with the empty relay sequence written indefinite",
    certs(POOL, pool_cert(relays=b"\x9f\xff")),
)
repeat(
    "pool registrations: repeat with the relays written indefinite",
    certs(
        pool_cert(relays=array([RELAY_ADDRESS, RELAY_NAME])),
        pool_cert(relays=array([RELAY_ADDRESS, RELAY_NAME], indef=True)),
    ),
)
repeat(
    "pool registrations: repeat with a relay written with wide headers",
    certs(pool_cert(relays=array([RELAY_ADDRESS])), pool_cert(relays=array([RELAY_ADDRESS_WIDE]))),
)
repeat(
    "pool registrations: repeat with a relay array written indefinite",
    certs(
        pool_cert(relays=array([RELAY_MULTI])),
        pool_cert(relays=array([array([uint(2), text("srv.example.invalid")], indef=True)])),
    ),
)
repeat(
    "pool registrations: repeat with the metadata written indefinite",
    certs(
        pool_cert(metadata=METADATA),
        pool_cert(metadata=array([text(METADATA_URL), bstr(h(0x65, 32))], indef=True)),
    ),
)
repeat(
    "pool registrations: repeat with the margin not in lowest terms",
    certs(POOL, pool_cert(margin=HALF_UNREDUCED)),
)
repeat(
    "pool registrations: repeat with the owners under tag 258",
    certs(POOL, pool_cert(owners=sset([bstr(h(0x62, 28))], tagged=True))),
)
repeat("pool registrations: repeat with a wide pledge", certs(POOL, pool_cert(pledge=uint(1, 8))))

# Certificates whose fields are all decoded values: repeats are found whatever the encoding.
CRED = array([uint(0), bstr(KEY_HASH)])
DREP_REGISTRATION = array([uint(16), CRED, uint(500_000_000), ANCHOR])
conway_body(
    "drep registrations: distinct deposits",
    certs(DREP_REGISTRATION, array([uint(16), CRED, uint(500_000_001), ANCHOR])),
)
conway_body(
    "stake delegations: distinct pools",
    certs(array([uint(2), CRED, bstr(POOL_ID)]), array([uint(2), CRED, bstr(h(0x67, 28))])),
)
repeat(
    "stake delegations: repeat with an indefinite array",
    certs(array([uint(2), CRED, bstr(POOL_ID)]), array([uint(2), CRED, bstr(POOL_ID)], indef=True)),
)

# Proposal procedures: the reference's `Ord GovAction` compares a parameter update field by field
# (a `PParamsUpdate`) and treasury withdrawals as a `Map`.


def proposal(action, deposit=1_000_000, anchor=ANCHOR):
    return array([uint(deposit), bstr(b"\xe1" + h(0x55, 28)), action, anchor])


def proposals(*items, tagged=False):
    return body({20: sset(list(items), tagged=tagged)})


def param_change(fields, indef=False, previous=NULL, policy=NULL):
    return array([uint(0), previous, cmap(fields, indef=indef), policy])


ACCOUNT_1 = bstr(b"\xe1" + h(0x71, 28))
ACCOUNT_2 = bstr(b"\xe0" + h(0x72, 28))
FIELDS = [(uint(0), uint(44)), (uint(1), uint(155_381))]
CHANGE = param_change(FIELDS)
conway_body("parameter changes: one", proposals(proposal(CHANGE)))
conway_body(
    "parameter changes: distinct values",
    proposals(proposal(CHANGE), proposal(param_change([(uint(0), uint(45)), (uint(1), uint(155_381))]))),
)
conway_body(
    "parameter changes: distinct fields",
    proposals(proposal(CHANGE), proposal(param_change([(uint(0), uint(44))]))),
)
conway_body(
    "parameter changes: distinct guardrails script",
    proposals(proposal(CHANGE), proposal(param_change(FIELDS, policy=bstr(h(0x73, 28))))),
)
repeat("parameter changes: exact repeat", proposals(proposal(CHANGE), proposal(CHANGE)))
repeat(
    "parameter changes: repeat with the fields in another order",
    proposals(proposal(CHANGE), proposal(param_change(list(reversed(FIELDS))))),
)
repeat(
    "parameter changes: repeat with the update written indefinite",
    proposals(proposal(CHANGE), proposal(param_change(FIELDS, indef=True))),
)
repeat(
    "parameter changes: repeat with wide arguments",
    proposals(
        proposal(CHANGE),
        proposal(param_change([(uint(0, 4), uint(44, 2)), (uint(1, 1), uint(155_381, 8))])),
    ),
)
RATIONAL_CHANGE = param_change([(uint(9), HALF)])
conway_body("parameter changes: a rational field", proposals(proposal(RATIONAL_CHANGE)))
repeat(
    "parameter changes: repeat with a rational not in lowest terms",
    proposals(proposal(RATIONAL_CHANGE), proposal(param_change([(uint(9), HALF_UNREDUCED)]))),
)
PRICE_ITEMS = [tag(30, array([uint(577), uint(10_000)])), tag(30, array([uint(721), uint(10_000_000)]))]
COST_MODEL = [(uint(0), array([uint(1), uint(2), uint(3)])), (uint(1), array([uint(4), uint(5)]))]
conway_body("parameter changes: a cost model", proposals(proposal(param_change([(uint(18), cmap(COST_MODEL))]))))
repeat(
    "parameter changes: repeat with the cost models in another order",
    proposals(
        proposal(param_change([(uint(18), cmap(COST_MODEL))])),
        proposal(param_change([(uint(18), cmap(list(reversed(COST_MODEL))))])),
    ),
)

WITHDRAWALS = [(ACCOUNT_1, uint(5)), (ACCOUNT_2, uint(6))]


def treasury(pairs, indef=False, policy=NULL):
    return array([uint(2), cmap(pairs, indef=indef), policy])


conway_body("treasury withdrawals: one", proposals(proposal(treasury(WITHDRAWALS))))
conway_body(
    "treasury withdrawals: distinct amounts",
    proposals(proposal(treasury(WITHDRAWALS)), proposal(treasury([(ACCOUNT_1, uint(5)), (ACCOUNT_2, uint(7))]))),
)
conway_body(
    "treasury withdrawals: distinct accounts",
    proposals(proposal(treasury(WITHDRAWALS)), proposal(treasury([(ACCOUNT_1, uint(5))]))),
)
repeat(
    "treasury withdrawals: exact repeat",
    proposals(proposal(treasury(WITHDRAWALS)), proposal(treasury(WITHDRAWALS))),
)
repeat(
    "treasury withdrawals: repeat with the entries in another order",
    proposals(proposal(treasury(WITHDRAWALS)), proposal(treasury(list(reversed(WITHDRAWALS))))),
)
repeat(
    "treasury withdrawals: repeat with the map written indefinite",
    proposals(proposal(treasury(WITHDRAWALS)), proposal(treasury(WITHDRAWALS, indef=True))),
)

# The other constructors keep decoded values only; their repeats are found under any encoding.
PREVIOUS = array([bstr(h(0x66, 32)), uint(0)])
FORK = array([uint(1), NULL, array([uint(10), uint(0)])])
conway_body(
    "hard forks: distinct versions",
    proposals(proposal(FORK), proposal(array([uint(1), NULL, array([uint(10), uint(1)])]))),
)
repeat(
    "hard forks: repeat with the version written indefinite",
    proposals(proposal(FORK), proposal(array([uint(1), NULL, array([uint(10), uint(0)], indef=True)]))),
)
MEMBERS = [array([uint(0), bstr(h(0x74, 28))]), array([uint(0), bstr(h(0x75, 28))])]
ADDED = [(array([uint(0), bstr(h(0x76, 28))]), uint(100)), (array([uint(1), bstr(h(0x77, 28))]), uint(200))]
COMMITTEE = array([uint(4), NULL, sset(MEMBERS), cmap(ADDED), HALF])
COMMITTEE_REWRITTEN = array(
    [
        uint(4),
        NULL,
        sset(list(reversed(MEMBERS)), tagged=True),
        cmap(list(reversed(ADDED)), indef=True),
        HALF_UNREDUCED,
    ]
)
conway_body("committee updates: one", proposals(proposal(COMMITTEE)))
repeat(
    "committee updates: repeat with every collection written another way",
    proposals(proposal(COMMITTEE), proposal(COMMITTEE_REWRITTEN)),
)
# --- rationals: the reference reads tag 30 as `n % d` over signed integers of any size ------------
# At decoder version 9 `decodeRationalWithTag` (Plain.hs) reads two `decodeInteger`s, so 1/2 is
# also [-1, -2] and [2^64, 2^65] (bignums), and the bound is checked on the reduced value.


def nint(v):
    """The negative integer v (< 0) in its shortest form."""
    return head(1, -1 - v)


def bignum(v):
    raw = v.to_bytes((v.bit_length() + 7) // 8, "big")
    return tag(2, bstr(raw))


def ratio(n, d):
    return tag(30, array([n, d]))


HALF_SIGNED = ratio(nint(-1), nint(-2))
HALF_BIG = ratio(bignum(2**64), bignum(2**65))
HALF_MIXED = ratio(nint(-3), nint(-6))
THIRD_SIGNED = ratio(nint(-1), nint(-3))


def signed(n, d):
    return ratio(nint(-n), nint(-d))


repeat(
    "parameter changes: repeat with a rational written with negative parts",
    proposals(proposal(RATIONAL_CHANGE), proposal(param_change([(uint(9), HALF_SIGNED)]))),
)
repeat(
    "parameter changes: repeat with a rational written as bignums",
    proposals(proposal(RATIONAL_CHANGE), proposal(param_change([(uint(9), HALF_BIG)]))),
)
repeat(
    "parameter changes: repeat with a rational written with negative parts not in lowest terms",
    proposals(proposal(param_change([(uint(9), HALF_SIGNED)])), proposal(param_change([(uint(9), HALF_MIXED)]))),
)
conway_body(
    "parameter changes: a rational and a different one with negative parts",
    proposals(proposal(RATIONAL_CHANGE), proposal(param_change([(uint(9), THIRD_SIGNED)]))),
)
PRICES = array([ratio(uint(577), uint(10_000)), ratio(uint(721), uint(10_000_000))])
PRICES_SIGNED = array([signed(577, 10_000), signed(721, 10_000_000)])
PRICES_OTHER = array([signed(577, 10_000), signed(722, 10_000_000)])
conway_body("parameter changes: prices", proposals(proposal(param_change([(uint(19), PRICES)]))))
repeat(
    "parameter changes: repeat with prices written with negative parts",
    proposals(proposal(param_change([(uint(19), PRICES)])), proposal(param_change([(uint(19), PRICES_SIGNED)]))),
)
conway_body(
    "parameter changes: prices that differ in one signed part",
    proposals(proposal(param_change([(uint(19), PRICES)])), proposal(param_change([(uint(19), PRICES_OTHER)]))),
)
repeat(
    "parameter changes: repeat with prices written as bignums",
    proposals(
        proposal(param_change([(uint(19), array([ratio(uint(1), uint(2)), ratio(uint(1), uint(3))]))])),
        proposal(param_change([(uint(19), array([HALF_BIG, ratio(bignum(2**64), bignum(3 * 2**64))]))])),
    ),
)
POOL_THRESHOLDS = array([ratio(uint(i), uint(10)) for i in range(1, 6)])
POOL_THRESHOLDS_SIGNED = array([signed(i, 10) for i in range(1, 6)])
POOL_THRESHOLDS_OTHER = array([signed(i, 10) for i in (1, 2, 3, 4, 6)])
conway_body("parameter changes: pool voting thresholds", proposals(proposal(param_change([(uint(25), POOL_THRESHOLDS)]))))
repeat(
    "parameter changes: repeat with pool voting thresholds written with negative parts",
    proposals(
        proposal(param_change([(uint(25), POOL_THRESHOLDS)])),
        proposal(param_change([(uint(25), POOL_THRESHOLDS_SIGNED)])),
    ),
)
conway_body(
    "parameter changes: pool voting thresholds that differ in one signed part",
    proposals(
        proposal(param_change([(uint(25), POOL_THRESHOLDS)])),
        proposal(param_change([(uint(25), POOL_THRESHOLDS_OTHER)])),
    ),
)
DREP_THRESHOLDS = array([ratio(uint(i), uint(20)) for i in range(1, 11)])
DREP_THRESHOLDS_SIGNED = array([signed(i, 20) for i in range(1, 11)])
repeat(
    "parameter changes: repeat with DRep voting thresholds written with negative parts",
    proposals(
        proposal(param_change([(uint(26), DREP_THRESHOLDS)])),
        proposal(param_change([(uint(26), DREP_THRESHOLDS_SIGNED)])),
    ),
)
conway_body(
    "parameter changes: the reference script fee",
    proposals(proposal(param_change([(uint(33), ratio(uint(15), uint(1))), ]))),
)
repeat(
    "parameter changes: repeat with the reference script fee written with negative parts",
    proposals(
        proposal(param_change([(uint(33), ratio(uint(15), uint(1))), ])),
        proposal(param_change([(uint(33), signed(30, 2)), ])),
    ),
)

# Pool margin and committee quorum are `UnitInterval`s: the reduced value must lie in [0, 1].
conway_body("pool margin: signed parts that reduce to one half", certs(pool_cert(margin=HALF_SIGNED)))
conway_body("pool margin: bignum parts that reduce to one half", certs(pool_cert(margin=HALF_BIG)))
conway_body("pool margin: one", certs(pool_cert(margin=ratio(uint(3), uint(3)))))
conway_body("pool margin: zero over a large denominator", certs(pool_cert(margin=ratio(uint(0), bignum(2**64 - 1)))))
for label, margin, rule in (
    ("a negative value", ratio(nint(-1), uint(2)), "BoundedRatio"),
    ("a value above one", ratio(uint(3), uint(2)), "BoundedRatio"),
    ("a value above one with negative parts", signed(3, 2), "BoundedRatio"),
    ("a reduced numerator that does not fit 64 bits", ratio(bignum(2**64 + 1), uint(1)), "BoundedRatio"),
):
    conway_body(f"pool margin: {label}", certs(pool_cert(margin=margin)), rule, "RationalOutOfBounds")
conway_body(
    "pool margin: zero denominator",
    certs(pool_cert(margin=ratio(uint(1), uint(0)))),
    "Denominator cannot be zero",
    "ZeroDenominator",
)
conway_body(
    "pool margin: zero denominator written negative",
    certs(pool_cert(margin=ratio(nint(-1), tag(2, bstr(b"")))) ),
    "Denominator cannot be zero",
    "ZeroDenominator",
)
conway_body(
    "committee quorum: signed parts that reduce to one half",
    proposals(proposal(array([uint(4), NULL, sset([]), cmap([]), HALF_SIGNED]))),
)
conway_body(
    "committee quorum: bignum parts that reduce to one half",
    proposals(proposal(array([uint(4), NULL, sset([]), cmap([]), HALF_BIG]))),
)
conway_body(
    "committee quorum: a value above one with negative parts",
    proposals(proposal(array([uint(4), NULL, sset([]), cmap([]), signed(3, 2)]))),
    "BoundedRatio",
    "RationalOutOfBounds",
)
repeat(
    "committee updates: repeat with the quorum written with negative parts",
    proposals(
        proposal(array([uint(4), NULL, sset(MEMBERS), cmap(ADDED), HALF])),
        proposal(array([uint(4), NULL, sset(MEMBERS), cmap(ADDED), HALF_SIGNED])),
    ),
)
repeat(
    "pool registrations: repeat with the margin written with negative parts",
    certs(POOL, pool_cert(margin=HALF_SIGNED)),
)
repeat(
    "pool registrations: repeat with the margin written as bignums",
    certs(POOL, pool_cert(margin=HALF_BIG)),
)

# cborg's `decodeInteger` takes a bignum only from the one-byte heads 0xc2 / 0xc3, over a definite
# byte string (`readBigUInt` reads it with `tryConsumeBytes`); a widened tag head or an indefinite
# string is not an integer. A definite string of any length head, with leading zeros, is one.
BIGNUM_INDEFINITE = b"\xc2\x5f" + bstr(b"\x01") + b"\xff"
BIGNUM_WIDE_TAG = b"\xd8\x02" + bstr(b"\x01")
BIGNUM_WIDE_TAG_3 = b"\xd8\x03" + bstr(b"\x00")
BIGNUM_NEGATIVE_INDEFINITE = b"\xc3\x5f" + bstr(b"\x00") + b"\xff"
BIGNUM_WIDE_LENGTH = b"\xc2\x58\x01\x01"
BIGNUM_LEADING_ZEROS = b"\xc2\x42\x00\x02"
BIGNUM_MINUS_ONE = b"\xc3\x40"
BIGNUM_TWO = b"\xc2" + bstr(b"\x02")

for label, numerator in (
    ("a bignum over an indefinite byte string", BIGNUM_INDEFINITE),
    ("a bignum tag with a widened head", BIGNUM_WIDE_TAG),
    ("a negative bignum tag with a widened head", BIGNUM_WIDE_TAG_3),
    ("a negative bignum over an indefinite byte string", BIGNUM_NEGATIVE_INDEFINITE),
):
    denominator = nint(-2) if numerator in (BIGNUM_WIDE_TAG_3, BIGNUM_NEGATIVE_INDEFINITE) else uint(2)
    conway_body(f"pool margin: {label}", certs(pool_cert(margin=ratio(numerator, denominator))), "expected integer", "Expected")
conway_body(
    "pool margin: a denominator that is a bignum tag with a widened head",
    certs(pool_cert(margin=ratio(uint(1), BIGNUM_WIDE_TAG))),
    "expected integer",
    "Expected",
)
conway_body(
    "committee quorum: a bignum over an indefinite byte string",
    proposals(proposal(array([uint(4), NULL, sset([]), cmap([]), ratio(BIGNUM_INDEFINITE, uint(2))]))),
    "expected integer",
    "Expected",
)
conway_body("pool margin: a bignum with a wide length head", certs(pool_cert(margin=ratio(BIGNUM_WIDE_LENGTH, uint(2)))))
conway_body("pool margin: a bignum with leading zeros", certs(pool_cert(margin=ratio(uint(1), BIGNUM_LEADING_ZEROS))))
conway_body(
    "pool margin: a negative bignum for minus one over minus two",
    certs(pool_cert(margin=ratio(BIGNUM_MINUS_ONE, nint(-2)))),
)
conway_body(
    "pool margin: integers written with wide heads",
    certs(pool_cert(margin=tag(30, array([uint(1, 8), uint(2, 4)], width=4)))),
)
conway_body(
    "pool margin: negative integers written with wide heads",
    certs(pool_cert(margin=tag(30, array([head(1, 0, 8), head(1, 1, 2)])))),
)
repeat(
    "parameter changes: repeat with a rational written as definite bignums with other heads",
    proposals(
        proposal(RATIONAL_CHANGE),
        proposal(param_change([(uint(9), ratio(BIGNUM_WIDE_LENGTH, BIGNUM_LEADING_ZEROS))])),
    ),
)
repeat(
    "parameter changes: repeat with a rational written with wide integer heads",
    proposals(
        proposal(RATIONAL_CHANGE),
        proposal(param_change([(uint(9), tag(30, array([uint(1, 8), uint(2, 4)], width=4))) ])),
    ),
)
CONSTITUTION = array([uint(5), NULL, array([ANCHOR, NULL])])
conway_body("constitutions: one", proposals(proposal(CONSTITUTION)))
conway_body(
    "constitutions: distinct guardrails script",
    proposals(proposal(CONSTITUTION), proposal(array([uint(5), NULL, array([ANCHOR, bstr(h(0x78, 28))])]))),
)
INFO = array([uint(6)])
conway_body("information actions: one", proposals(proposal(INFO)))
conway_body("information actions: distinct deposits", proposals(proposal(INFO), proposal(INFO, deposit=2_000_000)))
conway_body(
    "no confidence: distinct previous action ids",
    proposals(proposal(array([uint(3), PREVIOUS])), proposal(array([uint(3), array([bstr(h(0x66, 32)), uint(1)])]))),
)
repeat(
    "no confidence: repeat with the previous action id written wide",
    proposals(
        proposal(array([uint(3), PREVIOUS])),
        proposal(array([uint(3), array([bstr(h(0x66, 32)), uint(0, 2)])])),
    ),
)

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


# --- output addresses of header kind 8: a Byron address is decoded in full (CRC-protected) --------
def crc32(b):
    import zlib

    return zlib.crc32(b) & 0xFFFFFFFF


def byron_address(crc_xor=0, trailing=b""):
    """`[#6.24(bytes .cbor [root, {}, 0]), crc32]` of a made-up root; `crc_xor` damages the checksum."""
    payload = array([bstr(h(0x11, 28)), cmap([]), uint(0)])
    return array([tag(24, bstr(payload)), uint(crc32(payload) ^ crc_xor)]) + trailing


def byron_output_body(address):
    return body({1: array([array([bstr(address), uint(2_000_000)])])})


for era in ("conway", "babbage"):
    case(f"{era} output: Byron address with a valid checksum", era, "body", byron_output_body(byron_address()))
    case(
        f"{era} output: Byron address with a wrong checksum",
        era,
        "body",
        byron_output_body(byron_address(crc_xor=1)),
        "was not the computed one",
        "CrcMismatch",
    )
    case(
        f"{era} output: Byron address followed by a byte",
        era,
        "body",
        byron_output_body(byron_address(trailing=b"\x00")),
        "DecoderErrorLeftover",
        "Syntax",
    )
    case(
        f"{era} output: header kind 8 that is no Byron envelope",
        era,
        "body",
        byron_output_body(b"\x83\x00\x00\x00"),
        "Unused bits are not suppose to be set",
        "AddressHeader",
    )
    case(
        f"{era} output: header kind 8 truncated",
        era,
        "body",
        byron_output_body(b"\x82\x00"),
        "expected tag",
        "Syntax",
    )


# --- Byron addresses: what the reference's version-1 decoder refuses and what it keeps ----------
def ibstr(b):
    """An indefinite-length byte string of one chunk."""
    return b"\x5f" + bstr(b) + b"\xff"


def attrs(pairs, indef=False):
    """An attribute map; `pairs` are (encoded key, encoded value) so a case can write either badly."""
    return cmap(pairs, indef=indef)


def path_attr(path=b"\x01\x02", wide=False):
    """Attribute 1: a byte string that holds the CBOR of the derivation path (a byte string)."""
    inner = head(2, len(path), 1) + path if wide else bstr(path)
    return (uint(1), bstr(inner))


def magic_attr(magic=42, width=None):
    """Attribute 2: a byte string that holds the CBOR of the network magic (a Word32)."""
    return (uint(2), bstr(uint(magic, width)))


def byron_payload(root=None, attr=None, kind=None, parts=None):
    root = bstr(h(0x11, 28)) if root is None else root
    attr = cmap([]) if attr is None else attr
    kind = uint(0) if kind is None else kind
    return array([root, attr, kind]) if parts is None else parts


def byron_envelope(payload, tag_head=None, payload_item=None, crc=None, outer=None, trailing=b""):
    """The Byron `Address`: `[#6.24(bytes .cbor payload), crc32]`."""
    item = bstr(payload) if payload_item is None else payload_item
    tagged = (tag_head if tag_head is not None else head(6, 24)) + item
    checksum = uint(crc32(payload)) if crc is None else crc
    return (outer if outer is not None else b"\x82") + tagged + checksum + trailing


def byron_address_from(**parts):
    return byron_envelope(byron_payload(**parts))


BYRON_ACCEPTED = (
    ("tag 24 with a wide head", byron_envelope(byron_payload(), tag_head=b"\xd9\x00\x18")),
    (
        "payload length with a wide head",
        byron_envelope(byron_payload(), payload_item=head(2, len(byron_payload()), 2) + byron_payload()),
    ),
    ("crc with a wide head", byron_envelope(byron_payload(), crc=uint(crc32(byron_payload()), 8))),
    (
        "body list with a wide head",
        byron_address_from(parts=array([bstr(h(0x11, 28)), cmap([]), uint(0)], width=1)),
    ),
    ("root length with a wide head", byron_address_from(root=head(2, 28, 2) + h(0x11, 28))),
    (
        "attribute map with a wide key and length",
        byron_address_from(attr=head(5, 1, 1) + uint(1, 1) + bstr(bstr(b"\x01"))),
    ),
    ("derivation path and network magic", byron_address_from(attr=attrs([path_attr(), magic_attr()]))),
    (
        "unknown attribute kept with a wide length",
        byron_address_from(attr=attrs([(uint(5), head(2, 2, 1) + b"\x01\x02")])),
    ),
    ("redeem type", byron_address_from(kind=uint(2))),
)

BYRON_REFUSED = (
    ("indefinite payload bytes", byron_envelope(byron_payload(), payload_item=ibstr(byron_payload()))),
    (
        "indefinite body list",
        byron_address_from(parts=b"\x9f" + bstr(h(0x11, 28)) + cmap([]) + uint(0) + b"\xff"),
    ),
    ("indefinite root bytes", byron_address_from(root=ibstr(h(0x11, 28)))),
    ("indefinite attribute map", byron_address_from(attr=attrs([path_attr()], indef=True))),
    ("indefinite attribute value", byron_address_from(attr=attrs([(uint(5), ibstr(b"\x01"))]))),
    ("attribute keys descending", byron_address_from(attr=attrs([magic_attr(), path_attr()]))),
    ("attribute key repeated", byron_address_from(attr=attrs([path_attr(), path_attr(b"\x03")]))),
    ("attribute key 256", byron_address_from(attr=attrs([(uint(256), bstr(b"\x01"))]))),
    ("attribute key above Word32", byron_address_from(attr=attrs([(uint(2**32 + 1), bstr(b"\x01"))]))),
    ("type with a wide head", byron_address_from(kind=uint(0, 1))),
    ("type 1", byron_address_from(kind=uint(1))),
    ("type 3", byron_address_from(kind=uint(3))),
    ("type 256", byron_address_from(kind=uint(256))),
    ("derivation path with a wide length", byron_address_from(attr=attrs([path_attr(wide=True)]))),
    ("derivation path indefinite", byron_address_from(attr=attrs([(uint(1), bstr(ibstr(b"\x01\x02")))]))),
    ("derivation path that is no byte string", byron_address_from(attr=attrs([(uint(1), bstr(uint(7)))]))),
    (
        "derivation path followed by a byte",
        byron_address_from(attr=attrs([(uint(1), bstr(bstr(b"\x01") + b"\x00"))])),
    ),
    ("network magic with a wide head", byron_address_from(attr=attrs([magic_attr(5, 4)]))),
    ("network magic above Word32", byron_address_from(attr=attrs([magic_attr(2**32, 8)]))),
    ("root of 27 bytes", byron_address_from(root=bstr(h(0x11, 27)))),
    ("root of 29 bytes", byron_address_from(root=bstr(h(0x11, 29)))),
)

BYRON_REFUSAL = {
    "indefinite payload bytes": ("expected bytes", "IndefiniteLength"),
    "indefinite body list": ("expected list len", "IndefiniteLength"),
    "indefinite root bytes": ("expected string", "IndefiniteLength"),
    "indefinite attribute map": ("expected map len", "IndefiniteLength"),
    "indefinite attribute value": ("expected bytes", "IndefiniteLength"),
    "attribute keys descending": ("Canonicity violation while decoding Map", "Expected"),
    "attribute key repeated": ("Canonicity violation while decoding Map", "Expected"),
    "attribute key 256": ("expected word8", "NumberOutOfRange"),
    "attribute key above Word32": ("expected word8", "NumberOutOfRange"),
    "type with a wide head": ("non-canonical word8", "Expected"),
    "type 1": ("Found unknown tag 1 while decoding AddrType", "UnknownTag"),
    "type 3": ("Found unknown tag 3 while decoding AddrType", "UnknownTag"),
    "type 256": ("expected word8", "NumberOutOfRange"),
    "derivation path with a wide length": ("non-canonical length prefix", "Expected"),
    "derivation path indefinite": ("expected bytes", "IndefiniteLength"),
    "derivation path that is no byte string": ("expected bytes", "Expected"),
    "derivation path followed by a byte": ("Found unexpected leftover bytes while decoding HDAddressPayload", "Syntax"),
    "network magic with a wide head": ("non-canonical word32", "Expected"),
    "network magic above Word32": ("expected word32", "NumberOutOfRange"),
    "root of 27 bytes": ("Bytes not expected length", "ByteLength"),
    "root of 29 bytes": ("Bytes not expected length", "ByteLength"),
}

BYRON_HEADERS = (("0x81", b"\x81"), ("0x83", b"\x83"), ("0x8f", b"\x8f"))

for era in ("conway", "babbage"):
    for label, address in BYRON_ACCEPTED:
        case(f"{era} output: Byron address, {label}", era, "body", byron_output_body(address))
    for label, address in BYRON_REFUSED:
        rule, expect = BYRON_REFUSAL[label]
        case(f"{era} output: Byron address, {label}", era, "body", byron_output_body(address), rule, expect)
    for label, outer in BYRON_HEADERS:
        case(
            f"{era} output: Byron address under the header byte {label}",
            era,
            "body",
            byron_output_body(byron_envelope(byron_payload(), outer=outer)),
            "Unused bits are not suppose to be set",
            "AddressHeader",
        )


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", required=True)
    args = parser.parse_args()
    names = [c["name"] for c in CASES]
    assert len(names) == len(set(names)), "case names must be unique"
    ids = [c["id"] for c in CASES]
    assert len(ids) == len(set(ids)), "case ids must be unique"
    with open(args.out, "w") as f:
        json.dump(CASES, f, indent=1)
        f.write("\n")


if __name__ == "__main__":
    main()
