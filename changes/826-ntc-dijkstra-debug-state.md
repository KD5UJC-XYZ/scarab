### Fixed (node-to-client under the Leios build, #826)
- **`DebugNewEpochState` and `DebugEpochState` answer a Dijkstra state under `--reference-build leios-b004fe0c`.**
  The answers were always written with the 11.1 layout's codecs, which write no Dijkstra state, so the node closed
  the session with "the answer is out of range". They are now written in the state layout of the build the node
  mirrors, the one `export-ledger-snapshot --reference-build` writes the state file in; the release builds' answers
  are unchanged.
- **A ledger state that cannot be written keeps its cause.** The refusal now names the constraint the state violates
  instead of reporting only that the answer is out of range.
