# MIR amounts: a `DeltaCoin` is an `Integer` (decoder versions 2 to 7)

Status: Current. Evidence for [#707](https://github.com/KD5UJC-XYZ/scarab/issues/707).

A move-instantaneous-rewards certificate that pays stake credentials holds a map from credential to
`DeltaCoin` (`StakeAddressesMIR`, `eras/shelley/impl/src/Cardano/Ledger/Shelley/TxCert.hs:341-353`,
cardano-ledger `f649f9751074d2ab3de033fc3912f29c9862c1f5`). `DeltaCoin` is a newtype over `Integer`
and derives its decoder (`libs/cardano-ledger-core/src/Cardano/Ledger/Coin.hs:115-119`), so an
amount is read by cborg's `decodeInteger`: an unsigned or negative integer item of any head width,
or a bignum, the one-byte tag head `0xc2` or `0xc3` over a definite byte string.

`cases.jsonl` holds a line per case, written by `scripts/oracle/conway-decoding/mir_amount_cases.py`:
a body of each era from Shelley to Babbage whose MIR certificate writes its amount as an unsigned
and a negative integer (also with an eight-byte head), as a bignum of each sign within and beyond 64
bits, with leading zeros and empty, and in the forms `decodeInteger` refuses (a widened tag head
`0xd8 0x02`, a bignum over a chunked string, text). The verdict (`accepted` and the complete
`failure`) is what the unchanged released decoders did with each: `decodeFullAnnotator` at the
era's decoder version; the counts are in `provenance.json`. Every refusal names `rule`, a fragment
of the reference failure, and `expect`, the Scarab error case.

`MirAmountsReferenceSuite` requires the same verdict from `TxBody.decode`, the original bytes for an
acceptance, and that a bignum amount is the integer it encodes.

Scope: wire decoding only; no ledger rule, signature or script is run.

Regenerate with the [oracle recipe](../../../scripts/oracle/conway-decoding/README.md).
