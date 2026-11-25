# SPDX-License-Identifier: Apache-2.0
"""The node's launcher keeps a forging node's memory off disk (docs/running/forging.md, "Keys in memory").

`scripts/scarab-jvm` is the release image's entry point (`docker/candidate.Dockerfile`) and what the Nix network apps
(`scripts/nix-network`) end in. Both run here for real, with a stand-in `java` on the path that records its core file
limits, its process ID, the JVM option variables it was given and its arguments. The options and the variables are
spelled out below, not taken from the code under test.
"""
import json
import os
from pathlib import Path
import re
import resource
import shutil
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import candidate_oci

REPO = Path(__file__).resolve().parents[2]
LAUNCHER = REPO / "scripts" / "scarab-jvm"
NIX_NETWORK = REPO / "scripts" / "nix-network"
DOCKERFILE = REPO / "docker" / "candidate.Dockerfile"

MEMORY_OPTIONS = ["-XX:-CreateCoredumpOnCrash", "-XX:ErrorFile=/dev/null", "-XX:-ErrorFileToStdout",
                  "-XX:-ErrorFileToStderr", "-XX:-DumpReplayDataOnError", "-XX:-HeapDumpOnOutOfMemoryError",
                  "-XX:+DisableAttachMechanism", "-XX:+PerfDisableSharedMem"]
VARIABLES = ["JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"]
STAND_IN = f"""#!{sys.executable}
import json, os, resource, sys
soft, hard = resource.getrlimit(resource.RLIMIT_CORE)
with open(os.environ["STAND_IN_RECORD"], "w") as out:
    json.dump(dict(soft=soft, hard=hard, pid=os.getpid(), args=sys.argv[1:],
                   variables={{name: os.environ.get(name) for name in {VARIABLES!r}}}), out)
sys.exit(int(os.environ.get("STAND_IN_EXIT", "0")))
"""


class StandIn:
    def __init__(self, test):
        scratch = tempfile.TemporaryDirectory()
        test.addCleanup(scratch.cleanup)
        self.root = Path(scratch.name)
        self.bin = self.root / "bin"
        self.bin.mkdir()
        java = self.bin / "java"
        java.write_text(STAND_IN)
        java.chmod(0o755)
        self.record = self.root / "record.json"

    def environment(self, **extra):
        env = {k: v for k, v in os.environ.items()
               if k not in VARIABLES and not k.startswith("SCARAB_")}
        env.update(PATH=f"{self.bin}:{os.environ['PATH']}", STAND_IN_RECORD=str(self.record), **extra)
        return env

    def java(self):
        return json.loads(self.record.read_text()) if self.record.exists() else None


class LauncherTest(unittest.TestCase):
    ARGUMENTS = ["--sun-misc-unsafe-memory-access=allow", "-jar", "/opt/scarab/scarab.jar", "follow",
                 "--config", "/a dir/config.json", "--peer", "", "two\nlines"]

    def setUp(self):
        self.stand_in = StandIn(self)

    def launch(self, **environment):
        process = subprocess.Popen(["bash", str(LAUNCHER), *self.ARGUMENTS], env=self.stand_in.environment(**environment),
                                   stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        stdout, stderr = process.communicate(timeout=60)
        return process, stderr, self.stand_in.java()

    def test_by_default_the_jvm_gets_the_memory_options_and_no_core_file(self):
        process, stderr, java = self.launch()
        self.assertEqual(process.returncode, 0, stderr)
        self.assertEqual(stderr, "", "the launcher wrote to standard error with nothing to report")
        self.assertEqual((java["soft"], java["hard"]), (0, 0), "the soft or the hard core file limit is not 0")
        self.assertEqual(java["args"], MEMORY_OPTIONS + self.ARGUMENTS,
                         "the JVM did not get the memory options followed by the arguments, unchanged")
        self.assertEqual(java["pid"], process.pid, "the launcher did not exec java, so the JVM is not its process")

    def test_the_jvm_option_variables_are_dropped_and_named_on_one_line(self):
        given = {"JAVA_TOOL_OPTIONS": "-XX:OnError=gcore", "_JAVA_OPTIONS": ""}
        process, stderr, java = self.launch(**given)
        self.assertEqual(process.returncode, 0, stderr)
        self.assertEqual(java["variables"], dict.fromkeys(VARIABLES), "a JVM option variable reached the JVM")
        lines = stderr.splitlines()
        self.assertEqual(len(lines), 1, f"not one line on standard error: {stderr!r}")
        for name in VARIABLES:
            self.assertEqual(re.search(rf"\b{name}\b", lines[0]) is not None, name in given,
                             f"the line names {name} wrongly: {lines[0]!r}")
        self.assertNotIn("gcore", stderr, "the line repeats a dropped value")
        self.assertEqual(java["args"][:len(MEMORY_OPTIONS)], MEMORY_OPTIONS)

    def test_diagnostics_keep_the_variables_and_the_jvm_defaults(self):
        inherited = resource.getrlimit(resource.RLIMIT_CORE)
        given = dict.fromkeys(VARIABLES, "-Xlog:disable")
        process, stderr, java = self.launch(SCARAB_JVM_DIAGNOSTICS="1", **given)
        self.assertEqual(process.returncode, 0, stderr)
        self.assertIn("memory diagnostics enabled: do not use on a forging node", stderr)
        self.assertEqual(java["variables"], given, "diagnostics dropped a JVM option variable")
        self.assertEqual(java["args"], self.ARGUMENTS, "diagnostics still passed memory options")
        self.assertEqual((java["soft"], java["hard"]), inherited, "diagnostics changed the core file limit")

    def test_diagnostics_off_is_the_default(self):
        _, _, java = self.launch(SCARAB_JVM_DIAGNOSTICS="0")
        self.assertEqual(java["args"][:len(MEMORY_OPTIONS)], MEMORY_OPTIONS)

    def test_heap_and_processors_become_jvm_options(self):
        process, stderr, java = self.launch(SCARAB_HEAP_SIZE="3g", SCARAB_RUN_PROCESSORS="4")
        self.assertEqual(process.returncode, 0, stderr)
        self.assertEqual(java["args"], ["-Xmx3g", "-XX:ActiveProcessorCount=4", *MEMORY_OPTIONS, *self.ARGUMENTS])

    def test_a_value_it_cannot_read_stops_before_the_jvm(self):
        cases = [("SCARAB_JVM_DIAGNOSTICS", value) for value in ["yes", "-S", " 1", "2"]]
        cases += [("SCARAB_HEAP_SIZE", value) for value in ["", "-S", "3x", "0", "3g -XX:OnError=gcore", "08g"]]
        cases += [("SCARAB_RUN_PROCESSORS", value) for value in ["", "-S", "0", "four", "4 4"]]
        for name, value in cases:
            with self.subTest(name=name, value=value):
                self.stand_in.record.unlink(missing_ok=True)
                process, stderr, java = self.launch(**{name: value})
                self.assertEqual(process.returncode, 2, f"{name}={value!r} was accepted")
                self.assertIn(name, stderr, "the refusal does not name the variable")
                self.assertIsNone(java, f"the JVM started with {name}={value!r}")

    def test_the_jvm_exit_status_is_the_launchers(self):
        process, stderr, _ = self.launch(STAND_IN_EXIT="7")
        self.assertEqual(process.returncode, 7, stderr)


class CandidateImageTest(unittest.TestCase):
    def test_the_entry_point_is_the_launcher(self):
        lines = [line for line in DOCKERFILE.read_text().splitlines() if line.startswith("ENTRYPOINT ")]
        self.assertEqual(len(lines), 1, "docker/candidate.Dockerfile has one ENTRYPOINT")
        entrypoint = json.loads(lines[0].removeprefix("ENTRYPOINT "))
        self.assertEqual(entrypoint, ["/opt/scarab/scarab-jvm", "--sun-misc-unsafe-memory-access=allow", "-jar",
                                      "/opt/scarab/scarab.jar"], "the image does not start the node through the launcher")
        self.assertEqual(entrypoint, candidate_oci.ENTRYPOINT, "candidate_oci verifies another entry point")

    def test_the_image_carries_the_launcher_read_only(self):
        self.assertIn("COPY --chmod=0555 scarab-jvm /opt/scarab/scarab-jvm", DOCKERFILE.read_text().splitlines())
        self.assertEqual(candidate_oci.LAUNCHER, LAUNCHER, "candidate_oci compares the image with another launcher")


class NixLauncherTest(unittest.TestCase):
    def setUp(self):
        self.stand_in = StandIn(self)
        self.checkout = self.stand_in.root / "checkout"
        (self.checkout / "scripts").mkdir(parents=True)
        (self.checkout / "node").mkdir()
        (self.checkout / "build.sbt").write_text("")
        self.build_record = self.stand_in.root / "build.txt"
        (self.checkout / "scripts" / "sbtw").write_text(
            f'printf "%s" "${{JAVA_TOOL_OPTIONS-unset}}" > {self.build_record}\n'
            "mkdir -p node/target && printf '/stand-in/classes' > node/target/runtime-classpath.txt\n")
        shutil.copy(LAUNCHER, self.checkout / "scripts" / "scarab-jvm")
        self.data = self.stand_in.root / "data"
        self.config = self.stand_in.root / "config"
        self.config.mkdir()

    def launch(self, **environment):
        env = self.stand_in.environment(SCARAB_WRAPPER_NETWORK="preview", SCARAB_WRAPPER_QUERY_PORT="7071",
                                        SCARAB_SOURCE_DIR=str(self.checkout), SCARAB_DATA_DIR=str(self.data),
                                        **environment)
        return subprocess.run(
            ["bash", str(NIX_NETWORK), "--config", str(self.config / "config.json"),
             "--topology", str(self.config / "topology.json"), "--db", str(self.data / "db"),
             "--local-socket", str(self.data / "node.socket")],
            env=env, capture_output=True, text=True, timeout=60)

    def test_the_node_starts_through_the_launcher_and_the_build_keeps_the_environment(self):
        done = self.launch(JAVA_TOOL_OPTIONS="-Dbuild=1")
        self.assertEqual(done.returncode, 0, done.stderr)
        java = self.stand_in.java()
        self.assertEqual((java["soft"], java["hard"]), (0, 0), "the node's JVM may write a core file")
        options = java["args"][:java["args"].index("-cp")]
        self.assertEqual(options, ["-Xmx3g", "-XX:ActiveProcessorCount=4", *MEMORY_OPTIONS,
                                   "--sun-misc-unsafe-memory-access=allow", "--enable-native-access=ALL-UNNAMED"])
        self.assertIsNone(java["variables"]["JAVA_TOOL_OPTIONS"], "JAVA_TOOL_OPTIONS reached the node's JVM")
        self.assertEqual(self.build_record.read_text(), "-Dbuild=1", "the build did not get the caller's environment")

    def test_the_heap_and_processors_can_be_changed(self):
        done = self.launch(SCARAB_HEAP_SIZE="6g", SCARAB_RUN_PROCESSORS="2")
        self.assertEqual(done.returncode, 0, done.stderr)
        self.assertEqual(self.stand_in.java()["args"][:2], ["-Xmx6g", "-XX:ActiveProcessorCount=2"])

    def test_a_checkout_without_the_launcher_is_refused(self):
        (self.checkout / "scripts" / "scarab-jvm").unlink()
        done = self.launch()
        self.assertEqual(done.returncode, 2, done.stderr)
        self.assertIn("scripts/scarab-jvm", done.stderr)
        self.assertIsNone(self.stand_in.java(), "the node started without the launcher")


if __name__ == "__main__":
    unittest.main()
