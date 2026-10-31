### Fixed (block-fetch peer order, #427)

- Block-fetch candidates are ranked from the reference's peer order: by each connection's
  `ConnectionId`, remote address first, as the reference's peer maps hold them, instead of the
  order in which the sessions registered. The GSV comparator is not transitive, so its result
  depends on that order; where it finds peers equal or cycles between them, the ranking now starts
  from the same order as the reference's.
