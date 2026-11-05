### Fixed (ChainSync, #755)

- The ChainSync server reads a `MsgFindIntersect` of any number of points within the 65535 bytes of a node-to-node message, as the reference's `decodeList decodePoint` does, where it refused more than 64 points or 8192 CBOR items. A node-to-node ChainSync message's items are bounded by its 65535 bytes; node-to-client has no protocol item budget.
- A ChainSync point or tip whose hash is not 32 bytes is refused, as consensus's `decodeRawHash` refuses it; it was read. Reference verdicts: `fixtures/reference/chainsync-find-intersect`.
