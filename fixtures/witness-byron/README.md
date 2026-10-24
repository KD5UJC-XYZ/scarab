# Byron Ed25519 reference verdicts

Differential corpus for the Byron-era verifier `ByronEd25519` (#392). The inputs are public; every
verdict was produced by the **unchanged released** reference libraries on the Byron path of
cardano-node 11.1.3, never written by hand. See [`provenance.json`](provenance.json) and verify the
files against [`SHA256SUMS`](SHA256SUMS) (`sha256sum -c SHA256SUMS` in this directory).

## Contents

- `generated.tsv`: 569 rows, no header, columns `id`, `public_key_hex`, `signature_hex`,
  `message_hex` (the layout of [`../witness/vectors.tsv`](../witness/vectors.tsv)). Written by
  [`scripts/oracle/byron-donna/generate.py`](../../scripts/oracle/byron-donna/generate.py), which is
  deterministic (secrets and nonces are SHA-512 of fixed labels) and writes no verdict.
- `reference-generated.tsv`: one row per `generated.tsv` row, columns `id`, `vkey`, `redeem`.
- `reference-witness-vectors.tsv`: the same for the 2,219 rows of the strict witness corpus
  [`../witness/vectors.tsv`](../witness/vectors.tsv).

`vkey` is `verifySignatureRaw` of cardano-crypto-wrapper 1.7.0.0 (block headers, delegation
certificates, update proposals and votes, ordinary transaction witnesses), with the key extended by
a chain code that verification ignores; `redeem` is `verifyRedeemSigRaw` (AVVM redeem witnesses).
Both reach `Crypto.PubKey.Ed25519.verify` of crypton 1.0.6, the ed25519-donna `ed25519_sign_open`.
A verdict is `accept`, `reject`, or `size` when the key or signature has a length the API cannot
take (the Byron decoders refuse those bytes before verification).

## Classes

The row ids name what each row exercises:

| Class | Rows | Reference |
|---|---|---|
| non-canonical S | `s-plus-kL-*` (S + kL, k = 1..15), `s-top-*`, `*-s-plus-L-*`, `*-s-is-L-*` | S + L accepted (S below 2^253, reduced modulo L); S + 2L and beyond, and any of the top three bits set, refused |
| small-order A or R | `a-order{1,2,4,8}-*` (each of the eight torsion points as the key), `ar-small-*` (both of small order, S = 0), `r-identity-*`, `r-small-mixed-key-*`, `a-mixed-*` | accepted |
| non-canonical A | `a-y-p-plus-t-sign{0,1}-*` (the 19 encodings with y >= p), `a-x0-sign-*` (x = 0 with the sign bit set) | accepted when y mod p is on the curve and the signature holds; `-as-canonical-*` shows the challenge hashes the original key bytes |
| controls | `control-*` (valid, other message, flipped bits, off-curve A or R), `r-identity-{y-p-plus-1,x0-sign}*` (non-canonical R) | as an ordinary verifier; a non-canonical R is refused |
| mixture | `mixture-*`: 256 seeded combinations of the mutations above | 35 accepted, 221 refused |

In the strict corpus the reference accepts 1,031 rows that `StrictEd25519` refuses (the 1,024
sodium S + L rows and seven published edge-case vectors) and refuses none that it
accepts. `ByronEd25519ReferenceSuite` asserts every verdict, both directions of that comparison
and the digest pins.

## Regenerating

Build the reference image as [`scripts/oracle/byron-donna/README.md`](../../scripts/oracle/byron-donna/README.md)
describes, then from the repository root:

```sh
python3 -I scripts/oracle/byron-donna/generate.py > generated.tsv
docker run --rm -i --network none --read-only scarab-392-byron-donna-reference:local < generated.tsv
docker run --rm -i --network none --read-only scarab-392-byron-donna-reference:local < fixtures/witness/vectors.tsv
```

The generator output and both verdict files must be byte-identical to the committed ones.
