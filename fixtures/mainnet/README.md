# Mainnet Byron fixtures

Captured with `xyz.kd5ujc.scarab.node.tools.CaptureMain` from `backbone.cardano.iog.io:3001` (magic 764824073) on
2026-10-10 and 2026-10-11.

| File | Contents |
|---|---|
| `byron-ebb-header.hex` | Raw header of the genesis epoch-boundary block (block 0). Its full block is 648 KB, so only the header is kept. |
| `byron-main-blocks.hex` | The next three blocks (Byron main blocks, tag 1), full `[1, block]` encoding, contiguous. |
| `byron-tx-blocks.hex` | Three Byron main blocks with transactions, full `[1, block]` encoding, not contiguous: slot 3313 (epoch 0, block 3314: the redemption of an AVVM balance, one redeem witness, no fee), slot 432001 (epoch 20, one transaction with a verification-key witness) and slot 432004 (epoch 20, two transactions, one with two inputs). Each was captured with `--blocks 1 --from <parent slot>.<parent hash> --byron-epoch-slots 21600`. |
| `byron-tx-inputs.json` | The outputs those transactions spend (transaction id, index, address encoding, lovelace), as the Koios API reports them (`tx_info`; its `outputs` array is not in index order, the `tx_index` field is). The AVVM entry is the genesis balance of the redeem key. |
| `byron-genesis-trimmed.json` | The public mainnet `byron-genesis.json` (https://book.play.dev.cardano.org/environments/mainnet/) with `avvmDistr` cut to the one entry the slot 3313 block spends (the full file has 14,505 entries, 1 MB). Its canonical hash is therefore not the real genesis hash. |
| `byron-large-address-block.hex` | The Byron main block at slot 1,769,882 (epoch 81, hash `f277604972be4b11...`, 8.6 KB), full `[1, block]` encoding: the block where the first mainnet sync from genesis stopped. Its one transaction (bc61865d) pays an address of 7,689 bytes. Captured with `--blocks 1 --from 1769881.7bc92640368f07ccbe62b615c2bd8ed59da10a0624248dca67b89d0442d38161 --byron-epoch-slots 21600` on 2026-10-16. |
| `byron-large-address-inputs.json` | The output that transaction spends (3,000,000 lovelace), from the Koios API (`tx_utxos`), in the form of `byron-tx-inputs.json`. |
| `byron-tx-c1092b9c-block.hex` | The Byron main block at slot 4,105,943 (epoch 190, hash `ff78a9ec7703935d...`, 1.5 KB), full `[1, block]` encoding: it holds transaction c1092b9c, whose output 0 (12,932,000,000 lovelace) mainnet stop 4 lost at the Byron to Shelley handover. Captured with `--blocks 1 --from 4105942.3015c21d6b5fd44465fa06ad3058e263d690aae397c6a95df5b2c72c8373f225 --byron-epoch-slots 21600` on 2026-10-16. |
| `byron-tx-c1092b9c-inputs.json` | The output that c1092b9c spends (c6f9c891...#0, 7,785,462,486,762 lovelace), from the Koios API (`tx_info`), in the form of `byron-tx-inputs.json`; the address is the base58 address decoded. |
| `shelley-pool-network-block.hex` | The Shelley block at slot 4,495,760 (epoch 208, protocol version 2, hash `fb4e87a00f0eea0d...`, 8 KB), full `[era, block]` encoding: the block where the second mainnet sync from genesis stopped. Transaction 49be5965 registers a pool whose reward account is on network 0. Captured with `--blocks 1 --from 4495740.c8a3bd53fc4c76491d911e1f2f4c8eb5baf7ef8848fbefda7820ab846854ec90 --byron-epoch-slots 21600` on 2026-10-16 (the previous block's point from the Koios API, `blocks`). |
| `mary-garbage-address-block.hex` | The Mary block at slot 39,249,764 (epoch 288, block 6,203,514, hash `24dcc9a0ecbcbd9c...`, six transactions), full `[era, block]` encoding: the block where the sixth mainnet sync from genesis stopped. Output 0 of transaction 369826b2 is a base address of 57 bytes followed by 21 bytes. Captured with `--blocks 1 --from 39249748.3652c26eb2b4f9d6e0f3153e5d50bc2134d2d0479e3b327131c30eb5a9f0161b --byron-epoch-slots 21600` on 2026-10-16 (the previous block's point from the Koios API, `blocks`). |
| `mary-pointer-tx-9a47e091.hex` | Transaction 9a47e091709e4fe1 (a Mary transaction of block 5,567,164, hash `afcb6d12...`, slot 26,366,727, epoch 258, protocol 4.0, 11 transactions), the CBOR `[body, witnesses, auxiliary data]` of one transaction (270 bytes) as the Koios API reports it (`block_tx_cbor`), fetched 2026-10-17 by scanning the blocks from the last good snapshot of the mainnet follower to its first refusal for the bytes of the pointer. Output 0 is 50,000,000 lovelace at a pointer address whose pointer is slot 16,292,793,057, transaction index 1,011,302, certificate index 20. |

`ByronMainnetSuite` decodes the blocks, checks the body proofs against the headers, derives the AVVM
redeem address and the genesis UTxO (the pseudo transaction id and address equal what the chain
spent), and validates every transaction: witness signatures under the Byron signing tags and the
protocol magic, address roots, value conservation and the minimum fee.

`ByronLargeAddressSuite` decodes the block at slot 1,769,882, checks its proofs, decodes the 7,689-byte address and validates the transaction in full.

`ByronHandoverUtxoSuite` (node tests) applies transaction c1092b9c of the block at slot 4,105,943 with the Byron ledger and carries its outputs through the handover to Shelley over a UTxO store.

`MainnetPoolNetworkSuite` decodes the block at slot 4,495,760 and applies its pool registration at protocol versions 2 to 8. `PoolRelaysSuite` reads the relays of that registration (one IPv4 relay, `03652f85`, port 3001), the one real registration with relays in the repository.

`pool-relays-ipv6.json` holds the relay sequences of two mainnet pool registrations with an IPv6 relay (transactions 812dea7e..., epoch 577, pool19yf67fka...; 602f0b7f..., epoch 646, pool1zj4h7kc6...), the exact CBOR items out of the transactions as the Koios API's `tx_cbor` gives them, beside the address the API's `pool_updates` shows (fetched 2026-10-17). `fixtures/preview/pool-relays-ipv6.json` has one Preview registration (1ce619e0..., epoch 1307) in the same form. `PoolRelaysRegistrationSuite` (node tests) decodes them; they pin the byte order of an IPv6 address in a registration: the four 32-bit words of the address, each little-endian (`2a02:c206:2242:2138::1` is `06c2022a 38214222 00000000 01000000`).

`MainnetGarbageAddressSuite` (ledger tests) and `MainnetGarbageAddressStoreSuite` (storage tests) decode the block at slot 39,249,764 and read the address of transaction 369826b2 in each era, and put its output through the UTxO stores and the Haskell UTxO codec.

`MainnetPointerSuite` (ledger tests), `MainnetPointerStoreSuite` (storage tests) and `MainnetPointerSnapshotSuite` (node tests) decode transaction 9a47e091 in each era, put its output through the stores and the Haskell codecs, and write the V4 snapshot of a state that holds it.

`MainnetAllegraReservesSuite` (ledger tests) and `AllegraRedeemStoreSuite` (node tests) use the AVVM balance of `byron-genesis-trimmed.json` (and a verification-key output of `byron-tx-inputs.json`) as the redeem outputs that the boundary entering Allegra returns to the reserves (mainnet stop 5); the reward inputs of the first suite are public chain data from the Koios API, cited in the suite.

`genesis/` holds the public mainnet node configuration from
https://book.play.dev.cardano.org/environments/mainnet/ unchanged, downloaded 2026-10-14: `config.json`,
`topology.json`, `shelley-genesis.json`, `alonzo-genesis.json` and `conway-genesis.json`. The Byron genesis file
(1 MB) is not kept: `ConfigSuite` checks the Shelley, Alonzo and Conway hashes against the files, and the
Byron hash against the full file when one is present at .workspace/data/2026-10-14-configs/mainnet.

## Provenance and licence

Public Cardano mainnet chain data, fetched over node-to-node from the public IOG backbone relay and
stored unchanged. `byron-tx-inputs.json` contains data from the Koios public API
(https://koios.rest), which itself reports public chain state; Koios is credited as the source and is
not affiliated with this project. Blockchain data is public; no private data and no third-party code
is included. The files carry no separate licence beyond the repository's Apache-2.0 for our own
selection and packaging. Not affiliated with or endorsed by any Cardano organisation (see
`../../NOTICE`).
