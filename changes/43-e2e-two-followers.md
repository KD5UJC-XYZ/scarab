### Added (devnet, #43)
- **`devnet.py e2e --plutus-vm scalus --plutus-vm scarab` runs one Scala follower per Plutus evaluator in one
  cluster**, and the nightly era walk does so. Each follower has its own container, log, sockets and ports and is
  judged as the single follower is, every comparison reads the reference once and compares all followers with that
  block, and at the end their final chains and their projected states at the shared blocks must agree
  (`crossCheck` in `e2e-summary.json`, each follower under `followers`). With one `--plutus-vm`, or none, the run is
  unchanged; `--oracle` takes one follower.
