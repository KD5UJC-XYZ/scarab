# SPDX-License-Identifier: Apache-2.0
"""The JIT workaround of #1521 reaches every JVM that runs Scarab's code (scripts/check_jit_workaround.py).

The options are spelled out here, not taken from the code under test; the launchers that are run here (the devnet's
container command, the conformance tools' JVM) are run for their argument lists, with no Docker.
"""
import argparse
import functools
from pathlib import Path
import sys
import unittest
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import check_jit_workaround as check
import jit_workaround
from conformance import scenarios
from devnet import cluster

ROOT = Path(__file__).resolve().parents[2]
OPTIONS = ("-XX:+UnlockDiagnosticVMOptions", "-XX:-ReduceAllocationMerges")


@functools.cache
def repository():
    return dict(check.tracked(ROOT))


class OptionsTest(unittest.TestCase):
    def test_the_list_is_the_workaround(self):
        self.assertEqual(jit_workaround.OPTIONS, OPTIONS)

    def test_the_repository_passes(self):
        self.assertEqual(check.problems(repository().items(), check.documents(ROOT)), [])


class LauncherBehaviourTest(unittest.TestCase):
    def test_every_devnet_jvm_gets_the_options(self):
        with mock.patch.object(cluster, "jdk_image", return_value="scarab-jdk:local"):
            command = cluster.scala_container(argparse.Namespace(name="n"), Path("/r"), "cp", "M", [])
        options = command[command.index("scarab-jdk:local") + 1:command.index("-cp")]
        self.assertTrue(check.contiguous(options), options)

    def test_every_conformance_tool_gets_the_options(self):
        for flags in (scenarios.JDK_FLAGS, scenarios.TOOL_JVM, scenarios.TURBO_JVM):
            self.assertTrue(check.contiguous(flags), flags)


class DiscoveryTest(unittest.TestCase):
    """Each case takes the repository as it is and changes one file."""

    def setUp(self):
        self.files = repository()
        self.documents = dict(check.documents(ROOT))

    def found(self, path=None, text=None, documents=None):
        files = dict(self.files)
        if path is not None:
            files[path] = text
        return check.problems(files.items(), (documents or self.documents).items())

    def changed(self, path, old, new):
        self.assertIn(old, self.files[path], f"{path} no longer holds the text this case changes")
        return self.found(path, self.files[path].replace(old, new, 1))

    def test_a_new_launcher_must_be_classified(self):
        for path, text in [("scripts/new_tool.py", 'subprocess.run(["java", "-cp", jar, main])\n'),
                           ("scripts/new_tool.py", 'subprocess.run([\n    "java",\n    "-jar",\n    jar,\n])\n'),
                           ("scripts/new_tool.py", 'run(["docker", "run", "--entrypoint", "java", image, "-cp", jar])\n'),
                           ("scripts/new_tool.py", 'run(["bash", "-c", "exec java -cp x Main"])\n'),
                           ("scripts/new_tool.sh", 'exec java -Xmx1g -cp "$cp" Main\n'),
                           ("scripts/new_tool.sh", 'exec java \\\n  -cp "$cp" Main\n'),
                           ("docker/new.Dockerfile", 'ENTRYPOINT ["/opt/java/openjdk/bin/java", "-jar", "x.jar"]\n'),
                           ("docker/new.Dockerfile", 'ENTRYPOINT ["java", "-jar", "scarab.jar"]\n'),
                           ("node/src/test/scala/A.scala", 'new ProcessBuilder(Path.of(home, "bin", "java").toString)\n'),
                           ("node/src/test/scala/A.scala", 'new ProcessBuilder(\n  "java",\n  "-cp",\n  cp\n)\n')]:
            with self.subTest(path=path):
                found = self.found(path, text)
                self.assertEqual(len(found), 1, found)
                self.assertIn("is not classified", found[0])

    def test_a_comment_or_a_word_is_not_a_launcher(self):
        for text in ["# exec java -cp x Main\n", "  * java -Xmx6g -cp <classpath> Main\n",
                     'LANGS = ("scala", "java")\n', '"""Never java or sbt on the host: `java -version` in the image."""\n',
                     'raise ValueError(f"fat JAR java -jar smoke failed: {out}")\n', 'record = {"java": None}\n']:
            with self.subTest(text=text):
                self.assertEqual(self.found("scripts/new_tool.py", text), [])

    def test_a_new_launch_in_a_known_file_must_be_classified(self):
        found = self.changed("scripts/preflight_doctor.py", '"java", "-XshowSettings:properties", "-version"',
                             '"java", "-XshowSettings:properties", "-version", ("java", "-cp", "x")')
        self.assertEqual(len(found), 1, found)
        self.assertIn("records 1", found[0])

    def test_a_python_launch_without_the_options_fails_although_counted(self):
        found = self.changed("scripts/reproducible_build.py", '["java", *jit_workaround.OPTIONS, "-jar"', '["java", "-jar"')
        self.assertEqual(len(found), 1, found)
        self.assertIn("1 launch(es) without", found[0])

    def test_a_launcher_without_the_options_fails(self):
        cases = [
            ("build.sbt", ' ++ jitWorkaround\n', '\n'),
            ("build.sbt", '"-XX:-ReduceAllocationMerges")', '"-XX:+ReduceAllocationMerges")'),
            ("build.sbt", "ThisBuild / Test / javaOptions ++= jdk25Flags", "ThisBuild / Test / javaOptions ++= Nil"),
            ("build.sbt", 'val options = Vector("-Xmx256m") ++ jdk25Flags', 'val options = Vector("-Xmx256m")'),
            ("build.sbt", "Compile / run / javaOptions ++= jdk25Flags", "Compile / run / javaOptions ++= Nil"),
            ("scripts/sbtw", "-XX:+UnlockDiagnosticVMOptions -XX:-ReduceAllocationMerges)", ")"),
            ("scripts/scarab-jvm", '"${jit[@]}" "$@"', '"$@"'),
            ("scripts/scarab-jvm", "jit=(-XX:+UnlockDiagnosticVMOptions -XX:-ReduceAllocationMerges)",
             "jit=(-XX:-ReduceAllocationMerges)"),
            ("antithesis/pool/scarab/scarab-node.sh", "  -XX:+UnlockDiagnosticVMOptions -XX:-ReduceAllocationMerges \\\n",
             ""),
            ("scripts/devnet/cluster.py", ", *jit_workaround.OPTIONS]", "]"),
            ("scripts/conformance/scenarios.py", ", *jit_workaround.OPTIONS)", ")"),
            ("scripts/conformance/run.py", '"java", *scenario.jvm, ', '"java", '),
            ("models/src/test/scala/xyz/kd5ujc/scarab/models/RuntimeCauseSuite.scala",
             '        "-XX:-ReduceAllocationMerges",\n', ''),
            ("docker/candidate.Dockerfile", 'ENTRYPOINT ["/opt/scarab/scarab-jvm", ', 'ENTRYPOINT ['),
            ("scripts/nix-network", 'exec "$BASH" -p "$source_dir/scripts/scarab-jvm" "$JAVA_HOME/bin/java"',
             'exec "$JAVA_HOME/bin/java"'),
        ]
        for path, old, new in cases:
            with self.subTest(path=path, old=old):
                found = self.changed(path, old, new)
                self.assertTrue(found, "the change was not found")
                self.assertTrue(all(problem.startswith(path) for problem in found), found)

    def group(self, suite, options, comment="the forked group of a suite"):
        """build.sbt with one more forked group, of `suite`, and the count of launches it records raised to match."""
        grouping = ('lazy val extra = scarab("extra")\n  .settings(\n    Test / testGrouping := {\n'
                    f'      // {comment}\n'
                    f'      val (jit, others) = (Test / definedTests).value.partition(_.name.endsWith(".{suite}"))\n'
                    f'      val fork = (Test / forkOptions).value.withRunJVMOptions({options})\n'
                    '      Seq(Tests.Group("jit", jit, Tests.SubProcess(fork)))\n    }\n  )\n')
        files = dict(self.files)
        files["build.sbt"] = self.files["build.sbt"] + grouping
        entry = check.LAUNCHERS["build.sbt"]
        registry = {**check.LAUNCHERS, "build.sbt": check.Launcher(entry.launches + 1, entry.check, entry.note)}
        return check.problems(files.items(), self.documents.items(), registry)

    def test_only_the_regression_group_runs_on_the_jit_defaults(self):
        options = 'Vector("-Xbatch", "-XX:ActiveProcessorCount=2") ++ jdk25FlagsJitDefaults'
        self.assertEqual(self.group("MainnetCollateralJitSuite", options), [])
        found = self.group("OtherSuite", options)
        self.assertEqual(len(found), 1, found)
        self.assertIn("without jdk25Flags", found[0])

    def test_the_regression_group_must_not_run_with_the_workaround(self):
        found = self.group("MainnetCollateralJitSuite", 'Vector("-Xbatch", "-XX:ActiveProcessorCount=2") ++ jdk25Flags')
        self.assertEqual(len(found), 1, found)
        self.assertIn("runs with the JIT workaround, so it tests nothing", found[0])

    def test_a_comment_naming_the_regression_suite_exempts_nothing(self):
        found = self.group("OtherSuite", 'Vector("-Xmx1g") ++ jdk25FlagsJitDefaults',
                           comment='like ".MainnetCollateralJitSuite"')
        self.assertTrue(any("without jdk25Flags" in problem for problem in found), found)

    def test_the_defaults_list_outside_a_grouping_fails(self):
        found = self.changed("build.sbt", "Compile / run / javaOptions ++= jdk25Flags",
                             "Compile / run / javaOptions ++= jdk25FlagsJitDefaults")
        self.assertTrue(any("outside a test grouping" in problem for problem in found), found)

    def test_a_forked_group_with_options_of_its_own_needs_jdk25_flags(self):
        self.assertEqual(self.group("OtherSuite", 'Vector("-Xmx1g") ++ jdk25Flags'), [])
        found = self.group("OtherSuite", 'Vector("-Xmx1g", "-Xss1m")')
        self.assertEqual(len(found), 1, found)
        self.assertIn("without jdk25Flags", found[0])

    def test_a_replaced_option_list_fails(self):
        found = self.changed("build.sbt", "Test / javaOptions ++= Seq(", "Test / javaOptions := Seq(")
        self.assertTrue(any("javaOptions :=" in problem for problem in found), found)

    def test_a_documented_command_without_the_options_fails(self):
        documents = dict(self.documents)
        documents["docs/running/new.md"] = ('```sh\njava -Xmx3g -cp "$(cat node/target/runtime-classpath.txt)" \\\n'
                                            '  xyz.kd5ujc.scarab.node.Main follow --db /data/db\n```\n')
        found = self.found(documents=documents)
        self.assertEqual(found, ["docs/running/new.md:2: a documented JVM of Scarab's code without "
                                 "`-XX:+UnlockDiagnosticVMOptions -XX:-ReduceAllocationMerges` (#1521)"])

    def test_a_documented_command_through_the_launcher_or_with_the_options_passes(self):
        documents = dict(self.documents)
        documents["docs/running/new.md"] = (
            '```sh\njava -XX:+UnlockDiagnosticVMOptions -XX:-ReduceAllocationMerges -jar scarab.jar follow\n'
            'docker run --entrypoint /opt/scarab/scarab-jvm <image> /opt/java/openjdk/bin/java -jar scarab.jar\n```\n'
            'Prose: `java -cp x xyz.kd5ujc.scarab.node.Main` is not a command.\n')
        self.assertEqual(self.found(documents=documents), [])

    def test_every_recorded_entry_says_why(self):
        for path, entry in check.LAUNCHERS.items():
            with self.subTest(path=path):
                self.assertTrue(entry.note.strip())


if __name__ == "__main__":
    unittest.main()
