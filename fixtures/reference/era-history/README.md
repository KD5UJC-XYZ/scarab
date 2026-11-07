<!-- SPDX-License-Identifier: Apache-2.0 -->
# Era history (`GetInterpreter`)

Status: Current. Evidence for [#118](https://github.com/KD5UJC-XYZ/scarab/issues/118).

`cases.jsonl` holds the reference's answer to `GetInterpreter` for two hard-fork states of a chain with the
preprod genesis, from the [era-history oracle](../../../scripts/oracle/era-history/README.md) (the unchanged
ouroboros-consensus `82ecba32`). Each line has the state (`scenario`, `transition`), the summary as the
reference shows it, and the `interpreter` bytes in hex:

- `conway-tip-100000000`: Byron to epoch 4, the eras from Shelley to Babbage empty at epoch 4, Conway from
  epoch 4 with the tip at slot 100,000,000. Conway ends at the safe zone, epoch 236, at
  101,952,000,000,000,000,000 ps: a tag-2 big number, beyond 64 bits.
- `byron-origin`: the chain still in Byron, at the origin. Byron ends at its safe zone, epoch 1, not where
  the configuration would place Shelley.

`EraHistoryNetworksSuite` requires Scarab's answer to be these bytes. `provenance.json` records the pin, the
image, the program's and the executable's hashes, the build plan's hash and the genesis files' hashes.
