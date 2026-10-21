### Fixed (decoder fuzz, chainsync-ntn)

- The Byron size hint of a node-to-node ChainSync header is a Word32 in both directions, as in the reference
  (`SizeInBytes`, `encodeWord32` / `decodeWord32`). The decoder accepted any unsigned integer and the encoder
  refused values above 2^31-1, so a header with a hint such as 4233459348 decoded but did not encode (nightly
  fuzz seed 20747333). A hint above 2^32-1 is now refused on decode with `ProtocolError.SizeHint`.
