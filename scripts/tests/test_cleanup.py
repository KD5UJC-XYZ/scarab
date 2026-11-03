# SPDX-License-Identifier: Apache-2.0
import json
import sys
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import cleanup
from cleanup import (IN_MAIN, PULL_REQUEST, UNMERGED, Container, Facts, RunDir, Scope, Worktree, build_output,
                     container_labels, container_verdict, finished_at, holds, run_dir_verdict, worktree_verdict)

TREE = Worktree(path="/r/.claude/worktrees/42-x", head="abc", branch="claude/42-x", locked=False, dirty=False,
                ignored=("target/", "project/project/", "node/target/", ".idea/"), activity=10 * 86400)
IDLE = Facts(used=frozenset(), mounted=(), mount_points=("/", "/proc"), containers=frozenset())
DAY = 86400
NOBODY = Scope(frozenset(), None)


def merged(head, branch):
    return PULL_REQUEST


def verdict(tree, facts=None, state=merged, scope=None):
    return worktree_verdict(tree, facts or IDLE, state, scope or NOBODY, 30 * DAY)


def with_tree(**changes):
    return Worktree(**{**TREE.__dict__, **changes})


def with_facts(**changes):
    return Facts(**{**IDLE.__dict__, **changes})


class WorktreeTest(unittest.TestCase):
    def test_a_clean_unused_worktree_whose_pull_request_was_merged_is_removed(self):
        self.assertEqual(verdict(TREE), (True, "its head was merged into main by a pull request"))

    def test_a_head_merely_in_main_needs_its_owner_or_a_stale_sweep(self):
        in_main = lambda head, branch: IN_MAIN
        self.assertEqual(verdict(TREE, state=in_main), (False, "head is in origin/main without a merged pull request; "
                                                              "not removed without --owner 42-x or --stale-days"))
        self.assertTrue(verdict(TREE, state=in_main, scope=Scope(frozenset({"42-x"}), None))[0])
        self.assertTrue(verdict(TREE, state=in_main, scope=Scope(frozenset({"claude/42-x"}), None))[0])
        self.assertEqual(verdict(TREE, state=in_main, scope=Scope(frozenset(), 14)),
                         (True, "head is in origin/main, no git activity for 20 days"))
        self.assertFalse(verdict(TREE, state=in_main, scope=Scope(frozenset(), 21))[0])

    def test_each_doubt_keeps_it_and_says_why(self):
        cases = [
            (with_tree(locked=True), IDLE, merged, "locked"),
            (with_tree(dirty=True), IDLE, merged, "uncommitted or untracked files"),
            (TREE, with_facts(used=frozenset({TREE.path + "/node"})), merged, "a process works or holds a file in it"),
            (TREE, with_facts(mounted=(TREE.path,)), merged, "mounted by a container"),
            (TREE, with_facts(mount_points=("/", TREE.path + "/data")), merged, "something is mounted inside it"),
            (with_tree(ignored=("target/", ".workspace/")), IDLE, merged,
             "ignored files that are not build output: .workspace/"),
            (TREE, IDLE, lambda head, branch: None, "merge state unknown"),
            (TREE, IDLE, lambda head, branch: UNMERGED, "head is neither in origin/main nor the head of a pull request merged into main"),
        ]
        for tree, facts, state, reason in cases:
            with self.subTest(reason=reason):
                self.assertEqual(verdict(tree, facts, state, Scope(frozenset({"42-x"}), 0)), (False, reason))

    def test_a_sibling_with_a_longer_name_is_not_inside(self):
        self.assertFalse(holds("/r/wt/42-x", ["/r/wt/42-xy/file"]))
        self.assertTrue(holds("/r/wt/42-x", ["/r/wt/42-x", "/elsewhere"]))

    def test_the_merge_state_is_asked_only_when_everything_else_allows_removal(self):
        asked = []
        verdict(with_tree(dirty=True), state=lambda head, branch: asked.append(head))
        self.assertEqual(asked, [])

    def test_build_output_is_recognised_by_any_path_part(self):
        self.assertTrue(build_output("ledger/target/"))
        self.assertTrue(build_output("project/project/"))
        self.assertTrue(build_output(".vscode/"))
        self.assertFalse(build_output(".cache/"))


class MergedPullRequestTest(unittest.TestCase):
    def answer(self, pulls):
        reply = mock.Mock(returncode=0, stdout=json.dumps(pulls), stderr="")
        with mock.patch.object(cleanup.subprocess, "run", return_value=reply):
            return cleanup.merged_pull_request("Org/repo", "abc", "claude/42-x")

    def pull(self, **changes):
        base = {"merged_at": "2026-11-01T00:00:00Z", "head": {"sha": "abc", "ref": "claude/42-x"},
                "base": {"ref": "main", "repo": {"full_name": "org/repo"}}}
        return {**base, **changes}

    def test_only_an_exact_head_merged_into_main_of_this_repository_counts(self):
        self.assertTrue(self.answer([self.pull()]))
        self.assertFalse(self.answer([self.pull(merged_at=None)]))
        self.assertFalse(self.answer([self.pull(head={"sha": "abd", "ref": "claude/42-x"})]))
        # A follow-up worktree made from the merged head, on a branch of its own, is not that pull request's.
        self.assertFalse(self.answer([self.pull(head={"sha": "abc", "ref": "claude/43-follow-up"})]))
        self.assertFalse(self.answer([self.pull(base={"ref": "integration", "repo": {"full_name": "org/repo"}})]))
        self.assertFalse(self.answer([self.pull(base={"ref": "main", "repo": {"full_name": "fork/repo"}})]))

    def test_an_unpushed_commit_is_unmerged_and_any_other_failure_is_unknown(self):
        missing = mock.Mock(returncode=1, stdout='{"message":"No commit found for SHA: abc"}', stderr="")
        broken = mock.Mock(returncode=1, stdout="", stderr="connection refused")
        with mock.patch.object(cleanup.subprocess, "run", return_value=missing):
            self.assertFalse(cleanup.merged_pull_request("org/repo", "abc", "b"))
        with mock.patch.object(cleanup.subprocess, "run", return_value=broken):
            self.assertIsNone(cleanup.merged_pull_request("org/repo", "abc", "b"))


class ContainerTest(unittest.TestCase):
    LABELS = dict(pair.split("=", 1) for pair in container_labels("devnet", "soak-1")[1::2])
    STOPPED = Container(id="1", name="soak-1-ref", running=False, labels=LABELS, mounts=(), finished=10 * DAY)

    def test_a_stopped_container_is_removed_only_for_its_owner_or_a_stale_sweep(self):
        now = 30 * DAY
        self.assertEqual(container_verdict(self.STOPPED, Scope(frozenset({"soak-1"}), None), now),
                         (True, "stopped, owner soak-1"))
        self.assertEqual(container_verdict(self.STOPPED, Scope(frozenset(), 14), now),
                         (True, "stopped 20 days ago (devnet, soak-1)"))
        self.assertFalse(container_verdict(self.STOPPED, Scope(frozenset(), 21), now)[0])
        self.assertFalse(container_verdict(self.STOPPED, Scope(frozenset({"other"}), None), now)[0])

    def test_running_and_unlabelled_containers_are_never_removed(self):
        everyone = Scope(frozenset({"soak-1"}), 0)
        self.assertEqual(container_verdict(Container(**{**self.STOPPED.__dict__, "running": True}), everyone, 0),
                         (False, "running (devnet, soak-1)"))
        self.assertEqual(container_verdict(Container(**{**self.STOPPED.__dict__, "labels": {}}), everyone, 0),
                         (False, "not labelled as ours"))

    def test_docker_finish_times_parse_and_the_zero_time_is_unknown(self):
        self.assertEqual(finished_at("1970-01-02T00:00:00.123456789Z"), DAY)
        self.assertEqual(finished_at("0001-01-01T00:00:00Z"), 0.0)
        self.assertEqual(finished_at(""), 0.0)


class RunDirTest(unittest.TestCase):
    OLD = RunDir("/tmp/scarab/old", False, 10 * DAY, False)

    def verdict(self, entry, facts=IDLE, cited=None, scope=NOBODY):
        return run_dir_verdict(entry, facts, cited or {}, scope, 30 * DAY)

    def test_age_alone_never_removes_a_directory(self):
        self.assertEqual(self.verdict(self.OLD),
                         (False, "written 20.0 days ago; not removed without --owner old or --stale-days"))

    def test_an_owner_or_a_stale_sweep_removes_it(self):
        self.assertEqual(self.verdict(self.OLD, scope=Scope(frozenset({"old"}), None)), (True, "owner old"))
        self.assertEqual(self.verdict(self.OLD, scope=Scope(frozenset(), 14)), (True, "untouched for 20 days"))
        self.assertFalse(self.verdict(self.OLD, scope=Scope(frozenset(), 21))[0])

    def test_citation_devnet_use_mounts_and_foreign_files_keep_it_even_for_its_owner(self):
        owner = Scope(frozenset({"old"}), 0)
        cases = [
            (self.OLD, IDLE, {"old": "docs/x.md"}, "cited by docs/x.md"),
            (self.OLD, with_facts(containers=frozenset({"old-ref"})), {}, "devnet container old-ref exists"),
            (self.OLD, with_facts(used=frozenset({"/tmp/scarab/old/node.log"})), {}, "a process works or holds a file in it"),
            (self.OLD, with_facts(mounted=("/tmp/scarab/old/db",)), {}, "mounted by a container"),
            (self.OLD, with_facts(mount_points=("/", "/tmp/scarab/old/original")), {}, "something is mounted inside it"),
            (RunDir("/tmp/scarab/old", False, 10 * DAY, True), IDLE, {}, "holds another user's files"),
        ]
        for entry, facts, cited, reason in cases:
            with self.subTest(reason=reason):
                self.assertEqual(self.verdict(entry, facts, cited, owner), (False, reason))

    def test_a_symlink_is_removed_as_a_link(self):
        link = RunDir("/tmp/scarab/moved", True, 10 * DAY, False)
        self.assertEqual(self.verdict(link, scope=Scope(frozenset(), 7)),
                         (True, "untouched for 20 days (a symlink; its target is kept)"))


class StatusConfigurationTest(unittest.TestCase):
    def test_ignored_files_are_listed_even_when_git_is_configured_to_hide_untracked_files(self):
        import subprocess
        import tempfile
        with tempfile.TemporaryDirectory() as scratch:
            repo, tree = Path(scratch) / "repo", Path(scratch) / "tree"
            run = lambda *args, cwd=repo: subprocess.run(["git", "-C", str(cwd), *args], check=True,
                                                         capture_output=True, text=True)
            repo.mkdir()
            run("init", "-q", "-b", "main")
            run("config", "user.email", "t@example.org")
            run("config", "user.name", "t")
            (repo / ".gitignore").write_text(".workspace/\n")
            run("add", ".gitignore")
            run("commit", "-q", "-m", "init")
            run("worktree", "add", "-q", str(tree))
            run("config", "status.showUntrackedFiles", "no")
            (tree / ".workspace").mkdir()
            (tree / ".workspace" / "evidence").write_text("kept")
            with mock.patch.object(cleanup, "ROOT", repo):
                trees = cleanup.worktrees()
        self.assertEqual([entry.ignored for entry in trees], [(".workspace/",)])


class ActivityTest(unittest.TestCase):
    MONTH = 30 * 86400

    def setUp(self):
        import subprocess
        import tempfile
        scratch = tempfile.TemporaryDirectory()
        self.addCleanup(scratch.cleanup)
        self.repo, self.tree = Path(scratch.name) / "repo", Path(scratch.name) / "tree"
        self.run_git = lambda *args, cwd=self.repo: subprocess.run(["git", "-C", str(cwd), *args], check=True,
                                                                  capture_output=True, text=True)
        self.repo.mkdir()
        self.run_git("init", "-q", "-b", "main")
        self.run_git("config", "user.email", "t@example.org")
        self.run_git("config", "user.name", "t")
        (self.repo / "file").write_text("one")
        self.run_git("add", "file")
        self.run_git("commit", "-q", "-m", "init")
        self.run_git("worktree", "add", "-q", str(self.tree))
        self.admin = Path(self.run_git("rev-parse", "--absolute-git-dir", cwd=self.tree).stdout.strip())

    def age(self, *stamps):
        import os
        import time
        then = time.time() - self.MONTH
        for stamp in stamps:
            os.utime(self.admin / stamp, (then, then))
        return then

    def listed(self):
        with mock.patch.object(cleanup, "ROOT", self.repo):
            trees = cleanup.worktrees()
        self.assertEqual([entry.dirty for entry in trees], [False])
        return trees[0]

    def test_listing_does_not_refresh_the_index_and_make_an_old_worktree_look_used(self):
        import os
        then = self.age("HEAD", "logs/HEAD", "index")
        # A file whose time no longer matches the index's record, though its content does, is what makes a plain
        # `git status` rewrite the index.
        os.utime(self.tree / "file", (then - 60, then - 60))
        self.assertAlmostEqual(self.listed().activity, then, delta=1)
        self.assertAlmostEqual(self.listed().activity, then, delta=1)

    def test_an_edit_staged_and_restored_counts_as_activity_though_it_leaves_the_worktree_clean(self):
        import time
        self.age("HEAD", "logs/HEAD", "index")
        (self.tree / "file").write_text("two")
        self.run_git("add", "file", cwd=self.tree)
        self.run_git("restore", "--staged", "file", cwd=self.tree)
        self.run_git("restore", "file", cwd=self.tree)
        self.assertGreater(self.listed().activity, time.time() - 3600)

    def test_an_edit_undone_in_the_working_tree_alone_counts_as_activity(self):
        import time
        self.age("HEAD", "logs/HEAD", "index")
        (self.tree / "file").write_text("two")
        self.run_git("restore", "--source=HEAD", "--worktree", "file", cwd=self.tree)
        self.assertGreater(self.listed().activity, time.time() - 3600)

    def test_without_a_head_reflog_the_activity_is_unknown_and_a_stale_sweep_keeps_it(self):
        import time
        self.run_git("config", "core.logAllRefUpdates", "false")
        (self.admin / "logs" / "HEAD").unlink()
        self.age("HEAD", "index")
        # A reset on the attached branch moves the branch, not HEAD, and leaves no reflog to show it.
        self.run_git("reset", "-q", "--hard", "HEAD", cwd=self.tree)
        self.age("index")
        tree = self.listed()
        self.assertIsNone(tree.activity)
        verdict = worktree_verdict(tree, IDLE, lambda head, branch: IN_MAIN, Scope(frozenset(), 14), time.time())
        self.assertEqual(verdict, (False, "head is in origin/main and it has no HEAD reflog, so its last activity is "
                                          "unknown"))


class ApplyTest(unittest.TestCase):
    def test_each_removal_is_rechecked_alone_just_before_it_runs(self):
        first = Worktree(**{**TREE.__dict__, "path": "/r/wt/a"})
        second = Worktree(**{**TREE.__dict__, "path": "/r/wt/b"})
        rechecks, removed = [], []

        def assess(args, merged, cited, only=None):
            if only is None:
                return [(True, "worktree", first, "ok"), (True, "worktree", second, "ok")]
            rechecks.append(dict(only))
            # The first removal makes the second busy.
            busy = only["worktree"] == ["/r/wt/b"] and removed
            target = first if only["worktree"] == ["/r/wt/a"] else second
            return [(not busy, "worktree", target, "a process works or holds a file in it" if busy else "ok")]

        with mock.patch.object(cleanup, "assess", assess), \
             mock.patch.object(cleanup, "citations", lambda data: {}), \
             mock.patch.object(cleanup, "merge_state", lambda offline: merged), \
             mock.patch.object(cleanup, "remove_worktree", lambda tree: removed.append(tree.path) or "removed"), \
             mock.patch("builtins.print"):
            self.assertEqual(cleanup.main(["--apply"]), 0)
        self.assertEqual(rechecks, [{"worktree": ["/r/wt/a"]}, {"worktree": ["/r/wt/b"]}])
        self.assertEqual(removed, ["/r/wt/a"])


if __name__ == "__main__":
    unittest.main()
