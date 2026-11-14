### Added (build, #984)

- `scripts/check_pinned_citations.py`, run by preflight and CI, refuses a new Scala or sbt comment that cites a Haskell file and line (`Foo.hs:12-34`) without a pin in the same comment: a revision, `package-x.y.z` or `name x.y.z`. `scripts/check_parameter_comments.py` refuses a new comment inside the parentheses of a definition, a class or a call. Both count per source against a baseline that only shrinks.

### Changed (build, #984)

- The Python guards are registered once, in `scripts/guards.json`: `scripts/preflight` runs each as a step of its own and CI's scripts job runs them all through `scripts/guards.py`. CI now also runs the performance register and documented-flags checks, which only preflight ran.
