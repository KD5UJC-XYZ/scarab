#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Offline Plutus-ingress fixture planning/comparison; no subprocess, Docker or key generation.

The integration owner executes these bounded argv plans in its exclusively owned
reference container. Only fixture funding has a reference submission command.
"""
from dataclasses import dataclass
import hashlib
import re

PROFILE = "isolated-conway-pv9-plutus-v3-spend-v1"
ROOT = "/work/plutus-ingress-fixture"
FUNDING = ROOT + "/funding.signed"
SPEND = ROOT + "/spend.signed"
SCRIPT = ROOT + "/script.json"
AMOUNT = 20_000_000
FEE = 300_000
FUNDING_FEE = 200_000
COLLATERAL = 5_000_000
SCRIPT_SHA = "57fb50f08ffc1222cbe2b652db3dcfed0f714da98f8170cb104aee2bde4070f6"
DATUM = ROOT + "/datum.json"
REDEEMER = ROOT + "/redeemer.json"
PARAMETERS = ROOT + "/protocol.json"
MAX_COIN = 2 ** 64 - 1


def require(value, why):
    if not value:
        raise ValueError(why)


def digest(raw):
    require(isinstance(raw, bytes), "original bytes required")
    return hashlib.sha256(raw).hexdigest()


def hex_hash(value, size):
    require(isinstance(value, str) and re.fullmatch("[0-9a-f]{" + str(size * 2) + "}", value), "hash width/encoding")
    return value


def coin(value):
    require(type(value) is int and 0 <= value <= MAX_COIN, "uint64 coin")
    return value


def point(value):
    require(isinstance(value, dict) and set(value) == {"slot", "blockNo", "hash"}, "full point required")
    return dict(slot=coin(value["slot"]), blockNo=coin(value["blockNo"]), hash=hex_hash(value["hash"], 32))


def address(value):
    require(isinstance(value, str) and re.fullmatch(r"addr_test1[0-9a-z]{20,200}", value), "testnet address text")
    return value


def script_fixture(key_hash, original):
    key_hash = hex_hash(key_hash, 28)
    require(isinstance(original, bytes) and len(original) == 369 and digest(original) == SCRIPT_SHA,
            "registered exact V3 script payload")
    credential = hashlib.blake2b(b"\x03" + original, digest_size=28).hexdigest()
    # CLI text envelope contains a byte string of the serialized script payload.
    wrapped = b"\x59\x01\x71" + original
    datum = dict(constructor=0, fields=[dict(bytes=key_hash), dict(int=5_000_000)])
    return dict(schema="plutus-ingress-script-fixture-v1", json=dict(type="PlutusScriptV3", description="Bounded isolated PV9 test script", cborHex=wrapped.hex()),
                originalCborHex=original.hex(), originalSHA256=digest(original), datum=datum,
                scriptHash=credential, enterpriseAddressHex="70" + credential)


def checked_script_address(fixture, info):
    require(isinstance(info, dict) and info.get("base16") == fixture["enterpriseAddressHex"],
            "reference enterprise script credential binding")
    return address(info.get("address"))


def output(value):
    require(isinstance(value, dict) and isinstance(value.get("value"), dict) and
            set(value["value"]) == {"lovelace"}, "scalar ADA output")
    require(set(value) <= {"address", "value", "datum", "datumhash", "inlineDatum", "inlineDatumhash", "inlineDatumRaw", "referenceScript"},
            "unknown output feature")
    require(all(value.get(k) is None for k in ("datum", "datumhash", "referenceScript")),
            "no datum hash or reference script")
    inline = value.get("inlineDatum")
    if inline is None:
        require(value.get("inlineDatumhash") is None and value.get("inlineDatumRaw") is None, "absent inline datum metadata")
    else:
        # This JSON funding comparison checks datum semantics only. Original datum
        # CBOR/MemPack equality is a separate mandatory Scala bootstrap gate.
        if value.get("inlineDatumhash") is not None:
            hex_hash(value["inlineDatumhash"], 32)
        if value.get("inlineDatumRaw") is not None:
            raw = value["inlineDatumRaw"]
            require(isinstance(raw, str) and 0 < len(raw) <= 512 and len(raw) % 2 == 0 and
                    re.fullmatch("[0-9a-fA-F]+", raw), "bounded inline datum metadata")
        require(isinstance(inline, dict) and set(inline) == {"constructor", "fields"} and
                type(inline["constructor"]) is int and inline["constructor"] == 0 and
                isinstance(inline["fields"], list) and len(inline["fields"]) == 2 and
                set(inline["fields"][0]) == {"bytes"} and set(inline["fields"][1]) == {"int"}, "registered inline datum shape")
        hex_hash(inline["fields"][0]["bytes"], 28)
        require(type(inline["fields"][1]["int"]) is int and inline["fields"][1]["int"] == 5_000_000, "fixed datum minimum")
    return address(value.get("address")), coin(value["value"]["lovelace"])


def whole_utxo(value):
    require(isinstance(value, dict) and len(value) <= 4096, "bounded complete UTxO")
    for key, row in value.items():
        require(isinstance(key, str) and re.fullmatch(r"[0-9a-f]{64}#[0-9]{1,5}", key) and
                str(int(key.split("#")[1])) == key.split("#")[1] and int(key.split("#")[1]) <= 65535, "UTxO input identity")
        output(row)
    return value


def funding_plan(utxo, source_address, script_address, destination, collateral_address, datum):
    whole_utxo(utxo)
    addresses = tuple(address(a) for a in (source_address, script_address, destination, collateral_address))
    require(source_address != script_address and destination == collateral_address and destination != source_address, "script, funding and beneficiary separation")
    selected = [(key, value) for key, value in utxo.items() if value["address"] == source_address]
    require(len(selected) == 1, "one source UTxO")
    txin, value = selected[0]
    require(not any(v["address"] == script_address for v in utxo.values()), "script address must be unfunded")
    require(all(row.get("inlineDatum") is None for row in utxo.values()), "initial datumless funding state")
    change = output(value)[1] - AMOUNT - COLLATERAL - FUNDING_FEE
    require(change >= 1_000_000, "funding change minimum fixture amount")
    return dict(input=txin, sourceCoin=output(value)[1], sourceAddress=source_address,
                scriptAddress=script_address, destination=destination, scriptCoin=AMOUNT,
                change=change, fee=FUNDING_FEE, collateralAddress=collateral_address, datum=datum)


def commands(plan, magic, signing_key):
    require(type(magic) is int and 0 < magic <= 0xffffffff, "private network magic")
    require(isinstance(signing_key, str) and re.fullmatch(r"/work/(?:[A-Za-z0-9_-]+/)+[A-Za-z0-9_-]+\.skey", signing_key),
            "existing container-private signing key path")
    cli = ("cardano-cli", "conway", "transaction")
    def sign(body, signed):
        return cli + ("sign", "--tx-body-file", body, "--signing-key-file", signing_key,
                      "--testnet-magic", str(magic), "--out-file", signed)
    return dict(
        fundingBuild=cli + ("build-raw", "--tx-in", plan["input"],
            "--tx-out", plan["scriptAddress"] + "+" + str(AMOUNT),
            "--tx-out-inline-datum-file", DATUM,
            "--tx-out", plan["collateralAddress"] + "+" + str(COLLATERAL),
            "--tx-out", plan["sourceAddress"] + "+" + str(plan["change"]),
            "--fee", str(FUNDING_FEE), "--invalid-hereafter", "2000", "--out-file", ROOT + "/funding.body"),
        fundingSign=sign(ROOT + "/funding.body", FUNDING),
        spendSign=cli + ("sign", "--tx-body-file", ROOT + "/spend.body", "--signing-key-file", ROOT + "/keys/beneficiary.skey", "--testnet-magic", str(magic), "--out-file", SPEND))


def spend_command(plan, funded_txid, initial_point):
    hex_hash(funded_txid, 32)
    start = point(initial_point)
    require(0 < start["slot"] < 300, "frozen post-funding initial slot")
    return ("cardano-cli", "conway", "transaction", "build-raw", "--tx-in", funded_txid + "#0",
            "--tx-in-script-file", SCRIPT, "--tx-in-inline-datum-present",
            "--tx-in-redeemer-file", REDEEMER, "--tx-in-execution-units", "(30000000,100000)",
            "--tx-in-collateral", funded_txid + "#1", "--protocol-params-file", PARAMETERS,
            "--tx-out", plan["destination"] + "+19700000", "--fee", str(FEE),
            "--invalid-before", str(start["slot"]), "--invalid-hereafter", "999",
            "--out-file", ROOT + "/spend.body")



@dataclass(frozen=True)
class Snapshot:
    full_point: dict
    utxo: dict
    fees: int

    def checked(self):
        point(self.full_point)
        whole_utxo(self.utxo)
        coin(self.fees)
        return self


def funding_comparison(before, after, plan, txid, original, identity):
    before.checked(); after.checked(); hex_hash(txid, 32)
    require(isinstance(original, bytes) and 0 < len(original) <= 65536, "funding original byte bound")
    require(identity.get("transactionId") == txid and identity.get("envelopeSHA256") == digest(original) and
            type(identity.get("bytes")) is int and identity["bytes"] == len(original), "Scala funding original identity")
    hex_hash(identity.get("bodySHA256"), 32); hex_hash(identity.get("witnessesSHA256"), 32)
    require(after.full_point["slot"] > before.full_point["slot"] and
            after.full_point["blockNo"] > before.full_point["blockNo"] and
            after.full_point["hash"] != before.full_point["hash"], "funding must have a later full point")
    old, new = before.utxo, after.utxo
    source = plan["input"]
    require(source in old and output(old[source]) == (plan["sourceAddress"], plan["sourceCoin"]), "exact funding source")
    require(set(new) == (set(old) - {source}) | {txid + "#0", txid + "#1", txid + "#2"}, "complete funding input/output set")
    require(all(new[k] == old[k] for k in set(old) - {source}), "unrelated UTxO changed")
    require(output(new[txid + "#0"]) == (plan["scriptAddress"], AMOUNT) and
            new[txid + "#0"].get("inlineDatum") == plan["datum"] and
            output(new[txid + "#1"]) == (plan["collateralAddress"], COLLATERAL) and
            new[txid + "#1"].get("inlineDatum") is None and
            output(new[txid + "#2"]) == (plan["sourceAddress"], plan["change"]) and
            new[txid + "#2"].get("inlineDatum") is None, "funding output address/value")
    require(after.fees - before.fees == FUNDING_FEE, "funding fee pot delta")
    require(plan["sourceCoin"] == AMOUNT + COLLATERAL + plan["change"] + FUNDING_FEE, "funding conservation")
    return dict(schema="plutus-ingress-bootstrap-comparison-v1", passed=True,
                scope="reference-only-fixture-funding", transactionId=txid,
                originalSHA256=digest(original), originalBytes=len(original),
                bodySHA256=identity["bodySHA256"], witnessesSHA256=identity["witnessesSHA256"],
                beforePoint=point(before.full_point), afterPoint=point(after.full_point),
                beforeFees=before.fees, afterFees=after.fees, completeUtxoChecked=True,
                scalaFundingValidated=False, fullLedgerValidated=False,
                fundedInput=txid + "#0", collateralInput=txid + "#1", profile=PROFILE)


class ReferenceSubmissionGate:
    """Single-use fixture-funding submit gate, then permanent reference-submit prohibition.

    The supplied executor must target the owned private container and enforce its
    deadline/resources. This is a command guard, not an OS sandbox.
    """
    def __init__(self, execute, magic, socket):
        require(type(magic) is int and 0 < magic <= 0xffffffff, "private network magic")
        require(isinstance(socket, str) and re.fullmatch(r"/work/(?:[A-Za-z0-9_-]+/)+sock", socket), "owned socket path")
        self._execute = execute
        self._funding = ("cardano-cli", "conway", "transaction", "submit", "--tx-file", FUNDING,
                         "--testnet-magic", str(magic), "--socket-path", socket)
        self._used = False
        self._sealed = False
        self._returned = False
        self._funding_sha = None

    def submit_funding(self, read_original, expected_sha):
        hex_hash(expected_sha, 32)
        require(not self._used and not self._sealed, "fixture submission unavailable")
        raw = read_original(FUNDING)
        require(isinstance(raw, bytes) and 0 < len(raw) <= 65536 and digest(raw) == expected_sha, "funding original changed")
        self._used = True  # An uncertain result is never silently resubmitted.
        self._funding_sha = expected_sha
        result = self._execute(*self._funding)
        status = getattr(result, "returncode", None)
        require(type(status) is int and status == 0, "explicit integer-zero funding submit status required")
        self._returned = True
        return result

    def execute(self, *argv):
        require(argv and argv[0] == "cardano-cli", "direct reference CLI argv required")
        require("submit" not in argv, "reference submission reserved for fixture-only funding")
        return self._execute(*argv)

    def seal_bootstrap(self, comparison):
        require(self._used and self._returned and not self._sealed, "successful funding submission must precede seal")
        require(comparison.get("schema") == "plutus-ingress-bootstrap-comparison-v1" and
                comparison.get("passed") is True and comparison.get("scope") == "reference-only-fixture-funding" and
                comparison.get("originalSHA256") == self._funding_sha and comparison.get("completeUtxoChecked") is True,
                "confirmed funding comparison required before seal")
        self._sealed = True
