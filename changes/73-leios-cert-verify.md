### Added (Leios certificate checks, #73)
- **The checks the reference runs on a certifying ranking block before it applies the certified endorser block are
  mirrored as one pure function** (`LeiosCertification.check`; ouroboros-consensus 2416493b `Forker.applyBlock`): the
  parent's announcement, its hash, the committee of its tip (`leiosCommitteeOfTip`: the set snapshot's committee and the
  quorum parameter) and `verifyLeiosCert`, in that order, each failing with the reference's `LeiosExtValidationError`
  case. The Leios testnet's certifying block 97,276 passes them from its parent's state; nothing calls them yet, since
  the node applies no Dijkstra block.

### Fixed (Leios certificates, #73)
- **Votes and certificates sign the announcing block hash's CBOR encoding** (`58 20` and the 32 bytes), as the
  reference's `SignableRepresentation RbHash` does, not the 32 bytes alone, under which the testnet's certificate of
  block 97,276 did not verify.
- **A certificate is judged as cardano-base's `verifyLeiosCert` judges it**: bits of the signer bitfield at or beyond
  the committee size are ignored instead of refused, a certificate with no signer fails on its weight (or its
  signature when nothing is required) instead of as a case of its own, weights are exact fractions of the stake, and the
  verdicts are the reference's five `VerificationError` cases.
