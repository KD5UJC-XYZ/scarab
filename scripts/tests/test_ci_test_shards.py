# SPDX-License-Identifier: Apache-2.0
from pathlib import Path
import json
import re
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import ci_test_shards as shards

ROOT = Path(__file__).resolve().parents[2]

INVENTORY = "\n".join([
    "codec\tcodec\tx.codec.ASuite",
    "codec\tcodec\tx.codec.BSuite",
    "node\tnode\tx.node.HeavySuite",
    "node\tnode\tx.node.LightSuite",
    "node\tnode\tx.node.OtherSuite",
    "node\tnode\tx.storage.CodecSuite",
    "modelsGen\tmodels-gen\tx.gen.GenSuite",
    "tools\ttools",
]) + "\n"
WEIGHTS = {"shards": 3, "modules": {"codec": 10, "node": 100, "modelsGen": 5, "tools": 1},
           "split": {"node": {"x.node.HeavySuite": 60}}}


def plan(inventory=INVENTORY, weights=None):
    return shards.make_plan(shards.read_inventory(inventory), shards.read_weights(weights or WEIGHTS))


def report(suite, tests=2, failures=0, errors=0):
    return (f"<?xml version='1.0' encoding='UTF-8'?>\n<testsuite name=\"{suite}\" tests=\"{tests}\" "
            f"failures=\"{failures}\" errors=\"{errors}\" skipped=\"0\" time=\"0.1\"></testsuite>\n")


class InventoryTests(unittest.TestCase):
    def test_reads_modules_in_order_with_a_module_without_suites(self):
        modules = shards.read_inventory(INVENTORY)
        self.assertEqual([m.name for m in modules], ["codec", "node", "modelsGen", "tools"])
        self.assertEqual(modules[2].directory, "models-gen")
        self.assertEqual(modules[3].suites, ())

    def test_refuses_what_testOnly_would_not_take_as_one_suite(self):
        for line in ("node\tnode\tx.node.*", "node\tnode\tx.node.A Suite", "node\tnode\t", "node", "node\t\tx.A",
                     "node\tnode\tx.A\textra"):
            with self.subTest(line=line):
                with self.assertRaises(shards.ShardError):
                    shards.read_inventory(line + "\n")

    def test_refuses_a_suite_twice_and_a_module_in_two_directories(self):
        with self.assertRaisesRegex(shards.ShardError, "2 times"):
            shards.read_inventory("node\tnode\tx.A\nnode\tnode\tx.A\n")
        with self.assertRaisesRegex(shards.ShardError, "two directories"):
            shards.read_inventory("node\tnode\tx.A\nnode\tother\tx.B\n")
        with self.assertRaisesRegex(shards.ShardError, "no module"):
            shards.read_inventory("")


class WeightsTests(unittest.TestCase):
    def test_refuses_malformed_weights(self):
        for data in ([], {"shards": 0, "modules": {}}, {"shards": True, "modules": {}},
                     {"shards": 2, "modules": {"a": -1}}, {"shards": 2, "modules": {"a": "1"}},
                     {"shards": 2, "modules": {"a": 1}, "split": {"b": {}}},
                     {"shards": 2, "modules": {"a": 1}, "split": {"a": {"x.S": None}}}):
            with self.subTest(data=data):
                with self.assertRaises(shards.ShardError):
                    shards.read_weights(data)

    def test_refuses_weights_that_do_not_match_the_inventory(self):
        inventory = shards.read_inventory(INVENTORY)
        cases = {
            "module tools has no weight": dict(WEIGHTS, modules={"codec": 1, "node": 1, "modelsGen": 1}),
            "weighs module gone, which sbt does not report": dict(WEIGHTS, modules=dict(WEIGHTS["modules"], gone=1)),
            "weighs suite x.node.Renamed of node": dict(WEIGHTS, split={"node": {"x.node.Renamed": 1}}),
        }
        for message, data in cases.items():
            with self.subTest(message=message):
                with self.assertRaisesRegex(shards.ShardError, re.escape(message)):
                    shards.assign(inventory, shards.read_weights(data))


class PlanTests(unittest.TestCase):
    def test_every_suite_is_in_exactly_one_shard(self):
        result = plan()
        ran = [tuple(pair) for shard in result["shards"] for pair in shard["suites"]]
        expected = [(m.name, s) for m in shards.read_inventory(INVENTORY) for s in m.suites]
        self.assertEqual(sorted(ran), sorted(expected), "each suite once, none missing, none extra")

    def test_whole_modules_run_as_check_runs_them_and_split_ones_by_suite(self):
        result = plan()
        commands = [c for shard in result["shards"] for c in shard["commands"]]
        for module in ("codec", "modelsGen", "tools"):
            self.assertEqual(commands.count(f"{module}/Test/test"), 1, f"{module} runs whole, once")
        node = [c for c in commands if c.startswith("node/")]
        self.assertTrue(all(c.startswith("node/Test/testOnly ") for c in node), node)
        named = sorted(s for c in node for s in c.split(" ")[1:])
        self.assertEqual(named, ["x.node.HeavySuite", "x.node.LightSuite", "x.node.OtherSuite", "x.storage.CodecSuite"])

    def test_heaviest_first_to_the_lightest_shard_and_unnamed_suites_share_the_rest(self):
        result = plan()
        # node's three unnamed suites share 100 - 60 seconds: 13.3 each.
        self.assertEqual([s["seconds"] for s in result["shards"]], [60.0, 27.7, 28.3])
        self.assertEqual(result["shards"][0]["commands"], ["node/Test/testOnly x.node.HeavySuite"])
        self.assertEqual(result["shards"][2]["commands"],
                         ["codec/Test/test", "node/Test/testOnly x.node.OtherSuite", "modelsGen/Test/test"])

    def test_the_same_inputs_give_the_same_plan(self):
        self.assertEqual(json.dumps(plan()), json.dumps(plan()))

    def test_a_new_suite_of_a_split_module_lands_in_a_shard_without_an_edit(self):
        result = plan(INVENTORY + "node\tnode\tx.node.NewSuite\n")
        holders = [s["shard"] for s in result["shards"] if ["node", "x.node.NewSuite"] in s["suites"]]
        self.assertEqual(len(holders), 1)

    def test_partition_check_refuses_a_missing_duplicate_or_unknown_suite(self):
        inventory = shards.read_inventory("a\ta\tx.A\na\ta\tx.B\n")
        cases = {
            "suite x.B of a is in no shard": [[("a", "x.A")], []],
            "suite x.A of a is in 2 shards": [[("a", "x.A")], [("a", "x.A"), ("a", "x.B")]],
            "suite x.C of a is in a shard but not in the inventory": [[("a", "x.A"), ("a", "x.B"), ("a", "x.C")]],
        }
        for message, assignment in cases.items():
            with self.subTest(message=message):
                with self.assertRaisesRegex(shards.ShardError, re.escape(message)):
                    shards.check_partition(inventory, assignment)

    def test_refuses_commands_that_run_a_suite_twice_or_not_as_the_shard_declares(self):
        def altered(change):
            result = json.loads(json.dumps(plan()))
            change(result["shards"])
            return result

        cases = {
            # The same suite twice in one shard leaves one report, so only the commands can show it.
            "x.node.HeavySuite of node 2 times": lambda s: s[0]["commands"].append(s[0]["commands"][0]),
            "x.node.LightSuite of node 0 times": lambda s: s[1]["commands"].remove("node/Test/testOnly x.node.LightSuite"
                                                                                   " x.storage.CodecSuite"),
            "x.node.LightSuite of node 1 times": lambda s: s[0]["commands"].append("node/Test/testOnly x.node.LightSuite"),
            "names no module of the inventory": lambda s: s[0]["commands"].append("gone/Test/test"),
            "is not a whole module's tests or some of its suites by name":
                lambda s: s[0]["commands"].append("node/Test/testOnly x.node.*"),
        }
        for message, change in cases.items():
            with self.subTest(message=message):
                with self.assertRaisesRegex(shards.ShardError, re.escape(message)):
                    shards.check_plan(altered(change))

    def test_commands_of_a_shard_and_of_a_shard_the_plan_has_not(self):
        result = plan()
        self.assertEqual(shards.shard_of(result, 1)["commands"], ["node/Test/testOnly x.node.HeavySuite"])
        with self.assertRaisesRegex(shards.ShardError, "no shard 4"):
            shards.shard_of(result, 4)


class ReportTests(unittest.TestCase):
    def setUp(self):
        self.plan = plan()
        self.dir = tempfile.TemporaryDirectory()
        self.root = Path(self.dir.name)

    def tearDown(self):
        self.dir.cleanup()

    def write_reports(self, skip=(), extra=(), **overrides):
        """Writes each planned suite's report under its shard, as `collect` lays them out."""
        for shard in self.plan["shards"]:
            for module, suite in shard["suites"]:
                if suite in skip:
                    continue
                path = self.root / str(shard["shard"]) / module / f"TEST-{suite}.xml"
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(overrides.get(suite, report(suite)))
        for shard, module, suite in extra:
            path = self.root / shard / module / f"TEST-{suite}.xml"
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(report(suite))

    def test_accepts_one_passing_report_per_suite_and_counts_tests(self):
        self.write_reports()
        totals = shards.verify(self.plan, self.root)
        self.assertEqual(sum(s for s, _ in totals.values()), 7, "seven suites")
        self.assertEqual(sum(t for _, t in totals.values()), 14, "two tests each")

    def test_refuses_a_missing_report(self):
        self.write_reports(skip={"x.node.LightSuite"})
        with self.assertRaisesRegex(shards.ShardError, "suite x.node.LightSuite of node has no report"):
            shards.verify(self.plan, self.root)

    def test_refuses_a_suite_reported_twice_or_from_another_shard(self):
        self.write_reports(extra=[("1", "codec", "x.codec.ASuite")])
        with self.assertRaises(shards.ShardError) as raised:
            shards.verify(self.plan, self.root)
        self.assertIn("suite x.codec.ASuite of codec has 2 reports", raised.exception.problems)
        self.assertTrue(any("was planned in shard 3" in p for p in raised.exception.problems), raised.exception)

    def test_refuses_a_report_the_plan_does_not_hold(self):
        for shard, module, message in (("1", "node", "x.node.Stray of node is not in the plan"),
                                       ("9", "node", "is not in a shard and module of the plan"),
                                       ("1", "gone", "is not in a shard and module of the plan")):
            with self.subTest(shard=shard, module=module):
                self.dir.cleanup()
                self.root.mkdir()
                self.write_reports(extra=[(shard, module, "x.node.Stray")])
                with self.assertRaisesRegex(shards.ShardError, re.escape(message)):
                    shards.verify(self.plan, self.root)

    def test_refuses_failures_errors_and_reports_that_are_not_the_suites(self):
        cases = {
            "1 failures, 0 errors": report("x.node.LightSuite", failures=1),
            "0 failures, 2 errors": report("x.node.LightSuite", errors=2),
            "is not the JUnit report of x.node.LightSuite": report("x.node.OtherSuite"),
            "is not XML": "<testsuite",
        }
        for message, text in cases.items():
            with self.subTest(message=message):
                self.dir.cleanup()
                self.root.mkdir()
                self.write_reports(**{"x.node.LightSuite": text})
                with self.assertRaisesRegex(shards.ShardError, re.escape(message)):
                    shards.verify(self.plan, self.root)

    def test_collect_copies_each_module_reports_under_the_shard(self):
        checkout = self.root / "checkout"
        source = checkout / "models-gen" / "target" / "test-reports"
        source.mkdir(parents=True)
        (source / "TEST-x.gen.GenSuite.xml").write_text(report("x.gen.GenSuite"))
        (source / "other.txt").write_text("not a report")
        out = self.root / "out"
        self.assertEqual(shards.collect(self.plan, 3, checkout, out), 1)
        self.assertTrue((out / "3" / "modelsGen" / "TEST-x.gen.GenSuite.xml").is_file())


class CompiledTests(unittest.TestCase):
    def test_finds_zinc_compilations_in_a_coloured_log(self):
        log = ("\x1b[0m[\x1b[0m\x1b[0minfo\x1b[0m] \x1b[0m\x1b[0mcompiling 14 Scala sources to /w/codec/target ...\x1b[0m\n"
               "[info] compiling 1 Java source to /w/crypto/target ...\n"
               "[info] compiling 5 Scala sources to /w/project/target/scala-2.12/sbt-1.0/classes ...\n")
        self.assertEqual(len(shards.compiled(log)), 3)

    def test_ignores_test_output_that_mentions_compiling(self):
        log = ("[info] done compiling\n"
               "  + compiling a script refuses a bad term 0.01s\n"
               "[info] Passed: Total 9, Failed 0, Errors 0, Passed 9\n")
        self.assertEqual(shards.compiled(log), [])

    def test_command_fails_on_a_compilation(self):
        with tempfile.TemporaryDirectory() as directory:
            log = Path(directory) / "shard.log"
            log.write_text("[info] compiling 2 Scala sources to /w/node/target ...\n")
            self.assertEqual(shards.main(["compiled", str(log)]), 1)
            log.write_text("[info] Passed: Total 9, Failed 0, Errors 0, Passed 9\n")
            self.assertEqual(shards.main(["compiled", str(log)]), 0)


class CommittedWeightsTests(unittest.TestCase):
    weights = shards.load_weights(ROOT)
    build = (ROOT / "build.sbt").read_text()
    workflow = (ROOT / ".github/workflows/ci.yml").read_text()

    def test_every_module_of_check_has_a_weight(self):
        listed = re.search(r"^val modules = Seq\((.*?)\)$", self.build, re.MULTILINE | re.DOTALL)
        self.assertIsNotNone(listed, "build.sbt lists the modules of check")
        modules = re.findall(r'"(\w+)"', listed.group(1))
        self.assertEqual(sorted(self.weights.modules), sorted(modules))

    def test_every_named_suite_is_a_test_class_of_its_module(self):
        directories = {name: re.search(rf'lazy val {name} = scarab\("{name}"(?:, "([\w-]+)")?\)', self.build)
                       for name in self.weights.split}
        for module, named in self.weights.split.items():
            directory = (directories[module].group(1) if directories[module] else None) or module
            sources = list((ROOT / directory / "src" / "test" / "scala").rglob("*.scala"))
            for suite in named:
                package, _, simple = suite.rpartition(".")
                with self.subTest(suite=suite):
                    declared = [p for p in sources if re.search(rf"^package {re.escape(package)}$", p.read_text(),
                                                                re.MULTILINE)
                                and re.search(rf"\bclass {re.escape(simple)}\b", p.read_text())]
                    self.assertEqual(len(declared), 1, f"{suite} is declared once in {directory}'s tests")

    def test_the_workflow_runs_one_job_per_shard(self):
        matrix = re.search(r"^        shard: \[([\d, ]+)\]$", self.workflow, re.MULTILINE)
        self.assertIsNotNone(matrix, "the shard job's matrix")
        self.assertEqual([int(n) for n in matrix.group(1).split(",")], list(range(1, self.weights.shards + 1)))


if __name__ == "__main__":
    unittest.main()
