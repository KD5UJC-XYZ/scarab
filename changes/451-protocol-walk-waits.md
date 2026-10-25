### Fixed (devnet e2e protocol walk, #451)
- **The protocol walk no longer stops at version 10 when the comparisons are slow.** Its hop to 11 waits for the base
  governance round's DRep delegations and committee. That round ran in the comparison worker's epoch batch, and a
  comparison that waits for a slow Scala node holds the worker for epochs, so the hop could be proposed too late for the
  walk's bound. `e2e` now runs the round once per epoch in a thread of its own (`EraLoad.start_governance`). The bound
  and the requirement that the chain reach the reference's highest version are unchanged.
- **Every walk pass that proposes nothing logs a typed reason** (`WaitReason`: reward accounts, the hop of this epoch,
  delegations not made or not yet in the distribution, committee hot keys, the slot window, a pending enactment).
  `protocol.waiting` in `e2e-summary.json` and the `WalkStalled` failure name the current wait. `blockedThreads` holds the
  stacks of load threads still running when the run stopped them.
- **`e2e` ends its load threads before it summarises or removes the containers.** The comparison worker, the governance
  thread and the extended thread are all joined on every exit, a thread still running after 90 s fails the run, and
  once the load is asked to stop no stage starts and no transaction is submitted.
