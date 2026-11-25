### Fixed (ChainSync capacity wait, #810)
- **A session whose retained window is full no longer waits forever after our chain moves on.** While
  the window is full, ChainSync waits for room before the next header, so the header gate recorded
  nothing, and the candidate was never trimmed to our chain's new anchor. A window whose oldest block is
  never fetched again then stayed full. The wait now follows our chain as the reference follows it in
  its waits: it wakes when our chain changes, trims the gate's history and the candidate to our new
  anchor. When the peer's chain no longer passes through our anchor, the session drains and finds a new
  intersection, as the reference does, without taking the waiting header.
