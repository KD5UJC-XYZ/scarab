<!-- SPDX-License-Identifier: Apache-2.0 -->
# Rolling ChainSync (#310)

Part of the [compatibility matrix](../compatibility.md); the register entry is `rolling-chainsync` in [compatibility.json](../data/compatibility.json). The three sections below are the cells of the former matrix row, unchanged.

## Status as recorded

implemented on the feature branch: native low/high 200/300 decisions, one owned receiver, request-scoped Genesis context, immediate AwaitReply callbacks, coherent intersection heights and bounded publication

## Evidence

798 explicit native observations, all 24 original generated pipeline cases and all 84 original Genesis scenarios; FIFO/cancellation/reuse, rejected-tip and empty-graft regressions; current-main Docker check and private Genesis relay pass. [Contract and acceptance](../design/chainsync-pipeline.md)

## Explicitly unchecked or unconfirmed

Finite pinned inputs; no exact thread-interleaving or throughput-improvement claim. Legacy round metrics remain batch-specific; rolling header counts are preserved. Pending Genesis PR #309 is a merge dependency. Inbound usefulness metrics #150, Genesis churn #311 and legacy Praos early-header deferrals remain separate.
