### Fixed (consensus)
- **The Praos issue-number rule computes `m + 1` in `Word64`, as the reference does.** After the largest
  operational-certificate counter, `2^64 - 1`, no counter is accepted (ouroboros-consensus `Praos.hs`).
