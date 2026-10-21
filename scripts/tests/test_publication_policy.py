# SPDX-License-Identifier: Apache-2.0
"""Real loopback HTTP responses exercise the GET-only authorization boundary."""
from copy import deepcopy
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import sys
import threading
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from publication_common import GitHub, PublicationError, decode_json, safe_path
from publication_policy import environment_snapshot, named_environment_snapshot


class PublicationPolicyTests(unittest.TestCase):
    def setUp(self):
        self.policy = {"schemaVersion": 1, "enabled": True, "repository": "example/scarab",
                       "environments": {channel: {"name": "release-" + channel,
                                                "reviewers": [{"type": "User", "id": 42}],
                                                "branches": [{"type": "branch", "name": "main"},
                                                             {"type": "tag", "name": "v*"}]}
                                        for channel in ["alpha", "beta", "preprod", "prod"]}}
        self.environment = {"id": 27, "name": "release-preprod", "can_admins_bypass": False,
                            "protection_rules": [{"type": "required_reviewers", "prevent_self_review": True,
                                                  "reviewers": [{"type": "User", "reviewer": {"id": 42}}]}],
                            "deployment_branch_policy": {"protected_branches": False, "custom_branch_policies": True}}
        self.branches = [{"type": "branch", "name": "main"}, {"type": "tag", "name": "v*"}]
        self.status = 200
        self.requests = []
        owner = self

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                owner.requests.append((self.command, self.path))
                body = ({"branch_policies": owner.branches} if "deployment-branch-policies" in self.path
                        else owner.environment)
                self.send_response(owner.status)
                self.end_headers()
                self.wfile.write(json.dumps(body).encode())

            def do_PUT(self):
                owner.requests.append((self.command, self.path))
                self.send_error(405)

            def log_message(self, *args):
                pass

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever)
        self.thread.start()
        self.addCleanup(self.cleanup_server)
        self.client = GitHub("example/scarab", base="http://127.0.0.1:" + str(self.server.server_port))

    def cleanup_server(self):
        self.server.shutdown()
        self.thread.join(timeout=5)
        self.server.server_close()
        self.assertFalse(self.thread.is_alive(), "owned HTTP worker must terminate")

    def snapshot(self):
        return environment_snapshot(self.client, "preprod", self.policy)

    def test_live_policy_response_is_required_and_snapshot_is_deterministic(self):
        first = self.snapshot()
        self.assertEqual(first, self.snapshot())
        self.assertEqual(first["environmentId"], 27)
        self.assertTrue(self.requests)
        self.assertTrue(all(method == "GET" for method, _ in self.requests), "preflight must never create policy")

    def test_missing_environment_is_refused_without_creation(self):
        self.status = 404
        with self.assertRaisesRegex(PublicationError, "HTTP 404"):
            self.snapshot()
        self.assertEqual([method for method, _ in self.requests], ["GET"])

    def test_unobservable_or_enabled_admin_bypass_is_refused(self):
        for value in [None, True, 0, "false"]:
            with self.subTest(value=value):
                self.environment["can_admins_bypass"] = value
                with self.assertRaisesRegex(PublicationError, "bypass"):
                    self.snapshot()
        del self.environment["can_admins_bypass"]
        with self.assertRaisesRegex(PublicationError, "bypass"):
            self.snapshot()

    def test_empty_or_self_approving_review_policy_is_refused(self):
        for rules in [[], [{"type": "required_reviewers", "prevent_self_review": False}],
                      [{"type": "required_reviewers", "prevent_self_review": True, "reviewers": []}]]:
            with self.subTest(rules=rules):
                self.environment["protection_rules"] = rules
                with self.assertRaises(PublicationError):
                    self.snapshot()

    def test_reviewer_and_environment_recreation_change_the_snapshot(self):
        before = self.snapshot()
        self.environment["id"] = 28
        self.assertNotEqual(self.snapshot()["snapshotSha256"], before["snapshotSha256"])
        self.environment["protection_rules"][0]["reviewers"][0]["reviewer"]["id"] = 99
        with self.assertRaisesRegex(PublicationError, "reviewers differ"):
            self.snapshot()

    def test_branch_and_tag_restrictions_must_equal_reviewed_policy(self):
        for rules in [[], [{"type": "branch", "name": "*"}],
                      [{"type": "branch", "name": "main"}, {"type": "branch", "name": "v*"}]]:
            with self.subTest(rules=rules):
                self.branches = rules
                with self.assertRaisesRegex(PublicationError, "branch/tag"):
                    self.snapshot()

    def test_disabled_missing_or_wrong_repository_policy_never_contacts_api(self):
        for change in [{"enabled": False}, {"repository": "other/scarab"}, {"environments": {}}]:
            with self.subTest(change=change), self.assertRaises(PublicationError):
                environment_snapshot(self.client, "preprod", dict(self.policy, **change))
        self.assertEqual(self.requests, [])

    def test_policy_revision_changes_are_visible_after_approval(self):
        before = self.snapshot()
        revised = deepcopy(self.policy)
        revised["reviewedEpoch"] = 2
        self.assertNotEqual(environment_snapshot(self.client, "preprod", revised)["snapshotSha256"], before["snapshotSha256"])

    def test_shared_app_environment_preflight_accepts_only_reviewed_main_policy(self):
        name = "scarab-release-pr"
        self.policy["automationEnvironments"] = {name: {"name": name, "reviewers": [{"type": "User", "id": 42}],
                                                     "branches": [{"type": "branch", "name": "main"}]}}
        self.environment["name"] = name
        self.branches = [{"type": "branch", "name": "main"}]
        result = named_environment_snapshot(self.client, name, "refs/heads/main", self.policy)
        self.assertEqual(result["environment"], name)
        for ref in ["refs/heads/feature", "refs/tags/v1.2.3", None]:
            with self.subTest(ref=ref), self.assertRaises(PublicationError):
                named_environment_snapshot(self.client, name, ref, self.policy)
        self.policy["automationEnvironments"][name]["branches"].append({"type": "branch", "name": "*"})
        with self.assertRaisesRegex(PublicationError, "main only"):
            named_environment_snapshot(self.client, name, "refs/heads/main", self.policy)

    def test_unknown_app_environment_never_contacts_or_creates_api_resources(self):
        with self.assertRaisesRegex(PublicationError, "absent"):
            named_environment_snapshot(self.client, "unconfigured", "refs/heads/main", self.policy)
        self.assertEqual(self.requests, [])

    def test_non_github_remote_and_local_credentials_are_refused(self):
        for base, token in [("http://api.github.com", None), ("https://evil.invalid", None),
                            ("http://127.0.0.1", "not-a-real-token")]:
            with self.subTest(base=base), self.assertRaises(PublicationError):
                GitHub("example/scarab", token, base)

    def test_duplicate_authority_json_and_unsafe_paths_are_refused(self):
        with self.assertRaises(PublicationError):
            decode_json('{"enabled":false,"enabled":true}')
        for name in ["../secret", "/absolute", "a//b", "a/./b", "a/../../b", "a;echo", "a\\b"]:
            with self.subTest(path=name), self.assertRaises(PublicationError):
                safe_path(Path("/unused"), name)


if __name__ == "__main__":
    unittest.main()
