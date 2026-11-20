# SPDX-License-Identifier: Apache-2.0
"""The Dijkstra script-context cases no recorded transaction covers, for the chain context harness (#85, #54).

Each case is a Dijkstra top-level transaction with the outputs it spends and references, in the input of the
chain harness (`chain/Main.hs`): the environment of the chain context fixture (its protocol version, the cost
models of Plutus V1 to V4 in force, its system start and slot length), so that Plutus V1 to V3 scripts are
collected with their cost models. The cases reach what the chain's transactions do not: the refusals of a Plutus
V1 to V3 context in a Dijkstra transaction (`guardDijkstraFeaturesForPlutusV1toV3`, Conway's
`guardConwayFeaturesForPlutusV1V2`, an unsupported certificate, a script in a sub-transaction), a guard's context
whose sub-transaction fails (`SubTxContextError`) and a guard's redeemer with no script
(`ScriptHashNotFoundForPurpose`), beside transactions of every language that are collected. The scripts are
placeholders that are never evaluated.

    python3 -I scripts/oracle/leios-testnet/leios_context_refusal_cases.py > cases.json
    python3 -I scripts/oracle/leios-testnet/chain/refusals.py --evidence <a directory outside the checkout>
"""
import hashlib
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import leios_context_cases as k  # noqa: E402  (the case helpers)

h, uint, bstr, array, cmap, NULL, TRUE = k.h, k.uint, k.bstr, k.array, k.cmap, k.NULL, k.TRUE
CHAIN = os.path.join(HERE, "..", "..", "..", "fixtures", "reference", "leios-testnet-chain-contexts", "cases.json")
# The witness set's key of each language's scripts (`eras/dijkstra/impl/cddl/data/dijkstra.cddl`).
WITNESS_KEY = {1: 3, 2: 6, 3: 7, 4: 8}


def plutus_hash(language, code):
    return hashlib.blake2b(bytes([language]) + code, digest_size=28).digest()


def minting(language, n, extra=None, utxo_extra=(), wits_extra=None, more=()):
    """A script of `language` minting one token, with `extra` body fields and the redeemers `more`."""
    code = k.script(n)
    policy = plutus_hash(language, code)
    asset = cmap([(bstr(policy), cmap([(bstr(b"aa"), uint(1))]))])
    utxo = [(k.txin(0x90 + n), k.output(k.base_key(0x40), 9_000_000)), *utxo_extra]
    fields = {
        0: array([k.txin(0x90 + n)]),
        1: array([k.output(k.base_key(0x42), 8_000_000, assets=asset)]),
        2: uint(200_000),
        9: asset,
    }
    fields.update(extra or {})
    wits = {5: k.redeemers((1, 0, uint(0)), *more), WITNESS_KEY[language]: array([bstr(code)])}
    wits.update(wits_extra or {})
    return array([k.body(fields), cmap([(uint(key), v) for key, v in sorted(wits.items())]), NULL, TRUE]), utxo


def sub_transaction(fields, wits=None):
    return array([k.body(fields), cmap([(uint(key), v) for key, v in sorted((wits or {}).items())]), NULL])


def legacy_spend(language, n):
    """A script of `language` spending an output with a datum hash, the datum among the witnesses."""
    code = k.script(n)
    sh = plutus_hash(language, code)
    utxo = [(k.txin(0xA0 + n), k.output(k.enterprise_script(sh), 5_000_000, datum=k.by_hash(k.DATUM))),
            (k.txin(0xB0 + n), k.output(k.base_key(0x40), 4_000_000)),
            (k.txin(0xC8 + n), k.output(k.base_key(0x46), 2_000_000))]
    fields = {0: array([k.txin(0xA0 + n), k.txin(0xB0 + n)]), 1: array([k.output(k.base_key(0x44), 8_000_000)]),
              2: uint(200_000), 3: uint(900), 8: uint(10), 18: array([k.txin(0xC8 + n)]),
              14: array([k.cred(key=h(0x45, 28))])}
    wits = cmap([(uint(4), array([k.DATUM])), (uint(5), k.redeemers((0, 0, uint(7)))),
                 (uint(WITNESS_KEY[language]), array([bstr(code)]))])
    return array([k.body(fields), wits, NULL, TRUE]), utxo


def v3_governance():
    """A Plutus V3 script certifying a script DRep's registration beside a vote and a proposal, which a V3
    context shows, and a treasury donation."""
    code = k.script(60)
    sh = plutus_hash(3, code)
    utxo = [(k.txin(0xC0), k.output(k.base_key(0x40), 90_000_000))]
    fields = {
        0: array([k.txin(0xC0)]),
        1: array([k.output(k.base_key(0x52), 1_000_000)]),
        2: uint(200_000),
        4: array([array([uint(16), k.cred(script_hash=sh), uint(500_000_000), NULL])]),
        19: cmap([(array([uint(2), bstr(h(0x53, 28))]),
                   cmap([(array([bstr(h(0x45, 32)), uint(1)]), array([uint(1), NULL]))]))]),
        20: array([array([uint(1_000_000), bstr(k.account_key(0x54)), array([uint(6)]), k.ANCHOR])]),
        22: uint(5),
    }
    wits = cmap([(uint(5), k.redeemers((2, 0, uint(3)))), (uint(7), array([bstr(code)]))])
    return array([k.body(fields), wits, NULL, TRUE]), utxo


def mixed_languages():
    """Plutus V1, V2, V3 and V4 minting scripts in one transaction, each collected with its own context."""
    codes = {lang: k.script(70 + lang) for lang in (1, 2, 3, 4)}
    policies = {lang: plutus_hash(lang, code) for lang, code in codes.items()}
    asset = cmap([(bstr(policies[lang]), cmap([(bstr(b"aa"), uint(lang))])) for lang in sorted(policies,
                                                                                          key=lambda l: policies[l])])
    utxo = [(k.txin(0xD0), k.output(k.base_key(0x40), 9_000_000))]
    fields = {0: array([k.txin(0xD0)]), 1: array([k.output(k.base_key(0x42), 8_000_000, assets=asset)]),
              2: uint(200_000), 9: asset}
    order = sorted(policies, key=lambda lang: policies[lang])
    wits = {5: k.redeemers(*[(1, i, uint(lang)) for i, lang in enumerate(order)])}
    for lang, code in codes.items():
        wits[WITNESS_KEY[lang]] = array([bstr(code)])
    return array([k.body(fields), cmap([(uint(key), v) for key, v in sorted(wits.items())]), NULL, TRUE]), utxo


def guard_with_failing_sub():
    """A Plutus V4 guard at the top level whose sub-transaction has a pointer address in an output: the guard's
    view of the whole transaction cannot be built (`SubTxContextError`)."""
    guard = k.script(80)
    sg = plutus_hash(4, guard)
    utxo = [(k.txin(0xE0), k.output(k.base_key(0x40), 9_000_000)),
            (k.txin(0xE1), k.output(k.base_key(0x42), 4_000_000))]
    sub = sub_transaction({0: array([k.txin(0xE1)]),
                           1: array([k.output(b"\x41" + h(0x62, 28) + bytes([1, 2, 3]), 4_000_000)])})
    fields = {0: array([k.txin(0xE0)]), 1: array([k.output(k.base_key(0x4A), 8_000_000)]), 2: uint(200_000),
              14: array([k.cred(script_hash=sg)]), 23: array([sub])}
    wits = cmap([(uint(5), k.redeemers((6, 0, uint(1)))), (uint(8), array([bstr(guard)]))])
    return array([k.body(fields), wits, NULL, TRUE]), utxo


def legacy_in_sub():
    """A Plutus V3 minting script inside a sub-transaction: refused there (`UnsupportedScriptInSubTx`); the top
    level runs no script."""
    code = k.script(81)
    policy = plutus_hash(3, code)
    asset = cmap([(bstr(policy), cmap([(bstr(b"aa"), uint(1))]))])
    utxo = [(k.txin(0xE2), k.output(k.base_key(0x40), 9_000_000)),
            (k.txin(0xE3), k.output(k.base_key(0x42), 4_000_000))]
    sub = sub_transaction({0: array([k.txin(0xE3)]), 1: array([k.output(k.base_key(0x46), 4_000_000, assets=asset)]),
                           9: asset},
                          {5: k.redeemers((1, 0, uint(0))), 7: array([bstr(code)])})
    fields = {0: array([k.txin(0xE2)]), 1: array([k.output(k.base_key(0x4A), 8_000_000)]), 2: uint(200_000),
              23: array([sub])}
    return array([k.body(fields), cmap([]), NULL, TRUE]), utxo


CASES = [
    ("V1 spending, a reference input and a validity interval", lambda: legacy_spend(1, 1)),
    ("V2 spending, a reference input and a validity interval", lambda: legacy_spend(2, 2)),
    ("V3 spending, a reference input and a validity interval", lambda: legacy_spend(3, 3)),
    ("V3 certifying, with a vote, a proposal and a donation", v3_governance),
    ("V1 to V4 minting in one transaction", mixed_languages),
    ("V3 with direct deposits", lambda: minting(3, 10, {25: cmap([(bstr(k.account_key(0x58)), uint(3))])})),
    ("V2 with account balance intervals",
     lambda: minting(2, 11, {26: cmap([(bstr(k.account_key(0x59)), array([uint(1), NULL]))])})),
    ("V1 with required top-level guards", lambda: minting(1, 12, {24: cmap([(k.cred(key=h(0x56, 28)), NULL)])})),
    ("V3 with a script guard", lambda: minting(3, 13, {14: array([k.cred(script_hash=h(0x57, 28))])})),
    ("V2 with a vote", lambda: minting(2, 14, {19: cmap([(array([uint(2), bstr(h(0x53, 28))]),
                                                            cmap([(array([bstr(h(0x45, 32)), uint(1)]),
                                                                   array([uint(1), NULL]))]))])})),
    ("V1 with a proposal", lambda: minting(1, 15, {20: array([array([uint(1_000_000), bstr(k.account_key(0x54)),
                                                                      array([uint(6)]), k.ANCHOR])])})),
    ("V2 with a treasury donation", lambda: minting(2, 16, {22: uint(5)})),
    ("V1 with the current treasury", lambda: minting(1, 17, {21: uint(100)})),
    ("V1 with a DRep's registration", lambda: minting(1, 18, {4: array([array([uint(16), k.cred(key=h(0x75, 28)),
                                                                              uint(500_000_000), NULL])])})),
    ("V3 with a pointer output, which its context shows", lambda: minting(3, 19, {1: array([k.output(b"\x41" + h(0x62, 28) + bytes([1, 2, 3]),
                                                                          8_000_000)])})),
    ("V4 guard whose sub-transaction fails", guard_with_failing_sub),
    ("V3 in a sub-transaction", legacy_in_sub),
    ("V4 with the redeemer of a guard whose script is not provided",
     lambda: minting(4, 20, {14: array([k.cred(script_hash=h(0x58, 28))])}, more=[(6, 0, uint(0))])),
]


def main():
    chain = json.load(open(CHAIN))
    out = {key: chain[key] for key in ("protocolVersion", "costModels", "systemStart", "epochSize", "slotLength")}
    out["cases"] = []
    for name, build in CASES:
        tx, utxo = build()
        out["cases"].append({"name": name, "bytes": k.hexs(tx), "utxo": [[k.hexs(i), k.hexs(o)] for i, o in utxo]})
    json.dump(out, sys.stdout, indent=1)
    sys.stdout.write("\n")


if __name__ == "__main__":
    main()
