### Fixed (state dump at slot targets, #675)

- `--dump-state <slot>,<slot>,...` decides a slot target from the tip of the state each block was
  applied to and the block's slot, not from a list of targets not yet met. A block that replaces a
  rolled-back block that met a target now gets its digest. After a restart only the targets the
  first block crosses are due, not every earlier one. A target met by a Byron block gets no digest;
  it was previously written at the first Shelley-based block.
