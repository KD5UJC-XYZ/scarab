### Fixed (consensus, #818)

- A Byron block's slot is its epoch and slot count flattened in `Word64` arithmetic, which wraps, as the reference flattens them. A slot count at or past the epoch's length is no longer refused. Blocks are ordered by the reference's envelope rules on that slot, so a boundary block may follow a boundary block across an epoch with no blocks.
