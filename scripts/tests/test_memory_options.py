# SPDX-License-Identifier: Apache-2.0
"""The shipped launchers keep a forging node's memory off disk (docs/running/forging.md, "Keys in memory").

The release image's entry point (`docker/candidate.Dockerfile`) is the one `candidate_oci` verifies, with its JVM
options before `-jar`; the Nix network apps (`scripts/nix-network`) start the JVM with the same options and a core
file limit of 0. The Nix launcher runs from a stand-in checkout whose `sbtw` writes a class path, with a stand-in
`java` that prints its core file limit, `_JAVA_OPTIONS` and its arguments.
"""
import json
import os
from pathlib import Path
import resource
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import candidate_oci

REPO = Path(__file__).resolve().parents[2]
DOCKERFILE = REPO / "docker" / "candidate.Dockerfile"
NIX_NETWORK = REPO / "scripts" / "nix-network"


class CandidateImageTest(unittest.TestCase):
    def test_the_entry_point_is_the_one_the_candidate_verification_requires(self):
        lines = [line for line in DOCKERFILE.read_text().splitlines() if line.startswith("ENTRYPOINT ")]
        self.assertEqual(len(lines), 1, "docker/candidate.Dockerfile has one ENTRYPOINT")
        entrypoint = json.loads(lines[0].removeprefix("ENTRYPOINT "))
        self.assertEqual(entrypoint, candidate_oci.ENTRYPOINT,
                         "the image's entry point differs from candidate_oci.ENTRYPOINT, so a candidate would fail")

    def test_the_memory_options_come_before_the_jar(self):
        jar = candidate_oci.ENTRYPOINT.index("-jar")
        for option in candidate_oci.MEMORY_OPTIONS:
            self.assertIn(option, candidate_oci.ENTRYPOINT[:jar],
                          f"{option} is not a JVM option of the entry point, so the node would run without it")


class NixLauncherTest(unittest.TestCase):
    def setUp(self):
        scratch = tempfile.TemporaryDirectory()
        self.addCleanup(scratch.cleanup)
        root = Path(scratch.name)
        self.checkout = root / "checkout"
        (self.checkout / "scripts").mkdir(parents=True)
        (self.checkout / "node").mkdir()
        (self.checkout / "build.sbt").write_text("")
        (self.checkout / "scripts" / "sbtw").write_text(
            "mkdir -p node/target && printf '/stand-in/classes' > node/target/runtime-classpath.txt\n")
        bin_dir = root / "bin"
        bin_dir.mkdir()
        java = bin_dir / "java"
        java.write_text('#!/usr/bin/env bash\nulimit -c\nprintf "%s\\n" "${_JAVA_OPTIONS-unset}" "$@"\n')
        java.chmod(0o755)
        self.path = f"{bin_dir}:{os.environ['PATH']}"
        self.data = root / "data"
        self.config = root / "config"
        self.config.mkdir()

    def launch(self, **environment):
        env = {k: v for k, v in os.environ.items() if k not in ("_JAVA_OPTIONS", "SCARAB_CORE_LIMIT")}
        env.update(PATH=self.path, SCARAB_WRAPPER_NETWORK="preview", SCARAB_WRAPPER_QUERY_PORT="7071",
                   SCARAB_SOURCE_DIR=str(self.checkout), SCARAB_DATA_DIR=str(self.data), **environment)
        done = subprocess.run(
            ["bash", str(NIX_NETWORK), "--config", str(self.config / "config.json"),
             "--topology", str(self.config / "topology.json"), "--db", str(self.data / "db"),
             "--local-socket", str(self.data / "node.socket")],
            env=env, capture_output=True, text=True, timeout=60)
        self.assertEqual(done.returncode, 0, done.stderr)
        limit, java_options, *arguments = done.stdout.splitlines()
        return limit, java_options, arguments

    def test_the_jvm_starts_with_the_memory_options_and_no_core_file(self):
        limit, java_options, arguments = self.launch()
        self.assertEqual(limit, "0", "the JVM may write a core file")
        self.assertEqual(java_options, "unset", "the launcher set _JAVA_OPTIONS itself")
        options = arguments[:arguments.index("-cp")]
        for option in candidate_oci.MEMORY_OPTIONS:
            self.assertIn(option, options, f"{option} is not among the JVM options {options}")

    def test_the_opt_in_reaches_the_jvm(self):
        hard = resource.getrlimit(resource.RLIMIT_CORE)[1]
        if hard == 0:
            self.skipTest("the hard core file limit is 0 here, so no limit above 0 can be asked for")
        # bash's ulimit -c counts 1024-byte blocks
        wanted = "unlimited" if hard == resource.RLIM_INFINITY else str(hard // 1024)
        limit, java_options, _ = self.launch(SCARAB_CORE_LIMIT=wanted, _JAVA_OPTIONS="-XX:+CreateCoredumpOnCrash")
        self.assertEqual(limit, wanted, "SCARAB_CORE_LIMIT did not set the JVM's core file limit")
        self.assertEqual(java_options, "-XX:+CreateCoredumpOnCrash", "_JAVA_OPTIONS did not reach the JVM")


if __name__ == "__main__":
    unittest.main()
