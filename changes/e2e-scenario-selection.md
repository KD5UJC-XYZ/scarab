### Added (E2E devnet workflow: chosen scenarios on demand)

- A run of the E2E devnet workflow started by hand takes a `scenarios` input, names separated by spaces or
  commas, and runs only those scenarios; empty, and on the nightly schedule and tags, it runs all of them. A name
  that is not a scenario fails the run's first job, before any devnet starts. The run's title names the
  selection.
- `scripts/dispatch_e2e.py --scenario <name>`, once per scenario, dispatches such a run, and refuses an unknown
  name before it waits. The scenarios and their arguments are listed in `scripts/ci_e2e_matrix.py`.
