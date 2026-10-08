#!/usr/bin/env python3
"""Portable retained-source and strict TCP profile audit; never opens sockets or fetches URLs."""
import hashlib
import json
import subprocess
from pathlib import Path

root = Path(__file__).resolve().parent.parent
base = root / "fixtures/network/tcp-direct-range"
manifest = json.loads((base / "manifest.json").read_text())
assert manifest["ouroborosNetworkCommit"] == "c45735a56c567fa977969173d18943bac6bb3821"
assert manifest["externalEndpointExecutedHere"] is False
assert manifest["keepAliveImplemented"] is False
assert manifest["generalMuxDispatcher"] is False
for entry in manifest["sources"]:
    raw = (base / entry["path"]).read_bytes()
    assert len(raw) == entry["bytes"]
    assert hashlib.sha256(raw).hexdigest() == entry["sha256"], entry["path"]
for entry in manifest["licenses"]:
    raw = (base / entry["path"]).read_bytes()
    assert hashlib.sha256(raw).hexdigest() == entry["sha256"], entry["path"]
for name in ["NumericPeer.scala", "AsyncTcpTransport.scala", "TcpLimits.scala"]:
    text = (root / "network-runtime/src/main/scala/lab/network" / name).read_text()
    for forbidden in ["getByName", "getAllByName", "unsafeRunSync", "unsafeRunAndForget", "ExecutionContext.global", "unsafe.implicits.global", "newCachedThreadPool"]:
        assert forbidden not in text, (name, forbidden)
compat = json.loads((root / "compatibility.json").read_text())
tcp = compat["tcpDirectRange"]
assert tcp["profile"] == "ntn14-blockfetch-short-strict-v1"
for key in ["externalEndpointExecutedHere", "relayInteropEstablished", "keepAliveImplemented", "generalMuxDispatcher", "bodyCommitmentsValidated", "networkAuthenticated", "ledgerValidated"]:
    assert tcp[key] is False, key
assert tcp["mandatoryOriginalByteManifest"] is False
cp = (root / "app/target/runtime-classpath.txt").read_text().strip()
cases = [(["--help"], 0), (["run"], 2), (["run", "--peer", "example.invalid"], 2),
         (["run", "--latest"], 2)]
for args, code in cases:
    result = subprocess.run(["java", "-cp", cp, "lab.Main", "chain-fetch-tcp", *args],
                            cwd=root, text=True, capture_output=True, timeout=20)
    assert result.returncode == code, (args, result.returncode, result.stdout, result.stderr)
print(json.dumps({"status": "passed", "sources": len(manifest["sources"]), "directJvmPreflightCases": len(cases), "licenses": len(manifest["licenses"]), "networkExecuted": False, "scope": "portable source hashes, licenses and capability-boundary assertions"}, indent=2))
