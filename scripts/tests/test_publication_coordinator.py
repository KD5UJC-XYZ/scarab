# SPDX-License-Identifier: Apache-2.0
"""Independent loopback GitHub protocol fixture; no official tags, assets or releases are written."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import sys
import tempfile
import threading
import unittest
import urllib.parse

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from publication_common import GitHub, PublicationError, digest
from publication_coordinator import Publication, body_with_state, reserve_tag, state_from_release
from published_receipt import AuthenticatedReceipt, _AUTHENTICATED


class PublicationCoordinatorTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.source, self.tip, self.tag_object = "a" * 40, "a" * 40, "a" * 40
        self.tag_exists = True
        self.release, self.assets, self.requests = None, {}, []
        self.notes = "Reviewed source notes."
        owner = self

        class Handler(BaseHTTPRequestHandler):
            def reply(self, status, body):
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(json.dumps(body).encode())

            def do_GET(self):
                owner.requests.append((self.command, self.path))
                route = urllib.parse.urlsplit(self.path).path
                if "/git/ref/tags/" in route:
                    if owner.tag_exists:
                        self.reply(200, {"ref": "refs/tags/v1.2.3-beta.1", "object": {"type": "commit", "sha": owner.tag_object, "url": "fixture"}})
                    else:
                        self.reply(404, {})
                elif "/git/ref/heads/release/beta" in route:
                    self.reply(200, {"ref": "refs/heads/release/beta", "object": {"type": "commit", "sha": owner.tip}})
                elif "/compare/" in route:
                    self.reply(200, {"status": "identical" if owner.tip == owner.source else
                                    "ahead" if owner.tip == "b" * 40 else "diverged"})
                elif "/releases/tags/" in route:
                    self.reply(200 if owner.release else 404, owner.release or {})
                elif route.endswith("/releases"):
                    self.reply(200, [owner.release] if owner.release else [])
                elif route.endswith("/assets"):
                    self.reply(200, list(owner.assets.values()))
                else:
                    self.reply(404, {})

            def do_POST(self):
                owner.requests.append((self.command, self.path))
                body = self.rfile.read(int(self.headers["Content-Length"]))
                route = urllib.parse.urlsplit(self.path)
                if route.path.endswith("/releases"):
                    owner.release = dict(json.loads(body), id=9)
                    self.reply(201, owner.release)
                elif route.path.endswith("/assets"):
                    name = urllib.parse.parse_qs(route.query)["name"][0]
                    if name in owner.assets:
                        self.reply(422, {})
                    else:
                        asset = {"name": name, "size": len(body), "digest": "sha256:" + digest(body), "id": len(owner.assets) + 1}
                        owner.assets[name] = asset
                        self.reply(201, asset)
                elif route.path.endswith("/git/refs"):
                    request = json.loads(body)
                    owner.tag_exists, owner.tag_object = True, request["sha"]
                    self.reply(201, {"ref": request["ref"], "object": {"type": "commit", "sha": request["sha"], "url": "fixture"}})
                else:
                    self.reply(404, {})

            def do_PATCH(self):
                owner.requests.append((self.command, self.path))
                owner.release.update(json.loads(self.rfile.read(int(self.headers["Content-Length"]))))
                self.reply(200, owner.release)

            def log_message(self, *args):
                pass

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever)
        self.thread.start()
        self.addCleanup(self.cleanup_server)
        self.github = GitHub("example/scarab", base="http://127.0.0.1:" + str(self.server.server_port))
        self.identity = {"schemaVersion": "1", "mode": "release", "version": "1.2.3-beta.1", "gitTag": "v1.2.3-beta.1",
                         "gitCommit": self.source, "gitTagObject": self.source, "channel": "beta",
                         "sourceRef": "refs/heads/release/beta", "sourceRefCommit": self.source}
        self.publication = Publication(self.github, self.identity, "c" * 64, "sha256:" + "d" * 64)

    def cleanup_server(self):
        self.server.shutdown()
        self.thread.join(timeout=5)
        self.server.server_close()
        self.assertFalse(self.thread.is_alive())

    def record(self):
        path = Path(self.temporary.name) / "candidate.json"
        path.write_bytes(b"frozen candidate")
        return {"path": path, "name": "candidate.json", "sha256": digest(path.read_bytes()), "size": path.stat().st_size}

    def writes(self):
        return [request for request in self.requests if request[0] != "GET"]

    def test_first_attempt_creates_only_draft_never_latest_or_public(self):
        release = self.publication.ensure_draft(self.notes)
        self.assertTrue(release["draft"])
        self.assertTrue(release["prerelease"])
        self.assertEqual(release["make_latest"], "false")
        self.assertEqual(state_from_release(release)["status"], "draft")
        self.assertEqual(len(self.writes()), 1)

    def test_identical_retry_and_streamed_assets_do_not_overwrite_or_duplicate(self):
        release = self.publication.ensure_draft(self.notes)
        record = self.record()
        self.publication.upload(release, [record])
        writes = len(self.writes())
        self.publication.ensure_draft(self.notes)
        self.publication.upload(release, [record])
        self.assertEqual(len(self.writes()), writes)
        self.assertEqual(self.assets[record["name"]]["digest"], "sha256:" + record["sha256"])

    def test_wrong_server_tag_or_first_branch_tip_fails_before_staging(self):
        for attribute in ["tip", "tag_object"]:
            setattr(self, attribute, "b" * 40)
            with self.subTest(attribute=attribute), self.assertRaises(PublicationError):
                self.publication.ensure_draft(self.notes)
            setattr(self, attribute, self.source)
        self.assertEqual(self.writes(), [])

    def test_retry_ancestry_requires_an_existing_same_input_attempt(self):
        self.publication.ensure_draft(self.notes)
        self.tip = "b" * 40
        self.assertEqual(self.publication.ensure_draft(self.notes)["id"], 9)
        self.tip = "f" * 40
        with self.assertRaisesRegex(PublicationError, "no longer retained"):
            self.publication.ensure_draft(self.notes)

    def test_changed_candidate_or_notes_cannot_reuse_existing_release(self):
        self.publication.ensure_draft(self.notes)
        other = Publication(self.github, self.identity, "e" * 64, "sha256:" + "d" * 64)
        with self.assertRaisesRegex(PublicationError, "immutable"):
            other.ensure_draft(self.notes)
        with self.assertRaisesRegex(PublicationError, "notes differ"):
            self.publication.ensure_draft("replacement notes")

    def test_changed_local_or_existing_remote_assets_fail_without_clobber(self):
        release = self.publication.ensure_draft(self.notes)
        record = self.record()
        self.publication.upload(release, [record])
        self.assets[record["name"]]["digest"] = "sha256:" + "f" * 64
        before = len(self.writes())
        with self.assertRaisesRegex(PublicationError, "never clobber"):
            self.publication.upload(release, [record])
        record["path"].write_bytes(b"mutated")
        with self.assertRaisesRegex(PublicationError, "changed before upload"):
            self.publication.upload(release, [record])
        self.assertEqual(len(self.writes()), before)

    def test_partial_failure_never_advances_without_authenticated_independent_receipt(self):
        self.publication.ensure_draft(self.notes)
        self.publication.transition("draft", "draft-staged", self.notes)
        with self.assertRaisesRegex(PublicationError, "authenticated"):
            self.publication.transition("draft-staged", "verified", self.notes, {"status": "verified"})
        with self.assertRaisesRegex(PublicationError, "forbidden"):
            self.publication.transition("draft-staged", "published", self.notes)
        self.assertTrue(self.release["draft"])
        self.assertEqual(state_from_release(self.release)["status"], "draft-staged")

    def production_publication(self):
        identity = dict(self.identity, version="1.2.3", gitTag="v1.2.3", channel="prod", sourceRef="refs/heads/release/prod")
        publication = Publication(self.github, identity, "c" * 64, "sha256:" + "d" * 64)
        state = dict(publication.expected, status="verified")
        self.release = {"id": 9, "tag_name": "v1.2.3", "prerelease": False, "draft": True,
                        "body": body_with_state(self.notes, state)}
        return publication

    def receipt(self, image="sha256:" + "d" * 64):
        document = {"schemaVersion": 1, "status": "verified", "phase": "staged", "repository": "example/scarab",
                    "tag": "v1.2.3", "sourceCommit": self.source, "version": "1.2.3", "channel": "prod",
                    "candidateSha256": "c" * 64,
                    "subjects": {"jarSha256": "e" * 64, "imageDigest": image, "bomSha256": ["1" * 64, "2" * 64]},
                    "publisher": {"workflow": ".github/workflows/release-publication.yml",
                                  "workflowCommit": self.source, "runId": 7},
                    "checks": {name: True for name in ["hashes", "attestations", "sourceBinding", "bomBinding",
                                                       "jarRuntime", "imageRuntime", "anonymousDownloads"]}}
        document["checks"]["anonymousDownloads"] = False
        return AuthenticatedReceipt(json.dumps(document).encode(), _AUTHENTICATED)

    def test_production_publishes_with_an_authenticated_receipt_and_no_produced_block_proof(self):
        publication = self.production_publication()
        publication.transition("verified", "published", self.notes, self.receipt())
        self.assertFalse(self.release["draft"])
        self.assertEqual(state_from_release(self.release)["status"], "published")

    def test_production_is_refused_without_a_matching_authenticated_receipt(self):
        publication = self.production_publication()
        with self.assertRaisesRegex(PublicationError, "authenticated"):
            publication.transition("verified", "published", self.notes, {"status": "verified"})
        with self.assertRaisesRegex(PublicationError, "other artifacts"):
            publication.transition("verified", "published", self.notes, self.receipt("sha256:" + "f" * 64))
        self.assertTrue(self.release["draft"])
        self.assertEqual(state_from_release(self.release)["status"], "verified")

    def test_reserved_lightweight_tag_retries_are_immutable(self):
        self.tag_exists = False
        first = reserve_tag(self.github, "v1.2.3-beta.1", self.source, "beta")
        writes = len(self.writes())
        self.assertEqual(reserve_tag(self.github, "v1.2.3-beta.1", self.source, "beta"), first)
        self.assertEqual(len(self.writes()), writes)
        self.tag_object = "b" * 40
        with self.assertRaisesRegex(PublicationError, "never move"):
            reserve_tag(self.github, "v1.2.3-beta.1", self.source, "beta")
        self.assertEqual(len(self.writes()), writes)

    def test_notes_cannot_inject_an_alternate_coordinator_record(self):
        with self.assertRaises(PublicationError):
            self.publication.ensure_draft("<!-- scarab-publication-v1:{} -->")
        self.assertIsNone(self.release)


if __name__ == "__main__":
    unittest.main()
