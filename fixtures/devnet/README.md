# Devnet transactions the reference refused

`refused-txs.jsonl` holds transactions from a `devnet.py soak --governance-extended` run against the
Haskell `cardano-node` 11.1.3 cluster, replayed by `DevnetRefusedTxSuite` (no Docker needed). One JSON
object per line, `scenario` says what it is for:

| `scenario` | Lines | What the test checks |
|---|---|---|
| `vrf` | a `context` line (the two genesis pools whose VRF key hashes the sequence uses), then ten pool registrations in the order the devnet sent them, each `outcome` `accepted` or `refused` | the accepted ones apply and the refused ones fail with `VRFKeyHashAlreadyRegistered`, the reference's predicate failure, at protocol version 11 |
| `unelected` | votes of committee hot keys the reference refused with `UnelectedCommitteeVoters` | the same transactions fail with that rule at protocol version 11 |
| `refoverlap` | a transaction whose reference input is also an input, refused with `BabbageNonDisjointRefInputs` at protocol version 10 | refused at 10 by the same rule, applied at 11 (no script runs) |

`tx` is the signed transaction CBOR in hex, exactly as submitted; `error` is what `cardano-cli transaction
submit` printed. The ledger state around a transaction is not recorded: the test rebuilds only what decides
the rule (the pools that hold VRF key hashes, no committee, the protocol version) and gives the inputs the
value that balances the transaction.

Regenerate from the run directory of a finished extended soak:

```sh
python3 scripts/devnet.py --name scarab-devnet soak --seconds 900 --governance-extended   # leaves the run directory
python3 scripts/devnet.py --name scarab-devnet refused-fixture                              # writes this file
python3 scripts/devnet.py --name scarab-devnet down
```

A Plutus V3 transaction refused with `CollectErrors (BadTranslation ReferenceInputsNotDisjointFromInputs)` is
not kept here: replaying it needs the devnet's cost model for the script data hash; `ConwayRulesSuite` builds
the same shape instead.

## `lsq/`

The genesis directory of a devnet and the reference node's answers to the LocalStateQuery queries
that do not depend on activity, replayed by `QueryGoldenSuite`; see [lsq/README.md](lsq/README.md).

## A block with more than 23 transactions

`burst-24-txs-block.hex` is one Conway block, in hex, that the Haskell `cardano-node` 11.1.3 reference cluster
forged on 2026-11-14 during `devnet.py load --mode parallel --burst-size 80`. It holds 24 transactions, so its
transaction bodies and witness sets are written as indefinite-length lists (`0x9f`), while its empty auxiliary
data map and invalid-index list stay definite (#987). The block is 5674 bytes, SHA-256
`113c27f92600771cc998fbcfd717eb922364a293ffdd396bdea907cad3811452`; it was copied from node 1's volatile DB.
`ForgeSuite` rebuilds its body from its own transactions with `BlockBuilder` and checks the size and the body
hash against the block.

Regenerate (the block, its transaction count and its bytes differ from run to run):

```sh
python3 scripts/devnet.py --name scarab-devnet up
python3 scripts/devnet.py --name scarab-devnet load --mode parallel --burst-size 80 --bursts 2
# copy node1's db/volatile/blocks-*.dat out of the reference container and pick a block with 24 or more transactions
python3 scripts/devnet.py --name scarab-devnet down
```
