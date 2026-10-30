### Fixed (mempool, #412)

- A transaction the mempool applies again (revalidation and block production) reports every predicate failure, in the reference's order, where it reported only the first.
