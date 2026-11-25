### Changed (conformance, #1411)

- Follows #1412, which ran `uplc-turbo` on GitHub-hosted runners for its memory counts alone, on the then untested assumption that a shared runner's timings are too noisy to publish. Measured, a runner's score varies no more than a quiet development machine's, so `uplc-turbo` is now an ordinary scenario of the **Conformance reports** workflow (input `uplc-turbo`, on by default, and in the nightly run): its CPU times and memory are measured on the runner at the runner's default budget, and its report is published from there with the others. A report written locally stays publishable.
- A report's provenance records the CPU a tool container sees (its model, the machine's logical CPUs and those the container may use) and the container's limits; `publish.py` refuses a `uplc-turbo` report whose provenance names no CPU, as every one written by `--allocation-only` does.
- The job summary gives the `uplc-turbo` score by the site's rule (`summarize.py` writes `turbo-score.md` and `turbo-score.json`): met and total, the programs missed on memory and on CPU alone, each with its CPU time and memory as multiples of its budget, and the median and 95th percentile of bytes per memory unit.

### Removed (conformance, #1411)

- `run.py --allocation-only`, the workflow's `uplc-turbo-memory` input and `scripts/conformance/turbo_memory.py`, added by #1412: the flag only lifted the refusal of `uplc-turbo` on CI, so a memory-only run cost as much as a full one, and the full run's summary includes its memory counts.
