<!-- SPDX-License-Identifier: Apache-2.0 -->
# Ledger: genesis fields (#497)

Part of the [compatibility matrix](../compatibility.md); the register entry is `ledger-genesis-fields` in [compatibility.json](../data/compatibility.json).

## Status as recorded

implemented. At start-up the node checks the Shelley, Alonzo and Conway genesis files field by field against the reference's parsers (`GenesisSchema`), before any reader of its own runs:
- `FromJSON ShelleyGenesis` and `LegacyJSONPParams`, cardano-ledger `f649f975` `Shelley/Genesis.hs:403-437,494-513`;
- `FromJSON AlonzoGenesis`, `Alonzo/Genesis.hs:222-242`, with `ExUnits` and `Prices`, `Plutus/ExUnits.hs:118-128,176-181`;
- `FromJSON ConwayGenesis` and `UpgradeConwayPParams`, `Conway/Genesis.hs:156-165`, `Conway/PParams.hs:1082-1095`.

A required field is required, of the type it is read with: the `Word16`, `Word32` and `Word64` bounds, the bounded ratios as decimals or objects, the nonce tag, the network, hashes, credentials and addresses. A refusal is a typed `ConfigProblem.GenesisField` naming the file and the field's path. The readers that run after the check read the same instances and keep no defaults: `ProtocolParams.fromGenesis`, `GenesisState.globals` and `LiteLedger.fromGenesis`. `minPoolCost` and `minUTxOValue` are optional, as in the reference.

## Evidence

`GenesisSchemaSuite`: 279 retained reference cases ([cases and pins](../../fixtures/reference/genesis-fields/README.md)):
- 61 whole genesis files of the repository;
- every field of the preview files removed or given another JSON type;
- the values the types decide;
- the optional fields and the map entries.

Scarab accepts exactly what the reference accepts. A removed field, or one of another type, is refused at its own path. Every accepted file then passes every reader the node runs after the check. For a Shelley file, the parameters and the update quorum read are the reference's.

## Explicitly unchecked or unconfirmed

- The entries of `staking`, `delegs`, `initialDReps` and the `extraConfig` injections are checked to their shape only. Their contents are read by `GenesisInjections` and not compared with the reference's entry parsers ([#563](https://github.com/KD5UJC-XYZ/scarab/issues/563)).
- An `updateQuorum` past `Int` is read as `Int.MaxValue`, which no vote count reaches, as none reaches the larger value.
- The cost models are [ledger-genesis-cost-models](ledger-genesis-cost-models.md).
