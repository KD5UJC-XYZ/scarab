### Fixed (CDDL fuzz seeds, #294)

- Load complete pinned schema dependencies for ChainSync header envelopes and Leios messages. Every requested CDDL sample is required; missing imports, unsupported grammar and sampling failures fail the seed gate instead of silently dropping a batch.
