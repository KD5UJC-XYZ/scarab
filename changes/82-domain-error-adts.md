### Changed (domain and service errors, #82)

- Codec, crypto, Plutus, protocol/network, ledger, consensus, storage/legacy and node operations
  report domain/service-specific error cases with structured inputs and nested causes. Generic
  encoders preserve their error families; payload adapters retain typed codec causes. Configuration, snapshot and peer
  failures retain their source causes instead of erasing them into messages; evaluator defects
  remain distinct from script rejection. Original bytes, arithmetic, acceptance rules and persisted
  formats are preserved.
- CI checks declared String-valued Scala `Either` channels, including ordinary aliases and alternate
  spelling. The source guard is not a semantic proof; build-time generator error cleanup,
  compiler/AST enforcement and the Scalus foreign callback limitation remain tracked under M11-E.
