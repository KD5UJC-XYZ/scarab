<!-- SPDX-License-Identifier: Apache-2.0 -->
# Ledger: repeated withdrawal accounts, decoder versions 2 to 9 (#494)

Part of the [compatibility matrix](../compatibility.md); the register entry is `ledger-repeated-withdrawals` in [compatibility.json](../data/compatibility.json).

## Status as recorded

implemented: below decoder version 9 the withdrawals of a body keep one entry per reward account, with the last amount written, as `decodeMapByKey` builds the `Withdrawals` map with `Map.fromList` (cardano-ledger f649f975 `Decoder.hs:815-823`, `Address.hs:980-982`), so the UTXO rule counts an account once in the consumed value and the withdrawal check sees one entry; from version 9 the repeat is refused (`decodeMapLikeEnforceNoDuplicates`, `DuplicateElement`). Only the decoded entries are collapsed: the body's original bytes and id are unchanged. The other maps of a pre-Conway body already keep the last value of a repeated key (mint and output multi-assets, update proposals, MIR maps, cost models) or refuse it as the reference does (body keys, parameter-update keys); metadata labels are not collapsed, which no rule reads

## Evidence

`DefiniteBytesReferenceSuite`: the last 12 lines of `fixtures/reference/definite-bytes/cases.jsonl` (an account repeated around another and an account repeated with the smaller amount last, in each era): the 10 Shelley to Babbage bodies accepted with the withdrawals the reference decoded (recorded by the harness), the 2 Conway bodies refused; against the decoder before this change all 10 kept both entries (measured). `DuplicateWithdrawalsLedgerSuite`: on native ledger state in each era from Shelley to Babbage, a body decoded from bytes that withdraws one account's full balance twice and pays it out twice is refused with `ValueNotConserved`, where removing only the last-wins decode makes the native rule accept it (measured). A companion case keeps the smaller last amount, drains the account once and checks native UTxO, fees and deposits; removing the same decode makes that case fail with `IncompleteWithdrawals`. Restoring the original decoder makes both cases pass in every tested era

## Explicitly unchecked or unconfirmed

decoder version 8 (Babbage after the intra-era hard fork) is read with the same rule as 7 and is not measured separately

The reward-account payload decoder still accepts network-header bits that the reference refuses (`headerIsAccountAddress`, cardano-ledger f649f975 `Address.hs:930-954`). That payload alignment belongs to [#477](https://github.com/KD5UJC-XYZ/scarab/issues/477). The evidence here checks duplicate-map semantics; it does not establish complete withdrawal-payload decoding equivalence.
