# Provenance and adaptation record

The [portable manifest](../fixtures/network/block-fetch/manifest.json) identifies
all bundled fixtures, protocol sources and license/notice files with exact hashes.

No new source downloads or upstream runtime executions were performed. Authority is the retained pinned source set identified in `cardano-research/next-block-fetch-slice.md` and `block-fetch-source/manifest.json`. These fixtures are source-derived encoder literals, never captured Haskell/Pallas transcripts.

## Authorities and local evidence

- [cardano-research/ouroboros-network/ouroboros-network/protocols/lib/Ouroboros/Network/Protocol/BlockFetch/Codec.hs](https://github.com/IntersectMBO/ouroboros-network/blob/c45735a56c567fa977969173d18943bac6bb3821/ouroboros-network/protocols/lib/Ouroboros/Network/Protocol/BlockFetch/Codec.hs)
  - SHA-256: `812427f9cd4a8204b2f479c5aa5a1ff019f324d1a99a1709616d482a46f9d39c`; bytes: 7023
- [cardano-research/ouroboros-network/ouroboros-network/protocols/lib/Ouroboros/Network/Protocol/BlockFetch/Type.hs](https://github.com/IntersectMBO/ouroboros-network/blob/c45735a56c567fa977969173d18943bac6bb3821/ouroboros-network/protocols/lib/Ouroboros/Network/Protocol/BlockFetch/Type.hs)
  - SHA-256: `d4418295585030812da3137147efbaa70ca34981a70c48631a6878749e6796d3`; bytes: 3604
- [cardano-research/ouroboros-network/ouroboros-network/api/lib/Ouroboros/Network/Block.hs](https://github.com/IntersectMBO/ouroboros-network/blob/c45735a56c567fa977969173d18943bac6bb3821/ouroboros-network/api/lib/Ouroboros/Network/Block.hs)
  - SHA-256: `83e93f9be5a5bf30600c4d5257f8527bd06adc22d5f92bcc83165a479982a822`; bytes: 17714
- [cardano-research/ouroboros-network/ouroboros-network/api/lib/Ouroboros/Network/Protocol/Limits.hs](https://github.com/IntersectMBO/ouroboros-network/blob/c45735a56c567fa977969173d18943bac6bb3821/ouroboros-network/api/lib/Ouroboros/Network/Protocol/Limits.hs)
  - SHA-256: `85d22f3f9af5c94c5dc67897860a10a1432a02a4574b6c4ad0c9cc5394b04a36`; bytes: 3669
- [cardano-research/ouroboros-network/cardano-diffusion/lib/Cardano/Network/NodeToNode.hs](https://github.com/IntersectMBO/ouroboros-network/blob/c45735a56c567fa977969173d18943bac6bb3821/cardano-diffusion/lib/Cardano/Network/NodeToNode.hs)
  - SHA-256: `1c913963429ec8e054095812438441cb1ec59fe533fbd0ddefafa9ab28266f40`; bytes: 22334
- [cardano-research/block-fetch-source/consensus-server.hs](https://github.com/IntersectMBO/ouroboros-consensus/blob/82ecba329d7d054340bf707d44fe6e9ac27cec40/ouroboros-consensus/src/ouroboros-consensus/Ouroboros/Consensus/MiniProtocol/BlockFetch/Server.hs)
  - SHA-256: `d8dc5172c88fc68ca8113f5a1857b4519a74088eaa9424f7182a30534ddeea36`; bytes: 5368
- [cardano-research/block-fetch-source/codec.rs](https://github.com/txpipe/pallas/blob/0ea294c1273b9c4a5a0a9f767897800589f5e02b/pallas-network/src/miniprotocols/blockfetch/codec.rs)
  - SHA-256: `78da5b46f2726e8eaea9bf62d67f5cc71e919520f57f350e3aee68ae3ff08052`; bytes: 2073
- [cardano-research/chain-sync-source/ouroboros-consensus-cardano/golden/cardano/CardanoNodeToNodeVersion2/Block_Conway](https://github.com/IntersectMBO/ouroboros-consensus/blob/82ecba329d7d054340bf707d44fe6e9ac27cec40/ouroboros-consensus-cardano/golden/cardano/CardanoNodeToNodeVersion2/Block_Conway)
  - SHA-256: `eb451bb8134942ff8afbcd1e87cedd282197b865d8a3637e41cf158ad7e34548`; bytes: 7807

## What each source establishes

- Network BlockFetch Codec/Type: exact tags, definite arities, state agency, six transitions and inclusive ChainRange semantics.
- Network Block: Serialised adapter checks tag24 and decodes a definite byte string. Inner bytes remain opaque.
- Network Protocol Limits: small 65,535-byte and large 2,500,000-byte limits, longWait 60 seconds.
- Network NodeToNode: BlockFetch mini-protocol 3.
- Consensus server: StreamFromInclusive/StreamToInclusive; Origin produces NoGenesisBlock; UnknownRange produces NoBlocks. Mid-stream garbage collection is not a successful partial batch.
- Pallas codec: independent encoder shape comparison only. Its unchecked outer arity/tag decoder behavior is deliberately not copied.
- Conway Block_Conway: genuine upstream serialized-block golden, not a full BlockFetch transcript and not an authenticated chain segment.

The local `cardano-scala-lab/network/src/main/scala/lab/network/ChainSyncWire.scala` was used as the mechanical basis of ProtocolWire. Its Reader/Writer scanner logic is retained; independent Limits/Failure helpers were added and hardcoded ChainSync policy was removed from the extraction. Existing ChainSync.Point/UInt64/DecodeResult and PayloadCodec APIs and Mux direction values were read locally. The integrated shared scanner preserves ChainSync policy through its original checked boundary.

## Fixture classes

- `batch-done.hex`: source-derived encoder literal; synthetic wrapper/point data; wire bytes 2; wire SHA-256 `b8aa8c1e3b7597cd09410468c2d70dcc83a8cb1c3663fd1b2a4b4290daba16d7`.
- `client-done.hex`: source-derived encoder literal; synthetic wrapper/point data; wire bytes 2; wire SHA-256 `ac38783f6a3b2fe3b579718d6ba8493a456d800665b4433a0e7c823cff90a603`.
- `conway-block-source-derived.hex`: source-derived BlockFetch envelope around genuine upstream golden payload; wire bytes 7809; wire SHA-256 `ccf88f406ce7085c3425e8cb63f94c59697c386d8920650b8f8f9b042f4b0d43`.
- `no-blocks.hex`: source-derived encoder literal; synthetic wrapper/point data; wire bytes 2; wire SHA-256 `9ab801dcef11b73fbe8e3f6a5724152b3079cd8cabb4446e060d5ca55bd30865`.
- `request-origin.hex`: source-derived encoder literal; synthetic wrapper/point data; wire bytes 4; wire SHA-256 `3510d537a3a301739cad5db00710d31dd68f0e5b188d9ab8282aa42f37d42be0`.
- `request-slot1-zero32.hex`: source-derived encoder literal; synthetic wrapper/point data; wire bytes 74; wire SHA-256 `de38168b8034ad0c70290c95501fb02896584131853aa89de5285adb154c49c1`.
- `start-batch.hex`: source-derived encoder literal; synthetic wrapper/point data; wire bytes 2; wire SHA-256 `e30f7e51e257e9f7da46b7cfb9174bf9ae2d238491fa07e9f85e2915bfa4d829`.
- `toy-block.hex`: source-derived encoder literal; synthetic wrapper/point data; wire bytes 6; wire SHA-256 `d749b5941387466325775c37eb0bddbf473fd94a6416d7b400add581ad893f3d`.

`toy-block.hex` contains raw CBOR `80` solely as a wrapper test; it is not a Cardano block. Origin-to-Origin is generic wire-valid and explicitly Cardano-API-invalid. Zero32 hashes are synthetic and identify no real block.

Conway original serialized golden: 7,807 bytes; SHA-256 `eb451bb8134942ff8afbcd1e87cedd282197b865d8a3637e41cf158ad7e34548`. Derived full message prepends `8204` without altering golden bytes: 7,809 bytes; SHA-256 `ccf88f406ce7085c3425e8cb63f94c59697c386d8920650b8f8f9b042f4b0d43`. Its tag24 bstr contents are 7,802 raw disk-block bytes; SHA-256 `0b7c8bdb99cf28f5e73769733abb4d4630013c5cf9368ed3176717841f95d8f3`. Their prefix is `[7,...]`, not ChainSync's Conway header era index6. No network or ledger validity is inferred from the bytes.

## Licenses

Scala sources use Apache-2.0 SPDX headers. Source projects are Apache-2.0; network and consensus LICENSE/NOTICE files are retained under `fixtures/licenses`. Preserve the applicable notices during integration, including the consensus notice for its reused golden. Pallas code was inspected as independent evidence, not copied. No provider-sourced historical data was newly copied, and project source licenses do not establish provider-data redistribution rights.

## Verification

Portable fixture/source/license admission is implemented by `scripts/verify-block-fetch-cli.py`.
Final Scala and CLI verification results are recorded in `progress.md` and the
BlockFetch build/CLI logs. No upstream runtime oracle or live protocol was executed.
