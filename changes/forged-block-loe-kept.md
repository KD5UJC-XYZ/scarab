### Fixed (forged blocks are kept as the reference's ChainDB keeps them)
- **A block the node forges is kept whatever chain selection makes of it, and chain selection can take it later.**
  As the reference's ChainDB puts a block it is given in its VolatileDB, the forged block is written to the received
  log and joins the blocks chain selection chooses from, unless it is there already, known invalid or no younger
  than the immutable tip; a refused one is recorded invalid. A block the Limit on Eagerness held back is adopted when
  the LoE moves or the node is caught up, one that lost to the tip or left the chain when a block delivered later
  makes its branch preferred, and an adopted one that a rollback left off the chain when its branch grows again,
  after a restart too, none of them delivered by a peer. The forger still logs what selection made of the block when
  it submitted it; a later adoption is logged as the chain's `extended`. A failure to keep the block that is not a
  storage failure is logged `notKept` and changes nothing else; a storage failure stops the node. Before, only an
  adopted block was kept, on the selected chain, and a block that left it, or never reached it, was lost.
