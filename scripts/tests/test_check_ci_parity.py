# SPDX-License-Identifier: Apache-2.0
"""check_ci_parity: a check that runs on a push to main also ran on its pull request, and push runs are never cancelled."""
from pathlib import Path
import sys
import tempfile
import textwrap
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import check_ci_parity as parity

ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ".github/workflows/w.yml"
# The fixed form of the push-to-main workflows: a pull request's runs share a group and cancel; others have their own.
GOOD_CONCURRENCY = """\
concurrency:
  group: w-${{ github.event_name == 'pull_request' && format('pr-{0}', github.event.pull_request.number) || format('run-{0}', github.run_id) }}
  cancel-in-progress: ${{ github.event_name == 'pull_request' }}
"""
TRIGGERS = """\
on:
  pull_request:
    branches: [main, dev]
  push:
    branches: [main]
"""


def workflow(jobs, on=TRIGGERS, concurrency=GOOD_CONCURRENCY):
    return "name: W\n" + on + concurrency + "jobs:\n" + textwrap.indent(textwrap.dedent(jobs), "  ")


def job(name="check", lines="", steps="- run: make check\n"):
    return f"{name}:\n  runs-on: ubuntu-24.04\n{textwrap.indent(lines, '  ')}  steps:\n" + textwrap.indent(steps, "    ")


def problems(text, allowed=None):
    return parity.findings([(WORKFLOW, text)], {} if allowed is None else allowed)


class YamlReaderTests(unittest.TestCase):
    def test_reads_the_subset_the_workflows_use(self):
        text = textwrap.dedent("""\
            # comment
            on:
              push:
                branches: [main, 'release/*', "x"]  # trailing comment
            empty:
            compact:
            - a
            - b: 1
              c: 'it''s'
            - - nested
            nums: [1, 2.5, -3]
            flags: {a: true, b: 'x, y', c: null}
            literal: |
              one
                two

              three
            folded: >-
              one
              two

              three
                more
              last
            keep: |+
              kept

            url: https://example.org/a#b
            quoted: "a\\tb\\u00e9 # not a comment"
            """)
        self.assertEqual(parity.read_yaml(text), {
            "on": {"push": {"branches": ["main", "release/*", "x"]}},
            "empty": None,
            "compact": ["a", {"b": 1, "c": "it's"}, ["nested"]],
            "nums": [1, 2.5, -3],
            "flags": {"a": True, "b": "x, y", "c": None},
            "literal": "one\n  two\n\nthree\n",
            "folded": "one two\nthree\n  more\nlast",
            "keep": "kept\n\n",
            "url": "https://example.org/a#b",
            "quoted": "a\tb\u00e9 # not a comment",
        })

    def test_refuses_what_it_does_not_read(self):
        cases = {
            "anchor": "a: &x 1\n",
            "alias": "a: *x\n",
            "tag": "a: !!str 1\n",
            "nested flow": "a: [1, [2]]\n",
            "multi-line flow": "a: [1,\n  2]\n",
            "multi-line plain": "a: one\n  two\n",
            "multi-line quoted": "a: 'one\n  two'\n",
            "duplicate key": "a: 1\na: 2\n",
            "tab indentation": "a:\n\tb: 1\n",
            "document marker": "---\na: 1\n",
            "indentation indicator": "a: |2\n  x\n",
            "complex key": "? a\n: b\n",
            "bad indentation": "a:\n    b: 1\n  c: 2\n",
            "unknown escape": 'a: "\\q"\n',
        }
        for name, text in cases.items():
            with self.subTest(name):
                with self.assertRaises(parity.ReadError):
                    parity.read_yaml(text)

    def test_scalars_resolve_as_the_yaml_1_2_core_schema_says(self):
        self.assertEqual(parity.read_yaml("a: [0x1f, 0o17, 1e3, .inf, -.Inf, 7, '7', ~, True]\n"),
                         {"a": [31, 15, 1000.0, float("inf"), float("-inf"), 7, "7", None, True]})
        self.assertNotEqual(parity.read_yaml("a: .nan\n")["a"], parity.read_yaml("a: .nan\n")["a"])

    def test_block_scalars_keep_exactly_their_line_breaks(self):
        cases = {"a: |+\n\n": "\n", "a: |+\n\n\n": "\n\n", "a: |\n\n": "", "a: |\n  x": "x", "a: |+\n  x": "x",
                 "a: |\n  x\n\n": "x\n", "a: |+\n  x\n \n": "x\n\n", "a: >\n  x\n  y": "x y"}
        for text, value in cases.items():
            with self.subTest(text):
                self.assertEqual(parity.read_yaml(text), {"a": value})

    def test_deep_nesting_is_refused_rather_than_exhausting_the_stack(self):
        nested = "a:\n" + "".join(" " * (depth + 1) + "b:\n" for depth in range(400))
        with self.assertRaises(parity.ReadError):
            parity.read_yaml(nested)
        for source in ("(" * 200 + "true" + ")" * 200, " && ".join(["true"] * 200), "!" * 200 + "true"):
            with self.subTest(source[:10]):
                with self.assertRaises(parity.ReadError):
                    parity.parse_expression(source)

    def test_every_workflow_of_the_repository_is_read(self):
        files = parity.workflow_files(ROOT)
        self.assertGreater(len(files), 0)
        for path, text in files:
            with self.subTest(path):
                self.assertIsInstance(parity.read_yaml(text), dict)

    def test_agrees_with_pyyaml_on_every_workflow(self):
        try:
            import yaml
        except ImportError:
            self.skipTest("PyYAML is not installed in this environment (the pinned test venv has none)")

        def normal(value):
            # PyYAML reads YAML 1.1, where the key `on` is the boolean true; GitHub reads it as the string.
            if isinstance(value, dict):
                return {("on" if key is True else key): normal(item) for key, item in value.items()}
            if isinstance(value, list):
                return [normal(item) for item in value]
            return value

        for path, text in parity.workflow_files(ROOT):
            with self.subTest(path):
                self.assertEqual(parity.read_yaml(text), normal(yaml.safe_load(text)))


class ExpressionTests(unittest.TestCase):
    def value(self, source, event=parity.PUSH_TO_MAIN):
        return parity.evaluate(parity.parse_expression(source), event)

    def test_operators_follow_githubs_rules(self):
        self.assertIs(self.value("github.event_name == 'PUSH'"), True, "strings compare ignoring case")
        self.assertIs(self.value("!github.event_name == false"), True, "! binds tighter than ==")
        self.assertEqual(self.value("github.event.pull_request.number || 'x'"), "x")
        self.assertEqual(self.value("'a' && 'b'"), "b")
        self.assertIs(self.value("null == null"), True)
        self.assertIs(self.value("1 == 1.0"), True)
        self.assertIs(self.value("inputs.write"), None, "push and pull request events have no inputs")
        self.assertEqual(self.value("format('{0}-{{x}}-{1}', 'a', 2)"), "a-{x}-2")
        self.assertIs(self.value("contains('Hello', 'ELL')"), True)
        self.assertIs(self.value("startsWith(github.ref, 'refs/heads/')"), True)

    def test_only_comparisons_the_runner_decides_alike_are_evaluated(self):
        self.assertIs(self.value("'Pull_Request' == 'pull_request'"), True, "ASCII case is ignored")
        self.assertIs(self.value("always()"), True)
        # The runner converts across types (a string to a number, hex and octal in 32 bits) and folds non-ASCII
        # case by .NET rules; the guard models neither and refuses them.
        for source in ("'true' == 1", "'0xffffffff' == 4294967295", "'' == 0", "null == 0",
                       "'\u00df' == 'ss'", "'\u0131' == 'I'", "contains('\u0131', 'I')"):
            with self.subTest(source):
                with self.assertRaises(parity.ReadError):
                    self.value(source)
        run = self.value("github.run_id > 0")
        self.assertIsInstance(run, parity.Unknown, "the run id is not a value the guard knows")
        self.assertTrue(run.event)

    def test_unknowns_are_kept_with_what_they_depend_on(self):
        shared = self.value("needs.a.outputs.run == 'true'")
        self.assertIsInstance(shared, parity.Unknown)
        self.assertFalse(shared.event)
        pull = self.value("github.event.pull_request.number == 3", parity.PULL_REQUEST_TO_MAIN)
        self.assertIsInstance(pull, parity.Unknown)
        self.assertTrue(pull.event, "a pull request's number differs per event")
        self.assertIs(parity.truth(self.value("always() || github.event_name == 'push'")), True)
        self.assertIs(parity.truth(self.value("always() && github.event_name == 'pull_request'")), False)
        self.assertIsNone(parity.truth(self.value("always() && vars.X")))

    def test_refuses_what_it_cannot_read(self):
        for source in ("a ? b : c", "github.event_name ==", "toJSON(github)", "unknown.x", "format('{1}', 'a')",
                       "github.*.name", "github.event == 'x'"):
            with self.subTest(source):
                with self.assertRaises(parity.ReadError):
                    self.value(source)

    def test_an_if_written_with_or_without_the_braces_is_the_same(self):
        self.assertEqual(parity.condition("${{ always() }}"), parity.condition("always()"))
        self.assertEqual(parity.condition(None), ("call", "success", ()))
        with self.assertRaises(parity.ReadError):
            parity.condition("${{ always() }} && x")


class RuleTests(unittest.TestCase):
    def test_the_fixed_form_passes(self):
        self.assertEqual(problems(workflow(job())), [])

    def test_a_job_only_a_push_runs_is_refused(self):
        found = problems(workflow(job("check") + job("publish", "if: github.event_name == 'push'\n")))
        self.assertEqual(found, [f"{WORKFLOW}: job publish: runs on a push to main but cannot run on a pull request to main"])

    def test_a_job_only_a_pull_request_runs_passes(self):
        self.assertEqual(problems(workflow(job("review", "if: github.event_name == 'pull_request'\n"))), [])

    def test_a_condition_the_event_leaves_undecided_is_refused(self):
        found = problems(workflow(job("check", "if: github.event_name == 'push' || github.event.pull_request.draft\n")))
        self.assertEqual(found, [f"{WORKFLOW}: job check: its `if` may hold on a push to main where it does not on a "
                                 "pull request"])

    def test_a_condition_on_who_pushed_is_refused(self):
        found = problems(workflow(job("check", "if: github.actor != 'dependabot[bot]'\n")))
        self.assertEqual(found, [f"{WORKFLOW}: job check: its `if` may hold on a push to main where it does not on a "
                                 "pull request"])
        self.assertEqual(problems(workflow(job("check", "if: github.repository == 'o/r'\n"))), [])

    def test_a_condition_on_what_ran_differently_is_refused(self):
        select = job("select", "if: github.event_name == 'pull_request'\noutputs:\n  skip: ${{ steps.s.outputs.skip }}\n",
                     steps="- id: s\n  run: echo skip=true >> \"$GITHUB_OUTPUT\"\n")
        check = job("check", "needs: select\nif: always() && needs.select.outputs.skip != 'true'\n")
        self.assertEqual(problems(workflow(select + check)), [
            f"{WORKFLOW}: job check: its `if` may hold on a push to main where it does not on a pull request"])
        steps = ("- if: github.event_name == 'pull_request'\n  id: s\n  run: echo SKIP=1 >> \"$GITHUB_ENV\"\n"
                 "- if: env.SKIP != '1'\n  run: make\n- if: steps.s.outcome != 'success'\n  run: make\n")
        self.assertEqual(problems(workflow(job(steps=steps))), [
            f"{WORKFLOW}: job check, step 1 (s): runs differently on the two events and writes $GITHUB_ENV or "
            "$GITHUB_PATH for the steps after it",
            f"{WORKFLOW}: job check, step 3 (run): its `if` may hold on a push to main where it does not on a pull "
            "request"])

    def test_what_ran_differently_is_not_read_anywhere_else(self):
        copied = ("- id: a\n  if: github.event_name == 'pull_request'\n  run: echo skip=true >> \"$GITHUB_OUTPUT\"\n"
                  "- id: b\n  run: echo 'skip=${{ steps.a.outputs.skip }}' >> \"$GITHUB_OUTPUT\"\n"
                  "- if: steps.b.outputs.skip != 'true'\n  run: make check\n")
        self.assertEqual(problems(workflow(job(steps=copied))), [
            f"{WORKFLOW}: job check, step 2 (b): `run` reads the event, or what ran differently on the two events",
            f"{WORKFLOW}: job check, step 3 (run): its `if` may hold on a push to main where it does not on a pull "
            "request"])
        select = job("select", "if: github.event_name == 'pull_request'\n")
        for lines in ("needs: select\nif: always() && !contains(toJSON(needs), 'true')\n",
                      "needs: select\nif: always() && needs[vars.PRODUCER].outputs.skip != 'true'\n"):
            with self.subTest(lines):
                self.assertEqual(problems(workflow(select + job("check", lines))), [
                    f"{WORKFLOW}: job check: its `if` may hold on a push to main where it does not on a pull request"])
        env = job("check", "needs: select\nif: always()\nenv:\n  SKIP: ${{ needs.select.outputs.skip }}\n")
        self.assertEqual(problems(workflow(select + env)), [
            f"{WORKFLOW}: job check: `env` reads the event, or what ran differently on the two events"])

    def test_failure_handlers_can_run_whatever_their_needs(self):
        jobs = (job("a", steps="- run: exit 1\n") + job("b", "needs: a\nif: false\n")
                + job("check", "needs: [a, b]\nif: failure() && github.event_name == 'push'\n"))
        self.assertEqual(problems(workflow(jobs)), [
            f"{WORKFLOW}: job check: runs on a push to main but cannot run on a pull request to main"])

    def test_conditions_the_runner_may_decide_otherwise_are_refused(self):
        for condition in ("github.event_name == 'push' || 'true' == 1", "github.event_name == 'push' || '\u0131' == 'I'"):
            with self.subTest(condition):
                found = problems(workflow(job("check", f"if: {condition}\n")))
                self.assertEqual(len(found), 1, found)
                self.assertTrue(found[0].startswith(f"{WORKFLOW}: cannot be read by this guard"), found)
        self.assertEqual(problems(workflow(job("check", "if: github.event_name == 'push' || always()\n"))), [])

    def test_a_condition_on_the_run_alone_passes(self):
        jobs = job("select") + job("check", "needs: select\nif: needs.select.outputs.run == 'true'\n")
        self.assertEqual(problems(workflow(jobs)), [])

    def test_a_job_whose_needs_never_run_on_a_push_is_not_a_push_job(self):
        jobs = job("approve", "if: github.event_name == 'workflow_dispatch'\n") + job("write", "needs: approve\n")
        self.assertEqual(problems(workflow(jobs)), [])

    def test_a_job_needing_a_push_only_job_is_refused_even_when_that_job_is_allowed(self):
        jobs = job("publish", "if: github.event_name == 'push'\n") + job("after", "needs: publish\n")
        found = problems(workflow(jobs), {(WORKFLOW, "publish"): "publishes the graph"})
        self.assertEqual(found, [
            f"{WORKFLOW}: job after: needs publish, which runs on a push only (SIDE_EFFECT_JOBS)",
            f"{WORKFLOW}: job after: runs on a push to main but cannot run on a pull request to main"])

    def test_always_runs_without_its_needs(self):
        jobs = job("publish", "if: github.event_name == 'push'\n") + job("gate", "needs: publish\nif: always()\n")
        found = problems(workflow(jobs), {(WORKFLOW, "publish"): "publishes the graph"})
        self.assertEqual(found, [f"{WORKFLOW}: job gate: needs publish, which runs on a push only (SIDE_EFFECT_JOBS)"])

    def test_a_step_only_a_push_runs_is_refused(self):
        steps = "- run: make check\n- name: upload\n  if: github.event_name != 'pull_request'\n  run: make upload\n"
        found = problems(workflow(job(steps=steps)))
        self.assertEqual(found, [f"{WORKFLOW}: job check, step 2 (upload): its `if` may hold on a push to main where it "
                                 "does not on a pull request"])

    def test_a_step_only_a_pull_request_runs_passes(self):
        steps = "- run: make check\n- if: github.event_name == 'pull_request'\n  run: make review\n"
        self.assertEqual(problems(workflow(job(steps=steps))), [])

    def test_reading_the_event_in_a_script_or_a_value_is_refused(self):
        cases = {
            "script": ('- run: test "$GITHUB_EVENT_NAME" = push\n', "step 1 (run): `run` reads the event, or what ran differently on the two events"),
            "env": ("- run: make\n  env:\n    BASE: ${{ github.event.before }}\n", "step 1 (run): `env` reads the event, or what ran differently on the two events"),
            "ref": ("- run: echo ${{ github.ref_name }}\n", "step 1 (run): `run` reads the event, or what ran differently on the two events"),
        }
        for name, (steps, message) in cases.items():
            with self.subTest(name):
                self.assertEqual(problems(workflow(job(steps=steps))), [f"{WORKFLOW}: job check, {message}"])
        found = problems(workflow(job(lines="continue-on-error: ${{ github.event_name == 'push' }}\n")))
        self.assertEqual(found, [f"{WORKFLOW}: job check: `continue-on-error` reads the event, or what ran differently on the two events"])
        for script in ("echo ${{ github[format('event{0}', '_name')] }}", "echo '${{ toJSON(github) }}'",
                       'test "$GITHUB_ACTOR" = bot', "echo ${{ github.triggering_actor }}"):
            with self.subTest(script):
                self.assertEqual(problems(workflow(job(steps=f"- run: {script}\n"))),
                                 [f"{WORKFLOW}: job check, step 1 (run): `run` reads the event, or what ran differently on the two events"])
        self.assertEqual(problems(workflow(job(steps="- run: echo ${{ github.sha }} ${{ github.repository }}\n"))), [])

    def test_reusable_workflows_and_local_actions_are_refused(self):
        found = problems(workflow("call:\n  uses: ./.github/workflows/other.yml\n"))
        self.assertEqual(found, [f"{WORKFLOW}: job call: calls a reusable workflow, which this guard cannot read"])
        found = problems(workflow(job(steps="- uses: ./.github/actions/x\n")))
        self.assertEqual(found, [f"{WORKFLOW}: job check, step 1 (./.github/actions/x): uses a local action, which this "
                                 "guard cannot read"])


class TriggerTests(unittest.TestCase):
    def test_a_workflow_main_pushes_start_must_run_on_pull_requests_to_main(self):
        cases = {
            "no pull_request": ("on:\n  push:\n    branches: [main]\n",
                                "runs on a push to main but not on pull requests (`pull_request`)"),
            "other branches": ("on:\n  pull_request:\n    branches: [dev]\n  push:\n    branches: [main]\n",
                               "its `pull_request` branch filter leaves out main"),
            "ignored": ("on:\n  pull_request:\n    branches-ignore: ['ma*']\n  push:\n    branches: [main]\n",
                        "its `pull_request` branches-ignore leaves out main"),
            "types": ("on:\n  pull_request:\n    types: [opened]\n  push:\n    branches: [main]\n",
                      "its `pull_request` types leave out one of opened, synchronize, reopened"),
            "narrower paths": ("on:\n  pull_request:\n    paths: [a/**]\n  push:\n    branches: [main]\n",
                               "its `pull_request` paths filter is not the push's own"),
            "target only": ("on:\n  pull_request_target:\n  push:\n    branches: [main]\n",
                            "runs on a push to main but not on pull requests (`pull_request`)"),
        }
        for name, (on, message) in cases.items():
            with self.subTest(name):
                self.assertEqual(problems(workflow(job(), on=on)), [f"{WORKFLOW}: {message}"])

    def test_broader_or_equal_pull_request_triggers_pass(self):
        for on in ("on: [push, pull_request]\n",
                   "on:\n  pull_request:\n  push:\n    branches: [main]\n    paths: [a/**]\n",
                   "on:\n  pull_request:\n    paths: [a/**]\n  push:\n    branches: [main]\n    paths: [a/**]\n",
                   "on:\n  pull_request:\n    branches: ['**', '!dev']\n  push:\n    branches: [main]\n"):
            with self.subTest(on):
                self.assertEqual(problems(workflow(job(), on=on)), [])

    def test_a_globstar_also_matches_no_directory(self):
        on = "on:\n  push:\n    branches: ['**/main']\n"
        self.assertEqual(problems(workflow(job(), on=on)),
                         [f"{WORKFLOW}: runs on a push to main but not on pull requests (`pull_request`)"])
        self.assertTrue(parity.branch_matches(["release/**"], "release/beta"))
        self.assertFalse(parity.branch_matches(["release/*"], "release/a/b"))
        with self.assertRaises(parity.ReadError):
            parity.branch_matches(["ma?n"], "main")

    def test_workflows_main_pushes_do_not_start_are_not_checked(self):
        for on in ("on:\n  push:\n    tags: ['v*']\n", "on:\n  schedule:\n    - cron: '1 2 * * *'\n",
                   "on:\n  push:\n    branches: [dev]\n", "on:\n  push:\n    branches-ignore: [main]\n"):
            with self.subTest(on):
                text = workflow(job("publish", "if: github.event_name != 'pull_request'\n"), on=on,
                                concurrency="concurrency:\n  group: x\n  cancel-in-progress: true\n")
                self.assertEqual(problems(text), [])


class ConcurrencyTests(unittest.TestCase):
    def test_a_push_run_on_main_is_never_cancelled_or_replaced(self):
        replaced = (f"{WORKFLOW}: on a push to main the concurrency group is not unique to the run (put github.run_id "
                    "in it), so a newer run replaces a waiting one")
        cancelled = f"{WORKFLOW}: `cancel-in-progress` can be true on a push to main"
        cases = {
            "the old ci.yml form": ("group: ci-${{ github.event.pull_request.number || github.ref }}\n"
                                    "cancel-in-progress: true\n", [cancelled, replaced]),
            "a static group without cancelling": ("group: release\ncancel-in-progress: false\n", [replaced]),
            "a group per commit": ("group: w-${{ github.sha }}\n", [replaced]),
            "cancelling decided by the run": ("group: w-${{ github.run_id }}\ncancel-in-progress: ${{ vars.CANCEL }}\n",
                                              [cancelled]),
        }
        for name, (body, expected) in cases.items():
            with self.subTest(name):
                text = workflow(job(), concurrency="concurrency:\n" + textwrap.indent(body, "  "))
                self.assertEqual(problems(text), expected)
        self.assertEqual(problems(workflow(job(), concurrency="concurrency: static\n")), [replaced])
        self.assertEqual(problems(workflow(job(), concurrency="")), [])

    def test_groups_within_a_run_or_of_a_pull_request_may_not_meet_a_push_run(self):
        shared = job("a", "concurrency: jobs-${{ github.run_id }}\n") + job("b", "concurrency: jobs-${{ github.run_id }}\n")
        self.assertEqual(problems(workflow(shared)), [
            f"{WORKFLOW}: job b: shares its concurrency group with {WORKFLOW}: job a in the same run"])
        matrix = job(lines="strategy:\n  matrix:\n    n: [1, 2]\nconcurrency: check-${{ github.run_id }}\n")
        self.assertEqual(problems(workflow(matrix)), [
            f"{WORKFLOW}: job check: every job of its matrix takes the same concurrency group on a push to main"])
        titled = ("concurrency:\n  group: w-${{ github.event_name == 'pull_request' && github.event.pull_request.title "
                  "|| github.run_id }}\n  cancel-in-progress: ${{ github.event_name == 'pull_request' }}\n")
        self.assertEqual(problems(workflow(job(), concurrency=titled)), [
            f"{WORKFLOW}: a pull_request run's group ({WORKFLOW}) may equal its group on a push to main; give them "
            "different fixed text"])
        cased = job("a", "concurrency: TASK-${{ github.run_id }}\n") + job("b", "concurrency: task-${{ github.run_id }}\n")
        self.assertEqual(problems(workflow(cased)), [
            f"{WORKFLOW}: job b: shares its concurrency group with {WORKFLOW}: job a in the same run"],
            "group names compare ignoring case")
        decided = ("name: O\non:\n  pull_request:\nconcurrency:\n  group: ${{ github.run_id > 0 && format('w-{0}', "
                   "github.event.pull_request.title) || format('pr-{0}', github.run_id) }}\n  cancel-in-progress: true\n"
                   "jobs:\n" + textwrap.indent(job(), "  "))
        self.assertEqual(parity.findings([(WORKFLOW, workflow(job())), (".github/workflows/o.yml", decided)], {}), [
            f"{WORKFLOW}: a pull_request run's group (.github/workflows/o.yml) may equal its group on a push to main; "
            "give them different fixed text"], "a decision on the run id is not taken")
        unreachable = ("name: O\non:\n  pull_request:\njobs:\n" + textwrap.indent(
            job("never", "if: false\nconcurrency: w-${{ github.event.pull_request.title }}\n"), "  "))
        self.assertEqual(parity.findings([(WORKFLOW, workflow(job())), (".github/workflows/o.yml", unreachable)], {}), [])
        other = ("name: O\non:\n  pull_request_target:\nconcurrency:\n  group: w-run-${{ github.event.pull_request.number }}\n"
                 "  cancel-in-progress: true\njobs:\n" + textwrap.indent(job(), "  "))
        self.assertEqual(parity.findings([(WORKFLOW, workflow(job())), (".github/workflows/o.yml", other)], {}), [
            f"{WORKFLOW}: a pull_request_target run's group (.github/workflows/o.yml) may equal its group on a push to "
            "main; give them different fixed text"])

    def test_job_concurrency_counts_too(self):
        found = problems(workflow(job(lines="concurrency:\n  group: deploy\n")))
        self.assertEqual(found, [f"{WORKFLOW}: job check: on a push to main the concurrency group is not unique to the "
                                 "run (put github.run_id in it), so a newer run replaces a waiting one"])

    def test_the_repository_workflows_cancel_only_a_pull_requests_older_runs(self):
        for path, text in parity.workflow_files(ROOT):
            spec = parity.read_yaml(text)
            if not parity.pushes_to_main(parity.triggers(spec)):
                continue
            with self.subTest(path):
                group = spec["concurrency"]["group"]
                cancel = spec["concurrency"].get("cancel-in-progress", False)
                self.assertIn(parity.RUN_ID, parity.template(group, parity.PUSH_TO_MAIN))
                self.assertIs(parity._truthy(parity.template(cancel, parity.PUSH_TO_MAIN)), False)


class AllowlistTests(unittest.TestCase):
    PUBLISH = job("check") + job("publish", "if: github.event_name == 'push'\n")

    def test_an_allowed_side_effect_job_passes_with_its_reason(self):
        self.assertEqual(problems(workflow(self.PUBLISH), {(WORKFLOW, "publish"): "submits the dependency graph"}), [])

    def test_an_entry_needs_a_reason_and_a_job_to_exempt(self):
        self.assertEqual(problems(workflow(self.PUBLISH), {(WORKFLOW, "publish"): " "}),
                         [f"SIDE_EFFECT_JOBS[{WORKFLOW}, publish]: give the reason"])
        self.assertEqual(problems(workflow(job()), {(WORKFLOW, "gone"): "was removed"}),
                         [f"SIDE_EFFECT_JOBS[{WORKFLOW}, gone]: names no job that runs on a push to main; remove it"])
        self.assertEqual(problems(workflow(job()), {(WORKFLOW, "check"): "runs everywhere"}),
                         [f"{WORKFLOW}: job check: runs on pull requests as on a push; remove it from SIDE_EFFECT_JOBS"])

    def test_an_allowed_job_is_still_never_cancelled_on_a_push(self):
        text = workflow(job("publish", "if: github.event_name == 'push'\nconcurrency:\n  group: publish\n"))
        self.assertEqual(problems(text, {(WORKFLOW, "publish"): "publishes"}),
                         [f"{WORKFLOW}: job publish: on a push to main the concurrency group is not unique to the run "
                          "(put github.run_id in it), so a newer run replaces a waiting one"])


class MainTests(unittest.TestCase):
    def test_the_repository_passes(self):
        lines = []
        self.assertEqual(parity.main([], ROOT, out=lines.append), 0, "\n".join(lines))

    def test_a_violation_fails_and_an_unreadable_workflow_is_a_violation(self):
        with tempfile.TemporaryDirectory() as scratch:
            root = Path(scratch)
            (root / parity.WORKFLOWS).mkdir(parents=True)
            (root / parity.WORKFLOWS / "w.yml").write_text(workflow(job()))
            lines = []
            self.assertEqual(parity.main([], root, {}, lines.append), 0, lines)
            (root / parity.WORKFLOWS / "bad.yml").write_text("on:\n  push: &x\n")
            lines = []
            self.assertEqual(parity.main([], root, {}, lines.append), 1)
            self.assertTrue(lines[0].startswith(".github/workflows/bad.yml: cannot be read by this guard"), lines)


if __name__ == "__main__":
    unittest.main()
