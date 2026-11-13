# Delegation hashes: a hash of a delegating certificate is exactly 28 bytes (decoder version 9)

Status: Current. Regression for the November 2025 mainnet partition.

## The incident

At about 08:00 UTC on 21 November 2025 Cardano mainnet partitioned over "a malformed delegation
transaction" in which "an oversized hash" passed the checks of newer nodes; older nodes refused the
transaction, newer ones accepted it, and node 10.5.2 (11:00 UTC) and 10.5.3 (15:30 UTC) fixed it
(Intersect, [Incident report: network partition analysis and resolution strategy](https://intersectmbo.org/news/incident-report-network-partition-analysis-and-resolution-strategy),
21 November 2025). Pi Lanningham's after-action report names the field and the length: "a
delegation certificate to a pool identifier that was twice as big as it should be", which nodes
with the bug truncated to the expected length ("Instead of delegating to pool EASY1, it delegated
to EASY1EASY1"; [Poison Piggy - After Action Report](https://www.314pool.com/post/cardano-post-mortem-2),
22 November 2025). The Cardano Foundation's post-mortem ([November 2025 Incident: Cardano Shows Its
Resilience](https://cardanofoundation.org/blog/november-2025-cardano-shows-resilience), 4 December
2025) describes "a buggy transaction ... handled differently in different versions of a library"
and names no field or length. None of the three publishes the transaction's bytes, so this corpus
builds its own.

## The cases

The reference reads a stake credential's key or script hash, a pool id and a DRep's key or script
hash as a `Hash Blake2b_224`: `DecCBOR (Hash h a)` over `DecCBOR (PackedBytes n)`
(`libs/cardano-ledger-binary/src/Cardano/Ledger/Binary/Decoding/DecCBOR.hs:492-498`, cardano-ledger
`f649f9751074d2ab3de033fc3912f29c9862c1f5`), whose `packByteString` fails unless the string is
exactly the size ("Number of bytes mismatch", `cardano-crypto-class/src/Cardano/Crypto/PackedBytes/Internal.hs:379-406`,
cardano-base `060819b59c184b951a54e3c563304983c53a3eac`, released as cardano-crypto-class 2.5.1.0).
The Conway certificates that delegate are tag 2 (`shelleyTxCertDelegDecoder`,
`eras/shelley/impl/src/Cardano/Ledger/Shelley/TxCert.hs:490-506`) and tags 9 to 13
(`conwayTxCertDelegDecoder`, `eras/conway/impl/src/Cardano/Ledger/Conway/TxCert.hs:683-731`), read
by `DecCBOR (ConwayTxCert era)` (`:672-681`).

`cases.jsonl` holds a line per case, written by
`scripts/oracle/conway-decoding/delegation_hash_cases.py`: a whole Conway transaction
`[body, {}, true, null]` whose body holds one certificate of tag 2, 9, 10, 11, 12 or 13, with one of
its hashes 27 or 29 bytes long (`place`, `length`): the stake credential's key hash or script hash,
the pool id and the DRep's key hash or script hash, where the certificate has them. Each pool id is
also written 56 bytes long, a 28-byte id twice, the shape of the incident. Each certificate is also
written with every hash of 28 bytes, once with key hashes and once with script hashes (`hashes`):
the accepted controls. A line records the reference's verdict (`accepted` and the complete
`failure`) from the unchanged released decoder, `decodeFullAnnotator` of a Conway `Tx` at decoder
version 9; the counts are in `provenance.json`. Every refusal names `rule`, a fragment of the
reference failure, and `expect`, the Scarab error case.

`DelegationHashesReferenceSuite` (ledger) requires the same verdict of `Tx.fromEnvelope`, which reads
a transaction's parts with the decoders a block's transactions go through: an accepted transaction
holds the certificate it writes, and a refused one is `ByteLength` of the place's field
(`CertificateCredential`, `PoolId` or `DrepHash`), 28 and the length it has.
`DelegationHashesSubmissionSuite` (node) requires the mempool, through local and remote submission,
to refuse each refused line with that cause as `Malformed` before the ledger rules run, and not to
refuse an accepted line as malformed.

Scope: decoding only, at decoder version 9, the lowest of Conway. The reference's hash decoder reads
no protocol version (`DecCBOR.hs:492-498`), and the credential and DRep decoders change their form
only from version 12 (`libs/cardano-ledger-core/src/Cardano/Ledger/Credential.hs:327-334`,
`DRep.hs:90-99`), past Conway's 9 to 11, but the corpus records version 9 alone. No ledger rule, signature or script is run; the accepted controls
are not shown to pass the ledger. Hashes in other places of a transaction or a block (pool
registrations, outputs, witnesses, governance) are not in this corpus.

Regenerate with the [oracle recipe](../../../scripts/oracle/conway-decoding/README.md).
