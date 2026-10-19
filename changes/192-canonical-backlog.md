### Fixed (NtC verification, #192)

- Select backlog intersections from points still canonical on both nodes, retaining historical
  blocks for byte comparisons. Retry a symmetric stale intersection once from an older candidate;
  asymmetric answers, differing bytes and transport failures remain failures.
