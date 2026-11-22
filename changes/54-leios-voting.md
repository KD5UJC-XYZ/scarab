### Added (Leios voting, #54)

- Under `--reference-build leios-b004fe0c` the node votes with the `--shelley-bls-key` keys read at
  start, also when it starts as non-producing: each endorser block whose closure the store acquires
  is voted on once its vote window opens, if the selection's tip announces it, a key holds a seat of
  the tip's committee, its closure applies to the tip's state (every transaction by the mempool's
  rules, with the validity flag the block declares) and the window is still open; one vote per seat,
  counted in the node's vote state, which its LeiosNotify servers relay.
- The log lines `leiosVoteScheduled`, `leiosVoted`, `leiosNotVoted` (with the reference's reasons)
  and `leiosKernelMsg`; the node's own counted votes also log `leiosVoteAcquired` (with `connection`
  null) and `leiosCertified`. A failure of the voting thread stops the node (`fatal` of kind
  `leiosVoting`, exit code 6).
- `devnet.py leios-traffic --follow --follow-vote POOL`: the follower votes as pool POOL of the
  cluster, and the judge requires a reference node to count one of its votes.
