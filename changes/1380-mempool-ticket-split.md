### Changed (Mempool, #1380)

- The transactions after a ticket are found by a binary search on the pool's ticket order, as the reference's `splitAfterTicketNo` finds them, not by reading every held transaction. Each peer that relays our transactions asked for them, and waited for new ones, with a full scan of the pool after every change, as did the certifying view's catch-up. Under testnet load, with a few thousand transactions held, that was about 13% of pool1's Java execution samples.
