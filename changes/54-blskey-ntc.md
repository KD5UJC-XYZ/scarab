### Fixed (the Leios build, #54)

- `GetStakePoolState` now answers for a pool whose future parameters carry a voting key, with the
  same bytes as the Leios build. It used to answer that the result could not be written.
