These five rows are full generated `DELEG` judgments from the pinned cardano-ledger
Conway conformance executable. `context.cbor`, the actual two-field rule environment,
prestate, signal, implementation poststate and upstream verdict are kept byte-for-byte.
The Scala suite decodes the environment's full pool-state map and asserts it equals the
pool map in the full prestate before running Scarab's shared DELEG transition. This is a
bounded sample, not a complete run of the upstream property.
