### Fixed (node-to-client, #356)
- **A channel's receive buffer grows linearly.** The multiplexer copied the whole buffer for every SDU, so reading a
  message of n bytes cost n squared over the SDU size: a 64 MiB message took 10 s in the benchmark, and now takes
  under a second. The buffer is now one array that doubles, and a message that arrives in many SDUs is decoded after
  the buffer doubles or the queue runs dry, not after every SDU. See `docs/performance.md` section 23.
- **LocalStateQuery client messages lose the 2.5 MB and 65,535-item bounds** of a node-to-node message, as answers
  did in #353. The reference reads a query with the same limit-free codec and bounds it only by the mux ingress queue.
  The nesting of a query stays bounded (32,767 levels, as the item bound allowed before), which keeps the refusal of
  a 40,000-layer `GetCBOR` query.
