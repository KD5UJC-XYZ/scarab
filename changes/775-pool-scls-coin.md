### Fixed (held stake pools, #775)

- The SCLS export service now writes what the reference's canonical-state export writes for a
  held pool. That covers a relay DNS name or metadata URL longer than 128 bytes, which genesis
  accepts. Before, the export refused such a pool: on the relay name, its check of the values it
  wrote applied the import's bound; on the metadata URL, its encoder did. The SCLS import still
  refuses either above 128 bytes, as the reference's import does at Conway's decoder version.
- The SCLS import and export now take a pool metadata hash of any length, as the reference's
  `PoolMetadata` codec does, instead of requiring 32 bytes.
- `GetStakePoolParams` and `GetPoolState` now answer for a genesis pool whose pledge or cost is
  negative or larger than `Word64`, writing the Coin as a CBOR `Integer` as the reference does.
  Before, the answer was refused as unwritable. The pool parameters in the state are written the
  same way and are still refused when the state is read back, as the reference refuses them.
