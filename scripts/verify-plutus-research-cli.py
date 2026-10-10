#!/usr/bin/env python3
"""Offline Compile-only packaged entrypoint checks; no cluster or network submission."""
from pathlib import Path
import hashlib
import json
import os
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parent.parent
CLASSPATH = (ROOT / "app/target/runtime-classpath.txt").read_text().strip()
assert CLASSPATH and all("test-classes" not in p and not p.endswith("-tests.jar")
                         for p in CLASSPATH.split(os.pathsep)), "Compile-only runtime required"
COMMAND = ["java", "-cp", CLASSPATH, "lab.Main"]

def check(name, args, code, marker=None):
    result = subprocess.run(COMMAND + args, cwd=ROOT, capture_output=True, text=True, timeout=30)
    assert result.returncode == code, (name, result.returncode, result.stdout, result.stderr)
    if marker is not None:
        assert marker in result.stdout + result.stderr, (name, result.stdout, result.stderr)
    print("PASS: " + name)
    return result

check("explicit help does not start a profile", ["plutus-research", "--help"], 0, "loopback")
check("no configuration cannot start", ["plutus-research"], 2, "PLUTUS_RESEARCH_CONFIG:")
with tempfile.TemporaryDirectory(prefix="plutus-compile-cli-") as temporary:
    root = Path(temporary)
    config = ["plutus-research", "--profile", "isolated-conway-pv9-plutus-v3-spend-v1",
              "--initial", str(root / "initial"), "--manifest-sha256", "0" * 64,
              "--port", "3001", "--magic", "42", "--exchange", str(root / "exchange")]
    invalid = config.copy(); invalid[2] = "general-plutus"
    check("unsupported profile rejected before filesystem actions", invalid, 2, "PLUTUS_RESEARCH_CONFIG:")
    invalid = config.copy(); invalid[invalid.index("--magic") + 1] = "764824073"
    check("public magic rejected before bootstrap", invalid, 2, "PLUTUS_RESEARCH_CONFIG:")
    assert not list(root.iterdir())
    source, output = root / "original.cbor", root / "identity.json"
    raw, body, witnesses = bytes.fromhex("84a1001800a0f5f6"), bytes.fromhex("a1001800"), bytes.fromhex("a0")
    source.write_bytes(raw)
    check("original-span helper runs without Test classes", ["transaction-originals", str(source), str(output)], 0)
    record = json.loads(output.read_text())
    assert record == dict(transactionId=hashlib.blake2b(body, digest_size=32).hexdigest(),
                          envelopeSHA256=hashlib.sha256(raw).hexdigest(),
                          bodySHA256=hashlib.sha256(body).hexdigest(),
                          witnessesSHA256=hashlib.sha256(witnesses).hexdigest(), bytes=len(raw))
    preserved = output.read_bytes()
    check("existing evidence cannot be replaced", ["transaction-originals", str(source), str(output)], 2,
          "TRANSACTION_ORIGINALS_FAILED:")
    assert output.read_bytes() == preserved
    source.write_bytes(b"invalid")
    refused = root / "invalid.json"
    check("malformed original creates no success evidence", ["transaction-originals", str(source), str(refused)], 2,
          "TRANSACTION_ORIGINALS_FAILED:")
    assert not refused.exists()
    source.write_bytes(b"x" * 65537)
    check("oversize original creates no success evidence", ["transaction-originals", str(source), str(refused)], 2,
          "TRANSACTION_ORIGINALS_FAILED:")
    assert not refused.exists()
print("8 Compile-only Plutus research CLI checks passed; no live actions")
