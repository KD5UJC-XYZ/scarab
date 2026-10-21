#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Check public CHaP and fixture-inventory exceptions with CI's real gitleaks rules and synthetic negative controls."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
IMAGE = "zricethezav/gitleaks:v8.24.3@sha256:e1b35e12a8c6fa8901f060459cfb6b2fc4c484d3afbe3b029733a3bbfab07055"
TARGET = "scripts/oracle/byron/full/cabal.project"
INVENTORY = "docs/data/fixture-inventory.json"


def scan(source, expected, target_path=TARGET, git_history=False):
    with tempfile.TemporaryDirectory(prefix="scarab-secret-scan-") as temporary:
        directory = Path(temporary)
        target = directory / "repo" / target_path
        target.parent.mkdir(parents=True)
        target.write_text(source)
        if git_history:
            # CI scans git hunks, not dir mode's arbitrary byte chunks of large files.
            repo = directory / "repo"
            subprocess.run(["git", "init", "--quiet", str(repo)], check=True, capture_output=True)
            subprocess.run(["git", "-C", str(repo), "add", "."], check=True, capture_output=True)
            subprocess.run(["git", "-C", str(repo), "-c", "user.name=Scarab scanner control",
                            "-c", "user.email=scanner-control@example.invalid", "-c", "commit.gpgsign=false",
                            "commit", "--quiet", "-m", "Public inventory scanner control"],
                           check=True, capture_output=True)
        report = directory / "report.json"
        done = subprocess.run([
            "docker", "run", "--rm", "--network", "none", "--user", f"{os.getuid()}:{os.getgid()}",
            "-e", "HOME=/tmp", "-w", "/test/repo", "-v", f"{directory}:/test", "-v", f"{ROOT / '.gitleaks.toml'}:/config.toml:ro",
            IMAGE, "git" if git_history else "dir", ".", "--config", "/config.toml", "--no-banner", "--redact",
            "--exit-code", "1", "--report-format", "json", "--report-path", "/test/report.json",
        ], text=True, capture_output=True)
        findings = json.loads(report.read_text())
        locations = [(row["RuleID"], row["File"], row["StartLine"]) for row in findings]
        assert done.returncode == bool(expected), (done.returncode, done.stderr, locations[:3])
        assert all(row["File"].endswith(target_path) for row in findings), "unexpected fixture path"
        actual = {(row["RuleID"], row["StartLine"]) for row in findings}
        assert actual == expected and len(findings) == len(expected), actual


def main():
    source = (ROOT / TARGET).read_text()
    scan(source, set())
    # Generated only in a disposable directory: neither value is a real credential.
    api_token = hashlib.sha256(b"Scarab secret scanner synthetic negative control").hexdigest()
    api_line = len(source.splitlines()) + 1
    private_marker = "-" * 5 + "BEGIN RSA PRIVATE " + "KEY" + "-" * 5
    private_end = "-" * 5 + "END RSA PRIVATE " + "KEY" + "-" * 5
    # The default rule needs complete framing and a body; this body cannot encode a private key.
    private_fixture = private_marker + "\n" + "not-a-private-key-fixture!" * 4 + "\n" + private_end + "\n"
    controls = source + f'api_key = "{api_token}"\n' + private_fixture
    scan(controls, {("generic-api-key", api_line), ("private-key", api_line + 1)})
    inventory = (ROOT / INVENTORY).read_text()
    scan(inventory, set(), INVENTORY, git_history=True)
    inventory_line = len(inventory.splitlines()) + 1
    scan(inventory + f'api_key = "{api_token}"\n' + private_fixture,
         {("generic-api-key", inventory_line), ("private-key", inventory_line + 1)}, INVENTORY, git_history=True)
    # This is a real public payload digest, not an invented credential or refreshed expectation.
    entries = json.loads(inventory)["files"]
    payload = next(path for path in entries if "key" in path.lower())
    assert hashlib.sha256((ROOT / payload).read_bytes()).hexdigest() == entries[payload]
    entry = f'    "{payload}": "{entries[payload]}",\n'
    scan(entry, set(), INVENTORY)
    # Neither the same entry elsewhere nor arbitrary same-file content satisfies both criteria.
    scan(entry, {("generic-api-key", 1)}, "docs/data/fixture-inventory-copy.json")
    scan(entry, {("generic-api-key", 1)}, "other/" + INVENTORY)
    malformed = [
        f'    "api_key": "{api_token}",\n',
        f'    "{payload}": "{entries[payload].upper()}",\n',
        entry.rstrip("\n") + " extra-content\n",
    ]
    for content in malformed:
        scan(content, {("generic-api-key", 1)}, INVENTORY)
    print("Secret scan controls passed: public CHaP root and verified inventory digests allowed; "
          "same-file API/private-key, malformed entries and other-path controls detected")


if __name__ == "__main__":
    main()
