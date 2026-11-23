### Changed (era types, #85)

- The mempool judges Conway transactions with Conway's era rules (MEMPOOL on the era types), for local
  submission and TxSubmission2 alike, in place of the flat rules; the forger still selects with the flat rules.
- A refused Conway submission is answered with the reference's bytes for the era's failures: GOV's vote and
  withdrawal failures in key order; under 11.2 and the Leios build, below protocol version 11, a withdrawal refused
  at LEDGER rather than CERTS; amounts and units past 64 bits in full, `WrongNetwork` addresses in the
  reference's order, and a pool metadata hash of another length than 32 bytes written as the reference writes it.
- Under 11.2 and the Leios build a held Conway transaction is validated again in full when the major protocol
  version changes, as their `reapplyValidatedTx` does.
- A submission whose stored output, proposals record or proposal count the era rules cannot handle, or whose two
  readings by the node disagree, gets no reply and costs the peer nothing; parameters in force that do not read
  into Conway's leave the mempool without a ledger.
