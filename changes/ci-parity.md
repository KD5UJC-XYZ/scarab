### Changed (ci)

- A push to `main` runs nothing its pull request did not, and runs to the end. CI, the CDDL cross-check and the security checks give each run on `main` a concurrency group of its own, so a newer push no longer cancels it or replaces it while it waits; only a pull request's newer push cancels its older run. Release PR preparation does the same for its pull request and push runs, and keeps its manual coordinator runs serialized in one group.
- The CDDL cross-check against cuddle ("cuddle 1.8.1.0 against the generated codecs", gated by "CDDL comparison gate") runs on every pull request and every push to `main`, as well as nightly, instead of only when CDDL, generator or build inputs change.
- The sbt dependency graph is submitted daily and on demand by its own workflow, `dependency-graph.yml`, instead of on every push to `main`; it publishes for Dependabot alerts and checks nothing.
- `scripts/check_ci_parity.py` (guard `ci-parity` in `scripts/guards.json`) refuses a workflow in which a job a push to `main` can start would not run on pull requests to `main` under a condition no narrower, or in which a push run on `main` can be cancelled or replaced.

### Changed (ci, #995)

- The release checks are paused on pull requests and pushes to `main` until releases start from `main`, at the owner's request: "frozen release identity controls" runs only when CI is started by hand, and the required summary "scalafmt, compile and test" requires only "Scala tests and native guards"; release PR preparation, "release proposal controls" included, runs only by hand, and the cutover's drafted required checks leave that check out. `docs/design/releases.md` says how to restore them.
