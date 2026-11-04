### Fixed (consensus, #770)

- The leader check evaluates `ln(1 - f)` with the reference's `splitLn` and `findE` for any active-slot coefficient in (0, 1]. A coefficient above 1/2 other than 1 was refused for every header; it is now decided as the reference decides it. Mainnet and the public testnets (1/20) are unchanged: their logarithm is the same to the last E34 unit.
