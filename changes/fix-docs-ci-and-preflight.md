### Changed (development checks)

- Documentation-only changes skip Scala, CDDL cross-check and fuzz work in CI while retaining required check names and non-sbt checks. Unknown or mixed changes still run full checks.

### Fixed (development tooling)

- Preflight workflow lint runs with the checkout owner and read-only Git metadata so private linked worktrees are accessible.
- Issue triage reads paginated GitHub responses without requiring the newer `gh --slurp` option.
