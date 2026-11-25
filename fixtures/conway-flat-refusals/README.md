# Conway refusals as the flat rules' encoder wrote them

Status: Current — the expected values of `ConwayLedgerChecksSuite` and `ConwayFailureCborSuite` (#85).

Before Scarab deleted the flat rules' Conway refusal encoder (`PredicateFailureCbor`) with the flat MEMPOOL, the
two suites that held Conway's era encoder (`ConwayFailureCbor`) to it ran once more and recorded the bytes both
encoders wrote; each suite had asserted the two equal. The era encoder that stays must keep writing them.

`ledger-checks.jsonl` holds one line per Conway case of the refusal corpus (`fixtures/reference/refusals`), 84 in
all: `case`, the case's label; and `r`, 48 cells separated by spaces, one for each judgment of the era-typed LEDGER
one level below MEMPOOL the suite makes of it, in this order: the protocol version the case was recorded at, then
9, 10 and 11; under each, the builds 11.1, 11.2 and Leios (`ReferenceBuild.values`); under each, the validation
`All` then `NonStatic`; under each, depth 0 then 1. A refused judgment's cell is the first 16 hex digits of the
SHA-256 of its `ApplyTxErr` bytes, an admitted one's is `-`: 3,744 refusals and 288 admissions.

`failure-constructors.jsonl` holds one line per failure list `ConwayFailureCborSuite` wrote (217): `failure`, the
first 16 hex digits of the SHA-256 of the list's `toString` (UTF-8), and `bytes`, those of the SHA-256 of its
`ApplyTxErr` bytes. They are the constructors of Conway's LEDGER tree the suite builds, those the corpora do not
reach among them, and the failures the flat form could hold only from the transaction.

Recorded on 2026-11-25 at Scarab commit `84bf3a1cd`, the last with the flat encoder, by a test in each suite that
wrote these lines; the tests and their throwaway checkout were removed after. The digests are Scarab's own, not
the reference's: they hold the era encoder to the bytes the flat encoder wrote, which were held to the reference's
where it recorded them (`ReferenceRefusalSuite`). A failure whose `toString` changes finds no line and fails its
test; record it again only from a reviewed change. No secret is held.
