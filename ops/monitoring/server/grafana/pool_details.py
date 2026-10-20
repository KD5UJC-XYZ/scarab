# SPDX-License-Identifier: Apache-2.0
"""Producer-only details from existing ForgeEvent metrics and retained JSON logs."""


def extend_pool_dashboard(ui, dashboard):
    m = 'host=~"$host", container=~"$container"'
    p = ui.Layout()

    def event(names):
        return '{' + m + ', event=~"' + names + '"}'

    def body(function, window):
        return (function + '(' + event('forged') +
                ' | json bodySize="bodySize" | unwrap bodySize | __error__="" [' + window + '])')

    def count(window):
        return ('sum by (host, container) (increase(scarab_forge_adoption_seconds_count{' + m +
                '}[' + window + ']))')

    def logs(title, names, description):
        panel = ui.logs(title, event(names), desc=description)
        panel['targets'][0]['maxLines'] = 200
        return panel

    p.row('Our producer · block bodies, adoption and public identity')
    p.line((24, {'type': 'text', 'title': 'Scope of these measurements', 'options': {
        'mode': 'markdown', 'content': '**Our producer only.** `forged.bodySize` is the body size '
        'reported for blocks this node forged. It is not the whole wire block size, a protocol-limit '
        'utilization percentage, or a feed of all received network blocks. The latest/max body cards '
        'search the selected time range; an empty result means no retained matching forge event. '
        'A forged block may subsequently be lost. Public pool and VRF hashes identify our producer; '
        'they do not reveal future network-wide leadership winners. Expand raw events for the exact '
        'transaction ID array; no transaction count is guessed from its encoded string.'}}), h=4)
    p.line(
        (6, ui.stat('Latest own block body · selected range', ui.LOKI,
                    body('last_over_time', '$__range'), unit='bytes', decimals=0,
                    desc='bodySize from the most recent retained forged event per matching log stream. '
                         'No event in the selected range gives no data.')),
        (6, ui.stat('Largest own block body · selected range', ui.LOKI,
                    body('max_over_time', '$__range'), unit='bytes', decimals=0,
                    desc='Maximum bodySize among our retained forged events in the selected range. '
                         'This is an observation, not the chain protocol maximum.')),
        (6, ui.stat('Last own forged slot · process', ui.PROM,
                    'max by (host, container) (cardano_node_metrics_forgedSlotLast_int{' + m + '})',
                    decimals=0, desc='Latest slot forged by this process. May be unavailable until the first measured '
                                    'forge; it does not imply adoption or public-chain inclusion.')),
        (6, ui.stat('Own adoption latency samples · selected range', ui.PROM, count('$__range'),
                    decimals=0, desc='Recorded adoption events with known latency in the selected range. '
                                    'No samples means latency estimates are unavailable.')), h=5)
    sample_guard = ' and on (host, container) (' + count('$__rate_interval') + ' > 0)'
    mean = ('sum by (host, container) (rate(scarab_forge_adoption_seconds_sum{' + m +
            '}[$__rate_interval])) / sum by (host, container) '
            '(rate(scarab_forge_adoption_seconds_count{' + m + '}[$__rate_interval]))')
    p95 = ('histogram_quantile(0.95, sum by (host, container, le) '
           '(rate(scarab_forge_adoption_seconds_bucket{' + m + '}[$__rate_interval])))')
    p.line(
        (12, ui.series('Our forged body size · rolling one hour', ui.LOKI, [
            (body('avg_over_time', '1h'), 'mean own body'),
            (body('max_over_time', '1h'), 'largest own body')], unit='bytes',
            desc='Each point summarizes our retained forged events in the trailing hour. '
                 'Windows without any matching event stay empty. Not representative of all network blocks.')),
        (12, ui.series('Our slot start → local adoption latency', ui.PROM, [
            ('(' + mean + ')' + sample_guard, 'mean own adoption'),
            (p95 + sample_guard, 'p95 own adoption')], unit='s',
            desc='Existing adoption histogram: time from the start of the slot to local adoption of our forged block. '
                 'Guarded by positive sample count; not peer RTT or proof of adoption elsewhere. '
                 'A p95 based on few blocks is only a coarse histogram estimate.')))
    p.line(
        (12, ui.series('Our adoption latency observations · rolling hour', ui.PROM,
                       [(count('1h'), 'observations / rolling hour')],
                       desc='Assess sample support alongside latency. Histogram buckets do not give per-block timing detail.')),
        (12, logs('Our public producer identity · start events', 'forgeStarted',
                  'Public pool hash, VRF public-key hash, certificate counter and KES window from ForgeEvent.Started. '
                  'Expand time range to include a startup; these identifiers are not signing keys.')), h=9)
    p.line((24, logs('Our forged blocks · body bytes and original transaction IDs', 'forged',
                    'Unmodified forged events include slot, epoch, blockNo, hash, prev, txs[], bodySize, '
                    'stake, totalStake and certificate counters. Source: log/Traced.scala ForgeEvent.Forged. '
                    'These are locally forged candidates; inspect outcomes below.')), h=10)
    p.line(
        (12, logs('Our producer outcomes', 'adopted|lost|forgeSkipped|forgeFailed|forgeEpoch',
                  'Local adoption, lost candidates, skip/failure reasons and epoch summaries. '
                  'Adopted here means adopted by our node, not independently verified public-chain finality.')),
        (12, logs('KES and producer failure details',
                  'kesReloaded|kesReloadRefused|kesPersistFailed|forgeFailed',
                  'Retained event details complement the pool KES counters and host rotation metrics. '
                  'Absence of logs alone does not establish health.')), h=10)

    def flatten(items):
        for panel in items:
            yield panel
            yield from flatten(panel.get('panels', []))

    existing = list(flatten(dashboard['panels']))
    next_id = max(panel['id'] for panel in existing) + 1
    bottom = max(panel['gridPos']['y'] + panel['gridPos']['h'] for panel in existing)
    for i, panel in enumerate(p.panels):
        panel['id'] = next_id + i
        panel['gridPos']['y'] += bottom
        if panel['type'] == 'timeseries':
            panel['fieldConfig']['defaults']['custom']['spanNulls'] = False
    row = p.panels[0]
    row.update(collapsed=True, panels=p.panels[1:])
    dashboard['panels'].append(row)
    return dashboard
