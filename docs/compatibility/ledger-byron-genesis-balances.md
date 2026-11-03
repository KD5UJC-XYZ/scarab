<!-- SPDX-License-Identifier: Apache-2.0 -->
# Ledger: Byron genesis balances (#728, #729)

Part of the [compatibility matrix](../compatibility.md); the register entry is `ledger-byron-genesis-balances` in [compatibility.json](../data/compatibility.json).

## Status as recorded

implemented. The node reads the two balance fields of the Byron genesis, `nonAvvmBalances` and `avvmDistr`, as `readGenesisData` reads them (`Cardano/Chain/Genesis/Data.hs:96,102,169-181`, cardano-ledger `f649f975`), in `ByronGenesisFile.genesisBalances`:
- a non-AVVM key is `decodeAddressBase58` (`Common/Address.hs:188-189,233-243`): Base58, then the whole Byron address at `byronProtVer`. A key that is not a Byron address is refused (`ConfigProblem.ByronGenesisAddress`). The genesis UTxO keys and holds the address encoded again with minimal heads, as `fromBalances` does (`UTxO/UTxO.hs:121-131`), whatever widths the key's bytes used (`ByronAddress.reencoded`);
- an AVVM key is `fromAvvmVK` (cardano-crypto-wrapper 1.7.0.0 `Signing/Redeem/VerificationKey.hs:120-129`): either base64 alphabet, padded and canonical, 32 bytes (`ConfigProblem.AvvmKey`);
- an amount is `fromJSON @Word64`, which is `readEither` (cardano-prelude 0.2.2.0 `Json/Canonical.hs:114-115`) over the string's UTF-8 bytes (canonical-json 0.6.0.1 `Types.hs:64,90-91`): Haskell's `Read Word64`, taken modulo 2^64, with no `maxLovelaceVal` check (`Common/Lovelace.hs:117-118`; `HaskellRead.word64`);
- of two keys that read as the same address or redeem key, the first in the text holds (canonical-json `FromJSON (Map k a)`, `Class.hs:162-175`).

## Evidence

`ByronGenesisBalancesReferenceSuite`: 114 retained reference cases ([cases and pins](../../fixtures/reference/byron-genesis-balances/README.md)), 62 taken and 52 refused, from the [oracle](../../scripts/oracle/byron-genesis-balances/README.md) that runs the unchanged released reader. For each taken case Scarab's genesis UTxO, built by `ByronLedger.genesisUtxo`, has the reference's outputs: id, index, address and amount; each refused case is refused for the field the reference names.

## Explicitly unchecked or unconfirmed

How the whole Byron genesis file is parsed (canonical JSON's strings, escapes and numbers, a key repeated in the text, a field left out) is [#730](https://github.com/KD5UJC-XYZ/scarab/issues/730).
