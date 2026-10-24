<!-- SPDX-License-Identifier: Apache-2.0 -->
# Fast start (Mithril, snapshot import)

Part of the [compatibility matrix](../compatibility.md); the register entry is `fast-start-mithril` in [compatibility.json](../data/compatibility.json). The three sections below are the cells of the former matrix row, unchanged.

## Status as recorded

implemented (E1, #27): pinned official certificate-chain/full immutable verification, isolated download or cached source, independent ancillary authentication, exact artifact/network policy and atomic fresh-directory publication; unchanged V5/stored CBOR

## Evidence

genuine recorded Preview epoch 1454/i29089 full bootstrap (3,192,290 outputs), original checkpoint Haskell projection 0diff, independent genesis-derived comparison and 345-block continuation to 125,669,042; actual root/certificate/immutable/archive/substitution refusals; `MithrilClientSuite`, `MithrilBootstrapSuite`, `MithrilBootstrapAcceptanceSuite`, CLI tests; private restart and full check (details in [design](../design/mithril-bootstrap.md#acceptance))

## Explicitly unchecked or unconfirmed

fixed captured bundle endpoint, no ongoing public-tip run or mainnet acceptance; two inactive Conway parameter extras differ from genesis replay under the existing era-lacking parameter policy (raw dump equality is false); ancillary ledger/unfinished trio use the operator key, never Mithril certification; Linux amd64 pinned verifier; mainnet/week-long following remains D9/#69
