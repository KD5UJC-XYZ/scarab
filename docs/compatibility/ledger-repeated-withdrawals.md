<!-- SPDX-License-Identifier: Apache-2.0 -->
# Ledger: repeated withdrawal accounts, decoder versions 2 to 9 (#494)

Part of the [compatibility matrix](../compatibility.md); the register entry is `ledger-repeated-withdrawals` in [compatibility.json](../data/compatibility.json).

## Status as recorded

implemented: below decoder version 9 the withdrawals of a body keep one entry per reward account, with the last amount written, as `decodeMapByKey` builds the `Withdrawals` map with `Map.fromList` (cardano-ledger f649f975 `Decoder.hs:815-823`, `Address.hs:980-982`), so the UTXO rule counts an account once in the consumed value and the withdrawal check sees one entry; from version 9 the repeat is refused (`decodeMapLikeEnforceNoDuplicates`, `DuplicateElement`). The decoded body and native ledger API use `Map[RewardAccount, BigInt]`, so a constructor or `copy` cannot reintroduce duplicate accounts. Every wire pair is decoded before forming the map. The body's original bytes and id remain unchanged, and script-purpose/context consumers sort explicitly in their reference-defined orders. V1/V2 translation then builds the reference’s second map keyed by staking credential, keeping the later account’s amount if two networks share a credential (`Alonzo/Plutus/TxInfo.hs:301-309`). V3 retains each account entry in ledger order (`Conway/TxInfo.hs:549-551,697-699`). The other maps of a pre-Conway body already keep the last value of a repeated key (mint and output multi-assets, update proposals, MIR maps, cost models) or refuse it as the reference does (body keys, parameter-update keys); metadata labels are not collapsed, which no rule reads

## Evidence

`DefiniteBytesReferenceSuite` checks the repeated-account corpus, including a repeat around another account and a smaller final amount in every era. Accepted bodies must match the reference's decoded account map and retain their exact original bytes and transaction id; Conway repeats must be refused. The [corpus provenance](../../fixtures/reference/definite-bytes/provenance.json) records the native capture.

`DuplicateWithdrawalsLedgerSuite` applies the transactions to native state in each era from Shelley through Babbage. Paying one account's balance twice is refused with `ValueNotConserved`, through both raw decoding and the public case-class `copy` path. The smaller final amount drains the account once and preserves native UTxO value, fees and deposits. `HotPathEquivalenceSuite` checks account ordering over maps, and the script-context golden suites retain their independent byte expectations. `WithdrawalContextsSuite` checks the exact V1/V2 winner and credential order for matching credentials under networks 0 and 1, and V3’s distinct duplicate-credential list semantics. These direct translation tests make no ledger-admission claim.

## Explicitly unchecked or unconfirmed

decoder version 8 (Babbage after the intra-era hard fork) is read with the same rule as 7 and is not measured separately

The reward-account payload decoder still accepts network-header bits that the reference refuses (`headerIsAccountAddress`, cardano-ledger f649f975 `Address.hs:930-954`). That payload alignment belongs to [#477](https://github.com/KD5UJC-XYZ/scarab/issues/477). The evidence here checks duplicate-map semantics; it does not establish complete withdrawal-payload decoding equivalence.

The amount representation and compact-coin arithmetic are separately sequenced under [#492](https://github.com/KD5UJC-XYZ/scarab/issues/492). This change enforces account-key uniqueness and does not claim that the `BigInt` amount type already mirrors `Coin`.
