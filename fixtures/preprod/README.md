# Preprod testnet fixtures

Raw node-to-node BlockFetch blocks (`[era_tag, block]`), one hex-encoded block per line, captured
with `lab.node.tools.CaptureMain` from the public relay `preprod-node.play.dev.cardano.org:3001`
(network magic 1) on 2026-10-11:

```sh
java -cp "$(cat node/target/runtime-classpath.txt)" lab.node.tools.CaptureMain capture \
  --peer preprod-node.play.dev.cardano.org:3001 --magic 1 --blocks 70 --out blocks.hex \
  --byron-epoch-slots 21600
```

| File | Contents |
|---|---|
| `blocks.hex` | The first 70 blocks from Origin, contiguous: the epoch 0 boundary block (tag 0), the 45 Byron main blocks (tag 1) that are the whole Byron era of preprod, and the first 24 Shelley blocks (tag 2, TPraos headers, protocol 2). 117 KB. |
| `genesis/` | The public preprod configuration (`topology.json`, `config.json`, `byron-genesis.json`, `shelley-genesis.json`, `alonzo-genesis.json`, `conway-genesis.json`) from https://book.play.dev.cardano.org/environments/preprod/, unchanged. `shelley-genesis.json` must stay byte-identical: the initial Praos nonce is its hash. |

What the Byron blocks contain (decoded with `analyze` of the capture, pinned by `ByronPreprodSuite`):

- one proposal for protocol 1.0.0 with seven votes (block 2, epoch 0), endorsed by the blocks that
  follow (their header version is 1.0.0 from block 4); one proposal for protocol 2.0.0 with seven
  votes (block 23, epoch 2), endorsed likewise; no delegation certificate, **no transaction**;
- blocks are sparse (a block every ~2160 slots, round-robin over the seven genesis keys), and the
  only boundary block is the epoch 0 one: preprod has none for epochs 1 to 3.

The configuration has no `TestShelleyHardForkAtEpoch`: the epoch in which the chain enters Shelley
(4) is derived from the update proposals and endorsements above. The suite replays the blocks from the
genesis files, so it covers the genesis UTxO (8 non-AVVM balances), the genesis delegation, the PBFT
window, the update state, the epoch-4 derivation and the hand-over to the Shelley ledger.

Preprod has no Byron transactions, so the Byron transaction rules are pinned by real mainnet blocks,
see `../mainnet/README.md`.

## Provenance and licence

Public Cardano preprod testnet chain data, fetched over node-to-node from the public IOG relay and
stored unchanged. The genesis files are the public network configuration. No private data and no
third-party code is included. The repository's Apache-2.0 applies to our own selection and packaging.
Not affiliated with or endorsed by any Cardano organisation (see `../../NOTICE`).

## tx-06869a76.hex

The full transaction `06869a76132665a0667fd26edbc7adc6cd241a794416777e752829653f4a9c3c` (Babbage,
protocol 8.0, 11,843 bytes, `[body, witnesses, is_valid, null]`) from preprod block 1,558,703
(slot 43,488,253, epoch 104, hash `884ec6b99acebb6f6c9769fdc80efd220ada2b09c677c552dffb8ade811f9eed`),
as hex. Source: Koios `tx_cbor` (`https://preprod.koios.rest/api/v1/tx_cbor`), fetched 2026-10-14.
Output 0 carries 2 policies and 501 assets: its value is 5001 bytes in definite-length CBOR but
5000 bytes as the ledger writes it (a map of more than 23 entries is indefinite-length), so it is
within `maxValueSize` 5000. Used by `PreConwayRulesSuite`.
