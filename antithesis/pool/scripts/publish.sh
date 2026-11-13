#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Pushes the three images, and a config image whose compose file names them by digest, to a public registry, so a
# run pulls everything from there and nothing goes to the tenant registry. Prints the config image's digest
# reference, for `launch.sh FAULTS DURATION CONFIG_IMAGE`.
#   publish.sh [REPOSITORY]   (default ghcr.io/kd5ujc-xyz/scarab-antithesis)
# The registry login is the caller's; Antithesis can pull only from a public repository.
set -euo pipefail

here=$(cd "$(dirname "$0")/.." && pwd)
repo=${1:-ghcr.io/kd5ujc-xyz/scarab-antithesis}
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

digest_of() {
  docker image inspect "$1" --format '{{range .RepoDigests}}{{println .}}{{end}}' | grep -m1 "^$repo@"
}

cp "$here/config/docker-compose.yaml" "$work/docker-compose.yaml"
for image in cardano scarab driver; do
  docker tag "scarab-at-$image:trial" "$repo:pool-$image"
  docker push -q "$repo:pool-$image" > /dev/null
  pinned=$(digest_of "$repo:pool-$image")
  sed -i "s|image: scarab-at-$image:trial|image: $pinned|" "$work/docker-compose.yaml"
done
if grep -q 'image: scarab-at-' "$work/docker-compose.yaml"; then
  echo "publish: an image of the compose file was not pinned" >&2
  exit 1
fi
printf 'FROM scratch\nCOPY docker-compose.yaml /docker-compose.yaml\n' > "$work/Dockerfile"
docker build -q --network=none --platform linux/amd64 -t "$repo:pool-config" "$work" > /dev/null
docker push -q "$repo:pool-config" > /dev/null
digest_of "$repo:pool-config"
