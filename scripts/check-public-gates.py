#!/usr/bin/env python3
"""Run the explicit public serial gates. Private-corpus gates are not executed."""
import os
import subprocess
from pathlib import Path
root = Path(__file__).resolve().parents[1]
env = dict(os.environ, JAVA_TOOL_OPTIONS="-XX:ActiveProcessorCount=4", PYTHONDONTWRITEBYTECODE="1")
gates = ['verify-cli.py', 'verify-network-cli.py', 'verify-ledger-cli.py', 'verify-witness-cli.py', 'verify-coverage-cli.py', 'verify-fee-size-cli.py', 'verify-vrf-cli.py', 'verify-praos-cli.py', 'verify-chain-sync-cli.py', 'verify-chain-sync-session-cli.py', 'verify-block-fetch-cli.py', 'runtime-inventory.py', 'verify-runtime-inventory.py', 'verify-tcp-direct-range.py', 'verify-opcert-cli.py', 'project-opcert-fixtures.py', 'verify-opcert-projector.py', 'verify-keepalive-direct-range.py', 'project-sum6-fixtures.py', 'verify-sum6-projector.py', 'verify-sum6-cli.py', 'project-restricted-replay-fixtures.py', 'verify-restricted-replay-projector.py', 'verify-restricted-replay-cli.py', 'verify-restricted-replay-store-cli.py']
for index, gate in enumerate(gates, 1):
    print(f"PUBLIC GATE {index}/{len(gates)}: {gate}", flush=True)
    subprocess.run(["python3", "-u", str(root / "scripts" / gate)], cwd=root, env=env, check=True)
print(f"PUBLIC SERIAL GATES PASSED: {len(gates)}; PRIVATE GATES NOT RUN: 6", flush=True)
