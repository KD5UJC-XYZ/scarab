### Fixed (BlockFetch server: a range's order, #932)

- A BlockFetch range is in order when its start's slot is at or before its end's, and within one slot a Byron
  boundary block comes before the main block, as the reference orders an iterator's bounds (`validBounds`,
  ouroboros-consensus 82ecba32 `Storage/Common.hs` 126-137, and its ImmutableDB's chunk slots,
  `ImmutableDB/Impl/Iterator.hs` 151-159). It used to be ordered by block number, which a boundary block shares
  with the block before it: a range from a boundary block back to that block was answered with every stored block
  from the boundary block to the immutable tip, or with an empty batch when both were in the volatile chain. Such a
  range is now answered NoBlocks, as other ranges out of order are; the reference ends the connection for them,
  which #932 still tracks.

### Changed (serving stored blocks)

- A BlockFetch range finds its two ends in the volatile chain or in the immutable store's index and reads each
  stored block of the range once, when it is sent; it reads no block after the range. It used to read and decode
  both ends to order them, read the first again, and read up to two blocks past the end: a range of five stored
  blocks now makes five reads, where it made seven to nine.
- A ChainSync intersection at a point older than the volatile chain finds the point in the immutable store's index
  and reads no block, as the reference's follower finds it in its indexes. It used to read and checksum the block.
- The header ChainSync sends for a block, stored or volatile, is decoded from the block's envelope and header; the
  block is still parsed as CBOR whole, but the contents of its body (transactions, auxiliary data, the body hash)
  are not decoded, where they used to be. A stored block is still read whole, once: the immutable index records no
  header offset.
