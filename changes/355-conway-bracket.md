### Fixed (devnet ledger comparison, #355)

- **The Conway ledger comparison reads the reference at one chosen block instead of hoping the tip stands still.** The queries
  of a comparison (`ledger-state`, `utxo`, `protocol-state`, `gov-state`, `committee-state`, `drep-stake-distribution`) were
  bracketed by two tip reads, and a comparison was an error if a block arrived meanwhile. On a devnet with a block every half
  second and a `ledger-state` query that takes most of a second, most attempts failed, so the nightly `e2e` failed with
  `conway: 2 ledger comparison errors` (`no stable bracket`). `pinnedquery.py` now forwards each `cardano-cli` connection through
  a socket in the reference container and rewrites only its `MsgAcquireVolatileTip` to `MsgAcquire` at the tip read once for the
  comparison, so every query reads the same block. A block that has left the node's volatile window is refused by the node and
  recorded as a failed query with its stderr and wall time; a query that was never pinned fails the attempt. A comparison
  that cannot be taken is still an error, and the number of attempts is unchanged. Only a recorded exit status of 0 accepts a
  query's output: valid JSON from a query that exited nonzero, or with no recorded status, fails the attempt with its status,
  stderr tail, wall time and acquire count. The `soak` and `pool` scenarios record a comparison that could not be taken as an
  `error` entry that their gates already count, instead of dropping it. Every acquire and reacquire of a pinned
  connection, volatile tip, immutable tip or explicit point, is sent as an acquisition of the pinned block, and an explicit point
  that differed, or an immutable-tip acquire, fails the comparison without a retry. The `soak` gate fails on any comparison error, and the `pool` final comparison keeps
  the failed tries it retried past.
