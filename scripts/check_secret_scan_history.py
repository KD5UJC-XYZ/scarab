#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Exercise CI's actual pinned scanner command against disposable Git histories."""
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def workflow_command():
    source = (ROOT / ".github/workflows/ci.yml").read_text()
    images = re.findall(r"(?m)^\s+(zricethezav/gitleaks:[^\s\\]+)\s*\\$", source)
    commands = re.findall(r"(?m)^\s+(git /repo [^\n]+)$", source)
    assert len(images) == len(commands) == 1, "expected one pinned CI history scanner"
    return images[0], shlex.split(commands[0])


def git(repo, *args):
    return subprocess.check_output(["git", "-C", str(repo), *args], text=True).strip()


def commit(repo, message):
    git(repo, "add", "--all")
    git(repo, "commit", "--quiet", "--allow-empty", "-m", message)
    return git(repo, "rev-parse", "HEAD")


def add_control(repo, path, label):
    # Generated only in the disposable repository; this digest is not a credential.
    value = hashlib.sha256(label.encode()).hexdigest()
    (repo / path).write_text(f'api_key = "{value}"\n')
    return commit(repo, label)


def scan(repo, image, command, expected):
    with tempfile.TemporaryDirectory(prefix="scarab-scan-report-") as temporary:
        report = Path(temporary) / "findings.json"
        done = subprocess.run([
            "docker", "run", "--rm", "--network", "none", "--user", f"{os.getuid()}:{os.getgid()}",
            "-e", "HOME=/tmp", "-v", f"{repo}:/repo:ro", "-v", f"{temporary}:/report", "-w", "/repo",
            image, *command, "--report-format", "json", "--report-path", "/report/findings.json",
        ], text=True, capture_output=True)
        assert done.returncode == bool(expected), (done.returncode, done.stderr)
        findings = json.loads(report.read_text())
        actual = {(row["RuleID"], row["File"], row["Commit"]) for row in findings}
        assert actual == expected and len(findings) == len(expected), actual


def main():
    image, command = workflow_command()
    with tempfile.TemporaryDirectory(prefix="scarab-scan-history-") as temporary:
        repo = Path(temporary)
        subprocess.run(["git", "init", "--quiet", "--initial-branch=main", str(repo)], check=True)
        git(repo, "config", "user.name", "Synthetic scan control")
        git(repo, "config", "user.email", "scan-control@example.invalid")
        git(repo, "config", "commit.gpgsign", "false")
        git(repo, "config", "core.hooksPath", "/dev/null")
        shutil.copyfile(ROOT / ".gitleaks.toml", repo / ".gitleaks.toml")
        baseline = commit(repo, "clean checked-out history")

        git(repo, "checkout", "--quiet", "-b", "unmerged")
        unrelated = add_control(repo, "unmerged-only.txt", "Scarab unrelated-ref negative control")
        git(repo, "update-ref", "refs/remotes/origin/unmerged", unrelated)
        git(repo, "checkout", "--quiet", "main")
        git(repo, "branch", "--delete", "--force", "unmerged")
        # A fetched remote branch is present but is not part of this checkout's ancestry.
        scan(repo, image, command, set())
        # Reproduce the old scanner's --all behavior with the identical image/config/history.
        all_refs = [arg for arg in command if not arg.startswith("--log-opts")]
        all_refs.append("--log-opts=--full-history --all")
        scan(repo, image, all_refs, {("generic-api-key", "unmerged-only.txt", unrelated)})

        historical = add_control(repo, "deleted-secret.txt", "Scarab deleted-history positive control")
        (repo / "deleted-secret.txt").unlink()
        commit(repo, "delete the checked-out historical control")
        expected = {("generic-api-key", "deleted-secret.txt", historical)}
        scan(repo, image, command, expected)

        git(repo, "checkout", "--quiet", "-b", "merged", baseline)
        merged = add_control(repo, "merged-secret.txt", "Scarab second-parent positive control")
        (repo / "merged-secret.txt").unlink()
        commit(repo, "delete the second-parent historical control")
        git(repo, "checkout", "--quiet", "main")
        git(repo, "merge", "--quiet", "--no-ff", "merged", "-m", "merge the historical control")
        assert len(git(repo, "rev-list", "--parents", "-n", "1", "HEAD").split()) == 3
        expected.add(("generic-api-key", "merged-secret.txt", merged))
        scan(repo, image, command, expected)
    print("History scan controls passed: unrelated ref excluded; old --all reproduces it; "
          "deleted and merged-parent secrets detected")


if __name__ == "__main__":
    main()
