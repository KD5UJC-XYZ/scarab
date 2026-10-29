### Fixed (peer selection)
- **A failure while a ledger peer snapshot is verified against the immutable chain no longer stops the node.** As in
  the reference (`jobVerifyPeerSnapshot`, whose job handler answers no match for any failure), the verification
  answers no match (`ledgerPeers`, `snapshotVerified`, `matchesImmutablePoint` false) and the node goes on. When the
  store failed, an error line (`ledgerPeers`, `snapshotVerificationFailed`, with its `reason`) names the cause first;
  the reference has no such line, and this is an owner-approved deviation. Issue 586.
