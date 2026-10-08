# Experimental strict witness verification: evidence and limits

## Scope

The JVM verifier is a public-input Ed25519 predicate targeting the pinned
Cardano sodium source's non-`ED25519_COMPAT` acceptance profile. Its evidence is
2,219 preserved public vectors, including eight witnesses in genuine archived
Haskell-ledger transaction sequences. It is **experimental**: these observations
do not establish exact Cardano release-binary equivalence, a security audit, or
complete witness/transaction validation.

Signature success alone does not establish required signer presence, key-hash or
address authorization, bootstrap witness validity, script satisfaction, native or
Plutus witness rules, certificate authorization, ledger transitions or consensus
compatibility. There is no signing or key-generation API in this slice.

## Reproducible public corpus

`fixtures/witness/manifest.json` records exact pins, counts, schemas, source
expectations, archive results, licenses, output digests and remaining gates.

| Family | Rows | Expected-status provenance |
| --- | ---: | --- |
| Cardano sodium known answers | 1,024 | Public key/signature/message columns of pinned upstream test table |
| Sodium S+L | 1,024 | Exact upstream `add_l` mutation, rejection under non-COMPAT assertion |
| ed25519-speccheck | 12 | Published adversarial inputs; target-specific archived observation, no universal verdict |
| Wycheproof | 151 | Upstream valid/invalid result, flags and comments retained |
| Accepted-ledger setup witnesses | 3 | Positive witnesses in genuine accepted Haskell transactions |
| Rejected-ledger transaction witnesses | 5 | Public native/JVM differential observations, not separately stored Haskell signature goldens |

All 2,219 original IDs and input bytes are retained. The exact `vectors.tsv`
SHA256 remains
`f79f0a87efbd99ebe159c18211497f6b0d282e7946f1839216dc31bbf99ef918`.
The projection yields 1,121 `SignatureVerified`, 1,086 `SignatureRejected`, and
12 `MalformedSignatureLength` expectations. The latter are Wycheproof cases
30–41, handled before cryptography; native verification was **not run** for
those cases, rather than returning false.

`status.tsv` separates `source_expected`, expectation kind, intended typed result,
system sodium observation, BC raw observation and archived strict observation.
For fixed sizes the typed expectation is explicitly derived from the independent
archived native observation, with source assertions checked where available;
running the new candidate cannot rewrite the oracle expectations. Speccheck and
the five ledger-rejected-transaction signatures have `source_expected=unknown`.

`ledger-witnesses.json` preserves each full original transaction, exact original
body CBOR slice, BLAKE2b-256 digest, public key, signature, source event and witness
index, source ledger boolean, and zero-based half-open offsets in both source
sequence and transaction payload. `ledger-witnesses.tsv` is the same eight
rows in a dependency-free test format: ID, transaction hex, witness index, body
hex, body hash. Its SHA256 is
`eb8a8ae0c0731ef24e03789dcc26c314cdada4298a6555aa69f484ff544c2d3f`.

All eight signatures pass the archived native, raw BC and strict observations,
including the five in ledger-rejected transactions. A failed value-conservation
predicate must never be reused as a negative signature expectation. The signed
message is the **32 raw BLAKE2b-256 bytes of the exact original body CBOR**. It is
plain Ed25519, not Ed25519ph, body CBOR itself, a CBOR-encoded hash, or a
domain-prefixed hash. Equivalent-value body re-encoding changes the signed bytes.

## Offline and optional source reproduction

The normal check needs Python 3.9+ and its standard library, works from any
working directory, and requires no network or cryptographic/native library:

```sh
python3 scripts/project-witness-fixtures.py --check
```

Without `--check`, the same script regenerates `vectors.tsv`, `status.tsv`,
`projection.json`, `ledger-witnesses.json`, `ledger-witnesses.tsv` and
`SHA256SUMS`. Input and archive hashes are immutable constants and validated
before interpretation, with bounded reads and bounded CBOR nesting/item counts.
Fixed derived output hashes are checked before writing. Checking archives is not
a claim to have rerun their cryptographic oracles.

The source sodium test table contains published secret seeds. It is deliberately
**not vendored**. Instead `inputs/sodium-public.json` contains only source index,
public key, signature and message, with SHA256
`f87e83f0f22dc39b89cd2657f270f381ad629c6ab06459294ed9a8a466ea4653`.
The other vendored vector inputs contain public information only. Complete
license texts and notices are retained.

To independently reproject the sodium inputs, obtain the immutable source as a
separate optional operation and keep it outside the deliverable:

```sh
curl --fail --location --proto '=https' --tlsv1.2 \
  'https://raw.githubusercontent.com/IntersectMBO/libsodium/dbb48cce5429cb6585c9034f002568964f1ce567/test/default/sign.c' \
  --output /tmp/cardano-sodium-sign.c
python3 scripts/project-witness-fixtures.py --check \
  --sodium-source /tmp/cardano-sodium-sign.c
```

No fetching occurs in the projector. It bounds the original file at 3,000,000
bytes and **must match SHA256
`fa3f37d292782694d721a218d0adf2b7de633552d41bbdc1d0ae0a708e32a21d`
before decoding/parsing it**. It discards the seed field, never executes source
or signing code, and validates the public projection's immutable digest. The
original source is not required for default offline reproduction. Do not include
it in the fixture directory or distribution.

## Exact source and artifact pins

- [Cardano node 11.1.3](https://github.com/IntersectMBO/cardano-node/releases/tag/11.1.3):
  commit `938cba990357ae7c4b7f95c8f75dd9d31174bbeb`. Preserved release metadata
  identifies `cardano-crypto-class` 2.5.1.0 at cardano-base
  `060819b59c184b951a54e3c563304983c53a3eac`. The preserved exact node flake lock
  pins sodium `dbb48cce5429cb6585c9034f002568964f1ce567`.
- [Release Ed25519 source](https://github.com/IntersectMBO/cardano-base/blob/060819b59c184b951a54e3c563304983c53a3eac/cardano-crypto-class/src/Cardano/Crypto/DSIGN/Ed25519.hs)
  SHA256 `e1d3d42f5c4e77e2ab0cbad58462f47cff6e4c355af920c606d057e32d6b3c72`
  is byte-identical to the earlier inspected base revision
  `c1e1ed8d7ae39c111c73fc5f59ac37ce5933b83c`. The exact release DSIGN class was
  also inspected. This closes that source mismatch, not binary provenance.
- Cardano ledger source `f649f9751074d2ab3de033fc3912f29c9862c1f5`:
  `Utxow.hs` passes the annotated body hash to `verifyWitVKey`; `State/UTxO.hs`
  calls `verifySignedDSIGN`, and `Keys/Internal.hs` chooses Ed25519. Exact file
  hashes and paths are recorded in the manifest.
- [Pinned sodium verifier](https://github.com/IntersectMBO/libsodium/blob/dbb48cce5429cb6585c9034f002568964f1ce567/src/libsodium/crypto_sign/ed25519/ref10/open.c)
  SHA256 `2b345bf78695844262832eb14d0655da3a815024db0d437791142ee3b34b062d`
  is vendored as public verification source. Its bytes equal official
  1.0.18-RELEASE `open.c`, also preserved. ISC license is retained.
- [ed25519-speccheck](https://github.com/novifinancial/ed25519-speccheck/blob/65519336fda78a3d016e947df6d82848aca0c9da/cases.json):
  commit `65519336fda78a3d016e947df6d82848aca0c9da`, cases SHA256
  `08e47a36d9aead288664930505584f353fff113ab854f2800db1e4f5b3540450`.
- [Wycheproof](https://github.com/C2SP/wycheproof/blob/12fd3aaf33eb5fa1f52e026912ee00c054f9d984/testvectors_v1/ed25519_test.json):
  commit `12fd3aaf33eb5fa1f52e026912ee00c054f9d984`, cases SHA256
  `752d2ea7d7c6cf4736381b6cbacb61f8182b126ab7cd9b058f00c50084975536`.
- Ledger inputs retain the Amaru
  `34a453005bcaaf837ee73bd996b99eab8ef92961` → Blueprint
  `1b230262024a992cfb9e9bc47970fd3fafca8dac` → Haskell generator
  `dc8494b30741824fcd2ff90d8b4755bfdc10b03d` chain already documented in
  `fixtures/ledger/provenance.json`. Full original sequences are copied
  byte-identically here. These are archived Conway PV9 sequences, not freshly
  generated node-11.1.3 fixtures; original Haskell tests were not rerun.

### JVM dependencies and source correspondence

`com.weavechain:curve25519-elisabeth:0.1.3` is the tested arithmetic artifact:

- [Binary](https://repo.maven.apache.org/maven2/com/weavechain/curve25519-elisabeth/0.1.3/curve25519-elisabeth-0.1.3.jar)
  SHA256 `256048db6904c00832ab6045c624c69844d7617f719e3cd446691257aab8ffcb`
- [Published sources](https://repo.maven.apache.org/maven2/com/weavechain/curve25519-elisabeth/0.1.3/curve25519-elisabeth-0.1.3-sources.jar)
  SHA256 `6259b983a6368c4a39eb39f3db1e78193ba9b82da5d7ec6ea42ccc5514328dda`

Published artifact sources differ materially from the earlier git-head audit pin
`51d65293890efc56293839bfe376c4000e47526a`, including EdwardsPoint and Scalar.
The old git audit is not evidence for the tested jar. No reproducible binary build
has been established. The full artifact license is retained, including MIT,
CC0/eddsa-java and BSD-3-Clause/curve25519-dalek notices.

`org.bouncycastle:bcprov-jdk18on:1.85.2` was the direct comparison:

- Binary SHA256 `986b0fb92ec10e0c66b43e036ce0077e6150cfaecd1db9fb92b56672e157afe5`
- Source artifact SHA256 `b37ac84b1d5435ab7b8d166c16ab9f75e09f68f8ec50479bae433939b241b03f`

BC's raw Ed25519 verifier uses a cofactored relation. It accepts zero-based
speccheck cases 2, 4 and 5 that system sodium and the strict verifier reject.
This is a target-specific acceptance difference, not a blanket BC vulnerability.
A `validatePublicKeyFull` prime-subgroup filter is not a compatibility fix:
speccheck case 3 has mixed-order points and is accepted by all three. The
historical speccheck `results.md` BC row is not the BC 1.85.2 observation here.

## Exact-source native oracle and remaining release gap

A separate isolated run now built the official sodium fork at
`dbb48cce5429cb6585c9034f002568964f1ce567` with **ED25519_COMPAT undefined**.
It agrees with the archived system sodium and strict prototype on all **2,207**
fixed-size rows; all eight ledger signatures pass and all 12 malformed lengths
were excluded before native invocation. BC still differs exactly on speccheck
2/4/5, and mixed-order case 3 remains accepted.

Public results, summary, build/source provenance, source-file hashes, historical
runner and compile/preprocessor evidence are preserved separately in
`fixtures/witness/archive/pinned-source-oracle/`. The immutable vector and
historical `status.tsv` hashes have not changed. The offline projector validates
this additional archive against every original row without loading native code.

- Official source archive SHA256:
  `e4f29ae3c16037e484bb69e3fa22a5565c42adf497f8f88e61ff8d9486ab863e`
- 611 source files matched the archive after the build; upstream source was
  unchanged.
- Compiler: GCC Debian 14.2.0-19, x86_64-linux-gnu. Requested CFLAGS:
  `-O2 -g0 -UED25519_COMPAT`. Complete effective flags/DEFS are archived.
- Generated preprocessing confirms the non-COMPAT canonical-S/small-order branch.
- Locally built library SHA256:
  `76b41125c867878a1b0cfe64dde580872f61ca4cc6fae06223e902e3e803b24f`

The native binary, source archive and original secret-bearing upstream test
source are **not vendored** here. The historical native runner depends on the
separately retained research workspace and is not portable standalone execution
from the fixture copy. Only the standard-library projector is the portable
reproduction path. That checks projection and evidence integrity, not a new
native execution or reproducible native rebuild.

This closes the unexecuted exact-source oracle portion of the earlier gap. It
**does not establish official shipped-node compiler/build flags, native linkage
or release-binary equivalence**. No cross-platform byte-identical native build is
claimed, and no native dependency enters the JVM runtime.

### Historical system observation and remaining review

The archived native run used preinstalled
`/lib/x86_64-linux-gnu/libsodium.so.23`, reporting 1.0.18, SHA256
`85e2e494494f4f50c4b6d2b4ae90e885da1bfdefd52c4cc5767fd10abff62505`.
Package-manager/source-build provenance was not established. It is a recognized
system oracle, **not established as the Cardano fork or a reproducible build of
any source commit**. Only version lookup and detached public verification were
used; no signing, key derivation, key generation, native build or node execution
was performed by this fixture projection.

The pinned fork and official 1.0.18 `open.c` byte equality strengthens source
reasoning but proves nothing about all helpers, the complete installed library,
compiler flags or release linkage. Exact target build flags, including disabled
`ED25519_COMPAT`, and native source/binary correspondence must still be established
for the official shipped release before its equivalence is claimed. The local
exact-source oracle has its own established non-COMPAT flags and artifact hash.

The intended strict predicate uses canonical S, canonical point round trips,
small-order rejection, and the uncofactored equation `[S]B - [h]A == R`. It must
not impose prime-subgroup-only membership or cofactor-clear the equation. The new
composition around a less-established arithmetic dependency still needs review;
2,207 differential fixed-size observations are not an audit or exhaustive proof.
Historical archive runners preserve their original behavior and are excluded
from the project build; in particular their broad prototype exception catch is
not a suitable runtime provider-error contract.

Runtime verification remains JVM-only. Native oracle archives are research/test
provenance, not native runtime dependencies. Locally mutated bodies, hashes,
keys/signatures, malformed containers, provider-failure tests and parser-scope
checks belong to separately labeled local regressions, not upstream goldens.
