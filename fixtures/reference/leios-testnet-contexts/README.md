# PlutusV4 script contexts as the Leios testnet's build collects them

Status: Current. Evidence for [#54](https://github.com/KD5UJC-XYZ/scarab/issues/54).

The reference is the build the public Leios testnet runs: cardano-node `b004fe0c`, with
cardano-ledger `9df9f98cce86c5f2195142b2c02dd9b39dd9dc7c` and plutus-ledger-api 1.71.0.0 (`261766ad`,
the version the release's node binary links). [The oracle README](../../../scripts/oracle/leios-testnet/README.md)
says how each was established. In that build a PlutusV4 script's argument is the `ScriptContext` of
`PlutusLedgerApi.V4`, built by `EraPlutusTxInfo 'PlutusV4 DijkstraEra`
(`eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/TxInfo.hs:574-1058`).

`cases.json` holds eleven top-level transactions with the outputs their inputs and reference inputs
spend, written by `scripts/oracle/leios-testnet/leios_context_cases.py`:
- `everything`: PlutusV4 scripts for spending (an inline datum), minting (positive and negative
  quantities), a certificate, a withdrawal (a reference script), a vote and a proposal, and every
  body field the PlutusV4 transaction information shows: outputs with each datum form and a
  reference script, a retirement certificate, withdrawals, a validity interval, guards, required
  top-level guards with and without a datum, direct deposits, each form of balance interval, votes
  of two voters, a proposal without a script, the treasury and a donation;
- `guarded`: a guard script at the top level with two sub-transactions, the first requiring the
  guard with a datum and minting under its own PlutusV4 policy, the second withdrawing with its own
  validity interval; the guard's context carries the whole transaction (`TopTxInfo`);
- `governance`: PlutusV4 scripts for a parameter change's guardrail (an update of numbers,
  ratios, prices, execution units, voting thresholds, an unset pledge leverage, the pool margin and
  the Peras bootstrap round), a script DRep's registration and a script committee member's
  authorisation, beside every other certificate kind and governance action;
- `sub-transaction scripts`: PlutusV4 scripts inside a sub-transaction only, a guard (whose
  context has no view of the whole transaction) and two spends of outputs with a datum hash, the
  datum carried for one and not for the other; withdrawals, direct deposits, required guards and
  balance intervals keyed by key and script credentials of both networks; a parameter change of
  cost models, DRep thresholds and a pledge leverage;
- seven refusals: a pointer address and a Byron address in an output, an input whose output is
  unknown, a reference input that is also an input, a redeemer that names no purpose, a script with
  no redeemer, and a redeemer of a withdrawal with no script.

`results.json` is the oracle's output for them (`kind` "context"): the contexts the build collects
before it evaluates the scripts (`mkDijkstraStAnnTopTx`) at protocol version 12.0, slots of one
second from the POSIX epoch, each the `ToCBOR PlutusWithContext` encoding, or the collection's
failure. The scripts are placeholders that are never deserialised or evaluated.
`DijkstraScriptContextsSuite` requires the same arguments, in the same order, and the same refusals;
`DijkstraContextWireSuite` requires each PlutusV4 context's `PlutusWithContext` bytes
(`PlutusFailureCbor`) to equal the reference's.
