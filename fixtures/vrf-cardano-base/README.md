<!-- SPDX-License-Identifier: Apache-2.0 -->
# The reference's ECVRF draft-03 test vectors

The seven `vrf_ver03_*` test vectors of IntersectMBO/cardano-base at
`060819b59c184b951a54e3c563304983c53a3eac` (the cardano-base of cardano-node 11.1.3), from
`cardano-crypto-praos/test_vectors/`, byte for byte. The reference's own test,
`checkVer03TestVector` (`cardano-crypto-praos/testlib/Test/Crypto/VRF.hs:112-136`), reads each one and checks
that proving `alpha` with `sk || pk` gives `pi`, that the key of `sk` is `pk`, that the output of `pi` is `beta`,
and that verifying `pi` with `pk` gives `beta`. `Draft03CardanoBaseVectorSuite` (crypto tests) makes the same
checks of `Draft03Prover` and `StrictDraft03`. The output of `pi` alone, which the reference takes from an unverified
proof (`outputFromProof`), is checked with `ProofToOutput`, a mirror of the reference's proof-to-hash in test scope
only (the node exposes no such operation), itself checked against the native function in `../vrf-proof-to-hash`.

| File | SHA-256 | Key | alpha |
|---|---|---|---|
| `vrf_ver03_generated_1` | `6c4f8a82c453acda114a3a3887c61fee7c2da223c6518ffb1618a34b614089c1` | the all-zero seed | `00` |
| `vrf_ver03_generated_2` | `d15875d0598b4ecc962c966e272985a1e7113add367aa87c1ffbad8ccd2bef49` | the all-zero seed | `00010203040506070809` |
| `vrf_ver03_generated_3` | `10e371f743bc78fdc344cc7b631335c9fa4bccc290143ccf720155b9f6ee1a5a` | seed `a70b8f60…` | `00` |
| `vrf_ver03_generated_4` | `c8ea137dfe7a730a2fa83458d3efaf2faf6a394e888d38d5138c2c36a4af6252` | seed `a70b8f60…` | `00010203040506070809` |
| `vrf_ver03_standard_10` | `f21491dab4462e9146da3995991c13c04ac26ce0a5f6fbff1597cba97d41f814` | RFC 8032 test 1 | empty |
| `vrf_ver03_standard_11` | `2723c93fef149961f05e02516c3032453bb2852a9a2f2284e93eda6e112f2c9b` | RFC 8032 test 2 | `72` |
| `vrf_ver03_standard_12` | `b3875a5c5ea570b87beb4e84690455845b4c00d29e074bb004d93811f5719ae7` | RFC 8032 test 3 | `af82` |

The three `standard` vectors are the examples of draft-irtf-cfrg-vrf-03 for ECVRF-ED25519-SHA512-Elligator2; the
`generated` ones are cardano-base's. The seeds are published test keys (the all-zero seed, RFC 8032's), not
keys of any pool.

The files are Apache-2.0, the licence of cardano-base: `../licenses/cardano-base-LICENSE` and
`../licenses/cardano-base-NOTICE`, identical to the LICENSE and NOTICE of that revision.
