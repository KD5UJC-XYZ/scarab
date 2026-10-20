#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Test actual generated dashboard PromQL with Prometheus's own rule evaluator.

Run with --promtool /path/to/promtool. --write-dir prepares portable rule-test
files instead, for a Docker-only promtool invocation in that directory.
JSON is also YAML, so no Python YAML package is required.
"""
import argparse
import json
import pathlib
import shutil
import subprocess
import tempfile

REPO = pathlib.Path(__file__).resolve().parents[1]


def panels(dashboard):
    for panel in dashboard['panels']:
        yield panel
        yield from panels({'panels': panel.get('panels', [])})


def fixtures(dashboard, pool_dashboard):
    by_title = {panel['title']: panel for panel in panels(dashboard)}
    selections = [
        ('dashboard_active_total', 'Active / hot · total', 0),
        ('dashboard_active_shortfall', 'Active target shortfall', 0),
        ('dashboard_big_active_shortfall', 'Active target shortfall', 1),
    ]
    rules = []
    for name, title, index in selections:
        expression = by_title[title]['targets'][index]['expr']
        expression = expression.replace('$host', 'pascal').replace('$container', 'scarab-preprod')
        if '$' in expression:
            raise ValueError(f'Unsubstituted variable in {title}: {expression}')
        rules.append({'record': name, 'expr': expression})

    def source(name, value, extra=''):
        return {'series': name + '{host="pascal",container="scarab-preprod"' + extra + '}',
                'values': f'{value}x10'}

    ordinary = 'cardano_node_metrics_peerSelection_ActivePeers_int'
    big = 'cardano_node_metrics_peerSelection_ActiveBigLedgerPeers_int'
    local = 'cardano_node_metrics_peerSelection_ActiveLocalRootPeers_int'

    def case(name, regular, large, expected_total, expected_regular, expected_large,
             regular_target=20, large_target=5):
        inputs = []
        if regular is not None:
            inputs.append(source(ordinary, regular))
        if large is not None:
            inputs.append(source(big, large))
        # Local roots are already in ordinary: the regression must not add them again.
        inputs.append(source(local, 2))
        if regular_target is not None:
            inputs.append(source('scarab_peer_selection_targets', regular_target, ',target="Active"'))
        if large_target is not None:
            inputs.append(source('scarab_peer_selection_targets', large_target, ',target="ActiveBigLedger"'))
        expected = []
        for (record, _, _), value in zip(selections, (expected_total, expected_regular, expected_large)):
            samples = [] if value is None else [
                {'labels': record + '{host="pascal",container="scarab-preprod"}', 'value': value}]
            expected.append({'expr': record, 'eval_time': '5m', 'exp_samples': samples})
        return {'name': name, 'interval': '1m', 'input_series': inputs, 'promql_expr_test': expected}

    cases = [
        case('Disjoint populations total 25; local roots already included', 20, 5, 25, 0, 0),
        case('Both populations below targets', 14, 2, 16, 6, 3),
        case('Target overshoot clamps shortfall to zero', 22, 7, 29, 0, 0),
        case('Observed zero populations remain observed zero', 0, 0, 0, 20, 5),
        case('Missing big population remains absent, never a healthy zero', 20, None, None, 0, None),
        case('Missing ordinary population remains absent', None, 5, None, None, 0),
        case('No scrape population or target is not healthy zero', None, None, None, None, None,
             regular_target=None, large_target=None),
        case('Missing target cannot produce a zero shortfall', 20, 5, 25, None, None,
             regular_target=None, large_target=None),
    ]
    pool_panels = {panel['title']: panel for panel in panels(pool_dashboard)}
    alert_selections = [
        ('dashboard_node_alerts', 'Node alerts firing', '{host="pascal",container="scarab-preprod"}'),
        ('dashboard_rotation_alerts', 'Host rotation alerts firing', '{host="pascal"}'),
        ('dashboard_global_rotation_alert', 'Global rotation-metrics alert', ''),
    ]
    for record, title, _ in alert_selections:
        expression = pool_panels[title]['targets'][0]['expr']
        expression = expression.replace('$host', 'pascal').replace('$container', 'scarab-preprod')
        if '$' in expression:
            raise ValueError(f'Unsubstituted variable in {title}: {expression}')
        rules.append({'record': record, 'expr': expression})

    def alert(name, labels):
        return {'series': 'ALERTS{alertstate="firing",alertname="' + name + '"' + labels + '}',
                'values': '1x10'}

    def alert_case(name, alerts, node, rotation, global_alert, scrape=True, driver=True):
        inputs = list(alerts)
        if scrape:
            inputs.append(source('up', 1, ',job="scarab"'))
        if driver:
            # Host-only textfile data deliberately lacks a container label.
            inputs.append({'series': 'scarab_kes_rotation_last_run_timestamp_seconds{host="pascal"}',
                           'values': '100x10'})
        expected = []
        for (record, _, labels), value in zip(alert_selections, (node, rotation, global_alert)):
            samples = [] if value is None else [{'labels': record + labels, 'value': value}]
            expected.append({'expr': record, 'eval_time': '5m', 'exp_samples': samples})
        return {'name': name, 'interval': '1m', 'input_series': inputs, 'promql_expr_test': expected}

    selected = ',host="pascal",container="scarab-preprod"'
    cases.extend([
        alert_case('Selected node fault is visible', [alert('PoolOffline', selected)], 1, 0, 0),
        alert_case('Host-only critical rotation fault is visible',
                   [alert('PoolKesRotationCritical', ',host="pascal"')], 0, 1, 0),
        alert_case('Global absent-metrics fault has no host or container labels',
                   [alert('PoolKesRotationMetricsMissing', '')], 0, None, 1, driver=False),
        alert_case('Unrelated host and node faults are excluded',
                   [alert('PoolOffline', ',host="other",container="scarab-other"'),
                    alert('PoolKesRotationCritical', ',host="other"')], 0, 0, 0),
        alert_case('Missing driver telemetry does not fabricate a healthy host zero',
                   [], 0, None, 0, driver=False),
        alert_case('Selected node fault survives loss of its scrape series',
                   [alert('PoolOffline', selected)], 1, None, 0, scrape=False, driver=False),
        alert_case('No node or driver telemetry stays absent',
                   [], None, None, 0, scrape=False, driver=False),
    ])
    return ({'groups': [{'name': 'actual-dashboard-expressions', 'interval': '1m', 'rules': rules}]},
            {'rule_files': ['dashboard.rules.json'], 'evaluation_interval': '1m', 'tests': cases})


def write_fixtures(directory, dashboard, pool_dashboard):
    directory.mkdir(parents=True, exist_ok=True)
    rules, tests = fixtures(dashboard, pool_dashboard)
    for name, data in [('dashboard.rules.json', rules), ('dashboard.tests.json', tests)]:
        (directory / name).write_text(json.dumps(data, indent=2) + '\n')
    return directory / 'dashboard.tests.json'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--dashboard', type=pathlib.Path,
                        default=REPO / 'ops/monitoring/server/grafana/dashboards/scarab-network.json')
    parser.add_argument('--pool-dashboard', type=pathlib.Path,
                        default=REPO / 'ops/monitoring/server/grafana/dashboards/scarab-pool.json')
    parser.add_argument('--promtool', help='Prometheus promtool executable; required unless --write-dir is supplied')
    parser.add_argument('--write-dir', type=pathlib.Path, help='Prepare fixtures for a separate Docker invocation')
    args = parser.parse_args()
    dashboard = json.loads(args.dashboard.read_text())
    pool_dashboard = json.loads(args.pool_dashboard.read_text())
    if args.write_dir:
        directory = args.write_dir.resolve()
        test_file = write_fixtures(directory, dashboard, pool_dashboard)
        print(f'Prepared 15 semantic cases from generated dashboard expressions: {test_file}')
        print('Not executed: run promtool test rules dashboard.tests.json in that directory.')
        return
    executable = args.promtool or shutil.which('promtool')
    if not executable:
        parser.error('promtool is required. Use --write-dir to prepare fixtures for Docker; no tests were run.')
    executable = str(pathlib.Path(executable).resolve())
    with tempfile.TemporaryDirectory(prefix='scarab-dashboard-promql-') as temporary:
        directory = pathlib.Path(temporary)
        write_fixtures(directory, dashboard, pool_dashboard)
        subprocess.run([executable, 'test', 'rules', 'dashboard.tests.json'], cwd=directory, check=True)


if __name__ == '__main__':
    main()
