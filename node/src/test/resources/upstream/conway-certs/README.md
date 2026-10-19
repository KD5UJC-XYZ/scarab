# Pinned Conway CERTS conformance sample

This is one actual generated CERTS judgment from the exact property path and source pins in `manifest.json`. Both the upstream implementation and Agda accept the empty certificate vector, and all raw context/environment/state/signal/result files are preserved and hashed. Scarab decodes the complete upstream `CertsEnv`, including the serialized Conway `Tx` and committee proposal map, and its typed CERTS transition matches the full Haskell post-state.

The related Agda context is checked against the withdrawals and voting procedures decoded from the actual transaction. This is one seeded generated case and does not establish full CERTS property coverage.
