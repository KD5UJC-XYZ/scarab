<!-- SPDX-License-Identifier: Apache-2.0 -->
# Snapshot and listener failure ownership (G7-F2/G7-F4)

Part of the [compatibility matrix](../compatibility.md); the register entry is `snapshot-listener-failures` in [compatibility.json](../data/compatibility.json). The three sections below are the cells of the former matrix row, unchanged.

## Status as recorded

implemented: periodic/shutdown snapshots and TCP/Unix handlers own their error scopes; typed storage/domain errors and original defects reach event/reporting boundaries; unexpected listener defects reach the node fatal sink

## Evidence

`SnapshotFailureSuite`, `BearerServerSuite`, `ListenerFailuresSuite`, guarded `LocalSubmissionSuite` and unchanged log goldens; private restart/relay/NtC evidence below

## Explicitly unchecked or unconfirmed

snapshot report-and-replay policy and persisted format remain unchanged; the G7-F1/F3/F5 contracts and evidence are recorded alongside this slice
