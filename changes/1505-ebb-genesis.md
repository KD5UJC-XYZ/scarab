### Fixed (consensus, #1505)

- A Byron epoch boundary block that names the genesis (epoch 0, or the "Genesis" tag) is filed after
  genesis in the processed block forest whatever bytes its header holds as the previous hash, as the
  reference's VolatileDB files it (`headerPrevHash` is `GenesisHash`), and as the header gate already
  placed it. Before, the forest filed it under the block those bytes name, so a boundary block made
  with other bytes there was either never reached by chain selection (and so never found invalid) or
  reached after that block. The recorded first boundary blocks of mainnet and preprod name their
  genesis hash, so nothing changes on the public chains.
