#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Check the public CHaP exception with CI's real gitleaks rules and synthetic negative controls."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
IMAGE = "zricethezav/gitleaks:v8.24.3@sha256:e1b35e12a8c6fa8901f060459cfb6b2fc4c484d3afbe3b029733a3bbfab07055"
TARGET = "scripts/oracle/byron/full/cabal.project"


def scan(source, expected):
    with tempfile.TemporaryDirectory(prefix="scarab-secret-scan-") as temporary:
        directory = Path(temporary)
        target = directory / "repo" / TARGET
        target.parent.mkdir(parents=True)
        target.write_text(source)
        report = directory / "report.json"
        done = subprocess.run([
            "docker", "run", "--rm", "--network", "none", "--user", f"{os.getuid()}:{os.getgid()}",
            "-e", "HOME=/tmp", "-v", f"{directory}:/test", "-v", f"{ROOT / '.gitleaks.toml'}:/config.toml:ro",
            IMAGE, "dir", "/test/repo", "--config", "/config.toml", "--no-banner", "--redact",
            "--exit-code", "1", "--report-format", "json", "--report-path", "/test/report.json",
        ], text=True, capture_output=True)
        assert done.returncode == bool(expected), (done.returncode, done.stderr)
        findings = json.loads(report.read_text())
        assert all(row["File"].endswith(TARGET) for row in findings), "unexpected fixture path"
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
    print("Secret scan controls passed: public root allowed; different same-file API token and private-key rule detected")


if __name__ == "__main__":
    main()
