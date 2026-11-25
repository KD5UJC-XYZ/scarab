# SPDX-License-Identifier: Apache-2.0
"""The node's launcher keeps a forging node's memory off disk (docs/running/forging.md, "Keys in memory").

`scripts/scarab-jvm` is the release image's entry point (`docker/candidate.Dockerfile`) and what the Nix network apps
(`scripts/nix-network`) end in. Both run here for real, the launcher through its own first line, with a stand-in
`java` that records its core file limits, its process ID, the variables that select or add JVM options and its
arguments. The options and the variables are spelled out below, not taken from the code under test.
"""
import ctypes
import json
import os
from pathlib import Path
import platform
import re
import resource
import shutil
import struct
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
VARIABLES = ["JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "JDK_ALTERNATE_VM"]
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
        self.java = self.bin / "java"
        self.java.write_text(STAND_IN)
        self.java.chmod(0o755)
        self.record = self.root / "record.json"

    def environment(self, **extra):
        env = {k: v for k, v in os.environ.items()
               if k not in VARIABLES and not k.startswith(("SCARAB_", "BASH_FUNC_")) and k not in ("BASH_ENV", "ENV")}
        env.update(STAND_IN_RECORD=str(self.record), **extra)
        return env

    def recorded(self):
        return json.loads(self.record.read_text()) if self.record.exists() else None


def refuse_raising_limits():
    """A seccomp filter for the child: setrlimit, and prlimit64 with a new limit, fail with EPERM (x86-64 only)."""
    libc = ctypes.CDLL(None, use_errno=True)
    load, equal, answer = 0x20, 0x15, 0x06
    allow, refuse = 0x7FFF0000, 0x00050000 | 1

    def instruction(code, k, true=0, false=0):
        return struct.pack("HBBI", code, true, false, k)
    program = b"".join([
        instruction(load, 4), instruction(equal, 0xC000003E, 0, 7),
        instruction(load, 0), instruction(equal, 160, 6, 0), instruction(equal, 302, 0, 4),
        instruction(load, 32), instruction(equal, 0, 0, 3), instruction(load, 36), instruction(equal, 0, 0, 1),
        instruction(answer, allow), instruction(answer, refuse)])
    buffer = ctypes.create_string_buffer(program)

    class Program(ctypes.Structure):
        _fields_ = [("len", ctypes.c_ushort), ("filter", ctypes.c_void_p)]
    prog = Program(len(program) // 8, ctypes.cast(buffer, ctypes.c_void_p))

    def install():
        if libc.prctl(38, 1, 0, 0, 0) != 0 or libc.prctl(22, 2, ctypes.byref(prog), 0, 0) != 0:
            os._exit(99)
    install.keep = (buffer, prog)
    return install


class LauncherTest(unittest.TestCase):
    ARGUMENTS = ["--sun-misc-unsafe-memory-access=allow", "-jar", "/opt/scarab/scarab.jar", "follow",
                 "--config", "/a dir/config.json", "--peer", "", "two\nlines"]

    def setUp(self):
        self.stand_in = StandIn(self)

    def launch(self, *, java=None, preexec=None, **environment):
        process = subprocess.Popen([str(LAUNCHER), str(self.stand_in.java) if java is None else java, *self.ARGUMENTS],
                                   env=self.stand_in.environment(**environment), preexec_fn=preexec,
                                   stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        _, stderr = process.communicate(timeout=60)
        return process, stderr, self.stand_in.recorded()

    def test_by_default_the_jvm_gets_the_memory_options_and_no_core_file(self):
        process, stderr, java = self.launch()
        self.assertEqual(process.returncode, 0, stderr)
        self.assertEqual(stderr, "", "the launcher wrote to standard error with nothing to report")
        self.assertEqual((java["soft"], java["hard"]), (0, 0), "the soft or the hard core file limit is not 0")
        self.assertEqual(java["args"], MEMORY_OPTIONS + self.ARGUMENTS,
                         "the JVM did not get the memory options followed by the arguments, unchanged")
        self.assertEqual(java["pid"], process.pid, "the launcher did not exec java, so the JVM is not its process")

    def test_every_option_variable_is_dropped_and_named_on_one_line(self):
        given = {"JAVA_TOOL_OPTIONS": "-XX:OnError=gcore", "JDK_JAVA_OPTIONS": "-XX:+HeapDumpOnOutOfMemoryError",
                 "_JAVA_OPTIONS": "", "JDK_ALTERNATE_VM": "/tmp/elsewhere"}
        process, stderr, java = self.launch(**given)
        self.assertEqual(process.returncode, 0, stderr)
        self.assertEqual(java["variables"], dict.fromkeys(VARIABLES), "a variable reached the JVM")
        lines = stderr.splitlines()
        self.assertEqual(len(lines), 1, f"not one line on standard error: {stderr!r}")
        for name in VARIABLES:
            self.assertRegex(lines[0], rf"(^|\s){name}(\s|$)", f"the line does not name {name}")
        for value in ["gcore", "HeapDump", "elsewhere"]:
            self.assertNotIn(value, stderr, "the line repeats a dropped value")
        self.assertEqual(java["args"], MEMORY_OPTIONS + self.ARGUMENTS)

    def test_neither_bash_env_nor_exported_functions_change_what_it_does(self):
        bash_env = self.stand_in.root / "bash-env"
        bash_env.write_text("ulimit() { :; }\nunset() { :; }\nexec() { :; }\nexport JAVA_TOOL_OPTIONS=-XX:OnError=gcore\n")
        noop = "() { :; }"
        process, stderr, java = self.launch(BASH_ENV=str(bash_env), ENV=str(bash_env), JDK_JAVA_OPTIONS="-Dx=1",
                                            **{"BASH_FUNC_ulimit%%": noop, "BASH_FUNC_unset%%": noop,
                                               "BASH_FUNC_exec%%": noop})
        self.assertEqual(process.returncode, 0, stderr)
        self.assertIsNotNone(java, "java did not start: a function stood in for exec")
        self.assertEqual((java["soft"], java["hard"]), (0, 0), "a function stood in for ulimit")
        self.assertEqual(java["variables"], dict.fromkeys(VARIABLES), "a function stood in for unset, or BASH_ENV ran")
        self.assertEqual(java["args"], MEMORY_OPTIONS + self.ARGUMENTS)
        self.assertEqual(java["pid"], process.pid)

    @unittest.skipUnless(sys.platform == "linux" and platform.machine() == "x86_64",
                         "the seccomp filter is written for x86-64 Linux")
    def test_a_core_limit_it_cannot_set_stops_it_before_java(self):
        process, stderr, java = self.launch(preexec=refuse_raising_limits())
        self.assertNotEqual(process.returncode, 0, "the launcher went on after ulimit failed")
        self.assertIn("ulimit", stderr)
        self.assertIsNone(java, "the JVM started without its core file limit")

    def test_diagnostics_keep_the_variables_and_the_jvm_defaults(self):
        inherited = resource.getrlimit(resource.RLIMIT_CORE)
        given = dict.fromkeys(VARIABLES, "-Xlog:disable")
        process, stderr, java = self.launch(SCARAB_JVM_DIAGNOSTICS="1", **given)
        self.assertEqual(process.returncode, 0, stderr)
        self.assertIn("memory diagnostics enabled: do not use on a forging node", stderr)
        self.assertEqual(java["variables"], given, "diagnostics dropped a variable")
        self.assertEqual(java["args"], self.ARGUMENTS, "diagnostics still passed memory options")
        self.assertEqual((java["soft"], java["hard"]), inherited, "diagnostics changed the core file limit")

    def test_diagnostics_off_is_the_default(self):
        _, _, java = self.launch(SCARAB_JVM_DIAGNOSTICS="0")
        self.assertEqual(java["args"], MEMORY_OPTIONS + self.ARGUMENTS)

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

    def test_java_is_named_by_its_absolute_path(self):
        for java in ["java", "bin/java", ""]:
            with self.subTest(java=java):
                process, stderr, recorded = self.launch(java=java, PATH=f"{self.stand_in.bin}:{os.environ['PATH']}")
                self.assertEqual(process.returncode, 2, f"{java!r} was accepted as the path of java")
                self.assertIsNone(recorded, "a java found on the path started")

    def test_the_jvm_exit_status_is_the_launchers(self):
        process, stderr, _ = self.launch(STAND_IN_EXIT="7")
        self.assertEqual(process.returncode, 7, stderr)


class CandidateImageTest(unittest.TestCase):
    def test_the_entry_point_is_the_launcher_with_the_images_java(self):
        lines = [line for line in DOCKERFILE.read_text().splitlines() if line.startswith("ENTRYPOINT ")]
        self.assertEqual(len(lines), 1, "docker/candidate.Dockerfile has one ENTRYPOINT")
        entrypoint = json.loads(lines[0].removeprefix("ENTRYPOINT "))
        self.assertEqual(entrypoint, ["/opt/scarab/scarab-jvm", "/opt/java/openjdk/bin/java",
                                      "--sun-misc-unsafe-memory-access=allow", "-jar", "/opt/scarab/scarab.jar"],
                         "the image does not start the node through the launcher")
        self.assertEqual(entrypoint, candidate_oci.ENTRYPOINT, "candidate_oci verifies another entry point")

    def test_the_image_carries_the_launcher_read_only(self):
        self.assertIn("COPY --chmod=0555 scarab-jvm /opt/scarab/scarab-jvm", DOCKERFILE.read_text().splitlines())
        self.assertEqual(LAUNCHER.read_text().splitlines()[0], "#!/bin/bash -p",
                         "the launcher does not start in bash's privileged mode")


class NixLauncherTest(unittest.TestCase):
    def setUp(self):
        self.stand_in = StandIn(self)
        self.checkout = self.stand_in.root / "checkout"
        (self.checkout / "scripts").mkdir(parents=True)
        (self.checkout / "node").mkdir()
        (self.checkout / "build.sbt").write_text("")
        self.build_record = self.stand_in.root / "build record.txt"
        (self.checkout / "scripts" / "sbtw").write_text(
            f'printf "%s" "${{JAVA_TOOL_OPTIONS-unset}}" > "{self.build_record}"\n'
            "mkdir -p node/target && printf '/stand-in/classes' > node/target/runtime-classpath.txt\n")
        shutil.copy(LAUNCHER, self.checkout / "scripts" / "scarab-jvm")
        self.data = self.stand_in.root / "data"
        self.config = self.stand_in.root / "config"
        self.config.mkdir()

    def launch(self, **environment):
        env = self.stand_in.environment(SCARAB_WRAPPER_NETWORK="preview", SCARAB_WRAPPER_QUERY_PORT="7071",
                                        SCARAB_SOURCE_DIR=str(self.checkout), SCARAB_DATA_DIR=str(self.data),
                                        JAVA_HOME=str(self.stand_in.root), **environment)
        return subprocess.run(
            ["bash", str(NIX_NETWORK), "--config", str(self.config / "config.json"),
             "--topology", str(self.config / "topology.json"), "--db", str(self.data / "db"),
             "--local-socket", str(self.data / "node.socket")],
            env=env, capture_output=True, text=True, timeout=60)

    def test_the_node_starts_through_the_launcher_and_the_build_keeps_the_environment(self):
        noop = "() { :; }"
        done = self.launch(JAVA_TOOL_OPTIONS="-Dbuild=1", **{"BASH_FUNC_ulimit%%": noop})
        self.assertEqual(done.returncode, 0, done.stderr)
        java = self.stand_in.recorded()
        self.assertEqual((java["soft"], java["hard"]), (0, 0), "the node's JVM may write a core file")
        options = java["args"][:java["args"].index("-cp")]
        self.assertEqual(options, ["-Xmx3g", "-XX:ActiveProcessorCount=4", *MEMORY_OPTIONS,
                                   "--sun-misc-unsafe-memory-access=allow", "--enable-native-access=ALL-UNNAMED"])
        self.assertIsNone(java["variables"]["JAVA_TOOL_OPTIONS"], "JAVA_TOOL_OPTIONS reached the node's JVM")
        self.assertEqual(self.build_record.read_text(), "-Dbuild=1", "the build did not get the caller's environment")

    def test_the_heap_and_processors_can_be_changed(self):
        done = self.launch(SCARAB_HEAP_SIZE="6g", SCARAB_RUN_PROCESSORS="2")
        self.assertEqual(done.returncode, 0, done.stderr)
        self.assertEqual(self.stand_in.recorded()["args"][:2], ["-Xmx6g", "-XX:ActiveProcessorCount=2"])

    def test_a_checkout_without_the_launcher_is_refused(self):
        (self.checkout / "scripts" / "scarab-jvm").unlink()
        done = self.launch()
        self.assertEqual(done.returncode, 2, done.stderr)
        self.assertIn("scripts/scarab-jvm", done.stderr)
        self.assertIsNone(self.stand_in.recorded(), "the node started without the launcher")


if __name__ == "__main__":
    unittest.main()
