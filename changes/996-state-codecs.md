### Fixed (state codecs, #996)

- The ledger-state export and the node-to-client answers write values, as the reference's
  `EncCBOR` does, instead of the bytes a transaction arrived with: Conway parameter updates as
  definite maps of their field values in reference order, pool relays and metadata from their
  values, and governance actions from their values. A governance action outside what the
  reference's types hold (such as a hard-fork minor version beyond `Word32`) is refused instead
  of being truncated; the transaction writer's own rules are not applied to held state.
