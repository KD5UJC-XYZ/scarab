# Historical full-research/private-corpus command, not the public acceptance profile.
# Public: bash scripts/sbtw check app/runtimeClasspathFile; python3 scripts/check-public-gates.py
set -euo pipefail
export JAVA_TOOL_OPTIONS=-XX:ActiveProcessorCount=4
export PYTHONDONTWRITEBYTECODE=1
echo "=== scripts/verify-chain-fetch-cli.py ==="
python -u scripts/verify-chain-fetch-cli.py
echo "=== scripts/verify-block-fetch-cli.py ==="
python -u scripts/verify-block-fetch-cli.py
echo "=== scripts/verify-post-byron-fixtures.py ==="
python -u scripts/verify-post-byron-fixtures.py
echo "=== scripts/runtime-inventory.py ==="
python -u scripts/runtime-inventory.py
echo "=== scripts/verify-runtime-inventory.py ==="
python -u scripts/verify-runtime-inventory.py
echo "=== scripts/verify-tcp-direct-range.py ==="
python -u scripts/verify-tcp-direct-range.py
echo "=== scripts/verify-opcert-cli.py ==="
python -u scripts/verify-opcert-cli.py
echo "=== scripts/project-opcert-fixtures.py ==="
python -u scripts/project-opcert-fixtures.py
echo "=== scripts/verify-opcert-projector.py ==="
python -u scripts/verify-opcert-projector.py
echo "=== scripts/verify-keepalive-direct-range.py ==="
python -u scripts/verify-keepalive-direct-range.py
echo "=== scripts/project-sum6-fixtures.py ==="
python -u scripts/project-sum6-fixtures.py
echo "=== scripts/verify-sum6-projector.py ==="
python -u scripts/verify-sum6-projector.py
echo "=== scripts/verify-sum6-cli.py ==="
python -u scripts/verify-sum6-cli.py
echo "=== scripts/verify-body-commitment-projector.py ==="
python -u scripts/verify-body-commitment-projector.py
echo "=== scripts/verify-body-commitment-cli.py ==="
python -u scripts/verify-body-commitment-cli.py
echo "15 RESUMED SERIAL GATES PASSED; PRIOR10 PASSED IN ATTEMPT1"
