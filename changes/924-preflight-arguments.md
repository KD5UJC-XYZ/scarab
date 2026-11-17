### Fixed (developer tooling, #924)

- `scripts/preflight --help` (or `-h`) prints what the script checks and exits 0, from any working directory,
  instead of running the whole preflight; any argument other than `--check` or `--doctor` alone is refused with
  exit status 2 before any step runs, so a mistyped `--check` no longer passes as the non-sbt checks.
