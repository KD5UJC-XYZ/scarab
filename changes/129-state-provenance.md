### Changed (state provenance, #129)

- Preserve each ledger state's completeness gaps and observed era starts through transitions,
rollback and V5 snapshot restore/rewrite, retaining historical non-myopic warnings. Track the
native consensus voting count and clear recoverable gaps only at proven replacement boundaries.
The V5 layout and fingerprint are unchanged; operational database upgrades remain separate.
An exhausted imported voting counter produces a typed refusal where the native Word32 successor
is partial, preserving failure without an uncaught exception or wrapped count.
