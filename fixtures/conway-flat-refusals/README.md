# Conway judgments as the flat rules wrote them

Status: Current — the expected values of `ConwayLedgerChecksSuite`, `ConwayFailureCborSuite`,
`ConwayMempoolRunSuite`, `EraUtxoSuite`, `PreviewConwayBlockLedgersSuite` and `PreviewConwayRulesSuite`
(#85). Licence: Apache-2.0, this project's (see `NOTICE`).

Before Scarab deleted its flat Conway rules (the flat MEMPOOL, then its LEDGER and LEDGERS) and the flat rules'
Conway refusal encoder (`PredicateFailureCbor`), the suites that held Conway's era rules and their encoder (`ConwayFailureCbor`)
to them ran once more, and each recorded, for every judgment it compared, what the era side gave after asserting
that the flat side gave the same. The era side that stays must keep giving it. A digest is the first 16 hex
digits of the SHA-256 of the bytes it names.

- `ledger-checks.jsonl`: one line per Conway case of the refusal corpus (`fixtures/reference/refusals`), 84 in
  all: `case`, the case's label; and `r`, 48 cells separated by spaces, one for each judgment of the era-typed
  LEDGER one level below MEMPOOL: the protocol version the case was recorded at, then 9, 10 and 11; under each,
  the builds 11.1, 11.2 and Leios (`ReferenceBuild.values`); under each, the validation `All` then `NonStatic`;
  under each, depth 0 then 1. A refusal's cell is the digest of its `ApplyTxErr` bytes, which both encoders
  wrote; an admission's is `-`. 3,744 refusals and 288 admissions. Recorded at Scarab commit `84bf3a1cd`.
- `mempool-run.jsonl`: one line per Conway case of the refusal corpus, 84: `case`; and `r`, 24 cells, one for
  each root judgment of MEMPOOL: the version the case was recorded at, then 9, 10 and 11; under each, the three
  builds; under each, the application (`applyTx`) then the application again of the transaction held as
  validated in that state (`reapply`). The flat MEMPOOL and the era MEMPOOL gave the same failures in the same
  order, or the same ledger state (where the 11.2 and Leios builds refuse a withdrawal in LEDGER below version
  11, the flat failures with CERTS's refusal of it replaced by LEDGER's, `MovedWithdrawals.expected`). A
  refusal's cell is `R:` and the digest of its `ApplyTxErr` bytes, every failure in its order; an admission's
  is `A:` and the digest of the ledger state it leaves in the Haskell state's encoding at protocol version 9
  (`ledgerState` of the case's layout, `ReferenceRefusalSuite.stateCodecs`). 2,016 judgments: 1,884
  refusals and 132 admissions. Recorded at Scarab commit `84bf3a1cd`.
- `failure-constructors.jsonl`: one line for each case `ConwayFailureCborSuite` compared with the flat
  encoder's bytes, and for no other, 278 in all, each id once: `id`, the case's id (its group and its
  position in the group, as the suite names it); and `bytes`, the digest of its `ApplyTxErr` bytes, which both
  encoders wrote. The flat form
  of a case is its failures' (`ConwayLedgerPredFailure.toStored`, since deleted), or, where it holds what only the
  transaction gives, the flat failure of the same values the suite compared it with (a context's error, a
  recorded proposal's purpose). The suite's other checks have other oracles and are not here: the reference
  node's recorded reasons, the bytes derived by hand from the reference's instances (the integers past 64
  bits among them, which the flat encoder could not write), the reference's order of addresses and a pool
  metadata's bytes. Recorded at Scarab commit `3aaa942aa`, the parent of the deletion's.

- `ledger-judgments.jsonl`: one line for each judgment of Conway's era LEDGER or LEDGERS that a suite compared
  with the flat LEDGER or LEDGERS, 257 lines, each id once: `id`, the judgment's (its suite's prefix, then its
  case); and `r`, its cells separated by spaces. A refusal's cell is `R:` and the digest of its `ApplyTxErr` bytes,
  every failure in its order; an admission's is `A:`, the digest of the ledger state it leaves in the Haskell
  state's encoding at protocol version 9 (of the case's layout under `ledger-checks/`, 11.1's elsewhere), then `/`
  and the counters of the scripts it ran: succeeded, failed, run; a judgment with no verdict for want of a
  script's is `N`. The flat rules gave the same failures in the same order, or the same state and counters (with
  the moved withdrawals of `mempool-run.jsonl`). The prefixes:
  - `ledger-checks/`, 84 lines: each Conway case of the refusal corpus, 24 cells, the era LEDGER at the root at the
    versions, then builds, then policies of `ledger-checks.jsonl` (1,872 refusals, 144 admissions);
  - `gov-after-certs/`, 1 line: `ConwayLedgerChecksSuite`'s DRep that unregisters and votes, 3 cells, one under
    each build (`ReferenceBuild.values`);
  - `era-utxo-ledger/`, 140 lines: `EraUtxoSuite`'s 70 submissions, as submitted and with the flag flipped, 6
    cells, the era LEDGER at the root under each build, then policy (453 refusals, 387 admissions);
  - `preview-blocks/`, 10 lines: `PreviewConwayBlockLedgersSuite`'s three Preview blocks, the era LEDGERS on each
    prefix (`<file>:<line>/<k>`: the state after it, the counters of its last transaction), the block with its
    first transaction again (`/repeated`: LEDGER at the root, one level down, and LEDGERS), and the proposal
    under another guardrails script (`/guardrails`);
  - `preview-rules/`, 22 lines: `PreviewConwayRulesSuite`'s Preview transactions, the era LEDGERS on each in the
    world of its test (`proposal/`, `spend/`, `vote/`), and each proposal under another guardrails script
    (`guardrails/`).

  Recorded at Scarab commit `57ae6eb18`, the parent of the flat LEDGER's deletion. The suites whose transactions
  are built with keys drawn afresh on each run (`ConwayLedgerSuite`, `ConwayRulesSuite`,
  `ConwayGovernanceSuite`) have no judgment the same from run to run, and are not here: their cases name the era
  failures they expect, which the flat LEDGER gave on every one before it was deleted (but for
  `EraNotVersionUtxowSuite`'s Conway case, ported after, which keeps its typed expectations).

Commit `84bf3a1cd` is the base this work started from; `3aaa942aa` the last of it with the flat encoder. The
tests that wrote these lines and their throwaway checkouts were removed after. The digests are Scarab's own,
not the reference's: they hold the era side to the results it replaced, which were held to the reference's
where it recorded them (`ReferenceRefusalSuite`). A case the suites no longer judge, or one they judge that is
not here, fails; record it again only from a reviewed change. No secret is held.
