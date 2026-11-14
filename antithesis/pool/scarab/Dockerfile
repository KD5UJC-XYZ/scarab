# SPDX-License-Identifier: Apache-2.0
# Scarab for the trial: a JAR assembled from a commit (`node/assembly` in scarab-jdk:local), which build.sh puts in
# the build context as scarab.jar, on the runtime of docker/candidate.Dockerfile (its classes are Java 25).
# It runs as root, as the Haskell pools do, so the configurator need not hand pool 1's keys to another user: on
# Antithesis a `chmod` of copied files failed with "Operation not permitted".

# Linux/amd64 Temurin 25.0.4.1+1 JRE on Ubuntu 26.04, the digest docker/candidate.Dockerfile pins.
FROM eclipse-temurin@sha256:acd83cdf6c9b5bfcd7c4e9d3e1969bacaacdcfa034c074500ab28610fb840f81
ARG SCARAB_COMMIT
LABEL org.opencontainers.image.revision=$SCARAB_COMMIT
ENV MALLOC_ARENA_MAX=2
# Antithesis instruments the JARs it finds under /opt/antithesis/catalog/ (following one symlink) and adds its own
# JARs beside the application's, so the node starts from the classpath /opt/scarab/*, not with `java -jar`.
RUN mkdir -p /data /ipc /opt/at /opt/scarab /opt/antithesis/catalog \
 && ln -s /opt/scarab/scarab.jar /opt/antithesis/catalog/scarab.jar
COPY scarab.jar /opt/scarab/scarab.jar
COPY --chmod=0755 scarab-node.sh /opt/at/scarab-node.sh
WORKDIR /opt/scarab
ENTRYPOINT ["/opt/at/scarab-node.sh"]
