### Changed (storage, #839)

- A graceful stop (SIGTERM, SIGINT, `--seconds`, `--stall-exit-after` or a fatal error) no longer writes a
  ledger snapshot, as the reference node's `closeDB` writes none. A restart now replays the stored blocks
  from the newest periodic snapshot to the immutable tip, as it already did after `kill -9`, and takes as
  long as that replay. A snapshot being written when the node stops is abandoned, not awaited, and nothing
  of it is restored.

### Removed (logging, #839)

- The `shutdownSnapshot` event (`Storage.ShutdownSnapshot`). Once the stores are closed, the node now logs
  `closedDB` (`Storage.ClosedDB`, Info) with the `immutableTip` and the `tip` of the chain, as the reference
  traces `ClosedDB`. A log configuration, dashboard or watcher that names `shutdownSnapshot` should name
  `closedDB` instead.
