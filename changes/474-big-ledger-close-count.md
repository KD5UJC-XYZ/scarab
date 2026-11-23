### Changed (network, #474)

- Above the established target for big ledger peers, the peer governor closes as many warm big ledger
  peers as the reference does: the number it closes now also subtracts the ordinary peers being closed
  or activated, as the reference's `EstablishedPeers.aboveTargetBigLedgerPeers` does, so an ordinary
  close or activation in flight delays a big one. Before, only the big ledger peers in those
  transitions were subtracted, and the node could close more big ledger peers at once than the
  reference. The hot side is unchanged: it subtracts only the big ledger peers being deactivated, as
  the reference's does.
