### Added (ledger decoding)

- The decoders of an era's parts take an explicit decoder version, as cardano-ledger-binary's `decodeFull'` does: `TxBody.decode`, `Witnesses.decode`, `Tx.fromEnvelope`, `Tx.fromBlock`, `Certificate.decode` and `Header.fromRaw` take an optional `DecoderVersion`, and `Block.decode` and `Block.decodeShelley` one per era. They read at the version given, of any era, as `decodeFull'` does. `DecoderVersion.at(era, major)` builds one of an era's range, `ProtVerLow` to `ProtVerHigh` (Byron 0 to 1, Conway 9 to 11), and refuses any other with a typed error. Without one they read at the era's `eraProtVerLow`, as before.

### Fixed (ledger decoding)

- Explicit decoder versions select address parsing, multi-asset validation and cost-model rules independently of the era's value type, output forms and script languages. Alonzo-family witness sets refuse duplicate bootstrap witnesses, vkeys, native scripts and datums at version 12, and redeemers require the map form. Default era decoding and stored formats are unchanged.
