### Changed (Leios votes, #1349)

- A LeiosNotify client checks the signatures of the votes it receives ahead of their turn, up to half the node's
  processors' worth of such checks at once, and still counts each vote in the order it arrived: a vote's turn
  reads again whether its block is one votes are taken for and whether it counted already, and the committee in
  force, and checks the vote itself where its check ahead was made against another committee or has not ended.
  A peer's replayed votes are taken in faster than one signature check at a time allows.
