### Changed (era types, #85)

- The node applies Conway blocks with Conway's era rules (BBODY on the era types), serially or through
  `--plutus-workers`, in place of the flat rules; blocks of the other eras, and Dijkstra, are unchanged. The
  state, the phase-2 counters and `--plutus-cases` are as before.
- A refused Conway block names every failure its rules record, in the reference's order, where it named the
  first. A block refused on failures recorded before a defect of this node is refused as the reference refuses
  it, the defect named in the refusal's reason.
- A Conway block whose rules meet a state, a store or a limit of this node's own is withheld
  (`blockNoVerdict`): kind `evaluation` for a script the evaluator gives no verdict for, kind `defect` for a
  stored output or a record of the proposals that does not read, too many proposals or the node's readings of
  the block that disagree; nobody is blamed and the node goes on.
