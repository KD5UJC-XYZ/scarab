<!-- SPDX-License-Identifier: Apache-2.0 -->
# NtC backlog verification harness

Part of the [compatibility matrix](../compatibility.md); the register entry is `ntc-backlog-harness` in [compatibility.json](../data/compatibility.json). The three sections below are the cells of the former matrix row, unchanged.

## Status as recorded

implemented (#192): canonical common intersection candidates prune rollbacks independently of historical byte comparisons; one older-point retry only after symmetric, empty, error-free intersection refusal, retaining both attempts

## Evidence

Ten deterministic controls, all 212 Python tests, full Docker check (4,737 passed/15 skipped), and real NtC comparison of 76 blocks including four backlog blocks on `0dfe3637`

## Explicitly unchecked or unconfirmed

The bounded retry can still exhaust under repeated rollbacks; errors, asymmetric refusal and differing bytes fail immediately. The nonempty-backlog gate and existing query masks/exclusions are unchanged.
