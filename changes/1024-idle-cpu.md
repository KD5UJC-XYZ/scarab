### Changed (performance, #1024)

- A near-idle node uses less CPU. The query and metrics HTTP servers now run on the JDK's asynchronous channels instead of the effect runtime's integrated selector, so the runtime's idle workers no longer poll it about 200,000 times a second while they wait for timers. The servers' routes, ports, shutdown timeouts and answers are unchanged.
- The BlockFetch decision, made at the reference's interval (10 ms in Praos mode), no longer rebuilds a set of every fetched block and a set per candidate: it asks a membership test over the block pool's and the received log's state, read once per decision, as the reference's `readFetchedBlocks` does. The same blocks count as fetched.
