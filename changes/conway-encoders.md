### Added (ledger encoding)

- Write the Conway values Scarab decodes and could not write as the reference ledger writes a decoded value (`ConwayCodecs`, cardano-ledger f649f975): certificates and the ordered set of a body's certificates, voters, voting procedures, the ordered set of proposal procedures, scripts, redeemers, vkey and bootstrap witnesses, transaction metadata, cost models and Shelley-based blocks. Values the reference keeps as the bytes it read (a native script, plutus data, a header, a block body) are written as those bytes. They are re-encodings for node-to-client answers, ledger-state export and the cbor-decoding conformance report; nothing is hashed or signed over them.
- A transaction metadatum decodes to a value (`Metadatum`), read by the same decoder that checks the metadata of auxiliary data, and a Shelley-based block keeps its body segments as it read them.

### Fixed (ledger encoding)

- A pool's relays are written as the reference writes a sequence: definite up to 23 relays and indefinite above (the registered relays of a genesis pool).
