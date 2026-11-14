# SPDX-License-Identifier: Apache-2.0
import copy
from datetime import datetime, timezone
from pathlib import Path
import re
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import check_storage_format as fmt
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

    def test_levels_below_one_need_no_sections_and_level_one_needs_both(self):
        self.assertEqual(self.check(body_of(0)), 0)
        for body in [body_of(1), body_of(1, "", "")]:
            with self.subTest(body=body), self.assertRaisesRegex(policy.PolicyError, "Deviations from the reference"):
                self.check(body)

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

    def test_the_changed_files_set_a_floor_under_the_stated_level(self):
        pr = pull_request(head="feature")
        pr["body"], pr["user"] = body_of(0), {"type": "User"}
        self.assertEqual(policy.check_body(pr, ["docs/a.md", "changes/x.md", "README.md"]), 0)
        with self.assertRaisesRegex(policy.PolicyError, "level 0 is below 1"):
            policy.check_body(pr, ["docs/a.md", "node/src/main/scala/A.scala"])
        pr["body"] = body_of(2, "None", "None")
        with self.assertRaisesRegex(policy.PolicyError, "level 2 is below 3: the persisted-format"):
            policy.check_body(pr, [policy.PERSISTED_FORMAT])
        pr["body"] = body_of(3, "None", "None")
        self.assertEqual(policy.check_body(pr, [policy.PERSISTED_FORMAT]), 3)

    def test_the_promotion_controller_path_does_not_read_the_body(self):
        pr = pull_request()
        pr["body"] = ""
        self.assertEqual(policy.evaluate(pr, "example/scarab", 42, "dev-only-main")["head"], "dev")


NOW = datetime(2026, 11, 14, 12, 0, tzinfo=timezone.utc)
STORAGE_TITLE = "fix(storage)!: storage format V10 drops the bootstrap sidecar fields"
STORAGE_FILES = [fmt.MANIFEST, "storage/src/main/scala/xyz/kd5ujc/scarab/storage/SnapshotSidecar.scala",
                 "node/src/test/scala/xyz/kd5ujc/scarab/storage/CodecSuite.scala",
                 "fixtures/snapshot-v10/preview-4320.bin", "docs/storage-migrations.md", "changes/994-sidecar.md"]
RECORD = ("## Storage format change\n\n"
          "- Question: May I change the storage format? The V10 sidecar drops the bootstrap fields; V8 stores are\n"
          "  unaffected; V9 and V10 files are refused; the alternative keeps the fields unused.\n"
          "- Asked at: `2026-11-14T09:30:00Z`\n"
          "- Answer: just modify V10 directly\n"
          "- **Answered at:** 2026-11-14T09:41:07Z\n")


class StorageFormatTests(unittest.TestCase):
    def pr(self, title=STORAGE_TITLE, body=None, user_type="User", head="feature"):
        pr = pull_request(head=head)
        pr["title"], pr["body"], pr["user"] = title, body_of(3, "None", "None") + RECORD if body is None else body, \
            {"type": user_type}
        return pr

    def check(self, pr, files=STORAGE_FILES):
        return policy.check_storage_format(pr, files, NOW)

    def test_a_complete_storage_format_change_passes_and_is_at_least_level_three(self):
        self.assertTrue(self.check(self.pr()))
        self.assertEqual(policy.check_body(self.pr(), STORAGE_FILES), 3)
        low = self.pr(body=body_of(2, "None", "None") + RECORD)
        with self.assertRaisesRegex(policy.PolicyError, "level 2 is below 3: the persisted-format register, the "
                                                        "storage format manifest or its guard"):
            policy.check_body(low, STORAGE_FILES)
        with self.assertRaisesRegex(policy.PolicyError, "level 3 or more, not 2"):
            self.check(low)
        with self.assertRaisesRegex(policy.PolicyError, "level 2 is below 3"):
            policy.check_body(low, [fmt.GUARD])

    def test_a_pull_request_that_leaves_the_manifest_and_the_guard_alone_is_not_one(self):
        self.assertFalse(self.check(self.pr(title="feat(node): accept witness", body=""), ["node/src/main/A.scala"]))
        self.assertTrue(self.check(self.pr(), ["scripts/check_storage_format.py"]))

    def test_the_title_is_conventional_storage_scoped_breaking_and_names_the_storage_format(self):
        for title in ["feat(storage)!: storage format V11 for the Leios state",
                      "fix(storage)!: the Storage Format of V10 keeps its binding"]:
            with self.subTest(title=title):
                self.assertTrue(self.check(self.pr(title=title)))
        for title in ["STORAGE FORMAT: V10 drops the bootstrap sidecar fields",
                      "fix(storage): storage format V10 drops the bootstrap sidecar fields",
                      "fix(node)!: storage format V10 drops the bootstrap sidecar fields",
                      "fix(storage)!: V10 drops the bootstrap sidecar fields",
                      "fix(storage)!: storage formats move", "fix(storage)!:"]:
            with self.subTest(title=title), self.assertRaisesRegex(policy.PolicyError, r"\(storage\)!"):
                self.check(self.pr(title=title))

    def test_it_is_a_pull_request_of_its_own(self):
        for extra in ["consensus/src/main/scala/xyz/kd5ujc/scarab/consensus/NodeState.scala", "AGENTS.md",
                      "changes/README.md", "scripts/pr_policy.py"]:
            with self.subTest(extra=extra), self.assertRaisesRegex(policy.PolicyError, "must be its own pull request.*"
                                                                   + re.escape(extra)):
                self.check(self.pr(), STORAGE_FILES + [extra])

    def test_the_body_records_the_question_and_the_answer_with_their_utc_times(self):
        record = policy.approval_record(policy.sections(RECORD)["storage format change"])
        self.assertEqual(record["asked_at"], "2026-11-14T09:30:00Z")
        self.assertEqual(record["answered_at"], "2026-11-14T09:41:07Z")
        self.assertEqual(record["answer"], "just modify V10 directly")
        self.assertTrue(record["question"].endswith("the alternative keeps the fields unused."))
        base = body_of(3, "None", "None")
        for body, message in [
            (base, "Storage format change\" section"),
            (base + "<!--\n" + RECORD + "-->", "Storage format change\" section"),
            (base + RECORD.replace("- Answer: just modify V10 directly\n", ""), "answer is missing"),
            (base + RECORD.replace("`2026-11-14T09:30:00Z`", "this morning"), "asked_at is not a UTC"),
            (base + RECORD.replace("2026-11-14T09:41:07Z", "2026-11-14T09:00:00Z"), "not after asked_at"),
            (base + RECORD.replace("May I change the storage format?", "May I?"), "does not say plainly"),
        ]:
            with self.subTest(message=message), self.assertRaisesRegex(policy.PolicyError, message):
                self.check(self.pr(body=body))

    def test_a_bot_is_checked_and_a_promotion_of_dev_is_not(self):
        with self.assertRaisesRegex(policy.PolicyError, "level 3 or more, not None"):
            self.check(self.pr(body="", user_type="Bot"))
        with self.assertRaisesRegex(policy.PolicyError, 'needs a "## Storage format change" section'):
            self.check(self.pr(body=body_of(3, "None", "None"), user_type="Bot"))
        self.assertFalse(self.check(self.pr(title="chore: promote dev", body="", head="dev"), STORAGE_FILES + ["a"]))

    def test_a_branch_named_dev_in_a_fork_is_not_a_promotion(self):
        fork = self.pr(title="chore: promote dev", body="", head="dev")
        fork["head"]["repo"] = {"id": 900, "full_name": "fork/scarab"}
        with self.assertRaisesRegex(policy.PolicyError, r"\(storage\)!"):
            self.check(fork, STORAGE_FILES)
        renamed = self.pr(title="chore: promote dev", body="", head="dev")
        renamed["head"]["repo"] = {"id": 42, "full_name": "example/renamed"}
        self.assertFalse(policy.promotion(renamed))

    def test_the_record_is_read_as_github_renders_it(self):
        base = body_of(3, "None", "None")
        trailing = RECORD + "Evidence: checked the migration.\n### Notes\nmore prose\n"
        record = policy.approval_record(policy.rendered_section(base + trailing, "storage format change"))
        self.assertEqual(record["answered_at"], "2026-11-14T09:41:07Z")
        self.assertTrue(self.check(self.pr(body=base + trailing)))
        quoted = RECORD.replace("- Answer: just modify V10 directly\n",
                                "- Answer:\n  > just modify\n  > V10 directly\n")
        self.assertEqual(policy.approval_record(policy.rendered_section(quoted, "storage format change"))["answer"],
                         "just modify\nV10 directly")
        for hidden in ["```\n" + RECORD + "```\n", "~~~~\n" + RECORD + "~~~~\n"]:
            with self.subTest(hidden=hidden[:4]), self.assertRaisesRegex(policy.PolicyError, "section"):
                self.check(self.pr(body=base + hidden))
        ended = "## Storage format change\n\n# Other\n\n" + RECORD.split("\n\n", 1)[1]
        with self.assertRaisesRegex(policy.PolicyError, "section"):
            self.check(self.pr(body=base + ended))


if __name__ == "__main__":
    unittest.main()
