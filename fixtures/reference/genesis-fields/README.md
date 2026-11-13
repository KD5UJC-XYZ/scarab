<!-- SPDX-License-Identifier: Apache-2.0 -->
# Genesis fields

322 cases measured against the unchanged released Haskell ledger libraries used by node 11.1.3
(cardano-ledger `f649f975`): 279 on 2026-10-28 for [#497](https://github.com/KD5UJC-XYZ/scarab/issues/497), 22 on
2026-11-07 for [#573](https://github.com/KD5UJC-XYZ/scarab/issues/573), 12 on 2026-11-09 for the genesis files of the
refusal corpora of [#832](https://github.com/KD5UJC-XYZ/scarab/issues/832), 3 on 2026-11-10 for those of the
Leios-build refusal corpus of [#826](https://github.com/KD5UJC-XYZ/scarab/issues/826), and 6 on 2026-11-13 for the
Shelley and Conway genesis files of the three clusters of the `refusalcoverage` corpora. That recording ran every
case again, on an image rebuilt from the same recipe (a new image id), and the 316 earlier verdicts came out
unchanged. The oracle source and [recipe](../../../scripts/oracle/genesis-fields/README.md) are committed;
`provenance.json` records the image identity, the program's hash and the hash of every base file.

`cases.jsonl` holds one case per line:
- the parser (`kind`: `FromJSON ShelleyGenesis`, `FromJSON AlonzoGenesis` or `FromJSON ConwayGenesis`);
- a genesis file of the repository (`base`) and the change made to it (`op`: none, `remove` or `replace`, at `path`, with `value`);
- the reference's verdict. An acceptance of a Shelley file keeps the protocol parameters and the update quorum the reference read.

The fixture keeps the change, not the changed file; `GenesisSchemaSuite` rebuilds each input from its base.

| group | cases | reference verdict |
|---|---|---|
| whole files | every Shelley, Alonzo and Conway genesis file of the repository (82): the public networks, the devnet and test fixtures, the reference captures and the refusal corpus | accepted |
| a field removed | every field of the preview files, and one level into `protocolParams`, `protocolVersion`, `extraEntropy`, `executionPrices`, the execution units, `constitution`, its `anchor`, `committee` and the voting thresholds | refused, except `minUTxOValue`, `minPoolCost` and the constitution's `script`, which are optional |
| a field of another JSON type | the same fields | refused |
| values the types decide | the nonce tag (`Nonce` with and without `contents`, another tag); execution units at and past `Word64`, under both names, the first name malformed (`<|>` takes the second) or past `Word64` (refused: the bound is checked after `<|>`); prices under both names; the ratios as objects and as decimals, and above 1 (`UnitInterval` refuses, `NonNegativeInterval` takes it); `updateQuorum` past 31 bits and at `Word64`'s maximum (accepted) and past it (refused); the Shelley `minFeeA`, `minFeeB`, `keyDeposit`, `poolDeposit`, `minUTxOValue` and `minPoolCost` negative and past `Word64` (refused: `CompactForm Coin`) and at its maximum (accepted); the Conway `dRepDeposit` and `govActionDeposit` negative and past `Word64` (accepted: an unbounded `Coin`, compacted only at the upgrade into Conway) | as listed |
| optional fields and map entries | `staking` and `extraConfig` removed or of another type, `staking.pools` of another type, `delegs` and `initialDReps` empty or of another type; a `genDelegs` entry without `delegate`, with a short `vrf` or key; an `initialFunds` entry with a bad address or a coin of another type, and a valid one; a committee member's epoch of another type and a bad credential; a constitution URL of 129 bytes (accepted: the JSON instance does not bound it) and a short data hash | as listed |

Scope: the fields of the three files to the types the instances read them with. The entries of `staking`, `delegs`, `initialDReps` and the `extraConfig` injections are checked to their shape only. `GenesisInjections` reads their contents, and comparing those with the reference's entry parsers is [#563](https://github.com/KD5UJC-XYZ/scarab/issues/563). The cost models are the [genesis cost-model fixture](../genesis-cost-models/README.md)'s.
