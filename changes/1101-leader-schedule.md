### Changed (forging from a leader schedule, #1101)

- The forger forges from a per-epoch leader schedule instead of a per-slot loop (an approved Deviation,
  `docs/design/leader-schedule.md`). The schedule of the current epoch, and of
  the next once its nonce is known, is computed ahead of the slots and spread over the time left; a led slot is
  checked again against the chain at the slot, its block is built 50 ms before the slot's onset and handed to
  chain selection at the onset, and built again if the chain, the slot's inputs or the credentials changed
  meanwhile. In the first slot of a KES period the block is built after the onset, as before. Every slot's
  leadership decision, every won slot's certificate and every block are the per-slot check's.
- A slot the pool does not lead logs only the line that ends it: the reference's line where a check before the
  leader check refuses it (`slotIsImmutable`, `blockFromFuture`, `noLedgerView`, `forgeStateUpdateError`,
  `forgeFailed`), else `nodeNotLeader`. `startLeadershipCheck` and `stateInfo` at Info appear only on led slots
  and at a KES period change, and the slot's Debug lines only on led slots; `stateInfo` at Warning and Alert near
  the key's expiry is logged every slot as before. The forge counters and the `ForgingStats` gauges count every
  slot as before.
- The lines of a led slot are held until its outcome is final and keep the time they were made: a block forged
  ahead of its slot shows a `forged` time before the slot's onset. `adopted`'s `latencyMs`,
  `scarab_forge_adoption_seconds` and `forgeLatencyMs` stay measured from the slot's onset.
- The KES key evolves at the first slot of each period, also while the node cannot forecast its slots, and only
  by an attempt admitted at its slot (begun or made again once the clock had published it), or by a reload.

### Added (forging from a leader schedule, #1101)

- The leader schedule is published: the gauges `scarab_forge_scheduled_leader_slots{epoch}`,
  `scarab_forge_expected_leader_slots{epoch}`, `scarab_forge_next_leader_slot` and
  `scarab_forge_schedule_complete{epoch}` (`epoch` is `current` or `next`), and the `leaderSchedule` log line
  (Forge, Info) when an epoch's schedule is complete or a recomputation changes it. Whoever reads the node's
  `/metrics` or log learns in advance when the pool leads.
- `forgeDiscarded` (Forge, Debug): an attempt at a slot that did not count, its lines dropped.
