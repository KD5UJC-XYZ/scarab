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

## `redeemer-index.jsonl`

The reference node's verdicts on the redeemer-index cases of a `devnet.py e2e` run against the Haskell
`cardano-node` 11.1.3 cluster (`redeemer_index` in `scripts/devnet/erarefusal.py`), replayed by
`RedeemerIndexReferenceSuite` (no Docker needed). One JSON object per line, one per transaction, in the order
the run sent them; `label` starts with the era:

| `label` (after the era) | Transaction |
|---|---|
| `missing-redeemers-every-purpose` | spends an output locked by the Plutus V1 script, withdraws 0 from its stake account, delegates the account and mints under the script as policy, with no redeemer |
| `repeated-certificate-redeemer-0`, `-redeemers-0-1`, `-no-redeemer` (Alonzo, Babbage) | two identical delegations of the script's stake credential, with a redeemer at certificate index 0, at 0 and 1, or none |
| `distinct-certificates-redeemer-0` (every era), `-redeemers-0-1` (Conway) | two delegations of the credential to two pools, with a redeemer at index 0, or at 0 and 1; Conway has no repeated pair, since its body cannot repeat a certificate |

`verdict` is `accepted` or `refused`; a refused line has `reason`, the raw `ApplyTxErr` of LocalTxSubmission in
hex, and `cli`, what `cardano-cli transaction submit` printed for a second submission. `tx` is the signed
transaction CBOR in hex as submitted, `spent` the outputs it spends and its collateral (output CBOR by
`txid#index`, rebuilt by the run from the address, coin and assets the reference's UTxO holds, and for the
script-locked output the datum hash the run's setup gave it), and
`costModelPlutusV1` the cost model the reference had in force. `node`, `image`, `commit`, `dirty`, `command` and
`protocol` (era, slot, block hash and protocol version, read from the reference with the verdict) are the provenance.
The test builds a chain from `spent`, the credential and the pools the certificates name, at the recorded protocol
version and cost model, and requires the node's verdict to be the recorded one and a refusal to encode as exactly
`reason`. A recording is complete only with every case once; `refused-fixture --redeemer-index` writes nothing
otherwise, and the suite requires the same set.

Licence: the transactions were built for a throwaway local devnet, signed with keys generated for that run and
discarded with it, and the verdicts are what the unmodified reference node answered. They are public data with no
secrets, under Apache-2.0, as `NOTICE` records.

Regenerate (every line changes: keys, inputs and slots differ from run to run):

```sh
python3 scripts/devnet.py --name scarab-eras e2e                  # the run directory stays until `down`
python3 scripts/devnet.py --name scarab-eras refused-fixture --redeemer-index
python3 scripts/devnet.py --name scarab-eras down
```

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
