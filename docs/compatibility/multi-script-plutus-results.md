<!-- SPDX-License-Identifier: Apache-2.0 -->
# Multi-script Plutus results (I4)

Part of the [compatibility matrix](../compatibility.md); the register entry is `multi-script-plutus-results` in [compatibility.json](../data/compatibility.json). The three sections below are the cells of the former matrix row, unchanged.

## Status as recorded

implemented aggregation and reconstruction: every typed result/failure, ordered traces and declared context bytes retained across serial/prepared paths

## Evidence

Released-source unchanged API oracle: 25 cases, all eight three-script patterns in V1/V2/V3; `ScriptResultOracleSuite`, 50 BBODY/defect/outage controls in `ScriptResultBlockSuite`, 73 request reconstruction byte comparisons in `ScriptResultWireSuite`, wide-redeemer small-stack control; source `63a629bc`: Docker check 4,921 passed/16 skipped, 220 Python tests, e2e 1,650 block applications/66 equal projected ledger comparisons, NtC 932/932 query comparisons under existing masks and 20/20 raw-byte/text refusals; [contract and evidence](../design/script-results.md)

## Explicitly unchecked or unconfirmed

Exact failing-tag diagnostic wording/full rejection-byte parity is optional future work in [#208](https://github.com/KD5UJC-XYZ/scarab/issues/208), outside v0.2. The reference restricting API exposes consumed units on success only; native failure units are retained without an independent parity claim. API-only derived transactions make no signed admission claim.
