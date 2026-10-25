### Changed (logging, #112)

- The libraries' SLF4J lines (`Library.Slf4j`) default to `Info`: without a `--log-severity` that covers them, a library's `Debug` lines (dnsjava's lookups at start-up) are no longer formatted or written. `--log-severity Library=Debug`, `Library.Slf4j=Debug` or `*=Debug` lets them through again, on every sink or, with a `<sink>:` prefix, on one. The node's own lines keep their `Debug` default.
