# Plutus validity upper bounds at the forecast horizon: reference verdicts

Each directory is one corpus recorded on a throwaway devnet whose block producers are the Haskell
`cardano-node` 11.1.3 (`cardano-reference-11.1.3:local`; node revision, image identity, the
recording command, the Scarab source commit and the recording time are in each `corpus.json`). The
transactions carry a Plutus script and a validity upper bound at, or just inside, the end of the
forecast horizon of the era history at the recorded tip.

| directory | era | protocol | cases |
|---|---|---|---|
| `alonzo-horizon` | Alonzo | 6.0 | upper bound past and inside the horizon (both accepted: Alonzo extends the epoch info linearly) |
| `babbage-horizon` | Babbage | 8.0 | upper bound past (refused) and inside (accepted) |
| `babbage-known-horizon` | Babbage | 7.0 | upper bound past the horizon with the next era's epoch configured (refused) |
| `conway-past-horizon` | Conway | 10.0 | upper bound past the horizon (refused) |

A corpus holds `corpus.json` (provenance and the SHA-256 of every case), `cases/` (the signed
transaction with the complete `ApplyTxErr` the reference sent over LocalTxSubmission, or its
acceptance), `states/` (the ledger state the transaction was judged against at the recorded tip),
`utxo/` (the outputs it spends) and `genesis/` (the genesis files of the cluster). The signing keys,
databases and logs of the cluster are not fixtures.

`PastHorizonReferenceSuite` replays every case through Scarab's own rules with the clock of the era
history at the recorded tip and requires the reference's bytes; a missing directory fails the suite
as an incomplete gate.

Provenance: the corpora were recorded from a working tree on `d14d42cad` (the reference-refusal
tooling of PR #411, `refusalcases` and `refusalcorpus`) with the horizon case builder and recorder
added on top of it, hence `scarabDirty: true`; the `command` field of each `corpus.json` names the
call that was run. That builder and recorder depend on the #411 tooling and are not part of this
tree; regeneration arrives with #411. A re-recording gives new genesis, timing and signatures, so it
replaces a corpus as a whole.
