# SPDX-License-Identifier: Apache-2.0
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import textwrap
import unittest
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import codex_review as review

# A stand-in for `codex exec`: it reads the brief, writes the final message and a session file the way Codex 0.169
# does, and prints the JSON events. FAKE_* variables bend one part of that to test a refusal.
FAKE = textwrap.dedent('''\
    import json, os, sys, pathlib
    args = sys.argv[1:]
    brief = sys.stdin.read()
    out = pathlib.Path(args[args.index("-o") + 1])
    work = pathlib.Path(args[args.index("-C") + 1])
    settings = [args[i + 1] for i, a in enumerate(args) if a == "-c"]
    effort = next(s.split("=", 1)[1].strip('"') for s in settings if s.startswith("model_reasoning_effort="))
    model = os.environ.get("FAKE_MODEL", args[args.index("-m") + 1])
    network = "network.enabled=true" in " ".join(settings) and not os.environ.get("FAKE_NO_NETWORK")
    thread = "01a1f035-0000-7000-8000-00000000abcd"
    print(json.dumps({"type": "thread.started", "thread_id": thread}))
    if os.environ.get("FAKE_FAIL"):
        print(json.dumps({"type": "turn.failed", "error": {"message": "boom"}}))
        sys.exit(0)
    if os.environ.get("FAKE_EDIT"):
        (work / "edited.txt").write_text("x")
    day = pathlib.Path(os.environ["CODEX_HOME"], "sessions", "2026", "11", "18")
    day.mkdir(parents=True, exist_ok=True)
    with open(day / f"rollout-2026-11-18T00-00-00-{thread}.jsonl", "w") as f:
        f.write(json.dumps({"type": "session_meta", "payload": {"cli_version": "0.169.0"}}) + "\\n")
        f.write(json.dumps({"type": "turn_context", "payload": {
            "model": model, "effort": os.environ.get("FAKE_EFFORT", effort),
            "sandbox_policy": {"type": "read-only", "network_access": network}}}) + "\\n")
    message = os.environ.get("FAKE_MESSAGE", "P2 [Foo.scala:3](" + str(work) + "/Foo.scala#L3): brief was "
                             + str(len(brief)) + " chars")
    out.write_text(message)
    print(json.dumps({"type": "turn.completed", "usage": {"input_tokens": 1}}))
''')


class FloorAndStampTests(unittest.TestCase):
    def test_each_level_has_a_floor_and_design_documents_and_adrs_raise_it_to_high(self):
        self.assertEqual([review.floor(level) for level in range(5)], ["medium", "high", "high", "xhigh", "xhigh"])
        self.assertEqual(review.floor(0, ["docs/design/leios.md"]), "high")
        self.assertEqual(review.floor(0, ["docs/adr/0017-fibers.md", "README.md"]), "high")
        self.assertEqual(review.floor(3, ["docs/adr/0017-fibers.md"]), "xhigh")
        self.assertEqual(review.floor(0, ["docs/running.md", "docs/design-rules.md"]), "medium")
        with self.assertRaises(review.ReviewError):
            review.floor(5)

    def test_a_stamp_reads_back_and_is_judged_against_the_floor(self):
        text = "intro\n" + review.stamp({"model": review.MODEL, "effort": "high", "level": 2, "head": "a" * 40})
        [fields] = review.stamps(text)
        self.assertEqual((fields["model"], fields["effort"], fields["level"]), (review.MODEL, "high", "2"))
        self.assertIsNone(review.stamp_problem(fields, 2))
        self.assertIn("below xhigh", review.stamp_problem(fields, 3))
        self.assertIn("not " + review.MODEL, review.stamp_problem(dict(fields, model="other"), 1))
        self.assertEqual(review.stamps("no stamp <!-- a comment -->"), [])

    def test_links_to_absolute_paths_and_the_worktree_path_are_removed_and_a_home_path_is_refused(self):
        work = "/srv/w/scarab"
        self.assertEqual(review.scrub(f"[A.scala:3]({work}/A.scala#L3) and {work}/B.scala", work),
                         "A.scala:3 and B.scala")
        with self.assertRaisesRegex(review.ReviewError, "home directory"):
            review.scrub("see /home/someone/notes.txt", work)

    def test_the_command_pins_model_effort_read_only_network_and_no_approvals(self):
        line = " ".join(review.command("high", Path("/w"), Path("/o")))
        for part in [f"-m {review.MODEL}", 'model_reasoning_effort="high"', 'extends=":read-only"',
                     "network.enabled=true", 'approval_policy="never"', 'web_search="live"', "-C /w", "--json"]:
            self.assertIn(part, line)
        self.assertNotIn("--sandbox", line)


class RunTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        root = Path(self.tmp.name)
        self.work, self.home = root / "work", root / "codex-home"
        self.work.mkdir()
        git = ["git", "-C", str(self.work), "-c", "user.name=t", "-c", "user.email=t@example.invalid"]
        subprocess.run(["git", "init", "-q", "-b", "main", str(self.work)], check=True)
        (self.work / "README.md").write_text("base\n")
        subprocess.run(git + ["add", "."], check=True)
        subprocess.run(git + ["commit", "-qm", "base"], check=True)
        subprocess.run(git + ["branch", "base"], check=True)
        (self.work / "Foo.scala").write_text("object Foo\n")
        subprocess.run(git + ["add", "."], check=True)
        subprocess.run(git + ["commit", "-qm", "head"], check=True)
        self.fake = root / "codex"
        self.fake.write_text(f"#!{sys.executable}\n" + FAKE)
        self.fake.chmod(0o755)
        self.brief = root / "brief.md"
        self.brief.write_text("Review the diff.\n")
        self.out = root / "review.md"

    def tearDown(self):
        self.tmp.cleanup()

    def run_review(self, *extra, **env):
        environment = {"CODEX_HOME": str(self.home), **env}
        with mock.patch.dict(os.environ, environment), mock.patch("sys.stderr"), mock.patch("sys.stdout"):
            return review.main(["--level", "1", "--brief", str(self.brief), "--out", str(self.out),
                                "--base", "base", "--cd", str(self.work), "--codex", str(self.fake), *extra])

    def test_a_good_run_writes_the_stamp_the_header_and_the_scrubbed_review(self):
        self.assertEqual(self.run_review(), 0)
        text = self.out.read_text()
        [fields] = review.stamps(text)
        self.assertEqual((fields["model"], fields["effort"], fields["level"], fields["network"], fields["cli"]),
                         (review.MODEL, "high", "1", "on", "0.169.0"))
        self.assertIn("P2 Foo.scala:3: brief was 17 chars", text)
        self.assertNotIn(str(self.work), text)

    def test_a_higher_effort_is_allowed_and_a_lower_one_refused(self):
        self.assertEqual(self.run_review("--effort", "xhigh"), 0)
        self.assertEqual(review.stamps(self.out.read_text())[0]["effort"], "xhigh")
        self.out.unlink()
        self.assertEqual(self.run_review("--effort", "medium"), 1)
        self.assertFalse(self.out.exists())

    def test_a_run_is_refused_when_the_session_or_the_worktree_is_not_as_required(self):
        for env in [{"FAKE_MODEL": "gpt-daybreak-blue-latest"}, {"FAKE_EFFORT": "low"}, {"FAKE_NO_NETWORK": "1"},
                    {"FAKE_FAIL": "1"}, {"FAKE_EDIT": "1"}, {"FAKE_MESSAGE": ""},
                    {"FAKE_MESSAGE": "see /home/someone/x"}]:
            with self.subTest(env=env):
                if self.home.exists():
                    for f in self.home.rglob("*.jsonl"):
                        f.unlink()
                (self.work / "edited.txt").unlink(missing_ok=True)
                self.assertEqual(self.run_review(**env), 1)
                self.assertFalse(self.out.exists())


if __name__ == "__main__":
    unittest.main()
