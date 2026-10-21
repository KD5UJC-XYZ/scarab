# SPDX-License-Identifier: Apache-2.0
import fcntl
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("release_fragments", Path(__file__).resolve().parents[1] / "release_fragments.py")
r = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(r)

LEGACY = "### Added (legacy)\n\n- Original legacy text.\n"
HISTORY = "## [0.1.0] - 2026-10-11\n\n### Added\n\n- Earlier history.\n"
CHANGELOG = "# Changelog\n\n## [Unreleased]\n\nFragments supply new entries.\n\n" + LEGACY + "\n" + HISTORY


class ReleaseFragmentsTests(unittest.TestCase):
    def setUp(self):
        self.scratch = tempfile.TemporaryDirectory()
        self.addCleanup(self.scratch.cleanup)
        self.repo = Path(self.scratch.name) / "repo"
        self.repo.mkdir()
        self.git("init", "-q")
        self.git("config", "user.name", "Fixture")
        self.git("config", "user.email", "fixture@example.invalid")
        (self.repo / "changes").mkdir()
        (self.repo / "changes/README.md").write_text("Retain the README.\n")
        (self.repo / "CHANGELOG.md").write_text(CHANGELOG)
        self.base = self.save()
        self.write("10-two.md", "### Fixed (same issue, #10)\n\n- Second heading.\n\n### Changed (topic)\n\n- More.\n")
        self.write("2-one.md", "### Added (#2)\n\n- First.\n")
        self.write("10-one.md", "### Fixed (same issue, #10)\n\n- Distinct entry.\n")
        self.write("z-last.md", "### Removed (last)\n\n- Last.\n")
        self.source = self.save()

    def git(self, *args):
        return subprocess.check_output(["git", "-C", str(self.repo), *args], stderr=subprocess.DEVNULL).decode().strip()

    def save(self):
        self.git("add", ".")
        self.git("commit", "-qm", "fixture")
        return self.git("rev-parse", "HEAD")

    def write(self, name, body):
        (self.repo / "changes" / name).write_text(body)

    def build(self, **kw):
        return r.build(self.repo, self.source, self.base, kw.pop("release_version", "0.2.0"), "2026-10-21", **kw)

    def prepare(self, version="0.2.0", selected=None):
        out = Path(self.scratch.name) / (version + "-preview")
        r.prepare(self.repo, out, source=self.source, baseline=self.base, release_version=version,
                  release_date="2026-10-21", selected=selected)
        return out / "plan.json"

    def receipt(self, version="0.2.0"):
        return f".release/changelog/{version}.json"

    def test_deterministic_numeric_issue_path_order_and_duplicate_topics(self):
        plan, notes, changelog = self.build()
        self.assertEqual(plan["selected"], ["changes/2-one.md", "changes/10-one.md", "changes/10-two.md", "changes/z-last.md"])
        self.assertEqual(self.build(), (plan, notes, changelog))
        positions = [notes.index((self.repo / p).read_bytes().strip(b"\n")) for p in plan["selected"]]
        self.assertEqual(positions, sorted(positions))
        self.assertEqual(notes.count(b"### Fixed (same issue, #10)"), 2)
        self.assertEqual(changelog.count(LEGACY.encode().strip()), 1)
        self.assertTrue(changelog.endswith(HISTORY.encode()))

    def test_public_scriv_adapter_preserves_actual_corpus_bytes_and_order(self):
        project = Path(__file__).resolve().parents[2]
        paths = sorted((p for p in (project / "changes").glob("*.md") if p.name != "README.md"), key=lambda p: r.order("changes/" + p.name))
        blocks = [p.read_text().strip("\n") for p in paths]
        self.assertGreater(len(blocks), 100)
        self.assertEqual(r.collect(blocks), "\n\n".join(blocks) + "\n")
        self.assertEqual(r.collect(["### Fixed\n\n<!-- retain -->\n\n- x  \n\n```md\n### code heading\n```"]),
                         "### Fixed\n\n<!-- retain -->\n\n- x  \n\n```md\n### code heading\n```\n")

    def test_all_committed_working_corpus_fragments_obey_structure(self):
        project = Path(__file__).resolve().parents[2]
        for path in (project / "changes").glob("*.md"):
            if path.name != "README.md":
                r.validate_fragment(path.read_bytes(), str(path))

    def test_template_exclusion_is_explicit_not_a_substring_that_loses_real_entries(self):
        self.write("161-template-support.md", "### Added\n\n- A real feature.\n")
        self.write("TEMPLATE.md", "Placeholder, not news.\n")
        self.write("news.template.md", "Placeholder, not news.\n")
        (self.repo / "changes/templates").mkdir()
        (self.repo / "changes/templates/example.md").write_text("Placeholder.\n")
        self.source = self.save()
        plan, _, _ = self.build()
        self.assertIn("changes/161-template-support.md", plan["selected"])
        self.assertFalse(any(path.endswith("TEMPLATE.md") or path.endswith(".template.md") or
                             "/templates/" in path for path in plan["selected"]))

    def test_preview_reads_immutable_git_not_dirty_or_untracked_files(self):
        before = self.build()
        self.write("2-one.md", "malformed working copy\n")
        self.write("new.md", "not selected\n")
        self.assertEqual(self.build(), before)

    def test_canonical_beta_zero_is_a_valid_proposal_but_leading_zero_is_refused(self):
        plan, notes, _ = self.build(release_version="0.2.0-beta.0")
        self.assertEqual(plan["channel"], "beta")
        self.assertTrue(notes.startswith(b"## [0.2.0-beta.0]"))
        with self.assertRaises(r.ReleaseError):
            self.build(release_version="0.2.0-beta.01")

    def test_beta_preview_retains_every_input_and_cannot_apply(self):
        originals = {p: p.read_bytes() for p in self.repo.rglob("*.md")}
        plan = self.prepare("0.2.0-beta.1")
        with self.assertRaisesRegex(r.ReleaseError, "beta"):
            r.apply(self.repo, plan)
        self.assertEqual(originals, {p: p.read_bytes() for p in originals})

    def test_explicit_selection_retains_deferred_and_newly_arrived_inputs(self):
        plan = self.prepare(selected=["changes/2-one.md"])
        self.write("3-new.md", "### Added\n\n- Concurrent arrival.\n")
        self.save()
        r.apply(self.repo, plan)
        self.assertFalse((self.repo / "changes/2-one.md").exists())
        for p in ("10-one.md", "10-two.md", "z-last.md", "3-new.md", "README.md"):
            self.assertTrue((self.repo / "changes" / p).exists())

    def test_apply_is_idempotent_and_receipt_is_only_pending(self):
        plan = self.prepare()
        r.apply(self.repo, plan)
        before = (self.repo / "CHANGELOG.md").read_bytes()
        self.assertIn("already prepared", r.apply(self.repo, plan))
        self.assertEqual(before, (self.repo / "CHANGELOG.md").read_bytes())
        receipt = r.load_json(self.repo / self.receipt())
        self.assertEqual(receipt["state"], "pending-publication")
        r.verify_applied(self.repo, receipt)

    def test_changed_missing_selected_and_changed_reviewed_output_fail_before_mutation(self):
        for change in ("modify", "delete", "notes", "candidate"):
            with self.subTest(change=change):
                plan = self.prepare("0.2.1" if change == "modify" else "0.2.2" if change == "delete" else "0.2.3" if change == "notes" else "0.2.4")
                selected = self.repo / "changes/2-one.md"
                original = selected.read_bytes()
                if change == "modify":
                    selected.write_text("changed")
                elif change == "delete":
                    selected.unlink()
                else:
                    (plan.parent / ("notes.md" if change == "notes" else "CHANGELOG.md")).write_text("changed")
                with self.assertRaises(r.ReleaseError):
                    r.apply(self.repo, plan)
                self.assertEqual((self.repo / "CHANGELOG.md").read_text(), CHANGELOG)
                selected.write_bytes(original)

    def test_malformed_fragments_report_file_and_line(self):
        cases = ["plain paragraph", "### Unknown\n- text", "### Fixed\nno bullet", "### Fixed\n- x\n### Added", "### Fixed\n- x\n```", "### Fixed\r\n- x\r\n"]
        for body in cases:
            with self.subTest(body=body):
                with self.assertRaisesRegex(r.ReleaseError, "changes/bad.md:"):
                    r.validate_fragment(body.encode(), "changes/bad.md")

    def test_pending_stable_blocks_second_preparation_but_beta_recovers_old_and_new(self):
        first = self.prepare()
        r.apply(self.repo, first)
        self.write("11-later.md", "### Fixed\n\n- Later.\n")
        self.source = self.save()
        with self.assertRaisesRegex(r.ReleaseError, "pending"):
            self.build(release_version="0.2.1")
        plan, notes, _ = self.build(release_version="0.2.1-beta.1")
        self.assertEqual(len(plan["inputs"]), 5)
        self.assertEqual(notes.count(b"Original legacy text"), 1)
        self.assertIn(b"Distinct entry", notes)
        self.assertIn(b"Later.", notes)

    def test_stale_frozen_plan_cannot_bypass_another_pending_preparation(self):
        first, other = self.prepare(), self.prepare("0.2.1")
        r.apply(self.repo, first)
        with self.assertRaisesRegex(r.ReleaseError, "pending"):
            r.apply(self.repo, other)

    def test_restore_abandoned_preparation_retains_version_reservation(self):
        plan = self.prepare()
        r.apply(self.repo, plan)
        r.restore(self.repo, self.receipt(), self.base)
        self.assertEqual((self.repo / "CHANGELOG.md").read_text(), CHANGELOG)
        self.assertTrue((self.repo / "changes/2-one.md").is_file())
        self.source = self.save()
        with self.assertRaisesRegex(r.ReleaseError, "reserved"):
            self.build()
        self.build(release_version="0.2.1")

    def test_declared_published_baseline_preparation_cannot_be_abandoned(self):
        plan = self.prepare()
        r.apply(self.repo, plan)
        self.base = self.save()
        with self.assertRaisesRegex(r.ReleaseError, "published baseline"):
            r.restore(self.repo, self.receipt(), self.base)

    def test_restore_refuses_new_output_changes_or_reappeared_inputs(self):
        plan = self.prepare()
        r.apply(self.repo, plan)
        self.write("2-one.md", "new content")
        with self.assertRaisesRegex(r.ReleaseError, "reappeared"):
            r.restore(self.repo, self.receipt(), self.base)

    def test_receipt_metadata_tampering_is_not_a_publication_receipt(self):
        plan = self.prepare()
        r.apply(self.repo, plan)
        receipt = r.load_json(self.repo / self.receipt())
        receipt["inputs"] = []
        with self.assertRaisesRegex(r.ReleaseError, "frozen selection"):
            r.verify_applied(self.repo, receipt)

    def test_interrupted_multifile_transaction_rolls_forward_exactly(self):
        plan = self.prepare()
        real_atomic = r.atomic
        def interrupted(path, payload):
            if path.name == "0.2.0.md":
                raise OSError("simulated interruption")
            return real_atomic(path, payload)
        with patch.object(r, "atomic", side_effect=interrupted), self.assertRaises(OSError):
            r.apply(self.repo, plan)
        self.assertTrue(r.git_path(self.repo, "scarab-changelog-transaction.json").exists())
        r.recover(self.repo)
        self.assertIn("already prepared", r.apply(self.repo, plan))

    def test_recovery_refuses_unknown_changes_before_any_additional_write(self):
        operations = [r.operation(self.repo, "CHANGELOG.md", b"new"), r.operation(self.repo, "changes/2-one.md", None)]
        journal = {"schemaVersion": 1, "sourceHead": self.git("rev-parse", "HEAD"), "operations": operations}
        path = r.git_path(self.repo, "scarab-changelog-transaction.json")
        r.atomic(path, r.json_bytes(journal))
        self.write("2-one.md", "concurrent edit")
        with self.assertRaisesRegex(r.ReleaseError, "changed"):
            r.recover(self.repo)
        self.assertEqual((self.repo / "CHANGELOG.md").read_text(), CHANGELOG)

    def test_concurrent_preparation_lock_is_exclusive(self):
        with r.git_path(self.repo, "scarab-changelog.lock").open("a") as lock:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            with self.assertRaisesRegex(r.ReleaseError, "another"):
                r.apply(self.repo, self.prepare())

    def test_symlink_fragment_and_output_refused(self):
        target = self.repo / "changes/2-one.md"
        target.unlink()
        target.symlink_to(self.repo / "CHANGELOG.md")
        self.source = self.save()
        with self.assertRaisesRegex(r.ReleaseError, "regular tracked"):
            self.build()

    def test_verified_baseline_advance_allows_next_release_and_excludes_consumed_identity(self):
        plan = self.prepare()
        r.apply(self.repo, plan)
        published = self.save()
        old_input = r.blob(self.repo, self.source, "changes/2-one.md")
        self.write("2-one.md", old_input.decode())
        self.write("20-next.md", "### Added\n\n- Next release.\n")
        self.source = self.save()
        self.base = published
        next_plan, notes, changelog = self.build(release_version="0.2.1")
        self.assertEqual(next_plan["selected"], ["changes/20-next.md"])
        self.assertNotIn(b"Distinct entry", notes)
        self.assertNotIn(b"Original legacy text", notes)
        self.assertEqual(changelog.count(b"## [0.2.0]"), 1)
        second = self.prepare("0.2.1")
        r.apply(self.repo, second)
        self.assertTrue((self.repo / "changes/2-one.md").exists())
        self.assertEqual((self.repo / "CHANGELOG.md").read_bytes().count(b"Original legacy text"), 1)

    def test_baseline_membership_requires_identical_receipt_bytes(self):
        plan = self.prepare()
        r.apply(self.repo, plan)
        self.base = self.save()
        receipt_path = self.repo / self.receipt()
        data = r.load_json(receipt_path)
        data["notesSha256"] = "f" * 64
        receipt_path.write_bytes(r.json_bytes(data))
        self.source = self.save()
        with self.assertRaisesRegex(r.ReleaseError, "frozen selection|receipt changed"):
            self.build(release_version="0.2.1")

    def test_new_pending_attempt_after_published_baseline_still_blocks(self):
        first = self.prepare()
        r.apply(self.repo, first)
        self.base = self.save()
        self.write("20-next.md", "### Fixed\n\n- Next release.\n")
        self.source = self.save()
        second = self.prepare("0.2.1")
        r.apply(self.repo, second)
        self.source = self.save()
        with self.assertRaisesRegex(r.ReleaseError, "pending"):
            self.build(release_version="0.2.2")

    def test_recovery_rejects_other_paths_duplicate_paths_schema_and_moved_head(self):
        original = r.operation(self.repo, "CHANGELOG.md", b"changed")
        for operations in ([original, original], [dict(original, path="build.sbt")],
                           [dict(original, path=".git/config")], [dict(original, extra=True)]):
            journal = {"schemaVersion": 1, "sourceHead": self.source, "operations": operations}
            with self.assertRaises(r.ReleaseError):
                r.finish(self.repo, journal)
            self.assertEqual((self.repo / "CHANGELOG.md").read_text(), CHANGELOG)
        self.write("99.md", "### Fixed\n\n- Head moves.\n")
        self.save()
        with self.assertRaisesRegex(r.ReleaseError, "HEAD changed"):
            r.finish(self.repo, {"schemaVersion": 1, "sourceHead": self.source, "operations": [original]})

    def test_multiple_releases_validate_each_immutable_receipt_once_without_recursive_replay(self):
        count = 6
        for number in range(count):
            version = f"0.2.{number}"
            if number:
                self.write(f"50-release-{number}.md", f"### Fixed\n\n- Release {number}.\n")
                self.source = self.save()
            plan = self.prepare(version)
            r.apply(self.repo, plan)
            self.base = self.save()
        context = r.History(self.repo)
        context.prime(self.base, self.base)
        self.assertEqual(context.validations, count)
        for _, receipt in context.records(self.base):
            r.validate_receipt(self.repo, receipt, context)
        self.assertEqual(context.validations, count, "memoized immutable receipts must not replay history")
        self.assertTrue(all(source in context.done for source in context.nodes))

    def test_nested_body_headings_and_fenced_headings_are_preserved(self):
        body = b"### Fixed\n\n- Detail.\n\n#### Known gaps\n\n- Still open.\n\n```md\n# example\n```\n"
        r.validate_fragment(body, "changes/nested.md")
        self.assertEqual(r.collect([body.decode()]), body.decode())

    def test_invalid_versions_dates_sources_and_duplicate_selection(self):
        for version in ("v0.2.0", "0.02.0", "0.2.0-beta.01", "0.2.0+build", "../escape"):
            with self.assertRaises(r.ReleaseError):
                self.build(release_version=version)
        with self.assertRaises(r.ReleaseError):
            r.build(self.repo, "HEAD", self.base, "0.2.0", "2026-10-21")
        with self.assertRaises(ValueError):
            r.build(self.repo, self.source, self.base, "0.2.0", "2026-02-30")
        with self.assertRaisesRegex(r.ReleaseError, "duplicate"):
            self.build(selected=["changes/2-one.md"] * 2)


if __name__ == "__main__":
    unittest.main()
