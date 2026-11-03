<!-- SPDX-License-Identifier: Apache-2.0 -->
# Ledger: Byron genesis file (#728, #729, #730, #734)

Part of the [compatibility matrix](../compatibility.md); the register entry is `ledger-byron-genesis-balances` in [compatibility.json](../data/compatibility.json).

## Status as recorded

implemented. The node reads the Byron genesis as `readGenesisData` does (`Cardano/Chain/Genesis/Data.hs:169-181`, cardano-ledger `f649f975`), whichever era the chain starts in:
- the file's bytes are parsed by `parseCanonicalJSON` (canonical-json 0.6.0.1 `Text/JSON/Canonical/Parse.hs`; `CanonicalJson.parse`, on the heap): whitespace as `isSpace` of a byte, strings of any bytes but `"` and `\` with only the escapes `\"` and `\\`, integers of at most 15 digits, nothing read after the value. A file it refuses is refused (`ConfigProblem.CanonicalJsonSyntax`), #730;
- the genesis hash is `blake2b-256` of `renderCanonicalJSON` of what was parsed (`:179`; `CanonicalJson.render`): keys sorted by byte, a repeated key kept;
- a field is the first of its name (`fromJSField`), and must be present.

Every field is read as `FromJSON GenesisData` reads it (`:85-102`; `ByronGenesisRead`), in its order, the first refusal being the reference's (`ConfigProblem.ByronGenesisValue`, typed by field), #734:
- `protocolConsts` is looked up before every other field (`:88-90`), but is read as an object only for `k`, after `blockVersionData`;
- `bootStakeholders` is a `Map KeyHash Word16` (`Genesis/KeyHashes.hs:40-42`): each key base16 of 28 bytes (`Common/KeyHash.hs:49-53`, `decodeAbstractHash`), each value a number taken modulo 2^16, the keys as a set in byte order;
- `heavyDelegation` is a `Map KeyHash Certificate` (`Genesis/Delegation.hs:50-58`) whose entries are checked by `recreateGenesisDelegation` in key order (`:114-127`): each key the hash of its issuer, no delegate a key. A certificate (`Delegation/Certificate.hs:257-263`) has `omega` a number taken modulo 2^64, its keys base64 as base64-bytestring decodes it of 64 bytes (`Signing/VerificationKey.hs:137-140`), its signature base16 of 64 bytes (`Signing/Signature.hs:120-124`), not checked here;
- `startTime` is a number of seconds (an `Int54`, either sign), `k` a number taken modulo 2^64 (`BlockCount`), `protocolMagic` a number taken modulo 2^32 (`ProtocolMagicId`), so the epoch is 10k slots modulo 2^64 (`ProtocolConstants.hs:44-45`);
- `blockVersionData` (`Update/ProtocolParameters.hs:144-160`): `scriptVersion` a number modulo 2^16; the four sizes and the slot duration `Read Natural` of a string (`HaskellRead.natural`); the thresholds `Read Word64` of at most 10^15 (`Common/LovelacePortion.hs:91-100`); `updateImplicit` and `unlockStakeEpoch` `Read Word64`; `txFeePolicy` its `summand` `Read Word64` divided by 10^9 and its `multiplier` `Read Integer` (`Common/TxFeePolicy.hs:109-128`).

A chain that starts in Shelley reads the whole Byron genesis too, and a genesis the reference refuses does not start the node.

The two balance fields, `nonAvvmBalances` and `avvmDistr`, are read as `FromJSON GenesisData` reads them (`:96,102`), in `ByronGenesisFile.nonAvvmBalances` and `avvmBalances`:
- a non-AVVM key is `decodeAddressBase58` (`Common/Address.hs:188-189,233-243`): Base58, then the whole Byron address at `byronProtVer`. A key that is not a Byron address is refused (`ConfigProblem.ByronGenesisAddress`). The genesis UTxO keys and holds the address encoded again with minimal heads, as `fromBalances` does (`UTxO/UTxO.hs:121-131`), whatever widths the key's bytes used (`ByronAddress.reencoded`);
- an AVVM key is `fromAvvmVK` (cardano-crypto-wrapper 1.7.0.0 `Signing/Redeem/VerificationKey.hs:120-129`): either base64 alphabet, padded and canonical, 32 bytes (`ConfigProblem.AvvmKey`);
- an amount is `fromJSON @Word64`, which is `readEither` (cardano-prelude 0.2.2.0 `Json/Canonical.hs:114-115`) over the string's UTF-8 bytes (canonical-json 0.6.0.1 `Types.hs:64,90-91`): Haskell's `Read Word64`, taken modulo 2^64, with no `maxLovelaceVal` check (`Common/Lovelace.hs:117-118`; `HaskellRead.word64`);
- every entry is read, and of two keys that read as the same address or redeem key, the first in the text holds (canonical-json `FromJSON (Map k a)`, `Class.hs:162-175`).

## Evidence

`ByronGenesisBalancesReferenceSuite`: 175 retained reference cases ([cases and pins](../../fixtures/reference/byron-genesis-balances/README.md)), 95 taken and 80 refused, from the [oracle](../../scripts/oracle/byron-genesis-balances/README.md) that runs the unchanged released reader. For each taken case Scarab's genesis UTxO, built by `ByronLedger.genesisUtxo`, has the reference's outputs: id, index, address and amount; each refused case is refused for the field the reference names; where the bytes parse, Scarab's genesis hash is the reference's.

`ByronGenesisDataReferenceSuite`: 198 retained reference cases ([cases and pins](../../fixtures/reference/byron-genesis-data/README.md)), 86 taken and 112 refused, from the [oracle](../../scripts/oracle/byron-genesis-data/README.md) that runs `fromJSON @GenesisData` unchanged: the genesis files of the repository and edits of the devnet genesis covering each field. For each taken case every field Scarab reads equals the reference's; each refused case is refused for the field and the reason the reference gives; where the bytes parse, the hash is the reference's.

## Explicitly unchecked or unconfirmed

The data corpus reports the balances by their count; the balances corpus covers them entry by entry.
