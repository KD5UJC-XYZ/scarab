### Changed (the Leios build, #826)

- In the layout of `--reference-build leios-b004fe0c`, a block of a Shelley-based era adds the
  size of every transaction it holds, valid or not, to the ledger state's cumulative transaction
  bytes, as the Leios build's consensus layer does: the total starts at zero, is carried from era to
  era, and is written to and read from the ledger snapshot. Until now a block left the state with
  the 11.1 layout's field, which the Leios layout cannot write. `follow` still refuses the Leios
  build, so no run applies blocks in that layout yet. The `11.1` build is unchanged.
