# SPDX-License-Identifier: Apache-2.0
"""The submission action keeps its command and credentials across the Docker launcher."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "dependency_sbt"


class DependencySbtTests(unittest.TestCase):
    def test_action_command_and_context_reach_container_without_token_in_argv(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            capture = root / "arguments.json"
            docker = root / "docker"
            docker.write_text(
                "#!/usr/bin/env python3\n"
                "import json, os, pathlib, sys\n"
                "pathlib.Path(os.environ['CAPTURE']).write_text(json.dumps(sys.argv[1:]))\n"
                "sys.exit(23)\n"
            )
            docker.chmod(0o755)
            command = 'githubGenerateSnapshot {"ignoredConfigs":["scala-tool","scala-doc-tool"]}; githubSubmitSnapshot'
            env = dict(os.environ, PATH=f"{root}:{os.environ['PATH']}", CAPTURE=str(capture))
            env.update(
                GITHUB_WORKSPACE=str(root / "workspace with spaces"),
                RUNNER_TEMP=str(root / "temporary"),
                GITHUB_OUTPUT=str(root / "temporary" / "output"),
                GITHUB_TOKEN="synthetic-secret-not-an-argument",
                SCARAB_BUILD_PROCESSORS="2",
            )
            result = subprocess.run(
                ["bash", str(SCRIPT), "--batch", command], env=env, capture_output=True, text=True
            )
            self.assertEqual(result.returncode, 23)
            args = json.loads(capture.read_text())
            self.assertEqual(args[-3:], ["bash", "scripts/sbtw", command])
            self.assertNotIn("--batch", args)
            self.assertIn("--cpus=2", args)
            self.assertIn("GITHUB_TOKEN", args)
            self.assertNotIn(env["GITHUB_TOKEN"], json.dumps(args) + result.stdout + result.stderr)
            self.assertIn(f"{env['GITHUB_WORKSPACE']}:{env['GITHUB_WORKSPACE']}", args)
            self.assertIn(f"{env['RUNNER_TEMP']}:{env['RUNNER_TEMP']}", args)
            self.assertIn("SBT_GLOBAL_BASE=/tmp/scarab-dependency-sbt", args)

    def test_missing_action_context_fails_before_docker(self):
        env = {key: value for key, value in os.environ.items() if key != "GITHUB_WORKSPACE"}
        result = subprocess.run(
            ["bash", str(SCRIPT), "--batch", "exit"], env=env, capture_output=True, text=True
        )
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("GITHUB_WORKSPACE", result.stderr)


if __name__ == "__main__":
    unittest.main()
