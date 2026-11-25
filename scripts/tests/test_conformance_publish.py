# SPDX-License-Identifier: Apache-2.0
"""`scripts/conformance/publish.py`: which files may be published, that a dry run runs nothing, and what `--apply`
does, against real Git repositories in a temporary directory (a local bare remote with a first commit), a fake `gh`
and a synthetic site that is taken for the pinned one (`conformance_site`): nothing leaves the test's directory."""
import hashlib
import io
import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
sys.path.insert(0, str(Path(__file__).resolve().parent))
from conformance import inputs, publish  # noqa: E402
from conformance_site import ABSENT, gzipped, ledger_report, write_site  # noqa: E402

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


def verified_site(root):
    """An inputs directory under `root` holding a synthetic site that `inputs.fetch` takes for the pinned one: two
    ledger-rules fixtures, one vector and one benchmark."""
    pin = inputs.load_pins()["site"]
    target = write_site(Path(root, pin.directory), {"00001-pass": ABSENT, "00002-fail": {"predicate": "P"}},
                        [{"id": "v", "plutus_version": "V3", "protocol_version": "10.0", "evaluation": "ok",
                          "budget": None}], ["a.flat"])
    Path(root, f"{pin.directory}.verified.json").write_text(json.dumps(inputs.receipt(pin, target)))
    return Path(root)


# Git as the tests run it: no global or system configuration (no signing, no hooks), and never a prompt.
GIT_ENV = {**os.environ, "GIT_CONFIG_GLOBAL": os.devnull, "GIT_CONFIG_NOSYSTEM": "1", "GIT_TERMINAL_PROMPT": "0"}


def git(*args):
    return subprocess.run(["git", *args], check=True, capture_output=True, env=GIT_ENV).stdout


class Remote:
    """A bare repository with `main` holding `files`, as the reports repository, under `root`."""

    def __init__(self, root, files=None):
        self.bare, seed = Path(root, "remote.git"), Path(root, "seed")
        git("init", "-q", "--bare", "-b", "main", str(self.bare))
        git("init", "-q", "-b", "main", str(seed))
        for path, body in (files or {"README.md": b"# first commit\n"}).items():
            (seed / path).parent.mkdir(parents=True, exist_ok=True)
            (seed / path).write_bytes(body)
        git("-C", str(seed), "add", "-A")
        git("-C", str(seed), "-c", "user.name=Seed", "-c", "user.email=seed@example.org", "commit", "-q", "-m", "first")
        git("-C", str(seed), "push", "-q", str(self.bare), "main")
        self.url = self.bare.as_uri()

    def files(self):
        names = git("--git-dir", str(self.bare), "ls-tree", "-r", "--name-only", "main").decode().split()
        return {name: git("--git-dir", str(self.bare), "show", f"main:{name}") for name in names}

    def commits(self):
        return int(git("--git-dir", str(self.bare), "rev-list", "--count", "main"))


class Runner:
    """Runs `git` for real, in the isolated environment, and fakes `gh`: GitHub knows `commits`. The first
    `push_failures` pushes are refused. Every command is recorded."""

    def __init__(self, commits=(COMMIT,), push_failures=0):
        self.commits, self.push_failures, self.calls = set(commits), push_failures, []

    def __call__(self, argv):
        argv = list(argv)
        self.calls.append(argv)
        if argv[:2] == ["gh", "api"]:
            sha = argv[2].rsplit("/", 1)[1]
            known = sha in self.commits
            return subprocess.CompletedProcess(argv, 0 if known else 1, sha + "\n" if known else "", "")
        assert argv[0] == "git", argv
        if "push" in argv and self.push_failures:
            self.push_failures -= 1
            return subprocess.CompletedProcess(argv, 1, "", "remote rejected (test)")
        return subprocess.run(argv, text=True, capture_output=True, env=GIT_ENV)

    def verbs(self):
        verbs = []
        for call in self.calls:
            words = call[1:]
            while words[0] in ("-C", "-c"):
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
        turbo = "scarab-20261114-uplc-turbo.json.gz"
        for runner in ({"environment": {"runner": "github-actions"}}, {}, {"environment": None}):
            with tempfile.TemporaryDirectory() as tmp, self.assertRaises(publish.PublishError, msg=str(runner)):
                write_report(tmp, turbo, **runner)
                publish.collect([Path(tmp)])
        with tempfile.TemporaryDirectory() as tmp:
            write_report(tmp, turbo, environment={"runner": "local"})
            write_report(tmp, environment={"runner": "github-actions"})
            self.assertEqual(len(publish.collect([Path(tmp)])), 2)
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
        self.assertIn("# fetch the site at 628006278309c02a1c3e49dce00e43595d925c2e into <temporary directory>/"
                      "site-628006278309, unless a verified copy is there", text)
        self.assertIn("fetch -q --depth 1 --filter=blob:none --no-tags "
                      "https://github.com/pragma-org/node-diversity-conformance 62800627", text)
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


PUBLISH = ["api", "clone", "rev-parse", "add", "diff", "commit", "rev-parse", "push"]
UPLC = "scarab-20261114-uplc-conformance.json.gz"


def provenance(name):
    return name.replace(".json.gz", ".provenance.json")


def ledger_files():
    return {f"reports/ledger-rules/{LEDGER}", f"reports/ledger-rules/{provenance(LEDGER)}",
            "latest/ledger-rules.json.gz", "latest/ledger-rules.provenance.json"}


class ApplyTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.out = self.root / "out"
        self.out.mkdir()
        self.inputs = verified_site(self.root / "inputs")

    def tearDown(self):
        self.tmp.cleanup()

    def publish(self, remote, runner, *args, reports=None):
        temporary_directory = tempfile.TemporaryDirectory
        self.scratch = []

        def recording(**kwargs):
            directory = temporary_directory(dir=self.root, **kwargs)
            self.scratch.append(Path(directory.name))
            return directory

        with mock.patch("sys.stdout", new=io.StringIO()) as out, mock.patch("sys.stderr", new=io.StringIO()) as err, \
                mock.patch.object(publish.tempfile, "TemporaryDirectory", side_effect=recording):
            code = publish.main([str(reports or self.out), "--apply", "--repo-url", remote.url,
                                 "--identity", "Bot <bot@example.org>", "--inputs", str(self.inputs), *args],
                                run=runner)
        for directory in self.scratch:
            self.assertFalse(directory.exists(), f"publisher's temporary directory remains: {directory}")
        return code, out.getvalue() + err.getvalue()

    def test_apply_clones_lays_out_commits_and_pushes(self):
        remote, runner = Remote(self.root), Runner()
        body = write_report(self.out).read_bytes()
        code, said = self.publish(remote, runner)
        self.assertEqual(code, 0, said)
        self.assertEqual(len(self.scratch), 1)
        self.assertEqual(runner.verbs(), PUBLISH)
        self.assertIn("--depth", runner.calls[1])
        files = remote.files()
        self.assertEqual(set(files), ledger_files() | {"README.md"})
        self.assertEqual(files[f"reports/ledger-rules/{LEDGER}"], body)
        self.assertEqual(files["latest/ledger-rules.json.gz"], body)
        readme = files["README.md"].decode()
        self.assertIn("[`ledger-rules`](latest/ledger-rules.json.gz) | 2026-11-14 |", readme)
        self.assertIn(f"https://github.com/KD5UJC-XYZ/scarab/commit/{COMMIT}", readme)
        add = next(call for call in runner.calls if "add" in call)
        self.assertEqual(set(add[add.index("--") + 1:]), ledger_files() | {"README.md"}, "exactly what it wrote")
        log = git("--git-dir", str(remote.bare), "log", "-1", "--format=%an <%ae>%n%B", "main").decode()
        self.assertIn("Bot <bot@example.org>", log)
        self.assertIn(f"Scarab commit {COMMIT}", log)
        self.assertEqual(remote.commits(), 2)

    def test_the_readme_scores_latest_by_the_site_s_rules(self):
        remote = Remote(self.root)
        write_report(self.out, body=gzipped(ledger_report([("00001", "pass"), ("00002", "Q")])))
        code, said = self.publish(remote, Runner())
        self.assertEqual(code, 0, said)
        readme = remote.files()["README.md"].decode()
        self.assertIn("do not edit it by hand", readme)
        self.assertLess(readme.index("## Results"), readme.index("## Reports in `latest/`"))
        self.assertIn("| `ledger-rules` | 1 | 2 | 50.00% | yes |", readme)
        self.assertIn("| `uplc-conformance` | | | no report | yes |", readme)
        self.assertIn("| `cbor-decoding`, Dijkstra | | | no report | no |", readme)
        self.assertIn("| **Overall** | | | **12.50%** | |", readme)
        self.assertIn("| [`ledger-rules`](latest/ledger-rules.json.gz) | 2026-11-14 |", readme)

    def test_a_site_that_cannot_be_fetched_publishes_nothing(self):
        remote, runner = Remote(self.root), Runner()
        write_report(self.out)
        with mock.patch.object(publish.inputs, "fetch", side_effect=inputs.InputError("site: checked out another")):
            code, said = self.publish(remote, runner)
        self.assertEqual(code, 1, said)
        self.assertIn("the site at 628006278 could not be fetched or read", said)
        self.assertIn("nothing was published", said)
        self.assertEqual(runner.verbs(), ["api"], "refused before the reports repository is cloned")
        self.assertEqual(remote.commits(), 1)

    def test_a_failed_copy_publishes_nothing_and_a_retry_only_its_own_files(self):
        remote = Remote(self.root)
        write_report(self.out)
        copy, calls = publish.shutil.copyfile, []

        def failing(source, target):
            calls.append(target)
            if len(calls) == 3:
                raise OSError("disk full")
            return copy(source, target)

        with mock.patch.object(publish.shutil, "copyfile", side_effect=failing):
            code, said = self.publish(remote, Runner())
        self.assertEqual(code, 1)
        self.assertIn("nothing was published", said)
        self.assertEqual(remote.commits(), 1)
        other = self.root / "other"
        other.mkdir()
        write_report(other, UPLC)
        self.assertEqual(self.publish(remote, Runner(), reports=other)[0], 0)
        self.assertFalse([name for name in remote.files() if "ledger-rules" in name], "no leftover of the failure")
        self.assertIn(f"reports/uplc-conformance/{UPLC}", remote.files())

    def test_a_failed_push_publishes_nothing_and_a_retry_pushes(self):
        remote = Remote(self.root)
        write_report(self.out)
        code, said = self.publish(remote, Runner(push_failures=1))
        self.assertEqual(code, 1)
        self.assertIn("git push failed", said)
        self.assertEqual(remote.commits(), 1)
        code, said = self.publish(remote, Runner())
        self.assertEqual(code, 0, said)
        self.assertNotIn("already published", said)
        self.assertLessEqual(ledger_files(), set(remote.files()), "the retry pushed")

    def test_a_remote_hook_rejects_a_real_push_and_nothing_is_published(self):
        remote, runner = Remote(self.root), Runner()
        before = git("--git-dir", str(remote.bare), "rev-parse", "main")
        files = remote.files()
        hook = remote.bare / "hooks" / "pre-receive"
        hook.write_text("#!/bin/sh\nexit 1\n")
        hook.chmod(0o755)
        write_report(self.out)
        code, said = self.publish(remote, runner)
        self.assertEqual(code, 1, said)
        self.assertIn("pre-receive hook declined", said)
        self.assertIn("nothing was published", said)
        self.assertNotIn("outcome unknown", said)
        self.assertEqual(git("--git-dir", str(remote.bare), "rev-parse", "main"), before)
        self.assertEqual(remote.files(), files)
        self.assertEqual(len(self.scratch), 1)
        self.assertEqual(runner.calls[-1], ["git", "-C", str(self.scratch[0] / "clone"),
                                          "ls-remote", remote.url, "refs/heads/main"])

    def test_a_successful_push_that_looks_failed_is_reported_as_published(self):
        remote, runner = Remote(self.root), Runner()
        write_report(self.out)

        def disconnected(argv):
            done = runner(argv)
            if "push" in argv:
                self.assertEqual(done.returncode, 0, done.stderr)
                raise OSError("connection dropped after push")
            return done

        code, said = self.publish(remote, disconnected)
        self.assertEqual(code, 0, said)
        self.assertIn(f"published reports/ledger-rules/{LEDGER}", said)
        self.assertNotIn("nothing was published", said)
        self.assertEqual(remote.commits(), 2)
        self.assertLessEqual(ledger_files(), set(remote.files()))
        self.assertEqual(len(self.scratch), 1)
        code, said = self.publish(remote, Runner())
        self.assertEqual(code, 1, said)
        self.assertIn("already published", said)
        self.assertEqual(remote.commits(), 2, "a retry must not publish twice")

    def test_a_failed_remote_check_leaves_the_outcome_unknown(self):
        remote, runner = Remote(self.root), Runner(push_failures=1)
        write_report(self.out)

        def unavailable(argv):
            if "ls-remote" in argv:
                remote.bare.rename(self.root / "unavailable.git")
            return runner(argv)

        code, said = self.publish(remote, unavailable)
        self.assertEqual(code, 1, said)
        self.assertIn("outcome unknown", said)
        self.assertIn("git ls-remote failed", said)
        self.assertIn(f"check {remote.url} refs/heads/main, then re-run", said)
        self.assertIn("already-published check prevents publishing a report twice", said)
        self.assertNotIn("nothing was published", said)
        self.assertEqual(len(self.scratch), 1)

    def test_a_concurrent_remote_commit_leaves_the_outcome_unknown(self):
        remote, runner = Remote(self.root), Runner()
        write_report(self.out)

        def concurrent(argv):
            if "push" in argv:
                seed = str(self.root / "seed")
                git("-C", seed, "-c", "user.name=Seed", "-c", "user.email=seed@example.org",
                    "commit", "-q", "--allow-empty", "-m", "concurrent publication")
                git("-C", seed, "push", "-q", remote.url, "main")
            return runner(argv)

        code, said = self.publish(remote, concurrent)
        self.assertEqual(code, 1, said)
        self.assertIn("outcome unknown", said)
        self.assertIn(f"check {remote.url} refs/heads/main, then re-run", said)
        self.assertNotIn("nothing was published", said)
        self.assertEqual(remote.files(), {"README.md": b"# first commit\n"})

    def test_a_malformed_provenance_in_the_repository_publishes_nothing(self):
        remote = Remote(self.root, {"latest/uplc-conformance.provenance.json": b"{not json"})
        write_report(self.out)
        code, said = self.publish(remote, Runner())
        self.assertEqual(code, 1)
        self.assertIn("nothing was published", said)
        self.assertEqual(remote.commits(), 1)

    def test_a_dated_report_is_not_overwritten_without_replace(self):
        remote = Remote(self.root, {f"reports/ledger-rules/{LEDGER}": b"earlier"})
        body = write_report(self.out).read_bytes()
        runner = Runner()
        code, said = self.publish(remote, runner)
        self.assertEqual(code, 1)
        self.assertIn("already published", said)
        self.assertNotIn("add", runner.verbs(), "refused before anything is staged")
        self.assertEqual(remote.files()[f"reports/ledger-rules/{LEDGER}"], b"earlier")
        self.assertEqual(self.publish(remote, Runner(), "--replace")[0], 0)
        self.assertEqual(remote.files()[f"reports/ledger-rules/{LEDGER}"], body)

    def test_latest_keeps_a_newer_report(self):
        newer = json.dumps({"report": "scarab-20261201-ledger-rules.json.gz", "sha256": "ab" * 32,
                            "scarab": {"commit": "d" * 40}}).encode()
        remote = Remote(self.root, {"latest/ledger-rules.json.gz": b"newer",
                                    "latest/ledger-rules.provenance.json": newer})
        write_report(self.out)
        self.assertEqual(self.publish(remote, Runner())[0], 0)
        files = remote.files()
        self.assertIn(f"reports/ledger-rules/{LEDGER}", files, "the older one is archived")
        self.assertEqual(files["latest/ledger-rules.json.gz"], b"newer")
        self.assertIn("| 2026-12-01 |", files["README.md"].decode())

    def test_an_unpushed_scarab_commit_is_refused_before_any_git_command(self):
        runner = Runner(commits=())
        write_report(self.out)
        self.assertEqual(self.publish(Remote(self.root), runner)[0], 1)
        self.assertEqual(runner.verbs(), ["api"])

    def test_files_the_remote_already_holds_are_not_committed_again(self):
        remote = Remote(self.root)
        write_report(self.out)
        self.assertEqual(self.publish(remote, Runner())[0], 0)
        runner = Runner()
        code, said = self.publish(remote, runner, "--replace")
        self.assertEqual(code, 0)
        self.assertIn("already published", said)
        self.assertEqual(runner.verbs(), ["api", "clone", "rev-parse", "add", "diff"])
        self.assertEqual(remote.commits(), 2)

    def test_a_bad_identity_is_refused(self):
        write_report(self.out)
        with mock.patch("sys.stdout", new=io.StringIO()), mock.patch("sys.stderr", new=io.StringIO()):
            self.assertEqual(publish.main([str(self.out), "--apply", "--identity", "no email"], run=never), 1)


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
