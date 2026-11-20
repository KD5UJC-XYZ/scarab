### Changed (relay, #1237)

- The BlockFetch server serves any fork the node holds, as the reference's ChainDB iterator does: a
  block received from a peer or forged, rolled back off the selected chain, never selected, or held
  invalid, is served until the received log collects it, and a range on such a fork is walked back through the held blocks and
  joined to the immutable chain at its tip. ChainSync still follows the selected chain alone.
- A range the reference's server refuses with an exception now ends the connection instead of being
  answered `NoBlocks`: an Origin end (`NoGenesisBlock`), bounds that are not a range
  (`InvalidIteratorRange`), and a block collected during its batch (`BlockGCed`, with no
  `BatchDone`). The peer is tried again after a backoff, as for the reference's `ShutdownPeer`; an
  Origin end no longer counts as a protocol violation.

### Fixed (storage, #1237)

- The received log's collection deletes every file whose blocks are all below the immutable tip but
  the one being written, wherever it is in the log, and only those files' blocks leave its index: a
  block still on disk in a kept file stays readable, as in the reference's VolatileDB.
