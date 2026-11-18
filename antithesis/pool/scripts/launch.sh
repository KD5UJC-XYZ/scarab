#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Validates, then launches one run of the pool workload; snouty pushes the images and the config to the tenant
# registry.
#   [CONFIG=config-canary] [LAUNCHER=basic_test] launch.sh FAULTS DURATION   for example: launch.sh on 15m
# FAULTS is on or off. CONFIG names the config directory (default config); the canary's run is named scarab-canary.
# The run records the commit of the Scarab image that build.sh labelled.
# The default launcher is the tenant's own, amaru-cardano, as cardano-foundation/moog launches it: it applies node
# faults (kill, stop, pause), network faults and clock skips, and takes a comma-separated list of compose services to
# leave out of each class (`custom.*_exclusion`, moog spec 107); the driver is left out of all of them. On this tenant
# basic_test applied network faults only, whatever --fault-tolerance said, and faulted the driver too (README, "did
# not apply"). LAUNCHER=basic_test keeps it for comparison, FAULTS on as high_availability and off as none.
# The duration bounds the run: a 15m run cost 15 to 20 compute hours, about 7 of them setup. From the tenant's release
# 66, MAX_COMPUTE_HOURS bounds it by its cost instead (--max-compute-hours, setup included; snouty refuses it with
# --duration, so the duration is then not passed) and SETUP_TIMEOUT fails it at once if setup takes longer
# (--setup-timeout, such as 15m; the server's default is 5m).
set -euo pipefail

usage() { echo "usage: [CONFIG=...] [LAUNCHER=amaru-cardano|basic_test] launch.sh on|off DURATION" >&2; exit 2; }
[ $# -eq 2 ] || usage
faults=$1
duration=$2
case $faults in
  on | off) ;;
  *) echo "launch: FAULTS is on or off, not $faults" >&2; usage ;;
esac
# snouty's form, checked before the local validation: whole numbers with h, m or s, in that order, such as 1h30m.
[[ $duration =~ ^([0-9]+h)?([0-9]+m)?([0-9]+s)?$ && -n $duration ]] ||
  { echo "launch: DURATION is like 15m, 1h or 1h30m, not $duration" >&2; usage; }
h=${BASH_REMATCH[1]%h} m=${BASH_REMATCH[2]%m} s=${BASH_REMATCH[3]%s}
[ $((10#${h:-0} * 3600 + 10#${m:-0} * 60 + 10#${s:-0})) -ge 60 ] ||
  { echo "launch: DURATION is at least a minute, not $duration" >&2; usage; }
launcher=${LAUNCHER:-amaru-cardano}
case $launcher in
  amaru-cardano | basic_test) ;;
  *) echo "launch: LAUNCHER is amaru-cardano or basic_test, not $launcher" >&2; usage ;;
esac
if [ "$launcher" = basic_test ]; then
  level=none
  [ "$faults" = off ] || level=high_availability
  fault_args=(--fault-tolerance "$level" --fault-exclusions '^driver$')
else
  enabled=false
  [ "$faults" = off ] || enabled=true
  fault_args=(--param custom.faults_enabled="$enabled" --param custom.instrumented=true)
  for class in network_fault container_faults_kill container_faults_pause container_faults_stop; do
    fault_args+=(--param "custom.${class}_exclusion=driver")
  done
fi
budget_args=(--duration "$duration")
if [ -n "${MAX_COMPUTE_HOURS:-}" ]; then
  [[ $MAX_COMPUTE_HOURS =~ ^[0-9]{1,4}$ ]] && [ $((10#$MAX_COMPUTE_HOURS)) -ge 1 ] && [ $((10#$MAX_COMPUTE_HOURS)) -le 1000 ] ||
    { echo "launch: MAX_COMPUTE_HOURS is a whole number from 1 to 1000 (snouty's bound; the tier sets a minimum), not $MAX_COMPUTE_HOURS" >&2; usage; }
  budget_args=(--max-compute-hours "$MAX_COMPUTE_HOURS")
fi
if [ -n "${SETUP_TIMEOUT:-}" ]; then
  [[ $SETUP_TIMEOUT =~ ^([0-9]{1,2})m$ ]] && [ $((10#${BASH_REMATCH[1]})) -ge 1 ] && [ $((10#${BASH_REMATCH[1]})) -le 30 ] ||
    { echo "launch: SETUP_TIMEOUT is whole minutes from 1m to 30m, such as 15m, not $SETUP_TIMEOUT" >&2; usage; }
  budget_args+=(--setup-timeout "$SETUP_TIMEOUT")
fi
here=$(cd "$(dirname "$0")/.." && pwd)
commit=$(docker image inspect scarab-at-scarab:trial --format '{{index .Config.Labels "org.opencontainers.image.revision"}}')
[ -n "$commit" ] || { echo "launch: scarab-at-scarab:trial has no commit label; build it with build.sh" >&2; exit 1; }
# Validation waits for setup complete: up to 600 s for the pools to agree, then up to 1200 s for the healthy-setup gate,
# and for the canary up to 600 s of cost-model enactment between the two.
validate_timeout=35m
case ${CONFIG:-config} in
  config-canary) name=scarab-canary validate_timeout=45m
    description="writeBits canary (GHSA-vfw2-hhrc-87v5), k = 5, on Scarab $commit with two cardano-node pools" ;;
  config-reference) name=scarab-pool-reference
    description="control: cardano-node 11.1.3 in s1's seat with two cardano-node 11.1.3 pools, create-env defaults" ;;
  config-reference-late-start) name=scarab-pool-reference-late-start
    description="control: cardano-node 11.1.3 in s1's seat, its container started once the two pools listen" ;;
  config-late-start) name=scarab-pool-late-start
    description="Scarab $commit pool with two cardano-node 11.1.3 pools, s1's container started once they listen" ;;
  config-k200) name=scarab-pool-k200
    description="Scarab $commit pool with two cardano-node 11.1.3 pools, k = 200, 40000-slot epochs" ;;
  config-short-kes) name=scarab-pool-short-kes
    description="Scarab $commit pool with two cardano-node 11.1.3 pools, KES periods of 250 slots" ;;
  config-reload) name=scarab-pool-reload
    description="Scarab $commit pool with two cardano-node 11.1.3 pools, KES periods of 250 slots, reloads by SIGHUP" ;;
  config-thin-catalog) name=scarab-pool-thin-catalog
    description="Scarab $commit pool with two cardano-node 11.1.3 pools, only Scarab's own classes instrumented" ;;
  config-split-catalog) name=scarab-pool-split-catalog
    description="Scarab $commit pool with two cardano-node 11.1.3 pools, every class instrumented from the thin arm's two JARs" ;;
  *) name=scarab-pool
    description="Scarab $commit pool with two cardano-node 11.1.3 pools, create-env defaults" ;;
esac

"$here/scripts/validate.sh" "$validate_timeout"
SNOUTY_CONTAINER_ENGINE=docker exec snouty launch \
  --launcher "$launcher" \
  --config "$here/${CONFIG:-config}" \
  --test-name "$name" \
  --description "$description" \
  "${fault_args[@]}" \
  "${budget_args[@]}" \
  --param attrs.scarab="$commit" \
  --param attrs.reference=cardano-node-11.1.3 \
  --param attrs.plugin_version=0.1.0
