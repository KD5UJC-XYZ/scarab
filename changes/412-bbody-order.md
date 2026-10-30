### Changed (block body order, #412)

- BBODY checks a block in the reference's order: the reference scripts of its transactions (Conway),
  the body against the header's commitment, LEDGERS, and then the execution units of the block. A
  block that exceeds `maxBlockExUnits` is refused with the units of all its transactions, not those of
  the first prefix that exceeds the limit. Which blocks are refused is unchanged. The reason given
  can differ for a block that fails more than one check, and the units named by `BlockExUnitsTooBig`
  are now the block's total.
