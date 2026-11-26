### Fixed (chain sync, #810)
- **A session's candidate is trimmed to our chain's anchor whenever ChainSync takes a header, and no trim is
  lost.** The trim ran only after each step of the session's publisher, and was dropped when ChainSync replaced
  the candidate during that step, about one trim in six; it waited for the next step, which needed the
  consumer to take a header. The candidate, its index, what the publisher reads and what it has published are
  now one state that each change moves in one step, so the candidate is trimmed as each header is recorded, as
  the capacity wait follows our chain, and after each publisher step, as the reference trims its candidate
  whenever our chain changes. A trim still never passes what the session has published to its consumer.
- The block-fetch decisions are told of each trimmed candidate at once. Before, they kept the untrimmed one
  until the peer sent another header; an announcement of an older candidate now never replaces a newer one.
- A batch recorded right after a trim no longer scans the whole candidate again.
- A rollback that the header gate accepts to a point before the candidate's base, in the local chain it
  captured before the intersection, is published to the consumer as a rollback. Before, the session ended
  with a detached-candidate error.
