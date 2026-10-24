# SPDX-License-Identifier: Apache-2.0
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import check_onramp
from check_docs import Repo

FENCE = "`" * 3
SCALA = (
    "package x\n"
    "object Rules:\n"
    "  def minThing(a: Int): Int = a\n"
    "  enum Mode:\n"
    "    case First, Second\n"
    "class Helper\n"
)
OTHER = "package y\nobject Elsewhere:\n  def minThing = 1\n"
ADR_ONE = "# 0001. First decision\n\n- **Status**: Accepted\n- **Date**: 2026-10-15\n"
ADR_README = "| [0001](0001-first.md) | First decision | Accepted | 2026-10-15 |\n"
GLOSSARY_HEAD = "| Term | Meaning | Where defined |\n|---|---|---|\n"
QUESTIONS_HEAD = "| Question | File and symbol | Decision | Open issue |\n|---|---|---|---|\n"
DECISIONS_HEAD = "| Decision | Where recorded | Invariant it fixes | Reopen condition |\n|---|---|---|---|\n"


def build(files: dict[str, str]):
    tmp = tempfile.TemporaryDirectory()
    for name, content in files.items():
        path = Path(tmp.name) / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")
    return tmp, check_onramp.Index(Repo.discover(Path(tmp.name)))


class OnrampCase(unittest.TestCase):
    base = {"a/src/Rules.scala": SCALA, "b/src/Other.scala": OTHER, "docs/guide.md": "# Guide\n## Part one\n"}

    def index(self, extra: dict[str, str]) -> check_onramp.Index:
        tmp, index = build({**self.base, **extra})
        self.addCleanup(tmp.cleanup)
        return index

    def messages(self, problems: list[check_onramp.Problem]) -> list[str]:
        return [p.message for p in problems]


class PointerTest(OnrampCase):
    def cell(self, text: str, scoped: bool = True) -> list[str]:
        index = self.index({})
        return self.messages(check_onramp.check_pointer_cell(index, "docs/q.md", 3, text, scoped=scoped))

    def test_paths_symbols_and_members_resolve(self):
        self.assertEqual(self.cell("`Rules.scala`, `Rules`, `Rules.minThing`, `Rules.Mode`, `Mode.Second`"), [])
        self.assertEqual(self.cell("`a/src/Rules.scala`, `Helper`", scoped=True), [])
        self.assertEqual(self.cell("`docs/guide.md#part-one`"), [])

    def test_a_missing_path_symbol_member_or_anchor_is_reported(self):
        self.assertEqual(len(self.cell("`Missing.scala`")), 1)
        self.assertEqual(len(self.cell("`a/src/Gone.scala`")), 1)
        self.assertEqual(len(self.cell("`Nothing`")), 1)
        self.assertEqual(len(self.cell("`Rules.absent`")), 1)
        self.assertEqual(len(self.cell("`docs/guide.md#nope`")), 1)

    def test_a_symbol_must_be_defined_in_the_file_the_cell_cites(self):
        self.assertEqual(self.cell("`Other.scala`, `Elsewhere`"), [])
        self.assertEqual(len(self.cell("`Other.scala`, `Helper`")), 1)
        self.assertEqual(self.cell("`Other.scala`, `Helper`", scoped=False), [])

    def test_a_basename_in_two_places_is_ambiguous(self):
        index = self.index({"c/src/Rules.scala": OTHER})
        found = check_onramp.check_pointer_cell(index, "docs/q.md", 1, "`Rules.scala`", scoped=True)
        self.assertEqual(len(found), 1)
        self.assertIn("2 tracked files", found[0].message)

    def test_line_numbers_and_unclassifiable_spans_are_reported(self):
        self.assertEqual(len(self.cell("`Rules.scala:12`")) >= 1, True)
        self.assertEqual(len(self.cell("`some thing!`")), 0)
        self.assertEqual(len(self.cell("`$$$`")), 1)

    def test_a_command_checks_its_repo_paths_only(self):
        self.assertEqual(self.cell("`bash a/src/Rules.scala --flag <x>`"), [])
        self.assertEqual(len(self.cell("`bash a/src/Missing.scala`")), 1)

    def test_a_link_in_a_cell_must_resolve(self):
        self.assertEqual(self.cell("[g](guide.md#part-one)"), [])
        self.assertEqual(len(self.cell("[g](guide.md#nope)")), 1)


class TableTest(OnrampCase):
    def test_glossary_rows_need_a_resolving_pointer(self):
        good = GLOSSARY_HEAD + "| Rule | A rule | `Rules` |\n"
        self.assertEqual(check_onramp.check_glossary(self.index({"docs/glossary.md": good})), [])
        bad = GLOSSARY_HEAD + "| Rule | A rule | `Nothing` |\n| Other | text | prose only |\n| Rule | dup | `Rules` |\n"
        found = self.messages(check_onramp.check_glossary(self.index({"docs/glossary.md": bad})))
        self.assertEqual(len(found), 3)

    def test_glossary_reads_every_table_with_the_header(self):
        two = GLOSSARY_HEAD + "| A | one | `Rules` |\n\ntext\n\n" + GLOSSARY_HEAD + "| B | two | `Nothing` |\n"
        found = check_onramp.check_glossary(self.index({"docs/glossary.md": two}))
        self.assertEqual(len(found), 1)

    def test_a_missing_document_or_header_is_reported(self):
        self.assertEqual(self.messages(check_onramp.check_glossary(self.index({}))), ["missing"])
        found = check_onramp.check_glossary(self.index({"docs/glossary.md": "| X | Y |\n|---|---|\n| 1 | 2 |\n"}))
        self.assertIn("not found", found[0].message)

    def rows(self, n: int) -> str:
        return QUESTIONS_HEAD + "".join(f"| Q{i}? | `Rules.scala`, `Rules` | — | — |\n" for i in range(n))

    def test_the_question_count_is_bounded(self):
        for n, ok in ((29, False), (30, True), (50, True), (51, False)):
            found = check_onramp.check_questions(self.index({"docs/questions.md": self.rows(n)}))
            self.assertEqual(found == [], ok, n)

    def test_question_cells_are_checked(self):
        rows = self.rows(30) + "| Q99? | `Rules`, `Helper` | [g](guide.md#nope) | open |\n"
        found = self.messages(check_onramp.check_questions(self.index({"docs/questions.md": rows})))
        self.assertEqual(len(found), 2)
        ok = self.rows(30) + "| Q99? | `Rules` | [g](guide.md) | #12, #13 |\n"
        self.assertEqual(check_onramp.check_questions(self.index({"docs/questions.md": ok})), [])
        link = self.rows(30) + "| Q98? | `Rules` | — | [#5](https://github.com/KD5UJC-XYZ/scarab/issues/5) |\n"
        self.assertEqual(check_onramp.check_questions(self.index({"docs/questions.md": link})), [])

    def test_decisions_need_one_row_per_adr(self):
        adr = {"docs/adr/0001-first.md": ADR_ONE}
        row = "| First decision | [ADR 0001](adr/0001-first.md) | invariant | None recorded. |\n"
        good = DECISIONS_HEAD + row
        self.assertEqual(check_onramp.check_decisions(self.index({**adr, "docs/decisions.md": good})), [])
        missing = check_onramp.check_decisions(self.index({**adr, "docs/decisions.md": DECISIONS_HEAD}))
        self.assertIn("ADR 0001", missing[0].message)
        twice = check_onramp.check_decisions(self.index({**adr, "docs/decisions.md": good + row.replace("None recorded.", "n/a")}))
        self.assertTrue(any("exactly one" in m for m in self.messages(twice)))
        retitled = DECISIONS_HEAD + row.replace("First decision", "Renamed")
        self.assertTrue(check_onramp.check_decisions(self.index({**adr, "docs/decisions.md": retitled})))

    def test_decision_rows_may_have_no_reopen_condition_only_when_stated(self):
        blank = DECISIONS_HEAD + "| Thing | [g](guide.md) | invariant |  |\n"
        found = check_onramp.check_decisions(self.index({"docs/decisions.md": blank}))
        self.assertTrue(found)

    def test_the_adr_index_lists_every_record_with_its_date(self):
        adr = {"docs/adr/0001-first.md": ADR_ONE}
        self.assertEqual(check_onramp.check_adr_index(self.index({**adr, "docs/adr/README.md": ADR_README})), [])
        wrong = ADR_README.replace("2026-10-15", "2026-10-16")
        self.assertTrue(check_onramp.check_adr_index(self.index({**adr, "docs/adr/README.md": wrong})))
        self.assertTrue(check_onramp.check_adr_index(self.index({**adr, "docs/adr/README.md": "| x |\n"})))


class PlaybookTest(OnrampCase):
    cli = 'sub.add_parser("e2e")\nsub.add_parser("down")\n'
    files = {"scripts/devnet/cli.py": cli, "scripts/devnet.py": "x = 1\n", "scripts/real.py": "x = 1\n",
             "docs/playbooks/README.md": "[one](one.md)\n"}

    def run_playbook(self, text: str, extra: dict[str, str] | None = None) -> list[str]:
        index = self.index({**self.files, "docs/playbooks/one.md": text, **(extra or {})})
        return self.messages(check_onramp.check_playbooks(index))

    def test_a_valid_playbook_passes(self):
        text = f"# One\n\n{FENCE}sh\npython3 scripts/real.py\npython3 scripts/devnet.py --name mine e2e\n{FENCE}\n"
        self.assertEqual(self.run_playbook(text), [])

    def test_fenced_paths_and_scenarios_are_checked(self):
        text = f"# One\n{FENCE}sh\npython3 scripts/gone.py\npython3 scripts/devnet.py --name mine nonsense\n{FENCE}\n"
        found = self.run_playbook(text)
        self.assertEqual(len(found), 2)

    def test_quoted_arguments_flags_and_absolute_paths_are_not_paths(self):
        text = f'# One\n{FENCE}sh\nscripts/real.py "ledger/testOnly x" --out /tmp/x $out/y <z>\n{FENCE}\n'
        self.assertEqual(self.run_playbook(text), [])

    def test_the_line_limit_and_the_index_are_enforced(self):
        long = "# One\n" + "line\n" * 60
        self.assertTrue(any("lines" in m for m in self.run_playbook(long)))
        found = self.run_playbook("# One\n", {"docs/playbooks/README.md": "nothing\n"})
        self.assertTrue(any("does not link" in m for m in found))


class TemplateAndStartTest(OnrampCase):
    issue = "# SPDX-License-Identifier: Apache-2.0\nbody:\n" + "".join(
        f"  - type: textarea\n    id: {f}\n" for f in check_onramp.AUDIT_FIELDS)
    config = "contact_links:\n  - url: https://x/security/advisories/new\n"
    pr = "## Docs rows touched\n- Codemap rows:\n- Compatibility rows:\n"

    def templates(self, **over: str) -> list[str]:
        files = {".github/ISSUE_TEMPLATE/aud.yml": self.issue, ".github/ISSUE_TEMPLATE/config.yml": self.config,
                 ".github/pull_request_template.md": self.pr}
        files.update({k.replace("__", "/").replace("_yml", ".yml"): v for k, v in over.items()})
        return self.messages(check_onramp.check_templates(self.index(files)))

    def test_the_default_template_has_the_audit_shape(self):
        self.assertEqual(self.templates(), [])
        self.assertTrue(self.templates(**{".github__ISSUE_TEMPLATE__aud_yml": "body:\n"}))

    def test_the_audit_template_sorts_first_and_the_security_link_stays(self):
        self.assertTrue(any("sorts after" in m for m in
                            self.templates(**{".github__ISSUE_TEMPLATE__a_yml": "name: x\n"})))
        self.assertTrue(any("security" in m for m in self.templates(**{".github__ISSUE_TEMPLATE__config_yml": ""})))
        self.assertTrue(self.templates(**{".github__pull_request_template.md": "## What\n"}))

    def start(self, text: str) -> list[str]:
        return self.messages(check_onramp.check_start_block(self.index({"AGENTS.md": text})))

    block = ("# Instructions\n\n**Five-minute start**\n\n1. docs/codemap.md\n2. scripts/status.py\n3. docs/roadmap.md\n"
             "4. docs/decisions.md\n5. docs/playbooks/\n6. docs/playbooks/run-the-gates.md\n\nIntro paragraph.\n")

    def test_the_start_block_is_short_complete_and_first(self):
        self.assertEqual(self.start(self.block), [])
        self.assertTrue(self.start(self.block.replace("scripts/status.py", "x")))
        self.assertTrue(self.start(self.block.replace("1. docs/codemap.md\n", "1. docs/codemap.md\n" + "7. more\n" * 8)))
        self.assertTrue(self.start("# Instructions\n\nIntro first.\n\n" + self.block.split("\n\n", 1)[1]))
        self.assertTrue(self.start("# Instructions\n"))


class RunTest(OnrampCase):
    def test_run_reports_every_selected_check(self):
        tmp, index = build({})
        self.addCleanup(tmp.cleanup)
        problems = check_onramp.run(index.repo, ["glossary", "questions"])
        self.assertEqual([p.path for p in problems], ["docs/glossary.md", "docs/questions.md"])

    def test_the_real_repository_passes(self):
        repo = Repo.discover(check_onramp.ROOT)
        self.assertEqual([p.render() for p in check_onramp.run(repo, list(check_onramp.CHECKS))], [])


if __name__ == "__main__":
    unittest.main()
