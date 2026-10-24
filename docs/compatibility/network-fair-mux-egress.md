<!-- SPDX-License-Identifier: Apache-2.0 -->
# Network: fair Mux egress (#258)

Part of the [compatibility matrix](../compatibility.md); the register entry is `network-fair-mux-egress` in [compatibility.json](../data/compatibility.json). The three sections below are the cells of the former matrix row, unchanged.

## Status as recorded

implemented: one complete SDU turn per admitted protocol/role lane, bounded queued/staged/current work, duplex FIFO and global flush fences

## Evidence

Production-path paced bulk/control checks: successful KeepAlive at about 1.876/1.000 s with complete ordered bulk delivery; gated large-message/fence/cancellation/capacity controls; per-SDU deadlines and existing lifecycle/abort suites. Original upstream generator: 32 native submissions (22 passes, 10 discards), all 22 passing conversations replayed using the unchanged ordering assertion under an explicit 3 MB mock profile; the default public message bound remains 2.5 MB. [Contract](../design/network-limits.md#fair-egress-as-built-258), [corpus](../../network/src/test/resources/upstream/mux-starvation/README.md)

## Explicitly unchecked or unconfirmed

Twelve additional unequal-pair checks are local controls; original native wiring uses response1 twice. Local controlled service times are not host throughput; bounded generator replay does not establish exact thread-schedule parity. Admission waits and live fence prefixes remain explicit; broader network campaigns remain optional K2 roadmap follow-ups
