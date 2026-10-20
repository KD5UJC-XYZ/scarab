### Added (non-myopic reward likelihoods, #268)

- Generate the reference's 100 pool-performance likelihood samples for every snapshot pool, including pools that made no blocks, and preserve Float rounding, captured history decay and normalization. Reward API, timing and V5 continuation tests now compare likelihoods without a mask; a retained Preview pool corpus checks 72,700 original Haskell Float bit patterns.
- Preserve V5 encoding and imported frozen reward states. Missing historical likelihoods are not reconstructed; #129 still owns the conservative snapshot provenance flag and per-state gap tracking.
