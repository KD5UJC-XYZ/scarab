### Changed (tooling, #560)

- `scripts/preflight` checks for a reachable Docker before any step. Without one it prints the single line
  `INCOMPLETE: Docker unavailable, gates not run` and exits 3, instead of a `FAILED` row for each Docker step.
  With Docker its behaviour is unchanged.

### Added (tooling, #560)

- `scripts/preflight --doctor` prints the pinned JDK against `java -version` in the JDK image, whether the
  prepared dependency repository exists, whether Docker is reachable and where the cache lives. It writes
  nothing and exits 0.
