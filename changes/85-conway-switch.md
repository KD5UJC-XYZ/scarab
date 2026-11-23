### Changed (era types, #85)

- The node applies Conway blocks with Conway's era rules (BBODY on the era types), serially or through
  `--plutus-workers`, in place of the flat rules; blocks of the other eras, and Dijkstra, are unchanged. The
  state, the phase-2 counters and `--plutus-cases` are as before.
- A refused Conway block names every failure its rules record, in the reference's order, where it named the
  first. A block refused on failures recorded before a defect of this node is refused as the reference refuses
  it, the defect named in the refusal's reason.
- A Conway block whose rules meet a state, a store or a limit of this node's own (an unnamed script with no
  verdict, a stored output or a record of the proposals that does not read, too many proposals) is withheld
  (`blockNoVerdict`, kind `defect`); nobody is blamed and the node goes on.
