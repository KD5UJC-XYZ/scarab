### Fixed (Ouroboros Genesis: forged blocks and the Limit on Eagerness)
- **A forged block the Limit on Eagerness holds back is kept, and chain selection can take it later.** The block is
  put in the received log and joins the blocks chain selection chooses from, as the reference's ChainDB keeps it in
  its VolatileDB. When the LoE moves or the node is caught up, the selection that follows considers it with every
  other block it holds, and adopts it if it is still valid and preferred; a better chain that arrived meanwhile wins
  over it, and a refusal of it blames no peer. The forger still logs `didntAdoptBlock` when it submits the block; a
  later adoption is logged as the chain's `extended`. Before, such a block was dropped and never selected.
