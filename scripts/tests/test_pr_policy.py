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


REVIEWED = ("## Independent review\n\nCodex, no P1 or P2; the two P3 findings fixed in a1b2c3d: "
            "https://github.com/example/scarab/pull/8#issuecomment-1\n")


def body_of(level, deviations=None, gaps=None, review=REVIEWED):
    parts = [f"## Level and gates\n\n- Level: {level}\n"]
    if deviations is not None:
        parts.append(f"## Deviations from the reference\n\n<!-- None, or a table -->\n{deviations}\n")
    if gaps is not None:
        parts.append(f"## Gaps\n\n{gaps}\n")
    if review is not None:
        parts.append(review)
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

    def test_a_draft_needs_no_review_and_a_pull_request_that_is_not_a_draft_links_its_review(self):
        pr = pull_request(head="feature")
        pr["user"], pr["draft"] = {"type": "User"}, True
        for review in [None, "## Independent review\n\n<!-- the link -->\n", "## Independent review\n\npending\n"]:
            pr["body"] = body_of(1, "None", "None", review)
            with self.subTest(review=review, draft=True):
                self.assertEqual(policy.check_body(pr), 1)
        pr["draft"] = False
        for review in [None, "## Independent review\n\n<!-- https://github.com/example/scarab/pull/8#issuecomment-1 -->\n",
                       "## Independent review\n\nCodex found nothing; see the comment below.\n",
                       "## Independent reviews\n\nhttps://github.com/example/scarab/pull/8#issuecomment-1\n"]:
            pr["body"] = body_of(1, "None", "None", review)
            with self.subTest(review=review, draft=False), self.assertRaisesRegex(
                    policy.PolicyError, 'not a draft needs an "## Independent review" section'):
                policy.check_body(pr)
        pr["body"] = body_of(0, review=None)
        with self.assertRaisesRegex(policy.PolicyError, "Independent review"):
            policy.check_body(pr)
        pr["body"] = body_of(0)
        self.assertEqual(policy.check_body(pr), 0)
        link = "https://github.com/example/scarab/pull/8#issuecomment-1"
        for review in [f"## Independent review\n\n`{link}`\n",
                       f"## Independent review\n\n` a``b````{link} `\n",
                       f"## Independent review\n\nThe review:\n\n    {link}\n",
                       f"## Independent review\n\n[review]: {link}\n",
                       f"## Independent review\n\n[review]:\n  {link}\n",
                       f"## Independent review\n    {link}\n",
                       f"```\n## Independent review\n\n{link}\n```\n",
                       f"## Independent review\n\n~~~\n{link}\n~~~\n",
                       f"## Independent review\n\n# Evidence\n\n{link}\n"]:
            pr["body"] = body_of(1, "None", "None", review)
            with self.subTest(review=review), self.assertRaisesRegex(policy.PolicyError, "not a draft needs"):
                policy.check_body(pr)
        for review in [f"## Independent review\n\nCodex, clean: [the review]({link}).\n",
                       f"## Independent review\n\n### Findings\n\nnone; <{link}>\n",
                       f"## Independent review\n\n- verdict: clean\n- comment: {link}\n",
                       f"## Independent review\n\n\\`[review]({link})\\`\n",
                       f"## Independent review\n\n`code` and {link}\n",
                       f"## Independent review\n\nClean: [review].\n\n[review]: {link}\n",
                       f"## Independent review\n\nClean: [the comment][r].\n\n[R]:\n  {link}\n"]:
            pr["body"] = body_of(1, "None", "None", review)
            with self.subTest(review=review):
                self.assertEqual(policy.check_body(pr), 1)
        pr.pop("draft")
        with self.subTest(draft="absent"):
            pr["body"] = body_of(0, review=None)
            with self.assertRaisesRegex(policy.PolicyError, "Independent review"):
                policy.check_body(pr)

    def test_the_review_link_names_a_comment_or_a_review_of_this_pull_request(self):
        pr = pull_request(head="feature")
        pr["user"], pr["draft"] = {"type": "User"}, False
        footer = "\n🤖 Generated with [Claude Code](https://claude.com/claude-code)\n"
        for review in ["## Independent review\n\nCodex, clean: https://github.com/example/scarab/pull/8#issuecomment-7076\n",
                       "## Independent review\n\n[the review](https://github.com/example/scarab/pull/8#pullrequestreview-9).\n",
                       "## Independent review\n\n<https://github.com/Example/Scarab/pull/8#issuecomment-1>\n",
                       "## Independent review\n\nClean: [review].\n\n[review]: https://github.com/example/scarab/pull/8#issuecomment-1\n",
                       REVIEWED + footer]:
            pr["body"] = body_of(1, "None", "None", review)
            with self.subTest(review=review):
                self.assertEqual(policy.check_body(pr), 1)
        for review in ["## Independent review\n\nPending.\n" + footer,
                       "## Independent review\n\nPending.\n",
                       "## Independent review\n\nhttps://github.com/example/scarab/pull/8\n",
                       "## Independent review\n\nhttps://github.com/example/scarab/pull/80#issuecomment-1\n",
                       "## Independent review\n\nhttps://github.com/example/scarab/pull/7#issuecomment-1\n",
                       "## Independent review\n\nhttps://github.com/example/scarab/issues/8#issuecomment-1\n",
                       "## Independent review\n\nhttps://github.com/fork/scarab/pull/8#issuecomment-1\n",
                       "## Independent review\n\nhttps://github.com/example/scarab/pull/8#issuecomment-1x\n",
                       "## Independent review\n\nhttps://example.org/github.com/example/scarab/pull/8#issuecomment-1\n",
                       "## Independent review\n\n[review]: https://github.com/example/scarab/pull/8#issuecomment-1\n",
                       "## Independent review\n\nSee https://github.com/IntersectMBO/ouroboros-consensus/blob/82ecba329/A.hs#L343\n"]:
            pr["body"] = body_of(1, "None", "None", review)
            with self.subTest(review=review), self.assertRaisesRegex(policy.PolicyError, "a comment or a review of this "
                                                                     "pull request"):
                policy.check_body(pr)
        pr["body"] = body_of(1, "None", "None")
        for broken in [{"number": None}, {"base": {"ref": "main", "repo": {"id": 42}}}]:
            with self.subTest(broken=broken), self.assertRaisesRegex(policy.PolicyError, "Independent review"):
                policy.check_body(dict(pr, **broken))

    def test_a_review_written_in_the_section_passes_without_a_link(self):
        pr = pull_request(head="feature")
        pr["user"], pr["draft"] = {"type": "User"}, False
        footer = "\n🤖 Generated with [Claude Code](https://claude.com/claude-code)\n"
        inline = ("## Independent review\n\nCodex (read-only), two rounds. Round 1 found a missing bound on the "
                  "queue and a stale comment; both are fixed in the second commit. Round 2 at the final head found "
                  "no P0 to P3 findings and confirmed the bound holds on every path.\n")
        for review in [inline, inline + footer]:
            pr["body"] = body_of(1, "None", "None", review)
            with self.subTest(review=review):
                self.assertEqual(policy.check_body(pr), 1)
        short = "## Independent review\n\nCodex: clean, nothing found at the final head.\n"
        hidden = ["## Independent review\n\nPending.\n\n" + "".join(f"[label {i} words here]: https://example.org/{i}\n"
                                                            for i in range(10)),
                  "## Independent review\n\nPending. <code>" + "word " * 30 + "</code>\n",
                  "## Independent review\n\nPending.\n<pre>\n" + "word " * 30 + "\n</pre>\n",
                  "## Independent review\n\nPending. " + " ".join(f"https://example.org/a/b/c/{i}" for i in range(30)) + "\n"]
        for review in [short, short + footer, "## Independent review\n\n`" + "word " * 30 + "`\n" + footer] + hidden:
            pr["body"] = body_of(1, "None", "None", review)
            with self.subTest(review=review), self.assertRaisesRegex(policy.PolicyError, "written in the section"):
                policy.check_body(pr)

    def test_the_review_link_is_read_by_its_destination_never_its_visible_text(self):
        pr = pull_request(head="feature")
        pr["user"], pr["draft"] = {"type": "User"}, False
        this, other = ("https://github.com/example/scarab/pull/8#issuecomment-1",
                       "https://github.com/example/scarab/pull/9#issuecomment-1")
        for review in [f"## Independent review\n\n[{this}]({other})\n",
                       f"## Independent review\n\n[{this}](<{other}>)\n",
                       f"## Independent review\n\n[{this}]({other} \"the review\")\n",
                       f"## Independent review\n\n[see [{this}]]({other})\n",
                       f"## Independent review\n\n[{this}][r]\n\n[r]: {other}\n",
                       f"## Independent review\n\n![{this}]({this})\n"]:
            pr["body"] = body_of(1, "None", "None", review)
            with self.subTest(review=review), self.assertRaisesRegex(policy.PolicyError, "a comment or a review of this "
                                                                     "pull request"):
                policy.check_body(pr)
        for review in [f"## Independent review\n\n[{other}]({this})\n",
                       f"## Independent review\n\n[the review](<{this}>)\n",
                       f"## Independent review\n\n[{other}][r]\n\n[r]: {this}\n"]:
            pr["body"] = body_of(1, "None", "None", review)
            with self.subTest(review=review):
                self.assertEqual(policy.check_body(pr), 1)

    def test_reference_definitions_are_read_from_the_whole_body_and_only_used_links_count(self):
        pr = pull_request(head="feature")
        pr["user"], pr["draft"] = {"type": "User"}, False
        this = "https://github.com/example/scarab/pull/8#issuecomment-1"
        later = "\n## Evidence and docs\n\n- [x] tests\n\n"
        for review, rest in [("Clean: [the review][r].", f"[r]: {this}"),
                             ("Clean: [the review][r].", f"[r]: <{this}>"),
                             ("Clean: [Review].", f"[review]:\n  {this}"),
                             ("Clean: [the review][R  1].", f"[r 1]: {this}")]:
            pr["body"] = body_of(1, "None", "None", f"## Independent review\n\n{review}\n") + later + rest + "\n"
            with self.subTest(review=review):
                self.assertEqual(policy.check_body(pr), 1)
        for review, rest in [("Pending.", f"[r]: {this}"),
                             ("Clean: [the review][r].", f"```\n[r]: {this}\n```"),
                             ("Clean: [the review][r].", f"<!--\n[r]: {this}\n-->"),
                             ("Clean: [the review][s].", f"[r]: {this}"),
                             ("Clean: [the review][r].",
                              "[r]: https://github.com/example/scarab/pull/9#issuecomment-1\n" f"[r]: {this}")]:
            pr["body"] = body_of(1, "None", "None", f"## Independent review\n\n{review}\n") + later + rest + "\n"
            with self.subTest(review=review, rest=rest), self.assertRaisesRegex(policy.PolicyError, "Independent review"):
                policy.check_body(pr)
        pr["body"] = body_of(1, "None", "None", "## Independent review\n\nPending.\n") + later + f"Done: [r].\n\n[r]: {this}\n"
        with self.assertRaisesRegex(policy.PolicyError, "Independent review"):
            policy.check_body(pr)

    def test_a_bot_and_a_promotion_need_no_review_section(self):
        pr = pull_request()
        pr["body"], pr["user"] = "", {"type": "User"}
        self.assertIsNone(policy.check_body(pr))
        pr = pull_request(head="feature")
        pr["body"], pr["user"] = "", {"type": "Bot"}
        self.assertIsNone(policy.check_body(pr))

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
          "- Decision: approved, the V10 sidecar is edited in place\n"
          "- **Decided at:** 2026-11-14T09:41:07Z\n")


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

    def test_the_body_records_the_question_and_the_decision_with_their_utc_times(self):
        record = policy.approval_record(policy.sections(RECORD)["storage format change"])
        self.assertEqual(record["asked_at"], "2026-11-14T09:30:00Z")
        self.assertEqual(record["decided_at"], "2026-11-14T09:41:07Z")
        self.assertEqual(record["decision"], "approved, the V10 sidecar is edited in place")
        self.assertTrue(record["question"].endswith("the alternative keeps the fields unused."))
        base = body_of(3, "None", "None")
        for body, message in [
            (base, "Storage format change\" section"),
            (base + "<!--\n" + RECORD + "-->", "Storage format change\" section"),
            (base + RECORD.replace("- Decision: approved, the V10 sidecar is edited in place\n", ""), "decision is missing"),
            (base + RECORD.replace("`2026-11-14T09:30:00Z`", "this morning"), "asked_at is not a UTC"),
            (base + RECORD.replace("2026-11-14T09:41:07Z", "2026-11-14T09:00:00Z"), "decided_at is not after asked_at"),
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
        self.assertEqual(record["decided_at"], "2026-11-14T09:41:07Z")
        self.assertTrue(self.check(self.pr(body=base + trailing)))
        quoted = RECORD.replace("- Decision: approved, the V10 sidecar is edited in place\n",
                                "- Decision:\n  > approved, the V10 sidecar\n  > is edited in place\n")
        self.assertEqual(policy.approval_record(policy.rendered_section(quoted, "storage format change"))["decision"],
                         "approved, the V10 sidecar\nis edited in place")
        for hidden in ["```\n" + RECORD + "```\n", "~~~~\n" + RECORD + "~~~~\n"]:
            with self.subTest(hidden=hidden[:4]), self.assertRaisesRegex(policy.PolicyError, "section"):
                self.check(self.pr(body=base + hidden))
        ended = "## Storage format change\n\n# Other\n\n" + RECORD.split("\n\n", 1)[1]
        with self.assertRaisesRegex(policy.PolicyError, "section"):
            self.check(self.pr(body=base + ended))
        record = RECORD.split("\n\n", 1)[1]
        for hidden in ["```text\n```still-code\n" + RECORD + "```\n",
                       "## Storage format change\n\nOther\n=====\n\n" + record,
                       "## Storage format change\n\n" + "".join("    " + line + "\n" for line in record.splitlines()),
                       "## Storage format change\n" + "".join("    " + line + "\n" for line in record.splitlines())]:
            with self.subTest(hidden=hidden[:30]), self.assertRaisesRegex(policy.PolicyError, "section|is missing"):
                self.check(self.pr(body=base + hidden))
        self.assertTrue(self.check(self.pr(body=base + RECORD + "---\n\nmore prose\n")))


if __name__ == "__main__":
    unittest.main()
