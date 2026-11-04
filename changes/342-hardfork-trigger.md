### Fixed (consensus: hard-fork trigger, #342)
- **Every era-transition decision of a running chain applies the reference's rule.** An era with a version trigger is left at the next epoch
  only when the state's *definite* parameter update is to *exactly* the next era's first major version and at least `k`
  blocks followed the voting deadline (ouroboros-consensus 82ecba32 `ShelleyHFC.hs:220-227`). The block tick
  (`Hfc.tick`), the header envelope's expected era and the Genesis window's era summary now all ask the one rule,
  `HardForkConfig.transition`. Before, the tick and the envelope used `major >= v` on the adopted (or current) parameters
  with no stability-window check, while the Genesis window used the exact rule, so the paths could disagree.
- **A chain started from genesis enters only the eras configured at its first epoch** (#771), as
  `injectInitialExtLedgerState` does (ouroboros-consensus 82ecba32 `HardFork/Combinator/Embed/Nary.hs:259-306`):
  the Shelley genesis's protocol version no longer moves the starting era (`HardForkConfig.eraAt`, `major >= v`, is
  removed).
- **A restored snapshot that did not know its after-voting counter has it recounted** (#771): a state carrying
  `Gap.ShelleyTransition` gets the exact count of its epoch's blocks from the voting deadline to the anchor from the
  immutable store before replay, and the gap is closed; a store with no block before the deadline leaves it open.
