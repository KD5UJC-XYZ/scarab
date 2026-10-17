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
import json
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
        (5, stat("Block", LOKI, f'{per_run(unwrap("b", "block.blockNo"))} or {per_run(replayed())}',
                 decimals=0,
                 desc="Block number of the newest sampled `extended` line (one per 1000 blocks); while a "
                      "start replays its stored blocks, the newest replayed block.")),
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
        (12, series("Block number", LOKI, [
            (per_run(unwrap("b", "block.blockNo")), "{{host}} {{container}}"),
            (per_run(replayed()), "replaying {{host}} {{container}}")],
            desc="Followed blocks, and the blocks a start replays from its own store.")),
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
        (12, logs("Errors, exits and stack traces", '{' + SEL + ', event=~"error|exited|text|snapshotRefused|snapshotFailed"}')),
        (12, logs("Epochs", '{' + SEL + ', event=~"epoch|epochValidated|eraTransition"}')), h=10)
    p.row("Node metrics (/metrics; runs started with --metrics-listen)")
    M = 'host=~"$host", container=~"$container"'
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
        # a snapshot the node could not write or refused (B29): the node keeps running and replays more at the
        # next start, so nothing else shows it
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
            variable("host", "Host", "host", '{job="scarab"}'),
            variable("container", "Run", "container", '{job="scarab", host=~"$host"}')]},
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
    The alerts that go with them are in prometheus/rules/pool.yml (docs/running.md, "Pool monitoring")."""
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
    HOT, WARM, COLD = (f"cardano_node_metrics_peerSelection_{k}_int" for k in ("Hot", "Warm", "Cold"))
    DRV = "scarab_kes_rotation_"

    p.row("Alerts and state")
    p.line(
        (4, stat("Pool alerts firing", PROM, 'count(ALERTS{alertstate="firing", alertname=~"Pool.*"}) or vector(0)',
                 decimals=0, desc="Alerts of prometheus/rules/pool.yml that are firing now.", thresholds=GOOD_ZERO)),
        (4, stat("Node up", PROM, f'min by (host, container) (up{{job="scarab", {M}}})', decimals=0,
                 desc="1 when the node's /metrics answers the scrape.", thresholds=GOOD_ONE)),
        (4, stat("Forging enabled", PROM, per("cardano_node_metrics_forging_enabled_int"), decimals=0,
                 thresholds=GOOD_ONE)),
        (4, stat("Phase", PROM, f'max by (host, container, phase) (scarab_node_phase{{{M}}}) == 1',
                 desc="Restoring, Replaying or Following; a producer forges only while Following.")),
        (4, stat("Stalled", PROM, per("scarab_follower_stalled"), decimals=0,
                 desc="1 while the node has not extended its chain for --stall-minutes with a peer ahead.",
                 thresholds=GOOD_ZERO)),
        (4, stat("Uptime", PROM, f'time() - {per("process_start_time_seconds")}', unit="s",
                 desc="Since the process started; a drop to near zero is a restart.")),
        h=5)
    p.row("Forging")
    p.line(
        (4, stat("Blocks forged (range)", PROM, inc(FORGED), decimals=0)),
        (4, stat("Blocks adopted (range)", PROM, inc(ADOPTED), decimals=0,
                 desc="Forged blocks that became the tip of our chain.")),
        (4, stat("Blocks lost (range)", PROM, inc(LOST), decimals=0,
                 desc="Forged blocks that did not stay on our chain (a lost slot battle, or replaced).",
                 thresholds=[{"color": "green", "value": None}, {"color": "orange", "value": 1}])),
        (4, stat("Forge failures (range)", PROM,
                 f'sum by (host, container) (delta({CANNOT}{{{M}}}[$__range]))',
                 decimals=0, desc="Slots the pool led but could not forge in (`forgeFailed`).", thresholds=GOOD_ZERO)),
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
        (12, series("Slots that could not be forged or were skipped", PROM, [
            (per(CANNOT), "could not forge {{container}}"),
            (per("cardano_node_metrics_slotsMissed_int"), "forger busy (missed) {{container}}"),
            (f'sum by (host, container, reason) (increase(scarab_forge_skipped_total{{{M}}}[1h]))',
             "skipped/h {{reason}} {{container}}")])))
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
                 desc="Slots between the highest tip a peer announced and ours. Alert: above 120 for 10 minutes.",
                 thresholds=[{"color": "green", "value": None}, {"color": "orange", "value": 60},
                             {"color": "red", "value": 120}])),
        (6, stat("Peers hot", PROM, per(HOT), decimals=0, thresholds=GOOD_ONE)),
        (6, stat("Peers warm", PROM, per(WARM), decimals=0)),
        (6, stat("Peers cold", PROM, per(COLD), decimals=0)),
        h=5)
    p.line(
        (8, series("Our tip and the peers' tip (slot)", PROM, [
            (per(OURS), "ours {{container}}"), (per(PEERS), "peers {{container}}")])),
        (8, series("Slot lag", PROM, [(LAG, "{{container}}")])),
        (8, series("Peers hot, warm and cold", PROM, [
            (per(HOT), "hot {{container}}"), (per(WARM), "warm {{container}}"), (per(COLD), "cold {{container}}")])))
    p.line(
        (12, series("Node phase and stall watchdog", PROM, [
            (f'max by (host, container, phase) (scarab_node_phase{{{M}}})', "phase {{phase}} {{container}}"),
            (per("scarab_follower_stalled"), "stalled {{container}}"),
            (inc("scarab_follower_stalls_total", "1h"), "stalls/h {{container}}")])),
        (12, series("Restarts (process start time)", PROM, [(
            per("process_start_time_seconds"), "started {{container}}")], unit="s",
            desc="A step is a restart (seconds since 1970).")))
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


if __name__ == "__main__":
    out = pathlib.Path(__file__).parent / "dashboards"
    out.mkdir(exist_ok=True)
    for name, dashboard in (("scarab-runs", runs_dashboard()), ("scarab-pool", pool_dashboard())):
        (out / f"{name}.json").write_text(json.dumps(dashboard, indent=2) + "\n")
        print("wrote", out / f"{name}.json")
