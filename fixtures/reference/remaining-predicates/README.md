# Pool metadata and early-era bootstrap predicates

Synthetic public inputs measured against the unchanged released ledger libraries matching
cardano-node 11.1.3 on 2026-10-20. See the [offline oracle recipe](../../../scripts/oracle/remaining-predicates/README.md)
and [provenance](provenance.json).

`pool.json` contains 43 native POOL transitions: 32 acceptances and 11 rejections. It covers
protocol versions 2.0 through 11.0, the exact 4.0/4.1 soft fork, 0/31/32/33-byte hashes,
a 64-byte URL with a valid 32-byte hash, absent metadata, rejected re-registrations with
33/64/256-byte hashes, and simultaneous network/metadata/cost violations. Accepted cases retain
complete native PState bytes; refusals retain every native predicate failure and its CBOR.
The generic Conway POOL instance exercises the shared Shelley rule under each protocol version;
this does not claim complete early-era transaction admission through a Conway ledger.
Native `applySTS` prepends failures, so its recorded list is reverse predicate execution order.
`RemainingPredicateReferenceSuite` judges POOL as the root of an accumulating judgment and compares
the complete list, every failure and its order, byte for byte (#412); the two rejections with
several failures (`11-metadata-before-cost`, `11-network-before-metadata`) check that order.

`outputs.json` contains 36 original Shelley/Allegra/Mary bodies. Two actual native predicate APIs
run per body: 72 calls across unknown and derivation-path attributes with 0/64/65-byte payloads,
and bootstrap mainnet/testnet addresses. The Scala reference suite isolates each predicate by
using a matching network when measuring attributes, then executes combined production
validation with the original network. Where both captured native predicates fail, the expected
first failure follows reference source order (network before attributes). It compares typed
verdicts and complete emitted UTXO failure bytes. Signed Scala
LEDGER regressions additionally verify acceptance, refusal, conservation and ordering. These
captures do not claim complete native UTXO or LEDGER admission, or identical failure accumulation.

`metadata-bodies.hex` contains decoder-only transaction-body seeds with 32- and 33-byte metadata
hashes. Both are valid decoded certificates; POOL enforces the hash-size rule at admission.
Every era's transaction-body fuzz target includes these seeds, and a deterministic guard checks
that they decode and reach the metadata branch. They do not claim valid balance or signatures.

The executed released `Shelley/Rules/Pool.hs`, `Shelley/SoftForks.hs`, `Shelley/Rules/Utxo.hs`,
`Allegra/Rules/Utxo.hs` and `State/StakePool.hs` match supported ledger pin f649f975 byte for byte.
Pool metadata encoding remains the original two-field term; there is no persisted-format change.
