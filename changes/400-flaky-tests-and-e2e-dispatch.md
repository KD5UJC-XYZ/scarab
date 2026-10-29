### Added (CI hygiene, #400)
- A nightly workflow (`flaky-tests.yml`, `scripts/flaky_tests.py`) finds tests that failed and then passed on the same
  commit in the last day's CI runs and tracks each in an issue of its own (`Flaky: <Suite> "<test>"`, with the area of
  the suite's module), or comments the run on the suite's existing flaky-test issue.
- `python3 scripts/dispatch_e2e.py --ref <branch>` dispatches the E2E devnet workflow only once no E2E run is waiting,
  so it never replaces, and cancels, a run queued behind the running one.
