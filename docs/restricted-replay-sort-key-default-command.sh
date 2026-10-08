# Historical full-research/private-corpus command, not the public acceptance profile.
# Public: bash scripts/sbtw check app/runtimeClasspathFile; python3 scripts/check-public-gates.py
#!/usr/bin/env bash
set -euo pipefail
export JAVA_TOOL_OPTIONS=-XX:ActiveProcessorCount=4
./scripts/sbtw \
  'show Global / concurrentRestrictions' \
  'show core / Test / fork' 'show core / Test / parallelExecution' \
  'show vm / Test / fork' 'show vm / Test / parallelExecution' \
  'show network / Test / fork' 'show network / Test / parallelExecution' \
  'show networkRuntime / Test / fork' 'show networkRuntime / Test / parallelExecution' \
  'show ledger / Test / fork' 'show ledger / Test / parallelExecution' \
  'show fetcher / Test / fork' 'show fetcher / Test / parallelExecution' \
  'show app / Test / fork' 'show app / Test / parallelExecution' \
  check app/runtimeClasspathFile
