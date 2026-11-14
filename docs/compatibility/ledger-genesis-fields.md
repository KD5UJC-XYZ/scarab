<!-- SPDX-License-Identifier: Apache-2.0 -->
# Ledger: genesis fields (#497)

Part of the [compatibility matrix](../compatibility.md); the register entry is `ledger-genesis-fields` in [compatibility.json](../data/compatibility.json).

## Status as recorded

implemented. At start-up the node checks the Shelley, Alonzo and Conway genesis files field by field against the reference's parsers (`GenesisSchema`), before any reader of its own runs:
- `FromJSON ShelleyGenesis` and `LegacyJSONPParams`, cardano-ledger `f649f975` `Shelley/Genesis.hs:403-437,494-513`;
- `FromJSON AlonzoGenesis`, `Alonzo/Genesis.hs:222-242`, with `ExUnits` and `Prices`, `Plutus/ExUnits.hs:118-128,176-181`;
- `FromJSON ConwayGenesis` and `UpgradeConwayPParams`, `Conway/Genesis.hs:156-165`, `Conway/PParams.hs:1082-1095`.

A required field is required, of the type it is read with: the `Word16`, `Word32` and `Word64` bounds, the bounded ratios as decimals or objects, the nonce tag, the network, hashes, credentials and addresses. A refusal is a typed `ConfigProblem.GenesisField` naming the file and the field's path. The readers that run after the check read the same instances and keep no defaults: `ProtocolParams.fromGenesis`, `GenesisState.globals` and `GenesisState.genDelegs`. `minPoolCost` and `minUTxOValue` are optional, as in the reference.

## Evidence

`GenesisSchemaSuite`: 301 retained reference cases ([cases and pins](../../fixtures/reference/genesis-fields/README.md)):
- 61 whole genesis files of the repository;
- every field of the preview files removed or given another JSON type;
- the values the types decide, among them the Shelley fees, deposits and minimums, read as `CompactForm Coin` (`Word64`), and the Conway deposits, an unbounded `Coin` until the upgrade into Conway compacts them (#573);
- the optional fields and the map entries.

Scarab accepts exactly what the reference accepts. A removed field, or one of another type, is refused at its own path. Every accepted file then passes every reader the node runs after the check. For a Shelley file, the parameters and the update quorum read are the reference's.

### Bounded integer exponents

The shared `Scientific.toBoundedInteger` also reads node configuration fields. Its overflow guard
uses the stored exponent as well as the normalized one: moving trailing coefficient zeros into
a 64-bit exponent can wrap it. The reference's `dangerouslyBig` guard reads the stored exponent
([scientific 0.3.8.1, `Data/Scientific.hs:771-802`](https://github.com/basvandijk/scientific/blob/v0.3.8.1/src/Data/Scientific.hs#L771)).

On 2026-11-04, the executed Leios oracle image's aeson 2.3.1.0 / scientific 0.3.8.1 reader refused
`10e9223372036854775807`, `100e9223372036854775806` and `1000e9223372036854775805` as both `Word64`
and signed `Int`. Scarab previously read them as `1`. `JsonValueSuite` preserves the failing
regression for signed and unsigned bounds, and `LeiosSettingsSuite` exercises the real node reader.
Zero remains accepted, while the same nonzero positive inputs read as `Double` remain infinity.
The parser's existing machine-Int exponent wrapping is preserved: `1e18446744073709551616` reads
as `1` in both implementations.

Reproduce a refusal inside the [pinned Leios oracle](../../scripts/oracle/leios-testnet/README.md):

```sh
cabal exec --offline -- ghc -package aeson -e '(Data.Aeson.eitherDecode (Data.ByteString.Lazy.Char8.pack "10e9223372036854775807") :: Either String Data.Word.Word64)'
```

These are numeric-reader comparisons, not a whole-node configuration conformance claim.

## Explicitly unchecked or unconfirmed

- The entries of `staking`, `delegs`, `initialDReps` and the `extraConfig` injections are checked to their shape only. Their contents are read by `GenesisInjections` and not compared with the reference's entry parsers ([#563](https://github.com/KD5UJC-XYZ/scarab/issues/563)).
- An `updateQuorum` past `Int` is read as `Int.MaxValue`, which no vote count reaches, as none reaches the larger value.
- The cost models are [ledger-genesis-cost-models](ledger-genesis-cost-models.md).
