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


class Fake:
    """A fake `gh` and `git`: GitHub knows `commits`; a clone is an empty directory with `.git`; `diff --cached`
    finds changes unless `staged` is False. Every command is recorded, and the clone's files are listed at push."""

    def __init__(self, commits=(COMMIT,), staged=True):
        self.commits, self.staged, self.calls, self.pushed = set(commits), staged, [], None

    def __call__(self, argv):
        argv = list(argv)
        self.calls.append(argv)
        if argv[:2] == ["gh", "api"]:
            sha = argv[2].rsplit("/", 1)[1]
            known = sha in self.commits
            return subprocess.CompletedProcess(argv, 0 if known else 1, sha + "\n" if known else "", "")
        if argv[:2] == ["git", "clone"]:
            Path(argv[-1], ".git").mkdir(parents=True)
            return subprocess.CompletedProcess(argv, 0, "", "")
        if argv[:2] == ["git", "-C"]:
            verb = next(word for word in argv[3:] if not word.startswith("-") and "=" not in word)
            if verb == "diff":
                return subprocess.CompletedProcess(argv, 1 if self.staged else 0, "", "")
            if verb == "push":
                root = Path(argv[2])
                self.pushed = {p.relative_to(root).as_posix(): p.read_bytes()
                               for p in root.rglob("*") if p.is_file() and ".git" not in p.parts}
            return subprocess.CompletedProcess(argv, 0, "", "")
        raise AssertionError(f"unexpected command {argv}")

    def verbs(self):
        return [call[1] if call[0] == "gh" or call[1] == "clone"
                else next(w for w in call[3:] if not w.startswith("-") and "=" not in w) for call in self.calls]


def clone(directory):
    Path(directory, ".git").mkdir(parents=True)
    return Path(directory)


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
            reports, repo = Path(tmp, "out"), clone(Path(tmp, "repo"))
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
            self.assertFalse((repo / "README.md").exists())


class ApplyTest(unittest.TestCase):
    def test_apply_lays_out_commits_and_pushes_a_clone(self):
        fake = Fake()
        with tempfile.TemporaryDirectory() as tmp:
            reports, repo = Path(tmp, "out"), clone(Path(tmp, "repo"))
            reports.mkdir()
            body = write_report(reports).read_bytes()
            with mock.patch("sys.stdout", new=io.StringIO()):
                code = publish.main([str(reports), "--apply", "--checkout", str(repo),
                                     "--identity", "Bot <bot@example.org>"], run=fake)
            self.assertEqual(code, 0)
            self.assertEqual(fake.verbs(), ["api", "add", "diff", "commit", "push"])
            self.assertEqual((repo / "reports" / "ledger-rules" / LEDGER).read_bytes(), body)
            self.assertEqual((repo / "latest" / "ledger-rules.json.gz").read_bytes(), body)
            self.assertTrue((repo / "latest" / "ledger-rules.provenance.json").is_file())
            readme = (repo / "README.md").read_text()
            self.assertIn("[`ledger-rules`](latest/ledger-rules.json.gz) | 2026-11-14 |", readme)
            self.assertIn(f"https://github.com/KD5UJC-XYZ/scarab/commit/{COMMIT}", readme)
        commit = next(call for call in fake.calls if "commit" in call)
        self.assertIn("user.name=Bot", commit)
        self.assertIn("user.email=bot@example.org", commit)
        self.assertIn(f"Scarab commit {COMMIT}", commit[commit.index("-m") + 1])
        self.assertEqual(fake.calls[-1][-3:], ["--quiet", "origin", "HEAD:refs/heads/main"])

    def test_apply_without_a_checkout_clones_shallowly(self):
        fake = Fake()
        with tempfile.TemporaryDirectory() as tmp:
            write_report(tmp)
            with mock.patch("sys.stdout", new=io.StringIO()):
                self.assertEqual(publish.main([tmp, "--apply"], run=fake), 0)
        self.assertEqual(fake.verbs(), ["api", "clone", "add", "diff", "commit", "push"])
        self.assertIn("--depth", fake.calls[1])
        self.assertEqual(set(fake.pushed), {f"reports/ledger-rules/{LEDGER}",
                                            f"reports/ledger-rules/{LEDGER.replace('.json.gz', '.provenance.json')}",
                                            "latest/ledger-rules.json.gz", "latest/ledger-rules.provenance.json",
                                            "README.md"})

    def test_a_dated_report_is_not_overwritten_without_replace(self):
        with tempfile.TemporaryDirectory() as tmp:
            reports, repo = Path(tmp, "out"), clone(Path(tmp, "repo"))
            reports.mkdir()
            body = write_report(reports).read_bytes()
            archived = repo / "reports" / "ledger-rules" / LEDGER
            archived.parent.mkdir(parents=True)
            archived.write_bytes(b"earlier")
            fake = Fake()
            with mock.patch("sys.stdout", new=io.StringIO()), mock.patch("sys.stderr", new=io.StringIO()):
                self.assertEqual(publish.main([str(reports), "--apply", "--checkout", str(repo)], run=fake), 1)
            self.assertEqual(fake.verbs(), ["api"], "refused before anything is written or committed")
            self.assertEqual(archived.read_bytes(), b"earlier")
            fake = Fake()
            with mock.patch("sys.stdout", new=io.StringIO()):
                self.assertEqual(publish.main([str(reports), "--apply", "--checkout", str(repo), "--replace"],
                                              run=fake), 0)
            self.assertEqual(archived.read_bytes(), body)
            self.assertIn("push", fake.verbs())

    def test_latest_keeps_a_newer_report(self):
        with tempfile.TemporaryDirectory() as tmp:
            reports, repo = Path(tmp, "out"), clone(Path(tmp, "repo"))
            reports.mkdir()
            write_report(reports)
            newer = "scarab-20261201-ledger-rules.json.gz"
            (repo / "latest").mkdir()
            (repo / "latest" / "ledger-rules.json.gz").write_bytes(b"newer")
            (repo / "latest" / "ledger-rules.provenance.json").write_text(
                json.dumps({"report": newer, "sha256": "ab" * 32, "scarab": {"commit": "d" * 40}}))
            with mock.patch("sys.stdout", new=io.StringIO()):
                self.assertEqual(publish.main([str(reports), "--apply", "--checkout", str(repo)], run=Fake()), 0)
            self.assertTrue((repo / "reports" / "ledger-rules" / LEDGER).is_file(), "the older one is archived")
            self.assertEqual((repo / "latest" / "ledger-rules.json.gz").read_bytes(), b"newer")
            self.assertIn("| 2026-12-01 |", (repo / "README.md").read_text())

    def test_an_unpushed_commit_is_refused_before_any_git_command(self):
        fake = Fake(commits=())
        with tempfile.TemporaryDirectory() as tmp, mock.patch("sys.stdout", new=io.StringIO()), \
                mock.patch("sys.stderr", new=io.StringIO()):
            write_report(tmp)
            self.assertEqual(publish.main([tmp, "--apply"], run=fake), 1)
        self.assertEqual(fake.verbs(), ["api"])

    def test_nothing_new_is_not_committed(self):
        fake = Fake(staged=False)
        with tempfile.TemporaryDirectory() as tmp:
            reports, repo = Path(tmp, "out"), clone(Path(tmp, "repo"))
            reports.mkdir()
            write_report(reports)
            with mock.patch("sys.stdout", new=io.StringIO()):
                self.assertEqual(publish.main([str(reports), "--apply", "--checkout", str(repo)], run=fake), 0)
        self.assertEqual(fake.verbs(), ["api", "add", "diff"])

    def test_a_bad_identity_or_checkout_is_refused(self):
        with tempfile.TemporaryDirectory() as tmp, mock.patch("sys.stdout", new=io.StringIO()), \
                mock.patch("sys.stderr", new=io.StringIO()):
            write_report(tmp)
            self.assertEqual(publish.main([tmp, "--apply", "--identity", "no email"], run=never), 1)
            self.assertEqual(publish.main([tmp, "--apply", "--checkout", tmp], run=never), 1, "not a clone")


if __name__ == "__main__":
    unittest.main()
