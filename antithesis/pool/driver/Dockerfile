# SPDX-License-Identifier: Apache-2.0
# The trial's driver on Debian, so that it has the tools a debugging session and snouty's checks run (find, curl, jq,
# ps), with the release's statically linked cardano-cli 11.2.3.0 from the cardano image.
FROM scarab-at-cardano:trial AS cardano

# debian:trixie-slim, index digest checked on 2026-11-13.
FROM debian@sha256:a72b4433de1cf7d6bae0c4cee74d0547f58601a643b3375f070c51600d63b6b2
RUN apt-get update \
 && apt-get install -y --no-install-recommends ca-certificates curl jq procps python3 \
 && rm -rf /var/lib/apt/lists/*
COPY --from=cardano /opt/reference/bin/cardano-cli /usr/local/bin/cardano-cli
# The test template's directories are made here, 0755: `COPY --chmod` would give them the file's mode, and snouty's
# `docker cp` of a read-only tree fails, so it finds no test commands.
RUN mkdir -p /ipc/h2 /ipc/h3 /ipc/s1 /opt/at /opt/antithesis/test/v1/main
COPY --chmod=0644 lib.sh forktree.py /opt/at/
COPY --chmod=0755 driver.sh /opt/at/driver.sh
COPY --chmod=0755 parallel_driver_watch_tips parallel_driver_submit_payments anytime_check_divergence \
  /opt/antithesis/test/v1/main/
CMD ["/opt/at/driver.sh"]
