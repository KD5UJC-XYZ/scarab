### Fixed (ChainSync, #755, #341)

- The ChainSync server reads `MsgFindIntersect` lists within the reference's node-to-node message byte bound, including lists beyond the former candidate and CBOR-item caps.
- The ChainSync client offers the reference's Fibonacci-spaced intersection points and fragment anchor, replacing the newest-16 and doubling-stride selection.
- A ChainSync point or tip whose hash is not 32 bytes is refused, as consensus's `decodeRawHash` refuses it; it was read. Reference verdicts: `fixtures/reference/chainsync-find-intersect`.
