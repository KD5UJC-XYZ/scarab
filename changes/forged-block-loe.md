### Fixed (Ouroboros Genesis: forged blocks and the Limit on Eagerness)
- **A block the node forges goes through the Limit on Eagerness, as a block from a peer does.** In Genesis mode,
  before the node is caught up, chain selection takes a forged block only within `k` blocks of the Limit on
  Eagerness fragment, as the reference's chain selection takes every block it is given. A forged block past it is
  not adopted and is logged `didntAdoptBlock` and `lost`, as the reference traces `TraceDidntAdoptBlock`. Before,
  a forged block was adopted whatever the LoE allowed, so a block producer that was not caught up could select
  blocks the LoE held back and make the first of them immutable. Caught up, in Praos mode, or with LoE and GDD
  disabled, forged blocks are adopted as before.
