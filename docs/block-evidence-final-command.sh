# Historical full-research/private-corpus command, not the public acceptance profile.
# Public: bash scripts/sbtw check app/runtimeClasspathFile; python3 scripts/check-public-gates.py
#!/usr/bin/env bash
set -euo pipefail
export JAVA_TOOL_OPTIONS=-XX:ActiveProcessorCount=4
./scripts/sbtw scalafmtAll check app/runtimeClasspathFile > docs/block-evidence-final-build-verification.log 2>&1
for script in scripts/verify-body-commitment-cli.py scripts/verify-block-evidence-projector.py scripts/verify-block-evidence-cli.py scripts/runtime-inventory.py scripts/verify-runtime-inventory.py; do
  printf '\n=== %s ===\n' "$script"
  python3 -B "$script"
done
