#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Offline native-ingress fixture planning/comparison; no subprocess, Docker or key generation.

The integration owner executes these bounded argv plans in its exclusively owned
reference container. Only fixture funding has a reference submission command.
"""
from dataclasses import dataclass
import hashlib
import re

PROFILE = "isolated-conway-pv9-ada-native-v1"
ROOT = "/work/native-ingress-fixture"
FUNDING = ROOT + "/funding.signed"
SPEND = ROOT + "/spend.signed"
SCRIPT = ROOT + "/script.json"
AMOUNT = 20_000_000
FEE = 200_000
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


def script_fixture(key_hash):
    """Canonical signature-only native script, whose CBOR is also checked by Scala tests."""
    key_hash = hex_hash(key_hash, 28)
    original = bytes.fromhex("8200581c" + key_hash)
    credential = hashlib.blake2b(b"\x00" + original, digest_size=28).hexdigest()
    return dict(schema="native-ingress-script-fixture-v1", json=dict(type="sig", keyHash=key_hash),
                originalCborHex=original.hex(), originalSHA256=digest(original),
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
    require(all(value.get(k) is None for k in ("datum", "datumhash", "inlineDatum", "inlineDatumhash", "inlineDatumRaw", "referenceScript")),
            "no datum or reference script")
    return address(value.get("address")), coin(value["value"]["lovelace"])


def whole_utxo(value):
    require(isinstance(value, dict) and len(value) <= 4096, "bounded complete UTxO")
    for key, row in value.items():
        require(isinstance(key, str) and re.fullmatch(r"[0-9a-f]{64}#[0-9]{1,5}", key) and
                str(int(key.split("#")[1])) == key.split("#")[1] and int(key.split("#")[1]) <= 65535, "UTxO input identity")
        output(row)
    return value


def funding_plan(utxo, source_address, script_address, destination):
    whole_utxo(utxo)
    addresses = tuple(address(a) for a in (source_address, script_address, destination))
    require(len(set(addresses)) == 3, "distinct fixture addresses")
    selected = [(key, value) for key, value in utxo.items() if value["address"] == source_address]
    require(len(selected) == 1, "one source UTxO")
    txin, value = selected[0]
    require(not any(v["address"] == script_address for v in utxo.values()), "script address must be unfunded")
    change = output(value)[1] - AMOUNT - FEE
    require(change >= 1_000_000, "funding change minimum fixture amount")
    return dict(input=txin, sourceCoin=output(value)[1], sourceAddress=source_address,
                scriptAddress=script_address, destination=destination, scriptCoin=AMOUNT,
                change=change, fee=FEE)


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
            "--tx-out", plan["sourceAddress"] + "+" + str(plan["change"]),
            "--fee", str(FEE), "--invalid-hereafter", "2000", "--out-file", ROOT + "/funding.body"),
        fundingSign=sign(ROOT + "/funding.body", FUNDING),
        spendSign=sign(ROOT + "/spend.body", SPEND))


def spend_command(plan, funded_txid, initial_point):
    hex_hash(funded_txid, 32)
    start = point(initial_point)
    require(0 < start["slot"] < 300, "frozen post-funding initial slot")
    return ("cardano-cli", "conway", "transaction", "build-raw", "--tx-in", funded_txid + "#0",
            "--tx-in-script-file", SCRIPT, "--tx-out", plan["destination"] + "+10000000",
            "--tx-out", plan["sourceAddress"] + "+9800000", "--fee", str(FEE),
            "--invalid-before", str(start["slot"]), "--invalid-hereafter", "2000",
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
    require(set(new) == (set(old) - {source}) | {txid + "#0", txid + "#1"}, "complete funding input/output set")
    require(all(new[k] == old[k] for k in set(old) - {source}), "unrelated UTxO changed")
    require(output(new[txid + "#0"]) == (plan["scriptAddress"], AMOUNT) and
            output(new[txid + "#1"]) == (plan["sourceAddress"], plan["change"]), "funding output address/value")
    require(after.fees - before.fees == FEE, "funding fee pot delta")
    require(plan["sourceCoin"] == AMOUNT + plan["change"] + FEE, "funding conservation")
    return dict(schema="native-ingress-bootstrap-comparison-v1", passed=True,
                scope="reference-only-fixture-funding", transactionId=txid,
                originalSHA256=digest(original), originalBytes=len(original),
                bodySHA256=identity["bodySHA256"], witnessesSHA256=identity["witnessesSHA256"],
                beforePoint=point(before.full_point), afterPoint=point(after.full_point),
                beforeFees=before.fees, afterFees=after.fees, completeUtxoChecked=True,
                scalaFundingValidated=False, fullLedgerValidated=False,
                fundedInput=txid + "#0", profile=PROFILE)


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
        require(comparison.get("schema") == "native-ingress-bootstrap-comparison-v1" and
                comparison.get("passed") is True and comparison.get("scope") == "reference-only-fixture-funding" and
                comparison.get("originalSHA256") == self._funding_sha and comparison.get("completeUtxoChecked") is True,
                "confirmed funding comparison required before seal")
        self._sealed = True


def negative_reference_comparison(case, candidate, control, reference):
    """Compare explicit oracle evidence; Unsupported/resource errors are not ledger rejections.

    Native negatives run in a separate disposable oracle fixture, never on the
    acceptance connection. Text-only CLI diagnostics are retained but insufficient.
    """
    allowed = {"missing-script": "MissingScripts", "wrong-script": "WrongScriptHashes",
               "unrelated-signer": "FailedScripts"}
    require(case in allowed, "bounded witness-only negative case")
    for row in (candidate, control):
        hex_hash(row.get("transactionId"), 32); hex_hash(row.get("originalSHA256"), 32)
        hex_hash(row.get("bodySHA256"), 32)
    require(candidate.get("outcome") == "Rejected" and candidate.get("predicate") == allowed[case] and
            candidate.get("fullLedgerValidated") is False, "typed Scala rejection")
    require(control.get("outcome") == "ScopedDerived" and control.get("fullLedgerValidated") is False,
            "same-state positive Scala control")
    require(candidate["transactionId"] == control["transactionId"] and
            candidate["bodySHA256"] == control["bodySHA256"] and
            candidate["originalSHA256"] != control["originalSHA256"], "same-body witness-only pair")
    require(reference.get("schema") == "native-script-oracle-v1" and reference.get("ledgerRejected") is True and
            reference.get("originalSHA256") == candidate["originalSHA256"] and
            reference.get("predicate") == allowed[case], "typed exact-original reference oracle")
    require(reference.get("stateUnchanged") is True and reference.get("evaluationSlotEstablished") is True and
            reference.get("preStateSHA256") == candidate.get("preStateSHA256") == control.get("preStateSHA256") and
            type(reference.get("validationSlot")) is int and reference["validationSlot"] == candidate.get("validationSlot") == control.get("validationSlot"),
            "oracle state/slot binding")
    hex_hash(reference.get("preStateSHA256"), 32)
    coin(reference["validationSlot"])
    require(type(candidate.get("validationSlot")) is int and type(control.get("validationSlot")) is int,
            "exact control and candidate slots")
    return dict(schema="native-ingress-negative-comparison-v1", case=case, passed=True,
                transactionId=candidate["transactionId"], originalSHA256=candidate["originalSHA256"],
                predicate=allowed[case], fullLedgerValidated=False, liveApiAcceptance=False)
