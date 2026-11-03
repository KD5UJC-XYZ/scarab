### Fixed (address and reward account headers, #723)

- An address whose header names a network other than 0 or 1 (a Shelley header with any of bits
  1 to 3 set, such as `0x62` or `0x6f`) is refused in an output and a collateral return of every
  era, as the reference refuses it. So is a reward account with such a header, in a withdrawal, a
  pool registration, a proposal and a treasury withdrawal. A block holding one was taken before.
- An output the node stored before keeps reading whatever its header.
