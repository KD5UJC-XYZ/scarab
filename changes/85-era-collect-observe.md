### Added (era types, #85)

- Conway's block rules on the era types tell the `--plutus-cases` observer of a transaction whose scripts' contexts cannot be built, such as a Plutus V1 transaction creating an output with an inline datum, with the same observation and at the same place as the existing rules, whether the block's scripts are evaluated in order or in the node's parallel workers; no verdict changes. The node still applies Conway blocks with the existing rules, so nothing changes in behaviour.
