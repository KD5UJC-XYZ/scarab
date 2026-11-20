### Fixed (ledger, #85)

- A transaction's outputs are numbered with the 16-bit transaction index, as cardano-ledger numbers them, so an
  output past index 65,535 is neither translated nor created where the reference bounds it.
  - Plutus contexts: from Babbage on, the contexts leave such an output out, both on the era types and on the
    path the node runs (Conway's Plutus V1 to V3 and Dijkstra's flat Plutus V4). Alonzo's Plutus V1 context
    still holds every output, as the reference's does.
  - UTxO rules: in every Shelley-based era, the UTxO rules create, and add to the instant stake, only the outputs
    the reference's `txouts` creates. A block counts the reference scripts of the same outputs.
  - Allegra and Mary: three output checks read only those outputs, as the reference's do: the minimum value,
    the value size and the bootstrap attributes. Their network check and Shelley's output checks still read
    every output.
  - Collateral return: its index follows the last output, or is 65,535 when that does not fit, as the
    reference's `mkCollateralTxIn` gives it.

  No transaction within the current size limits reaches the bound.
