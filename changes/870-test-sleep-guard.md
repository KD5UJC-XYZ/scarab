### Added (build, #870)

- `scripts/check_test_sleeps.py`, run by preflight and CI, refuses new sleeps in tests: `sleep`, `delayBy`, `andWait`, the fs2 timers, `parkNanos`, `onSpinWait`, `cede`, `Thread.yield` and the two sleep-poll helpers, counted per Scala source outside `src/main` against a baseline that only shrinks. Tests synchronize on an observable condition, or advance `TestControl`'s clock for what is about time.
