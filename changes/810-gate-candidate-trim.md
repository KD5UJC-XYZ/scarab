### Fixed (ChainSync candidates, #810)
- **A peer's candidate is trimmed to our chain's anchor, as the reference trims it.** The header gate only capped a
  candidate at the retention limit (about 263,000 headers on mainnet). A long sync's candidates therefore held tens
  of thousands of headers, every header's checks grew with them, and the sync slowed steadily until a restart. The
  gate now trims a candidate after each header to the anchor of the selection it was checked against, as the
  reference's ChainSync client trims it whenever our chain changes. Each snapshot of the gate's history is the
  history itself rather than a copy of every entry.
