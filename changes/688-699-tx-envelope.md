### Fixed (transaction envelopes, #688)

- A Shelley, Allegra or Mary transaction submitted as a non-empty indefinite list is refused with
  `IndefiniteLength`, as the reference's `RecD` record `ShelleyTx` refuses it; the definite list and
  the detached record are read as before.

### Fixed (transaction envelopes, #699)

- An Alonzo-to-Conway transaction submitted as `[body, witnesses, auxiliary_data]`, without its
  validity flag, is read as a valid transaction, as the reference reads it, by `Tx.fromEnvelope`,
  the mempool (local and node-to-node submission) and the forger; a boolean third item without
  auxiliary data after it, and a non-boolean third item of four, are refused.
- The mempool holds and relays every transaction as the reference relays it: a definite list of
  four with the flag written and the other parts' original bytes, whether it came without its flag
  or in an indefinite list.
