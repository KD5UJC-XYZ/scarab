<!-- SPDX-License-Identifier: Apache-2.0 -->
# Network and session error ownership

Part of the [compatibility matrix](../compatibility.md); the register entry is `network-error-ownership` in [compatibility.json](../data/compatibility.json). The three sections below are the cells of the former matrix row, unchanged.

## Status as recorded

implemented (#132, AUD-02 #154): background fibers return typed values to their owner; caller-scoped channel capabilities; byte-counted nonblocking ingress; storage precedes tip publication; fatal storage/internal/defect/unavailable outcomes stop with exits 5/6/7/8 without peer blame or reconnect; an unavailable external submission closes only its connection, without predicate-failure bytes

## Evidence

`MuxSuite`, `BackgroundFibersSuite`, `SessionFailureSuite`, `FollowerStallSuite`, `FatalReporterSuite`, `LocalSubmissionSuite`, `MempoolSuite`: expected transport closure versus original unexpected cause, previous persisted tip, five fatal governor paths, borrowed-block attribution retained on unavailability, concurrent terminal reporting, real Unix-client closure and recovery through the real mempool; full check and scalafix; private relay/pool/governor/node-to-client evidence below

## Explicitly unchecked or unconfirmed

[#180 G7-F1/G7-F3/G7-F5](https://github.com/KD5UJC-XYZ/scarab/issues/180) adds caller-scoped rule/store/migration operations, terminal forging failures and bounded late source attribution (8,192 released origins, ten-minute availability, up to one-minute idle cleanup lag); same-connection reintersection is implemented under [#152](https://github.com/KD5UJC-XYZ/scarab/issues/152), with pending-reply/prefetch drain and typed terminal outcomes; channel/fiber costs are in [performance.md](../performance.md), section 22
