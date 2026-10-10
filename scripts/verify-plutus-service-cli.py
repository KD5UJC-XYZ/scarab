#!/usr/bin/env python3
"""Compile-only service configuration gate; no bootstrap, Docker or transaction submission."""
from pathlib import Path
import os
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parent.parent
CLASSPATH = (ROOT / "app/target/runtime-classpath.txt").read_text().strip()
assert CLASSPATH and all("test-classes" not in p and not p.endswith("-tests.jar")
                         for p in CLASSPATH.split(os.pathsep)), "Compile-only runtime required"
COMMAND = ["java", "-cp", CLASSPATH, "lab.Main"]

def check(name, args, code, marker):
    result = subprocess.run(COMMAND + args, cwd=ROOT, capture_output=True, text=True, timeout=30)
    assert result.returncode == code, (name, result.returncode, result.stdout, result.stderr)
    assert marker in result.stdout + result.stderr, (name, result.stdout, result.stderr)
    print("PASS: " + name)

check("explicit service help", ["plutus-service", "--help"], 0, "loopback")
check("no implicit service startup", ["plutus-service"], 2, "PLUTUS_SERVICE_CONFIG:")
with tempfile.TemporaryDirectory(prefix="plutus-service-cli-") as temporary:
    root = Path(temporary)
    config = ["plutus-service", "--profile", "isolated-conway-pv9-plutus-v3-spend-v1",
              "--initial", str(root / "initial"), "--manifest-sha256", "0" * 64,
              "--port", "3001", "--magic", "42", "--output", str(root / "output"),
              "--duration-seconds", "30", "--max-blocks", "64"]
    for name, flag, value in [
        ("wrong profile", "--profile", "general-plutus"),
        ("public network magic", "--magic", "764824073"),
        ("zero duration", "--duration-seconds", "0"),
        ("excess duration", "--duration-seconds", "61"),
        ("zero block limit", "--max-blocks", "0"),
        ("excess block limit", "--max-blocks", "129")]:
        invalid = config.copy(); invalid[invalid.index(flag) + 1] = value
        check(name, invalid, 2, "PLUTUS_SERVICE_CONFIG:")
        assert not list(root.iterdir()), "invalid configuration touched filesystem"
    check("duplicate configuration", config + ["--max-blocks", "64"], 2, "PLUTUS_SERVICE_CONFIG:")
    assert not list(root.iterdir())
print("9 Compile-only Plutus service CLI checks passed; no live actions")
