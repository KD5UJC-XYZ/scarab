# SPDX-License-Identifier: Apache-2.0
"""Tests of the Grafana dashboard generator and the pool alert rules (`ops/monitoring`): the generated JSON is the
committed JSON, ids are unique, every panel has a query, and every metric a panel or a rule reads is one the node
exports (`Catalogue.scala`), the rotation driver writes (`rotate_kes.py`), or a standard exporter does.

Run from the repository root:  python3 -m unittest discover -s scripts/tests -v
"""
import importlib.util
import json
import pathlib
import re
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
GRAFANA = ROOT / "ops" / "monitoring" / "server" / "grafana"
RULES = ROOT / "ops" / "monitoring" / "server" / "prometheus" / "rules" / "pool.yml"
CATALOGUE = ROOT / "node" / "src" / "main" / "scala" / "xyz" / "kd5ujc" / "scarab" / "node" / "metrics" / "Catalogue.scala"

spec = importlib.util.spec_from_file_location("build_dashboards", GRAFANA / "build_dashboards.py")
bd = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bd)
spec = importlib.util.spec_from_file_location("rotate_kes", ROOT / "scripts" / "pool" / "rotate_kes.py")
rk = importlib.util.module_from_spec(spec)
spec.loader.exec_module(rk)

NODE_METRIC = re.compile(r"\b((?:cardano_node_metrics|scarab)_[A-Za-z0-9_]+)")
STANDARD = {"up", "process_start_time_seconds", "ALERTS"}


def known_metrics():
    names = set(re.findall(r'MetricName\("([A-Za-z0-9_:]+)"\)', CATALOGUE.read_text()))
    names |= {rk.METRIC_PREFIX + n for n in (rk.LAST_RUN, rk.LAST_SUCCESS, rk.LAST_ROTATION, rk.LAST_STATUS, rk.REMAINING)}
    # a histogram is read through its _bucket, _sum and _count series
    return names | {n + s for n in names for s in ("_bucket", "_sum", "_count")}


def exprs(dashboard):
    return [(p["title"], t["expr"]) for p in dashboard["panels"] for t in p.get("targets", [])]


class DashboardTest(unittest.TestCase):
    dashboards = {"scarab-runs": bd.runs_dashboard(), "scarab-pool": bd.pool_dashboard()}

    def test_the_committed_json_is_what_the_generator_writes(self):
        for name, dashboard in self.dashboards.items():
            committed = json.loads((GRAFANA / "dashboards" / f"{name}.json").read_text())
            self.assertEqual(committed, json.loads(json.dumps(dashboard)), f"{name}.json is stale: rerun build_dashboards.py")

    def test_ids_are_unique_and_the_grid_fits(self):
        for name, d in self.dashboards.items():
            ids = [p["id"] for p in d["panels"]]
            self.assertEqual(len(ids), len(set(ids)), name)
            for p in d["panels"]:
                g = p["gridPos"]
                self.assertLessEqual(g["x"] + g["w"], 24, f"{name}: {p['title']}")

    def test_every_data_panel_has_a_query_and_a_datasource(self):
        for name, d in self.dashboards.items():
            for p in d["panels"]:
                if p["type"] != "row":
                    self.assertTrue(p["targets"] and all(t["expr"] for t in p["targets"]), f"{name}: {p['title']}")
                    self.assertIn("uid", p["datasource"])

    def test_pool_dashboard_reads_only_known_metrics(self):
        known = known_metrics()
        for title, expr in exprs(self.dashboards["scarab-pool"]):
            for metric in NODE_METRIC.findall(expr):
                self.assertIn(metric, known, f"{title}: {metric}")

    def test_pool_dashboard_has_the_panels_the_issue_asks_for(self):
        titles = {p["title"] for p in self.dashboards["scarab-pool"]["panels"]}
        for wanted in ("Blocks forged (range)", "Blocks adopted (range)", "Blocks lost (range)", "Forge failures (range)",
                       "Leader slots checked and won (per hour)", "KES periods remaining", "Certificate counter",
                       "Certificate start period", "Certificate expiry period", "Last success", "Last exit status",
                       "Slot lag behind the peers' tip", "Peers hot", "Phase", "Stalled"):
            self.assertIn(wanted, titles)


class RulesTest(unittest.TestCase):
    """promtool checks the rule file's syntax and behaviour (docs/running.md, "Pool monitoring"); here the names."""

    text = RULES.read_text()
    code = "\n".join(l for l in text.splitlines() if not l.lstrip().startswith("#"))

    def test_rules_read_only_known_metrics(self):
        known = known_metrics()
        for metric in NODE_METRIC.findall(self.code):
            self.assertIn(metric, known)

    def test_every_alert_has_a_severity_and_no_receiver(self):
        alerts = re.findall(r"- alert: (\w+)", self.text)
        self.assertGreaterEqual(len(alerts), 15)
        self.assertEqual(len(re.findall(r"labels: \{severity: (?:info|warning|critical)\}", self.text)), len(alerts))
        for forbidden in ("receiver", "webhook", "http://", "https://", "@"):
            self.assertNotIn(forbidden, self.code)


if __name__ == "__main__":
    unittest.main()
