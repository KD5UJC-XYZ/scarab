<!-- SPDX-License-Identifier: Apache-2.0 -->
# Prometheus metrics (J3, #47)

Part of the [compatibility matrix](../compatibility.md); the register entry is `prometheus-metrics` in [compatibility.json](../data/compatibility.json). The three sections below are the cells of the former matrix row, unchanged.

## Status as recorded

implemented for the original endpoint/algebra/names acceptance: tip, connections/peers, mempool, forged/adopted blocks and apply rate; per-node `Metrics[F]` registry in a `Ref`, event adapters and scrape-time collectors

## Evidence

`MetricsSuite`, `CatalogueSuite`, `CollectorsSuite`, `EventMetricsSuite`, `MetricsServerSuite`, `DocsSuite`; 149 documented catalogue definitions. Historical soak and pool scrape checks below compare exported values with logs/forging state; the later G5 soak checks both endpoints with 230 rendered series. Full check retains these suites

## Explicitly unchecked or unconfirmed

no claim of full Haskell metric or tracing equivalence; unexported series and semantic differences remain in running.md "Metrics" and roadmap J3e. Definition count differs from rendered series because labels and histograms expand it
