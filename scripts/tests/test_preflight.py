# SPDX-License-Identifier: Apache-2.0
"""`scripts/preflight` without Docker, and its `--doctor` mode (issue 560). Docker is replaced through
SCARAB_PREFLIGHT_DOCKER by a missing command or a stub, so the tests never need or touch a real daemon.

Run from the repository root:  python3 -m unittest discover -s scripts/tests -v
"""
import json
import os
import pathlib
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))

import preflight_doctor as doctor  # noqa: E402

ROOT = pathlib.Path(__file__).resolve().parents[2]
PREFLIGHT = ROOT / "scripts" / "preflight"
INCOMPLETE = "INCOMPLETE: Docker unavailable, gates not run\n"
JAVA_PROPERTIES = ("Property settings:\n    java.runtime.version = 25.0.4.1+1-LTS\n"
                   "    java.vendor = Eclipse Adoptium\n    java.version = 25.0.4.1\n")


def preflight(*arguments, docker):
    env = dict(os.environ, SCARAB_PREFLIGHT_DOCKER=docker, PYTHONDONTWRITEBYTECODE="1")
    return subprocess.run(["bash", str(PREFLIGHT), *arguments], capture_output=True, text=True, env=env,
                          timeout=120, check=False)


def tree_state():
    return subprocess.run(["git", "-C", str(ROOT), "status", "--porcelain", "--ignored"], capture_output=True,
                          text=True, check=True).stdout


class StubDocker:
    """A `docker` whose `info` exits with `info_status`; every call is appended to a log file."""

    def __init__(self, directory, info_status):
        self.path = pathlib.Path(directory) / "docker"
        self.log = pathlib.Path(directory) / "calls"
        self.path.write_text(f'#!/bin/sh\necho "$*" >> "{self.log}"\n'
                             f'[ "$1" = info ] && {{ echo "Cannot connect to the Docker daemon" >&2; exit {info_status}; }}\n'
                             'exit 1\n')
        self.path.chmod(0o755)

    def calls(self):
        return self.log.read_text().splitlines() if self.log.exists() else []


class IncompleteTest(unittest.TestCase):
    def assert_incomplete(self, result):
        self.assertEqual(result.returncode, 3, f"distinct exit status expected, got {result.returncode}")
        self.assertEqual(result.stdout, INCOMPLETE, "exactly one INCOMPLETE line")
        self.assertEqual(result.stderr, "")
        for green in ("ok", "passed", "FAILED", "=="):
            self.assertNotIn(green, result.stdout)

    def test_no_docker_command_stops_at_once(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.assert_incomplete(preflight(docker=str(pathlib.Path(tmp) / "absent")))

    def test_an_unreachable_daemon_stops_at_once_and_runs_no_step(self):
        with tempfile.TemporaryDirectory() as tmp:
            stub = StubDocker(tmp, info_status=1)
            self.assert_incomplete(preflight("--check", docker=str(stub.path)))
            self.assertEqual(stub.calls(), ["info"], "only the capability probe may reach Docker")


class DoctorTest(unittest.TestCase):
    def test_doctor_without_docker_exits_zero_and_writes_nothing(self):
        before = tree_state()
        with tempfile.TemporaryDirectory() as tmp:
            result = preflight("--doctor", docker=str(pathlib.Path(tmp) / "absent"))
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("docker: not found or not answering", result.stdout)
        self.assertIn("not checked (Docker unavailable)", result.stdout)
        self.assertIn("dependency repository:", result.stdout)
        self.assertIn("cache:", result.stdout)
        self.assertNotIn("INCOMPLETE:", result.stdout)
        self.assertEqual(tree_state(), before, "--doctor must not write into the repository")

    def test_doctor_asks_the_jdk_image_never_a_host_java(self):
        calls = []

        def runner(command, timeout=120):
            calls.append(command)
            if command[1] == "info":
                return subprocess.CompletedProcess(command, 0, "29.0.0\n", "")
            return subprocess.CompletedProcess(command, 0, "", JAVA_PROPERTIES)

        lines = doctor.report(ROOT, "stub-docker", {}, runner)
        self.assertTrue(all(c[0] == "stub-docker" for c in calls), f"every command goes through Docker: {calls}")
        self.assertIn(doctor.JDK_IMAGE, calls[1])
        jdk = next(line for line in lines if line.topic == "jdk")
        self.assertTrue(jdk.good, jdk.text)

    def test_a_different_jdk_in_the_image_is_a_warning(self):
        manifest = json.loads((ROOT / doctor.MANIFEST).read_text())
        other = subprocess.CompletedProcess([], 0, "", JAVA_PROPERTIES.replace("25.0.4.1+1-LTS", "21.0.1+12"))
        line = doctor.jdk_line(manifest, True, other)
        self.assertFalse(line.good)
        self.assertIn("21.0.1+12", line.text)
        missing = doctor.jdk_line(manifest, True, subprocess.CompletedProcess([], 125, "", "No such image"))
        self.assertFalse(missing.good)
        self.assertIn("not available", missing.text)

    def test_the_prepared_repository_is_found_under_the_cache(self):
        with tempfile.TemporaryDirectory() as tmp:
            cache = pathlib.Path(tmp)
            self.assertFalse(doctor.repository_line(cache, "abc").good)
            (cache / "scarab" / "patched-scalus" / "abc" / "repository").mkdir(parents=True)
            self.assertTrue(doctor.repository_line(cache, "abc").good)


if __name__ == "__main__":
    unittest.main()
