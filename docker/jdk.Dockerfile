# SPDX-License-Identifier: Apache-2.0
# Linux/amd64 Temurin 21.0.11+10 JDK on Ubuntu 26.04 (resolute): tag eclipse-temurin:21.0.11_10-jdk-resolute, index
# digest checked on 2026-10-24. The same Java release as the CI setup-java pins and the candidate JRE.
FROM eclipse-temurin@sha256:392c405c4ae3b6a713211f0ae068b9ea0ffeedd8e9bac3a06d159e0076252918
RUN apt-get update && apt-get install -y --no-install-recommends python3 && rm -rf /var/lib/apt/lists/*
# glibc keeps a malloc arena per thread; with the many threads of the node the arenas fragment and
# the resident set grew by about 1 GB in 30 minutes of a Preview sync (roadmap K6). Two arenas
# stop the growth (see docs/performance.md).
ENV MALLOC_ARENA_MAX=2
WORKDIR /work
