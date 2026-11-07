### Added (monitoring, #813)

- The monitoring server runs an Alertmanager, and Prometheus sends its alerts there. The receivers stay the operator's: `deploy.sh` writes `server/alertmanager/alertmanager.yml` from the committed example (which sends nothing and shows ntfy webhooks) when it is missing and never overwrites it. Alerts are grouped by host, routed on `severity` (`info` stays on the dashboard), resolved notices are sent, and a node's other alerts are held back while it is down or missing.
- `PoolNodeMetricsMissing` fires when a node with a `scarab.role` label stops reporting altogether (its container is gone, lost its labels, or is no longer scraped), which `PoolNodeDown` cannot see; `HostMetricsMissing` fires when a host's agent stops pushing.

### Changed (monitoring, #813)

- The agent turns the container label `scarab.role` into the series label `role`. The forging, leadership and KES-period alerts select `role="pool"`, so a node that does not forge no longer fires `PoolNotCheckingSlots` and `PoolNoBlocksFor48h`; run a producer with `--label scarab.role=pool`.

### Fixed (monitoring, #813)

- `deploy.sh agent` keeps the host's `TEXTFILE_DIR` when it rewrites the agent's `.env`; a redeploy no longer drops the KES rotation driver's metrics, which set off `PoolKesRotationMetricsMissing`.
- `PoolRestarted` selects the node's own series (`job="scarab"`); a restart of the monitoring stack's Prometheus no longer fires it.
