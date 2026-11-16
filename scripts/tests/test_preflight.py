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


class WorkflowLintTest(unittest.TestCase):
    def test_private_linked_worktree_uses_owner_and_shared_metadata(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp) / "repo"
            root.mkdir(mode=0o700)
            subprocess.run(["git", "init", "-q", str(root)], check=True)
            subprocess.run(["git", "-C", str(root), "-c", "user.name=Test", "-c", "user.email=test@example.com",
                            "commit", "--allow-empty", "-qm", "initial"], check=True)
            work = pathlib.Path(tmp) / "work"
            subprocess.run(["git", "-C", str(root), "worktree", "add", "-qb", "test", str(work)], check=True)
            (work / "scripts").mkdir()
            (work / "scripts/preflight").write_text(PREFLIGHT.read_text())
            binaries = pathlib.Path(tmp) / "bin"
            binaries.mkdir()
            log = pathlib.Path(tmp) / "calls.jsonl"
            docker = binaries / "docker"
            docker.write_text(f"#!{sys.executable}\nimport json, sys\nwith open({str(log)!r}, 'a') as f: f.write(json.dumps(sys.argv[1:])+'\\n')\n")
            docker.chmod(0o755)
            for name, code in (("python3", 0), ("gh", 1)):
                path = binaries / name
                path.write_text(f"#!/bin/sh\nexit {code}\n")
                path.chmod(0o755)
            env = dict(os.environ, PATH=str(binaries)+os.pathsep+os.environ["PATH"],
                       SCARAB_PREFLIGHT_DOCKER=str(docker))
            result = subprocess.run(["bash", str(work / "scripts/preflight")], env=env,
                                    text=True, capture_output=True, timeout=30)
            self.assertEqual(result.returncode, 0, result.stdout+result.stderr)
            calls = [json.loads(line) for line in log.read_text().splitlines()]
            lint = next(args for args in calls if any("rhysd/actionlint" in arg for arg in args))
            self.assertEqual(lint[lint.index("--user")+1], f"{os.getuid()}:{os.getgid()}")
            self.assertIn(f"{root / '.git'}:{root / '.git'}:ro", lint)
            self.assertIn(f"{work}:/repo:ro", lint)


class ArgumentsTest(unittest.TestCase):
    def test_help_prints_the_description_and_runs_no_step(self):
        before = tree_state()
        with tempfile.TemporaryDirectory() as tmp:
            for flag in ("--help", "-h"):
                result = preflight(flag, docker=str(pathlib.Path(tmp) / "absent"))
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertTrue(result.stdout.startswith("The repository-wide checks CI runs"), result.stdout)
                self.assertIn("`--check` also runs", result.stdout)
                self.assertNotIn("INCOMPLETE:", result.stdout, "no gate is attempted")
        self.assertEqual(tree_state(), before, "--help must not write into the repository")

    def test_help_works_when_the_script_is_named_from_another_directory(self):
        env = dict(os.environ, SCARAB_PREFLIGHT_DOCKER="absent-docker", PYTHONDONTWRITEBYTECODE="1")
        for cwd, path in ((ROOT / "scripts", "./preflight"), (ROOT / "docs", "../scripts/preflight")):
            result = subprocess.run(["bash", path, "--help"], cwd=cwd, capture_output=True, text=True, env=env,
                                    timeout=120, check=False)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(result.stderr, "", path)
            self.assertTrue(result.stdout.startswith("The repository-wide checks CI runs"), path)

    def test_an_unknown_argument_is_refused_before_any_step(self):
        with tempfile.TemporaryDirectory() as tmp:
            for arguments in (("--chek",), ("--check", "--doctor"), ("check",)):
                result = preflight(*arguments, docker=str(pathlib.Path(tmp) / "absent"))
                self.assertEqual(result.returncode, 2, arguments)
                self.assertIn("preflight: unknown arguments", result.stderr)
                self.assertEqual(result.stdout, "", "nothing runs")


class DoctorTest(unittest.TestCase):
    def test_doctor_without_docker_exits_zero_and_writes_nothing(self):
        before = tree_state()
        with tempfile.TemporaryDirectory() as tmp:
            result = preflight("--doctor", docker=str(pathlib.Path(tmp) / "absent"))
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("docker: not found or not answering", result.stdout)
        self.assertIn("not checked (Docker unavailable)", result.stdout)
        self.assertNotIn("dependency repository", result.stdout)
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
        pin = doctor.jdk_pin((ROOT / doctor.DOCKERFILE).read_text())
        for properties, shown in ((JAVA_PROPERTIES.replace("25.0.4.1+1-LTS", "21.0.1+12"), "21.0.1+12"),
                                  (JAVA_PROPERTIES.replace("25.0.4.1+1-LTS", "25.0.4.1+10-LTS"), "25.0.4.1+10-LTS"),
                                  (JAVA_PROPERTIES.replace("Eclipse Adoptium", "Oracle Corporation"), "Oracle")):
            with self.subTest(shown=shown):
                line = doctor.jdk_line(pin, True, subprocess.CompletedProcess([], 0, "", properties))
                self.assertFalse(line.good, line.text)
                self.assertIn(shown, line.text)
        missing = doctor.jdk_line(pin, True, subprocess.CompletedProcess([], 125, "", "No such image"))
        self.assertFalse(missing.good)
        self.assertIn("not available", missing.text)

    def test_the_pin_is_the_temurin_release_the_dockerfile_names(self):
        pin = doctor.jdk_pin((ROOT / doctor.DOCKERFILE).read_text())
        self.assertIsNotNone(pin, "docker/jdk.Dockerfile names the eclipse-temurin tag of its pinned digest")
        self.assertEqual(pin.vendor, "Eclipse Adoptium")
        self.assertRegex(pin.release, r"^\d+(\.\d+)*\+\d+$")
        self.assertEqual(doctor.jdk_pin("# tag eclipse-temurin:25.0.4.1_1-jdk-resolute, index\nFROM x\n"),
                         doctor.JdkPin("Eclipse Adoptium", "25.0.4.1+1"))
        self.assertTrue(doctor.JdkPin("Eclipse Adoptium", "25.0.4.1+1").matches("Eclipse Adoptium", "25.0.4.1+1-LTS"))
        unread = doctor.jdk_line(doctor.jdk_pin("FROM eclipse-temurin@sha256:00\n"), True, None)
        self.assertFalse(unread.good)
        self.assertIn("names no eclipse-temurin tag", unread.text)


if __name__ == "__main__":
    unittest.main()
