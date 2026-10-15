FROM eclipse-temurin@sha256:b9142586f9712700c6c9e07adcedfb18608b1a3a056e4001423a3354adfa9d80
RUN apt-get update && apt-get install -y --no-install-recommends python3 && rm -rf /var/lib/apt/lists/*
# glibc keeps a malloc arena per thread; with the many threads of the node the arenas fragment and
# the resident set grew by about 1 GB in 30 minutes of a Preview sync (roadmap K6). Two arenas
# stop the growth (see docs/performance.md).
ENV MALLOC_ARENA_MAX=2
WORKDIR /work
