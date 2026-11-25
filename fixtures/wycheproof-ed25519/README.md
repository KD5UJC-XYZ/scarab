<!-- SPDX-License-Identifier: Apache-2.0 -->
# Wycheproof Ed25519 vectors, with the reference's verdicts

The Wycheproof Ed25519 verification vectors, unchanged, and the verdict of the reference's verifier on each
of them. `WycheproofEd25519Suite` (crypto tests) runs all 210 cases through Scarab's `StrictEd25519`, the
JDK's Ed25519 and Bouncy Castle's.

| File | Contents |
|---|---|
| `ed25519_test.json` | C2SP/wycheproof `testvectors_v1/ed25519_test.json` at commit `a3d5d1423f2592d6646836a45a21a1b209da9f4d` (2026-10-23), byte for byte. SHA-256 `ea80ffebac085e21cb9d3e903e7fa2b448d96702da5b31f061d0b83b430b74b3`. 210 cases in 111 groups: 117 `valid`, 64 `invalid`, 29 `acceptable` (8 keys of small order, 21 keys of mixed order). |
| `sodium-results.tsv` | One row per case, no header: `tcId`, then `SignatureVerified`, `SignatureRejected`, or `NotRunMalformedLength` for the 12 cases whose signature is not 64 bytes, which the driver never passes to the library. SHA-256 is pinned in the suite. |
| `oracle/verify.c`, `oracle/build-and-run.sh` | The driver that produced `sodium-results.tsv`, and the script that builds the library and runs it. |
| `LICENSE` | Wycheproof's licence (Apache-2.0), from the same commit. |

`fixtures/witness/inputs/wycheproof-ed25519.json` is the earlier revision of the same file (151 cases, commit
`12fd3aaf33eb5fa1f52e026912ee00c054f9d984`), kept as it is in that frozen corpus. The 151 cases are all in this
file with the same expected results; the 59 added since are the keys of small and mixed order with their valid
controls, and one sodium_compat regression case.

## How the verdicts were produced

The reference verifies Ed25519 with libsodium's `crypto_sign_ed25519_verify_detached`
(cardano-base `060819b59c184b951a54e3c563304983c53a3eac`,
`cardano-crypto-class/src/Cardano/Crypto/DSIGN/Ed25519.hs:198-210`), from the Cardano fork IntersectMBO/libsodium
at `dbb48cce5429cb6585c9034f002568964f1ce567`, built without `ED25519_COMPAT`. `oracle/build-and-run.sh` builds
that revision from its source archive (`codeload.github.com/IntersectMBO/libsodium/tar.gz/dbb48cce…`, SHA-256
`e4f29ae3c16037e484bb69e3fa22a5565c42adf497f8f88e61ff8d9486ab863e`, the archive
`../witness/archive/pinned-source-oracle/provenance.json` records) with the configure flags and `CFLAGS` recorded
there, checks that `open.c` was compiled with `-UED25519_COMPAT`, and runs `oracle/verify.c` on the cases with no
network. The run of 2026-11-25 was in Debian bookworm (the base of `scripts/oracle/plutus/Dockerfile`) with gcc
12.2.0, glibc 2.36 and GNU Make 4.3, on a Linux 7.1.5 kernel; the library it built has SHA-256
`42ebd2c63d2857573162d455193b564984d5351e9f99e3c36627e18f57d1f6fb`. The archived oracle's library
(`76b41125…`) was built with gcc 14.2.0, GNU Make 4.4.1 and glibc 2.41 on a Linux 6.18.44 kernel
(`../witness/archive/pinned-source-oracle/provenance.json`), so the two binaries were not expected to be equal, and
no single cause of the difference is claimed. On the 151 cases of the earlier revision the verdicts of this build
equal the archived system-sodium and pinned-source verdicts of `../witness/status.tsv`, case for case.

To repeat it, extract the cases from `ed25519_test.json` as `tcId<TAB>pk<TAB>sig<TAB>msg` lines (the group's
`publicKey.pk`, empty fields kept) into `/io/cases.tsv`, mount the source archive at `/in`, `oracle/` at
`/oracle` and an output directory at `/out`, and run `sh /oracle/build-and-run.sh` in a Debian bookworm image
with gcc and make, with no network. The output must equal `sodium-results.tsv`.

This is a source build of the pinned revision, not the shipped node's binary, whose compiler and flags are not
established (as for `../witness`). The verdicts:

- every `valid` case verifies and every `invalid` case is rejected or not run: the reference agrees with
  Wycheproof wherever Wycheproof decides;
- the 8 `acceptable` keys of small order (cases 152 to 159) are rejected, by the small-order blacklist;
- the 21 `acceptable` keys of mixed order (cases 160 to 180) verify: the reference does not test the subgroup.

## Licence

The vectors are Wycheproof's, Apache-2.0 (`LICENSE`). The verdicts, the driver and the script are Scarab's own,
under the repository's Apache-2.0. The libsodium source (ISC) is neither vendored nor redistributed; only its
archive's digest is recorded.
