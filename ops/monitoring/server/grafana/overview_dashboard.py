# SPDX-License-Identifier: Apache-2.0
"""Fleet overview using existing node telemetry; no cross-network tip comparisons."""


def overview_dashboard(ui):
    p = ui.Layout()
    m = 'host=~"$host", container=~"$container"'

    def gauge(name, labels=""):
        selector = m + (", " + labels if labels else "")
        return f'max by (host, container) ({name}{{{selector}}})'

    def count(name):
        return f'sum by (host, container) (increase({name}{{{m}}}[$__range]))'

    def temperature(kind):
        return f'{gauge("cardano_node_metrics_peerSelection_" + kind + "_int")} + {gauge("cardano_node_metrics_peerSelection_" + kind + "BigLedgerPeers_int")}'

    lag = f'{gauge("scarab_peer_tip_slot")} - {gauge("cardano_node_metrics_slotNum_int")}'
    p.line((24, ui.text("Start here", """Compare **each node with its own peers**. Pascal/preprod and Rutherford/mainnet have different chains: their block and slot numbers are not comparable.

Use **Network** for peer states, discovery, failures and connection lifecycles; **Chain tip** for block activity, validation, rollbacks, ledger, mempool and storage; **Pool** for our producer and KES; **Runs** and **Node Exporter Full** for raw logs and machine resources.

**Hot** means active; **warm** means established but inactive; **cold** means known but disconnected. Totals below include ordinary **and** big-ledger peers. Missing telemetry stays missing; it is not treated as a healthy zero.""")), h=6)
    p.row("Live node state")
    phase = ui.stat("Current phase", ui.PROM,
                    f'max by (host, container, phase) (scarab_node_phase{{{m}}}) == 1',
                    desc="Active one-hot phase. Scrape availability is shown separately.")
    phase['targets'][0]['legendFormat'] = '{{host}} {{container}} — {{phase}}'
    phase['options']['textMode'] = 'name'
    p.line(
        (6, ui.stat("Metrics scrape up", ui.PROM, f'min by (host, container) (up{{job="scarab", {m}}})',
                    decimals=0, thresholds=ui.GOOD_ONE, desc="1 means the endpoint answered. It does not prove every collector updated or that the node is at network tip.")),
        (6, phase),
        (6, ui.stat("Process uptime", ui.PROM, f'time() - {gauge("process_start_time_seconds")}', unit="s")),
        (6, ui.stat("Metric sample age", ui.PROM,
                    f'time() - max by (host, container) (timestamp(up{{job="scarab", {m}}}))', unit="s",
                    desc="Age of the most recently ingested scrape sample. This measures the telemetry pipeline, not block freshness or individual event-driven gauge freshness.")), h=5)
    p.line(
        (6, ui.stat("Hot peers — total", ui.PROM, temperature('Hot'), decimals=0,
                    desc="Ordinary hot + big-ledger hot. Local roots are already included; do not add them again.")),
        (6, ui.stat("Warm peers — total", ui.PROM, temperature('Warm'), decimals=0)),
        (6, ui.stat("Cold peers — total", ui.PROM, temperature('Cold'), decimals=0)),
        (6, ui.stat("Peer slot difference", ui.PROM, lag, decimals=0,
                    desc="Last peer tip reported by follower extension minus our selected tip. Positive means that observed peer was ahead; negative can occur. Not wall-clock lag or independent proof of global synchronization.")), h=5)
    p.line(
        (8, ui.series("Hot peers and active target", ui.PROM, [
            (temperature('Hot'), 'active {{host}} {{container}}'),
            (f'{gauge("scarab_peer_selection_targets", "target=\"Active\"")} + {gauge("scarab_peer_selection_targets", "target=\"ActiveBigLedger\"")}',
             'target {{host}} {{container}}')], desc="Compare actual active peers with the sum of ordinary and big-ledger targets. Churn deliberately changes targets.")),
        (8, ui.series("Blocks applied per minute", ui.PROM, [
            (f'sum by (host, container) (rate(scarab_blocks_applied_total{{{m}}}[$__rate_interval])) * 60', '{{host}} {{container}}')],
            desc="Follower extensions, including replacement work after rollbacks; this is not a transaction rate or guaranteed unique canonical block count.")),
        (8, ui.series("Difference from observed peer tip", ui.PROM, [(lag, '{{host}} {{container}}')],
                      desc="Each series compares one node with its own observed peer tip; do not compare absolute tips across networks.")))
    p.row("Events in the selected interval")
    p.line(
        (6, ui.stat("Validation errors", ui.PROM, count('scarab_validation_errors_total'), decimals=0, thresholds=ui.GOOD_ZERO)),
        (6, ui.stat("Chain rollbacks", ui.PROM, count('scarab_rollbacks_total'), decimals=0,
                    desc="Selected-chain rollback events, not every competing branch observed.")),
        (6, ui.stat("Failed peer promotions", ui.PROM,
                    f'sum by (host, container) (increase(scarab_peer_selection_transitions_total{{{m}, transition="PromoteFailed"}}[$__range]))',
                    decimals=0, desc="Failed attempts to promote a peer. Use Network to inspect timing, failure logs and recovery; a failure is not automatically a node fault.")),
        (6, ui.stat("Follower stalls begun", ui.PROM, count('scarab_follower_stalls_total'), decimals=0,
                    desc="Watchdog stall events. The current stalled-state gauge is not reliably present on all deployed builds.")), h=5)
    p.line(
        (12, ui.series("Selected-chain density", ui.PROM, [(gauge('cardano_node_metrics_density_real'), '{{host}} {{container}}')],
                      unit='percentunit', desc="Blocks per slot across the volatile chain, from its anchor to selected tip. This is chain density, not block byte utilization.")),
        (12, ui.series("Peer promotions and losses per minute", ui.PROM, [
            (f'sum by (host, container, transition) (rate(scarab_peer_selection_transitions_total{{{m}, transition=~"PromoteFailed|Lost|PromotedHot"}}[$__rate_interval])) * 60',
             '{{host}} {{transition}}')], desc="Failure and recovery alongside churn. Rates need at least two scrape samples.")))
    build = ui.stat("Running build", ui.PROM, f'max by (host, container, git_commit, version) (scarab_build_info{{{m}}}) == 1',
                    desc="Embedded source revision and application version from each running node.")
    build['targets'][0]['legendFormat'] = '{{host}} {{container}} — {{git_commit}} ({{version}})'
    build['options']['textMode'] = 'name'
    p.line((24, build), h=4)
    p.line((24, ui.text("What the existing telemetry can tell us", """**Available:** governor temperatures/targets/origins, connection-manager states, lifecycle events, discovery/churn, selected-chain activity and density, rollbacks, validation timings, mempool, ledger population, snapshots, our own forging and KES.

**Not exported by the current nodes:** adopted-block body size and transaction count, producing-pool identity for each received block, per-peer RTT/throughput, and a graph of all competing branches (tines). There are no placeholder charts for these. Network event logs offer a local view, not a network-wide topology.

The existing singleton block-fetch latency histogram can have **zero observations while the range-fetch path is active**. ChainSync round duration includes awaiting a peer reply and is not RTT. Event-driven gauges can remain unchanged between events; a fresh scrape timestamp does not prove fresh underlying observations.""")), h=6)
    # Links on each value retain that node even when the fleet selectors contain All.
    for panel in p.panels:
        if panel.get('type') == 'stat':
            panel['fieldConfig']['defaults']['links'] = [
                {'title': title, 'url': '/d/' + uid + '?var-host=${__field.labels.host}&var-container=${__field.labels.container}&${__url_time_range}',
                 'targetBlank': False} for uid, title in (
                     ('scarab-network', 'Network for this node'), ('scarab-chain-tip', 'Chain tip for this node'))]
    return {
        'uid': 'scarab-overview', 'title': 'Scarab overview', 'tags': ['scarab', 'network', 'tip'],
        'description': 'Start here: live per-node health, peer targets, chain activity and links to deeper network, chain and producer dashboards.',
        'editable': False, 'graphTooltip': 1, 'time': {'from': 'now-1h', 'to': 'now'}, 'refresh': '1m',
        'timepicker': {'refresh_intervals': ['30s', '1m', '5m', '15m']},
        'templating': {'list': [
            ui.prom_variable('host', 'Host', 'label_values(up{job="scarab"}, host)'),
            ui.prom_variable('container', 'Node', 'label_values(up{job="scarab", host=~"$host"}, container)')]},
        'panels': p.panels, 'schemaVersion': 39, 'version': 1,
    }
