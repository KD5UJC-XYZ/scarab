#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Writes the provisioned Grafana dashboards (dashboards/*.json) from this file.

Edit here and rerun (`python3 build_dashboards.py`); the JSON is generated, not hand-edited.

The run panels read the node's log through Loki: every event except the per-block `extended` line,
plus one `extended` line per 1000 blocks (the stdout segment each run writes). So they work for
every build, before and after the node's /metrics endpoint. Host and container panels read
Prometheus (node exporter and cAdvisor, pushed by the agent on each worker).
The pool dashboard (`scarab-pool`) reads Prometheus only: the node's /metrics and the rotation driver's textfile.
"""
import importlib.util
import json
import sys
import pathlib

LOKI = {"type": "loki", "uid": "loki"}
PROM = {"type": "prometheus", "uid": "prometheus"}
SEL = 'host=~"$host", container=~"$container"'
EXT = '{' + SEL + ', event="extended"}'
# a start replays the volatile and immutable blocks above the restored snapshot before it follows
REPLAY = '{' + SEL + ', event="replayProgress"}'


def per_run(expr: str) -> str:
    return f"max by (host, container) ({expr})"


def unwrap(field: str, path: str, window: str = "$__auto") -> str:
    return f'max_over_time({EXT} | json {field}="{path}" | unwrap {field} [{window}])'


def replayed(window: str = "$__auto") -> str:
    """The tip block of the newest `replayProgress` line (one per 20,000 replayed blocks)."""
    return f'max_over_time({REPLAY} | json b="tip.blockNo" | unwrap b [{window}])'


class Layout:
    def __init__(self):
        self.panels, self.y, self.next_id = [], 0, 1

    def row(self, title):
        self.panels.append({"type": "row", "title": title, "id": self._id(), "collapsed": False,
                            "gridPos": {"h": 1, "w": 24, "x": 0, "y": self.y}})
        self.y += 1

    def line(self, *specs, h=8):
        x = 0
        for width, panel in specs:
            panel["id"] = self._id()
            panel["gridPos"] = {"h": h, "w": width, "x": x, "y": self.y}
            self.panels.append(panel)
            x += width
        self.y += h

    def _id(self):
        self.next_id += 1
        return self.next_id - 1


def target(ds, expr, legend="{{host}} {{container}}", instant=False, ref="A"):
    t = {"datasource": ds, "expr": expr, "legendFormat": legend, "refId": ref}
    if instant:
        t["queryType" if ds is LOKI else "instant"] = "instant" if ds is LOKI else True
    return t


def stat(title, ds, expr, unit="none", decimals=None, desc="", thresholds=None):
    fc = {"defaults": {"unit": unit, "color": {"mode": "thresholds"},
                       "thresholds": {"mode": "absolute",
                                      "steps": thresholds or [{"color": "green", "value": None}]}},
          "overrides": []}
    fc["defaults"]["noValue"] = "No samples"
    if decimals is not None:
        fc["defaults"]["decimals"] = decimals
    return {"type": "stat", "title": title, "description": desc, "datasource": ds,
            "targets": [target(ds, expr, instant=True)], "fieldConfig": fc,
            "options": {"reduceOptions": {"calcs": ["lastNotNull"], "fields": "", "values": False},
                        "textMode": "value_and_name", "colorMode": "value", "graphMode": "none",
                        "justifyMode": "auto", "orientation": "horizontal"}}


def series(title, ds, exprs, unit="none", desc="", stack=False):
    targets = [target(ds, e, legend=l, ref=chr(65 + i)) for i, (e, l) in enumerate(exprs)]
    return {"type": "timeseries", "title": title, "description": desc, "datasource": ds,
            "targets": targets,
            "fieldConfig": {"defaults": {"unit": unit, "custom": {
                "lineWidth": 1, "fillOpacity": 10, "showPoints": "never", "spanNulls": False,
                "stacking": {"mode": "normal" if stack else "none"}}}, "overrides": []},
            "options": {"legend": {"displayMode": "list", "placement": "bottom"},
                        "tooltip": {"mode": "multi", "sort": "desc"}}}


def text(title, content):
    """A guide or an explicit telemetry limitation, without a synthetic data query."""
    return {"type": "text", "title": title, "options": {"mode": "markdown", "content": content}}


def suite_links(single_node=False):
    return [{"type": "link", "title": title, "url": f"/d/{uid}", "includeVars": single_node or uid not in {"scarab-network", "scarab-chain-tip"},
             "keepTime": True, "targetBlank": False} for uid, title in (
        ("scarab-overview", "Overview"), ("scarab-network", "Network"),
        ("scarab-chain-tip", "Chain tip"), ("scarab-pool", "Pool"), ("scarab-runs", "Runs"))]


def logs(title, expr, desc=""):
    return {"type": "logs", "title": title, "description": desc, "datasource": LOKI,
            "targets": [{"datasource": LOKI, "expr": expr, "refId": "A"}],
            "options": {"showTime": True, "wrapLogMessage": True, "sortOrder": "Descending",
                        "enableLogDetails": True, "prettifyLogMessage": False, "dedupStrategy": "none"}}


def variable(name, label, query_label, stream):
    return {"name": name, "label": label, "type": "query", "datasource": LOKI,
            "query": {"type": 1, "label": query_label, "stream": stream,
                      "refId": "LokiVariableQueryEditor-VariableQuery"},
            "refresh": 2, "includeAll": True, "multi": True, "allValue": ".+",
            "current": {"text": ["All"], "value": ["$__all"]}, "sort": 1}


def runs_dashboard():
    p = Layout()
    p.row("Live sync · metrics")
    p.line(
        (5, stat("Current block", PROM,
                 'max by (host, container) (cardano_node_metrics_blockNum_int{' + SEL + '})',
                 decimals=0, desc="Current selected tip from /metrics; use sampled history below for older runs.")),
        (5, stat("Peer slot difference", PROM,
                 'max by (host, container) (scarab_peer_tip_slot{' + SEL + '}) - '
                 'max by (host, container) (cardano_node_metrics_slotNum_int{' + SEL + '})',
                 decimals=0, desc="Last peer tip recorded on follower extension minus our selected tip. Not an independent network clock.")),
        (4, stat("Current epoch", PROM,
                 'max by (host, container) (cardano_node_metrics_epoch_int{' + SEL + '})', decimals=0)),
        (5, stat("Validation errors (range)", PROM,
                 'sum by (host, container) (increase(scarab_validation_errors_total{' + SEL + '}[$__range]))',
                 decimals=0, thresholds=GOOD_ZERO)),
        (5, stat("Process restarts (range)", PROM,
                 'max by (host, container) (changes(process_start_time_seconds{' + SEL + '}[$__range]))',
                 decimals=0, desc="Changes in observed process start time. Requires scrapes on both sides of a restart.")),
        h=5)
    p.line(
        (12, series("Sampled historical block number", LOKI, [
            (per_run(unwrap("b", "block.blockNo")), "{{host}} {{container}}"),
            (per_run(replayed()), "replaying {{host}} {{container}}")],
            desc="Followed blocks, and the blocks a start replays from its own store.")),
        (12, series("Sampled historical blocks per second", LOKI, [(
            per_run(f'rate_counter({EXT} | json b="block.blockNo" | unwrap b [10m])'),
            "{{host}} {{container}}")], unit="none",
            desc="From the sampled block numbers over 10 minutes.")))
    p.line(
        (12, series("Errors and exits", LOKI, [
            ('sum by (host, container) (count_over_time({' + SEL + ', event="error"} [$__auto]))', "errors {{container}}"),
            ('sum by (host, container) (count_over_time({' + SEL + ', event="exited"} [$__auto]))', "exits {{container}}")])),
        (12, series("Log lines by event", LOKI, [(
            'sum by (event) (count_over_time({' + SEL + ', event!="extended"} [$__auto]))', "{{event}}")],
            stack=True)))
    p.row("Logs")
    p.line((24, logs("Node log (without the sampled block lines)", '{' + SEL + ', event!="extended"}')), h=14)
    p.line(
        (12, logs("Errors, exits and stack traces", '{' + SEL + ', event=~"error|exited|text|snapshotRefused|snapshotFailed"}')),
        (12, logs("Epochs", '{' + SEL + ', event=~"epoch|epochValidated|eraTransition"}')), h=10)
    p.row("Node metrics (/metrics; runs started with --metrics-listen)")
    M = 'host=~"$host", container=~"$container"'
    # Snapshot refusal/write failure leaves the node running but can increase replay on restart;
    # include the snapshot-problem counter so the overview exposes this degradation (B29).
    p.line(
        (5, stat("Synced (metrics)", PROM,
                 f'100 * max by (host, container) (cardano_node_metrics_slotNum_int{{{M}}}) '
                 f'/ max by (host, container) (scarab_peer_tip_slot{{{M}}})',
                 unit="percent", decimals=2,
                 thresholds=[{"color": "orange", "value": None}, {"color": "green", "value": 99.99}])),
        (5, stat("Block (metrics)", PROM, f'max by (host, container) (cardano_node_metrics_blockNum_int{{{M}}})',
                 decimals=0)),
        (5, stat("Validation errors", PROM,
                 f'sum by (host, container) (increase(scarab_validation_errors_total{{{M}}}[$__range]))',
                 decimals=0, thresholds=[{"color": "green", "value": None}, {"color": "red", "value": 1}])),
        (4, stat("Snapshots refused or failed", PROM,
                 f'sum by (host, container) (increase(scarab_snapshot_problems_total{{{M}, kind=~"Refused|Failed"}}[$__range]))',
                 decimals=0, thresholds=[{"color": "green", "value": None}, {"color": "red", "value": 1}])),
        (5, stat("Connections established", PROM,
                 f'sum by (host, container) (scarab_connections{{{M}, state="Established"}})', decimals=0)),
        h=5)
    p.line(
        (8, series("Blocks applied per second", PROM, [(
            f'sum by (host, container) (rate(scarab_blocks_applied_total{{{M}}}[5m]))', "{{host}} {{container}}")])),
        (8, series("Block apply time", PROM, [
            (f'sum by (host, container) (rate(scarab_block_apply_seconds_sum{{{M}}}[5m])) '
             f'/ sum by (host, container) (rate(scarab_block_apply_seconds_count{{{M}}}[5m]))', "mean {{container}}"),
            (f'histogram_quantile(0.99, sum by (host, container, le) (rate(scarab_block_apply_seconds_bucket{{{M}}}[5m])))',
             "p99 {{container}}")], unit="s")),
        (8, series("JVM heap and resident memory", PROM, [
            (f'sum by (host, container) (jvm_memory_bytes_used{{{M}, area="heap"}})', "heap {{container}}"),
            (f'max by (host, container) (process_resident_memory_bytes{{{M}}})', "rss {{container}}")], unit="bytes")))
    p.line(
        (8, series("GC time (fraction of wall time)", PROM, [(
            f'sum by (host, container) (rate(jvm_gc_collection_seconds_total{{{M}}}[5m]))', "{{container}}")],
            unit="percentunit")),
        (8, series("Snapshot write time", PROM, [(
            f'max by (host, container) (scarab_snapshot_last_write_seconds{{{M}}})', "{{container}}")], unit="s")),
        (8, series("CPU pool: longest step and timer lateness", PROM, [
            (f'max by (host, container) (scarab_cpu_longest_step_seconds{{{M}}})', "longest step {{container}}"),
            (f'max by (host, container) (scarab_scheduling_timer_lateness_max_seconds{{{M}}})', "timer lateness {{container}}")],
            unit="s")))
    p.row("Hosts and containers")
    p.line(
        (8, series("Container memory", PROM, [(
            'container_memory_working_set_bytes{host=~"$host", name=~"scarab-.+"}', "{{host}} {{name}}")],
            unit="bytes")),
        (8, series("Container CPU (cores)", PROM, [(
            'rate(container_cpu_usage_seconds_total{host=~"$host", name=~"scarab-.+"}[5m])', "{{host}} {{name}}")])),
        (8, series("Host CPU busy", PROM, [(
            '100 * (1 - avg by (host) (rate(node_cpu_seconds_total{host=~"$host", mode="idle"}[5m])))', "{{host}}")],
            unit="percent")))
    p.line(
        (8, series("Host memory available", PROM, [(
            'node_memory_MemAvailable_bytes{host=~"$host"}', "{{host}}")], unit="bytes")),
        (8, series("Disk free", PROM, [(
            'node_filesystem_avail_bytes{host=~"$host", fstype!~"tmpfs|overlay|squashfs"}', "{{host}} {{mountpoint}}")],
            unit="bytes")),
        (8, series("Disk writes", PROM, [(
            'sum by (host) (rate(node_disk_written_bytes_total{host=~"$host"}[5m]))', "{{host}}")], unit="Bps")))
    return {
        "uid": "scarab-runs", "title": "Scarab runs", "tags": ["scarab"],
        "description": "Long sync runs and pools on the workers: progress, errors, logs, hosts.",
        "editable": False, "graphTooltip": 1, "time": {"from": "now-6h", "to": "now"},
        "refresh": "30s",
        "timepicker": {"refresh_intervals": ["10s", "30s", "1m", "5m", "15m"]},
        "templating": {"list": [
            prom_variable("host", "Host", 'label_values(up{job="scarab"}, host)'),
            prom_variable("container", "Run", 'label_values(up{job="scarab", host=~"$host"}, container)')]},
        "panels": p.panels, "schemaVersion": 39, "version": 1,
    }



def prom_variable(name, label, query):
    return {"name": name, "label": label, "type": "query", "datasource": PROM,
            "query": {"query": query, "refId": f"{name}-variable"}, "definition": query,
            "refresh": 2, "includeAll": True, "multi": True, "allValue": ".+",
            "current": {"text": ["All"], "value": ["$__all"]}, "sort": 1}


def constant(name, label, value):
    """A box the viewer can change (the pool's expected blocks, the epoch length)."""
    return {"name": name, "label": label, "type": "textbox", "query": value,
            "current": {"text": value, "value": value}, "options": [{"selected": True, "text": value, "value": value}]}


GOOD_ZERO = [{"color": "green", "value": None}, {"color": "red", "value": 1}]
GOOD_ONE = [{"color": "red", "value": None}, {"color": "green", "value": 1}]


def pool_dashboard():
    """Panels for a block producer: forging, KES and the certificate, the rotation driver, sync and peers.
    The alerts that go with them are in prometheus/rules/pool.yml (docs/running/monitoring.md)."""
    p = Layout()
    M = 'host=~"$host", container=~"$container"'
    H = 'host=~"$host"'  # the driver's textfile metrics come from the host's node exporter: no container label

    def per(metric):
        return f"max by (host, container) ({metric}{{{M}}})"

    def inc(metric, window="$__range"):
        return f"sum by (host, container) (increase({metric}{{{M}}}[{window}]))"

    def drv(metric):
        return f"max by (host) ({metric}{{{H}}})"

    FORGED, ADOPTED = "cardano_node_metrics_Forge_forged_counter", "cardano_node_metrics_Forge_adopted_counter"
    LOST, CHECKED = "cardano_node_metrics_Forge_didnt_adopt_counter", "cardano_node_metrics_Forge_about_to_lead_counter"
    LEADER = "cardano_node_metrics_Forge_node_is_leader_counter"
    CANNOT = "cardano_node_metrics_nodeCannotForge_int"
    REMAINING = "cardano_node_metrics_remainingKESPeriods_int"
    CURRENT = "cardano_node_metrics_currentKESPeriod_int"
    EXPIRY = "cardano_node_metrics_operationalCertificateExpiryKESPeriod_int"
    START = "cardano_node_metrics_operationalCertificateStartKESPeriod_int"
    OURS, PEERS = "cardano_node_metrics_slotNum_int", "scarab_peer_tip_slot"
    LAG = f"{per(PEERS)} - {per(OURS)}"
    def temperature_total(kind):
        return f'{per("cardano_node_metrics_peerSelection_" + kind + "_int")} + {per("cardano_node_metrics_peerSelection_" + kind + "BigLedgerPeers_int")}'

    HOT_TOTAL, WARM_TOTAL, COLD_TOTAL = (temperature_total(k) for k in ("Hot", "Warm", "Cold"))
    DRV = "scarab_kes_rotation_"

    p.row("Alerts and state")
    p.line(
        (4, stat("Node alerts firing", PROM, f'count by (host, container) (ALERTS{{alertstate="firing", alertname=~"Pool.*", {M}}}) or on (host, container) (0 * min by (host, container) (up{{job="scarab", {M}}}))',
                 decimals=0, desc="Firing pool alerts carrying the selected node labels. Host-only rotation and global missing-metrics alerts are shown separately below.", thresholds=GOOD_ZERO)),
        (4, stat("Node up", PROM, f'min by (host, container) (up{{job="scarab", {M}}})', decimals=0,
                 desc="1 when the node's /metrics answers the scrape.", thresholds=GOOD_ONE)),
        (4, stat("Forging enabled", PROM, per("cardano_node_metrics_forging_enabled_int"), decimals=0,
                 thresholds=GOOD_ONE)),
        (4, stat("Phase", PROM, f'max by (host, container, phase) (scarab_node_phase{{{M}}}) == 1',
                 desc="Restoring, Replaying or Following; a producer forges only while Following.")),
        (4, stat("Stalls begun (range)", PROM, inc("scarab_follower_stalls_total"), decimals=0,
                 desc="Watchdog stalls begun in the interval. A current stalled-state gauge is unavailable on some deployed builds.",
                 thresholds=GOOD_ZERO)),
        (4, stat("Uptime", PROM, f'time() - {per("process_start_time_seconds")}', unit="s",
                 desc="Since the process started; a drop to near zero is a restart.")),
        h=5)
    p.row("Rotation and monitoring alerts")
    p.line(
        (8, stat("Host rotation alerts firing", PROM,
                 f'count by (host) (ALERTS{{alertstate="firing", alertname=~"PoolKesRotation.*", {H}}}) '
                 f'or on (host) (0 * {drv(DRV + "last_run_timestamp_seconds")})',
                 decimals=0, thresholds=GOOD_ZERO,
                 desc="KES driver alerts carry host only, so node/container filtering must not hide them. Missing driver telemetry remains missing.")),
        (8, stat("Global rotation-metrics alert", PROM,
                 'count(ALERTS{alertstate="firing", alertname="PoolKesRotationMetricsMissing", host=""}) or vector(0)',
                 decimals=0, thresholds=GOOD_ZERO,
                 desc="Fleet-wide alert from absent(driver metrics), which has no host or container labels. Intentionally unfiltered: 0 means this specific global alert is not firing.")),
        (8, stat("Driver sample age", PROM,
                 f'time() - max by (host) (timestamp(scarab_kes_rotation_last_run_timestamp_seconds{{{H}}}))',
                 unit="s", desc="Age of the ingested textfile metric, not the time since rotation ran. Compare Last run below.")), h=5)
    p.row("Forging")
    p.line(
        (4, stat("Blocks forged (range)", PROM, inc(FORGED), decimals=0)),
        (4, stat("Blocks adopted (range)", PROM, inc(ADOPTED), decimals=0,
                 desc="Forged blocks that became the tip of our chain.")),
        (4, stat("Blocks lost (range)", PROM, inc(LOST), decimals=0,
                 desc="Forged blocks that did not stay on our chain (a lost slot battle, or replaced).",
                 thresholds=[{"color": "green", "value": None}, {"color": "orange", "value": 1}])),
        (4, stat("Forge failures (range)", PROM,
                 f'sum by (host, container) (increase({CANNOT}{{{M}}}[$__range]))',
                 decimals=0, desc="Increase in the cumulative cannot-forge gauge (treated as a monotonic total with reset correction). Slots our pool led but could not forge in (`forgeFailed`).", thresholds=GOOD_ZERO)),
        (4, stat("Leader slots won (range)", PROM, inc(LEADER), decimals=0)),
        (4, stat("Expected per epoch", PROM, 'vector($expected)', decimals=1,
                 desc="The expected blocks per epoch for the pool's stake share (the `expected` box above).")),
        h=5)
    p.line(
        (12, series("Blocks per epoch against the expectation", PROM, [
            (inc(FORGED, "$epoch"), "forged in the last $epoch {{container}}"),
            (inc(ADOPTED, "$epoch"), "adopted in the last $epoch {{container}}"),
            (inc(LOST, "$epoch"), "lost in the last $epoch {{container}}"),
            ("vector($expected)", "expected per epoch")],
            desc="A sliding window of one epoch (the `epoch` box: 5d on Preprod and Mainnet, 1d on Preview). "
                 "The pool should hover around the expected line; a single epoch varies a lot (Poisson).")),
        (12, series("Blocks per hour", PROM, [
            (inc(FORGED, "1h"), "forged {{container}}"), (inc(ADOPTED, "1h"), "adopted {{container}}"),
            (inc(LOST, "1h"), "lost {{container}}")])))
    p.line(
        (12, series("Leader slots checked and won (per hour)", PROM, [
            (inc(CHECKED, "1h"), "checked {{container}}"), (inc(LEADER, "1h"), "won {{container}}")],
            desc="Slots the forger checked for leadership against the slots the pool led; checked should be "
                 "about one per second of a Following node.")),
        (12, series("Slots that could not be forged or were given up", PROM, [
            (per(CANNOT), "could not forge {{container}}"),
            (per("cardano_node_metrics_slotsMissed_int"), "missed (ForgingStats) {{container}}"),
            (f'sum by (host, container, reason) (increase(scarab_forge_skipped_total{{{M}}}[1h]))',
             "given up/h {{reason}} {{container}}")])))
    p.row("KES key and operational certificate")
    p.line(
        (4, stat("KES periods remaining", PROM, per(REMAINING), decimals=0,
                 thresholds=[{"color": "red", "value": None}, {"color": "orange", "value": 3},
                             {"color": "green", "value": 10}],
                 desc="Alerts: warning below 10, critical below 3.")),
        (4, stat("Current KES period", PROM, per(CURRENT), decimals=0)),
        (4, stat("Certificate start period", PROM, per(START), decimals=0)),
        (4, stat("Certificate expiry period", PROM, per(EXPIRY), decimals=0)),
        (4, stat("Certificate counter", PROM, per("scarab_opcert_counter"), decimals=0,
                 desc="The issue counter of the certificate in use; it rises by one with each rotation.")),
        (4, stat("KES persist failures (range)", PROM, inc("scarab_kes_persist_failures_total"), decimals=0,
                 desc="Evolved keys that could not be written back to the key file.", thresholds=GOOD_ZERO)),
        h=5)
    p.line(
        (12, series("KES periods: remaining, current and expiry", PROM, [
            (per(REMAINING), "remaining {{container}}"), (per(CURRENT), "current {{container}}"),
            (per(EXPIRY), "expiry {{container}}")],
            desc="Remaining climbs back each time the rotation driver installs a new certificate.")),
        (12, series("Certificate counter and reloads by outcome", PROM, [
            (per("scarab_opcert_counter"), "counter {{container}}"),
            (f'sum by (host, container, outcome) (increase(scarab_kes_reloads_total{{{M}}}[1h]))',
             "reloads/h {{outcome}} {{container}}"),
            (inc("scarab_kes_persist_failures_total", "1h"), "persist failures/h {{container}}")])))
    p.row("Rotation driver (scripts/pool/rotate_kes.py, textfile metrics)")
    p.line(
        (6, stat("Last success", PROM, f'time() - {drv(DRV + "last_success_timestamp_seconds")}',
                 unit="s", desc="Time since a run ended with exit status 0. Alert: critical beyond 3 hours.",
                 thresholds=[{"color": "green", "value": None}, {"color": "orange", "value": 7200},
                             {"color": "red", "value": 10800}])),
        (6, stat("Last run", PROM, f'time() - {drv(DRV + "last_run_timestamp_seconds")}', unit="s")),
        (6, stat("Last exit status", PROM, drv(DRV + "last_exit_status"), decimals=0,
                 desc="0 ok, 1 reload refused, 2 not confirmed, 3 node unreachable, 4 setup wrong.",
                 thresholds=GOOD_ZERO)),
        (6, stat("Last rotation", PROM, f'time() - {drv(DRV + "last_rotation_timestamp_seconds")}', unit="s",
                 desc="Time since the node confirmed credentials the driver installed.")),
        h=5)
    p.line(
        (12, series("Driver: periods remaining when it last looked", PROM, [(
            drv(DRV + "periods_remaining"), "{{host}}")])),
        (12, series("Driver: exit status", PROM, [(drv(DRV + "last_exit_status"), "{{host}}")])))
    p.row("Sync and peers")
    p.line(
        (6, stat("Slot lag behind the peers' tip", PROM, LAG, decimals=0,
                 desc="Last peer tip reported at follower extension minus our selected tip; not wall-clock lag or an independent network-tip measurement. Alert: above 120 for 10 minutes.",
                 thresholds=[{"color": "green", "value": None}, {"color": "orange", "value": 60},
                             {"color": "red", "value": 120}])),
        (6, stat("Peers hot", PROM, HOT_TOTAL, decimals=0, thresholds=GOOD_ONE, desc="Ordinary + big-ledger active peers; local roots are subsets, not additional peers.")),
        (6, stat("Peers warm", PROM, WARM_TOTAL, decimals=0)),
        (6, stat("Peers cold", PROM, COLD_TOTAL, decimals=0)),
        h=5)
    p.line(
        (8, series("Our tip and the peers' tip (slot)", PROM, [
            (per(OURS), "ours {{container}}"), (per(PEERS), "peers {{container}}")])),
        (8, series("Slot lag", PROM, [(LAG, "{{container}}")])),
        (8, series("Peers hot, warm and cold", PROM, [
            (HOT_TOTAL, "hot {{container}}"), (WARM_TOTAL, "warm {{container}}"), (COLD_TOTAL, "cold {{container}}")])))
    p.line(
        (12, series("Node phase and stall watchdog", PROM, [
            (f'max by (host, container, phase) (scarab_node_phase{{{M}}})', "phase {{phase}} {{container}}"),
            (inc("scarab_follower_stalls_total", "1h"), "stalls/h {{container}}")])),
        (12, series("Restarts (process start time)", PROM, [(
            per("process_start_time_seconds"), "started {{container}}")], unit="s",
            desc="A step is a restart (seconds since 1970).")))
    for panel in p.panels:
        if panel.get("title") == "Phase":
            panel["targets"][0]["legendFormat"] = "{{phase}}"
            panel["options"]["textMode"] = "name"
            panel["options"]["text"] = {"valueSize": 28}
    return {
        "uid": "scarab-pool", "title": "Scarab pool", "tags": ["scarab", "pool"],
        "description": "A block producer: forging, KES and the certificate, the rotation driver, sync and peers.",
        "editable": False, "graphTooltip": 1, "time": {"from": "now-24h", "to": "now"},
        "refresh": "1m",
        "timepicker": {"refresh_intervals": ["30s", "1m", "5m", "15m"]},
        "templating": {"list": [
            prom_variable("host", "Host", "label_values(cardano_node_metrics_forging_enabled_int, host)"),
            prom_variable("container", "Run",
                          'label_values(cardano_node_metrics_forging_enabled_int{host=~"$host"}, container)'),
            constant("expected", "Expected blocks per epoch", "14"),
            constant("epoch", "Epoch length", "5d")]},
        "panels": p.panels, "schemaVersion": 39, "version": 1,
    }


def dashboards():
    """Build every provisioned dashboard from source, also when imported by tests."""
    result = {"scarab-runs": runs_dashboard(), "scarab-pool": pool_dashboard()}
    ui = sys.modules.get(__name__)
    if ui is None:
        # importlib callers need not register the module in sys.modules.
        from types import SimpleNamespace
        ui = SimpleNamespace(**globals())
    for module_name, function in (("overview_dashboard", "overview_dashboard"),
                                  ("network_dashboard", "network_dashboard"),
                                  ("chain_dashboard", "chain_dashboard")):
        spec = importlib.util.spec_from_file_location(module_name, pathlib.Path(__file__).with_name(module_name + ".py"))
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        dashboard = getattr(module, function)(ui)
        result[dashboard["uid"]] = dashboard
    spec = importlib.util.spec_from_file_location("pool_details", pathlib.Path(__file__).with_name("pool_details.py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    result["scarab-pool"] = module.extend_pool_dashboard(ui, result["scarab-pool"])
    for dashboard in result.values():
        dashboard["links"] = suite_links(dashboard["uid"] in {"scarab-network", "scarab-chain-tip"})
    return result


if __name__ == "__main__":
    out = pathlib.Path(__file__).parent / "dashboards"
    out.mkdir(exist_ok=True)
    for name, dashboard in dashboards().items():
        (out / f"{name}.json").write_text(json.dumps(dashboard, indent=2) + "\n")
        print("wrote", out / f"{name}.json")
