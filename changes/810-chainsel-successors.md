### Fixed (chain selection cost, #810)
- **Chain selection no longer sorts and checks the whole processed forest for every block.** The forest now keeps
  the VolatileDB's successors index, updated where a block joins or leaves it. A block's admission finds its
  candidates from that block alone, back to the selected chain and on through its successors, as the reference's
  `chainSelectionForBlock` does; the LoE reprocessing pass walks from the successors of each selected point. The
  candidates and their order are unchanged.
- A block whose predecessors would lead back to it is refused when it joins the forest, raising in the same scope
  the `AvailableCycle` problem that selection raised before; the key and hash mismatch selection also checked can
  no longer be built.
- The garbage collection below the immutable tip reads only the blocks it removes.
