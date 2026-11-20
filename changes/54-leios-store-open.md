### Added (Leios endorser-block store, #54)

- The node's start is wired to open the endorser-block store at `<db>/leios` under
  `--reference-build leios-b004fe0c` with a database directory and `LeiosDbConfig.Backend: SQLite`
  (the default), and to close it after the run, as cardano-node b004fe0c opens and closes its LeiosDB.
  `follow` still refuses that build at start (`ReferenceBuildNotYet.Rules`), so the open runs once
  `follow` admits it; until then no node opens the store. Release builds, the `InMemory` backend and a
  run without `--db` open none. Under the Leios build with `SQLite`, `LeiosDbConfig` is no longer
  reported as unused.
