# Praos header cryptography conformance adapter

`lab.header.PraosHeaderConformance.inspect` composes the existing experimental OpCert and Sum6 KES predicates for a standalone ten-field Praos header. It is an offline fixture adapter, **not a new header validation profile**. Existing protocol profiles are unchanged. It returns individual signature results and always reports `consensusValidated = false`; a `Right` result can contain rejected signatures.

## Original bytes and signing bytes

The adapter binds the entire original header to the caller's expected Blake2b-256 hash. It extracts the issuer key, VRF key, OpCert hot key, counter, start period, signatures, slot and version from that same owned CBOR tree. No independently supplied signing message or key is accepted.

The reference's `Header` preserves memoized bytes for its hash, but `HeaderBody` signing calls `serialize' (pvMajor (hbProtVer hb)) hb`. These are different contracts. The adapter checks that the original body equals this project's shortest-width, definite-container encoding of its supported scalar/array shape, then checks KES on that encoding. Nonmatching body encodings return an unsupported-serialization error; this does not assert that the reference rejects them. Outer-header encoding changes alter the header hash without altering the KES message. This restriction is a useful fixture boundary, not proof of all version-dependent reference serialization rules.

OpCert signing bytes are `rawHotKey32 || counterBE8 || startPeriodBE8`. Verification uses the header issuer key. KES verification uses that certificate's hot key and period `floor(slot / slotsPerKesPeriod) - startPeriod`. The start is inclusive and the supplied lifetime end exclusive. BigInt arithmetic avoids wraparound; the local Sum6 lifetime is restricted to 1..64. Body size is bounded to Word32; other parsed unsigned integers have a Word64 ceiling. CBOR allocation is capped at 64 KiB, depth 8 and 64 items.

The expected major/minor pair is a fixture assertion, not an admission whitelist. In particular, observing 11.2 does not identify the ledger protocol version or authorize a consensus transition.

## Supplied context is not trusted state

The context carries a 32-byte genesis identifier and explicitly supplied timing parameters. The identifier is retained in the observation; this API does **not** parse genesis JSON or prove that the parameters belong to that hash. The caller must establish this association. `genesis-parameter-provenance` remains unchecked even when the fixture harness extracted both from one retained snapshot.

Optional registration binds Blake2b-224 of the header cold key to the supplied pool ID and Blake2b-256 of the header VRF key to the supplied VRF key hash. Missing registration is explicitly `NotSupplied`. Matching registration is only `MatchedSuppliedRegistration`: current ledger registration and stake-distribution membership are not inferred. Public tests derive registration from each pinned fixture and therefore test binding mechanics, not registration provenance.

VRF proof verification, stake, epoch nonce, counter state, leadership, chain continuity, block body commitments and ledger transitions remain unchecked. The existing Praos VRF predicate is deliberately not called with an invented epoch nonce. No counter rule is approximated from the certificate alone.

## Source evidence and exact-reference gap

The retained reference identifies node 11.1.3, CLI 11.2.3.0 and testnet 11.1.1, with Conway ledger protocol 9.0 and header advertisement 11.2. Binary SHA256 for node is `ee396604345cc07c7169391ef3f5364886d4b7fe0d539aff6919b470e7a336c3`.

* [Node commit 938cba990357ae7c4b7f95c8f75dd9d31174bbeb](https://github.com/IntersectMBO/cardano-node/tree/938cba990357ae7c4b7f95c8f75dd9d31174bbeb) declares `ouroboros-consensus ^>= 4.2.0.1` and CHaP index state 2026-09-23T18:57:23Z. A version range is not an exact runtime build plan.
* [CHaP ouroboros-consensus 4.2.0.1](https://chap.intersectmbo.org/package/ouroboros-consensus-4.2.0.1.tar.gz), archive SHA256 `a645670ccbb25179c96a10c8082f5bfb84660fbab14a255193c989359cd34501`, contains `Ouroboros/Consensus/Protocol/Praos.hs` with SHA256 `b770f0c73f2c34dd69146f2b087a786d7d937d119f0efb961ff6757105119e23`. This matches the project's already-pinned consensus source at commit `82ecba329d7d054340bf707d44fe6e9ac27cec40`. `doValidateKESSignature` specifies timing, OpCert verification, KES verification and counter state checks. Counter acceptance requires current issue number `m <= n <= m + 1`, with initial zero only when the key is in the stake distribution.
* [Pinned ledger Praos BlockHeader.hs](https://github.com/IntersectMBO/cardano-ledger/blob/f649f9751074d2ab3de033fc3912f29c9862c1f5/libs/cardano-protocol/src/Cardano/Protocol/Praos/BlockHeader.hs), SHA256 `0a08f975179dd1e8919052d14014b359e3653d625faa8ad7c4d8f84f88e983d8`, specifies the ten fields, Word32 body size, memoized header hashing and version-selected body reserialization.
* [Pinned ledger OCert.hs](https://github.com/IntersectMBO/cardano-ledger/blob/f649f9751074d2ab3de033fc3912f29c9862c1f5/libs/cardano-protocol/src/Cardano/Protocol/TPraos/OCert.hs), SHA256 `4aabd7fff56b33f74d648088eda1e87b4a9570914d4075a0c698d270ab56925c`, specifies the raw 48-byte OpCert signing message. Existing Sum6 source provenance remains in `fixtures/sum6/evidence/sources.json`.

Missing for an exact-reference validator: a binary-linked resolved dependency plan establishing the exact cardano-protocol/cardano-base/serialization revisions; version-11 serializer/decoder conformance over alternative encodings; and malformed/adversarial Ed25519/KES differential results against that precise binary's crypto backend. Existing strict Ed25519/Sum6 predicates remain experimental. Successful real signatures do not establish acceptance-set parity. These gaps are why this increment supplies an adapter rather than widening a validator's version whitelist.

## Validation

The public suite reuses four already-cleared Amaru headers, checks their existing SHA256 pins, and tests both predicates, original-byte binding, registration failures, signature mutations, unsupported body encodings, outer encoding preservation, lifetime bounds, malformed inputs and allocation bounds. It adds no corpus or keys.

Run `core/testOnly lab.header.PraosHeaderConformanceSuite` from the repository root through the project's usual sbt launcher. For constrained Docker runs, invoke the launcher JAR with `-XX:ActiveProcessorCount=2 -Xmx1200m`, a separate dependency cache and `--cpus=2 --memory=2g --memory-swap=2g`.

Private retained observations are separate from public tests and never required for public CI. Their source and raw bytes are not included in this commit. Review reports reside outside the repository under `/home/<user>/cardano-header-research-20261008`.
