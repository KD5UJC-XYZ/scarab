# SPDX-License-Identifier: Apache-2.0
import copy
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import pr_policy as policy


def pull_request(base="main", head="dev", source_id=42):
    repo = {"id": 42, "full_name": "example/scarab"}
    return {"number": 8, "title": "feat(node)!: validate original bytes", "state": "open", "draft": False,
            "mergeable": True, "mergeable_state": "clean",
            "base": {"ref": base, "sha": "b" * 40, "repo": repo},
            "head": {"ref": head, "sha": "a" * 40,
                     "repo": {"id": source_id, "full_name": "example/scarab" if source_id == 42 else "fork/scarab"}}}


class PrPolicyTests(unittest.TestCase):
    def evaluate(self, pr, mode="dev-only-main"):
        return policy.evaluate(pr, "example/scarab", 42, mode)

    def test_cutover_requires_same_repository_dev_for_main(self):
        self.assertEqual(self.evaluate(pull_request())["head"], "dev")
        for head, source in [("feature", 42), ("dev", 900), ("release/prod", 42)]:
            with self.subTest(head=head, source=source), self.assertRaisesRegex(policy.PolicyError, "only dev"):
                self.evaluate(pull_request(head=head, source_id=source))

    def test_other_targets_accept_any_source_including_forks(self):
        for base in ["dev", "release/alpha", "release/beta", "release/preprod", "release/prod", "feature"]:
            with self.subTest(base=base):
                self.assertEqual(self.evaluate(pull_request(base, "anything", 900))["base"], base)

    def test_transition_keeps_main_open_to_feature_prs(self):
        self.assertEqual(self.evaluate(pull_request(head="feature", source_id=900), "transition")["mode"], "transition")
        with self.assertRaisesRegex(policy.PolicyError, "unknown"):
            self.evaluate(pull_request(), "dev-only-mian")

    def test_source_policy_does_not_treat_a_shared_commit_as_branch_authority(self):
        allowed = pull_request()
        refused = pull_request(head="feature")
        self.assertEqual(allowed["head"]["sha"], refused["head"]["sha"])
        self.evaluate(allowed)
        with self.assertRaises(policy.PolicyError):
            self.evaluate(refused)

    def test_conventional_titles_and_breaking_markers(self):
        for title in ["fix: repair parsing", "feat(node): accept witness", "chore(main): release 0.2.0", "feat!: change interface"]:
            pr = pull_request()
            pr["title"] = title
            with self.subTest(title=title):
                self.evaluate(pr)
        for title in ["Fix parsing", "fix:", "fix:  ", "feat(node): first\n::error::injected", " feat: title"]:
            pr = pull_request()
            pr["title"] = title
            with self.subTest(title=title), self.assertRaises(policy.PolicyError):
                self.evaluate(pr)

    def test_missing_or_mismatched_repository_and_commit_fail(self):
        for field, value in [("repo", None), ("sha", "a" * 7)]:
            pr = pull_request()
            pr["head"][field] = value
            with self.subTest(field=field), self.assertRaises(policy.PolicyError):
                self.evaluate(pr)
        pr = copy.deepcopy(pull_request())
        pr["base"]["repo"]["id"] = 900
        with self.assertRaisesRegex(policy.PolicyError, "expected repository"):
            self.evaluate(pr)


if __name__ == "__main__":
    unittest.main()
