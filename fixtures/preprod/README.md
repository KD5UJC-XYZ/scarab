# Preprod testnet fixtures

Raw node-to-node BlockFetch blocks (`[era_tag, block]`), one hex-encoded block per line, captured
with `xyz.kd5ujc.scarab.node.tools.CaptureMain` from the public relay `preprod-node.play.dev.cardano.org:3001`
(network magic 1) on 2026-10-11:

```sh
java -cp "$(cat node/target/runtime-classpath.txt)" xyz.kd5ujc.scarab.node.tools.CaptureMain capture \
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

## tx-2edd684f.hex and plutus/tx-2edd684f.json

The full transaction `2edd684fb762fb4cc464367aa5a01b5e129e202e41b68f755a4e28d9ea293802` (Conway,
protocol 10.0, 5,329 bytes, `[body, witnesses, true, auxiliary_data]`) from preprod block 3,050,799
(slot 80,239,053, epoch 189, hash
`be1582397ea3968930c07c48272e4131d9510c13acecd70ff38034e165361f8d`), as hex. A Hydra head fanout (Plutus
V2, reference script) with a mint of the head tokens. Source: Koios `tx_cbor`, fetched 2026-10-14
(`https://preprod.koios.rest/api/v1/tx_cbor`). The outputs carry inline datums with constructor
indices of 2^64 - 1, 1313480702 and 2025506762; it is the transaction on which the first preprod sync
of the Scala node stopped (`ValidationTagMismatch`, see `docs/findings.md`).

`plutus/tx-2edd684f.json` is the case file of `PlutusCase` (the format of `fixtures/preview/plutus`):
the body, witnesses and auxiliary data, the resolved inputs, reference inputs and collateral input
and the cost models in force. The outputs are rebuilt from Koios `tx_info` (`_inputs`, `_scripts`,
`_bytecode`; addresses from the payment credential, enterprise form; the reference script's hash was
checked against the bytes) and the cost models are the V1, V2 and V3 arrays of Koios `epoch_params`
for epoch 189 (166, 175 and 297 parameters). The clock has one sample, slot 80,239,018 (the lower
bound of the validity interval) at POSIX 1,735,922,218,000 ms. Used by `PreprodConwayRulesSuite`.

## tx-0c22edee.hex and tx-0c22edee-inputs.json

The full transaction `0c22edee0ffd7c8f32d2fe4da1f144e9ef78dfb51e1678d5198493a83d6cf8ec` (Conway,
280 bytes, `[body, witnesses, true, null]`) from preprod block 3,158,876 (slot 83,038,792, epoch 196,
hash `a24ef424b670507eccd594170385a4da78abb6e1470637a9fe27a584f68c9fc6`), as hex. Source: Koios
`tx_cbor` and `tx_info` (`https://preprod.koios.rest/api/v1/`), fetched 2026-10-15. It spends two
5 ADA Byron outputs (inputs file: transaction id, index, address bytes, lovelace) whose address root
`533bcb8d...` is the blake2b-224 hash of the vkey of its one vkey witness; there is no bootstrap
witness. It is the transaction on which the preprod sync stopped (`MissingBootstrapWitnessesUTXOW`,
see `docs/findings.md`). Used by `PreprodByronKeyWitnessSuite`.

## tx-97b31e35.hex and plutus/tx-97b31e35.json

The full transaction `97b31e354e1946203be90ae78cc1cba21a70a5cbd7b39b6b82d273a98e323531` (Conway,
protocol 10, `[body, witnesses, true, auxiliary_data]`) from preprod block 3,498,869 (slot 92,114,397,
epoch 217, hash `40b4012f8d5a38e24ce5bcce74fc214c9f667d24623349919e046dcf08e9a450`), as hex. Four
inputs, three of them script-locked (Plutus V2, reference scripts), one collateral input. A script
calls `verifyEcdsaSecp256k1Signature` with an all-zero signature (the first sync stop of
epoch 217, see `docs/findings.md`). Source: Koios `tx_cbor`, `utxo_info` (inputs, reference inputs,
collateral; extended) and `epoch_params` for epoch 217 (V1, V2, V3 cost models of 166, 175, 297
parameters), fetched 2026-10-15 (`https://preprod.koios.rest/api/v1/`). The case file has the format
of `plutus/tx-2edd684f.json`; the clock has one sample, the validity upper bound, slot 92,114,852.
Used by `PreprodSecp256k1RulesSuite`.

## tx-aee75c1c.hex and plutus/tx-aee75c1c.json

The full transaction `aee75c1c595f90f893717d0186a96f09abf28579f08b76e412f327fa36bebf9b` (Conway,
protocol 10.0, 2,463 bytes, `[body, witnesses, true, null]`) from preprod block 4,045,972 (slot
105,654,302, epoch 248, hash `3a3e395f8dbf0ee1c8aac4436a1c3a0b2835fd4d46e6d17b49c174dc8befd7a5`), as
hex. One input, two outputs, one vkey witness, no redeemers. Output 0 carries the Plutus V2
reference script `5272e7132a75d7e314a75b1107b8a76a7fc0c284e48aa0b016251f7a`, 2,156 bytes: the
1,078-byte CBOR byte string of the flat program twice, which the reference ledger accepts (bytes
after the item are ignored for V1 and V2) and Scalus `fromCbor` rejects. The sync stopped on it
(`MalformedReferenceScripts`, see `docs/findings.md`). Source: Koios `tx_cbor`, `script_info`,
`utxo_info` (the one input, a plain ADA output rebuilt as `[address, lovelace]`) and `epoch_params`
for epoch 248 (V1, V2, V3 cost models), fetched 2026-10-15 (`https://preprod.koios.rest/api/v1/`).
Case file format as `plutus/tx-2edd684f.json`. Used by `PreprodMalformedRefScriptSuite`.

## tx-f90dce57.hex and tx-f90dce57-inputs.json

Preprod transaction `f90dce5765108da976abdbb9fc618f9a6ffd9fa4d93b2f288eed1808545424c9` (block
`bac1660208e7a8f63fc7caf97cfefcd3066a517d3ca4d971e1b316d1e43708e5`, height 5,183,974, slot
133,883,340, epoch 313, Conway, 23 transactions, 16,382 bytes). Its witness set holds one native
script, `all [all [all [ ... sig ]]]` nested 5,383 `all` levels deep (CBOR depth 10,772), which the
one input's script address (`ff3efca6...`) is the hash of. The Scala node's first full preprod sync
stopped at this block (`depth limit exceeded`, `docs/findings.md`); the Haskell nodes accepted it.
Source: Koios `block_tx_cbor` for the block, `tx_info` for the input (a plain 4 ADA output at the
script address, rebuilt as `[address, lovelace]`), fetched 2026-10-14
(`https://preprod.koios.rest/api/v1/`). Used by `DeepNestingSuite`.

## tx-b1db2a41.hex and tx-b1db2a41-inputs.json

Preprod transaction `b1db2a411cb651a413840d3c8b112895a5bda2519a8ba6a372b8dd1ffc7746c2` (block
`8ddb2e9618495b11dc5bf2820d177ec38453faaf81afbfdb02ab99a7ebc9e2f8`, height 5,184,855, slot
133,902,219, epoch 313, Conway, 163 bytes). Its witness set holds one native script,
`[3, -1, [sig 3118644a..., sig e07caf4b...]]`: `script_n_of_k` with `n` = -1 (CBOR `20`), which
the reference ledger decodes as an `Int` and which always holds. No vkey witnesses. The one input
(`fa72e22e...#0`, 2 ADA) sits at the script address `70678f1c...`, the hash of that script. The
Scala node's first full preprod sync stopped at this block (`script n: expected unsigned integer`,
`docs/findings.md`); the Haskell nodes accepted it. Source: Koios `block_tx_cbor` for the block and
`utxo_info` for the input (rebuilt as `[address, lovelace]`), fetched 2026-10-15
(`https://preprod.koios.rest/api/v1/`). Used by `PreprodNOfKSuite`.
