# SPDX-License-Identifier: Apache-2.0
# Scarab v0.2.0-alpha.1 (commit 4636ab355, a development-mode build) for the trial: the published image's JAR on the
# runtime of docker/candidate.Dockerfile. The published image runs it on a Java 21 JRE, but parts of the JAR are Java 25
# class files (version 69), so forging stops at its first leadership check with UnsupportedClassVersionError.
# The database and socket directories are owned by the node's user, so a named volume mounted there takes that owner.
FROM ghcr.io/kd5ujc-xyz/scarab@sha256:1811062cbb86d33b38cdf5132b0a24c3de1624f42625980bc0fca785390461e5 AS release

# Linux/amd64 Temurin 25.0.4.1+1 JRE on Ubuntu 26.04, the digest docker/candidate.Dockerfile pins.
FROM eclipse-temurin@sha256:acd83cdf6c9b5bfcd7c4e9d3e1969bacaacdcfa034c074500ab28610fb840f81
ENV MALLOC_ARENA_MAX=2
COPY --from=release /opt/scarab/ /opt/scarab/
RUN mkdir -p /data /ipc && chown 10001:10001 /data /ipc
COPY --chmod=0555 scarab-node.sh /opt/at/scarab-node.sh
WORKDIR /opt/scarab
USER 10001:10001
ENTRYPOINT ["/opt/at/scarab-node.sh"]
