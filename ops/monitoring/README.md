# Monitoring the long runs

Prometheus, Loki and Grafana on one always-on host (the *server*), and a Grafana Alloy *agent* on
each machine that runs Scarab containers. Agents push outward, so a worker opens no port (Docker
Desktop on Windows cannot publish one to the network without a firewall rule).

```
worker (worker-a, worker-b, ...)                 server (monitor-host)
  scarab-* containers ── stdout ──┐                 Loki        :3100  log streams, 30 days
  host /proc, /sys, docker ───────┼── Alloy ──push──▶ Prometheus  :9090  metrics, 180 days
  scarab-* /metrics ─────────────┘                 Grafana     :3000  dashboards
```

## What is collected

- **Logs**: the stdout of every container whose name starts with `scarab-` (the agent's own
  container excepted), labelled `host`, `container` and `event` (the node's JSON event name; `text`
  for lines that are not JSON, such as a JVM stack trace). A long run writes every event except the
  per-block `extended` line, plus one `extended` line in 1000 (the run script in
  [docs/running/logging.md](../../docs/running/logging.md)), so the streams stay small and the run panels can still
  plot progress from them.
- **Host and container metrics**: node exporter (`node_*`) and cAdvisor (`container_*`), labelled
  `host`.
  On Docker Desktop (worker-b) cAdvisor sees the containers' cgroups but cannot map them to
  containers (the engine's data directory is not visible from the VM), so per-container series are
  missing there; the node's own `/metrics` covers its memory and CPU. Docker Desktop also has no
  `/proc/net` for the agent, hence `UNIX_DISABLED_COLLECTORS` for that host.
- **Node metrics**: the node's `/metrics` (roadmap J3, [#47](https://github.com/KD5UJC-XYZ/scarab/issues/47)),
  scraped every 15 s from every `scarab-*` container that carries the label `scarab.metrics.port=<port>`.

## Dashboards

All five Scarab dashboards link to one another and carry the time range. Links between the single-node views also retain their node selection. Fleet navigation to a single-node view resets its selectors, so a fleet-wide All selection cannot produce an empty view; click a value in Overview for a drill-down that retains that specific node.
The new views refresh once a minute. Deeper diagnostic rows are collapsed so their queries run
when opened. The existing Node Exporter Full dashboard remains available for machine resources.

| Dashboard | UID | What to use it for |
|---|---|---|
| **Scarab overview** | `scarab-overview` | Fleet comparison: scrape health, phase, uptime, ordinary plus big-ledger peer totals versus targets, observed tip difference, application activity and notable events. Start here. |
| **Scarab network** | `scarab-network` | One node's governor temperatures/targets, discovery origins and ledger maps, promotion/demotion failures and jobs, retry backoff, churn, transport lifecycle/directions, inbound events and searchable peer investigations. |
| **Scarab chain tip and ledger** | `scarab-chain-tip` | One node's tip, applications and validation timings with sample counts, density, headers/ChainSync, rollbacks, epoch/ledger population, local mempool and restart/snapshot evidence. |
| **Scarab pool** | `scarab-pool` | Our producer's forging/adoption/loss, leadership, KES and certificate, rotation driver, alerts, sync and total peer temperatures; collapsed details show our forged body sizes, slot-start-to-adoption timing and public identity. Node, host-only rotation and global missing-metrics alert scopes are shown separately. |
| **Scarab runs** | `scarab-runs` | Live metric-based sync summary, retained sampled synchronization history, raw node logs and host/container resource charts. Its log-based block panels sample one in 1,000 extensions; use the new metric-based views at the tip. |

The **Network** and **Chain tip** views select one host and dependent node, keeping networks
separate; **Overview** can compare several nodes without combining their tips or populations.
Defaults select the producer on this installation; use the selectors or a dashboard URL with
`?var-host=<host>&var-container=<container>` on another deployment. Grafana provisioning imports
new dashboard files automatically; dashboard-only updates do not require node restarts.

The sources are [`build_dashboards.py`](server/grafana/build_dashboards.py) and its
[`overview_dashboard.py`](server/grafana/overview_dashboard.py),
[`network_dashboard.py`](server/grafana/network_dashboard.py) and
[`chain_dashboard.py`](server/grafana/chain_dashboard.py) and
[`pool_details.py`](server/grafana/pool_details.py) modules. Edit the sources and regenerate:

```sh
python3 ops/monitoring/server/grafana/build_dashboards.py
python3 -m unittest discover -s scripts/tests -p 'test_dashboard*.py' -v
```

### Reading the measurements

- **Temperatures:** hot is active, warm is established minus active, cold is known minus
  established (including peers currently connecting or awaiting retry). Warm can include closing/cooling peers. Ordinary and big-ledger sets are
  disjoint: add them for totals. Local roots are subsets and must not be added again. Known,
  established and active are nested sets; their target charts are not stacked. Deprecated local-root
  temperature names have historically reversed meanings, so the detailed view uses explicit
  known/established/active local-root counters.
- **Transport:** the connection manager and peer governor report different layers. Their counts
  can disagree; the dashboard shows them independently and does not equate a logical hot peer with
  one physical TCP connection. Direction gauges include reservations/negotiation; duplex overlaps
  inbound/outbound and must not be added as a third direction.
- **Tip:** observed peer lag uses the peer tip reported on the follower's last extension. It is
  neither an independently polled highest network tip nor a wall-clock freshness guarantee. A fresh
  scrape proves telemetry ingestion, not a new block or a fresh event-driven collector value.
  Compare activity and peer state alongside lag; do not compare mainnet and testnet heights.
- **Activity and timing:** follower application counts can include reapplication after a rollback;
  they are not unique canonical blocks or transaction throughput. Successful validation timings
  exclude refusals, which are counted separately. ChainSync round duration includes waiting and is
  not RTT. The singleton block-fetch histogram may have zero samples while range fetching is active;
  latency charts are gated by observation counts and show samples beside them.
- **Quiet or missing data:** enum-labelled series can remain absent until their first event; ledger
  population refreshes at epoch events; snapshot size/write duration appear after a write. Zero and
  missing are distinct. Rates require at least two scrapes; increases are extrapolated and can be
  fractional. Empty latency quantiles do not become zero; missing chart segments are not bridged.
- **Scope limits:** current telemetry has no continuous received-block size/transaction-count or
  producing-pool feed, per-peer RTT/bandwidth, or complete competing-branch/tine graph. Sampled
  extension and rollback logs do carry block hashes. Own-pool body sizes from retained `forged` events, slot-start-to-local-adoption timings, leader wins and forged/adopted/lost
  events are available in **Pool**; they do not enumerate winners across the whole network.

No dashboard collects additional chain data, changes log sampling, adds metric labels or reads
node databases. Detailed log views are capped at 200 lines. The peer regex filters retained event
investigations, not Prometheus series. Community **Node Exporter Full** (grafana.com 1860) is fetched
at deploy time into `grafana/dashboards/imported/` on the server, not committed.

### Verify against a running monitoring stack

The read-only verifier executes every generated target, including collapsed rows, using its actual
Prometheus/Loki query mode. It records substituted expressions, durations, sample counts, nonfinite
values, empty results and API errors. Empty results alone are not failures:

```sh
python3 scripts/verify_dashboards.py \
  --prometheus-url http://<monitor-host>:9090 --loki-url http://<monitor-host>:3100 \
  --host <worker> --container <node> --output /tmp/dashboard-queries.json
```

Repeat for each node. `scripts/test_dashboard_queries.py` extracts the generated peer totals, target shortfalls and
node/host/global alert-scope expressions and checks them with Prometheus `promtool`, including separate populations,
local-root double-counting, overshoot, observed zero and missing data. To use Docker's promtool,
prepare fixtures with `--write-dir /tmp/dashboard-query-tests`, then run
`promtool test rules dashboard.tests.json` with that directory as the working directory inside the
Prometheus image. Neither verification changes the nodes or monitoring data.

## Pool alerts

[`server/prometheus/rules/pool.yml`](server/prometheus/rules/pool.yml) holds the alert rules for a block producer
(KES periods, the rotation driver, forged blocks that were not adopted, sync, peers, stalls, restarts); the server's
Prometheus loads them (`rule_files`) and `server/prometheus/tests/pool.test.yml` tests them
(`promtool test rules`). The stack has no Alertmanager and the rules name no receiver: route on the `severity` label
with your own Alertmanager, or build Grafana alerts on the same expressions. The rotation driver's metrics reach
Prometheus through the agent's textfile directory (`TEXTFILE_DIR`). The alert table, the thresholds and their reasons
are in [docs/running/monitoring.md](../../docs/running/monitoring.md).

## Deploy

From a checkout, with ssh access to the hosts:

```bash
cd ops/monitoring
# the server, published on its VPN address
SSH_OPTS="-i ~/.ssh/<key> -o IdentitiesOnly=yes" \
  ./deploy.sh server root@<server> /srv/scarab-monitoring <server VPN address>
# an agent per worker (Docker Desktop hosts also set UNIX_DISABLED_COLLECTORS)
./deploy.sh agent <user>@<worker> /home/<user>/scarab/monitoring-agent worker-a <server VPN address>
UNIX_DISABLED_COLLECTORS=arp,netdev,netstat,softnet SSH_OPTS="-p 2222" \
  ./deploy.sh agent <user>@<worker> /home/<user>/git/scarab/monitoring-agent worker-b <server VPN address>
```

Rerunning a deploy updates the files and restarts what changed; the server's `.env` and data are
kept. Containers restart with the host (`restart: unless-stopped`).

To try the server locally: copy `server/.env.example` to `server/.env`, set a password, and
`docker compose up -d` in `server/` (ports on 127.0.0.1).

## Runs

Name run containers `scarab-<something>`; nothing else is needed for their logs. For the node's
metrics, give the container the label and the node the listener (only `/metrics` is served there;
see [docs/running/metrics.md](../../docs/running/metrics.md)):

```bash
docker run --name scarab-<run> --label scarab.metrics.port=12798 ... \
  ... follow --metrics-listen 0.0.0.0:12798 ...
```

The port is not published. The agent runs on Docker's default bridge, like the runs, and reaches the
port on the container's bridge address. The "Node metrics" row of the dashboard reads these series.
