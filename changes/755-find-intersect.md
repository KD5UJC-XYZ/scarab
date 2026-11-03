### Fixed (ChainSync, #755)

- The ChainSync server reads a `MsgFindIntersect` of any number of points within the 65535 bytes of a node-to-node message, as the reference's `decodeList decodePoint` does, where it refused more than 64 points or 8192 CBOR items. A ChainSync message's items are bounded at 65535, which its bytes already bound.
- A ChainSync point or tip whose hash is not 32 bytes is refused, as consensus's `decodeRawHash` refuses it; it was read. Reference verdicts: `fixtures/reference/chainsync-find-intersect`.
