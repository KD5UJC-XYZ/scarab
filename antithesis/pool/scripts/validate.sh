#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Starts the config locally with snouty and waits for setup complete; snouty removes the containers and volumes after.
#   [CONFIG=config-canary] validate.sh [TIMEOUT]   (default 10m; CONFIG names the config directory, default config)
set -euo pipefail

here=$(cd "$(dirname "$0")/.." && pwd)
SNOUTY_CONTAINER_ENGINE=docker exec snouty validate "$here/${CONFIG:-config}" --timeout "${1:-10m}"
