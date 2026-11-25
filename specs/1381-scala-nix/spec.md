# Scala Nix environment

Provide a reproducible, Docker-free Scala development environment in Scarab and
a reusable Scala template in the owner's nix-templates collection. Follow the
Rust template's flake-parts organization loosely. Preserve existing production Scala source,
build definitions and persisted formats.

Acceptance: entering the shell provides a consistent JDK and sbt; the existing
Scarab build loads and its checks run with Nix; Nix formatting and flake checks
pass; initializing the exported template in an empty directory produces a Scala
application that compiles and runs. Record validation limits explicitly.

The owner explicitly authorized Java and sbt through Nix and prohibited Docker
in this chat on 2026-11-24, overriding the Docker build instructions for this task.

## User stories and acceptance scenarios

- P1: Develop Scarab using Nix. Given the checkout and Nix with flakes enabled,
  when the developer enters `nix develop`, then Java and sbt use the selected
  JDK and the existing build runs without Docker.
- P2: Start another Scala project. Given an empty project directory, when the
  developer initializes the Scala template from nix-templates, then the sample
  compiles, passes its test and prints its greeting through sbt.

## Requirements checklist

- [x] Preserve the existing application sources and sbt version declarations.
- [x] Pin Nix inputs and keep JDK configuration in one shared module.
- [x] Follow the Rust template's modular flake-parts organization.
- [x] Include usage, customization and existing-project adoption instructions.
- [x] Validate initialization from the registered template, not just its source.

An existing project's source and build files must never be replaced with the
sample. Unsupported systems are outside scope. A first build needs network
access for Maven dependencies. Docker-only repository gates must be reported
as incomplete rather than claimed as passed.

## Network app extension

P1: Run `nix run .#preview`, `.#preprod` or `.#mainnet` from a Scarab checkout.
Given any of these apps, starting it downloads and caches that network's official
configuration, compiles the existing node and runs `follow`. Different networks
use separate databases, Unix sockets and query ports, and can run concurrently.
Additional node arguments retain their exact argument boundaries.

The owner approved the defaults in this chat on 2026-11-25: configuration,
database and socket under `${SCARAB_DATA_DIR:-/tmp/scarab}/<network>`, with one
query port per network. User-supplied configuration directories remain supported.
No stored format, production Scala implementation or generic Scala template is changed.
