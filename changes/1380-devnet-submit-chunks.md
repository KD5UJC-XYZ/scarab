### Fixed (devnet load submission, #1380)

- A devnet script longer than one command-line argument may be (128 KiB) runs from a file in the reference
  container. `devnet.py leios-traffic` with small load transactions (`--load-tx-bytes 600`, or a larger
  `--overflow`) built a submission script past that limit and stopped with `Argument list too long`; so could
  its fan-out setup and `devnet.py load --mode parallel` with a large burst.
- A load submission that reports no outcome for a file (its `docker exec` failed) counts that file as
  refused, with the exit status, instead of dropping it from the totals.
