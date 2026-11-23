### Changed (era types, #85)

- Conway's block rules on the era types reuse a stored output's reading into the era within a run, let a transaction's scripts of one language share their encoded `TxInfo`, and encode the language views of the parameters once a run, so a block's run takes less time than before. Their results are unchanged, and the node still applies Conway blocks with the existing rules.
