### Added (timing of the secret paths in CI, #1418)

- `SecretPathTiming` also times KES signing (a Sum6 key at period 0) and evolution (period 1 to 2, which generates
  a subtree of two leaves) against a fixed seed and random ones, marks a run inconclusive when one of its three
  controls reads what it must not, and writes its results as JSON when given a path.
- The workflow `secret-timing.yml` runs it nightly and on demand on a GitHub-hosted runner through
  `scripts/secret_timing.py`: three runs, each in a fresh container and JVM. The job fails when one secret
  target is above dudect's threshold of 10 in two conclusive runs, warns and stays green when fewer than two runs
  are conclusive, and records every statistic and the runner's CPU in its summary and artifact. It publishes
  nothing. What it covers and what it cannot see: `docs/running/secret-timing.md`.
