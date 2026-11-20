### Added (Leios endorser-block store, #54)

- A node of `--reference-build leios-b004fe0c` with a database directory opens the endorser-block
  store at `<db>/leios` at start and closes it after the run, as cardano-node b004fe0c opens its
  LeiosDB, when `LeiosDbConfig.Backend` is `SQLite` (the default); `LeiosDbConfig` is then no longer
  reported as unused. Release builds, the `InMemory` backend and a run without `--db` open none.
  Nothing writes or reads the store at runtime yet.
