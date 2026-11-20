# Script contexts of Dijkstra transactions that the chain does not reach

Status: Current. Evidence for [#85](https://github.com/KD5UJC-XYZ/scarab/issues/85) (Dijkstra's script contexts on
the era types) and [#54](https://github.com/KD5UJC-XYZ/scarab/issues/54).

The reference is the build the public Leios testnet runs: cardano-node `b004fe0c`, with cardano-ledger
`9df9f98cce86c5f2195142b2c02dd9b39dd9dc7c`, ouroboros-consensus `2416493b` and plutus-ledger-api 1.71.0.0
(`261766ad`); [the oracle README](../../../scripts/oracle/leios-testnet/README.md) says how each was established.

The [chain context fixture](../leios-testnet-chain-contexts/README.md) holds the contexts of 200 transactions the
testnet's ledger accepted, every one collected. These 22 transactions are written by hand, with placeholder scripts
that are never evaluated, to reach what those do not: a Plutus V1 to V3 script in a Dijkstra transaction meeting
what its context cannot show, a guard's view of a whole transaction whose sub-transaction cannot be translated, a
guard's redeemer with no script, and a guard's view of a whole transaction whose levels give votes and current
treasury amounts, which the chain's guards never had; beside them, transactions of every language that are
collected.

## The cases

`scripts/oracle/leios-testnet/leios_context_refusal_cases.py` writes them, in the chain context fixture's
environment: protocol version 12.0, the cost models of Plutus V1 to V4 in force on the chain, its system start and
slot length, so that a Plutus V1 to V3 script is collected with its cost model. The answers are those of the chain
harness (`scripts/oracle/leios-testnet/chain/Main.hs`, `mkDijkstraStAnnTopTx`).

Each case's answer has a collection for its top level and one for each sub-transaction. At the top level 11 cases
are collected and 11 refused; one of the 11 is refused in its sub-transaction.

- Collected: Plutus V1, V2 and V3 scripts spending an output with a datum hash, with a reference input, a validity
  interval and a guard that is a key; a V3 script certifying a script DRep's registration beside a vote, a proposal
  and a treasury donation; V1 to V4 scripts minting in one transaction; a V3 script with a pointer address among the
  outputs, which its context shows.
- Collected: a Plutus V4 guard at the top level, whose view of the whole transaction (`transGuardingTopTxInfo`,
  `eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/TxInfo.hs:819-925`) folds the levels' votes, the
  sub-transactions' in order and then the top level's, by the right-biased union of `VotingProcedures`
  (`TxInfo.hs:868-869, 878`; `eras/conway/impl/src/Cardano/Ledger/Conway/Governance/Procedures.hs:438-440`), and
  takes the first current treasury amount present in that order (`TxInfo.hs:913-916`): one voter's votes on an
  action at three levels, Yes at the top level over No and Abstain in the sub-transactions, and on a second action
  the second sub-transaction's Abstain over the first's No; votes at two levels by another voter and on another
  action, all kept; a treasury amount in the second of two sub-transactions only, which the view gives; and amounts
  in the second sub-transaction and at the top level, of which the view gives the sub-transaction's. That last
  case's amounts differ, which the ledger's rules refuse (`DijkstraTreasuryValueMismatch`,
  `SubTreasuryValueMismatch`) and the collection does not check, so that the amount taken shows which.
- Collected at the top level, which runs no script, and refused in the sub-transaction: a V3 minting script in a
  sub-transaction (`UnsupportedScriptInSubTx`, `transFailUnsupportedScriptInSubTx`, `TxInfo.hs:521-532`).
- Refused by Dijkstra's guard of a V1 to V3 context (`guardDijkstraFeaturesForPlutusV1toV3`,
  `eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/TxInfo.hs:486-519`): direct deposits, account balance intervals,
  required top-level guards, a guard that is a script.
- Refused by Conway's guard of a V1 or V2 context (`guardConwayFeaturesForPlutusV1V2`,
  `eras/conway/impl/src/Cardano/Ledger/Conway/TxInfo.hs:375-404`): a vote, a proposal, a treasury donation, the
  current treasury.
- Refused otherwise at the top level: a DRep's registration under a V1 script (`CertificateNotSupported`, Alonzo's,
  carried through Babbage's and Conway's errors); a V4 guard whose sub-transaction has a pointer address
  (`SubTxContextError`, the sub-transaction itself running no script and collected); a V4 minting script beside the
  redeemer of a guard whose script is not provided (`ScriptHashNotFoundForPurpose`).

## Files

`cases.json` is the harness's input: the environment and, for each case, its name, its bytes as a block body holds
them and the outputs it spends and references. `results.json` is the harness's answer: for each case the `ToCBOR
PlutusWithContext` encoding of every context of the top-level transaction and of each sub-transaction, or the
collection's failure as the ledger shows it (`Show`), the top level's and each sub-transaction's. `provenance.json`
binds both, the image that answered (its immutable ID, without network), the digests of its harness, package, cabal
project and executable, which must be the build the chain context fixture's provenance accepted, the pinned ledger
and cardano-base checkouts unchanged, and the digests of the runner, the generator and the helpers it imports. The
provenance's `topCollected` and `topRefused` count the cases by their top level's collection alone. The verifier
requires every level of every answer to be collected with its contexts or refused with the ledger's failure, and
`scripts/tests/test_leios_context_refusals_oracle.py` shows it refuses a copy whose answers drop a diagnostic.

```sh
python3 -I scripts/oracle/leios-testnet/leios_context_refusal_cases.py > fixtures/reference/leios-testnet-context-refusals/cases.json
python3 -I scripts/oracle/leios-testnet/chain/refusals.py --evidence <a directory outside the checkout>
python3 -I scripts/oracle/leios-testnet/chain/refusals.py --verify-only
```

`DijkstraPlutusContextSuite` reads it with Dijkstra's script contexts on the era types, each transaction annotated by
`mkDijkstraStAnnTopTx` and `mkDijkstraStAnnSubTx`: every level of the 22 cases, each context's serialized arguments
compared byte for byte and the rest of its `PlutusWithContext` record field by field, and each refusal as its typed error,
read against the recorded failure.

## Licence

The fixture's original material, the synthetic inputs Scarab generates (`cases.json`) and the recorded results
(`results.json`, with `provenance.json`), is distributed under the project's licence, Apache-2.0
([LICENSE](../../../LICENSE)). Its attributions:

- The inputs are synthetic: Scarab generates them (`leios_context_refusal_cases.py`, with the CBOR encoders of
  `scripts/oracle/conway-decoding/cases.py`), with placeholder scripts and hashes, and they hold no chain data.
- The environment the generator reuses, the protocol version, the cost models in force, the system start and the
  slot length, is the public Leios testnet's as the [chain context fixture](../leios-testnet-chain-contexts/README.md)
  records it: public parameters of a test network.
- The verdicts are produced by the unchanged cardano-ledger (Apache-2.0) of the pinned Leios testnet build, run in
  the chain harness image built from it; the image is not redistributed. [NOTICE](../../../NOTICE) records the
  corpus with the other captured and derived test data.
- The oracle's raw output is not published beyond `results.json`.
