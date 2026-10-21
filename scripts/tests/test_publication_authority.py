# SPDX-License-Identifier: Apache-2.0
"""Actual read-only loopback API exchanges; synthetic metadata is not service authorization."""
from copy import deepcopy
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import sys
import threading
import unittest
import urllib.parse

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from publication_authority import candidate_run, control_plane, CONTROL_PATHS
from publication_common import GitHub, PublicationError


class PublicationAuthorityTests(unittest.TestCase):
    def setUp(self):
        self.source = 'a' * 40
        self.routes = {
            '/actions/runs/42/attempts/1': dict(head_sha=self.source, head_branch='v1.2.3-beta.1', event='workflow_dispatch',
                                    path='.github/workflows/release-publication.yml', run_attempt=1,
                                    repository=dict(full_name='example/scarab')),
            '/actions/runs/42/attempts/1/jobs': dict(jobs=[dict(name=name, conclusion='success') for name in
                                               ['exact-cut-controls', 'candidate / candidate']]),
            '/actions/artifacts/123': dict(workflow_run=dict(id=42, head_sha=self.source), expired=False,
                                          name='release-candidate-' + self.source + '-1', digest='sha256:' + 'b' * 64)}
        owner = self

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                path = urllib.parse.urlsplit(self.path).path.removeprefix('/repos/example/scarab')
                if path.startswith('/contents/'):
                    path += '?' + urllib.parse.urlsplit(self.path).query
                self.send_response(200 if path in owner.routes else 404)
                self.send_header('Content-Type', 'application/json')
                self.end_headers()
                self.wfile.write(json.dumps(owner.routes.get(path, {})).encode())

            def log_message(self, *args):
                pass

        self.server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        self.worker = threading.Thread(target=self.server.serve_forever)
        self.worker.start()
        self.addCleanup(self.cleanup_server)
        self.github = GitHub('example/scarab', base='http://127.0.0.1:' + str(self.server.server_port))

    def cleanup_server(self):
        self.server.shutdown()
        self.worker.join(timeout=5)
        self.server.server_close()
        self.assertFalse(self.worker.is_alive())

    def checked(self):
        return candidate_run(self.github, 42, self.source, 'v1.2.3-beta.1', 123, 'b' * 64)

    def setup_control_plane(self):
        import base64
        trusted = 'f' * 40
        self.routes['/git/ref/heads/main'] = {'object': {'sha': trusted}}
        tree = [{'name': Path(path).name, 'type': 'file', 'sha': '1' * 40} for path in CONTROL_PATHS]
        tree.append({'name': 'ci.yml', 'type': 'file', 'sha': '2' * 40})
        for revision in [self.source, trusted]:
            self.routes['/contents/.github/workflows?ref=' + revision] = deepcopy(tree)
            for path in CONTROL_PATHS:
                self.routes['/contents/' + path + '?ref=' + revision] = {'type': 'file', 'sha': '1' * 40}
        for path in ['scripts/publication_authority.py', 'scripts/publication_coordinator.py',
                     'scripts/published_receipt.py']:
            raw = (Path(__file__).resolve().parents[2] / path).read_bytes()
            self.routes['/contents/' + path + '?ref=' + trusted] = {'encoding': 'base64', 'content': base64.b64encode(raw).decode()}
        return trusted

    def test_complete_workflow_tree_matches_approved_current_control_plane(self):
        control_plane(self.github, self.source, self.setup_control_plane())

    def test_unrelated_workflow_difference_refuses_without_extra_app_permission(self):
        trusted = self.setup_control_plane()
        self.routes['/contents/.github/workflows?ref=' + self.source][-1]['sha'] = '3' * 40
        with self.assertRaisesRegex(PublicationError, 'complete workflow tree'):
            control_plane(self.github, self.source, trusted)

    def test_exact_canonical_run_completed_gates_and_original_artifact(self):
        self.assertEqual(self.checked()['workflow_run']['id'], 42)

    def test_retained_attempt_one_survives_later_failed_ui_rerun(self):
        self.routes['/actions/runs/42'] = dict(self.routes['/actions/runs/42/attempts/1'], run_attempt=2)
        self.routes['/actions/runs/42/jobs'] = {'jobs': [{'name': 'candidate / candidate', 'conclusion': 'failure'}]}
        self.assertEqual(self.checked()['name'], 'release-candidate-' + self.source + '-1')

    def test_another_source_ref_workflow_event_or_repository_refused(self):
        original = deepcopy(self.routes)
        for field, value in [('head_sha', 'f' * 40), ('head_branch', 'main'), ('event', 'push'),
                             ('path', '.github/workflows/untrusted.yml'), ('repository', {'full_name': 'fork/scarab'})]:
            self.routes = deepcopy(original)
            self.routes['/actions/runs/42/attempts/1'][field] = value
            with self.subTest(field=field), self.assertRaises(PublicationError):
                self.checked()

    def test_failed_missing_or_duplicate_candidate_and_tooling_gates_refused(self):
        for name in ['candidate / candidate', 'exact-cut-controls']:
            original = deepcopy(self.routes['/actions/runs/42/attempts/1/jobs'])
            for conclusion in ['failure', 'cancelled', 'skipped', None]:
                self.routes['/actions/runs/42/attempts/1/jobs'] = deepcopy(original)
                row = next(row for row in self.routes['/actions/runs/42/attempts/1/jobs']['jobs'] if row['name'] == name)
                row['conclusion'] = conclusion
                with self.subTest(name=name, conclusion=conclusion), self.assertRaises(PublicationError):
                    self.checked()
            self.routes['/actions/runs/42/attempts/1/jobs'] = original

    def test_wrong_artifact_run_hash_expiry_name_or_source_refused(self):
        original = deepcopy(self.routes['/actions/artifacts/123'])
        for field, value in [('workflow_run', {'id': 99, 'head_sha': self.source}),
                             ('workflow_run', {'id': 42, 'head_sha': 'f' * 40}), ('expired', True),
                             ('name', 'incomplete-candidate'), ('digest', 'sha256:' + 'f' * 64)]:
            self.routes['/actions/artifacts/123'] = dict(original, **{field: value})
            with self.subTest(field=field, value=value), self.assertRaises(PublicationError):
                self.checked()


if __name__ == '__main__':
    unittest.main()
