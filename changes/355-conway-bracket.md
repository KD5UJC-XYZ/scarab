### Fixed (devnet ledger comparison, #355)

- **The Conway ledger comparison reads the reference at one chosen block instead of hoping the tip stands still.** The queries
  of a comparison (`ledger-state`, `utxo`, `protocol-state`, `gov-state`, `committee-state`, `drep-stake-distribution`) were
  bracketed by two tip reads, and a comparison was an error if a block arrived meanwhile. On a devnet with a block every half
  second and a `ledger-state` query that takes most of a second, most attempts failed, so the nightly `e2e` failed with
  `conway: 2 ledger comparison errors` (`no stable bracket`). `pinnedquery.py` now forwards each `cardano-cli` connection through
  a socket in the reference container and rewrites only its `MsgAcquireVolatileTip` to `MsgAcquire` at the tip read once for the
  comparison, so every query reads the same block. A block that has left the node's volatile window is refused by the node and
  recorded as a failed query with its stderr and wall time; a query that was never pinned fails the attempt. A comparison
  that cannot be taken is still an error, and the number of attempts is unchanged.
