### Fixed (refusals, #796)

- A refused transaction lists its failing key witnesses in the order the reference lists them, by each key's hash (`InvalidWitnessesUTXOW`), rather than the order the witness set gave them in.
- A refused vote lists the unelected committee voters once each, scripts before keys and then by hash, as the reference's set does (`UnelectedCommitteeVoters`), rather than in the order of the body's votes. Whether a transaction is accepted is unchanged; only the order of the items the refusal carries differs.
