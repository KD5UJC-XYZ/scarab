### Added (devnet: free-space margin and run-directory size, #610)

- `devnet.py --min-free-disk SIZE` (default 20 GiB) refuses to start a scenario when the filesystem holding its run directory has less free space, naming the margin, the free space and the flag that lowers it. It runs before the run directory is created, for `up`, `up-eras`, `faults` and `pool`/`kes-rotate`.
- The size of the run directory is printed at the end of a scenario, on stderr so the scenario's JSON summary stays the last line of stdout, with the command that removes it.
