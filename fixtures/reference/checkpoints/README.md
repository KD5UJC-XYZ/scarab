# Pinned checkpoint configuration

The manifest records the original source identities and hashes of the checkpoint files
in `fixtures/mainnet/genesis` and `fixtures/preview/genesis`. Both exactly match the
hashes already configured in those directories. Mainnet is the unchanged
`configuration/cardano/mainnet-checkpoints.json` from cardano-node
`938cba990357ae7c4b7f95c8f75dd9d31174bbeb` (11.1.3); Preview is the matching
published 11.1 configuration archived in the pinned Mithril source. These are parser
compatibility inputs, not generated differential results.
`Cardano/Node/Protocol/Checkpoints.hs:56–114` defines
the accepted format, duplicate rejection and file-hash verification; consensus
`HeaderValidation.hs:418–428` at `82ecba329d7d054340bf707d44fe6e9ac27cec40` defines
the block-number/hash predicate. Tests also exercise unsigned bounds, malformed
input and original signed captured blocks through header and full-block checks.

Regeneration copies the exact pinned source files and verifies their configured
BLAKE2b-256 hashes. It must not fetch a moving checkpoint file or change configured
trust inputs to make a test pass.
