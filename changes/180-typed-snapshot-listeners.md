### Fixed (snapshot and listener failure boundaries, #180 G7-F2/G7-F4)

- Preserve typed storage errors and original defects through periodic and shutdown snapshots, using error capabilities owned by the worker/finalizer. Snapshot format refusals remain separate; failed snapshots are reported and a later snapshot can run.
- Report TCP and Unix handler failures through a required typed listener callback. Each handler owns its error scope, ordinary network endings remain local, and unexpected handler defects reach the node's fatal reporter. Cancellation closes resources without reporting an error.
- Defer scope allocation and worker-program construction, so synchronous snapshot, listener and shared worker defects retain their causes just like failures during execution.
