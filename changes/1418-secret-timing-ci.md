### Added (timing of the secret paths in CI, #1418)

- `SecretPathTiming` also times KES signing (a Sum6 key at period 0) and two evolutions (period 0 to 1 and 1 to 2)
  against a fixed seed and random ones, makes the two classes' pools taking turns at which entry is made first, adds a
  control of KES keys of one seed in both classes, takes its crops' cutoffs from a calibration batch it then discards, counts a
  crop only from 1,000 measurements a class, consumes every result whole after the clock, reports a slow pool entry
  as a diagnostic, and writes its results as JSON, with an identity of the run, when given a path.
- The workflow `secret-timing.yml` runs it nightly and on demand on a GitHub-hosted runner through
  `scripts/secret_timing.py`: three runs, each in a fresh container and JVM. The program recomputes each run's
  verdict from its statistics. A leak, one secret target above dudect's threshold of 10 in two runs (in a run whose
  noise controls failed, above five times their level too), fails the job; any other reading above 10, or fewer than
  two clean runs, leaves it green with a warning, neither a pass nor a failure. Every statistic and the runner's CPU
  are in its summary and artifact; it publishes nothing. What it covers and what it cannot see:
  `docs/running/secret-timing.md`.
