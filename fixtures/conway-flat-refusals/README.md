# Conway judgments as the flat rules wrote them

Status: Current — the expected values of `ConwayLedgerChecksSuite`, `ConwayFailureCborSuite`,
`ConwayMempoolRunSuite`, `EraUtxoSuite`, `PreviewConwayBlockLedgersSuite`, `PreviewConwayRulesSuite`,
`ConwayGovChecksSuite` and `ConwayCertsChecksSuite` (#85). Licence: Apache-2.0, this project's (see `NOTICE`).

Before Scarab deleted its flat Conway rules (the flat MEMPOOL, then its LEDGER and LEDGERS, then its GOV, then its CERTS) and
the flat rules' Conway refusal encoder (`PredicateFailureCbor`), the suites that held Conway's era rules and their encoder (`ConwayFailureCbor`)
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
  11, the flat failures with CERTS's refusal of it replaced by LEDGER's, `MovedWithdrawals.expected`, since deleted). A
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
    under another guardrails script (`/guardrails`). Of `/repeated`, the parent compared the era LEDGER at the
    root with the flat LEDGER's accumulated failures, the era LEDGERS's whole list with those failures reversed,
    and the flat LEDGERS, which raises a transaction's first failure, with the flat LEDGER's first failure alone:
    no flat rule gave the era LEDGERS's whole list itself;
  - `preview-rules/`, 22 lines: `PreviewConwayRulesSuite`'s Preview transactions, the era LEDGERS on each in the
    world of its test (`proposal/`, `spend/`, `vote/`), and each proposal under another guardrails script
    (`guardrails/`).

  Recorded at Scarab commit `57ae6eb18`, the parent of the flat LEDGER's deletion. The suites whose transactions
  are built with keys drawn afresh on each run (`ConwayLedgerSuite`, `ConwayRulesSuite`,
  `ConwayGovernanceSuite`) have no judgment the same from run to run, and are not here: their cases name the era
  failures they expect, which the flat LEDGER gave on every one before it was deleted (but for
  `EraNotVersionUtxowSuite`'s Conway case, ported after, which keeps its typed expectations).

- `gov-judgments.jsonl`: one line for each judgment of Conway's era GOV, or of the GOV predicates ported to the era
  types, that `ConwayGovChecksSuite` compared with the flat GOV, 174 lines, each id once: `id`, the judgment's;
  and `r`, its cells separated by spaces. A refusal's cell is `R:` and the digest of the `ApplyTxErr` bytes of
  its failures in their order, each under LEDGER's `ConwayGovFailure`, as the node sends GOV's refusal; an
  admission's is `A:` and the digest of the case's governance state with the proposals GOV leaves, in the Haskell
  state's encoding of 11.1's layout at protocol version 9. The flat GOV gave the same failures in the same order,
  or the same proposals. The prefixes:
  - `whole/`, 85 lines: the transition whole at the root, on each Conway case of the eight corpora the flat GOV
    judged (`ConwayGovChecksSuite.RecordedCorpora`, named; the corpora recorded for the era rules alone,
    `ReferenceRefusalSuite.EraOnly`, are held to the reference's bytes instead), 4 cells each: at the version
    the case was recorded at, then 9, 10 and 11 (117 refusals and 219 admissions); and
    `whole/crafted/committee-conflict-expiry`, the crafted committee update removing and adding a member whose
    term has ended, 1 refusal;
  - `predicates/`, 89 lines: the failures of the predicates ported one by one (the bootstrap phase's proposal
    check, the well-formedness and guardrails of a proposal, the unelected committee voters from version 11) on
    each of those 84 cases and on five crafted cases (`predicates/crafted/…`: a treasury withdrawal in the
    bootstrap phase, an update setting the maximum transaction size to zero, two proposals under another
    guardrails script, and unelected committee voters with the key's vote first and the script's first), 1 cell
    each: `-` for none, or the refusal cell of each failure on its own, sorted, each as often as it was reported
    (16 cases with failures, the 68 others none), as the flat rule's failures were compared, a multiset. The
    crafted cases are made from the case `conway-bootstrap-attributes/utxo-bootstrap-attributes-too-big`, named.

  Recorded at Scarab commit `e6a61dfe5`, the head of the pull request that deleted the flat LEDGER (#1483) before
  it was squash-merged, which leaves that commit on no branch. The flat GOV's deletion was then rebased onto the
  merge, and its parent, `5d19dffba`, differs from `e6a61dfe5` by the Conway era decoders' one-pass reading of a
  transaction's parts (#1482). The suite was run again at `5d19dffba` with the flat GOV beside the era one: every
  comparison agreed, and the recording it wrote was this one, byte for byte. The
  comparisons that had other oracles are not here: the unknown actions' votes listed in key order, where the
  flat GOV listed them in the body's order (#85's item 7054122427, closed with it), and the unelected voters'
  order, which keep their expected values from the reference's source; and the GOV-only refusals, held to the
  reference's bytes.

- `certs-judgments.jsonl`: one line for each judgment of Conway's era CERTS that `ConwayCertsChecksSuite` compared with
  the flat CERTS, 467 lines, each id once: `id`, the judgment's; and `r`, its cells separated by spaces. The flat
  CERTS judged the same certificates from the same state, and the era CERTS gave the same failures in the same
  order, or the same certificate state, compared whole (no judgment of either ended with a defect). A refusal's
  cell is `R:` and the digest of the `ApplyTxErr` bytes of its failures in their order, each under LEDGER's
  `ConwayCertsFailure`, as the node sends CERTS's refusal. An admission's cell is `A:`, the digest of the
  certificate state it leaves in the Haskell state's encoding of its corpus's layout at protocol version 9, then
  `/` and the digest of the bytes that state keeps as the certificates gave them and the encoding writes in its
  own form: each pool's relays and metadata, then each waiting re-registration's, in key order, as a CBOR array of
  byte strings. Each admission is first checked to be held whole by the encoding, those bytes aside: read back, it
  is the state again. The prefixes:
  - `corpus/`, 112 lines: the transaction's own certificates of each case of the twelve Conway corpora the flat
    CERTS judged (`ConwayCertsChecksSuite.RecordedCorpora`, named), 4 cells each: at the version the case was
    recorded at, then 9, 10 and 11 (117 refusals and 331 admissions);
  - `blocks/`, 62 lines: the certificates of each transaction of the recorded Conway Preview blocks
    (`fixtures/preview/conway.hex` and `conway-governance.hex`, `<file>:<line>#<index>`), from the state of the
    case `conway-bootstrap-attributes/utxo-bootstrap-attributes-too-big`, 3 cells each: at 9, 10 and 11 (40
    refusals and 146 admissions);
  - `crafted/`, 293 lines: the crafted certificates and transactions, 1 cell each, by the label of the state they
    are judged from and the case's name: the scenarios of DELEG, POOL, GOVCERT and the committee, from that same
    case at 9, 10 and 11 (`<case>@v<version>/<name>`, 39), the base case of CERTS (withdrawals and the DReps'
    expiries) from it and from each corpus case with proposals or votes, with the dormant epochs counted and
    without (252), and a funded account unregistered at 10 and 11 (2) (34 refusals and 259 admissions).

  Recorded at Scarab commit `78ad579a9`, the head of the pull request that deleted the flat GOV (#1487) before it
  was squash-merged, which leaves that commit on no branch. The flat CERTS's deletion was then rebased onto the
  merge, and its parent, `0a562efe7`, adds this recording and the suite that reads it and otherwise differs from
  `78ad579a9` only outside the ledger: the read-ahead of queued blocks' store reads (#1484) and the fuzz job's
  differential targets (#1418). The suite of `78ad579a9` was run again at `0a562efe7` with the flat CERTS beside the
  era one: every comparison agreed, and the recording it wrote was this one, byte for byte. The comparisons that
  had other oracles are not here: the refusals only the certificate rules give, held to the reference's bytes; the base case's withdrawals under each build, held to the reference's gate
  (`hardforkConwayMoveWithdrawalsAndDRepChecksToLedgerRule`); and POOL's events. The flat CERTS checked and
  drained the withdrawals and moved the DReps' expiries in its base case below version 11 under every build, where
  the reference's 11.2 and Leios builds do so in LEDGER (#85's item 7055304484, closed with it). One case here is
  judged under a build other than 11.1, the Leios build's
  `conway-bootstrap-attributes-leios-b004fe0c/utxo-bootstrap-attributes-too-big`, and on it the two rules agreed.

Commit `84bf3a1cd` is the base this work started from; `3aaa942aa` the last of it with the flat encoder. The
tests that wrote these lines and their throwaway checkouts were removed after. The digests are Scarab's own,
not the reference's: they hold the era side to the results it replaced, which were held to the reference's
where it recorded them (`ReferenceRefusalSuite`). A case the suites no longer judge, or one they judge that is
not here, fails; record it again only from a reviewed change. No secret is held.
