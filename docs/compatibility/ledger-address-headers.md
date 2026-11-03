<!-- SPDX-License-Identifier: Apache-2.0 -->
# Ledger: address and reward account headers, decoder versions 2 to 9 (#723)

Part of the [compatibility matrix](../compatibility.md); the register entry is `ledger-address-headers` in [compatibility.json](../data/compatibility.json). The sections below record the implementation, evidence and remaining limits.

## Status as recorded

implemented: the reference reads only bit 0 of an address header's low nibble as the network (`headerNetworkId`, `libs/cardano-ledger-core/src/Cardano/Ledger/Address.hs:557-561`, cardano-ledger `f649f975`). It refuses a Shelley address whose header sets a bit of `headerNonShelleyBits = 0b10001110` (`decodeAddrStateLenientT`, `:655-666`), before it reads anything after the header. It takes a bootstrap address only with the header `0b10000010` (`isByronAddress`, `:548-552`), and a reward account only with a header of `0b111x000y` (`headerIsAccountAddress`, `:930-932`, in `decodeAccountAddressT`, `:938-955`). Every era reads an output's address with `fromCborBothAddr` (`:459-477`), whose backwards (below version 7) and rigorous readers both make the check, and a reward account with `fromCborAccountAddress` (`:913-916`).

`Address.decode` now refuses a Shelley header with bits 1 to 3 set (`LedgerDecodeError.AddressHeader`) in each era's `Address.Mode`, in an output and in a collateral return. `RewardAccount.decode` refuses such a reward account header (`LedgerDecodeError.RewardAccountHeader`) in a withdrawal, a pool registration's reward account, a proposal's return account and a treasury withdrawal. Before #723 both took a header naming a network of 2 to 15, so a block holding one was refused by every reference node and taken by Scarab.

What the node itself stored is read with `Address.Mode.Stored`: the UTxO store (`TxOut.fromBytes`), the Haskell state codecs (`UtxoCodecs`) and the MemPack import. It takes any low nibble, so an output an earlier build took and stored before #723 keeps reading (ADR 0006). No block or transaction is read in that mode. The reward accounts of a stored state are read by their own codec (`NodeTypeCodecs`), which also takes any nibble.

## Evidence

`AddressHeadersReferenceSuite`: `fixtures/reference/address-headers/cases.jsonl` (oracle `scripts/oracle/conway-decoding/address_cases.py`, harness kinds "address" and "account" added). For each era from Shelley to Conway it holds every header of an address and of a reward account read alone: sixteen types and sixteen low nibbles each, 3072 cases, 126 accepted. It also holds 68 bodies with an address or an account of network 1 and 2 in every place a body reads one, 34 accepted. Scarab gives the same verdict for each, and a stored output still reads with every nibble.

## Explicitly unchecked or unconfirmed

A node-to-client query's addresses (`GetUTxOByAddress`) are read as raw bytes until the query readers of #709 read them with `Address.decode` at the era's mode, which then makes the same check.
