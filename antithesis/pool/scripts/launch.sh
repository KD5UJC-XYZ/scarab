#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Validates, then launches one basic_test run of the pool workload.
#   launch.sh FAULT_TOLERANCE DURATION [CONFIG_IMAGE]   for example: launch.sh resilient 15m
# Without CONFIG_IMAGE, snouty pushes the three images and the config to the tenant registry. With the reference that
# publish.sh prints, the run pulls everything from that public registry instead.
# The tenant predates --max-compute-hours (release 66), so the duration bounds the run: 15m on the standard tier
# (48 cores) is 12 compute hours, plus setup.
set -euo pipefail

faults=$1
duration=$2
here=$(cd "$(dirname "$0")/.." && pwd)
if [ -n "${3:-}" ]; then
  config=(--config-image "$3")
else
  config=(--config "$here/config")
fi

"$here/scripts/validate.sh"
SNOUTY_CONTAINER_ENGINE=docker exec snouty launch \
  --launcher basic_test \
  "${config[@]}" \
  --test-name scarab-pool \
  --description "Scarab v0.2.0-alpha.1 pool with two cardano-node 11.1.3 pools, create-env defaults" \
  --duration "$duration" \
  --fault-tolerance "$faults" \
  --fault-exclusions '^driver$' \
  --param attrs.scarab=0.2.0-alpha.1-4636ab355 \
  --param attrs.reference=cardano-node-11.1.3 \
  --param attrs.plugin_version=0.1.0
