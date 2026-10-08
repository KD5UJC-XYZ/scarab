# Historical full-research/private-corpus command, not the public acceptance profile.
# Public: bash scripts/sbtw check app/runtimeClasspathFile; python3 scripts/check-public-gates.py
set -euo pipefail
export JAVA_TOOL_OPTIONS=-XX:ActiveProcessorCount=4
export PYTHONDONTWRITEBYTECODE=1
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
echo "SERIAL GATES 16 THROUGH 30 PASSED; DURABLE GATE31 VERIFIED SEPARATELY"
