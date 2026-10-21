### Fixed (mempool lifecycle, #40)

- Validate and revalidate transactions against the complete ledger ticked to the next slot, including epoch-boundary parameters, rewards, certificate state and treasury.
- Request re-offered transactions after rejection, adoption or rollback invalidation, matching the reference's current-mempool membership check. Revalidate retained transactions in order; transactions from rolled-back blocks return only when re-offered.
