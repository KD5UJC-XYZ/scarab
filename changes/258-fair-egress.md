### Fixed (networking, #258)

- Fair Mux egress serves one complete SDU per active protocol/role turn, keeping control traffic responsive during bulk transfers while retaining per-channel byte order, bounded admission and connection-global flush fences.
- Each bearer write now has one SDU's deadline (30 seconds, or 10 for Handshake); a stalled first SDU no longer borrows the remaining message's write budgets.
