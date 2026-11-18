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

# A home path, built so that this tracked file names none itself (scripts/check_local_paths.py).
HOME = "/" + "home/someone/"

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
    if os.environ.get("FAKE_RETRY"):
        print(json.dumps({"type": "error", "message": "stream disconnected; retrying"}))
    if os.environ.get("FAKE_REROUTE"):
        print(json.dumps({"type": "item.completed", "item": {"id": "item_1", "type": "error", "message":
                          "model rerouted: " + model + " -> other (HighRiskCyberActivity)"}}))
    if os.environ.get("FAKE_COMMIT"):
        import subprocess
        subprocess.run(["git", "-C", str(work), "-c", "user.name=t", "-c", "user.email=t@example.invalid",
                        "commit", "-q", "--allow-empty", "-m", "moved"], check=True)
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
        if os.environ.get("FAKE_TRUNCATED"):
            f.write('{"type": "turn_context", "payload": {"model": "oth' + "\\n")
        if os.environ.get("FAKE_SECOND_TURN"):
            f.write(json.dumps({"type": "turn_context", "payload": {
                "model": os.environ["FAKE_SECOND_TURN"], "effort": effort,
                "sandbox_policy": {"type": "read-only", "network_access": network}}}) + "\\n")
    message = os.environ.get("FAKE_MESSAGE", "P2 [Foo.scala](" + str(work) + "/Foo.scala#L3): brief was "
                             + str(len(brief)) + " chars")
    out.write_text(message)
    if not os.environ.get("FAKE_NO_COMPLETION"):
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

    def test_a_stamp_reads_back_from_the_first_line_and_is_judged_against_the_floor(self):
        line = review.stamp({"model": review.MODEL, "effort": "high", "level": 2, "head": "a" * 40})
        fields = review.stamp_of(line + "\nreview")
        self.assertEqual((fields["model"], fields["effort"], fields["level"]), (review.MODEL, "high", "2"))
        self.assertIsNone(review.stamp_problem(fields, 2))
        self.assertIn("below xhigh", review.stamp_problem(fields, 3))
        self.assertIn("not " + review.MODEL, review.stamp_problem(dict(fields, model="other"), 1))
        self.assertIsNone(review.stamp_of("intro\n" + line + "\nreview"))
        self.assertIsNone(review.stamp_of("no stamp <!-- a comment -->"))
        for text, message in [(line, "no review"), (line.replace(" head=", " level=3 head=") + "\nx", "repeats"),
                              (line.replace(" level=2", "") + "\nx", "lacks level")]:
            with self.subTest(text=text), self.assertRaisesRegex(review.ReviewError, message):
                review.stamp_of(text)

    def test_json_lines_split_on_newlines_only_and_refuse_a_bad_line_unless_lenient(self):
        text = '{"a": "x\u0085y"}\nnot json\n{"b": 1}\n'
        self.assertEqual(review.records(text, strict=False), [{"a": "x\u0085y"}, {"b": 1}])
        with self.assertRaisesRegex(review.ReviewError, "line 2"):
            review.records(text)

    def test_a_stamp_needs_review_text_beyond_its_attribution_and_must_start_the_comment(self):
        line = review.stamp({"model": review.MODEL, "effort": "high", "level": 1, "head": "a" * 40})
        with self.assertRaisesRegex(review.ReviewError, "no review"):
            review.stamp_of(line + "\n" + review.ATTRIBUTION + "`x` at effort `high`.\n\n")
        self.assertIsNone(review.stamp_of("    " + line + "\nreview"))
        self.assertIsNone(review.stamp_of("\n" + line + "\nreview"))

    def test_links_into_the_worktree_become_path_and_line_other_absolute_links_their_label(self):
        work = "/srv/w/scarab"
        self.assertEqual(review.scrub(f"[A.scala]({work}/x/A.scala#L3) [x/B.scala:4]({work}/x/B.scala:4) "
                                      f"[C]({work}/x/C.scala#L5-L9) [doc](/opt/doc) {work}/D.scala", work),
                         "A.scala (`x/A.scala:3`) x/B.scala:4 C (`x/C.scala:5`) doc D.scala")
        with self.assertRaisesRegex(review.ReviewError, "home directory"):
            review.scrub("see " + HOME + "notes.txt", work)

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
                                "--base", "base", "--pr-base", "base", "--cd", str(self.work), "--codex", str(self.fake), *extra])

    def test_a_good_run_writes_the_stamp_the_header_and_the_scrubbed_review(self):
        self.assertEqual(self.run_review(), 0)
        text = self.out.read_text()
        fields = review.stamp_of(text)
        self.assertEqual((fields["model"], fields["effort"], fields["level"], fields["network"], fields["cli"]),
                         (review.MODEL, "high", "1", "on", "0.169.0"))
        self.assertIn("P2 Foo.scala (`Foo.scala:3`): brief was 17 chars", text)
        self.assertNotIn(str(self.work), text)

    def test_a_higher_effort_is_allowed_and_a_lower_one_refused(self):
        self.assertEqual(self.run_review("--effort", "xhigh"), 0)
        self.assertEqual(review.stamp_of(self.out.read_text())["effort"], "xhigh")
        self.out.unlink()
        self.assertEqual(self.run_review("--effort", "medium"), 1)
        self.assertFalse(self.out.exists())

    def test_a_retried_error_followed_by_a_completed_turn_is_accepted(self):
        self.assertEqual(self.run_review(FAKE_RETRY="1"), 0)

    def test_a_run_is_refused_when_the_session_or_the_worktree_is_not_as_required(self):
        for env in [{"FAKE_MODEL": "gpt-daybreak-blue-latest"}, {"FAKE_EFFORT": "low"}, {"FAKE_NO_NETWORK": "1"},
                    {"FAKE_FAIL": "1"}, {"FAKE_NO_COMPLETION": "1"}, {"FAKE_REROUTE": "1"},
                    {"FAKE_SECOND_TURN": "other-model"}, {"FAKE_TRUNCATED": "1"}, {"FAKE_EDIT": "1"},
                    {"FAKE_COMMIT": "1"}, {"FAKE_MESSAGE": ""},
                    {"FAKE_MESSAGE": "see " + HOME + "x"}]:
            with self.subTest(env=env):
                if self.home.exists():
                    for f in self.home.rglob("*.jsonl"):
                        f.unlink()
                (self.work / "edited.txt").unlink(missing_ok=True)
                self.assertEqual(self.run_review(**env), 1)
                self.assertFalse(self.out.exists())

    def test_a_dirty_worktree_or_an_output_inside_it_is_refused_before_codex_runs(self):
        (self.work / "untracked.txt").write_text("x")
        self.assertEqual(self.run_review(), 1)
        (self.work / "untracked.txt").unlink()
        self.out = self.work / "review.md"
        self.assertEqual(self.run_review(), 1)
        sub = self.work / "sub"
        sub.mkdir()
        self.out = sub / ".." / "review.md"
        with mock.patch.dict(os.environ, {"CODEX_HOME": str(self.home)}), mock.patch("sys.stderr"):
            self.assertEqual(review.main(["--level", "1", "--brief", str(self.brief), "--out", str(self.out),
                                          "--base", "base", "--pr-base", "base", "--cd", str(sub),
                                          "--codex", str(self.fake)]), 1)
        self.assertFalse(self.home.exists())

    def test_the_floor_comes_from_the_pull_request_base_not_the_scope_of_a_second_review(self):
        git = ["git", "-C", str(self.work), "-c", "user.name=t", "-c", "user.email=t@example.invalid"]
        (self.work / "docs" / "adr").mkdir(parents=True)
        (self.work / "docs" / "adr" / "0001-x.md").write_text("adr\n")
        subprocess.run(git + ["add", "."], check=True)
        subprocess.run(git + ["commit", "-qm", "adr"], check=True)
        subprocess.run(git + ["branch", "fixes"], check=True)
        (self.work / "guide.md").write_text("guide\n")
        subprocess.run(git + ["add", "."], check=True)
        subprocess.run(git + ["commit", "-qm", "guide"], check=True)
        with mock.patch.dict(os.environ, {"CODEX_HOME": str(self.home)}), mock.patch("sys.stderr"), \
                mock.patch("sys.stdout"):
            self.assertEqual(review.main(["--level", "0", "--brief", str(self.brief), "--out", str(self.out),
                                          "--base", "fixes", "--pr-base", "base", "--cd", str(self.work),
                                          "--codex", str(self.fake)]), 0)
        fields = review.stamp_of(self.out.read_text())
        self.assertEqual((fields["effort"], fields["base"]), ("high", "fixes"))

    def test_the_floor_reads_both_sides_of_a_rename_and_unquoted_paths(self):
        git = ["git", "-C", str(self.work), "-c", "user.name=t", "-c", "user.email=t@example.invalid"]
        (self.work / "docs" / "adr").mkdir(parents=True)
        (self.work / "docs" / "adr" / "0001-ré.md").write_text("adr\n")
        subprocess.run(git + ["add", "."], check=True)
        subprocess.run(git + ["commit", "-qm", "adr"], check=True)
        self.assertIn("docs/adr/0001-ré.md", review.changed_files(self.work, "base"))
        self.assertEqual(review.floor(0, review.changed_files(self.work, "base")), "high")
        subprocess.run(git + ["branch", "-f", "base"], check=True)
        subprocess.run(git + ["mv", "docs/adr/0001-ré.md", "docs/old.md"], check=True)
        subprocess.run(git + ["commit", "-qm", "move"], check=True)
        self.assertEqual(sorted(review.changed_files(self.work, "base")), ["docs/adr/0001-ré.md", "docs/old.md"])

if __name__ == "__main__":
    unittest.main()
