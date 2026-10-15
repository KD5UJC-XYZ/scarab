# 0004. Build and run only in containers

- **Status**: Accepted
- **Date**: 2026-10-10

## Context

Several people and agent sessions build, test and run nodes on shared machines. Host toolchains drift,
and long-running nodes and devnets collide over ports, directories and processes.

## Decision

No host JDK or sbt. Builds and tests run in the JDK image (`docker/jdk.Dockerfile`); the sbt launcher
is pinned by checksum (`scripts/sbtw`); devnet scenarios run the Haskell reference image built from the
checksum-pinned official release. Each session uses its own containers, sbt server socket, devnet name
and data directory (`SCARAB_DATA_DIR`, default `/tmp/scarab`); run data never goes into the checkout.

## Consequences

- Reproducible builds and runs on any host with Docker and Python.
- A running JVM loads classes lazily, so long runs use frozen copies of the classes.
- See [AGENTS.md](../../AGENTS.md) for the per-session rules.
