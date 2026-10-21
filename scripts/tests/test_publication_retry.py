# SPDX-License-Identifier: Apache-2.0
"""Synthetic verifier protocol + actual HTTP/subprocess controls; NOT cryptographic publisher evidence.

The real pinned gh cryptographic controls are retained separately. This interpreter returns
explicit unsigned statements so transport, certificate-policy and subject matching can be tested
without manufacturing a Scarab OIDC identity or calling any service mutation endpoint.
"""
from copy import deepcopy
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import sys
import tarfile
import tempfile
import threading
import unittest
import urllib.parse

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from candidate_fixture import make_candidate
from publication_archive import pack
from publication_candidate import descriptors, release_checksums
from publication_common import GitHub, PublicationError, digest, file_digest, load_json
from publication_retry import BUNDLES, reuse_bundles
from published_receipt import WORKFLOW


class PublicationRetryTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.parent = Path(self.temporary.name)
        self.root = self.parent / 'candidate'
        self.manifest = make_candidate(self.root)
        self.identity = self.manifest['source']['buildIdentity']
        self.archive = self.parent / 'candidate-transport.tar'
        pack(self.root, self.archive)
        (self.root / 'SHA256SUMS').write_bytes(release_checksums(self.root, self.manifest))
        self.image_repo = 'ghcr.io/example/scarab'
        self.documents = self.bundles()
        self.retained = {name: json.dumps(value).encode() for name, value in self.documents.items()}
        self.payloads = None
        self.fetches = []
        for name in BUNDLES:
            (self.root / name).write_bytes(b'newly signed attempt bytes')
        self.gh = self.parent / 'synthetic-gh'
        self.gh.write_text("#!/usr/bin/env python3\n# SPDX-License-Identifier: Apache-2.0\n"
                           "import pathlib,sys\n# UNSIGNED protocol interpreter, not a signature verifier.\n"
                           "print(pathlib.Path(sys.argv[sys.argv.index('--bundle')+1]).read_text())\n")
        self.gh.chmod(0o755)
        owner = self

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                route = urllib.parse.urlsplit(self.path).path
                if route == '/repos/example/scarab':
                    data = json.dumps({'full_name': 'example/scarab', 'id': 42, 'owner': {'id': 7}}).encode()
                elif route == '/repos/example/scarab/releases/9/assets':
                    rows = [{'id': i + 1, 'name': name, 'size': len(raw), 'digest': 'sha256:' + digest(raw)}
                            for i, (name, raw) in enumerate(owner.retained.items())]
                    data = json.dumps(rows).encode()
                elif route.startswith('/repos/example/scarab/releases/assets/'):
                    i = int(route.rsplit('/', 1)[1]) - 1
                    name = list(owner.retained)[i]
                    owner.fetches.append(name)
                    data = owner.payloads[name] if owner.payloads is not None else owner.retained[name]
                else:
                    self.send_response(404)
                    self.end_headers()
                    return
                self.send_response(200)
                self.send_header('Content-Length', str(len(data)))
                self.end_headers()
                self.wfile.write(data)

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

    def bundles(self):
        certificate = {'sourceRepositoryIdentifier': '42', 'sourceRepositoryOwnerIdentifier': '7',
                       'sourceRepositoryDigest': self.identity['gitCommit'], 'buildSignerDigest': self.identity['gitCommit'],
                       'sourceRepositoryRef': 'refs/tags/' + self.identity['gitTag'],
                       'issuer': 'https://token.actions.githubusercontent.com', 'runnerEnvironment': 'github-hosted',
                       'buildSignerURI': 'https://github.com/example/scarab/' + WORKFLOW + '@refs/tags/' + self.identity['gitTag']}
        def proof(subjects, predicate=None):
            return [{'verificationResult': {'signature': {'certificate': deepcopy(certificate)},
                     'statement': {'subject': subjects, 'predicate': {} if predicate is None else predicate}}}]
        def subject(path):
            return {'name': path.name, 'digest': {'sha256': file_digest(path)}}
        jar = next(row for row in self.manifest['subjects'] if row['kind'] == 'jar')
        image = next(row for row in self.manifest['subjects'] if row['kind'] == 'oci-archive')
        paths = [self.root / 'candidate.json', self.root / 'SHA256SUMS', self.archive,
                 *[self.root / row['path'] for row in descriptors(self.manifest)]]
        image_subject = {'name': self.image_repo, 'digest': {'sha256': self.manifest['image']['manifest']['digest'][7:]}}
        return {'provenance.sigstore.json': proof([subject(path) for path in paths]),
                'jar-sbom.sigstore.json': proof([subject(self.root / jar['file']['path'])], load_json(self.root / jar['bom']['file']['path'])),
                'image-provenance.sigstore.json': proof([image_subject]),
                'image-sbom.sigstore.json': proof([image_subject], load_json(self.root / image['bom']['file']['path']))}

    def checked(self):
        reuse_bundles(self.github, {'id': 9}, self.root, self.manifest, self.archive, self.image_repo, self.gh)

    def untouched(self):
        for name in BUNDLES:
            self.assertEqual((self.root / name).read_bytes(), b'newly signed attempt bytes')

    def test_complete_retained_bundles_replace_local_only_after_all_subject_checks(self):
        self.checked()
        self.assertEqual(set(self.fetches), BUNDLES)
        for name, raw in self.retained.items():
            self.assertEqual((self.root / name).read_bytes(), raw)

    def test_partial_retention_reuses_original_and_leaves_missing_bundle_new(self):
        self.retained = {'provenance.sigstore.json': self.retained['provenance.sigstore.json']}
        self.checked()
        self.assertEqual((self.root / 'provenance.sigstore.json').read_bytes(), self.retained['provenance.sigstore.json'])
        for name in BUNDLES - set(self.retained):
            self.assertEqual((self.root / name).read_bytes(), b'newly signed attempt bytes')

    def test_changed_download_bytes_are_rejected_before_any_local_replacement(self):
        self.payloads = dict(self.retained)
        self.payloads['provenance.sigstore.json'] = b'changed transport'
        with self.assertRaises(PublicationError):
            self.checked()
        self.untouched()

    def test_wrong_certificate_source_repo_workflow_ref_issuer_or_runner_refused(self):
        original = deepcopy(self.documents)
        for key, value in [('sourceRepositoryDigest', 'f' * 40), ('buildSignerDigest', 'f' * 40),
                           ('sourceRepositoryIdentifier', '99'), ('sourceRepositoryOwnerIdentifier', '99'),
                           ('sourceRepositoryRef', 'refs/heads/main'), ('issuer', 'https://other.invalid'),
                           ('runnerEnvironment', 'self-hosted'), ('buildSignerURI', 'https://github.com/other/workflow')]:
            changed = deepcopy(original)
            changed['provenance.sigstore.json'][0]['verificationResult']['signature']['certificate'][key] = value
            self.retained = {name: json.dumps(body).encode() for name, body in changed.items()}
            with self.subTest(field=key), self.assertRaises(PublicationError):
                self.checked()
            self.untouched()

    def test_wrong_subject_archive_omission_or_sbom_refused_without_partial_replacement(self):
        original = deepcopy(self.documents)
        for name, kind in [('provenance.sigstore.json', 'omit-archive'), ('image-provenance.sigstore.json', 'subject'),
                           ('jar-sbom.sigstore.json', 'sbom'), ('image-sbom.sigstore.json', 'sbom')]:
            changed = deepcopy(original)
            statement = changed[name][0]['verificationResult']['statement']
            if kind == 'omit-archive':
                statement['subject'] = [row for row in statement['subject'] if row['name'] != self.archive.name]
            elif kind == 'subject':
                statement['subject'][0]['digest']['sha256'] = 'f' * 64
            else:
                statement['predicate'] = {'wrong': 'BOM'}
            self.retained = {key: json.dumps(body).encode() for key, body in changed.items()}
            with self.subTest(name=name), self.assertRaises(PublicationError):
                self.checked()
            self.untouched()


if __name__ == '__main__':
    unittest.main()
