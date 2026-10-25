### Fixed (Plutus script context, security)

- Translate the validity interval of a Plutus transaction through the era history at the ledger tip, as the reference does. From Babbage on, an upper bound beyond the forecast horizon (3k/f slots past the tip, rounded up to an epoch boundary, or the next era's safe zone where the transition is known) has no time: the transaction is refused with the reference's `CollectErrors` bytes and neither enters the mempool nor a forged block. Alonzo extends the history linearly from the block's slot, as the reference's Alonzo rules do, and is unchanged.
