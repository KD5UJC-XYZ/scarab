# SPDX-License-Identifier: Apache-2.0
"""Verify selector escaping and the instant/range API distinction without network access."""
import importlib.util
import pathlib
import unittest
import urllib.parse

PATH = pathlib.Path(__file__).resolve().parents[1] / 'verify_dashboards.py'
SPEC = importlib.util.spec_from_file_location('dashboard_verifier', PATH)
VERIFIER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VERIFIER)


class DashboardVerifierTest(unittest.TestCase):
    def test_literal_selector_and_query_mode_survive_url_encoding(self):
        expr = VERIFIER.substitute('rate(x{host=~"$host",container=~"$container"}[$__rate_interval])',
                                   'node.example', 'scarab+test')
        self.assertEqual(expr, 'rate(x{host=~"node\\\\.example",container=~"scarab\\\\+test"}[5m])')
        panel = {'datasource': {'type': 'prometheus'}}
        requests = VERIFIER.requests_for_target(panel, {'instant': True, 'range': True}, expr,
                                               'http://prom', 'http://loki', 1000, 300)
        self.assertEqual([r['mode'] for r in requests], ['instant', 'range'])
        instant, ranged = [urllib.parse.urlsplit(r['url']) for r in requests]
        self.assertEqual(instant.path, '/api/v1/query')
        self.assertEqual(ranged.path, '/api/v1/query_range')
        self.assertEqual(urllib.parse.parse_qs(instant.query)['query'], [expr])
        self.assertNotIn('start', urllib.parse.parse_qs(instant.query))
        self.assertEqual(urllib.parse.parse_qs(ranged.query)['start'], ['700.000'])
        loki = VERIFIER.requests_for_target({'datasource': {'type': 'loki'}},
            {'queryType': 'instant'}, '{host="test"}', 'http://prom', 'http://loki', 1000, 300)[0]
        self.assertEqual(urllib.parse.urlsplit(loki['url']).path, '/loki/api/v1/query')
        self.assertEqual(urllib.parse.parse_qs(urllib.parse.urlsplit(loki['url']).query)['time'], ['1000000000000'])

    def test_unknown_variables_and_datasources_are_errors(self):
        with self.assertRaisesRegex(ValueError, 'unresolved'):
            VERIFIER.substitute('$new_dimension', 'host', 'container')
        with self.assertRaisesRegex(ValueError, 'unsupported datasource'):
            VERIFIER.requests_for_target({'datasource': {'type': 'other'}}, {}, 'expr',
                                        'http://prom', 'http://loki', 1000, 300)
        for url in ['file:///etc/passwd', 'http://host/path?query=x', 'https://user:secret@host']:
            with self.assertRaises(ValueError):
                VERIFIER.base_url(url)
