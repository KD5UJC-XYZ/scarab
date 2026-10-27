### Changed (devnet, #527)

- The `faults` scenario runs a mainnet-like genesis: `k` = 100 (`--security-param`), epochs of 20,000 slots (10k/f) and KES periods of 1,000 slots (`--kes-slots`), and refuses a genesis whose epoch is not longer than 4k/f or whose KES keys end before the run. At the devnet's `k` = 5 the reference node failed these schedules too. Partitions keep their bound (the time the network grows five blocks), so a seed draws the same schedule as before. A weekly 2,400 s run crosses an epoch boundary under faults; the nightly run stays at 600 s.
