# SPDX-License-Identifier: Apache-2.0
"""`scripts/conformance/publish.py`: which files may be published, that a dry run runs no `gh` command, and the
commands `--apply` runs against a stub of `gh`."""
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


def write_report(directory, name="scarab-20261114-ledger-rules.json.gz", **changes):
    path = Path(directory, name)
    path.write_bytes(b"\x1f\x8b report")
    record = {"report": name, "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
              "validation": {"valid": True}, "scarab": {"commit": COMMIT, "dirty": False}}
    record.update(changes)
    Path(directory, name.removesuffix(".json.gz") + ".provenance.json").write_text(json.dumps(record))
    return path


def never(argv):
    raise AssertionError(f"ran {argv}")


class Gh:
    """A stub of `gh`: the release as it stands (None when absent), and every command it was given."""

    def __init__(self, release=None, commits=(COMMIT,)):
        self.release, self.commits, self.calls = release, set(commits), []

    def __call__(self, argv):
        self.calls.append(list(argv))
        if argv[:3] == ["gh", "release", "view"]:
            if self.release is None:
                return subprocess.CompletedProcess(argv, 1, "", "release not found\n")
            return subprocess.CompletedProcess(argv, 0, json.dumps(self.release), "")
        if argv[:2] == ["gh", "api"]:
            sha = argv[2].rsplit("/", 1)[1]
            return subprocess.CompletedProcess(argv, 0 if sha in self.commits else 1,
                                               sha + "\n" if sha in self.commits else "", "")
        return subprocess.CompletedProcess(argv, 0, "", "")

    def verbs(self):
        return [" ".join(call[1:3]) for call in self.calls]


class CollectTest(unittest.TestCase):
    def test_a_checked_report_is_collected_with_its_provenance(self):
        with tempfile.TemporaryDirectory() as tmp:
            write_report(tmp)
            Path(tmp, "summary.json").write_text("{}")
            reports = publish.collect([Path(tmp)])
            self.assertEqual([r.path.name for r in reports], ["scarab-20261114-ledger-rules.json.gz"])
            self.assertEqual(reports[0].commit, COMMIT)

    def test_unfit_files_are_refused(self):
        cases = {
            "dirty": {"scarab": {"commit": COMMIT, "dirty": True}},
            "unvalidated": {"validation": {"valid": False}},
            "another sha256": {"sha256": "0" * 64},
            "no commit": {"scarab": {"dirty": False}},
        }
        for label, changes in cases.items():
            with tempfile.TemporaryDirectory() as tmp, self.assertRaises(publish.PublishError, msg=label):
                write_report(tmp, **changes)
                publish.collect([Path(tmp)])
        with tempfile.TemporaryDirectory() as tmp, self.assertRaises(publish.PublishError):
            Path(tmp, "scarab-2026111-ledger-rules.json.gz").write_bytes(b"")
            publish.collect([Path(tmp, "scarab-2026111-ledger-rules.json.gz")])
        with tempfile.TemporaryDirectory() as tmp, self.assertRaises(publish.PublishError):
            Path(tmp, "scarab-20261114-ledger-rules.json.gz").write_bytes(b"")
            publish.collect([Path(tmp)])
        with tempfile.TemporaryDirectory() as tmp, self.assertRaises(publish.PublishError):
            write_report(tmp)
            write_report(tmp, "scarab-20261115-ledger-rules.json.gz")
            publish.collect([Path(tmp)])


class PublishTest(unittest.TestCase):
    def test_dry_run_is_the_default_and_runs_nothing(self):
        with tempfile.TemporaryDirectory() as tmp, mock.patch("sys.stdout", new=io.StringIO()) as out:
            write_report(tmp)
            self.assertEqual(publish.main([tmp], run=never), 0)
        text = out.getvalue()
        self.assertIn("gh release create conformance-reports", text)
        self.assertIn("--prerelease --latest=false", text)
        self.assertIn("gh release upload conformance-reports", text)
        self.assertNotIn("--clobber", text)

    def test_explicit_dry_run_runs_nothing_and_excludes_apply(self):
        with tempfile.TemporaryDirectory() as tmp, mock.patch("sys.stdout", new=io.StringIO()):
            write_report(tmp)
            self.assertEqual(publish.main([tmp, "--dry-run"], run=never), 0)
        with tempfile.TemporaryDirectory() as tmp, mock.patch("sys.stderr", new=io.StringIO()), \
                self.assertRaises(SystemExit) as refused:
            write_report(tmp)
            publish.main([tmp, "--dry-run", "--apply"], run=never)
        self.assertEqual(refused.exception.code, 2)

    def test_apply_creates_an_absent_release_then_uploads(self):
        gh = Gh()
        with tempfile.TemporaryDirectory() as tmp, mock.patch("sys.stdout", new=io.StringIO()):
            write_report(tmp)
            self.assertEqual(publish.main([tmp, "--apply"], run=gh), 0)
        self.assertEqual(gh.verbs(), ["release view", "release create", f"api repos/KD5UJC-XYZ/scarab/commits/{COMMIT}",
                                      "release upload"])
        create = gh.calls[1]
        self.assertIn("--prerelease", create)
        self.assertIn("--latest=false", create)
        self.assertNotIn("--draft", create)
        self.assertEqual(create[create.index("--title") + 1], "conformance-reports")
        self.assertIn("not a Scarab release", create[create.index("--notes") + 1])
        self.assertTrue(gh.calls[-1][-1].endswith(".provenance.json"))

    def test_apply_refuses_a_release_that_is_not_a_published_prerelease(self):
        for release in ({"isDraft": True, "isPrerelease": True, "assets": []},
                        {"isDraft": False, "isPrerelease": False, "assets": []}):
            gh = Gh(release)
            with tempfile.TemporaryDirectory() as tmp, mock.patch("sys.stderr", new=io.StringIO()):
                write_report(tmp)
                self.assertEqual(publish.main([tmp, "--apply"], run=gh), 1)
            self.assertEqual(gh.verbs(), ["release view"], "nothing after the refusal")

    def test_a_file_already_released_needs_replace(self):
        release = {"isDraft": False, "isPrerelease": True,
                   "assets": [{"name": "scarab-20261114-ledger-rules.json.gz"}]}
        gh = Gh(release)
        with tempfile.TemporaryDirectory() as tmp, mock.patch("sys.stderr", new=io.StringIO()):
            write_report(tmp)
            self.assertEqual(publish.main([tmp, "--apply"], run=gh), 1)
        self.assertNotIn("release upload", gh.verbs())
        gh = Gh(release)
        with tempfile.TemporaryDirectory() as tmp, mock.patch("sys.stdout", new=io.StringIO()):
            write_report(tmp)
            self.assertEqual(publish.main([tmp, "--apply", "--replace"], run=gh), 0)
        self.assertIn("--clobber", gh.calls[-1])

    def test_an_unpushed_commit_is_refused(self):
        gh = Gh(commits=())
        with tempfile.TemporaryDirectory() as tmp, mock.patch("sys.stdout", new=io.StringIO()), \
                mock.patch("sys.stderr", new=io.StringIO()):
            write_report(tmp)
            self.assertEqual(publish.main([tmp, "--apply"], run=gh), 1)
        self.assertNotIn("release upload", gh.verbs())


if __name__ == "__main__":
    unittest.main()
