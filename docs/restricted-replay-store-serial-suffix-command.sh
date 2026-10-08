# Historical full-research/private-corpus command, not the public acceptance profile.
# Public: bash scripts/sbtw check app/runtimeClasspathFile; python3 scripts/check-public-gates.py
set -euo pipefail
export JAVA_TOOL_OPTIONS=-XX:ActiveProcessorCount=4
export PYTHONDONTWRITEBYTECODE=1
echo "=== scripts/verify-network-cli.py ==="
python3 -u scripts/verify-network-cli.py
echo "=== scripts/verify-ledger-cli.py ==="
python3 -u scripts/verify-ledger-cli.py
echo "=== scripts/verify-witness-cli.py ==="
python3 -u scripts/verify-witness-cli.py
echo "=== scripts/verify-coverage-cli.py ==="
python3 -u scripts/verify-coverage-cli.py
echo "=== scripts/verify-fee-size-cli.py ==="
python3 -u scripts/verify-fee-size-cli.py
echo "=== scripts/verify-vrf-cli.py ==="
python3 -u scripts/verify-vrf-cli.py
echo "=== scripts/verify-praos-cli.py ==="
python3 -u scripts/verify-praos-cli.py
echo "=== scripts/verify-chain-sync-cli.py ==="
python3 -u scripts/verify-chain-sync-cli.py
echo "=== scripts/verify-chain-sync-session-cli.py ==="
python3 -u scripts/verify-chain-sync-session-cli.py
echo "=== scripts/verify-chain-fetch-cli.py ==="
python3 -u scripts/verify-chain-fetch-cli.py
echo "=== scripts/verify-block-fetch-cli.py ==="
python3 -u scripts/verify-block-fetch-cli.py
echo "=== scripts/verify-post-byron-fixtures.py ==="
python3 -u scripts/verify-post-byron-fixtures.py
echo "=== scripts/runtime-inventory.py ==="
python3 -u scripts/runtime-inventory.py
echo "=== scripts/verify-runtime-inventory.py ==="
python3 -u scripts/verify-runtime-inventory.py
echo "=== scripts/verify-tcp-direct-range.py ==="
python3 -u scripts/verify-tcp-direct-range.py
echo "=== scripts/verify-opcert-cli.py ==="
python3 -u scripts/verify-opcert-cli.py
echo "=== scripts/project-opcert-fixtures.py ==="
python3 -u scripts/project-opcert-fixtures.py
echo "=== scripts/verify-opcert-projector.py ==="
python3 -u scripts/verify-opcert-projector.py
echo "=== scripts/verify-keepalive-direct-range.py ==="
python3 -u scripts/verify-keepalive-direct-range.py
echo "=== scripts/project-sum6-fixtures.py ==="
python3 -u scripts/project-sum6-fixtures.py
echo "=== scripts/verify-sum6-projector.py ==="
python3 -u scripts/verify-sum6-projector.py
echo "=== scripts/verify-sum6-cli.py ==="
python3 -u scripts/verify-sum6-cli.py
echo "=== scripts/verify-body-commitment-projector.py ==="
python3 -u scripts/verify-body-commitment-projector.py
echo "=== scripts/verify-body-commitment-cli.py ==="
python3 -u scripts/verify-body-commitment-cli.py
echo "=== scripts/verify-block-evidence-projector.py ==="
python3 -u scripts/verify-block-evidence-projector.py
echo "=== scripts/verify-block-evidence-cli.py ==="
python3 -u scripts/verify-block-evidence-cli.py
echo "=== scripts/project-restricted-replay-fixtures.py ==="
python3 -u scripts/project-restricted-replay-fixtures.py
echo "=== scripts/verify-restricted-replay-projector.py ==="
python3 -u scripts/verify-restricted-replay-projector.py
echo "=== scripts/verify-restricted-replay-cli.py ==="
python3 -u scripts/verify-restricted-replay-cli.py
echo "=== scripts/verify-restricted-replay-store-cli.py ==="
python3 -u scripts/verify-restricted-replay-store-cli.py
echo "SERIAL GATES 2 THROUGH 31 PASSED"
