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


def body_of(level, deviations=None, gaps=None):
    parts = [f"## Level and gates\n\n- Level: {level}\n"]
    if deviations is not None:
        parts.append(f"## Deviations from the reference\n\n<!-- None, or a table -->\n{deviations}\n")
    if gaps is not None:
        parts.append(f"## Gaps\n\n{gaps}\n")
    return "\n".join(parts)


TABLE = ("| Behaviour | Reference (pinned) | Scarab | Approval |\n|---|---|---|---|\n"
         "| accept limit per address | network 2fb69829 `Server.hs:40` | 64 per address | "
         "[#474](https://github.com/KD5UJC-XYZ/scarab/issues/474#issuecomment-6404228543) |")


class PrBodyTests(unittest.TestCase):
    def check(self, body, head="feature", user_type="User"):
        pr = pull_request(head=head)
        pr["body"], pr["user"] = body, {"type": user_type}
        return policy.check_body(pr)

    def test_the_level_is_read_in_its_usual_forms_and_the_unfilled_template_is_none(self):
        for text, level in [("- Level: 1 (tooling)", 1), ("**Level 1** (scripts)", 1), ("Level 0 docs.", 0),
                            ("- Level: 0 docs / 1 small code / 2 behaviour", None), ("a level 2 change", None), ("", None)]:
            with self.subTest(text=text):
                self.assertEqual(policy.level_of(text), level)

    def test_a_body_without_a_level_fails_unless_a_bot_or_a_promotion_opened_it(self):
        with self.assertRaisesRegex(policy.PolicyError, "state the level"):
            self.check("## What and why\n\nNo level here.")
        self.assertIsNone(self.check("", user_type="Bot"))
        self.assertIsNone(self.check("", head="dev"))

    def test_levels_below_two_need_no_sections(self):
        self.assertEqual(self.check(body_of(1)), 1)

    def test_level_two_needs_both_sections_none_or_well_formed(self):
        self.assertEqual(self.check(body_of(2, "None", "None.")), 2)
        self.assertEqual(self.check(body_of(2, "None. This removes one deviation of #474.", "None: all tracked.")), 2)
        for opening in ["Nonetheless, see below", "Non-blocking", "Some: none recorded"]:
            with self.subTest(opening=opening), self.assertRaises(policy.PolicyError):
                self.check(body_of(2, opening, "None"))
        self.assertEqual(self.check(body_of(3, TABLE, "- #612: the owner-decision label check\n  continued")), 3)
        self.assertEqual(self.check(body_of(2, "None", "- [#605](https://github.com/o/r/issues/605): forms")), 2)
        for body, message in [
            (body_of(2, gaps="None"), "Deviations from the reference"),
            (body_of(2, deviations="None"), "Gaps"),
            (body_of(2, "Some, see below", "None"), "a table"),
            (body_of(2, TABLE.replace("[#474](https://github.com/KD5UJC-XYZ/scarab/issues/474#issuecomment-6404228543)",
                                      "approved"), "None"), "approval cell"),
            (body_of(2, TABLE.rsplit("|", 2)[0] + "|", "None"), "four nonempty cells"),
            (body_of(2, "None", "- the label is missing (#612)"), "starts with the issue"),
            (body_of(2, "None", "the label is missing"), "starts with the issue"),
        ]:
            with self.subTest(message=message), self.assertRaisesRegex(policy.PolicyError, message):
                self.check(body)

    def test_the_promotion_controller_path_does_not_read_the_body(self):
        pr = pull_request()
        pr["body"] = ""
        self.assertEqual(policy.evaluate(pr, "example/scarab", 42, "dev-only-main")["head"], "dev")


if __name__ == "__main__":
    unittest.main()
