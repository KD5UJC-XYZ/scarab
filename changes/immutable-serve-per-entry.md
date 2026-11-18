### Fixed (storage)

- Serving a block from the immutable store reads that block alone. A ChainSync client behind the volatile chain is
  sent each header from one read of its block. A BlockFetch range served from the store reads its blocks one at a
  time as it streams, each checked against its checksum and at most two ahead of the block being sent, besides the
  reads of the range's two ends that check its order. Both used to read and checksum the whole chunk that held the
  block, and copy each of its blocks, on every request.
- The immutable store holds the index of the chunk it appends to in memory, and the indexes of the chunks before it
  up to 270,000 entries, the least recently used dropped first. Every five minutes it drops the indexes of those not
  used for five minutes, as the reference expires its own; the chunk appended to is never dropped. The reference
  bounds its cache at 250 chunks, whatever their sizes; the bound in entries is a recorded Deviation. Serving a block
  used to read the chunk's index file on every request.
