### Fixed (consensus: hard-fork trigger, #342)
- **Every era-transition decision of a running chain applies the reference's rule.** An era with a version trigger is left at the next epoch
  only when the state's *definite* parameter update is to *exactly* the next era's first major version and at least `k`
  blocks followed the voting deadline (ouroboros-consensus 82ecba32 `ShelleyHFC.hs:220-227`). The block tick
  (`Hfc.tick`), the header envelope's expected era and the Genesis window's era summary now all ask the one rule,
  `HardForkConfig.transition`. Before, the tick and the envelope used `major >= v` on the adopted (or current) parameters
  with no stability-window check, while the Genesis window used the exact rule, so the paths could disagree.
  The era a genesis or imported state starts in is still the stateless walk `HardForkConfig.eraAt`.
