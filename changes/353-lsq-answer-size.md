### Fixed (node-to-client LocalStateQuery, #353)
- **LocalStateQuery answers are no longer capped at 2.5 MB.** `cardano-cli query leadership-schedule --next` asks
  `GetCBOR DebugEpochState`. On preprod its answer is a single byte string of tens of megabytes. The server refused the
  answer with `string limit exceeded` and closed the connection, and the CLI reported `BearerClosed`.
  `query ledger-state` and large whole-UTxO answers were refused the same way. Server messages (`MsgResult`,
  `MsgAcquired`, `MsgFailure`) and the node-to-client mux's outgoing messages now have the reference's bound: no byte,
  string or item limit beyond what a byte array can hold (`maximumMiniProtocolLimits`, and the codec run without
  limits). A client's queries keep their current bounds.
- The `ntc` scenario compares `cardano-cli query leadership-schedule --current` and `--next` on both sockets.
