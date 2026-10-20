# SPDX-License-Identifier: Apache-2.0
"""Network observability using existing bounded metrics and retained JSON events.

The entry point accepts the shared generator so this module has no import side effects.
"""


def network_dashboard(ui):
    p = ui.Layout()
    P, L = ui.PROM, ui.LOKI
    m = 'host=~"$host", container=~"$container"'
    prefix = 'cardano_node_metrics_peerSelection_'

    def gauge(metric, labels=''):
        return f'max by (host, container) ({metric}{{{m}{", " + labels if labels else ""}}})'

    def peer(name):
        return gauge(prefix + name + '_int')

    def total(name):
        return f'({peer(name + "Peers")} + {peer(name + "BigLedgerPeers")})'

    def target(name):
        return gauge('scarab_peer_selection_targets', f'target="{name}"')

    def increase(metric, group='', labels='', window='$__range'):
        return (f'sum by (host, container{", " + group if group else ""}) '
                f'(increase({metric}{{{m}{", " + labels if labels else ""}}}[{window}]))')

    def lines(title, exprs, desc='', unit='none', stack=False):
        panel = ui.series(title, P, exprs, unit=unit, desc=desc, stack=stack)
        panel['fieldConfig']['defaults']['custom']['spanNulls'] = False
        panel['options']['legend'].update({'calcs': ['lastNotNull', 'max'], 'displayMode': 'table'})
        return panel

    def event(kind, pipeline='', filter_peer=False):
        expr = '{' + m + f', event=~"{kind}"' + '}'
        if filter_peer:
            expr += ' |~ "${peer:raw}"'
        return expr + pipeline

    def table(title, expr, desc):
        panel = {'type': 'table', 'title': title, 'description': desc, 'datasource': L,
                 'targets': [ui.target(L, expr, instant=True)],
                 'options': {'showHeader': True, 'sortBy': [{'displayName': 'Value', 'desc': True}]},
                 'fieldConfig': {'defaults': {'decimals': 0}, 'overrides': []},
                 'transformations': [{'id': 'labelsToFields', 'options': {'mode': 'columns'}},
                                     {'id': 'organize', 'options': {'excludeByName': {'Time': True}}}]}
        return panel

    def text(title, content):
        return {'type': 'text', 'title': title, 'options': {'mode': 'markdown', 'content': content}}

    p.line((24, text('Reading the network',
        '**Hot = active**, **warm = established minus active**, **cold = known minus established**. '
        'Ordinary and big-ledger peers are disjoint governor populations; totals below include both. '
        'Established includes closing/lingering peers and active includes deactivation in progress. '
        'Connection-manager states are a separate layer. Differences from governor counts can persist and require investigation; these are not interchangeable socket counts. '
        'Select one host and node to keep mainnet and preprod separate. '
        'The peer regex filters log investigations only. Missing data stays missing; event counters '
        'may be absent until their first occurrence. No per-peer RTT, bandwidth or lifetime peer roster '
        'is exported by the current node.')), h=4)
    p.row('At a glance · current scrape, not sampled block logs')
    p.line(
        (4, ui.stat('Metrics reachable', P, f'min by (host, container) (up{{job="scarab", {m}}})',
                    decimals=0, thresholds=ui.GOOD_ONE,
                    desc='1 means Prometheus scraped this node. Missing is not a healthy zero.')),
        (4, ui.stat('Active / hot · total', P, total('Active'), decimals=0,
                    desc='Ordinary active peers plus active big-ledger peers.')),
        (4, ui.stat('Established · total', P, total('Established'), decimals=0,
                    desc='Ordinary plus big-ledger established peers; includes hot and warm.')),
        (4, ui.stat('Known · total', P, total('Known'), decimals=0,
                    desc='All peers currently known to the governor, including cold peers.')),
        (4, ui.stat('Manager established', P, gauge('scarab_connections', 'state="Established"'), decimals=0,
                    desc='Connection-manager established entries. This separate layer is not the hot peer count.')),
        (4, ui.stat('Peer slot lag', P,
                    gauge('scarab_peer_tip_slot') + ' - ' + gauge('cardano_node_metrics_slotNum_int'),
                    decimals=0, desc='Peer tip last reported on follower extension minus local slot; may be slightly negative. '
                    'Zero lag is not proof of network reachability.')), h=5)

    p.row('Governor populations · temperatures and targets')
    for suffix, label in (('', 'Ordinary'), ('BigLedger', 'Big ledger')):
        metrics = [('Hot', 'hot / active'), ('Warm', 'warm'), ('Cold', 'cold')]
        p.line(
            (12, lines(label + ' temperatures', [(peer(k + ('BigLedgerPeers' if suffix else '')), v)
                                                for k, v in metrics], stack=True,
                       desc='Disjoint hot, warm and cold sets for this population. Ordinary excludes big-ledger peers.')),
            (12, lines(label + ' population against targets', [
                (peer(k + ('BigLedgerPeers' if suffix else 'Peers')), k.lower())
                for k in ('Known', 'Established', 'Active')] + [
                (target(k + suffix), k.lower() + ' target') for k in ('Known', 'Established', 'Active')],
                desc='Known contains established, which contains active: these series must not be stacked. '
                     'Targets can change during scheduled churn.')))
    p.line(
        (8, lines('Total temperatures · both populations', [
            (f'({peer(k)} + {peer(k + "BigLedgerPeers")})', k.lower()) for k in ('Hot', 'Warm', 'Cold')],
            stack=True, desc='Hot + warm + cold equals all known peers across both populations.')),
        (8, lines('Active target shortfall', [
            (f'clamp_min({target("Active")} - {peer("ActivePeers")}, 0)', 'ordinary shortfall'),
            (f'clamp_min({target("ActiveBigLedger")} - {peer("ActiveBigLedgerPeers")}, 0)', 'big-ledger shortfall')],
            desc='Peers below each current active target. A transient shortfall is normal while promoting or churning.')),
        (8, lines('Established target shortfall', [
            (f'clamp_min({target("Established")} - {peer("EstablishedPeers")}, 0)', 'ordinary shortfall'),
            (f'clamp_min({target("EstablishedBigLedger")} - {peer("EstablishedBigLedgerPeers")}, 0)', 'big-ledger shortfall')],
            desc='Warm reserve depletion and disconnected peer recovery are visible here.')))

    p.row('Discovery · origins, roots and ledger pool maps')
    p.line(
        (12, lines('Known peers by origin', [
            (f'max by (host, container, origin) (scarab_peer_selection_peers{{{m}}})', '{{origin}}')], stack=True,
            desc='Origin is where a currently known address came from. Ledger and BigLedger prove discovery '
                 'beyond configured roots; these are peers, not the number of stake pools.')),
        (12, lines('Ledger discovery pool maps', [
            (f'max by (host, container, map) (scarab_ledger_peers_pools{{{m}}})', '{{map}} pools')],
            desc='Pools eligible in the ledger worker stake maps. Big is the leading 90% of stake; it overlaps '
                 'the ordinary pool map. Do not add maps, and do not interpret pools as live connections.')))
    p.line(
        (8, lines('Local roots · explicit status', [
            (peer(k + 'LocalRootPeers'), k.lower()) for k in ('Known', 'Established', 'Active')],
            desc='Uses unambiguous LocalRootPeers gauges. Historical HotLocalRoots/WarmLocalRoots labels '
                 'have reversed meanings and are deliberately not used.')),
        (8, lines('Bootstrap peers · explicit status', [
            (peer(k + 'BootstrapPeers'), k.lower()) for k in ('Known', 'Established', 'Active')],
            desc='Zero is normal when bootstrap peers are not configured. These peers are part of the ordinary population.')),
        (8, lines('Root discovery population', [(peer('RootPeers'), 'known roots'), (target('Root'), 'root target')],
            desc='Root count includes public roots including big-ledger roots, as in the reference governor. '
                 'It is not the configured local root count.')))
    p.line(
        (12, lines('Genesis state', [
            (f'max by (host, container, state) (scarab_gsm_state{{{m}}})', '{{state}}')],
            desc='One-hot Genesis State Machine phase. CaughtUp is a consensus freshness judgement.')),
        (12, lines('Trust, sensitivity and fetch mode', [
            (gauge('scarab_outbound_connections_trusted'), 'trusted outbound state'),
            (gauge('scarab_peer_selection_sensitive'), 'sensitive ledger state'),
            (f'max by (host, container, mode) (scarab_fetch_mode{{{m}}})', 'fetch {{mode}}')],
            desc='Trust is the governor availability criterion, not a general network-health score. '
                 'In Genesis mode it depends on sufficient active big-ledger peers. '
                 'Sensitive means restricted peer selection due to an old ledger; Deadline is tip-oriented fetch mode.')))

    p.line(
        (12, ui.series('Root discovery and peer-sharing events', L, [(
            'sum by (action) (count_over_time(' + event('peerSelectionRoots',
            ' | json | __error__=""') + ' [$__auto]))', '{{action}}')],
            desc='Counts retained root-provider requests/results/failures, inbound picks and peer-share '
                 'requests/results. Empty means no matching event in the interval. Requests and results '
                 'are events, not unique discovered peers.')),
        (12, ui.series('Root-provider retry delay · recent decisions', L, [(
            'max by (host, container) (max_over_time(' + event('peerSelectionRoots',
            ' | json | action=~"results|failure" | unwrap retryInSeconds | __error__=""') + ' [5m]))',
            'retry delay')], unit='s',
            desc='Largest retry delay decided in the trailing five minutes. Separate from individual peer '
                 'promotion backoff. No recent root-provider decision yields no data; this is not a countdown.')))

    p.row('Peer movement · in-flight gauges, failures and churn')
    for big, label in (('', 'Ordinary'), ('BigLedger', 'Big ledger')):
        p.line((12, lines(label + ' promotions in flight', [
            (peer('Cold' + big + 'PeersPromotions'), 'cold → warm / connecting'),
            (peer('Warm' + big + 'PeersPromotions'), 'warm → hot / activating')],
            desc='Current concurrent governor jobs, not cumulative transition counters.')),
            (12, lines(label + ' demotions in flight', [
            (peer('Active' + big + 'PeersDemotions'), 'hot → warm / deactivating'),
            (peer('Warm' + big + 'PeersDemotions'), 'warm → cold / closing')],
            desc='Current peers deactivating or closing. A plateau helps locate stuck transitions.')))
    p.row('Peer stability · transition rates, failures and retry delays')
    p.line(
        (12, lines('Transitions completed or failed · per minute', [
            (f'60 * sum by (host, container, transition) '
             f'(rate(scarab_peer_selection_transitions_total{{{m}}}[$__rate_interval]))', '{{transition}}')],
            desc='Successful promotions, demotions, failed jobs, asynchronous peer loss and forgetting. '
                 'Counters are created on first event; absent outcomes mean unobserved, not a verified zero.')),
        (12, lines('Connection opens, closes and refusals · per minute', [
            (f'60 * sum by (host, container, event) '
             f'(rate(scarab_connection_events_total{{{m}, event!="Changed"}}[$__rate_interval]))', '{{event}}')],
            desc='Connection events at the transport manager. A refused connection differs from a failed governor promotion.')))
    p.line(
        (8, lines('Failure retry backoff', [
            (f'histogram_quantile(0.50, sum by (host, container, le) '
             f'(rate(scarab_peer_selection_backoff_seconds_bucket{{{m}}}[$__rate_interval])))', 'p50'),
            (f'histogram_quantile(0.95, sum by (host, container, le) '
             f'(rate(scarab_peer_selection_backoff_seconds_bucket{{{m}}}[$__rate_interval])))', 'p95')], unit='s',
            desc='Scheduled retry delays observed after failed promotion; not elapsed connection latency. '
                 'No samples yields no quantile.')),
        (8, lines('Backoff observations · per minute', [
            (f'60 * sum by (host, container) '
             f'(rate(scarab_peer_selection_backoff_seconds_count{{{m}}}[$__rate_interval]))', 'retry delays')],
            desc='Companion sample rate: zero observations makes backoff quantiles unavailable.')),
        (8, lines('Churn sleep chosen', [(gauge('scarab_peer_selection_churn_wait_seconds'), 'scheduled delay')], unit='s',
            desc='Most recently scheduled sleep before a churn round. This is not a live countdown.')))
    p.row('Scheduled churn and observation boards')
    p.line(
        (8, lines('Churn rounds · per hour', [
            (increase('scarab_peer_selection_churn_rounds_total', window='1h'), 'rounds / rolling hour')],
            desc='Scheduled rounds begun. A zero can be normal during the first scheduled wait.')),
        (8, lines('Churn steps · selected range', [
            (increase('scarab_peer_selection_churn_steps_total', group='step, result'), '{{step}} {{result}}')],
            desc='Reached versus TimedOut per bounded churn step. Absent until first event.')),
        (8, lines('First-arrival observation boards', [
            (f'max by (host, container, board) (scarab_peer_selection_peer_metrics_board_size{{{m}}})', '{{board}} slots')],
            desc='Slots with first-header / first-block observations, bounded at 180 each. '
                 'This reports board occupancy, not winning peer identities, latency or pool winners.')))

    p.row('Connection manager · transport states and direction')
    p.line(
        (12, lines('Connection state distribution', [
            (f'max by (host, container, state) (scarab_connections{{{m}}})', '{{state}}')], stack=True,
            desc='Reserved → Unnegotiated → Established → Terminating → Terminated entries. '
                 'Terminated entries may be removed promptly. This transport state is distinct from peer temperature.')),
        (12, lines('Inbound, outbound and duplex', [
            (gauge('cardano_node_metrics_connectionManager_' + k + 'Conns_int'), k)
            for k in ('inbound', 'outbound', 'duplex')],
            desc='Inbound and outbound count nonterminated manager entries by who opened them, including '
                 'reservations and negotiation in progress. Duplex describes bidirectional '
                 'protocol capability and overlaps the direction counts. Do not add duplex to a connection total.')))
    p.line(
        (12, lines('Inbound session outcomes · per minute', [
            (f'60 * sum by (host, container, event) '
             f'(rate(scarab_inbound_events_total{{{m}}}[$__rate_interval]))', '{{event}}')],
            desc='Accepted, negotiated capability, following, ended/dropped, refused/closed inbound events. '
                 'An outbound-only node can legitimately have no inbound event series.')),
        (12, lines('ChainSync activity', [
            (f'sum by (host, container) (rate(scarab_chainsync_headers_total{{{m}}}[$__rate_interval]))', 'headers / second'),
            (f'sum by (host, container) (rate(scarab_chainsync_rounds_total{{{m}}}[$__rate_interval]))', 'rounds / second')],
            desc='Headers includes rollback replies; aggregate across sessions, not unique blocks. '
                 'Protocol waiting is part of round duration, so it is not network RTT.')))

    p.row('Investigate peers · retained JSON events, regex applies below')
    failed = event('peerSelectionPromote', ' | json | action="failed" | __error__=""', True)
    p.line(
        (12, table('Top failed peer promotions · selected range',
                   'topk(20, sum by (peer, failure) (count_over_time(' + failed + ' [$__range])))',
                   'Addresses and failure classes parsed at query time only, so they create no stored metric labels. '
                   'Counts reflect retained logs; retries can dominate and are not unique peer counts.')),
        (12, table('Promotion failures by cause and starting state',
                   'sum by (failure, from) (count_over_time(' + failed + ' [$__range]))',
                   'Failure class from the typed governor event. cold failures connect/handshake; warm failures activate. '
                   'An empty table means no matching retained events, not independently proven network health.')),
        h=10)
    p.line((24, ui.logs('Failed promotions and asynchronous peer loss', event('peerSelectionPromote|peerSelectionDemote',
                ' | json | action=~"failed|asynchronous|aborted" | __error__=""', True),
                'Expand an event for peer, typed failure, retryInSeconds, forgotten flag or loss reason. '
                'Use the peer regex above to focus on an address; use ".*" for all.')), h=10)
    p.line(
        (12, ui.logs('Peer promotions, demotions and forgetting',
                    event('peerSelectionPromote|peerSelectionDemote|peerSelectionForget', filter_peer=True),
                    'Decisions and their outcomes. Decided events may contain arrays of peers; raw regex matches those too.')),
        (12, ui.logs('Connection and inbound session lifecycle', event('connection|inbound', filter_peer=True),
                    'Open/close, handshake direction, duplex capability and session termination reasons. '
                    'Does not claim a complete current peer roster: logs describe events over the selected interval.')), h=12)
    p.line(
        (12, ui.logs('Discovery, ledger maps and peer sharing',
                    event('peerSelectionRoots|ledgerPeers|peerSelectionTargets'),
                    'Inspect source Ledger, stake-map refreshes, skipped pool relays, root retries, peer-share requests/results '
                    'and changing targets. Peer regex deliberately does not filter this global context.')),
        (12, ui.logs('Churn, trust, Genesis and fetch mode timeline',
                    event('peerSelectionChurn|peerSelectionSensitive|outboundConnections|gsmTransition|fetchModeChanged'),
                    'Explains deliberate target changes, churn timeouts, ledger freshness, trust and fetch mode changes. '
                    'Peer regex deliberately does not filter these global events.')), h=12)

    variables = [
        ui.prom_variable('host', 'Host', 'label_values(scarab_build_info, host)'),
        ui.prom_variable('container', 'Node', 'label_values(scarab_build_info{host=~"$host"}, container)'),
        ui.constant('peer', 'Peer log regex', '.*')]
    for var, default in zip(variables, ('pascal', 'scarab-preprod')):
        var.update({'includeAll': False, 'multi': False, 'current': {'text': default, 'value': default}})
        var.pop('allValue', None)
    for panel in p.panels:
        if panel.get('type') == 'timeseries':
            panel['fieldConfig']['defaults']['custom']['spanNulls'] = False
        if panel.get('type') == 'logs':
            for query in panel['targets']:
                query['maxLines'] = 200
    collapsed_titles = {'Peer movement · in-flight gauges, failures and churn',
                        'Scheduled churn and observation boards',
                        'Investigate peers · retained JSON events, regex applies below'}
    expanded = []
    current = None
    for panel in p.panels:
        if panel['type'] == 'row':
            current = panel if panel['title'] in collapsed_titles else None
            if current is not None:
                current.update(collapsed=True, panels=[])
            expanded.append(panel)
        elif current is not None:
            current['panels'].append(panel)
        else:
            expanded.append(panel)
    p.panels = expanded
    return {'uid': 'scarab-network', 'title': 'Scarab network', 'tags': ['scarab', 'network', 'peers'],
            'description': 'Peer governor populations, discovery, churn, transport states and peer-event investigations. '
                           'Existing node telemetry only; mainnet/preprod kept separate by single-node selection.',
            'editable': False, 'graphTooltip': 1, 'time': {'from': 'now-3h', 'to': 'now'}, 'refresh': '1m',
            'timepicker': {'refresh_intervals': ['30s', '1m', '5m', '15m']},
            'links': [{'title': title, 'type': 'link', 'url': url, 'keepTime': True,
                       'includeVars': True, 'targetBlank': False}
                      for title, url in [('Chain tip and ledger', '/d/scarab-chain-tip'),
                                         ('Pool and KES', '/d/scarab-pool'),
                                         ('Runs and machines', '/d/scarab-runs')]],
            'templating': {'list': variables}, 'panels': p.panels, 'schemaVersion': 39, 'version': 1}
