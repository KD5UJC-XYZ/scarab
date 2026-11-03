### Added (devnet, #610)

- A devnet command that starts a run refuses before it starts when the file system of the run directory has
  less free space than `--min-free-gib` (default 5 GiB; 0 skips the check), and prints the size of the run
  directory when the run ends, including when it fails.
