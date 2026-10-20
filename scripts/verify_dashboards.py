#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Read-only verification of generated dashboard queries against Prometheus and Loki."""
import argparse
import concurrent.futures
import datetime
import json
import math
import pathlib
import re
import time
import urllib.error
import urllib.parse
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[1]
DEFAULT_DASHBOARDS = ROOT / 'ops/monitoring/server/grafana/dashboards'
VARIABLE = re.compile(r'\$\{([^}]+)\}|\$([A-Za-z_][A-Za-z_0-9]*)')


def duration(value):
    match = re.fullmatch(r'([1-9][0-9]*)(s|m|h|d|w)', value)
    if not match:
        raise ValueError('duration must be a positive integer followed by s, m, h, d or w')
    return int(match[1]) * {'s': 1, 'm': 60, 'h': 3600, 'd': 86400, 'w': 604800}[match[2]]


def base_url(value):
    parsed = urllib.parse.urlsplit(value)
    if parsed.scheme not in {'http', 'https'} or not parsed.netloc or parsed.query or parsed.fragment:
        raise ValueError('API URL must be an absolute http(s) URL without a query or fragment')
    if parsed.username or parsed.password:
        raise ValueError('API credentials in URLs are not supported')
    return value.rstrip('/')


def substitute(expression, host, container, window='1h', rate_window='5m'):
    """Mirror selector interpolation, including both regex and query-string escaping."""
    values = {
        'host': json.dumps(re.escape(host))[1:-1],
        'container': json.dumps(re.escape(container))[1:-1],
        '__range': window, '__auto': rate_window, '__rate_interval': rate_window,
        'peer:raw': '.*', 'expected': '14', 'epoch': '5d',
    }

    def replace(match):
        key = match[1] or match[2]
        if key not in values:
            raise ValueError(f'unresolved dashboard variable ${key}')
        return values[key]
    return VARIABLE.sub(replace, expression)


def panels(items):
    """Include collapsed rows: those queries are invisible until the viewer expands them."""
    for item in items:
        yield item
        yield from panels(item.get('panels', []))


def requests_for_target(panel, target, expression, prometheus_url, loki_url, now, range_seconds):
    datasource = target.get('datasource', panel.get('datasource', {}))
    if not isinstance(datasource, dict):
        raise ValueError(f'unsupported datasource {datasource!r}')
    kind = datasource.get('type') or datasource.get('uid')
    if kind not in {'prometheus', 'loki'}:
        raise ValueError(f'unsupported datasource {datasource!r}')
    modes = []
    if kind == 'prometheus':
        if target.get('instant'):
            modes.append('instant')
        if target.get('range', not target.get('instant', False)):
            modes.append('range')
        if not modes:
            raise ValueError('Prometheus target enables neither instant nor range querying')
    else:
        query_type = target.get('queryType', 'range')
        if query_type not in {'instant', 'range'}:
            raise ValueError(f'unsupported Loki queryType {query_type!r}')
        modes.append(query_type)
    result = []
    for mode in modes:
        params = {'query': expression}
        if kind == 'prometheus':
            endpoint = '/api/v1/query' if mode == 'instant' else '/api/v1/query_range'
            if mode == 'instant':
                params['time'] = f'{now:.3f}'
            else:
                params.update(start=f'{now - range_seconds:.3f}', end=f'{now:.3f}', step='30s')
            base = prometheus_url
        else:
            endpoint = '/loki/api/v1/query' if mode == 'instant' else '/loki/api/v1/query_range'
            if mode == 'instant':
                params['time'] = str(int(now * 1e9))
            else:
                params.update(start=str(int((now - range_seconds) * 1e9)),
                              end=str(int(now * 1e9)), step='30s', limit='20', direction='backward')
            base = loki_url
        result.append({'datasource': kind, 'mode': mode, 'expression': expression,
                       'url': base + endpoint + '?' + urllib.parse.urlencode(params)})
    return result


def observe(request):
    started = time.monotonic()
    receipt = {key: request[key] for key in ('datasource', 'mode', 'expression', 'url')}
    receipt['used_by'] = request['used_by']
    try:
        with urllib.request.urlopen(request['url'], timeout=60) as response:
            body = json.load(response)
        receipt['warnings'] = body.get('warnings', [])
        receipt['infos'] = body.get('infos', [])
        if body.get('status') != 'success':
            raise ValueError(f'{body.get("errorType", "API error")}: {body.get("error", body)}')
        data = body.get('data', {})
        result_type = data.get('resultType')
        result = data.get('result', [])
        receipt['result_type'] = result_type
        if result_type in {'vector', 'matrix', 'streams'}:
            receipt['series_count'] = len(result)
            samples = [sample for item in result for sample in
                       (item.get('values', []) if result_type != 'vector' else [item['value']])]
        elif result_type in {'scalar', 'string'}:
            receipt['series_count'] = 1
            samples = [result]
        else:
            raise ValueError(f'unsupported result type {result_type!r}')
        receipt['sample_count'] = len(samples)
        if result_type not in {'streams', 'string'}:
            nonfinite = sum(not math.isfinite(float(sample[1])) for sample in samples)
            receipt['nonfinite_samples'] = nonfinite
            receipt['finite_samples'] = len(samples) - nonfinite
            receipt['data_state'] = ('empty' if not samples else 'nonfinite_only' if nonfinite == len(samples)
                                     else 'mixed_finite_nonfinite' if nonfinite else 'finite')
        else:
            receipt['data_state'] = 'empty' if not samples else 'logs' if result_type == 'streams' else 'string'
        receipt['status'] = 'success'
    except urllib.error.HTTPError as error:
        receipt.update(status='error', error=f'HTTP {error.code}: {error.read(8192).decode(errors="replace")}')
    except (OSError, ValueError, KeyError, TypeError) as error:
        receipt.update(status='error', error=str(error))
    receipt['duration_seconds'] = round(time.monotonic() - started, 3)
    return receipt


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, epilog=
        'Empty or nonfinite-only data is recorded, not replaced with zero and not treated as an API failure. '
        'The verifier is read-only; it never updates dashboards or nodes. Each distinct expression/mode '
        'is queried once using at most four concurrent HTTP requests.')
    parser.add_argument('--prometheus-url', required=True, help='Prometheus base URL (http or https)')
    parser.add_argument('--loki-url', required=True, help='Loki base URL (http or https)')
    parser.add_argument('--host', required=True, help='Literal host label value, safely regex escaped')
    parser.add_argument('--container', required=True, help='Literal container label value, safely regex escaped')
    parser.add_argument('--output', required=True, type=pathlib.Path, help='Private JSON evidence file')
    parser.add_argument('--dashboard-dir', type=pathlib.Path, default=DEFAULT_DASHBOARDS)
    parser.add_argument('--window', default='1h', help='Stat/increase window substituted for $__range (default: 1h)')
    parser.add_argument('--rate-window', default='5m', help='Rate/LogQL window (default: 5m)')
    parser.add_argument('--range-window', default='5m', help='Time span actually fetched for range panels (default: 5m)')
    args = parser.parse_args(argv)
    try:
        prom = base_url(args.prometheus_url)
        loki = base_url(args.loki_url)
        duration(args.window)
        duration(args.rate_window)
        span = duration(args.range_window)
    except ValueError as error:
        parser.error(str(error))
    now, started = time.time(), time.monotonic()
    queries, failures = {}, []
    files = sorted(args.dashboard_dir.glob('*.json'))
    if not files:
        parser.error(f'no dashboard JSON files in {args.dashboard_dir}')
    target_count = 0
    for path in files:
        try:
            dashboard = json.loads(path.read_text())
            for panel in panels(dashboard.get('panels', [])):
                for target in panel.get('targets', []):
                    if target.get('hide'):
                        continue
                    target_count += 1
                    identity = {'dashboard': dashboard.get('uid', path.stem),
                                'panel': panel.get('title', ''), 'ref_id': target.get('refId', '')}
                    try:
                        if not target.get('expr'):
                            raise ValueError('target has no expression')
                        expr = substitute(target['expr'], args.host, args.container, args.window, args.rate_window)
                        for request in requests_for_target(panel, target, expr, prom, loki, now, span):
                            key = (request['datasource'], request['mode'], request['expression'])
                            if key not in queries:
                                request['used_by'] = []
                                queries[key] = request
                            queries[key]['used_by'].append(identity)
                    except ValueError as error:
                        failures.append(dict(identity, status='error', error=str(error)))
        except (OSError, ValueError) as error:
            failures.append({'dashboard_file': str(path), 'status': 'error', 'error': str(error)})
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as executor:
        results = list(executor.map(observe, queries.values()))
    errors = len(failures) + sum(item['status'] == 'error' for item in results)
    summary = {'dashboards': len(files), 'targets': target_count, 'distinct_queries': len(results),
               'errors': errors, 'empty': sum(item.get('data_state') == 'empty' for item in results),
               'with_nonfinite': sum(item.get('nonfinite_samples', 0) > 0 for item in results),
               'elapsed_seconds': round(time.monotonic() - started, 3),
               'summed_query_seconds': round(sum(item['duration_seconds'] for item in results), 3)}
    evidence = {'recorded_at': datetime.datetime.now(datetime.timezone.utc).isoformat(),
                'host': args.host, 'container': args.container, 'window': args.window,
                'rate_window': args.rate_window, 'range_window': args.range_window,
                'summary': summary, 'configuration_errors': failures, 'queries': results}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(evidence, indent=2) + '\n')
    print(json.dumps(summary, sort_keys=True))
    print(f'Evidence: {args.output}')
    return 1 if errors else 0


if __name__ == '__main__':
    raise SystemExit(main())
