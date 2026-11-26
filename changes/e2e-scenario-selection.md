### Added (E2E devnet workflow: chosen scenarios on demand)

- A run of the E2E devnet workflow started by hand takes a `scenarios` input, names separated by spaces or
  commas, and runs only those scenarios; empty, and on the nightly schedule and tags, it runs all of them. A name
  that is not a scenario fails the run's first job, before any devnet starts. The run's title names the
  selection.
- A `repeat` input (1 by default, at most 5) runs each selected scenario that many times, each copy on a runner
  and devnet of its own, named `<scenario>-1`, `<scenario>-2`, ...; with one run the jobs and artifacts keep their
  names.
- `scripts/dispatch_e2e.py --scenario <name>`, once per scenario, and `--repeat <n>` dispatch such a run, and
  refuse an unknown name or a repeat out of range before they wait. The scenarios and their arguments are listed in
  `scripts/ci_e2e_matrix.py`.
