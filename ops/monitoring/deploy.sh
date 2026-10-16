#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Deploys the monitoring server or a worker's agent over ssh (see README.md).
#
#   deploy.sh server <ssh destination> <remote dir> <bind address>
#   deploy.sh agent  <ssh destination> <remote dir> <host name> <server address>
#
# For an agent, UNIX_DISABLED_COLLECTORS (if set) is passed on: on Docker Desktop hosts use
# UNIX_DISABLED_COLLECTORS=arp,netdev,netstat,softnet.
# Extra ssh options (a key, a port) go in $SSH_OPTS, e.g. SSH_OPTS="-p 2222".
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
ssh_() { ssh $SSH_OPTS -o BatchMode=yes "$@"; }
SSH_OPTS=${SSH_OPTS:-}

copy() { # <part> <dest> <dir>: the part's files, keeping .env and data on the remote side
  tar -C "$here/$1" --exclude=.env --exclude=data --exclude=.docker -czf - . | ssh_ "$2" "mkdir -p '$3' && tar -C '$3' -xzf -"
}

case "${1:-}" in
  server)
    [ $# -eq 4 ] || { echo "usage: $0 server <dest> <dir> <bind address>" >&2; exit 2; }
    dest=$2 dir=$3 bind=$4
    copy server "$dest" "$dir"
    ssh_ "$dest" bash -s -- "$dir" "$bind" <<'REMOTE'
set -euo pipefail
dir=$1 bind=$2
cd "$dir"
if [ ! -f .env ]; then
  pass=$(head -c 24 /dev/urandom | base64 | tr -d '/+=' | head -c 24)
  printf 'BIND_ADDR=%s\nDATA_DIR=%s/data\nGRAFANA_ADMIN_USER=admin\nGRAFANA_ADMIN_PASSWORD=%s\nPROMETHEUS_RETENTION=180d\n' \
    "$bind" "$dir" "$pass" > .env
  chmod 600 .env
  echo "wrote $dir/.env (Grafana admin password inside)"
fi
mkdir -p data/prometheus data/loki data/grafana grafana/dashboards/imported
chown -R 65534:65534 data/prometheus; chown -R 10001:10001 data/loki; chown -R 472:0 data/grafana
# community dashboards, fetched rather than committed: Node Exporter Full (grafana.com 1860)
if [ ! -s grafana/dashboards/imported/node-exporter-full.json ]; then
  curl -fsSL https://grafana.com/api/dashboards/1860/revisions/latest/download \
    | sed 's/\${DS_PROMETHEUS}/prometheus/g' > grafana/dashboards/imported/node-exporter-full.json \
    || echo "could not fetch the Node Exporter Full dashboard; skipped"
fi
docker compose up -d --remove-orphans
docker compose ps --format '{{.Name}} {{.Status}}'
REMOTE
    ;;
  agent)
    [ $# -eq 5 ] || { echo "usage: $0 agent <dest> <dir> <host name> <server address>" >&2; exit 2; }
    dest=$2 dir=$3 name=$4 server=$5
    copy agent "$dest" "$dir"
    ssh_ "$dest" bash -s -- "$dir" "$name" "$server" "${UNIX_DISABLED_COLLECTORS:-}" <<'REMOTE'
set -euo pipefail
dir=$1 name=$2 server=$3 disabled=${4:-}
cd "$dir"
# public images only: an empty client config, so a desktop credential helper that is missing in
# an ssh session (Docker Desktop over ssh) is never asked
mkdir -p .docker && [ -f .docker/config.json ] || echo '{}' > .docker/config.json
export DOCKER_CONFIG=$PWD/.docker
printf 'SCARAB_HOST=%s\nPROMETHEUS_PUSH_URL=http://%s:9090/api/v1/write\nLOKI_PUSH_URL=http://%s:3100/loki/api/v1/push\nUNIX_DISABLED_COLLECTORS=%s\n' \
  "$name" "$server" "$server" "$disabled" > .env
docker compose up -d --remove-orphans
docker compose ps --format '{{.Name}} {{.Status}}'
REMOTE
    ;;
  *) sed -n '3,9p' "$0" >&2; exit 2 ;;
esac
