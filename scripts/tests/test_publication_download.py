# SPDX-License-Identifier: Apache-2.0
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import sys
import tempfile
import threading
import unittest
import urllib.request

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from publication_common import PublicationError, digest
from publication_download import DownloadRedirect, download


class PublicationDownloadTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.body = b"independently fetched candidate content"
        self.descriptor = {"sha256": digest(self.body), "size": len(self.body)}
        self.requests = []
        owner = self

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                owner.requests.append(dict(self.headers))
                if self.path == "/redirect":
                    self.send_response(302)
                    self.send_header("Location", "/asset")
                    self.end_headers()
                else:
                    self.send_response(200)
                    self.end_headers()
                    self.wfile.write(owner.body)

            def log_message(self, *args):
                pass

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever)
        self.thread.start()
        self.addCleanup(self.cleanup_server)
        self.url = "http://127.0.0.1:" + str(self.server.server_port)
        self.target = Path(self.temporary.name) / "asset"

    def cleanup_server(self):
        self.server.shutdown()
        self.thread.join(timeout=5)
        self.server.server_close()
        self.assertFalse(self.thread.is_alive())

    def test_anonymous_actual_bytes_and_redirects_match_pinned_descriptor(self):
        download(self.url + "/redirect", self.target, self.descriptor, local=True)
        self.assertEqual(self.target.read_bytes(), self.body)
        self.assertEqual(len(self.requests), 2)
        self.assertTrue(all("Authorization" not in headers for headers in self.requests))

    def test_tampered_truncated_and_oversized_actual_responses_fail(self):
        original = self.body
        for index, changed in enumerate([original[:-1] + b"!", original[:-1], original + b"extra"]):
            self.body = changed
            with self.subTest(response=changed), self.assertRaises(PublicationError):
                download(self.url + "/asset", self.target.with_name(str(index)), self.descriptor, local=True)

    def test_existing_destinations_and_fixture_credentials_fail_before_request(self):
        self.target.write_bytes(b"existing")
        with self.assertRaises(PublicationError):
            download(self.url, self.target, self.descriptor, local=True)
        with self.assertRaises(PublicationError):
            download(self.url, self.target.with_name("other"), self.descriptor, token="synthetic", local=True)
        self.assertEqual(self.requests, [])

    def test_redirect_drops_authorization_at_origin_boundary_and_refuses_downgrade(self):
        request = urllib.request.Request("https://api.github.com/asset", headers={"Authorization": "synthetic"})
        handler = DownloadRedirect(False)
        result = handler.redirect_request(request, None, 302, "redirect", {}, "https://release-assets.githubusercontent.com/artifact")
        self.assertFalse(result.has_header("Authorization"))
        for url in ["http://github.com/artifact", "https://evil.invalid/artifact", "https://user:password@github.com/artifact"]:
            with self.subTest(url=url), self.assertRaises(PublicationError):
                handler.redirect_request(request, None, 302, "redirect", {}, url)


if __name__ == "__main__":
    unittest.main()
