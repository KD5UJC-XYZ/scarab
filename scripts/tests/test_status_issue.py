# SPDX-License-Identifier: Apache-2.0
import sys
import unittest
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import status

ROOT = Path(__file__).resolve().parents[2]
NOW = datetime(2026, 10, 29, tzinfo=timezone.utc)
DECISIONS = ("| Date | Issue | Decision | Invariant | Reopen when | Record |\n|---|---|---|---|---|---|\n"
             "| 2026-10-28 | [#567](https://github.com/o/r/issues/567) | Peer input never stops the node. | x | none | r |\n"
             "| 2026-10-25 | [#474](https://github.com/o/r/issues/474) | ~~The node stops on a defect.~~ | | | r |\n")
PLAYBOOKS = ("| Task | Playbook | Areas |\n|---|---|---|\n| Ledger | [add-a-ledger-predicate.md](add-a-ledger-predicate.md) | ledger |\n"
             "| Gates | [run-the-gates-to-ready.md](run-the-gates-to-ready.md) | any |\n")


def issue(number, *labels, state="open", body="", milestone="reference-parity"):
    return {"number": number, "title": f"Issue {number}", "state": state, "body": body,
            "labels": [{"name": l} for l in labels], "updated_at": "2026-10-28T00:00:00Z",
            "milestone": {"title": milestone} if milestone else None}


class IssueBriefCase(unittest.TestCase):
    def brief(self, raw, roadmap=None):
        return status.issue_brief(raw, roadmap or {}, DECISIONS, PLAYBOOKS, NOW)

    def test_an_issue_with_its_roadmap_id_decisions_and_playbooks(self):
        text = self.brief(issue(567, "area:ledger", "priority:P1", "status:in-progress"), {567: "B2"})
        self.assertIn("roadmap: B2", text)
        self.assertIn("2026-10-28: Peer input never stops the node.", text)
        self.assertIn("playbooks: docs/playbooks/add-a-ledger-predicate.md, docs/playbooks/run-the-gates-to-ready.md", text)
        self.assertIn("in progress: someone else is on it", text)
        self.assertIn("suggested level: 3 if it changes rules", text)
        self.assertIn("triage: clean", text)

    def test_a_follow_up_without_a_roadmap_id_names_the_ids_of_the_issues_it_cites(self):
        text = self.brief(issue(600, "area:network", "priority:P2", "status:backlog", body="Follow-up of #51 and #9."),
                          {51: "K4"})
        self.assertIn("roadmap: none; the issues it cites: K4 (#51)", text)
        self.assertIn("playbooks: docs/playbooks/run-the-gates-to-ready.md", text)

    def test_a_superseded_decision_is_marked_and_triage_problems_are_shown(self):
        text = self.brief(issue(474, "bug", milestone=None))
        self.assertIn("2026-10-25 (superseded): The node stops on a defect.", text)
        self.assertIn("triage: missing an area, priority or status label", text)
        self.assertIn("suggested level: unknown (no area label)", text)

    def test_a_closed_issue_is_not_called_in_progress_and_has_no_triage(self):
        text = self.brief(issue(603, "area:node", "status:in-progress", state="closed"))
        self.assertNotIn("in progress:", text)
        self.assertIn("triage: clean", text)

    def test_levels_follow_the_areas(self):
        self.assertTrue(status.suggested_level(["docs"]).startswith("0 "))
        self.assertTrue(status.suggested_level(["scripts", "build"]).startswith("1 "))
        self.assertTrue(status.suggested_level(["node"]).startswith("1 for a refactor"))

    def test_every_playbook_file_is_in_the_table_with_known_areas(self):
        text = (ROOT / "docs/playbooks/README.md").read_text(encoding="utf-8")
        rows = {m.group(2): {a.strip() for a in m.group(3).split(",")}
                for m in map(status.PLAYBOOK_ROW.match, text.splitlines()) if m}
        files = {p.name for p in (ROOT / "docs/playbooks").glob("*.md")} - {"README.md"}
        self.assertEqual(set(rows), files)
        for name, areas in rows.items():
            with self.subTest(playbook=name):
                self.assertTrue(areas <= set(status.AREAS) | {"any"}, areas)


if __name__ == "__main__":
    unittest.main()
