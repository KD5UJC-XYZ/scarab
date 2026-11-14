#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Write the PlutusV3 minting policies of the writeBits canary (GHSA-vfw2-hhrc-87v5), and its cost model.

    python3 -I antithesis/pool/canary/writebits_script.py      (from the repository root)

The policy is `(lam ctx [(lam _ ()) [(builtin writeBits) #00…00 [] False]])` with N zero bytes: it ignores its
script context and returns unit once `writeBits` returns. At protocol 11 the reference fails `writeBits` on an input
longer than 4,096 bytes; Scarab before the fix evaluated it successfully.

The unwrapped term is the one `WriteBitsLimitReferenceSuite` evaluates and the reference ran
(`fixtures/reference/plutus-writebits-limit/requests.jsonl`, `writebits-v3-pv11-4096` and `-4097`): this script
encodes it, checks the bytes against the fixture, and only then adds the lambda that takes the context, which a
minting policy is applied to. It writes `writebits-4096.plutus` and `writebits-4097.plutus` as cardano-cli text
envelopes next to itself.

create-env's PlutusV3 cost model has 251 parameters, which do not price `writeBits`, so the reference refuses the
policy for its budget there, and cardano-node 11.1.3 reads a Conway genesis only with exactly 251. The fixture's
requests carry the reference's model at protocol 11, 361 parameters whose first 251 are create-env's; it is written
as `plutus-v3-cost-model.json`, which the canary enacts by a governance parameter change, as mainnet did.
"""
import json
import pathlib

FIXTURE = pathlib.Path("fixtures/reference/plutus-writebits-limit/requests.jsonl")
WRITE_BITS = 80


class Bits:
    """A flat encoder (Plutus Core's on-chain format with de Bruijn indices), for the terms used here only."""

    def __init__(self):
        self.bits = []

    def put(self, value, width):
        self.bits.extend((value >> (width - 1 - i)) & 1 for i in range(width))

    def filler(self):
        self.bits.extend([0] * (7 - len(self.bits) % 8) + [1])

    def natural(self, n):
        while True:
            chunk, n = n & 0x7F, n >> 7
            self.put(chunk | (0x80 if n else 0), 8)
            if not n:
                return

    def type_tags(self, tags):
        for tag in tags:
            self.put(1, 1)
            self.put(tag, 4)
        self.put(0, 1)

    def to_bytes(self):
        assert len(self.bits) % 8 == 0
        return bytes(int("".join(map(str, self.bits[i:i + 8])), 2) for i in range(0, len(self.bits), 8))


def term(out, n, context_lambda):
    if context_lambda:
        out.put(2, 4)                              # (lam ctx ...)
    out.put(3, 4)                                  # [(lam _ ()) ...]
    out.put(2, 4)
    out.put(4, 4); out.type_tags([3])              # ()
    out.put(3, 4); out.put(3, 4); out.put(3, 4)    # [[[writeBits input] indices] False]
    out.put(7, 4); out.put(WRITE_BITS, 7)
    out.put(4, 4); out.type_tags([1])              # #00…00
    out.filler()
    data = bytes(n)
    for i in range(0, n, 255):
        chunk = data[i:i + 255]
        out.put(len(chunk), 8)
        out.bits.extend(b for byte in chunk for b in ((byte >> (7 - k)) & 1 for k in range(8)))
    out.put(0, 8)
    out.put(4, 4); out.type_tags([7, 5, 0]); out.put(0, 1)   # [] : list integer
    out.put(4, 4); out.type_tags([4]); out.put(0, 1)         # False


def program(n, context_lambda):
    out = Bits()
    for part in (1, 1, 0):
        out.natural(part)
    term(out, n, context_lambda)
    out.filler()
    return out.to_bytes()


def cbor_bytes(data):
    assert len(data) < 1 << 16
    return (bytes([0x59]) + len(data).to_bytes(2, "big") if len(data) > 0xFF else bytes([0x58, len(data)])) + data


def main():
    out_dir = pathlib.Path(__file__).resolve().parent
    rows = {row["id"]: row for row in map(json.loads, FIXTURE.read_text().splitlines())}
    models = {json.dumps(rows[f"writebits-v3-pv11-{n}"]["costModel"]) for n in (4096, 4097)}
    if len(models) != 1:
        raise SystemExit("the two requests carry different cost models")
    (out_dir / "plutus-v3-cost-model.json").write_text('{"PlutusV3": ' + models.pop() + "}\n")
    for n in (4096, 4097):
        unwrapped = cbor_bytes(program(n, context_lambda=False)).hex()
        if unwrapped != rows[f"writebits-v3-pv11-{n}"]["script"]:
            raise SystemExit(f"the encoding of the {n}-byte term differs from the fixture")
        envelope = {"type": "PlutusScriptV3", "description": f"writeBits on {n} zero bytes, then unit",
                    "cborHex": cbor_bytes(program(n, context_lambda=True)).hex()}
        (out_dir / f"writebits-{n}.plutus").write_text(json.dumps(envelope, indent=4) + "\n")
    print("policies and cost model written; the unwrapped terms match the fixture")


if __name__ == "__main__":
    main()
