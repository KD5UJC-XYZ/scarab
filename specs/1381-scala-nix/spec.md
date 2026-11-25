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
