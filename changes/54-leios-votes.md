### Added (Leios votes, #54)

- `follow --forge` takes `--shelley-bls-key <file>`, the pool's Leios voting keys, one `cardano-cli`
  BLS signing key envelope or a JSON array of them, read with the other credentials at start as
  cardano-node does. The node does not vote with them yet.
- The Leios vote state of the reference's Leios build: votes validated against the committee of the
  selection's tip, counted once per seat and announcing block, and a certificate assembled when the
  tally reaches the quorum; with the vote decision (`goVote`) and `decideLeiosCertify`, which the node
  does not run yet. A vote signed with the reference's golden key is the reference's golden vote.
