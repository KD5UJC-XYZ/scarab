# Isolated PV9 Plutus spending profile

`isolated-conway-pv9-plutus-v3-spend-v1` is an explicit, lifetime-fixed testnet
admission profile. It is not complete ledger validation or general Plutus support.
Receipts continue to report `fullLedgerValidated=false`.

The profile accepts one registered Plutus V3 script under PV9 semantics C, one
confirmed enterprise script input with the bounded inline datum
`Constr 0 [beneficiary, minimumPayment]`, one disjoint enterprise key collateral
input, and one ADA-only enterprise key payout. It requires redeemer integer 7,
exactly 100,000 declared memory units and 30,000,000 declared steps, a successful
validity flag, and null auxiliary data. The collateral witness verifies the
original body hash. It is not a required signer in the script context, and the
collateral input is not an ordinary spending input.

The registered 369-byte script has SHA-256
`57fb50f08ffc1222cbe2b652db3dcfed0f714da98f8170cb104aee2bde4070f6`.
The 251-entry cost-model text has SHA-256
`6ab455d588e186649a6aae2761fec85ae5a2647cb002a2acb737604f698b21a2`.
Script/model admission precedes parsing. Arbitrary Flat scripts, additional
purposes, reference inputs/scripts, datum-hash lookup, minting, multiasset,
certificates, withdrawals, governance transactions, collateral return, and
invalid phase-two collateral transitions remain unsupported.

## Admission and state

`PlutusAdmission` composes original-envelope identity, phase-one checks, context
derivation, original redeemer-container integrity, and the bounded VM result.
Its private receipt binds the exact source state object, state/environment IDs,
validation slot, transaction, script, context, cost model, and execution budget.
VM success alone cannot construct an admission receipt. The application supplies
the fixed `Pv9SubmissionEvaluator`; HTTP callers cannot select a provider.

The execution fee takes one ceiling of the combined rational memory/step price.
Collateral is checked against the supplied fee, including overpayment. Successful
transition consumes the script input, preserves collateral, and adds the fee once.
Block admission checks aggregate declared execution units before VM evaluation.
The pool reserves both spending and collateral dependencies and revalidates them
against each new confirmed view using the same admission path.

Native bootstrap retains original parameter/genesis pins, checks native MemPack
in the reference-derived verified full seed against the independently acquired
whole UTxO, recomputes instantaneous stake,
and checks fee-inclusive supply conservation. Datum-aware decoding is selected
only by the explicit checked Plutus profile. Stake seed attachment, transition,
and restore preserve this profile binding. MemPack decoding reconstructs output
framing; it does not claim original whole-output encoding from MemPack alone.

The application derives system start and rational slot duration from the pinned
effective Shelley genesis. The supported fixture starts Conway at slot zero;
this single-era geometry is not authenticated historical epoch information.
Fractional-millisecond context endpoints are rejected rather than rounded.
Synthetic epoch crossing is explicitly unsupported for this profile.

## Evidence boundary

Public synthetic integration tests use signed envelopes and the real bounded
PV9 evaluator. They cover collateral preservation, fees, restore, stale receipt
binding, pool collateral conflicts, dependency invalidation, execution limits,
and typed HTTP failures. Synthetic state is not an acquired reference snapshot.

Local node acceptance additionally requires actual acquired datum MemPack parity,
Scala HTTP ingress and pool admission, TxSubmission2 relay, follower confirmation
with original body/witness equality, and same-epoch endpoint UTxO/fee/stake checks.
The [2026-10-10 local acceptance receipt](../reference/plutus-admission/live-receipt.json)
records an acquired datum-bearing prestate at slot 292 and confirmed inclusion at
slot 616. Original body/witness hashes matched; complete UTxO semantics and stake
agreed; collateral was preserved and fees increased from 200,000 to 500,000.
The tested spend used Scala HTTP, the pool, and TxSubmission2. Only funding used
reference CLI submission. All owned containers were removed after the comparison.

Two earlier attempts stopped before funding: a missing CLI text-envelope
description (corrected and independently CLI-checked), and a random first block
at slot 175 outside the unchanged pre-funding slot-100 budget. The successful
attempt froze at slot 24 before funding; the bootstrap bound remained slot 300.
These are separate retained observations, not an average or a hidden retry.

The debug epoch export suppresses its UTxO map; the MemPack evidence comes from
the reference-derived verified full seed bound to that export and whole-UTxO
acquisition. The receipt contains hashes and scoped results. Private keys, cluster state,
acquisition originals, and logs remain outside Git. Funding JSON observations
were separate acquisitions bracketed under observed quiescence. The result does
not establish full ledger validation, governance parity, epoch crossing, or
whole submitted-envelope equality from reconstructed block transactions.
