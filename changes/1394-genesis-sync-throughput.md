### Fixed (Genesis sync from origin, #1394)
- **A sync from origin in Genesis mode no longer stalls at the immutable tip plus k.** While the Limit on Eagerness
  held the selection, every admitted block made chain selection sort the whole processed forest twice, and every
  header made ChainSync scan the candidate once for each block of our chain, so chain selection fell behind block
  fetch. The fetch peer's blocks stayed in flight until their admission was queued, so its fetch start aged past the
  10-second grace; the next empty chain-selection queue then counted as starvation, and the ChainSync-jumping dynamo
  was demoted just before it reached the next jump, again and again. The forest is
  now sorted once with the allocation-free byte order, membership in a candidate or in our chain is a set lookup, a
  header's point is computed once, and a candidate that only extends the published one is published without indexing
  it again. Results are unchanged. In a recorded mainnet sync from origin, the fixed node synced 32,385 blocks in
  9 minutes 15 seconds across every jump boundary it met, with no dynamo demotion; the unfixed node stayed at
  block 6,480.
