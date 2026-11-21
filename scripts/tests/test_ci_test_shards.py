# SPDX-License-Identifier: Apache-2.0
from pathlib import Path
import json
import re
import sys
import tempfile
import unittest
from xml.sax.saxutils import quoteattr

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


SKIP = {"module": "node", "suite": "x.node.LightSuite", "test": "reads the pinned capture",
        "environment": ["SCARAB_CAPTURE"], "issue": 294}


def skips(*entries):
    return shards.read_skips({"skips": list(entries)})


def report(suite, tests=2, failures=0, errors=0, skipped=(), cases=None):
    """sbt's JUnit report of `suite`: `tests` test cases, the last of them named by `skipped` and skipped, as sbt
    writes munit's `assume` and `.ignore`; `cases` overrides the number of test cases written."""
    names = [f"test {i}" for i in range(tests - len(skipped))] + list(skipped)
    written = names if cases is None else names[:cases]
    body = "".join(f"<testcase classname={quoteattr(suite)} name={quoteattr(name)} time=\"0.001\">"
                   + ("<skipped/>" if name in skipped else "") + "</testcase>" for name in written)
    return (f"<?xml version='1.0' encoding='UTF-8'?>\n<testsuite name=\"{suite}\" tests=\"{tests}\" "
            f"failures=\"{failures}\" errors=\"{errors}\" skipped=\"{len(skipped)}\" time=\"0.1\">{body}"
            "</testsuite>\n")


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


class SkipsTests(unittest.TestCase):
    def test_reads_an_entry_by_module_suite_and_test(self):
        allowed = skips(SKIP, dict(SKIP, test="needs the pinned blst", environment=[], data="blst 0.3.15", issue=7))
        self.assertEqual(sorted(allowed), [("node", "x.node.LightSuite", "needs the pinned blst"),
                                           ("node", "x.node.LightSuite", "reads the pinned capture")])
        self.assertEqual(allowed[("node", "x.node.LightSuite", "needs the pinned blst")].waits_on(), "blst 0.3.15")

    def test_refuses_an_entry_that_does_not_say_what_it_waits_on_or_who_owns_it(self):
        without = {k: v for k, v in SKIP.items() if k != "issue"}
        cases = {
            "no 'issue'": without,
            "`issue` is not the number": dict(SKIP, issue="294"),
            "`issue` is not the number ": dict(SKIP, issue=True),
            "`issue` is not the number  ": dict(SKIP, issue=0),
            "names neither the environment variables nor the data": dict(SKIP, environment=[]),
            "`environment` is not a list": dict(SKIP, environment="SCARAB_CAPTURE"),
            "`environment` is not a list ": dict(SKIP, environment=["scarab_capture"]),
            "`environment` is not a list  ": dict(SKIP, environment=["SCARAB_CAPTURE", "SCARAB_CAPTURE"]),
            "`data` is not a description": dict(SKIP, data=" "),
            "`suite` is not a class name": dict(SKIP, suite="x.node.*"),
            "`module` is not a module name": dict(SKIP, module=""),
            "`test` is not a test name": dict(SKIP, test=" padded "),
            "unknown field 'reason'": dict(SKIP, reason="waits"),
        }
        for message, entry in cases.items():
            with self.subTest(message=message):
                with self.assertRaisesRegex(shards.ShardError, re.escape(message.strip())):
                    skips(entry)

    def test_refuses_a_test_listed_twice_and_a_file_that_is_not_the_allowlist(self):
        with self.assertRaisesRegex(shards.ShardError, "is listed twice"):
            skips(SKIP, dict(SKIP, issue=7))
        for data in ([], {}, {"skips": {}}, {"skips": ["x"]}):
            with self.subTest(data=data):
                with self.assertRaises(shards.ShardError):
                    shards.read_skips(data)


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

    def verify(self, allowed=None):
        return shards.verify(self.plan, self.root, allowed if allowed is not None else {})

    def problems(self, allowed=None):
        with self.assertRaises(shards.ShardError) as raised:
            self.verify(allowed)
        return raised.exception.problems

    def test_accepts_one_passing_report_per_suite_and_counts_tests(self):
        self.write_reports()
        verified = self.verify()
        self.assertEqual(sum(t[0] for t in verified.totals.values()), 7, "seven suites")
        self.assertEqual(sum(t[1] for t in verified.totals.values()), 14, "two tests each")
        self.assertEqual(sum(t[2] for t in verified.totals.values()), 0, "no test skipped")
        self.assertEqual(verified.skipped, ())

    def test_refuses_a_missing_report(self):
        self.write_reports(skip={"x.node.LightSuite"})
        with self.assertRaisesRegex(shards.ShardError, "suite x.node.LightSuite of node has no report"):
            self.verify()

    def test_refuses_a_skipped_test_the_allowlist_does_not_list(self):
        self.write_reports(**{"x.node.LightSuite": report("x.node.LightSuite", tests=3, skipped=["waits on data"])})
        problems = self.problems(skips(SKIP))
        self.assertTrue(any("test 'waits on data' was skipped, and scripts/ci_test_skips.json does not list it" in p
                            for p in problems), problems)
        self.assertTrue(any("an incomplete run is not a pass (CNF-2)" in p for p in problems), problems)

    def test_refuses_a_skip_listed_for_another_suite_or_module(self):
        for entry in (dict(SKIP, suite="x.node.OtherSuite"), dict(SKIP, module="codec", suite="x.codec.ASuite")):
            with self.subTest(entry=entry):
                self.dir.cleanup()
                self.root.mkdir()
                self.write_reports(**{"x.node.LightSuite": report("x.node.LightSuite", skipped=[SKIP["test"]])})
                problems = self.problems(skips(entry))
                self.assertTrue(any(f"test {SKIP['test']!r} was skipped" in p for p in problems), problems)

    def test_accepts_a_listed_skip_and_reports_what_it_waits_on(self):
        self.write_reports(**{"x.node.LightSuite": report("x.node.LightSuite", tests=1, skipped=[SKIP["test"]])})
        verified = self.verify(skips(SKIP))
        self.assertEqual([s.key for s in verified.skipped], [("node", "x.node.LightSuite", SKIP["test"])],
                         "the one listed skip, accepted")
        self.assertEqual(sum(t[2] for t in verified.totals.values()), 1, "one test skipped")
        self.assertEqual(verified.skipped[0].waits_on(), "SCARAB_CAPTURE")

    def test_refuses_a_listed_skip_whose_test_did_not_skip(self):
        self.write_reports()
        problems = self.problems(skips(SKIP))
        self.assertEqual(problems, [f"scripts/ci_test_skips.json lists test {SKIP['test']!r} of x.node.LightSuite of "
                                    "node, which did not skip: remove its entry"])

    def test_refuses_a_listed_skip_of_a_suite_the_plan_does_not_hold(self):
        self.write_reports()
        problems = self.problems(skips(dict(SKIP, suite="x.node.Gone")))
        self.assertEqual(len(problems), 1, problems)
        self.assertIn("x.node.Gone of node, a suite the plan does not hold: remove its entry", problems[0])

    def test_refuses_a_report_that_ran_no_test(self):
        self.write_reports(**{"x.node.LightSuite": report("x.node.LightSuite", tests=0)})
        self.assertEqual(self.problems(), ["report 2/node/TEST-x.node.LightSuite.xml: suite x.node.LightSuite of node "
                                           "ran no test"])

    def test_refuses_a_report_whose_counts_are_not_its_test_cases(self):
        cases = {
            "counts 2 tests, 0 skipped, but holds 1 test cases, 0 skipped": report("x.node.LightSuite", cases=1),
            "counts 2 tests, 0 skipped, but holds 2 test cases, 1 skipped":
                report("x.node.LightSuite", skipped=["s"]).replace('skipped="1"', 'skipped="0"'),
            "is not the JUnit report of x.node.LightSuite": report("x.node.LightSuite").replace(' skipped="0"', ""),
        }
        for message, text in cases.items():
            with self.subTest(message=message):
                self.dir.cleanup()
                self.root.mkdir()
                self.write_reports(**{"x.node.LightSuite": text})
                with self.assertRaisesRegex(shards.ShardError, re.escape(message)):
                    self.verify()

    def test_refuses_a_suite_reported_twice_or_from_another_shard(self):
        self.write_reports(extra=[("1", "codec", "x.codec.ASuite")])
        problems = self.problems()
        self.assertIn("suite x.codec.ASuite of codec has 2 reports", problems)
        self.assertTrue(any("was planned in shard 3" in p for p in problems), problems)

    def test_refuses_a_report_the_plan_does_not_hold(self):
        for shard, module, message in (("1", "node", "x.node.Stray of node is not in the plan"),
                                       ("9", "node", "is not in a shard and module of the plan"),
                                       ("1", "gone", "is not in a shard and module of the plan")):
            with self.subTest(shard=shard, module=module):
                self.dir.cleanup()
                self.root.mkdir()
                self.write_reports(extra=[(shard, module, "x.node.Stray")])
                with self.assertRaisesRegex(shards.ShardError, re.escape(message)):
                    self.verify()

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
                    self.verify()

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


class CommittedSkipsTests(unittest.TestCase):
    """The committed allowlist: every entry names a test of its module's sources, and the variables it waits on."""
    allowed = shards.load_skips(ROOT)
    build = (ROOT / "build.sbt").read_text()

    def sources(self, module):
        declared = re.search(rf'lazy val {module} = scarab\("{module}"(?:, "([\w-]+)")?\)', self.build)
        self.assertIsNotNone(declared, f"build.sbt declares module {module}")
        directory = declared.group(1) or module
        return list((ROOT / directory / "src" / "test" / "scala").rglob("*.scala"))

    def test_is_in_module_suite_and_test_order(self):
        data = json.loads((ROOT / shards.SKIPS).read_text(encoding="utf-8"))
        keys = [(e["module"], e["suite"], e["test"]) for e in data["skips"]]
        self.assertEqual(keys, sorted(keys), "entries sorted by module, suite and test")

    def test_every_entry_is_a_test_of_its_suite_that_reads_what_it_waits_on(self):
        for skip in self.allowed.values():
            package, _, simple = skip.suite.rpartition(".")
            with self.subTest(suite=skip.suite, test=skip.test):
                declared = [p.read_text() for p in self.sources(skip.module)
                            if re.search(rf"^package {re.escape(package)}$", p.read_text(), re.MULTILINE)
                            and re.search(rf"\bclass {re.escape(simple)}\b", p.read_text())]
                self.assertEqual(len(declared), 1, f"{skip.suite} is declared once in {skip.module}'s tests")
                self.assertTrue(json.dumps(skip.test, ensure_ascii=False) in declared[0],
                                f"{skip.suite} declares the test {skip.test!r} by that name")
                for variable in skip.environment:
                    self.assertTrue(f'"{variable}"' in declared[0], f"{skip.suite} reads {variable}")


if __name__ == "__main__":
    unittest.main()
