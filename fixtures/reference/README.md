# Cases decided by the reference node

Spending-datum admission and optional argument construction are covered separately by
[18 complete reference spending cases](spending-datum/README.md), including all languages and both flags (#177).

Transactions that `python3 scripts/devnet.py v1-context` built and submitted to the Haskell
`cardano-node` 11.1.3 cluster of the devnet, each with the outputs it reads and what the reference did with it.
They are replayed without Docker by `V1ContextSuite` (phase 2, the transaction rule of the era, a block) and
the hand-built matrix of `ScriptContextV1EraSuite` states the same rules per protocol version.

## `v1-context/`

One JSON file per era and protocol version (`alonzo-pv5.json`, `babbage-pv7.json`, `conway-pv10.json`, ...):
an array of cases in the shape of `fixtures/preview/plutus` (`body`, `witnesses`, `isValid`, `spent`,
`references`, `collateral`, `costModels` keyed by language id) plus

| field | meaning |
|---|---|
| `languages` | the Plutus languages of the scripts the transaction runs (1, 2, 3) |
| `reference.accepted` | the reference put the transaction into a block |
| `reference.error` | what `cardano-cli transaction submit` printed when it was refused (the predicate failure) |
| `reference.expected` | the text the case asserts the refusal contains; `null` for a case the reference must accept |

Every transaction mints one token under a script whose context the ledger has to build. The outputs it reads
(reference inputs, spent inputs) and creates differ per case; the validity flag is set both ways, with scripts
that pass and that fail. The cost models are those of the cluster's protocol parameters.

Regenerate (a Conway cluster at the protocol version of the devnet genesis, then the era-walking cluster for
the earlier eras and protocol versions):

```sh
python3 scripts/devnet.py --name <yours> v1-context          # conway-pv10.json, in <run dir>/v1-context-cases.json
python3 scripts/devnet.py --name <yours> v1-context --eras   # Alonzo 5 and 6, Babbage 7 and 8, Conway 9
python3 scripts/devnet.py --name <yours> down
```


[Context collection and failure payloads](context-errors/README.md) (#176) retain 24 actual signed
submissions and 145 unchanged-reference API cases. Derived probes, direct constructor encodings and
whole-transaction admission evidence have separate provenance and assertions.

[Pre-Conway cost-model decoding](cost-models/README.md) (#332) retains 98 transactions (49 cases in each of the Alonzo and Babbage eras) measured against the unchanged released ledger decoders at decoder versions 5 and 7.

[Genesis cost models](genesis-cost-models/README.md) (#369) retains 135 cases of the Alonzo and Conway genesis cost-model parsers, a 4,188-list sweep of `mkCostModel` and 3,441 Plutus evaluations under cost-model lists of every length, measured against the unchanged released ledger libraries and Plutus 1.70.0.0.
