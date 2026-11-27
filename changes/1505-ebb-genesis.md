### Fixed (consensus, #1505)

- A Byron epoch boundary block that names the genesis (epoch 0, or the "Genesis" tag) is after
  genesis whatever bytes its header holds as the previous hash, as the reference reads it
  (`headerPrevHash` is `GenesisHash`) and as the header gate already placed it: the processed block
  forest, the received log's predecessor index, BlockFetch's ranges and ChainSync's known-invalid
  check read it so now. Before, they read the block those bytes name, so a boundary block made with
  other bytes there was filed after that block or never reached by chain selection (and so never
  found invalid), a relay could serve a range across it, and a peer could be disconnected for an
  unrelated invalid block. The recorded first boundary blocks of mainnet and preprod name their
  genesis hash, so nothing changes on the public chains.
