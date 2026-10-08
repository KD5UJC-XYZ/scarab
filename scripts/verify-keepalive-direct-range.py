#!/usr/bin/env python3
"""Portable pinned KeepAlive/source/capability audit and CLI preflight; no sockets or URLs."""
import hashlib
import json
import subprocess
from pathlib import Path

root = Path(__file__).resolve().parent.parent
base = root / "fixtures/network/keepalive-direct-range"
manifest = json.loads((base / "manifest.json").read_text())
assert manifest["ouroborosNetworkCommit"] == "c45735a56c567fa977969173d18943bac6bb3821"
assert manifest["externalEndpointExecutedHere"] is False
assert manifest["relayInteropEstablished"] is False
for entry in manifest["sources"] + manifest["licenses"]:
    raw = (base / entry["path"]).read_bytes()
    assert hashlib.sha256(raw).hexdigest() == entry["sha256"], entry["path"]
    if "bytes" in entry:
        assert len(raw) == entry["bytes"]
compat = json.loads((root / "compatibility.json").read_text())["keepAliveTcpDirectRange"]
for key in ["keepAliveCodecImplemented", "keepAliveRuntimeImplemented", "boundedDualProtocolRoutingTested", "localhostTcpTested"]:
    assert compat[key] is True
for key in ["externalEndpointExecutedHere", "relayInteropEstablished", "networkAuthenticated", "bodyCommitmentsValidated", "ledgerValidated", "consensusValidated", "mithrilAuthenticated", "referenceReplayChecked", "generalMuxDispatcher"]:
    assert compat[key] is False
for path in [root / "network/src/main/scala/lab/network/KeepAlive.scala", *root.glob("network-runtime/src/main/scala/lab/network/KeepAlive*.scala")]:
    source = path.read_text()
    for forbidden in ["unsafeRunSync", "unsafeRunAndForget", "ExecutionContext.global", "unsafe.implicits.global", "getByName", "getAllByName", "newCachedThreadPool"]:
        assert forbidden not in source, (str(path), forbidden)
cp = (root / "app/target/runtime-classpath.txt").read_text().strip()
cases = [( ["--help"], 0), (["run"], 2), (["run", "--peer", "example.invalid"], 2), (["run", "--latest"], 2)]
for args, expected in cases:
    result = subprocess.run(["java", "-cp", cp, "lab.Main", "chain-fetch-tcp-keepalive", *args], cwd=root, text=True, capture_output=True, timeout=20)
    assert result.returncode == expected, (args, result.returncode, result.stdout, result.stderr)
    if args == ["--help"]:
        assert "chain-fetch-tcp-keepalive" in result.stdout
        assert "graceful-required" in result.stdout
print(json.dumps({"status":"passed", "sources":len(manifest["sources"]), "licenses":len(manifest["licenses"]), "directJvmPreflightCases":len(cases), "externalNetworkExecuted":False}, indent=2))
