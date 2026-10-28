# SPDX-License-Identifier: Apache-2.0
# Linux/amd64 Temurin 25.0.4.1+1 JDK on Ubuntu 26.04 (resolute): tag eclipse-temurin:25.0.4.1_1-jdk-resolute, index
# digest checked on 2026-10-28. The same Java release as the CI setup-java pins and the candidate JRE.
FROM eclipse-temurin@sha256:3eb6c642ea5e6a5dbfa667971a07f30acd1ee0597bb7f6bbec21015251c5903d
RUN apt-get update && apt-get install -y --no-install-recommends python3 && rm -rf /var/lib/apt/lists/*
# glibc keeps a malloc arena per thread; with the many threads of the node the arenas fragment and
# the resident set grew by about 1 GB in 30 minutes of a Preview sync (roadmap K6). Two arenas
# stop the growth (see docs/performance.md).
ENV MALLOC_ARENA_MAX=2
WORKDIR /work
