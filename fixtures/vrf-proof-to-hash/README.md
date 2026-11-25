<!-- SPDX-License-Identifier: Apache-2.0 -->
# The reference's output of a VRF proof alone, on edge proofs

The verdict of the reference's `crypto_vrf_ietfdraft03_proof_to_hash`, the output of a proof without verifying it
(cardano-base `outputFromProof`), on 105 proofs: the `pi` of each of the seven vectors of `../vrf-cardano-base`,
unchanged and with fourteen changes each. `ProofToOutputSuite` (crypto tests) rebuilds every row from its vector
and change, and requires the test-scope `ProofToOutput` to give the native result on each.

| File | Contents |
|---|---|
| `cases.tsv` | One row per case, no header: `<vector file>/<change>`, then the 80-byte proof in hex. SHA-256 is pinned in the suite. |
| `sodium-results.tsv` | One row per case, no header: the id, then `Output:<64 bytes in hex>` or `Refused` (the function returned nonzero). |
| `oracle/proof_to_hash.c`, `oracle/build-and-run.sh` | The driver that produced `sodium-results.tsv`, and the script that builds the library and runs it. |

The changes, on the vector's `pi`:

- `unchanged`; `c-bit-0`, bit 0 of byte 32 (the challenge) flipped;
- s replaced by s + L, by L, by L - 1, by 2^252, and s with bit 255 set (`s-plus-L`, `s-equals-L`, `s-L-minus-1`,
  `s-2^252`, `s-bit-255`);
- Gamma replaced by the identity (`01 00…00`), the identity with the sign bit set, the point of order 2 (y = p - 1),
  a point of order 8 (libsodium's blacklist entry `26e8958f…`), the basepoint (`5866…66`), the non-canonical
  encoding of the identity (y = p + 1), the non-canonical y = p + 3 (a point not of small order), and the
  off-curve y = 2.

The native results: the changes of c, of s to L - 1 or 2^252, and every Gamma of small order or the basepoint give an
output; the non-canonical and off-curve Gammas, and s at or above L, are refused. That is what
`cardano-crypto-praos/cbits/vrf03/verify.c:13-41` at cardano-base `060819b59c184b951a54e3c563304983c53a3eac` says
(the Scaladoc of `ProofToOutput` goes through it step by step).

## How the results were produced

cardano-base's `outputFromProof` calls `crypto_vrf_ietfdraft03_proof_to_hash` (`cardano-crypto-praos/src/Cardano/Crypto/VRF/Praos.hs:197-198,514-522`).
The package compiles its own copy, `cbits/vrf03/verify.c`, only when its flag `external-libsodium-vrf` is off; by
default it takes the function from the libsodium fork (`cardano-crypto-praos.cabal:54-60`). The fork at
`dbb48cce5429cb6585c9034f002568964f1ce567` has the same function and decoder, line for line, without the `cardano_`
prefix of the names. `oracle/build-and-run.sh` builds that fork as `../wycheproof-ed25519/README.md` describes (the
same archive, configure flags and image) and runs `oracle/proof_to_hash.c` on `cases.tsv` with no network. The run of
2026-11-25 built the library with SHA-256 `42ebd2c63d2857573162d455193b564984d5351e9f99e3c36627e18f57d1f6fb`, the
same as for the Wycheproof verdicts. It is a source build of the pinned revision, not the shipped node's binary.

To repeat it, mount the source archive at `/in`, `oracle/` at `/oracle`, a directory holding `cases.tsv` at `/io`
and an output directory at `/out`, and run `sh /oracle/build-and-run.sh` in a Debian bookworm image with gcc and
make, with no network. The output must equal `sodium-results.tsv`.

## Licence

The proofs are derived from cardano-base's test vectors, Apache-2.0 (`../licenses/cardano-base-LICENSE`,
`../licenses/cardano-base-NOTICE`). The results, the driver and the script are Scarab's own, under the repository's
Apache-2.0. The libsodium source (ISC) is not redistributed.
