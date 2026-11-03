#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Cases for the Byron genesis data oracle (#734).

Each case is a whole Byron genesis file as `readGenesisData` reads it with `FromJSON GenesisData`:
either a genesis file of the repository unchanged (`file`), or the devnet genesis `BASE` with
literal edits (`edits`, each `[old, new]`, `old` occurring exactly once). The edits cover, field by
field, each form the field's canonical-JSON instance takes and refuses, and the order in which
`FromJSON GenesisData` reads the fields.

`--inputs` writes the harness input (the bytes of each case); `--reference` with `--out` merges the
harness output into `cases.jsonl`, a line per case with its edits and the reference's verdict: for a
file the fields it read (`result`), for an edited base the fields whose value differs from the
base's (`changed`).
"""
import argparse
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
BASE = "fixtures/devnet/lsq/genesis/byron-genesis.json"
FILES = [
    "fixtures/mainnet/genesis/byron-genesis.json",
    "fixtures/preprod/genesis/byron-genesis.json",
    "fixtures/preview/genesis/byron-genesis.json",
    "fixtures/devnet/lsq/genesis/byron-genesis.json",
    "fixtures/leios/testnet/config/byron-genesis.json",
    "fixtures/byron-version/full-registration/genesis.json",
    "fixtures/mainnet/byron-genesis-trimmed.json",
]

K1 = "a9140a470fee47d77f66200b5fbed39115176b0bfcedc5a39893572e"
K2 = "da1c8d620cc11452616b259804e69e93b4f6836d99dd3672c878a2ac"
K3 = "f1f8c6feb42a51f6e3b671177863bb576c1f038a20638dbbef733a91"
ISSUER1 = "FRu5f7uV1SvWxgQ33EYJ8bqBZ7mGgSk3LvtQ5R2PiWw1NrEaINuwnnjdqywTQB7iTNUR35GQS2HEzWJA/3SYTQ=="
ISSUER2 = "7lXVS68hSolRYPNH2EwlofCa2tcaa8aADOsmPX9bXVRhhiU4nvNyso50g5ZwmOUkNYO+yM/rKJrSwIdEzeVjuw=="
DELEGATE1 = "OxiZKX1F9Iv+uaog7OMVoO1yQDvZ5UBfYjxB2izzc00yQYFfPsRCBsh8BOFxNbnnLR5fxW7GXVWVTehCcnwQ4A=="
DELEGATE2 = "Hf2i+20boQJi7VsPmqICovt6ue2m7J8spN0nD74F2c9h0euG8irGMSp7uH+jLvr5bNFTR2PXAO5m4oiBfCLnMw=="
CERT1 = ("aeb6aa4ddc3a6f6191ee0b850bf6f547ead460b365cfa4c2e9069f16a9ff76dc"
         "403b83188017a2ea3e6625ab42f63646568cb38df691b5a838f6be04501c3b09")

STAKEHOLDERS = '{ "%s": 1\n    , "%s": 1\n    , "%s": 1\n    }' % (K1, K2, K3)
CONSTS = '"protocolConsts": { "k": 5 , "protocolMagic": 1082026 }'
FEE = '"txFeePolicy":\n        { "summand": "155381000000000" , "multiplier": "43946000000" }'
SOFTFORK = ('"softforkRule":\n        { "initThd": "900000000000000"\n        , "minThd": "600000000000000"\n'
            '        , "thdDecrement": "50000000000000"\n        }')


def base_text():
    return (ROOT / BASE).read_text(encoding="utf-8")


def apply(text, edits):
    for old, new in edits:
        assert text.count(old) == 1, old
        text = text.replace(old, new)
    return text


def heavy_delegation(text):
    start = text.index('"heavyDelegation":')
    end = text.index(', "startTime"')
    return text[start:end]


def cases():
    text = base_text()
    heavy = heavy_delegation(text)
    out = []

    def add(name, field, *edits):
        out.append({"name": name, "base": BASE, "edits": [list(e) for e in edits], "field": field})

    def param(key, value, quoted=True):
        old = re.search(r'"%s": ("[^"]*"|\d+)' % key, text).group(0)
        return (old, '"%s": %s' % (key, json.dumps(value) if quoted else value))

    # the top level: `fromJSObject`, `protocolConsts` looked up first, then each field in order
    add("top: the base genesis", None)
    add("top: an unknown field is ignored", None, ('"avvmDistr": {}', '"avvmDistr": {}, "extra": [1]'))
    add("top: a field twice, the first holds", None, ('"startTime": 1791765068', '"startTime": 7, "startTime": 1791765068'))
    add("top: not an object", "Top", ('{ "bootStakeholders"', '[ { "bootStakeholders"'), ('"avvmDistr": {}\n}', '"avvmDistr": {}\n} ]'))
    for key in ("bootStakeholders", "heavyDelegation", "startTime", "nonAvvmBalances", "blockVersionData", "avvmDistr"):
        add(f"top: without {key}", key, (f'"{key}":', f'"{key}X":'))
    add("top: without protocolConsts", "protocolConsts", ('"protocolConsts":', '"protocolConstsX":'))
    add("top: without protocolConsts and bootStakeholders, protocolConsts is looked up first", "protocolConsts",
        ('"protocolConsts":', '"protocolConstsX":'), ('"bootStakeholders":', '"bootStakeholdersX":'))
    add("top: protocolConsts a number and bootStakeholders missing, bootStakeholders is read first", "bootStakeholders",
        (CONSTS, '"protocolConsts": 5'), ('"bootStakeholders":', '"bootStakeholdersX":'))
    add("top: protocolConsts a number, refused when k is read", "protocolConsts", (CONSTS, '"protocolConsts": 5'))
    add("top: protocolConsts a number and blockVersionData missing, blockVersionData is read first", "blockVersionData",
        (CONSTS, '"protocolConsts": 5'), ('"blockVersionData":', '"blockVersionDataX":'))
    add("top: startTime and blockVersionData wrong, startTime is read first", "startTime",
        ('"startTime": 1791765068', '"startTime": "1"'), ('"blockVersionData":', '"blockVersionDataX":'))
    add("top: avvmDistr and k wrong, k is read first", "k",
        ('"avvmDistr": {}', '"avvmDistr": 5'), ('"k": 5', '"k": "5"'))

    # bootStakeholders: `Map KeyHash Word16`, its keys kept as a set
    add("bootStakeholders: empty", None, (STAKEHOLDERS, "{}"))
    add("bootStakeholders: an array", "bootStakeholders", (STAKEHOLDERS, "[]"))
    add("bootStakeholders: a value as a string", "bootStakeholders", (f'"{K1}": 1\n', f'"{K1}": "1"\n'))
    add("bootStakeholders: a value null", "bootStakeholders", (f'"{K1}": 1\n', f'"{K1}": null\n'))
    for label, v in (("65536", "65536"), ("-1", "-1"), ("zero", "0"), ("fifteen digits", "999999999999999")):
        add(f"bootStakeholders: a value {label}", None, (f'"{K1}": 1\n', f'"{K1}": {v}\n'))
    add("bootStakeholders: a key in upper case", None, (f'"{K1}": 1\n', f'"{K1.upper()}": 1\n'))
    add("bootStakeholders: a key in mixed case", None, (f'"{K1}": 1\n', f'"{K1[:10].upper() + K1[10:]}": 1\n'))
    add("bootStakeholders: a key twice in two cases", None, (f'"{K1}": 1\n', f'"{K1}": 1, "{K1.upper()}": 2\n'))
    add("bootStakeholders: keys out of order", None, (STAKEHOLDERS, '{ "%s": 1, "%s": 1, "%s": 1 }' % (K3, K1, K2)))
    add("bootStakeholders: a key of odd length", "bootStakeholders", (f'"{K1}": 1\n', f'"{K1}0": 1\n'))
    add("bootStakeholders: a key of 27 bytes", "bootStakeholders", (f'"{K1}": 1\n', f'"{K1[:-2]}": 1\n'))
    add("bootStakeholders: a key of 29 bytes", "bootStakeholders", (f'"{K1}": 1\n', f'"{K1}00": 1\n'))
    add("bootStakeholders: a key not hex", "bootStakeholders", (f'"{K1}": 1\n', f'"{K1[:-2]}zz": 1\n'))
    add("bootStakeholders: a key with 0x", "bootStakeholders", (f'"{K1}": 1\n', f'"0x{K1}": 1\n'))
    add("bootStakeholders: an empty key", "bootStakeholders", (f'"{K1}": 1\n', '"": 1\n'))

    # heavyDelegation: `Map KeyHash Certificate`, then `recreateGenesisDelegation`
    add("heavyDelegation: empty", None, (heavy, '"heavyDelegation": {}\n'))
    add("heavyDelegation: an array", "heavyDelegation", (heavy, '"heavyDelegation": []\n'))
    add("heavyDelegation: a certificate not an object", "heavyDelegation",
        (heavy, '"heavyDelegation": { "%s": 1 }\n' % K1))
    add("heavyDelegation: a key in upper case", None, (f'"{K1}":\n        {{', f'"{K1.upper()}":\n        {{'))
    add("heavyDelegation: a key of 27 bytes", "heavyDelegation", (f'"{K1}":\n        {{', f'"{K1[:-2]}":\n        {{'))
    add("heavyDelegation: a key that is not the issuer's hash", "heavyDelegation",
        (f'"{K1}":\n        {{', f'"{K1[:-2]}00":\n        {{'))
    add("heavyDelegation: two keys, one issuer twice", "heavyDelegation", (ISSUER2, ISSUER1))
    add("heavyDelegation: a delegate that is an issuer", "heavyDelegation", (DELEGATE1, ISSUER2))
    add("heavyDelegation: an issuer that delegates to itself", "heavyDelegation", (DELEGATE1, ISSUER1))
    add("heavyDelegation: two issuers, one delegate", None, (DELEGATE2, DELEGATE1))
    omega1 = f'"omega": 0\n        , "issuerPk":\n            "{ISSUER1}"'
    for label, v, field in (("5", "5", None), ("-1", "-1", None), ("fifteen digits", "999999999999999", None),
                            ("a string", '"0"', "omega"), ("null", "null", "omega")):
        add(f"heavyDelegation: omega {label}", field, (omega1, omega1.replace('"omega": 0', f'"omega": {v}')))
    add("heavyDelegation: without omega", "omega", (omega1, omega1.replace('"omega": 0\n        , ', '')))
    add("heavyDelegation: an unknown field in a certificate", None,
        (omega1, omega1.replace('"omega": 0', '"omega": 0, "extra": 1')))
    for label, v, field in (
            ("without padding", ISSUER1.rstrip("="), "issuerPk"),
            ("URL alphabet", ISSUER1.replace("/", "_").replace("+", "-"), "issuerPk"),
            ("with a space", ISSUER1[:8] + " " + ISSUER1[8:], "issuerPk"),
            ("63 bytes", "A" * 84, "issuerPk"),
            ("65 bytes", "A" * 87 + "=", "issuerPk"),
            ("not base64", "*" * 88, "issuerPk"),
            ("empty", "", "issuerPk")):
        add(f"heavyDelegation: issuerPk {label}", field, (f'"{ISSUER1}"', json.dumps(v)))
    add("heavyDelegation: issuerPk a number", "issuerPk", (f'"{ISSUER1}"', "1"))
    add("heavyDelegation: delegatePk without padding", "delegatePk", (f'"{DELEGATE1}"', json.dumps(DELEGATE1.rstrip("="))))
    add("heavyDelegation: delegatePk 63 bytes", "delegatePk", (f'"{DELEGATE1}"', json.dumps("A" * 84)))
    add("heavyDelegation: delegatePk nonzero padding bits", "delegatePk",
        (f'"{DELEGATE1}"', json.dumps(DELEGATE1[:-3] + "B==")))
    for label, v, field in (("in upper case", CERT1.upper(), None), ("63 bytes", CERT1[:-2], "cert"),
                            ("65 bytes", CERT1 + "00", "cert"), ("odd length", CERT1[:-1], "cert"),
                            ("not hex", CERT1[:-2] + "zz", "cert"), ("all zero", "00" * 64, None)):
        add(f"heavyDelegation: cert {label}", field, (f'"{CERT1}"', json.dumps(v)))
    add("heavyDelegation: cert a number", "cert", (f'"{CERT1}"', "1"))
    add("heavyDelegation: the certificate's fields wrong, omega is read first", "omega",
        (omega1, omega1.replace('"omega": 0', '"omega": "0"')), (f'"{CERT1}"', "1"))

    # startTime: `UTCTime` from an `Int54` of seconds
    for label, v, field in (("zero", "0", None), ("negative", "-1", None), ("fifteen digits", "999999999999999", None),
                            ("negative fifteen digits", "-999999999999999", None),
                            ("a string", '"1791765068"', "startTime"), ("null", "null", "startTime"),
                            ("an object", "{}", "startTime")):
        add(f"startTime: {label}", field, ('"startTime": 1791765068', f'"startTime": {v}'))

    # blockVersionData: `ProtocolParameters`, each field in order
    add("blockVersionData: an array", "blockVersionData",
        (text[text.index('"blockVersionData":'):text.index(', "protocolConsts"')], '"blockVersionData": []\n'))
    add("blockVersionData: two fields wrong, scriptVersion is read first", "scriptVersion",
        param("scriptVersion", "0"), param("slotDuration", 1000, quoted=False))
    for label, v, field in (("a string", '"0"', "scriptVersion"), ("65536", "65536", None), ("-1", "-1", None),
                            ("65535", "65535", None)):
        add(f"blockVersionData: scriptVersion {label}", field, param("scriptVersion", v, quoted=False))
    add("blockVersionData: without scriptVersion", "scriptVersion", ('"scriptVersion":', '"scriptVersionX":'))
    naturals = (("zero", "0", True), ("-0", "-0", True), ("-1", "-1", False), ("hex", "0x10", True),
                ("octal", "0o17", True), ("in parentheses", "(5)", True), ("spaces", " 5 ", True),
                ("leading zeros", "007", True), ("2^64", "18446744073709551616", True),
                ("thirty digits", "123456789012345678901234567890", True), ("exponent", "1e3", False),
                ("fraction", "5.0", False), ("plus", "+5", False), ("empty", "", False), ("(-1)", "(-1)", False))
    for key in ("slotDuration", "maxBlockSize", "maxHeaderSize", "maxTxSize", "maxProposalSize"):
        add(f"blockVersionData: {key} a number", key, param(key, 1000, quoted=False))
        add(f"blockVersionData: without {key}", key, (f'"{key}":', f'"{key}X":'))
    for label, v, ok in naturals:
        add(f"blockVersionData: slotDuration {label}", None if ok else "slotDuration", param("slotDuration", v))
    for label, v, ok in naturals[:4]:
        add(f"blockVersionData: maxTxSize {label}", None if ok else "maxTxSize", param("maxTxSize", v))
    portions = (("zero", "0", True), ("1e15", "1000000000000000", True), ("1e15 + 1", "1000000000000001", False),
                ("-1", "-1", False), ("2^64", "18446744073709551616", True),
                ("2^64 + 1e15", "18447744073709551616", True), ("2^64 + 1e15 + 1", "18447744073709551617", False),
                ("hex", "0x10", True), ("exponent", "1e3", False))
    for key in ("mpcThd", "heavyDelThd", "updateVoteThd", "updateProposalThd"):
        add(f"blockVersionData: {key} a number", key, param(key, 1, quoted=False))
    for label, v, ok in portions:
        add(f"blockVersionData: mpcThd {label}", None if ok else "mpcThd", param("mpcThd", v))
    for label, v, ok in portions[:4]:
        add(f"blockVersionData: updateProposalThd {label}", None if ok else "updateProposalThd",
            param("updateProposalThd", v))
    words = (("zero", "0", True), ("2^64 - 1", "18446744073709551615", True), ("2^64", "18446744073709551616", True),
             ("-1", "-1", True), ("hex", "0x10", True), ("exponent", "1e3", False), ("empty", "", False))
    for label, v, ok in words:
        add(f"blockVersionData: updateImplicit {label}", None if ok else "updateImplicit", param("updateImplicit", v))
        add(f"blockVersionData: unlockStakeEpoch {label}", None if ok else "unlockStakeEpoch",
            param("unlockStakeEpoch", v))
    add("blockVersionData: updateImplicit a number", "updateImplicit", param("updateImplicit", 1, quoted=False))
    add("blockVersionData: unlockStakeEpoch a number", "unlockStakeEpoch", param("unlockStakeEpoch", 1, quoted=False))
    add("blockVersionData: softforkRule an array", "softforkRule", (SOFTFORK, '"softforkRule": []'))
    add("blockVersionData: softforkRule without minThd", "minThd", ('"minThd":', '"minThdX":'))
    for key in ("initThd", "minThd", "thdDecrement"):
        add(f"blockVersionData: softforkRule {key} over 1e15", key, param(key, "1000000000000001"))
        add(f"blockVersionData: softforkRule {key} a number", key, param(key, 1, quoted=False))
    add("blockVersionData: softforkRule thdDecrement 2^64 wraps to zero", None, param("thdDecrement", "18446744073709551616"))
    add("blockVersionData: txFeePolicy an array", "txFeePolicy", (FEE, '"txFeePolicy": []'))
    add("blockVersionData: txFeePolicy without summand", "summand", ('"summand":', '"summandX":'))
    add("blockVersionData: txFeePolicy without multiplier", "multiplier", ('"multiplier":', '"multiplierX":'))
    for label, v, ok in (("zero", "0", True), ("under 1e9", "999999999", True), ("-1", "-1", True),
                         ("2^64 - 1", "18446744073709551615", True), ("2^64", "18446744073709551616", True),
                         ("hex", "0x3b9aca00", True), ("a number", 5, False), ("exponent", "1e9", False)):
        add(f"blockVersionData: txFeePolicy summand {label}", None if ok else "summand",
            param("summand", v, quoted=isinstance(v, str)))
    for label, v, ok in (("zero", "0", True), ("-5", "-5", True), ("(-5)", "(-5)", True), ("hex", "0x10", True),
                         ("thirty digits", "123456789012345678901234567890", True), ("2^64", "18446744073709551616", True),
                         ("a number", 5, False), ("fraction", "1.5", False), ("plus", "+5", False)):
        add(f"blockVersionData: txFeePolicy multiplier {label}", None if ok else "multiplier",
            param("multiplier", v, quoted=isinstance(v, str)))
    add("blockVersionData: an unknown field is ignored", None, ('"scriptVersion": 0', '"scriptVersion": 0, "extra": "x"'))

    # protocolConsts: `k` an `Int54` into `BlockCount`, `protocolMagic` a `Word32`
    for label, v, field in (("zero", "0", None), ("-1", "-1", None), ("fifteen digits", "999999999999999", None),
                            ("a string", '"5"', "k"), ("null", "null", "k")):
        add(f"protocolConsts: k {label}", field, ('"k": 5', f'"k": {v}'))
    add("protocolConsts: without k", "k", ('"k": 5 , ', ''))
    for label, v, field in (("zero", "0", None), ("2^32 - 1", "4294967295", None), ("2^32 + 1", "4294967297", None),
                            ("-1", "-1", None), ("fifteen digits", "999999999999999", None),
                            ("a string", '"1082026"', "protocolMagic")):
        add(f"protocolConsts: protocolMagic {label}", field, ('"protocolMagic": 1082026', f'"protocolMagic": {v}'))
    add("protocolConsts: without protocolMagic", "protocolMagic", (' , "protocolMagic": 1082026', ''))
    add("protocolConsts: k and protocolMagic wrong, k is read first", "k",
        ('"k": 5', '"k": "5"'), ('"protocolMagic": 1082026', '"protocolMagic": "1"'))
    return out


def inputs(cs):
    text = base_text()
    rows = []
    for c in cs:
        body = (ROOT / c["file"]).read_text(encoding="utf-8") if "file" in c else apply(text, c["edits"])
        rows.append({"name": c["name"], "text": body})
    return rows


def all_cases():
    return [{"name": f"file: {f}", "file": f, "field": None} for f in FILES] + cases()


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--inputs", type=Path, required=True)
    p.add_argument("--reference", type=Path)
    p.add_argument("--out", type=Path)
    a = p.parse_args()
    cs = all_cases()
    names = [c["name"] for c in cs]
    assert len(set(names)) == len(names), "case names are unique"
    if a.reference is None:
        a.inputs.write_text(json.dumps(inputs(cs)) + "\n")
        return
    verdicts = {r["name"]: r for r in json.loads(a.reference.read_text())}
    # the base genesis read whole, against which an edited case keeps only the fields it changes
    base = verdicts[f"file: {BASE}"]["result"]
    with a.out.open("w") as f:
        for c in cs:
            v = verdicts[c["name"]]
            field = c.pop("field")
            assert v["accepted"] == (field is None), (c["name"], v.get("failure"))
            row = dict(c, accepted=v["accepted"], hash=v["hash"])
            if v["accepted"] and "file" in c:
                row["result"] = v["result"]
            elif v["accepted"]:
                row["changed"] = {k: x for k, x in v["result"].items() if base[k] != x}
            else:
                row["failure"] = v["failure"]
                row["refusal"] = refusal(field, v["failure"])
            f.write(json.dumps(row, sort_keys=True, ensure_ascii=False) + "\n")


KINDS = {"null": "Null", "bool": "Bool", "num": "Number", "string": "String", "array": "Array", "object": "Object"}
NUMBER_TYPES = {"int", "Word16", "Word32"}
STRING_TYPES = {"Word64", "Natural", "Integer", "VerificationKey", "Signature EpochNumber"}


def refusal(field, failure):
    """The field a refusal names (as the case means it, the key of `ByronGenesisField` with its first
    letter in upper case) and the reader's `SchemaError`, in the shape circe derives for the suite's
    `Problem`."""
    name = field[0].upper() + field[1:]
    expected = re.search(r'seExpected = "((?:[^"\\]|\\.)*)"', failure).group(1)
    actual = re.search(r'seActual = (Nothing|Just "((?:[^"\\]|\\.)*)")', failure).group(2)
    return {"field": name, "problem": problem(expected, actual, failure)}


def problem(expected, actual, failure):
    if expected.startswith("field "):
        return {"Missing": {}}
    if actual in KINDS and (expected == "object" or expected in NUMBER_TYPES or expected in STRING_TYPES):
        want = "Object" if expected == "object" else "Number" if expected in NUMBER_TYPES else "String"
        return {"Kind": {"expected": {want: {}}, "actual": {KINDS[actual]: {}}}}
    if expected.startswith("LovelacePortion"):
        return {"Portion": {"value": actual}}
    if expected in ("Word64", "Natural", "Integer"):
        assert "Prelude.read: no parse" in failure, failure
        return {"NumberText": {}}
    if expected == "AbstractHash Blake2b_224 VerificationKey":
        length = re.search(r"the value was ([0-9a-fA-F]*)", failure)
        if length:
            return {"HashLength": {"length": len(length.group(1)) // 2}}
        assert "invalid" in failure, failure
        return {"Hex": {}}
    if expected == "VerificationKey":
        length = re.search(r"got (\d+) bytes", failure)
        if length:
            return {"KeyLength": {"length": int(length.group(1))}}
        assert "Failed to decode base 64" in failure, failure
        return {"Base64": {}}
    if expected == "Signature EpochNumber":
        length = re.search(r"got (\d+) bytes", failure)
        if length:
            return {"SignatureLength": {"length": int(length.group(1))}}
        assert "Failed to parse base 16" in failure, failure
        return {"Hex": {}}
    assert expected == "GenesisDelegation", failure
    if "Invalid key" in failure:
        key, issuer = re.search(r"Expected: ([0-9a-f]+)\\nGot: ([0-9a-f]+)", failure).groups()
        return {"DelegationKey": {"keyPrefix": key, "issuerPrefix": issuer}}
    delegate = re.search(r"multi-layer delegation\.\\n([0-9a-f]+) is a delegate", failure).group(1)
    return {"MultiLayer": {"delegatePrefix": delegate}}


if __name__ == "__main__":
    main()
