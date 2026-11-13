#!/bin/bash
# SPDX-License-Identifier: Apache-2.0
# The Haskell pools' healthcheck: exits 0 once something in this container listens on TCP port $1, read from
# /proc/net/tcp so that no probe connection reaches the node (`wait_listening` in `scripts/devnet/cluster.py`).
# s1 starts only after both pools pass it: a node dialled before it listens refuses the connection, and the dialler's
# backoff can outlast its first leader slot, after which it forges a chain of its own from genesis (issue 609).
port=$(printf '%04X' "$1")
while read -r _ local _ state _; do
  if [[ $local == *:$port && $state == 0A ]]; then
    exit 0
  fi
done < <(cat /proc/net/tcp /proc/net/tcp6 2>/dev/null)
exit 1
