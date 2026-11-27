### Changed (Genesis chain selection, #1499)
- **The Genesis projection of each admitted block no longer works over the whole selection window and limit.** While
  the node syncs in Genesis mode, the block-selection worker trims each candidate to the limit on eagerness before it
  ranks it. It rebuilt maps and sets of every point of the selection window (k blocks) and of the limit at each
  admission, which took a tenth of the worker's time per block on mainnet. The window now carries the index of its
  blocks from one admission to the next, and a point is found on the limit by its slot, as the reference's
  `AnchoredFragment` finds it. The candidates chosen, their order, the trims and the refusals are the same as before.
