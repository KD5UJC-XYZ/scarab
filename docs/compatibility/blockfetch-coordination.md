<!-- SPDX-License-Identifier: Apache-2.0 -->
# BlockFetch peer coordination (#148)

Part of the [compatibility matrix](../compatibility.md); the register entry is `blockfetch-coordination` in [compatibility.json](../data/compatibility.json). The three sections below are the cells of the former matrix row, unchanged.

## Status as recorded

Pinned Praos bulk/deadline rankings, preferred-peer handover, global targets, per-peer GSV byte/request windows and outgoing FIFO pipeline shared across inbound/outbound/recovery

## Evidence

289 native scheduling observations plus eight GSV observations (291 differential assertions); original 24-case/298-range generic protocol replay now runs the pipeline; coordinator race/window/lifecycle laws and [controlled performance workload](../performance.md#37-coordinated-blockfetch-service-model-148)

## Explicitly unchecked or unconfirmed

Genesis adds the devoted-fetch mode described above. Supplied comparator/tie inputs and finite generated scenarios do not establish identical thread schedules or universal performance. Combined check/private relay passed below; observed KeepAlive idle reconnects are tracked in [#292](https://github.com/KD5UJC-XYZ/scarab/issues/292). [As-built boundary](../design/block-fetch-coordination.md).
