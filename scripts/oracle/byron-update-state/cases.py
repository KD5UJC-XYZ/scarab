#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Cases for the Byron update-interface state oracle (#726).

Each case describes a `UPI.State` field by field (`currentEpoch` is always 0: only `initialState`
writes it). The initial states take the protocol parameters of the repository's Byron genesis files
as `FromJSON ProtocolParameters` reads them; the others hold candidates, application versions,
protocol and software proposals with their installer metadata, confirmations, votes, endorsements
and registration slots, with keys out of order and values at the bounds of their types.

`--inputs` writes the harness input; `--reference` with `--out` merges the harness output into
`cases.jsonl`, a line per case with its state and the reference's encoding (`cbor`), after checking
that every encoding read back as the state it was built from.
"""
import argparse
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
GENESIS = {
    "mainnet": "fixtures/mainnet/genesis/byron-genesis.json",
    "preprod": "fixtures/preprod/genesis/byron-genesis.json",
    "preview": "fixtures/preview/genesis/byron-genesis.json",
    "eras-devnet": "fixtures/eras/genesis/byron-genesis.json",
}
WORD64 = 2 ** 64 - 1
WORD32 = 2 ** 32 - 1
WORD16 = 2 ** 16 - 1
NANO = 10 ** 9
PORTION = 10 ** 15
MAX_LOVELACE = 45 * 10 ** 15


def genesis_parameters(path):
    """The parameters `FromJSON ProtocolParameters` reads (`Update/ProtocolParameters.hs:144-160`):
    the summand a `Word64` of nano-lovelace divided by 10^9, the multiplier nano-lovelace."""
    b = json.loads((ROOT / path).read_text(encoding="utf-8"))["blockVersionData"]
    rule = b["softforkRule"]
    return {
        "scriptVersion": int(b["scriptVersion"]),
        "slotDuration": int(b["slotDuration"]),
        "maxBlockSize": int(b["maxBlockSize"]),
        "maxHeaderSize": int(b["maxHeaderSize"]),
        "maxTxSize": int(b["maxTxSize"]),
        "maxProposalSize": int(b["maxProposalSize"]),
        "mpcThd": int(b["mpcThd"]),
        "heavyDelThd": int(b["heavyDelThd"]),
        "updateVoteThd": int(b["updateVoteThd"]),
        "updateProposalThd": int(b["updateProposalThd"]),
        "updateProposalTtl": int(b["updateImplicit"]),
        "softforkRule": [int(rule["initThd"]), int(rule["minThd"]), int(rule["thdDecrement"])],
        "summand": int(b["txFeePolicy"]["summand"]) // NANO,
        "multiplier": int(b["txFeePolicy"]["multiplier"]),
        "unlockStakeEpoch": int(b["unlockStakeEpoch"]),
    }


BASE = genesis_parameters(GENESIS["mainnet"])


def params(**changes):
    p = dict(BASE)
    p.update(changes)
    return p


def h32(*prefix):
    return bytes(prefix).hex() + "a5" * (32 - len(prefix))


def h28(*prefix):
    return bytes(prefix).hex() + "5a" * (28 - len(prefix))


def empty_state(parameters):
    return {
        "adoptedVersion": [0, 0, 0],
        "adoptedParameters": parameters,
        "candidates": [],
        "appVersions": [],
        "protocolProposals": [],
        "softwareProposals": [],
        "confirmed": [],
        "votes": [],
        "endorsements": [],
        "registrationSlots": [],
    }


def state(**fields):
    s = empty_state(BASE)
    s.update(fields)
    return s


def meta(*tags):
    return [{"tag": tag, "hash": h32(i + 1)} for i, tag in enumerate(tags)]


TEXTS = ["b", "ab", "a", "aa", "", "Z", "é", "�", "\U0001f600", "aé", "zz"]


def cases():
    out = [{"name": f"initial-{net}", "state": empty_state(genesis_parameters(path))}
           for net, path in GENESIS.items()]
    out.append({"name": "registered-and-confirmed", "state": state(
        adoptedVersion=[1, 0, 0],
        candidates=[{"slot": 4321, "version": [2, 0, 0], "parameters": params(maxBlockSize=4000000)}],
        appVersions=[
            {"name": "cardano-sl", "number": 1, "slot": 120, "metadata": meta("win64", "macos64", "linux")},
            {"name": "csl-daedalus", "number": 7, "slot": 98, "metadata": meta("linux")},
        ],
        protocolProposals=[
            {"id": h32(0x80), "version": [2, 0, 0], "parameters": params(maxBlockSize=4000000)},
            {"id": h32(0x01), "version": [1, 1, 0], "parameters": params(maxTxSize=8192)},
        ],
        softwareProposals=[
            {"id": h32(0x7f), "name": "cardano-sl", "number": 2, "metadata": meta("win64", "linux")},
            {"id": h32(0x02), "name": "daedalus", "number": 0, "metadata": meta("macos64")},
        ],
        confirmed=[{"id": h32(0x80), "slot": 4000}, {"id": h32(0x01), "slot": 3990}],
        votes=[
            {"id": h32(0x80), "voters": [h28(0x33), h28(0x01), h28(0xf0)]},
            {"id": h32(0x01), "voters": [h28(0x01)]},
            {"id": h32(0x7f), "voters": []},
        ],
        endorsements=[
            {"version": [2, 0, 0], "key": h28(0x33)},
            {"version": [1, 1, 0], "key": h28(0xf0)},
            {"version": [2, 0, 0], "key": h28(0x01)},
        ],
        registrationSlots=[{"id": h32(0x80), "slot": 3900}, {"id": h32(0x7f), "slot": 3950},
                           {"id": h32(0x01), "slot": 3800}, {"id": h32(0x02), "slot": 3999}],
    )})
    out.append({"name": "candidates-kept-in-list-order", "state": state(candidates=[
        {"slot": 900, "version": [3, 0, 0], "parameters": params(scriptVersion=1)},
        {"slot": 100, "version": [1, 0, 0], "parameters": params()},
        {"slot": 500, "version": [2, 4, 1], "parameters": params(maxHeaderSize=1)},
    ])})
    out.append({"name": "text-keys-in-utf8-order", "state": state(
        appVersions=[{"name": t, "number": i, "slot": i, "metadata": meta(*reversed(TEXTS)) if i == 0 else []}
                     for i, t in enumerate(TEXTS)],
        softwareProposals=[{"id": h32(i), "name": t, "number": i, "metadata": meta(t) if i else meta(*TEXTS)}
                           for i, t in enumerate(TEXTS)],
    )})
    out.append({"name": "hash-keys-unsigned-order", "state": state(
        protocolProposals=[{"id": h32(b), "version": [0, 0, b], "parameters": params()}
                           for b in (0xff, 0x00, 0x80, 0x7f, 0x01)],
        confirmed=[{"id": h32(b, 0x00), "slot": b} for b in (0xff, 0x80, 0x7f)]
        + [{"id": h32(0x80, 0xff), "slot": 1}],
        votes=[{"id": h32(0x10), "voters": [h28(b) for b in (0xff, 0x00, 0x80, 0x7f)]}],
        registrationSlots=[{"id": h32(b), "slot": b} for b in range(0, 256, 9)],
    )})
    out.append({"name": "endorsements-by-version-then-key", "state": state(endorsements=[
        {"version": list(v), "key": h28(k)}
        for v in [(1, 0, 0), (0, 2, 0), (0, 1, 9), (0, 1, 10), (0, 10, 0), (WORD16, 0, 0)]
        for k in (0x80, 0x01)
    ])})
    out.append({"name": "word-bounds", "state": state(
        adoptedVersion=[WORD16, WORD16, 255],
        adoptedParameters=params(scriptVersion=WORD16, updateProposalTtl=WORD64, unlockStakeEpoch=WORD64,
                                 mpcThd=PORTION, heavyDelThd=0, updateVoteThd=PORTION, updateProposalThd=0,
                                 softforkRule=[PORTION, 0, PORTION]),
        candidates=[{"slot": WORD64, "version": [WORD16, WORD16, 255], "parameters": params()}],
        appVersions=[{"name": "max", "number": WORD32, "slot": WORD64, "metadata": []}],
        softwareProposals=[{"id": h32(9), "name": "max", "number": WORD32, "metadata": []}],
        confirmed=[{"id": h32(9), "slot": WORD64}],
        registrationSlots=[{"id": h32(9), "slot": WORD64}, {"id": h32(8), "slot": 0}],
    )})
    out.append({"name": "naturals-and-nano", "state": state(
        adoptedParameters=params(slotDuration=0, maxBlockSize=WORD64, maxHeaderSize=WORD64 + 1,
                                 maxTxSize=10 ** 30, maxProposalSize=23, summand=MAX_LOVELACE,
                                 multiplier=-1),
        candidates=[
            {"slot": 1, "version": [1, 0, 0], "parameters": params(summand=0, multiplier=0)},
            {"slot": 2, "version": [1, 0, 1], "parameters": params(summand=1, multiplier=-(2 ** 64) - 1)},
            {"slot": 3, "version": [1, 0, 2], "parameters": params(summand=18446744073, multiplier=2 ** 70)},
            {"slot": 4, "version": [1, 0, 3], "parameters": params(summand=18446744074, multiplier=WORD64)},
        ],
    )})
    out.append({"name": "twenty-four-entries", "state": state(
        appVersions=[{"name": f"app{i:02d}", "number": i, "slot": i,
                      "metadata": meta(*[f"t{j:02d}" for j in range(24)]) if i == 0 else []}
                     for i in range(24)],
        protocolProposals=[{"id": h32(i), "version": [1, i, 0], "parameters": params(maxTxSize=4096 + i)}
                           for i in range(24)],
        softwareProposals=[{"id": h32(i), "name": f"sw{i:02d}", "number": i, "metadata": []}
                           for i in range(24)],
        confirmed=[{"id": h32(i), "slot": 2000 + i} for i in range(24)],
        votes=[{"id": h32(i), "voters": [h28(j) for j in range(24)] if i == 1 else [h28(i)]}
               for i in range(24)],
        endorsements=[{"version": [1, 0, 0], "key": h28(i)} for i in range(24)],
        registrationSlots=[{"id": h32(i), "slot": 1000 + i} for i in range(24)],
    )})
    return out


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--inputs", required=True, type=Path)
    ap.add_argument("--reference", type=Path)
    ap.add_argument("--out", type=Path)
    args = ap.parse_args()
    all_cases = cases()
    names = [c["name"] for c in all_cases]
    assert len(names) == len(set(names)), "case names repeat"
    if args.reference is None:
        args.inputs.write_text(json.dumps(all_cases, ensure_ascii=False) + "\n", encoding="utf-8")
        return
    if args.out is None:
        ap.error("--reference needs --out")
    results = json.loads(args.reference.read_text(encoding="utf-8"))
    if len(results) != len(all_cases):
        raise SystemExit(f"{len(results)} results for {len(all_cases)} cases")
    lines = []
    for case, result in zip(all_cases, results):
        if "failure" in result:
            raise SystemExit(f"{case['name']}: the harness refused the case: {result['failure']}")
        if result["name"] != case["name"] or result["readsBack"] is not True:
            raise SystemExit(f"{case['name']}: unexpected result {result}")
        lines.append(json.dumps({"name": case["name"], "state": case["state"], "cbor": result["cbor"]},
                                ensure_ascii=False, sort_keys=True))
    args.out.write_text("\n".join(lines) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
