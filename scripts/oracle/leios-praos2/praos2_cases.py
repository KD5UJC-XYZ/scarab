#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Prepare the maintained rc-2026w44 headers and their genesis stake for the Praos2 oracle.

The input distribution covers the first two epochs only; no ledger replay is performed here.
Original header encodings are retained after removing the ChainSync hard-fork/tag-24 wrapper.
"""
import argparse
import hashlib
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts/oracle/leios-testnet"))
import leios_cases as c  # noqa: E402


def inputs():
    source = ROOT / "fixtures/leios/testnet"
    genesis = json.loads((source / "config/shelley-genesis.json").read_text())
    extra = genesis["extraConfig"]
    delegated = extra["stakeCredentials"]["data"]
    stake = dict.fromkeys(extra["stakePools"]["data"], 0)
    for address, amount in extra["initialFunds"]["data"].items():
        raw = bytes.fromhex(address)
        if raw[0] >> 4 == 0:
            assert len(raw) == 57
            stake[delegated[raw[-28:].hex()]] += amount
        else:
            assert raw[0] >> 4 == 6 and len(raw) == 29, "unexpected genesis address form"
    pools = [
        {"pool": pool, "vrf": details["vrf"], "stake": stake[pool]}
        for pool, details in extra["stakePools"]["data"].items()
    ]
    headers = []
    for line in (source / "headers-from-origin.tsv").read_text().splitlines():
        if not line or line.startswith("#"):
            continue
        envelope = c.parts(bytes.fromhex(line.split("\t")[4]))
        assert envelope[0] == b"\x07" and envelope[1].startswith(b"\xd8\x18")
        headers.append(c.content(envelope[1][2:]).hex())
    return {"pools": pools, "headers": headers}


def number(raw):
    return raw[0] if raw[0] < 24 else int.from_bytes(raw[1:], "big")


def change(raw, updates, signature=None):
    body, old_signature = c.parts(raw)
    fields = c.parts(body)
    for index, replacement in updates.items():
        fields[index] = replacement
    return c.items([c.items(fields), old_signature if signature is None else signature])


def sign_inputs(source):
    raw = bytes.fromhex(source["headers"][5])
    announcement = c.items([c.c.bstr(bytes.fromhex("ab" * 32)), c.c.uint(40000)])
    return [(change(raw, update).hex(), counter) for update, counter in [
        ({}, 0), ({11: announcement}, 0), ({10: b"\xf5"}, 0),
        ({10: b"\xf5", 11: announcement}, 0), ({}, 1), ({}, 2), ({}, 2**64 - 1),
    ]]


def cases(source, signed=None):
    """Mutated headers keep their original signatures, exposing cheap-check precedence.

    Reupdate deliberately skips signature validation, as the native trusted-header path does.
    It is used to compare announcement storage, not to claim the mutated headers are valid.
    """
    result = []
    raw = bytes.fromhex(source["headers"][5])
    fields = c.parts(c.parts(raw)[0])
    slot = number(fields[1])
    u, b = c.c.uint, c.c.bstr
    maximum = 2**64 - 1
    announcement = c.items([b(bytes.fromhex("ab" * 32)), u(40000)])
    announced = change(raw, {11: announcement})
    certified = change(raw, {10: b"\xf5"})

    def add(name, header=raw, context=5, **options):
        result.append({"name": name, "context": context, "header": header.hex(), "options": options})

    add("original")
    add("protocol-only-without-chain-prefix", context=0, operation="protocol")
    add("wrong-block-before-slot-and-prev", change(raw, {0: u(6), 1: u(0), 2: b(bytes(32))}))
    add("wrong-slot-before-prev", change(raw, {1: u(0), 2: b(bytes(32))}))
    add("wrong-prev", change(raw, {2: b(bytes(32))}))
    add("checkpoint-before-obsolete", checkpointBlock=5, checkpointHash="00" * 32, major=13)
    add("matching-checkpoint", checkpointBlock=5, checkpointHash=hashlib.blake2b(raw, digest_size=32).hexdigest())
    add("obsolete-before-size", major=13, headerLimit=0, bodyLimit=0)
    add("header-size-before-body", headerLimit=0, bodyLimit=0)
    add("body-size", bodyLimit=0)
    add("exact-size-bounds", headerLimit=len(raw), bodyLimit=number(fields[6]))
    add("advertised-version-is-not-ledger-version", change(raw, {9: c.items([u(2**32 - 1), u(2**32 - 1)])}))
    add("eb-size-before-missing-announcement", change(certified, {11: c.items([b(bytes(32)), u(40001)])}))
    add("eb-size-at-bound", announced)
    add("eb-size-zero", change(raw, {11: c.items([b(bytes(32)), u(0)])}), ebLimit=0)
    add("certificate-without-announcement", certified)
    add("certificate-with-origin-last-slot", certified, announcementHeader=announced.hex(), originLastSlot=True, skipTick=True)
    add("certificate-one-slot-too-young", certified, announcementHeader=announced.hex(), lastSlot=slot - 13)
    add("certificate-at-earliest", certified, announcementHeader=announced.hex(), lastSlot=slot - 14)
    add("certificate-after-earliest", certified, announcementHeader=announced.hex(), lastSlot=slot - 15)
    add("certificate-zero-periods", certified, announcementHeader=announced.hex(), lastSlot=slot,
        announcementMillis=0, voteMillis=0, diffusionMillis=0)
    add("certificate-maximum-periods", certified, announcementHeader=announced.hex(), lastSlot=0,
        announcementMillis=2**32 - 1, voteMillis=2**32 - 1, diffusionMillis=2**32 - 1)
    add("certificate-truncated-slot-length", certified, announcementHeader=announced.hex(), lastSlot=slot - 14, slotMicros=1500)
    add("certificate-slot-wrap", certified, announcementHeader=announced.hex(), lastSlot=maximum - 5, skipTick=True)
    add("certificate-cannot-cross-epoch", change(certified, {1: u(21600)}), announcementHeader=announced.hex())
    add("accepted-header-clears-announcement", announcementHeader=announced.hex())
    add("reupdate-stores-announcement", announced, operation="reupdate")
    add("reupdate-replaces-announcement", change(raw, {11: c.items([b(bytes.fromhex("cd" * 32)), u(1)])}),
        operation="reupdate", announcementHeader=announced.hex())
    add("reupdate-clears-announcement", operation="reupdate", announcementHeader=announced.hex())
    add("reupdate-maximum-slot-wrap", change(raw, {1: u(maximum)}), operation="reupdate", skipTick=True)
    add("tick-preserves-announcement", operation="tick", announcementHeader=announced.hex())
    add("tick-new-epoch-clears-announcement", change(raw, {1: u(21600)}), operation="tick", announcementHeader=announced.hex())
    signature = bytearray(c.content(c.parts(raw)[1]))
    signature[0] ^= 1
    add("invalid-kes-signature", change(raw, {}, b(signature)))
    cert = c.parts(fields[8])
    broken_cert = bytearray(c.content(cert[3]))
    broken_cert[0] ^= 1
    add("invalid-cold-signature-before-counter", change(raw, {8: c.items(cert[:3] + [b(broken_cert)])}), counter=2)
    add("no-counter-for-unknown-pool", context=0, operation="protocol", poolOverride=[])
    add("unknown-vrf-pool-with-existing-counter", poolOverride=[])
    wrong_pool = dict(source["pools"][0], vrf="00" * 32)
    add("wrong-registered-vrf-key", poolOverride=[wrong_pool])
    add("vrf-proof-wrong-epoch-nonce", epochNonce="00" * 32)
    add("vrf-not-elected", poolOverride=[dict(source["pools"][0], stake=1),
        {"pool": "00" * 28, "vrf": "00" * 32, "stake": maximum - 1}])
    add("counter-too-small", counter=2)
    add("kes-before-start", change(raw, {8: c.items([cert[0], cert[1], u(1), cert[3]])}))
    add("kes-after-end", maxKesEvolutions=0)
    add("kes-end-wrap", change(raw, {1: u(maximum), 8: c.items([cert[0], cert[1], u(maximum - 1), cert[3]])}),
        operation="protocol", skipTick=True, kesPeriodSlots=1, maxKesEvolutions=2)
    for name, options in [
        ("default", {}),
        ("ceiling", {"slotMicros": 3000000}),
        ("truncated-slot", {"slotMicros": 1500}),
        ("maximum-periods", {"announcementMillis": 2**32 - 1, "voteMillis": 2**32 - 1, "diffusionMillis": 2**32 - 1}),
        ("slot-wrap", {"lastSlot": maximum - 5}),
        ("zero-periods", {"announcementMillis": 0, "voteMillis": 0, "diffusionMillis": 0}),
    ]:
        add("timing-" + name, operation="certificationSlot", **options)
    if signed is not None:
        assert len(signed) == 7
        signed = [bytes.fromhex(h) for h in signed]
        signed_fields = c.parts(c.parts(signed[0])[0])
        pool = {"pool": hashlib.blake2b(c.content(signed_fields[3]), digest_size=28).hexdigest(),
                "vrf": hashlib.blake2b(c.content(signed_fields[4]), digest_size=32).hexdigest(),
                "stake": source["pools"][0]["stake"]}
        add("signed-ordinary", signed[0], poolOverride=[pool])
        add("signed-announcement-at-bound", signed[1], poolOverride=[pool])
        add("signed-certificate-at-earliest", signed[2], poolOverride=[pool], announcementHeader=signed[1].hex(), lastSlot=slot - 14)
        add("signed-certificate-and-new-announcement", signed[3], poolOverride=[pool], announcementHeader=signed[1].hex(), lastSlot=slot - 14)
        add("signed-counter-first-one", signed[4], poolOverride=[pool])
        add("signed-counter-first-two-refused", signed[5], poolOverride=[pool])
        add("signed-counter-increment", signed[5], poolOverride=[pool], counter=1)
        add("signed-counter-unchanged", signed[5], poolOverride=[pool], counter=2)
        add("signed-counter-over-increment", signed[5], poolOverride=[pool], counter=0)
        add("signed-counter-upper-wrap", signed[6], poolOverride=[pool], counter=maximum)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--inputs", type=Path)
    parser.add_argument("--cases-inputs", type=Path)
    parser.add_argument("--reference", type=Path)
    parser.add_argument("--out", type=Path)
    parser.add_argument("--sign-inputs", type=Path)
    parser.add_argument("--signed", type=Path)
    args = parser.parse_args()
    source = inputs()
    generated = cases(source, json.loads(args.signed.read_text()) if args.signed else None)
    if args.sign_inputs:
        args.sign_inputs.write_text(json.dumps(sign_inputs(source), separators=(",", ":")) + "\n")
    if args.inputs:
        args.inputs.write_text(json.dumps(source, separators=(",", ":")) + "\n")
    if args.cases_inputs:
        expanded = [dict(pools=source["pools"], headers=source["headers"][:case["context"]] + [case["header"]],
                         name=case["name"], options=case["options"]) for case in generated]
        args.cases_inputs.write_text(json.dumps(expanded, separators=(",", ":")) + "\n")
    if args.reference and args.out:
        native = {case["name"]: case["result"][0] for case in json.loads(args.reference.read_text())}
        assert set(native) == {case["name"] for case in generated}
        args.out.write_text("".join(json.dumps(dict(case, result=native[case["name"]]), separators=(",", ":")) + "\n"
                                    for case in generated))


if __name__ == "__main__":
    main()
