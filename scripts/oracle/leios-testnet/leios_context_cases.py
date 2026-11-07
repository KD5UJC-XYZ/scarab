# SPDX-License-Identifier: Apache-2.0
"""The PlutusV4 script-context cases of the Leios testnet oracle (#54).

Each case is a Dijkstra top-level transaction with PlutusV4 scripts and the outputs it spends and
references. The oracle's "context" kind builds its script contexts as cardano-ledger 9df9f98c does
before evaluating them (`mkDijkstraStAnnTopTx`); `DijkstraScriptContextsSuite` requires
`DijkstraScriptContexts.transaction` to build the same arguments. The scripts are never evaluated,
so their bytes are placeholders that differ only to give distinct hashes.

    python3 scripts/oracle/leios-testnet/leios_context_cases.py > cases.json
    docker run --rm -v "$PWD:/w" scarab-leios-testnet:local /w/cases.json > results.json
"""
import hashlib
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "conway-decoding"))
import cases as c  # noqa: E402  (the CBOR helpers)

h, uint, bstr, text, array, cmap, tag = c.h, c.uint, c.bstr, c.text, c.array, c.cmap, c.tag
NULL, TRUE = c.NULL, c.TRUE


def nint(v):
    return c.nint(v)


def script(n):
    """A PlutusV4 script's bytes (`PlutusBinary`); never deserialised here."""
    return bytes([0x4A, 0x01, 0x00, 0x00, n, 0x22, 0x22, 0x00, 0x11, 0x00, 0x01])


def v4_hash(code):
    return hashlib.blake2b(b"\x04" + code, digest_size=28).digest()


def data_hash(raw):
    return hashlib.blake2b(raw, digest_size=32).digest()


def cred(script_hash=None, key=None):
    return array([uint(1), bstr(script_hash)]) if script_hash else array([uint(0), bstr(key)])


def txin(byte, index=0):
    return array([bstr(h(byte, 32)), uint(index)])


def enterprise_script(sh):
    return b"\x71" + sh


def base_key(byte):
    return b"\x01" + h(byte, 28) + h(byte + 1, 28)


def account_key(byte):
    return b"\xe1" + h(byte, 28)


def account_script(sh):
    return b"\xf1" + sh


def output(address, coin, assets=None, datum=None, ref_script=None):
    """A map-form output: address, value, datum option, reference script."""
    value = uint(coin) if not assets else array([uint(coin), assets])
    fields = [(uint(0), bstr(address)), (uint(1), value)]
    if datum is not None:
        fields.append((uint(2), datum))
    if ref_script is not None:
        fields.append((uint(3), tag(24, bstr(array([uint(4), bstr(ref_script)])))))
    return cmap(fields)


def inline(raw):
    return array([uint(1), tag(24, bstr(raw))])


def by_hash(raw):
    return array([uint(0), bstr(data_hash(raw))])


def redeemers(*entries):
    return cmap([(array([uint(t), uint(i)]), array([d, array([uint(1000), uint(2000)])]))
                 for t, i, d in entries])


def body(fields):
    return cmap([(uint(k), v) for k, v in sorted(fields.items())])


def hexs(b):
    return b.hex()


ANCHOR = array([text("https://example.invalid/a"), bstr(h(0x44, 32))])
DATUM = array([uint(1), uint(2)])
DATUM_2 = uint(42)


def everything():
    """One transaction that uses a PlutusV4 script for spending, minting, a certificate, a
    withdrawal, a vote and a proposal, with every PlutusV4 body field present."""
    spend, mint, cert, wd, vote, prop = (script(i) for i in range(1, 7))
    s_spend, s_mint, s_cert, s_wd, s_vote, s_prop = (v4_hash(s) for s in (spend, mint, cert, wd, vote, prop))
    utxo = [
        (txin(0x11), output(enterprise_script(s_spend), 5_000_000, datum=inline(DATUM))),
        (txin(0x12, 3), output(base_key(0x30), 3_000_000, datum=by_hash(DATUM_2))),
        (txin(0x13), output(base_key(0x32), 2_000_000, ref_script=wd)),
    ]
    b = body({
        0: array([txin(0x12, 3), txin(0x11)]),
        1: array([
            output(base_key(0x34), 1_000_000, assets=cmap([(bstr(s_mint), cmap([(bstr(b"aa"), uint(5))]))])),
            output(enterprise_script(s_spend), 2_000_000, datum=by_hash(DATUM)),
            output(base_key(0x36), 3_000_000, datum=inline(DATUM_2), ref_script=spend),
        ]),
        2: uint(200_000),
        3: uint(1000),
        4: array([array([uint(7), cred(script_hash=s_cert), uint(2_000_000)]),
                  array([uint(4), bstr(h(0x61, 28)), uint(9)])]),
        5: cmap([(bstr(account_script(s_wd)), uint(7)), (bstr(account_key(0x50)), uint(8))]),
        8: uint(10),
        9: cmap([(bstr(s_mint), cmap([(bstr(b"aa"), uint(5)), (bstr(b"bb"), nint(-3))]))]),
        14: array([cred(key=h(0x52, 28))]),
        18: array([txin(0x13)]),
        19: cmap([(array([uint(3), bstr(s_vote)]),
                   cmap([(array([bstr(h(0x45, 32)), uint(0)]), array([uint(1), NULL]))])),
                  (array([uint(2), bstr(h(0x53, 28))]),
                   cmap([(array([bstr(h(0x45, 32)), uint(1)]), array([uint(2), ANCHOR]))]))]),
        20: array([array([uint(1_000_000), bstr(account_key(0x54)),
                          array([uint(2), cmap([(bstr(account_key(0x55)), uint(10))]), bstr(s_prop)]),
                          ANCHOR]),
                   array([uint(1_000_000), bstr(account_key(0x54)), array([uint(6)]), ANCHOR])]),
        21: uint(100),
        22: uint(5),
        24: cmap([(cred(key=h(0x56, 28)), NULL), (cred(key=h(0x57, 28)), DATUM_2)]),
        25: cmap([(bstr(account_key(0x58)), uint(3))]),
        26: cmap([(bstr(account_key(0x59)), array([uint(1), NULL])),
                  (bstr(account_key(0x5A)), uint(4)),
                  (bstr(account_key(0x5B)), array([NULL, uint(9)]))]),
    })
    wits = cmap([
        (uint(4), array([DATUM, DATUM_2])),
        (uint(5), redeemers((0, 0, uint(1)), (1, 0, uint(2)), (2, 0, uint(3)), (3, 0, uint(4)),
                            (4, 0, uint(5)), (5, 0, uint(6)))),
        (uint(8), array([bstr(spend), bstr(mint), bstr(cert), bstr(vote), bstr(prop)])),
    ])
    return array([b, wits, NULL, TRUE]), utxo


def guarded():
    """A guard script at the top level with two sub-transactions: the first requires the guard
    with a datum and mints under a PlutusV4 policy, the second withdraws and has its own range."""
    guard, mint, sub_mint = script(10), script(11), script(12)
    s_guard, s_sub_mint = v4_hash(guard), v4_hash(sub_mint)
    utxo = [
        (txin(0x21), output(base_key(0x40), 9_000_000)),
        (txin(0x22), output(base_key(0x42), 4_000_000)),
        (txin(0x23), output(base_key(0x44), 4_000_000)),
    ]
    sub1 = array([
        body({
            0: array([txin(0x22)]),
            1: array([output(base_key(0x46), 4_000_000)]),
            8: uint(20),
            9: cmap([(bstr(s_sub_mint), cmap([(bstr(b"cc"), uint(2)), (bstr(b"dd"), nint(-1))]))]),
            14: array([cred(key=h(0x47, 28))]),
            24: cmap([(cred(script_hash=s_guard), DATUM)]),
            25: cmap([(bstr(account_key(0x58)), uint(3))]),
        }),
        cmap([(uint(5), redeemers((1, 0, uint(7)))), (uint(8), array([bstr(sub_mint)]))]),
        NULL,
    ])
    sub2 = array([
        body({
            0: array([txin(0x23)]),
            1: array([output(base_key(0x48), 4_000_000)]),
            3: uint(500),
            5: cmap([(bstr(account_key(0x58)), uint(1))]),
            22: uint(2),
            25: cmap([(bstr(account_key(0x58)), uint(4))]),
        }),
        cmap([]),
        NULL,
    ])
    b = body({
        0: array([txin(0x21)]),
        1: array([output(base_key(0x4A), 8_000_000)]),
        2: uint(200_000),
        3: uint(800),
        5: cmap([(bstr(account_key(0x58)), uint(2))]),
        8: uint(5),
        14: array([cred(key=h(0x4B, 28)), cred(script_hash=s_guard)]),
        22: uint(1),
        23: array([sub1, sub2]),
        27: cmap([(bstr(account_key(0x58)), array([uint(1), uint(100)]))]),
    })
    wits = cmap([
        (uint(4), array([DATUM_2])),
        (uint(5), redeemers((6, 1, DATUM))),
        (uint(8), array([bstr(guard), bstr(mint)])),
    ])
    return array([b, wits, NULL, TRUE]), utxo


def ratio(n, d):
    return tag(30, array([uint(n), uint(d)]))


def governance():
    """PlutusV4 scripts for a parameter change's guardrail, a DRep's and a committee member's
    certificates, with every other certificate and governance action beside them."""
    guardrail, drep, cold = script(40), script(41), script(42)
    s_guardrail, s_drep, s_cold = v4_hash(guardrail), v4_hash(drep), v4_hash(cold)
    utxo = [(txin(0x51), output(base_key(0x40), 90_000_000))]
    key = cred(key=h(0x63, 28))
    pool = bstr(h(0x64, 28))
    certs = [
        array([uint(2), key, pool]),
        array([uint(3), bstr(h(0x65, 28)), bstr(h(0x66, 32)), uint(500), uint(340), ratio(1, 3),
               bstr(account_key(0x67)), array([bstr(h(0x68, 28))]), array([]), NULL]),
        array([uint(7), cred(key=h(0x69, 28)), uint(2_000_000)]),
        array([uint(8), cred(key=h(0x6A, 28)), uint(2_000_000)]),
        array([uint(9), cred(key=h(0x6B, 28)), array([uint(2)])]),
        array([uint(10), cred(key=h(0x6C, 28)), pool, array([uint(3)])]),
        array([uint(11), cred(key=h(0x6D, 28)), pool, uint(2_000_000)]),
        array([uint(12), cred(key=h(0x6E, 28)), array([uint(0), bstr(h(0x6F, 28))]), uint(2_000_000)]),
        array([uint(13), cred(key=h(0x70, 28)), pool, array([uint(1), bstr(h(0x71, 28))]), uint(2_000_000)]),
        array([uint(14), cred(script_hash=s_cold), cred(key=h(0x72, 28))]),
        array([uint(15), cred(key=h(0x73, 28)), ANCHOR]),
        array([uint(16), cred(script_hash=s_drep), uint(500_000_000), NULL]),
        array([uint(17), cred(key=h(0x74, 28)), uint(500_000_000)]),
        array([uint(18), cred(key=h(0x75, 28)), ANCHOR]),
    ]
    update = cmap([
        (uint(0), uint(44)),
        (uint(10), ratio(3, 1000)),
        (uint(19), array([ratio(577, 10000), ratio(721, 10000000)])),
        (uint(20), array([uint(14_000_000), uint(10_000_000_000)])),
        (uint(25), array([ratio(1, 2)] * 5)),
        (uint(38), NULL),
        (uint(39), ratio(1, 100)),
        (uint(53), uint(7)),
    ])
    gaid = array([bstr(h(0x46, 32)), uint(3)])
    proposals = [
        array([uint(1), bstr(account_key(0x76)), array([uint(0), gaid, update, bstr(s_guardrail)]), ANCHOR]),
        array([uint(2), bstr(account_key(0x76)), array([uint(1), NULL, array([uint(12), uint(0)])]), ANCHOR]),
        array([uint(3), bstr(account_key(0x76)), array([uint(3), gaid]), ANCHOR]),
        array([uint(4), bstr(account_key(0x76)),
               array([uint(4), NULL, array([cred(key=h(0x77, 28)), cred(script_hash=h(0x78, 28))]),
                      cmap([(cred(key=h(0x79, 28)), uint(90)), (cred(script_hash=h(0x7A, 28)), uint(91))]),
                      ratio(2, 3)]), ANCHOR]),
        array([uint(5), bstr(account_key(0x76)), array([uint(5), NULL, array([ANCHOR, bstr(h(0x7B, 28))])]), ANCHOR]),
    ]
    b = body({
        0: array([txin(0x51)]),
        1: array([output(base_key(0x52), 1_000_000)]),
        2: uint(200_000),
        4: array(certs),
        20: array(proposals),
    })
    wits = cmap([
        (uint(5), redeemers((2, 9, uint(1)), (2, 11, uint(2)), (5, 0, uint(3)))),
        (uint(8), array([bstr(guardrail), bstr(drep), bstr(cold)])),
    ])
    return array([b, wits, NULL, TRUE]), utxo


def account_key0(byte):
    return b"\xe0" + h(byte, 28)


def account_script0(sh):
    return b"\xf0" + sh


def sub_transactions():
    """PlutusV4 scripts inside sub-transactions: a guard, and spends of outputs with a datum hash
    whose datum the sub-transaction carries or not; maps keyed by key and script credentials of
    both networks; a parameter change of cost models, DRep thresholds and a pledge leverage."""
    guard, spend = script(50), script(51)
    s_guard, s_spend = v4_hash(guard), v4_hash(spend)
    s_other = h(0x80, 28)
    utxo = [
        (txin(0x61), output(base_key(0x40), 90_000_000)),
        (txin(0x62), output(enterprise_script(s_spend), 3_000_000, datum=by_hash(DATUM))),
        (txin(0x63), output(enterprise_script(s_spend), 3_000_000, datum=by_hash(DATUM_2))),
    ]
    mixed = cmap([(bstr(account_key(0x81)), uint(1)), (bstr(account_script(s_other)), uint(2)),
                  (bstr(account_key0(0x81)), uint(3)), (bstr(account_script0(s_other)), uint(4))])
    sub = array([
        body({
            0: array([txin(0x62), txin(0x63)]),
            1: array([output(base_key(0x46), 6_000_000)]),
            5: mixed,
            14: array([cred(script_hash=s_guard), cred(key=h(0x82, 28))]),
            24: cmap([(cred(key=h(0x83, 28)), NULL), (cred(script_hash=s_other), DATUM),
                      (cred(script_hash=s_guard), DATUM_2)]),
            25: mixed,
            26: cmap([(bstr(account_script(s_other)), uint(4)), (bstr(account_key0(0x84)), array([NULL, uint(5)]))]),
        }),
        cmap([(uint(4), array([DATUM])),
              (uint(5), redeemers((0, 0, uint(1)), (0, 1, uint(2)), (6, 0, uint(3)))),
              (uint(8), array([bstr(guard), bstr(spend)]))]),
        NULL,
    ])
    update = cmap([
        (uint(18), cmap([(uint(3), array([uint(i) for i in range(251)]))])),
        (uint(26), array([ratio(i + 1, 20) for i in range(10)])),
        (uint(38), ratio(5, 1)),
    ])
    b = body({
        0: array([txin(0x61)]),
        1: array([output(base_key(0x52), 1_000_000)]),
        2: uint(200_000),
        5: mixed,
        20: array([array([uint(1), bstr(account_key(0x76)), array([uint(0), NULL, update, NULL]), ANCHOR])]),
        23: array([sub]),
    })
    return array([b, cmap([]), NULL, TRUE]), utxo


def byron_output():
    """A PlutusV4 minting script with a Byron address among the outputs: refused."""
    import zlib
    payload = array([bstr(h(0x90, 28)), cmap([]), uint(0)])
    address = array([tag(24, bstr(payload)), uint(zlib.crc32(payload))])
    mint = script(21)
    s_mint = v4_hash(mint)
    utxo = [(txin(0x32), output(base_key(0x40), 9_000_000))]
    b = body({
        0: array([txin(0x32)]),
        1: array([output(address, 8_000_000, assets=cmap([(bstr(s_mint), cmap([(bstr(b"aa"), uint(1))]))]))]),
        2: uint(200_000),
        9: cmap([(bstr(s_mint), cmap([(bstr(b"aa"), uint(1))]))]),
    })
    wits = cmap([(uint(5), redeemers((1, 0, uint(0)))), (uint(8), array([bstr(mint)]))])
    return array([b, wits, NULL, TRUE]), utxo


def pointer_output():
    """A PlutusV4 minting script with a pointer address among the outputs: refused."""
    mint = script(20)
    s_mint = v4_hash(mint)
    utxo = [(txin(0x31), output(base_key(0x40), 9_000_000))]
    b = body({
        0: array([txin(0x31)]),
        1: array([output(b"\x41" + h(0x62, 28) + bytes([1, 2, 3]), 8_000_000,
                         assets=cmap([(bstr(s_mint), cmap([(bstr(b"aa"), uint(1))]))]))]),
        2: uint(200_000),
        9: cmap([(bstr(s_mint), cmap([(bstr(b"aa"), uint(1))]))]),
    })
    wits = cmap([(uint(5), redeemers((1, 0, uint(0)))), (uint(8), array([bstr(mint)]))])
    return array([b, wits, NULL, TRUE]), utxo


def minting(extra=None, redeemer=(1, 0), more=None, ref_overlap=False):
    """A PlutusV4 minting script, with `extra` body fields, its redeemer at `redeemer` and a
    redeemer at `more`."""
    mint = script(30)
    s_mint = v4_hash(mint)
    utxo = [(txin(0x41), output(base_key(0x40), 9_000_000))]
    fields = {
        0: array([txin(0x41)]),
        1: array([output(base_key(0x42), 8_000_000,
                         assets=cmap([(bstr(s_mint), cmap([(bstr(b"aa"), uint(1))]))]))]),
        2: uint(200_000),
        9: cmap([(bstr(s_mint), cmap([(bstr(b"aa"), uint(1))]))]),
    }
    if ref_overlap:
        fields[18] = array([txin(0x41)])
    fields.update(extra or {})
    wits = [(uint(8), array([bstr(mint)]))]
    entries = [(k[0], k[1], uint(0)) for k in (redeemer, more) if k is not None]
    if entries:
        wits.insert(0, (uint(5), redeemers(*entries)))
    return array([body(fields), cmap(wits), NULL, TRUE]), utxo


def main():
    out = []
    for name, build in [
        ("everything", everything),
        ("guarded", guarded),
        ("governance", governance),
        ("sub-transaction scripts", sub_transactions),
        ("pointer output", pointer_output),
        ("byron output", byron_output),
        ("unresolved input", lambda: minting(extra={0: array([txin(0x41), txin(0x42)])})),
        ("reference input also an input", lambda: minting(ref_overlap=True)),
        ("redeemer pointing nowhere", lambda: minting(more=(1, 1))),
        ("no redeemer", lambda: minting(redeemer=None)),
        ("redeemer of a key withdrawal",
         lambda: minting(extra={5: cmap([(bstr(account_key(0x50)), uint(1))])}, more=(3, 0))),
    ]:
        tx, utxo = build()
        out.append({"name": name, "kind": "context", "bytes": hexs(tx),
                    "utxo": [[hexs(i), hexs(o)] for i, o in utxo]})
    json.dump(out, sys.stdout, indent=1)
    sys.stdout.write("\n")


if __name__ == "__main__":
    main()
