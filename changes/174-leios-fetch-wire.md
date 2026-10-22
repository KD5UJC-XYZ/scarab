### Changed

- Follow the LeiosFetch wire of cardano-blueprint `0460c5a6` (ouroboros-consensus#2416): `MsgLeiosBlockTxs` is
  `[4, [* tx]]` and carries only the transactions, a transaction is `#6.24(bytes)` whose payload is kept as it came,
  and an endorser block names at least one and at most `maxTxsPerEb` (17,142) transactions; a transaction list is
  bounded the same way. The pinned `leios-fetch/messages.cddl`, `models.conf` and the generated reader moved with it:
  the reader now decodes each transaction as `dijkstra.block_transaction`, and the generator enforces the entry
  bounds of a map (`{ + k => v }`) as it does those of an array. The older form of `fe4cceb9` is still read as
  `LeiosFetch.Message.LegacyBlockTxs` until a capture or a testnet release settles which form a relay speaks (#174).
- LeiosFetch bitmaps follow the reference decoder: at most 268 entries, no zero bitmap, windows below 268 and strictly
  ascending, each a typed error; a request writes the indefinite-length map the reference writes.
