### Changed (ci)

- A push to `main` runs nothing its pull request did not, and runs to the end. CI, the CDDL cross-check, the security checks and release PR preparation give each run on `main` a concurrency group of its own, so a newer push no longer cancels it or replaces it while it waits; only a pull request's newer push cancels its older run. Coordinator runs of release PR preparation stay serialized in one group.
- The CDDL cross-check against cuddle ("cuddle 1.8.1.0 against the generated codecs", gated by "CDDL comparison gate") runs on every pull request and every push to `main`, as well as nightly, instead of only when CDDL, generator or build inputs change.
- The sbt dependency graph is submitted daily and on demand by its own workflow, `dependency-graph.yml`, instead of on every push to `main`; it publishes for Dependabot alerts and checks nothing.
- `scripts/check_ci_parity.py`, run by CI and `scripts/preflight`, refuses a workflow in which a job a push to `main` can start would not run on pull requests to `main` under a condition no narrower, or in which a push run on `main` can be cancelled or replaced.
