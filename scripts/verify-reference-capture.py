#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Offline regressions over explicitly supplied local development-cluster evidence."""
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile


def main():
    if len(sys.argv) != 2:
        raise SystemExit("usage: verify-reference-capture.py LOCAL_CAPTURE_EVIDENCE_DIRECTORY")
    evidence = Path(sys.argv[1])
    capture = evidence / "scala-capture.md"
    if capture.stat().st_size > 3 * 1024 * 1024:
        raise ValueError("capture evidence too large")
    records = [json.loads(line) for line in capture.read_text().splitlines() if line.startswith("{")]
    header = next(r for r in records if r.get("record") == "header")
    block = next(r for r in records if r.get("record") == "block")
    observation = next(r for r in records if r.get("passed") is True)
    anchor = json.loads((evidence / "capture-anchor.md").read_text())
    raw = bytes.fromhex(block["rawHex"])
    envelope = bytes.fromhex(header["envelopeHex"])
    if hashlib.sha256(raw).hexdigest() != observation["rawBlockSha256"]:
        raise ValueError("capture bytes do not match recorded digest")
    if raw[-1] != 0x80:
        raise ValueError("current mutation profile requires an empty invalid-transaction-index list")
    root = Path(__file__).resolve().parent.parent
    cp = (root / "app/target/runtime-classpath.txt").read_text().strip()
    cases = [
        ("original-live-bytes", envelope, raw, anchor["hash"], True, None),
        ("truncated-block", envelope, raw[:-1], anchor["hash"], False, None),
        ("different-original-header", envelope[:-1] + bytes([envelope[-1] ^ 1]), raw,
         anchor["hash"], False, "header/block identity mismatch"),
        ("wrong-anchor-parent", envelope, raw, "00" * 32, False,
         "successor does not extend supplied anchor"),
        ("body-commitment-mutation", envelope, raw[:-1] + bytes.fromhex("8100"),
         anchor["hash"], False, "body commitment mismatch"),
    ]
    results = []
    with tempfile.TemporaryDirectory(prefix="capture-regression-") as directory:
        directory = Path(directory)
        for name, h, b, parent, accepted, expected_error in cases:
            hp, bp = directory / "header.hex", directory / "block.hex"
            hp.write_text(h.hex()); bp.write_text(b.hex())
            command = ["java", "-XX:ActiveProcessorCount=1", "-Xmx512m", "-cp", cp,
                       "lab.Main", "reference-capture-check", str(hp), str(bp),
                       str(anchor["slot"]), parent]
            result = subprocess.run(command, capture_output=True, text=True, timeout=15)
            if (result.returncode == 0) != accepted:
                raise AssertionError(name + ": " + result.stdout + result.stderr)
            if expected_error and expected_error not in result.stdout:
                raise AssertionError(name + " failed for the wrong reason: " + result.stdout)
            if accepted:
                report = json.loads(result.stdout)
                if report["headerProtocolVersion"] != observation["headerProtocolVersion"]:
                    raise AssertionError("advertised header version changed")
                if report["rawBlockSha256"] != observation["rawBlockSha256"]:
                    raise AssertionError("original block digest changed")
            results.append({"case": name, "expectedAccepted": accepted,
                            "exitCode": result.returncode, "passed": True})
    print(json.dumps({"scope": "offline-regression-of-local-live-capture", "checks": results,
                      "blockSha256": observation["rawBlockSha256"],
                      "headerProtocolVersion": observation["headerProtocolVersion"],
                      "ledgerConformance": False}, indent=2))


if __name__ == "__main__":
    main()
