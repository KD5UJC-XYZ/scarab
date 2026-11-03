### Fixed (Leios blocks as the Leios testnet's build reads them, #711)

- A Leios block and header are read as the build the public Leios testnet runs reads them (release
  `rc-2026w44`: cardano-node `b004fe0c`, cardano-ledger `9df9f98c`, ouroboros-consensus `2416493b`),
  the Leios reference since 2026-11-03. Every list of the block, the header, its body, the version
  info, the endorser block announcement, the VRF certificate and the Leios certificate is definite or
  indefinite with its items before the break; one written as an empty indefinite list with its fields
  after it is refused. The block envelope must be a definite list, and only tag 8 is a Leios block:
  tag 7 is a Conway block with a Praos header in that build.
- A Leios header's KES signature is checked over its body's original bytes, its version info is two
  unvalidated `Word32`, and its VRF output must be 64 bytes, as in that build. A Leios certificate in
  a block body is read with chunked byte strings and any head width, and its signature may be the
  identity point there, as that build decodes it; verification still refuses it.
- A new oracle built from that build's revisions (`scripts/oracle/leios-testnet`) and its corpus of 88
  cases (`fixtures/reference/leios-testnet-blocks`) record its verdicts; the 25 Leios cases of the
  header decoding corpus moved there.
