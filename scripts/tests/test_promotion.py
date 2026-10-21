# SPDX-License-Identifier: Apache-2.0
import copy
import hashlib
import os
from pathlib import Path
import sys
import subprocess
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from pr_policy import PolicyError
import promotion_rules as rules
from promote_main import promote
from promotion_audit import approved_bytes
from publication_common import canonical
from test_pr_policy import pull_request


class GitHubFixture:
    def __init__(self):
        self.pr = pull_request()
        self.rules = [dict(rule, id=index) for index, rule in enumerate(rules.rulesets(100, 200), 1)]
        for rule, mode in zip(self.rules, ["never", "pull_requests_only", "never", "never"]):
            rule.update(updated_at="2026-10-21T12:00:00Z", source_type="Repository",
                        source="example/scarab", current_user_can_bypass=mode)
        self.base = "b" * 40
        self.mutations = []

    def request(self, method, path, body=None):
        if method == "PUT":
            self.mutations.append((path, body))
            return {"merged": True, "sha": "c" * 40}
        if path.endswith("/rulesets?includes_parents=true&per_page=100"):
            return [{"id": rule["id"]} for rule in self.rules]
        if "/rulesets/" in path:
            return next(rule for rule in self.rules if rule["id"] == int(path.rsplit("/", 1)[1]))
        if path.endswith("/pulls/8"):
            return copy.deepcopy(self.pr)
        if path.endswith("/git/ref/heads/main"):
            return {"object": {"sha": self.base}}
        if path == "/repos/example/scarab":
            return {"id": 42, "default_branch": "main"}
        raise AssertionError("unexpected request " + path)


class PromotionTests(unittest.TestCase):
    def run_promotion(self, fixture, **overrides):
        args = dict(repository="example/scarab", repository_id=42, number=8, expected_head="a" * 40,
                    expected_base="b" * 40, promotion_app_id=100, release_app_id=200,
                    mode="dev-only-main", invocation_ref="refs/heads/main", invocation_sha="b" * 40)
        args.update(overrides)
        return promote(fixture, **args)

    def test_merge_uses_explicit_head_cas_and_no_bypass(self):
        api = GitHubFixture()
        result = self.run_promotion(api)
        self.assertTrue(result["merged"])
        self.assertEqual(api.mutations, [("/repos/example/scarab/pulls/8/merge",
                                         {"sha": "a" * 40, "merge_method": "merge"})])

    def test_shared_green_sha_does_not_authorize_feature_to_main(self):
        api = GitHubFixture()
        self.run_promotion(api)
        api.mutations.clear()
        api.pr = pull_request(head="feature")
        with self.assertRaisesRegex(PolicyError, "only dev"):
            self.run_promotion(api)
        self.assertFalse(api.mutations)

    def test_no_merge_before_cutover_or_from_an_untrusted_workflow(self):
        for override in [{"mode": "transition"}, {"invocation_ref": "refs/heads/feature"},
                         {"expected_head": "d" * 40}, {"expected_base": "e" * 40},
                         {"invocation_sha": "e" * 40}]:
            api = GitHubFixture()
            with self.subTest(override=override), self.assertRaises(PolicyError):
                self.run_promotion(api, **override)
            self.assertFalse(api.mutations)

    def test_changed_rules_or_bypass_actors_refuse_before_merge(self):
        for mutate in [lambda r: r[0].update(enforcement="disabled"),
                       lambda r: r[0]["bypass_actors"].append({"actor_id": 100, "actor_type": "Integration", "bypass_mode": "always"}),
                       lambda r: r[1]["bypass_actors"][0].update(bypass_mode="always"),
                       lambda r: r[3]["bypass_actors"].append({"actor_id": 200, "actor_type": "Integration", "bypass_mode": "always"})]:
            api = GitHubFixture()
            mutate(api.rules)
            with self.assertRaisesRegex(PolicyError, "ruleset"):
                self.run_promotion(api)
            self.assertFalse(api.mutations)

    def test_stale_draft_closed_pending_or_blocked_pr_never_merges(self):
        for patch in [{"draft": True}, {"state": "closed"}, {"mergeable": None},
                      {"mergeable_state": "blocked"}, {"base": dict(pull_request()["base"], ref="dev")}]:
            api = GitHubFixture()
            api.pr.update(patch)
            with self.subTest(patch=patch), self.assertRaises(PolicyError):
                self.run_promotion(api)
            self.assertFalse(api.mutations)
        api = GitHubFixture()
        api.base = "d" * 40
        with self.assertRaisesRegex(PolicyError, "advanced"):
            self.run_promotion(api)
        self.assertFalse(api.mutations)

    def test_redacted_bypass_requires_reviewed_unchanged_owner_snapshot(self):
        api = GitHubFixture()
        audit = rules.owner_audit(api.rules, 100, 200, "example/scarab")
        for rule in api.rules:
            del rule["bypass_actors"]
        with self.assertRaisesRegex(PolicyError, "owner-reviewed"):
            self.run_promotion(api)
        self.assertFalse(api.mutations)
        self.assertTrue(self.run_promotion(api, ruleset_audit=audit)["merged"])

    def test_audited_identity_version_and_actual_app_authority_must_match(self):
        for patch in [{"id": 90}, {"updated_at": "2026-10-21T12:00:01Z"},
                      {"source": "other/scarab"}, {"current_user_can_bypass": "always"}]:
            api = GitHubFixture()
            audit = rules.owner_audit(api.rules, 100, 200, "example/scarab")
            for rule in api.rules:
                del rule["bypass_actors"]
            api.rules[0].update(patch)
            with self.subTest(patch=patch), self.assertRaises(PolicyError):
                self.run_promotion(api, ruleset_audit=audit)
            self.assertFalse(api.mutations)

    def test_snapshot_is_not_an_override_for_visible_wrong_actors(self):
        api = GitHubFixture()
        audit = rules.owner_audit(api.rules, 100, 200, "example/scarab")
        api.rules[0]["bypass_actors"].append(
            {"actor_id": 200, "actor_type": "Integration", "bypass_mode": "always"})
        with self.assertRaises(PolicyError):
            self.run_promotion(api, ruleset_audit=audit)
        self.assertFalse(api.mutations)

    def test_owner_snapshot_requires_real_repository_metadata_and_complete_actors(self):
        for field in ["bypass_actors", "id", "updated_at", "source", "source_type"]:
            api = GitHubFixture()
            del api.rules[0][field]
            with self.subTest(field=field), self.assertRaises(PolicyError):
                rules.owner_audit(api.rules, 100, 200, "example/scarab")

    def test_release_app_requires_separate_creation_only_authority(self):
        api = GitHubFixture()
        audit = rules.owner_audit(api.rules, 100, 200, "example/scarab")
        for rule, mode in zip(api.rules, ["never", "never", "always", "never"]):
            del rule["bypass_actors"]
            rule["current_user_can_bypass"] = mode
        rules.verify_rulesets(api.rules, 100, 200, audit=audit, actor_role="release", repository="example/scarab")
        api.rules[3]["current_user_can_bypass"] = "always"
        with self.assertRaisesRegex(PolicyError, "authority"):
            rules.verify_rulesets(api.rules, 100, 200, audit=audit, actor_role="release", repository="example/scarab")

    def test_approval_input_is_strict_bounded_data_and_cannot_change_policy(self):
        audit = rules.owner_audit(GitHubFixture().rules, 100, 200, "example/scarab")
        self.assertEqual(approved_bytes(canonical(audit), "example/scarab", 100, 200), canonical(audit))
        for encoded in [b'{"schemaVersion":1,"schemaVersion":1}', b' ' * 65537,
                        canonical(dict(audit, repository="other/scarab"))]:
            with self.subTest(encoded=encoded[:80]), self.assertRaises(ValueError):
                approved_bytes(encoded, "example/scarab", 100, 200)
        audit['rulesets'][0]['bypass_actors'] = [
            {"actor_id": 100, "actor_type": "Integration", "bypass_mode": "always"}]
        with self.assertRaises(PolicyError):
            approved_bytes(canonical(audit), "example/scarab", 100, 200)

    def test_preflight_retains_exact_digest_and_changed_artifact_refuses_before_key(self):
        audit = rules.owner_audit(GitHubFixture().rules, 100, 200, "example/scarab")
        tool = Path(__file__).resolve().parents[1] / 'promotion_audit.py'
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            artifact = root / 'audit.json'
            environment = dict(os.environ, RULESET_AUDIT=canonical(audit).decode(),
                               GITHUB_OUTPUT=str(root / 'outputs'), GITHUB_STEP_SUMMARY=str(root / 'summary'))
            command = [sys.executable, str(tool), '--repository', 'example/scarab',
                       '--promotion-app-id', '100', '--release-app-id', '200']
            subprocess.run([*command, '--output', str(artifact)], env=environment, check=True,
                           stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            digest = hashlib.sha256(artifact.read_bytes()).hexdigest()
            self.assertEqual((root / 'outputs').read_text(), 'sha256=' + digest + '\n')
            self.assertIn(digest, (root / 'summary').read_text())
            verify = [*command, '--input', str(artifact), '--expected-sha256', digest]
            subprocess.run(verify, check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            artifact.write_bytes(artifact.read_bytes() + b' ')
            refused = subprocess.run(verify, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            self.assertNotEqual(refused.returncode, 0)
            self.assertIn(b'artifact digest differs', refused.stderr)


if __name__ == "__main__":
    unittest.main()
