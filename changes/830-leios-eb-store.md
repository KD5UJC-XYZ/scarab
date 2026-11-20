### Added (Leios endorser-block store, #830)

- The Leios store at `<db>/leios`, a RocksDB database of its own: announced endorser-block points,
  bodies and their transactions, with the reference LeiosDB's writes (which return the endorser
  blocks they completed), lookups, point scans, collection and promotion. Its layout is a prototype
  format of the Leios testnet build: a store of another format or version is refused.
