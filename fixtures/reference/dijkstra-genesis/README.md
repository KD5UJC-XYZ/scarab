<!-- SPDX-License-Identifier: Apache-2.0 -->
# Dijkstra genesis

107 cases measured on 2026-11-07 against the unchanged Haskell ledger of the public Leios testnet build
(cardano-node `b004fe0c`, cardano-ledger `9df9f98c`, cardano-ledger-dijkstra 0.5.0.0), for
[#54](https://github.com/KD5UJC-XYZ/scarab/issues/54). The oracle source and
[recipe](../../../scripts/oracle/dijkstra-genesis/README.md) are committed; `provenance.json` records the
image identity, the program's hash and the hash of every base file.

`cases.jsonl` holds one case per line:
- a Dijkstra genesis file of the repository (`base`) and the change made to it (`op`: none, `remove` or
  `replace`, at `path`, with `value`; an empty path replaces the whole document);
- the verdict of `FromJSON DijkstraGenesis` on the result. An acceptance keeps the genesis as the ledger
  writes it back (`ToJSON`), which holds every value read.

The fixture keeps the change, not the changed file; `DijkstraGenesisSuite` rebuilds each input from its base.

| group | cases | reference verdict |
|---|---|---|
| whole files | `fixtures/leios/testnet/config/dijkstra-genesis.json` and `fixtures/dijkstra-devnet/genesis/dijkstra-genesis.json` | accepted |
| a field removed | every field of the Leios testnet file, and the two units of `maxEndorserBlockExecutionUnits` | refused, except `maxPledgeLeverage` |
| a field of another JSON type | the same fields | refused |
| values the types decide | a document that is not an object; an unknown key (accepted); the Peras bootstrap round as a number, at and past `Word32`, negative; the pledge leverage as a decimal, a ratio object, negative, past `Word64`; the stride zero, one, past `Word32`, written `25600.0`; the `PositiveInterval` fields at zero, at `1e-19` (accepted), `1e-20`, as objects and above one; the `UnitInterval` fields above one, at one, negative, as an object; the `Word16` and `Word32` fields at and past their maximum, negative, fractional, with an exponent; the `Coin` field negative and past `Word64` (both accepted: it is an `Integer`) and fractional; the `CoinPerByte` field negative, at and past `Word64`; the execution units under the long names, the first name malformed (`<|>` takes the second), at and past `Word64`; the PlutusV4 cost model one short, one long, at and past `Int64`, fractional, an object | as listed |

Scope: the parser of the file and the values it reads. Not the translation from Conway, the parameter
update or the defaults the reference takes when the configuration names no Dijkstra genesis
(`emptyDijkstraGenesis`, cardano-api's `dijkstraGenesisDefaults`).
