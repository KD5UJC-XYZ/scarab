### Changed (era types, #85)

- Conway's block rules on the era types read each stored output into the era once a run, and build each transaction's script data and the language views of the parameters once, so a block's run takes less time than before. Their results are unchanged, and the node still applies Conway blocks with the existing rules.
