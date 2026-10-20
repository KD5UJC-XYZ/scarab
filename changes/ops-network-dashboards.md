### Added (operations dashboards)

- Add linked fleet overview, detailed peer/governor and chain-tip/ledger Grafana dashboards using existing node metrics and retained logs, with collapsed diagnostics and explicit sampling/measurement limits.
- Add read-only live query verification and semantic Prometheus fixtures for peer totals, target shortfalls and missing data.

### Fixed (operations dashboards)

- Include big-ledger peers in pool totals; show node, host rotation and global missing-metrics alerts separately; display phase names and observed stall counts, and preserve missing telemetry gaps.
