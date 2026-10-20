# SPDX-License-Identifier: Apache-2.0
"""Live chain, validation and storage observability using existing exported measurements."""


def chain_dashboard(ui):
    p = ui.Layout()
    m = 'host=~"$host", container=~"$container"'
    group = 'host, container'
    legend = '{{host}} {{container}}'
    prom, loki = ui.PROM, ui.LOKI
    stream = '{' + m + ', event=~"%s"}'

    def gauge(name, extra=''):
        return f'max by ({group}) ({name}{{{m}{extra}}})'

    def count(name, window='$__range', labels=''):
        by = group + (', ' + labels if labels else '')
        return f'sum by ({by}) (increase({name}{{{m}}}[{window}]))'

    def rate(name, labels=''):
        by = group + (', ' + labels if labels else '')
        return f'sum by ({by}) (rate({name}{{{m}}}[$__rate_interval]))'

    def quantile(name, q):
        return (f'histogram_quantile({q}, sum by ({group}, le) '
                f'(rate({name}_bucket{{{m}}}[$__rate_interval]))) '
                f'and on ({group}) ({rate(name + "_count")} > 0)')

    def mean(name):
        return f'{rate(name + "_sum")} / ({rate(name + "_count")} > 0)'

    def stat(title, expr, desc='', unit='none', **kwargs):
        return ui.stat(title, prom, expr, unit=unit, desc=desc, **kwargs)

    def line(title, exprs, desc='', unit='none', **kwargs):
        result = ui.series(title, prom, exprs, unit=unit, desc=desc, **kwargs)
        result['fieldConfig']['defaults']['custom']['spanNulls'] = False
        return result

    def text(title, content):
        return {'type': 'text', 'title': title, 'options': {'mode': 'markdown', 'content': content}}

    def sample_panel(title, metric, desc):
        return line(title, [(count(metric + '_count', '$__rate_interval'), legend)], desc=desc)

    def logs(title, events, desc):
        return ui.logs(title, stream % events, desc=desc)

    healthy_zero = [{'color': 'green', 'value': None}, {'color': 'orange', 'value': 1}]
    p.line((24, text('Reading this dashboard',
        '**At the tip:** watch block application, peer-observed lag, validation latency, fork switches and '
        'ChainSync activity. Select one node: Pascal and Ruthie follow different networks. '
        '**Lag compares our tip with the last peer tip recorded on an extension**; it is not an '
        'independent network clock or a freshness guarantee. Missing values remain missing. '
        'Latency quantiles are bucket estimates; read their sample counts, especially at quiet tips. Counter increases are extrapolated and may be fractional. '
        'A continuous per-block identity feed, block body bytes, chain transaction counts, producer identities and competing '
        'tine geometry are not available through current telemetry. Block hashes are present in sampled extension and rollback logs. Logs retain only one `extended` line per '
        '1,000 blocks. This dashboard does not infer those absent facts.')), h=5)

    p.row('Live chain and node identity')
    p.line(
        (4, stat('Metrics endpoint reachable', f'min by ({group}) (up{{job="scarab", {m}}})',
                 desc='Successful scrape does not prove chain freshness.', decimals=0,
                 thresholds=ui.GOOD_ONE)),
        (4, stat('Local block', gauge('cardano_node_metrics_blockNum_int'), decimals=0)),
        (4, stat('Local slot', gauge('cardano_node_metrics_slotNum_int'), decimals=0)),
        (4, stat('Epoch', gauge('cardano_node_metrics_epoch_int'), decimals=0)),
        (4, stat('Block lag vs recorded peer',
                 gauge('scarab_peer_tip_block') + ' - ' + gauge('cardano_node_metrics_blockNum_int'),
                 desc='Signed difference; negative values can occur because peer observation and tip updates differ.',
                 decimals=0)),
        (4, stat('Slot lag vs recorded peer',
                 gauge('scarab_peer_tip_slot') + ' - ' + gauge('cardano_node_metrics_slotNum_int'),
                 desc='Slots, not seconds. Does not establish time since the last network block.', decimals=0)), h=5)
    phase = f'max by ({group}, phase) (scarab_node_phase{{{m}}}) == 1'
    era = f'max by ({group}, era) (scarab_era{{{m}}}) == 1'
    build = f'max by ({group}, git_commit, version, scala_version) (scarab_build_info{{{m}}})'
    p.line((8, stat('Current node phase', phase, 'Exactly one phase is active.')),
           (8, stat('Current era', era, 'Most recent extension; may be absent before live following.')),
           (8, stat('Running build', build, 'Series labels carry source commit, version and Scala version.')), h=5)
    for panel, display in [(p.panels[-3], '${__field.labels.phase}'), (p.panels[-2], '${__field.labels.era}'),
                           (p.panels[-1], '${__field.labels.git_commit}')]:
        panel['fieldConfig']['defaults']['displayName'] = display
        panel['options']['textMode'] = 'name'
    p.line(
        (12, line('Local and peer-observed block heights', [
            (gauge('cardano_node_metrics_blockNum_int'), 'local'),
            (gauge('scarab_peer_tip_block'), 'recorded peer')],
            desc='Comparisons apply only within the selected node/network. Peer gauge updates on extension.')),
        (12, line('Tip differences in blocks and slots', [
            (gauge('scarab_peer_tip_block') + ' - ' + gauge('cardano_node_metrics_blockNum_int'), 'blocks'),
            (gauge('scarab_peer_tip_slot') + ' - ' + gauge('cardano_node_metrics_slotNum_int'), 'slots')],
            desc='Signed differences. Neither a slot ratio nor 100% is proof that we are current.')))
    p.line(
        (8, stat('Applied blocks in selected range', count('scarab_blocks_applied_total'),
                 desc='All eras combined. Stored replay excluded; this is local application activity.', decimals=0)),
        (8, stat('Validation refusals in range', count('scarab_validation_errors_total'),
                 desc='Refused blocks by ledger rule. Zero is separate from missing telemetry.',
                 decimals=0, thresholds=healthy_zero)),
        (8, stat('Follower stalls in range', count('scarab_follower_stalls_total'),
                 desc='Watchdog stalls: no extension while a peer announced a longer chain.',
                 decimals=0, thresholds=healthy_zero)), h=5)

    p.row('Chain activity, density and validation')
    p.line(
        (12, line('Blocks applied per minute, by era', [
            (rate('scarab_blocks_applied_total', 'era') + ' * 60', '{{era}}')],
            desc='Local chain extensions, excluding stored replay. A fork switch can reapply blocks; '
                 'this is not unique network production.')),
        (12, line('Implied interval per applied block', [
            ('1 / (' + rate('scarab_blocks_applied_total') + ' > 0)', legend)], unit='s',
            desc='Inverse of the rolling local application rate. Empty with no observed application. '
                 'Catch-up and branch switching differ from steady-tip block arrival.')))
    p.line(
        (12, line('Volatile chain density', [(gauge('cardano_node_metrics_density_real'), legend)],
                  unit='percentunit', desc='Blocks per slot from immutable anchor to local tip. '
                  'A chain-density measure, not block byte utilization or network occupancy.')),
        (12, line('Validation refusals by rule family / hour', [
            (count('scarab_validation_errors_total', '1h', 'rule'), '{{rule}}')],
            desc='One-hour rolling count. Inspect error events below for typed failure details.')))
    p.line(
        (16, line('Block validation and application time', [
            (mean('scarab_block_apply_seconds'), 'mean'),
            (quantile('scarab_block_apply_seconds', .50), 'p50'),
            (quantile('scarab_block_apply_seconds', .95), 'p95'),
            (quantile('scarab_block_apply_seconds', .99), 'p99')], unit='s',
            desc='Successful ledger validation and application only; validation refusals are recorded separately. Not full end-to-end propagation. '
                 'Quantiles need enough samples and are interpolated within histogram buckets. Values above the largest finite bucket (16.384s) cannot be resolved precisely.')),
        (8, sample_panel('Block apply samples in rate window', 'scarab_block_apply_seconds',
            'Samples behind the adjacent latency panel. Prometheus increase is extrapolated and may be fractional.')))
    p.line(
        (12, line('Block apply cumulative bucket population in window', [
            (f'sum by ({group}, le) (increase(scarab_block_apply_seconds_bucket{{{m}}}[$__rate_interval]))',
             '≤ {{le}} seconds')], desc='Cumulative counts: the +Inf bucket is the total sample count; '
                  'series overlap and must not be stacked.')),
        (12, line('Validation worker demand', [
            (rate('scarab_cpu_steps_total'), 'CPU steps / second')],
            desc='CPU-bound steps on the follower pool, not chain transactions or CPU utilization.')))

    p.row('Headers and protocol activity')
    p.line(
        (12, line('ChainSync traffic', [
            (rate('scarab_chainsync_rounds_total'), 'rounds / second'),
            (rate('scarab_chainsync_headers_total'), 'headers and rollbacks / second')],
            desc='Aggregate across sessions; duplicate announcements are possible. '
                 'Received header counter also includes rollbacks.')),
        (12, line('Header gate outcomes / minute', [
            (rate('scarab_headers_validated_total') + ' * 60', 'validated'),
            (rate('scarab_headers_unchecked_total') + ' * 60', 'left to block validation'),
            (rate('scarab_headers_waited_total') + ' * 60', 'waited for chain advance')],
            desc='Waited headers are an additional observation, not a mutually exclusive third acceptance result.')))
    p.line(
        (16, line('ChainSync round duration — includes waiting', [
            (mean('scarab_chainsync_round_seconds'), 'mean'),
            (quantile('scarab_chainsync_round_seconds', .95), 'p95')], unit='s',
            desc='From first pipelined request to last reply, including protocol await time. '
                 'This is not network RTT or block propagation delay; the largest finite bucket is 5 seconds.')),
        (8, sample_panel('ChainSync duration samples', 'scarab_chainsync_round_seconds',
                         'A quiet tip can have long waiting rounds and sparse completed samples.')))
    p.line(
        (8, line('Genesis state machine', [
            (f'max by ({group}, state) (scarab_gsm_state{{{m}}})', '{{state}}')],
            desc='One-hot state: PreSyncing, Syncing or CaughtUp.')),
        (8, line('Block fetch mode', [
            (f'max by ({group}, mode) (scarab_fetch_mode{{{m}}})', '{{mode}}')],
            desc='One-hot BulkSync versus Deadline mode.')),
        (8, line('First-arrival scoreboard occupancy', [
            (f'max by ({group}, board) (scarab_peer_selection_peer_metrics_board_size{{{m}}})', '{{board}}')],
            desc='Slots held by bounded header/block first-arrival scoreboards (at most 180 each). '
                 'No peer scores or first-arrival latencies are exported.')))

    p.row('Fork switches and rollback evidence')
    p.line(
        (6, stat('Rollbacks in range', count('scarab_rollbacks_total'), decimals=0)),
        (6, stat('Fork switches in range', count('cardano_node_metrics_forks_counter'), decimals=0,
                 desc='Switches involving at least one rolled-back block. Not a count of all competing branches.')),
        (6, stat('Blocks rolled back in range', count('scarab_rollback_depth_blocks_sum'), decimals=0)),
        (6, stat('Rollback depth observations', count('scarab_rollback_depth_blocks_count'), decimals=0,
                 desc='Read this count before interpreting depth quantiles.')), h=5)
    p.line(
        (12, line('Rollback events / hour and depth sum / hour', [
            (count('scarab_rollbacks_total', '1h'), 'events'),
            (count('scarab_rollback_depth_blocks_sum', '1h'), 'blocks rolled back')],
            desc='Our observed rollbacks; unseen competitor tines are not represented.')),
        (12, line('Rollback depth', [
            (mean('scarab_rollback_depth_blocks'), 'mean blocks'),
            (quantile('scarab_rollback_depth_blocks', .95), 'p95 blocks')],
            desc='Empty with no events in the rate window. Bucket estimates, not exact largest rollback.')))
    p.line((24, logs('Rollback and chain-selection event history',
                    'rolledBack|stalled|stallExit|reconnecting',
                    'Available events only. Raw JSON gives points and causes where emitted; '
                    'absence is not proof no alternative branch existed.')), h=9)

    p.row('Epoch position and ledger population')
    p.line(
        (8, line('Epoch number', [(gauge('cardano_node_metrics_epoch_int'), legend)],
                  desc='Current tip epoch, resolved by node genesis geometry.')),
        (8, line('Slot within epoch', [(gauge('cardano_node_metrics_slotInEpoch_int'), legend)],
                  desc='Tip slot relative to start of epoch. No duration/percentage is inferred because '
                       'epoch length and slot duration are not exported.')),
        (8, line('UTxO entries at epoch boundary', [(gauge('scarab_ledger_utxo_entries'), legend)],
                  desc='Updated at the start of an epoch; not a per-block live UTxO count. May be absent before this process emits its first ledgerEpoch event.')))
    p.line(
        (12, line('Registered stake credentials at epoch boundary',
                  [(gauge('scarab_ledger_accounts'), legend)],
                  desc='Epoch-refreshed ledger population; not a count of active wallets. May be absent until the first ledgerEpoch event in this process.')),
        (12, line('Registered stake pools at epoch boundary', [(gauge('scarab_ledger_pools'), legend)],
                  desc='Registered pools, not pools that won a block or produced this epoch. May be absent until the first ledgerEpoch event in this process.')))
    p.line((24, logs('Epoch, era and ledger validation events',
                    'epoch|ledgerEpoch|epochValidated|eraTransition',
                    'Actual emitted events, without inferring epoch durations or winners.')), h=8)

    p.row('Mempool — local submissions, not chain transaction throughput')
    p.line(
        (8, line('Transactions currently held', [(gauge('cardano_node_metrics_txsInMempool_int'), legend)])),
        (8, line('Held transaction bytes', [(gauge('cardano_node_metrics_mempoolBytes_int'), legend)],
                  unit='bytes', desc='Serialized mempool transactions; not block body size.')),
        (8, line('Mempool additions and outcomes / minute', [
            (rate('cardano_node_metrics_txsProcessedNum_counter') + ' * 60', 'added'),
            (rate('scarab_mempool_adopted_total') + ' * 60', 'included from our pool'),
            (rate('scarab_mempool_dropped_total') + ' * 60', 'dropped after tip change')],
            desc='Counts only transactions held or submitted locally. This cannot measure all '
                 'transactions in received blocks.')))
    p.line(
        (12, line('Mempool rejections / minute', [
            (rate('scarab_mempool_rejected_total', 'reason') + ' * 60', '{{reason}}')])),
        (12, logs('Mempool and own-forging event history',
                  'mempoolAdded|mempoolRejected|mempoolDropped|mempoolAdopted|forged|adopted|lost|forgeFailed',
                  'Only locally submitted/held transactions and our own forging events, when emitted.')), h=9)

    p.row('Persistence and restart recovery')
    p.line(
        (8, line('Immutable blocks', [(gauge('scarab_immutable_blocks'), legend)],
                  desc='Immutable blocks found on open plus subsequently committed blocks.')),
        (8, line('Volatile blocks', [(gauge('scarab_volatile_blocks'), legend)],
                  desc='Blocks retained above immutable anchor, bounded by security parameter k.')),
        (8, line('New immutable blocks / minute', [
            (f'clamp_min(deriv(scarab_immutable_blocks{{{m}}}[$__rate_interval]), 0) * 60', legend)],
                  desc='Trend of a gauge. Startup/restoration and truncation can distort this estimate.')))
    p.line(
        (6, stat('Snapshots written in range', count('scarab_snapshots_written_total'), decimals=0)),
        (6, stat('Snapshots restored in range', count('scarab_snapshots_restored_total'), decimals=0)),
        (6, stat('Storage migrations in range', count('scarab_snapshots_migrated_total'), decimals=0)),
        (6, stat('Snapshot problems in range', count('scarab_snapshot_problems_total'),
                 desc='Skipped, Failed and Refused; inspect kind and event detail.',
                 thresholds=healthy_zero, decimals=0)), h=5)
    p.line(
        (8, line('Last snapshot write duration', [(gauge('scarab_snapshot_last_write_seconds'), legend)],
                  unit='s', desc='Held value of most recent completed write, not a periodic duration sample.')),
        (8, line('Last snapshot bytes', [(gauge('scarab_snapshot_last_bytes'), legend)],
                  unit='bytes', desc='Size of latest written snapshot, not total database size.')),
        (8, line('Snapshot problems by kind / hour', [
            (count('scarab_snapshot_problems_total', '1h', 'kind'), '{{kind}}')],
            desc='Skipped unreadable snapshots can fall back to an older snapshot; inspect logs.')))
    p.line(
        (12, line('Startup replay block and target', [
            (gauge('scarab_replay_block'), 'last replay progress'),
            (gauge('scarab_replay_target_block'), 'immutable target')],
            desc='Retained startup values after Following. Progress reports are interval sampled; '
                 'volatile replay can continue beyond the immutable target.')),
        (12, line('Stored replay blocks at startup', [
            (gauge('scarab_replay_blocks'), 'replayed blocks')],
            desc='Retained total from startup. Elapsed duration is shown separately below.')))
    p.line(
        (8, stat('Last startup replay duration', gauge('scarab_replay_seconds'), unit='s')),
        (8, stat('Process uptime', 'time() - ' + gauge('process_start_time_seconds'), unit='s')),
        (8, stat('Process restarts visible in range',
                 f'changes(process_start_time_seconds{{{m}}}[$__range])', decimals=0,
                 desc='Start-time changes observed by scrapes; a missing interval can hide a restart.')), h=5)
    p.line((24, logs('Storage, startup and shutdown event history',
        'started|finished|snapshotRestored|snapshotWritten|snapshotSkipped|snapshotRefused|snapshotFailed|'
        'formatMigrated|immutableOpened|volatileOpened|volatileResumed|replayStarted|replayProgress|'
        'replayFinished|shutdownSnapshot|exited|error|fatal',
        'Storage lifecycle events are retained without per-block sampling. Raw details describe '
        'migration, restore and any recovery truncation.')), h=10)

    collapsed_titles = {'Epoch position and ledger population',
                        'Mempool — local submissions, not chain transaction throughput',
                        'Persistence and restart recovery'}
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

    host = ui.prom_variable('host', 'Node host', 'label_values(scarab_build_info, host)')
    container = ui.prom_variable('container', 'Node container',
        'label_values(scarab_build_info{host=~"$host"}, container)')
    for variable, default in [(host, 'pascal'), (container, 'scarab-preprod')]:
        variable.update(includeAll=False, multi=False, current={'text': default, 'value': default})
        variable.pop('allValue', None)
    return {
        'uid': 'scarab-chain-tip', 'title': 'Scarab chain tip and ledger',
        'tags': ['scarab', 'chain', 'ledger', 'network'],
        'description': 'Live-tip activity, validation, observed rollbacks, ledger epochs, local mempool and storage. '
                       'Existing node metrics and retained events; no inferred block contents or unseen branches.',
        'editable': False, 'graphTooltip': 1, 'time': {'from': 'now-3h', 'to': 'now'},
        'refresh': '1m', 'timepicker': {'refresh_intervals': ['30s', '1m', '5m', '15m']},
        'templating': {'list': [host, container]},
        'links': [{'title': title, 'type': 'link', 'url': url, 'keepTime': True,
                   'includeVars': True, 'targetBlank': False}
                  for title, url in [('Peer network', '/d/scarab-network'), ('Pool and KES', '/d/scarab-pool'),
                                     ('Runs and machines', '/d/scarab-runs')]],
        'panels': p.panels, 'schemaVersion': 39, 'version': 1,
    }
