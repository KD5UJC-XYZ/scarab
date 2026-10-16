#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Writes the provisioned Grafana dashboards (dashboards/*.json) from this file.

Edit here and rerun (`python3 build_dashboards.py`); the JSON is generated, not hand-edited.

The run panels read the node's log through Loki: every event except the per-block `extended` line,
plus one `extended` line per 1000 blocks (the stdout segment each run writes). So they work for
every build, before and after the node's /metrics endpoint. Host and container panels read
Prometheus (node exporter and cAdvisor, pushed by the agent on each worker).
"""
import json
import pathlib

LOKI = {"type": "loki", "uid": "loki"}
PROM = {"type": "prometheus", "uid": "prometheus"}
SEL = 'host=~"$host", container=~"$container"'
EXT = '{' + SEL + ', event="extended"}'


def per_run(expr: str) -> str:
    return f"max by (host, container) ({expr})"


def unwrap(field: str, path: str, window: str = "$__auto") -> str:
    return f'max_over_time({EXT} | json {field}="{path}" | unwrap {field} [{window}])'


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
                "lineWidth": 1, "fillOpacity": 10, "showPoints": "never", "spanNulls": True,
                "stacking": {"mode": "normal" if stack else "none"}}}, "overrides": []},
            "options": {"legend": {"displayMode": "list", "placement": "bottom"},
                        "tooltip": {"mode": "multi", "sort": "desc"}}}


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
    p.row("Sync")
    p.line(
        (5, stat("Block", LOKI, per_run(unwrap("b", "block.blockNo")), decimals=0,
                 desc="Block number of the newest sampled `extended` line (one per 1000 blocks).")),
        (5, stat("Synced", LOKI,
                 f'{per_run(unwrap("s", "block.slot"))} / {per_run(unwrap("p", "peerTip.slot"))} * 100',
                 unit="percent", decimals=2,
                 desc="Slot of our tip over the slot of the peer's tip.",
                 thresholds=[{"color": "orange", "value": None}, {"color": "green", "value": 99.99}])),
        (4, stat("Epoch", LOKI, per_run(unwrap("e", "epoch")), decimals=0)),
        (5, stat("Validation errors (range)", LOKI,
                 'sum by (host, container) (count_over_time({' + SEL + ', event="error"} [$__range]))',
                 decimals=0, desc="`error` events in the selected time range.",
                 thresholds=[{"color": "green", "value": None}, {"color": "red", "value": 1}])),
        (5, stat("Exits (range)", LOKI,
                 'sum by (host, container) (count_over_time({' + SEL + ', event="exited"} [$__range]))',
                 decimals=0, desc="The JVM stopped (a stop, a crash or a restart).",
                 thresholds=[{"color": "green", "value": None}, {"color": "orange", "value": 1}])),
        h=5)
    p.line(
        (12, series("Block number", LOKI, [(per_run(unwrap("b", "block.blockNo")), "{{host}} {{container}}")])),
        (12, series("Blocks per second", LOKI, [(
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
        (12, logs("Errors, exits and stack traces", '{' + SEL + ', event=~"error|exited|text"}')),
        (12, logs("Epochs", '{' + SEL + ', event=~"epoch|epochValidated|eraTransition"}')), h=10)
    p.row("Node metrics (/metrics; runs started with --metrics-listen)")
    M = 'host=~"$host", container=~"$container"'
    p.line(
        (6, stat("Synced (metrics)", PROM,
                 f'100 * max by (host, container) (cardano_node_metrics_slotNum_int{{{M}}}) '
                 f'/ max by (host, container) (scarab_peer_tip_slot{{{M}}})',
                 unit="percent", decimals=2,
                 thresholds=[{"color": "orange", "value": None}, {"color": "green", "value": 99.99}])),
        (6, stat("Block (metrics)", PROM, f'max by (host, container) (cardano_node_metrics_blockNum_int{{{M}}})',
                 decimals=0)),
        (6, stat("Validation errors", PROM,
                 f'sum by (host, container) (increase(scarab_validation_errors_total{{{M}}}[$__range]))',
                 decimals=0, thresholds=[{"color": "green", "value": None}, {"color": "red", "value": 1}])),
        (6, stat("Connections established", PROM,
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
            variable("host", "Host", "host", '{job="scarab"}'),
            variable("container", "Run", "container", '{job="scarab", host=~"$host"}')]},
        "panels": p.panels, "schemaVersion": 39, "version": 1,
    }


if __name__ == "__main__":
    out = pathlib.Path(__file__).parent / "dashboards"
    out.mkdir(exist_ok=True)
    (out / "scarab-runs.json").write_text(json.dumps(runs_dashboard(), indent=2) + "\n")
    print("wrote", out / "scarab-runs.json")
