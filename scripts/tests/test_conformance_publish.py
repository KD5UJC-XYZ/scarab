# SPDX-License-Identifier: Apache-2.0
"""`scripts/conformance/publish.py`: which files may be published, that a dry run runs nothing, and what `--apply`
writes into the reports repository and which commands it runs, against a fake `git` and `gh`: nothing is cloned or
pushed."""
import hashlib
import io
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from conformance import publish  # noqa: E402

COMMIT = "c" * 40
LEDGER = "scarab-20261114-ledger-rules.json.gz"


def write_report(directory, name=LEDGER, body=b"\x1f\x8b report", **changes):
    path = Path(directory, name)
    path.write_bytes(body)
    record = {"report": name, "sha256": hashlib.sha256(body).hexdigest(),
              "validation": {"valid": True}, "scarab": {"commit": COMMIT, "dirty": False}}
    record.update(changes)
    Path(directory, name.removesuffix(".json.gz") + ".provenance.json").write_text(json.dumps(record))
    return path


def never(argv):
    raise AssertionError(f"ran {argv}")


def tree(root):
    """The files of a clone's working tree, by path, without `.git`."""
    return {p.relative_to(root).as_posix(): p.read_bytes()
            for p in Path(root).rglob("*") if p.is_file() and ".git" not in p.relative_to(root).parts}


class Fake:
    """A fake `gh`, and a model of `git` over one clone: the remote's `main`, the clone's commits (snapshots of its
    files), its index and its working tree on disk. GitHub knows `commits`; the first `push_failures` pushes fail.
    Every command is recorded; nothing leaves the test's directory."""

    def __init__(self, commits=(COMMIT,), push_failures=0, remote=None):
        self.commits, self.push_failures, self.calls = set(commits), push_failures, []
        self.snapshots = {"c0": dict(remote or {"README.md": b"# first commit\n"})}
        self.remote = self.head = "c0"
        self.index = set()

    def clone_into(self, directory):
        root = Path(directory)
        (root / ".git").mkdir(parents=True)
        for path, body in self.snapshots[self.remote].items():
            (root / path).parent.mkdir(parents=True, exist_ok=True)
            (root / path).write_bytes(body)
        self.head = self.remote
        return root

    def done(self, argv, code=0, out=""):
        return subprocess.CompletedProcess(argv, code, out, "" if code == 0 else "fake git refused")

    def __call__(self, argv):
        argv = list(argv)
        self.calls.append(argv)
        if argv[:2] == ["gh", "api"]:
            sha = argv[2].rsplit("/", 1)[1]
            return self.done(argv, 0 if sha in self.commits else 1, sha + "\n" if sha in self.commits else "")
        if argv[:2] == ["git", "clone"]:
            self.clone_into(argv[-1])
            return self.done(argv)
        if argv[:2] != ["git", "-C"]:
            raise AssertionError(f"unexpected command {argv}")
        root, words = Path(argv[2]), argv[3:]
        while words and words[0] == "-c":
            words = words[2:]
        verb, rest = words[0], words[1:]
        if verb == "ls-remote":
            return self.done(argv, 0, f"{self.remote}\trefs/heads/main\n")
        if verb == "rev-parse":
            return self.done(argv, 0, self.head + "\n")
        if verb == "status":
            now, committed = tree(root), self.snapshots[self.head]
            changed = sorted(p for p in set(now) | set(committed) if now.get(p) != committed.get(p))
            return self.done(argv, 0, "".join(f" M {p}\n" for p in changed + sorted(self.index)))
        if verb == "add":
            self.index |= set(rest[rest.index("--") + 1:])
            return self.done(argv)
        if verb == "diff":
            now, committed = tree(root), self.snapshots[self.head]
            return self.done(argv, 1 if any(now.get(p) != committed.get(p) for p in self.index) else 0)
        if verb == "commit":
            now, snapshot = tree(root), dict(self.snapshots[self.head])
            snapshot.update({p: now[p] for p in self.index if p in now})
            self.head = f"c{len(self.snapshots)}"
            self.snapshots[self.head], self.index = snapshot, set()
            return self.done(argv)
        if verb == "push":
            if self.push_failures:
                self.push_failures -= 1
                return self.done(argv, 1)
            self.remote = self.head
            return self.done(argv)
        if verb == "reset":
            self.head, self.index = rest[-1], set()
            snapshot = self.snapshots[self.head]
            for path, body in snapshot.items():
                (root / path).parent.mkdir(parents=True, exist_ok=True)
                (root / path).write_bytes(body)
            return self.done(argv)
        if verb == "clean":
            owned = rest[rest.index("--") + 1:]
            for path in tree(root):
                if path not in self.snapshots[self.head] and path.split("/")[0] in owned:
                    (root / path).unlink()
            return self.done(argv)
        raise AssertionError(f"unexpected git {verb}")

    def published(self):
        """The files of the remote's `main`."""
        return self.snapshots[self.remote]

    def verbs(self):
        verbs = []
        for call in self.calls:
            if call[0] == "gh" or call[1] == "clone":
                verbs.append(call[1])
                continue
            words = call[3:]
            while words[0] == "-c":
                words = words[2:]
            verbs.append(words[0])
        return verbs


class CollectTest(unittest.TestCase):
    def test_a_checked_report_is_collected_with_its_provenance(self):
        with tempfile.TemporaryDirectory() as tmp:
            write_report(tmp)
            Path(tmp, "summary.json").write_text("{}")
            reports = publish.collect([Path(tmp)])
            self.assertEqual([r.path.name for r in reports], [LEDGER])
            self.assertEqual(reports[0].dated, f"reports/ledger-rules/{LEDGER}")
            self.assertEqual(reports[0].latest, "latest/ledger-rules.json.gz")

    def test_a_cbor_report_is_placed_under_its_scenario_and_era(self):
        with tempfile.TemporaryDirectory() as tmp:
            write_report(tmp, "scarab-20261114-cbor-decoding-dijkstra.json.gz")
            report = publish.collect([Path(tmp)])[0]
            self.assertEqual(report.dated, "reports/cbor-decoding/scarab-20261114-cbor-decoding-dijkstra.json.gz")
            self.assertEqual(report.latest, "latest/cbor-decoding-dijkstra.json.gz")

    def test_unfit_files_are_refused(self):
        cases = {
            "dirty": {"scarab": {"commit": COMMIT, "dirty": True}},
            "unvalidated": {"validation": {"valid": False}},
            "another sha256": {"sha256": "0" * 64},
            "no commit": {"scarab": {"dirty": False}},
        }
        for case, changes in cases.items():
            with tempfile.TemporaryDirectory() as tmp, self.assertRaises(publish.PublishError, msg=case):
                write_report(tmp, **changes)
                publish.collect([Path(tmp)])
        with tempfile.TemporaryDirectory() as tmp, self.assertRaises(publish.PublishError):
            Path(tmp, "scarab-2026111-ledger-rules.json.gz").write_bytes(b"")
            publish.collect([Path(tmp, "scarab-2026111-ledger-rules.json.gz")])
        with tempfile.TemporaryDirectory() as tmp, self.assertRaises(publish.PublishError):
            Path(tmp, LEDGER).write_bytes(b"")
            publish.collect([Path(tmp)])
        with tempfile.TemporaryDirectory() as tmp, self.assertRaises(publish.PublishError):
            write_report(tmp)
            write_report(tmp, "scarab-20261115-ledger-rules.json.gz")
            publish.collect([Path(tmp)])


class DryRunTest(unittest.TestCase):
    def test_dry_run_is_the_default_and_runs_nothing(self):
        with tempfile.TemporaryDirectory() as tmp, mock.patch("sys.stdout", new=io.StringIO()) as out:
            write_report(tmp)
            self.assertEqual(publish.main([tmp], run=never), 0)
            self.assertEqual(sorted(p.name for p in Path(tmp).iterdir()),
                             [LEDGER, LEDGER.replace(".json.gz", ".provenance.json")], "nothing written")
        text = out.getvalue()
        self.assertIn(f"gh api repos/KD5UJC-XYZ/scarab/commits/{COMMIT}", text)
        self.assertIn("git clone --quiet --depth 1 --no-tags --branch main "
                      "https://github.com/KD5UJC-XYZ/scarab-conformance.git", text)
        self.assertIn(f"-> reports/ledger-rules/{LEDGER}", text)
        self.assertIn("-> latest/ledger-rules.json.gz", text)
        self.assertIn("push --quiet origin HEAD:refs/heads/main", text)

    def test_explicit_dry_run_runs_nothing_and_excludes_apply(self):
        with tempfile.TemporaryDirectory() as tmp, mock.patch("sys.stdout", new=io.StringIO()):
            write_report(tmp)
            self.assertEqual(publish.main([tmp, "--dry-run"], run=never), 0)
        with tempfile.TemporaryDirectory() as tmp, mock.patch("sys.stderr", new=io.StringIO()), \
                self.assertRaises(SystemExit) as refused:
            write_report(tmp)
            publish.main([tmp, "--dry-run", "--apply"], run=never)
        self.assertEqual(refused.exception.code, 2)

    def test_dry_run_on_a_clone_reads_it_and_writes_nothing(self):
        with tempfile.TemporaryDirectory() as tmp:
            reports, repo = Path(tmp, "out"), Fake().clone_into(Path(tmp, "repo"))
            reports.mkdir()
            write_report(reports)
            archived = repo / "reports" / "ledger-rules" / LEDGER
            archived.parent.mkdir(parents=True)
            archived.write_bytes(b"earlier")
            with mock.patch("sys.stdout", new=io.StringIO()), mock.patch("sys.stderr", new=io.StringIO()) as err:
                self.assertEqual(publish.main([str(reports), "--checkout", str(repo)], run=never), 1)
            self.assertIn("already published", err.getvalue())
            with mock.patch("sys.stdout", new=io.StringIO()) as out:
                self.assertEqual(publish.main([str(reports), "--checkout", str(repo), "--replace"], run=never), 0)
            self.assertIn("replaces the file there", out.getvalue())
            self.assertEqual(archived.read_bytes(), b"earlier", "a dry run writes nothing")
            self.assertEqual((repo / "README.md").read_bytes(), b"# first commit\n")
            self.assertFalse((repo / "latest").exists())


PUBLISH = ["api", "ls-remote", "rev-parse", "status", "add", "diff", "commit", "push"]
UPLC = "scarab-20261114-uplc-conformance.json.gz"


def provenance(name):
    return name.replace(".json.gz", ".provenance.json")


class ApplyTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.out = self.root / "out"
        self.out.mkdir()

    def tearDown(self):
        self.tmp.cleanup()

    def publish(self, fake, *args, reports=None):
        with mock.patch("sys.stdout", new=io.StringIO()) as out, mock.patch("sys.stderr", new=io.StringIO()) as err:
            code = publish.main([str(reports or self.out), "--apply", *args], run=fake)
        return code, out.getvalue() + err.getvalue()

    def test_apply_lays_out_commits_and_pushes_a_clone(self):
        fake = Fake()
        repo = fake.clone_into(self.root / "repo")
        body = write_report(self.out).read_bytes()
        code, _ = self.publish(fake, "--checkout", str(repo), "--identity", "Bot <bot@example.org>")
        self.assertEqual(code, 0)
        self.assertEqual(fake.verbs(), PUBLISH)
        published = fake.published()
        self.assertEqual(published[f"reports/ledger-rules/{LEDGER}"], body)
        self.assertEqual(published["latest/ledger-rules.json.gz"], body)
        self.assertIn("latest/ledger-rules.provenance.json", published)
        readme = published["README.md"].decode()
        self.assertIn("[`ledger-rules`](latest/ledger-rules.json.gz) | 2026-11-14 |", readme)
        self.assertIn(f"https://github.com/KD5UJC-XYZ/scarab/commit/{COMMIT}", readme)
        add = next(call for call in fake.calls if "add" in call)
        self.assertEqual(sorted(add[add.index("--") + 1:]),
                         sorted(["README.md", f"reports/ledger-rules/{LEDGER}", f"reports/ledger-rules/{provenance(LEDGER)}",
                                 "latest/ledger-rules.json.gz", "latest/ledger-rules.provenance.json"]),
                         "exactly the files written are staged")
        commit = next(call for call in fake.calls if "commit" in call)
        self.assertIn("user.name=Bot", commit)
        self.assertIn("user.email=bot@example.org", commit)
        self.assertIn(f"Scarab commit {COMMIT}", commit[commit.index("-m") + 1])
        self.assertEqual(fake.calls[-1][-3:], ["--quiet", "origin", "HEAD:refs/heads/main"])

    def test_apply_without_a_checkout_clones_shallowly(self):
        fake = Fake()
        write_report(self.out)
        self.assertEqual(self.publish(fake)[0], 0)
        self.assertEqual(fake.verbs(), ["api", "clone"] + PUBLISH[1:])
        self.assertIn("--depth", fake.calls[1])
        self.assertEqual(set(fake.published()), {f"reports/ledger-rules/{LEDGER}",
                                                 f"reports/ledger-rules/{provenance(LEDGER)}",
                                                 "latest/ledger-rules.json.gz", "latest/ledger-rules.provenance.json",
                                                 "README.md"})

    def test_a_failed_copy_is_undone_and_a_retry_publishes_only_its_own_files(self):
        fake = Fake()
        repo = fake.clone_into(self.root / "repo")
        write_report(self.out)
        copy = publish.shutil.copyfile
        calls = []

        def failing(source, target):
            calls.append(target)
            if len(calls) == 3:
                raise OSError("disk full")
            return copy(source, target)

        with mock.patch.object(publish.shutil, "copyfile", side_effect=failing):
            code, said = self.publish(fake, "--checkout", str(repo))
        self.assertEqual(code, 1)
        self.assertIn("nothing was published", said)
        self.assertEqual(tree(repo), fake.published(), "the clone is back at the remote's main")
        self.assertNotIn("push", fake.verbs())

        other = self.root / "other"
        other.mkdir()
        write_report(other, UPLC)
        self.assertEqual(self.publish(fake, "--checkout", str(repo), reports=other)[0], 0)
        self.assertFalse([p for p in fake.published() if "ledger-rules" in p], "no leftover of the failed copy")
        self.assertIn(f"reports/uplc-conformance/{UPLC}", fake.published())

    def test_a_failed_push_is_undone_and_a_retry_pushes(self):
        fake = Fake(push_failures=1)
        repo = fake.clone_into(self.root / "repo")
        write_report(self.out)
        code, said = self.publish(fake, "--checkout", str(repo))
        self.assertEqual(code, 1)
        self.assertIn("git push failed", said)
        self.assertEqual(fake.head, fake.remote, "the local commit is dropped: the clone is at the remote's main")
        self.assertNotIn("latest/ledger-rules.json.gz", fake.published())
        code, said = self.publish(fake, "--checkout", str(repo))
        self.assertEqual(code, 0, said)
        self.assertNotIn("already published", said)
        self.assertIn("latest/ledger-rules.json.gz", fake.published(), "the retry did push")

    def test_a_clone_with_changes_or_unpushed_commits_is_refused(self):
        fake = Fake()
        repo = fake.clone_into(self.root / "repo")
        write_report(self.out)
        (repo / "reports").mkdir()
        (repo / "reports" / "stray.json.gz").write_bytes(b"left over")
        code, said = self.publish(fake, "--checkout", str(repo))
        self.assertEqual(code, 1)
        self.assertIn("changed, staged or untracked", said)
        self.assertEqual(fake.verbs(), ["api", "ls-remote", "rev-parse", "status"], "refused before any write")
        (repo / "reports" / "stray.json.gz").unlink()
        fake.snapshots["c9"], fake.head = dict(fake.published()), "c9"
        code, said = self.publish(fake, "--checkout", str(repo))
        self.assertEqual(code, 1)
        self.assertIn("is not the remote's main", said)

    def test_a_dated_report_is_not_overwritten_without_replace(self):
        archived = {f"reports/ledger-rules/{LEDGER}": b"earlier", "README.md": b"# r\n"}
        fake = Fake(remote=archived)
        repo = fake.clone_into(self.root / "repo")
        body = write_report(self.out).read_bytes()
        code, said = self.publish(fake, "--checkout", str(repo))
        self.assertEqual(code, 1)
        self.assertIn("already published", said)
        self.assertNotIn("add", fake.verbs(), "refused before anything is written or staged")
        self.assertEqual((repo / "reports" / "ledger-rules" / LEDGER).read_bytes(), b"earlier")
        self.assertEqual(self.publish(fake, "--checkout", str(repo), "--replace")[0], 0)
        self.assertEqual(fake.published()[f"reports/ledger-rules/{LEDGER}"], body)

    def test_latest_keeps_a_newer_report(self):
        newer = "scarab-20261201-ledger-rules.json.gz"
        record = json.dumps({"report": newer, "sha256": "ab" * 32, "scarab": {"commit": "d" * 40}}).encode()
        fake = Fake(remote={"latest/ledger-rules.json.gz": b"newer", "latest/ledger-rules.provenance.json": record})
        repo = fake.clone_into(self.root / "repo")
        write_report(self.out)
        self.assertEqual(self.publish(fake, "--checkout", str(repo))[0], 0)
        self.assertIn(f"reports/ledger-rules/{LEDGER}", fake.published(), "the older one is archived")
        self.assertEqual(fake.published()["latest/ledger-rules.json.gz"], b"newer")
        self.assertIn("| 2026-12-01 |", fake.published()["README.md"].decode())

    def test_an_unpushed_scarab_commit_is_refused_before_any_git_command(self):
        fake = Fake(commits=())
        write_report(self.out)
        self.assertEqual(self.publish(fake)[0], 1)
        self.assertEqual(fake.verbs(), ["api"])

    def test_files_the_remote_already_holds_are_not_committed_again(self):
        fake = Fake()
        repo = fake.clone_into(self.root / "repo")
        write_report(self.out)
        self.assertEqual(self.publish(fake, "--checkout", str(repo))[0], 0)
        fake.calls.clear()
        code, said = self.publish(fake, "--checkout", str(repo), "--replace")
        self.assertEqual(code, 0)
        self.assertIn("already published", said)
        self.assertEqual(fake.verbs(), ["api", "ls-remote", "rev-parse", "status", "add", "diff"])

    def test_a_bad_identity_or_checkout_is_refused(self):
        write_report(self.out)
        self.assertEqual(self.publish(never, "--identity", "no email")[0], 1)
        self.assertEqual(self.publish(never, "--checkout", str(self.out))[0], 1, "not a clone")


class BytecodeTest(unittest.TestCase):
    def test_a_dry_run_writes_no_bytecode(self):
        scripts = Path(__file__).resolve().parents[1]
        with tempfile.TemporaryDirectory() as tmp:
            copy = Path(tmp, "scripts", "conformance")
            copy.mkdir(parents=True)
            for source in (scripts / "conformance").iterdir():
                if source.suffix in (".py", ".json"):
                    (copy / source.name).write_bytes(source.read_bytes())
            reports = Path(tmp, "out")
            reports.mkdir()
            write_report(reports)
            env = {key: value for key, value in __import__("os").environ.items()
                   if key != "PYTHONDONTWRITEBYTECODE"}
            done = subprocess.run([sys.executable, "-I", str(copy / "publish.py"), str(reports)],
                                  capture_output=True, text=True, env=env)
            self.assertEqual(done.returncode, 0, done.stderr)
            self.assertIn("dry run", done.stdout)
            self.assertEqual(list(Path(tmp).rglob("__pycache__")), [], "no bytecode written")


if __name__ == "__main__":
    unittest.main()
