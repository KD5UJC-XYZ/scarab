### Changed (ledger, #998)

- A native script read from its bytes, a witness script among them, keeps those bytes, a window of them, and Conway's script writer writes a script back from those bytes as the reference does (`[0, native]`, a time lock being `MemoBytes`), a non-canonical encoding included. The writer takes the ledger's `Script` directly; `ConwayCodecs.ScriptPayload` is removed.
- An output's reference script keeps no bytes, as before, however the output was made (read from a block, from a snapshot's MemPack tables, or upgraded to Dijkstra), so stored outputs and every persisted format are unchanged.
