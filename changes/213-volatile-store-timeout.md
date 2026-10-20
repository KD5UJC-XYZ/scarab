### Fixed (storage, #213)

- `VolatileStoreSuite`'s two heaviest property tests exceed munit's implicit 30 s timeout budget on
  modest hardware (80.8 s and 35.5 s measured on dedicated 12-core hardware; 98 s / 34 s on a 2012
  i7-3770). The suite now sets an explicit `munitIOTimeout` of 10 minutes, removing the environmental
  flake.
