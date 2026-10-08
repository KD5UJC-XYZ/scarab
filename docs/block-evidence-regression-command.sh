# Historical full-research/private-corpus command, not the public acceptance profile.
# Public: bash scripts/sbtw check app/runtimeClasspathFile; python3 scripts/check-public-gates.py
#!/usr/bin/env bash
set -euo pipefail
export JAVA_TOOL_OPTIONS=-XX:ActiveProcessorCount=4
scripts=(
  scripts/verify-cli.py scripts/verify-network-cli.py scripts/verify-ledger-cli.py
  scripts/verify-witness-cli.py scripts/verify-coverage-cli.py scripts/verify-fee-size-cli.py
  scripts/verify-vrf-cli.py scripts/verify-praos-cli.py scripts/verify-chain-sync-cli.py
  scripts/verify-chain-sync-session-cli.py scripts/verify-chain-fetch-cli.py scripts/verify-block-fetch-cli.py
  scripts/verify-post-byron-fixtures.py scripts/runtime-inventory.py scripts/verify-runtime-inventory.py
  scripts/verify-tcp-direct-range.py scripts/verify-opcert-cli.py scripts/project-opcert-fixtures.py
  scripts/verify-opcert-projector.py scripts/verify-keepalive-direct-range.py scripts/project-sum6-fixtures.py
  scripts/verify-sum6-projector.py scripts/verify-sum6-cli.py
  scripts/verify-body-commitment-projector.py scripts/verify-body-commitment-cli.py
  scripts/verify-block-evidence-projector.py scripts/verify-block-evidence-cli.py
)
for script in "${scripts[@]}"; do
  printf '\n=== %s ===\n' "$script"
  python3 -B "$script"
done
