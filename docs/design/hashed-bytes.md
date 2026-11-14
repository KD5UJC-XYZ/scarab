# Hashed and signed bytes

**Status: Current.** It records which bytes Scarab hashes or signs. The rule itself is in
[AGENTS.md](../../AGENTS.md#how-code-is-written-here) and [CONTRIBUTING.md](../../CONTRIBUTING.md#style-rules):
hash and sign exactly the bytes the reference hashes and signs.

## The default: the bytes as received

Except where the next section says otherwise, the reference hashes and signs the bytes it received. Scarab
does the same, and keeps them beside every decoded value
([ADR 0013](../adr/0013-cbor-documents-are-an-index-over-the-input.md)). This covers:
- block bodies;
- transaction bodies, from Shelley on (a Byron transaction's id is a re-encoding, below);
- witness sets;
- scripts and datums;
- auxiliary data;
- a header's hash.

A re-encoding of any of these would give another hash whenever the sender's encoding was not canonical.

## Where the reference hashes or signs a re-encoding

These are the places known to Scarab where the reference encodes a value again before it hashes or signs it.
The rule requires Scarab to mirror each one at its site, citing the reference line; an entry says when Scarab
does not yet.

- **The KES signature of a Praos or TPraos block header** (Shelley to Conway) covers the header body
  encoded again, while the header's hash is over the bytes received: `getSignableRepresentation`
  (cardano-ledger f649f975 `libs/cardano-protocol/src/Cardano/Protocol/Praos/BlockHeader.hs:113-117`,
  `TPraos/BlockHeader.hs:170-171`; Scarab `HeaderBodyEncoding`). This entry does not cover Scarab's Leios
  header, which is signed over its body's bytes as received (`LeiosHeader.signable`).
- **A Byron address's root** is checked against a hash of the address's attributes encoded again
  (`checkAddrSpendingData`, cardano-ledger f649f975
  `eras/byron/ledger/impl/src/Cardano/Chain/Common/Address.hs:306-313`, hashed by `addressHash` in
  `Common/AddressHash.hs:16-25`; Scarab `ByronAddress.canonicalAttributes` and `ByronAddress.rootFor`).
- **A Byron transaction's id**, which keys the outputs it creates, is the hash of the transaction encoded
  again (`txOutputUTxO`, cardano-ledger f649f975 `eras/byron/ledger/impl/src/Cardano/Chain/UTxO/UTxO.hs:176-188`,
  the encoding at `UTxO/Tx.hs:102-108`), while its witnesses sign the hash of the bytes received
  (`recoverSigData`, `UTxO/TxWitness.hs:136-140`). Scarab hashes the bytes received for both: not yet
  mirrored, tracked in [#966](https://github.com/KD5UJC-XYZ/scarab/issues/966).

A new entry needs the reference read at its pinned version first. Making this list something the
compiler enforces, by giving received bytes and re-encodings different types, is tracked in
[#971](https://github.com/KD5UJC-XYZ/scarab/issues/971).
