# Conway judgments as the flat rules wrote them

Status: Current — the expected values of `ConwayLedgerChecksSuite`, `ConwayFailureCborSuite` and
`ConwayMempoolRunSuite` (#85). Licence: Apache-2.0, this project's (see `NOTICE`).

Before Scarab deleted its flat Conway rules (the flat MEMPOOL and its LEDGER) and the flat rules' Conway refusal
encoder (`PredicateFailureCbor`), the suites that held Conway's era rules and their encoder (`ConwayFailureCbor`)
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
  of a case is its failures' (`ConwayLedgerPredFailure.toStored`), or, where it holds what only the
  transaction gives, the flat failure of the same values the suite compared it with (a context's error, a
  recorded proposal's purpose). The suite's other checks have other oracles and are not here: the reference
  node's recorded reasons, the bytes derived by hand from the reference's instances (the integers past 64
  bits among them, which the flat encoder could not write), the reference's order of addresses and a pool
  metadata's bytes. Recorded at Scarab commit `3aaa942aa`, the parent of the deletion's.

Commit `84bf3a1cd` is the base this work started from; `3aaa942aa` the last of it with the flat encoder. The
tests that wrote these lines and their throwaway checkouts were removed after. The digests are Scarab's own,
not the reference's: they hold the era side to the results it replaced, which were held to the reference's
where it recorded them (`ReferenceRefusalSuite`). A case the suites no longer judge, or one they judge that is
not here, fails; record it again only from a reviewed change. No secret is held.
