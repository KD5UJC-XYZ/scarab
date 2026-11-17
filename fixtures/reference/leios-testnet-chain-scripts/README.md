# The public Leios testnet's own scripts judged by plutus 1.71.0.0

Status: Current. Evidence for [#54](https://github.com/KD5UJC-XYZ/scarab/issues/54) (the follow-up
[issuecomment-6905542791](https://github.com/KD5UJC-XYZ/scarab/issues/54#issuecomment-6905542791)):
the own evaluator at protocol 12 on scripts recorded on the Leios testnet's chain, against the
reference's results.

Every script context of [leios-testnet-chain-contexts](../leios-testnet-chain-contexts/README.md)
(transactions of the testnet's chain, their contexts collected by the testnet's build) became one
request of the [generated-program oracle at plutus 1.71.0.0](../../../scripts/oracle/plutus/generated-171/README.md),
the plutus the testnet's node links (`261766ad1d582bce8c86bad1af7a3b0e5355b21c`, unchanged):
`deserialiseScript` and `evaluateScriptRestricting` of the script's language at the context's
protocol version, 12, with the context's arguments (a PlutusV1 or PlutusV2 script's datum, redeemer
and context, a PlutusV3 or PlutusV4 script's context), the chain's cost model of the language (the
one the context carries), and the redeemer's execution units as the budget, as cardano-ledger
`9df9f98c` evaluates a script (`evaluatePlutusWithContext`,
`libs/cardano-ledger-core/src/Cardano/Ledger/Plutus/Evaluate.hs:382-392`, and each language's
`evaluatePlutusRunnable`, `Plutus/Language.hs:521-601`;
a PlutusV4 script through plutus's PlutusV3 functions there, through plutus's own PlutusV4 functions
in the oracle, which admit and evaluate as the PlutusV3 ones at protocol 12,
[the oracle README](../../../scripts/oracle/plutus/generated-171/README.md)).

On 2026-11-16 the 273 scripts of the 200 transactions (68 PlutusV1, 54 PlutusV2, 117 PlutusV3 and 34
PlutusV4, 62 distinct; 108 minting, 103 spending, 40 rewarding, 13 certifying, 7 guarding and 2
proposing) were judged. Every one is admitted and succeeds within its redeemer's budget, as every
transaction of the chain is recorded valid; none logs anything. The budgets spent run from 64,100
to 9,772,239,791 CPU steps and from 500 to 16,166,448 memory units.

## Files

As in [uplc-generated-171](../uplc-generated-171/README.md#files), without a generator:
`models.jsonl` (the chain's cost model of each language, at protocol 12), `requests.jsonl` (each
script with its arguments and budget, the transaction it belongs to, the transaction's recorded
validity flag and the purpose its context names), `verdicts.jsonl` (the oracle's admission, stage,
error class and constructor, budget spent and logs), `manifest.json`, binding the three with the
harness's and the runners' sources, and `provenance.json`. `leios_chain_scan.py requests` writes the
requests from the contexts, refusing a context the reference did not collect at any level, and
`leios_chain_scan.py verdicts` runs the oracle on them (the
[oracle README](../../../scripts/oracle/leios-testnet/README.md#the-chains-own-transactions-54)) as one
immutable image ID, without network, after the recorded-request runner's check of the image
(`scripts/oracle/plutus/recorded/run.py`: the repository's harness at the pinned, unchanged plutus,
an executable cabal finds up to date as the entry point); the manifest and provenance bind that ID
and the digests of the image's harness, build files and executable, which a later record must find
again. `leios_chain_scan.py verdicts --verify-only` checks the fixture without Docker, its requests
against those the retained contexts give. On 2026-11-16 the image, so checked, gave these
`verdicts.jsonl` bytes again. The raw answers of the oracle are not published.

## What the suite checks

`LeiosChainScriptsSuite` (module `plutus`) replays every request through the own evaluator as
`GeneratedOracle171Suite` does (`GeneratedReplay`, `LedgerEval.evaluateSerialised`): the same
admission and stage, error class and constructor, budget spent and logs; and through
`ScarabEvaluator`, the node's interpreter of that path: the same outcome, budget spent and logs. It also
requires each transaction's recorded validity flag to be the reference's verdict on its scripts:
valid if and only if every script succeeds within its redeemer's budget. No case is excluded.

## A larger run (not retained)

On 2026-11-16 the same rule with `--size 5000` took 5,000 of the chain's transactions with
redeemers, the retained 200 among them (1,790 of ranking blocks, 3,210 of endorser blocks; 40 with
sub-transactions), whose 5,113 scripts (165 PlutusV1, 219 PlutusV2, 4,681 PlutusV3 and 48 PlutusV4,
182 distinct; 3,185 spending, 1,667 rewarding, 239 minting, 13 certifying, 7 guarding and 2
proposing) went through the same oracles. Plutus 1.71.0.0 found every one admitted and succeeding
within its redeemer's budget, up to 9,999,627,550 CPU steps. `GeneratedOracleReplayMain` (the same
entry point as the suite's replay) found no disagreement in stage, error, budget or logs, and
`LeiosChainContextsSuite` run on those cases found the node's collection of the 5,113 scripts the
reference's and the 48 PlutusV4 arguments equal to the reference's. Its cases, contexts and verdicts
are not published; `leios_chain_scan.py sample --size 5000` on the same chain writes the sample
again.

## What it does not claim

The requests are a sample of the chain's scripts (the [sampling](../leios-testnet-chain-contexts/README.md#where-the-transactions-come-from)),
and their arguments are the reference's: the node's own collection of those arguments is checked by
`LeiosChainContextsSuite`, for PlutusV4 only. The node does not reach protocol 12
(`MaxMajorProtVer` 11).

## Licence

The scripts and arguments are public chain data of a test network ([NOTICE](../../../NOTICE)).
