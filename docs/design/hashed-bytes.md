# Hashed and signed bytes

**Status: Current.** It records which bytes Scarab hashes or signs. The rule itself is in
[AGENTS.md](../../AGENTS.md#how-code-is-written-here) and [CONTRIBUTING.md](../../CONTRIBUTING.md#style-rules):
hash and sign exactly the bytes the reference hashes and signs.

## The default: the bytes as received

The reference hashes and signs the bytes it received. Scarab does the same, and keeps them beside every
decoded value ([ADR 0013](../adr/0013-cbor-documents-are-an-index-over-the-input.md)). This covers:
- block bodies;
- transaction bodies;
- witness sets;
- scripts and datums;
- auxiliary data;
- a header's hash.

A re-encoding of any of these would give another hash whenever the sender's encoding was not canonical.

## Where the reference hashes or signs a re-encoding

Each place where the reference encodes a value again before it hashes or signs it is mirrored at its Scarab site,
which cites the reference line. They are listed here so that a reader can find them:

- **The KES signature of a block header** covers the header body encoded again, while the header's hash
  is over the bytes received: `getSignableRepresentation` (cardano-ledger f649f975
  `libs/cardano-protocol/src/Cardano/Protocol/Praos/BlockHeader.hs:113-117`,
  `TPraos/BlockHeader.hs:170-171`; Scarab `HeaderBodyEncoding`).
- **A Byron address's root** is checked against a hash of the address's attributes encoded again
  (`checkAddrSpendingData`, cardano-ledger f649f975
  `eras/byron/ledger/impl/src/Cardano/Chain/Common/Address.hs:306-312`; Scarab
  `ByronAddress.canonicalAttributes` and `ByronAddress.rootFor`).

A new entry needs the reference read at its pinned version first. Making this list something the
compiler enforces, by giving received bytes and re-encodings different types, is tracked in
[#971](https://github.com/KD5UJC-XYZ/scarab/issues/971).
