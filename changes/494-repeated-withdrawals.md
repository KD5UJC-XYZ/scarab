### Fixed (ledger, #494)
- **A withdrawal account named twice in a Shelley to Babbage body counts once, with its last amount.** Below decoder version
  9 the reference reads the withdrawals with `Map.fromList`, so a repeated reward account keeps the last amount written; the
  body decoder kept every entry, and the UTXO rule added each one to the consumed value. A transaction that took one account's
  full balance twice was accepted, where the reference refuses it with `ValueNotConserved`. Conway still refuses the repeat at
  decoding, and the body's original bytes and id are unchanged.
  The decoded withdrawals field and native ledger entry points now use a map, so constructing or copying a transaction
  cannot reintroduce duplicate accounts. Consumers retain the reference's explicit account ordering.
  V1/V2 script contexts also mirror the reference's credential-keyed map when different network accounts share a credential;
  V3 retains its distinct list translation.
