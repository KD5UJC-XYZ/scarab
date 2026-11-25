<!-- SPDX-License-Identifier: Apache-2.0 -->
# Block forging

Part of the [compatibility matrix](../compatibility.md); the register entry is `block-forging` in [compatibility.json](../data/compatibility.json). The three sections below are the cells of the former matrix row, unchanged.

## Status as recorded

implemented: Praos (Conway) only in a release build, and in the Leios build also Praos2 on a Dijkstra ledger
(#1291); forging from a per-epoch leader schedule (#1101, an approved Deviation,
[design](../design/leader-schedule.md)): the schedule of the current epoch, and of the next once its nonce is
known, is computed from the committed chain and the credentials in force (in the Leios build on a Dijkstra ledger
too, #54), ahead of the slots and spread over the
time left; a clock fiber keeps the reference's blockchain time (`checkValidClockChange` with its trace and its
stop beyond 20 s, `startTimeInTheFuture`, `currentSlotUnknown`); a slot driver takes the slots as
`knownSlotWatcher` does and publishes every slot's outcome, a slot not led with only its ending line (the
reference's line of a check before the leader check, else `nodeNotLeader`, after `startLeadershipCheck`, with
`stateInfo` where the key's update succeeded, at a KES period change) and counted as before; a forging fiber forges a
led slot 50 ms before its onset, the leader check made again against the chain then, and hands the block to chain
selection at the onset (or at the slot driver's take of the slot, where the wall clock moved back after the take) only
if its
block context, inputs and credentials still hold, else forges it again; a led slot of a KES period the clock has not
reached at the wake, in practice a period's first slot, is forged at its onset, as the reference forges it, once the
slot driver has updated the key and hands it over; only an attempt admitted at its slot (begun, or made again, once
the clock fiber had published it or the slot driver had taken it) changes the KES state for it (an evolution, a poisoning, a period's update to
record), checked by `HotKeys` under the key's lock, a reload excepted, and an attempt not admitted that would change
it ends and is made again at the onset, or at the slot driver's take of the slot if that comes first; the KES key is
updated at each period's first slot under a reservation that a forge holds from its update
to its signature, in memory, and written back to its file only for a slot that passed the forecast check, never at a later period than that slot's, so a reading of the clock ahead (at a period's first slot past the forecast range, or at a reload) writes nothing and a restart reads the file as it was; at start-up a key in its file ahead of the current period is logged as `kesKeyAhead` with how to recover (#1101, the KES row of 2026-11-18 in the decisions register, [ADR 0015 amendment](../adr/0015-kes-key-written-back-and-checked-reload.md#amendment-2026-11-18-only-a-confirmed-period-is-written-back)); the schedule is published in four gauges and the `leaderSchedule` line; the forged body is encoded as the reference encodes it, each of its four parts definite up to 23 entries and indefinite above (#987); the two checks of the block just built (body size, decoding) are forge defects: the slot ends without a block, the block's transactions leave the mempool and the node goes on, logged as `forgeDefect` and counted in `scarab_forge_defects_total` (owner decision of 2026-11-14, #987); G7-F3 retains typed terminal storage/unavailable/internal/defect outcomes, prevents signing after key-update failure and preserves the KES failure event/metric; the leader check computes the VRF output of every slot and builds the proof only for a won slot, where the reference evaluates the certified VRF (output and proof) for every slot (`checkIsLeader`, consensus 808e4005 `Protocol/Praos.hs` 408-434): the decision of every slot and a won slot's VRF output and proof are the same, and only the work changes: a lost slot builds no proof, and a won slot computes its output once more while it is proved (#1024); ECVRF draft-03 proving, Sum6 KES generate/sign/evolve, opcert loading; typed operational-certificate input/verifier causes retained through OCERT, credential checks and reload refusals (#82); KES signing-key validation returns closed error cases with depth/period/length payloads, preserved through `ForgeError.KesKey` and `KesEvolution` (#82); dynamic block forging as cardano-node 11.1.3 (`Run.hs` 620-668, #906): a SIGHUP that finds the VRF key, the certificate or the KES key file absent disables forging (no slot started, no block, no KES write-back, `blockForgingUpdate` `DisabledBlockForging`, `forging_enabled_int` 0), a later SIGHUP that finds good files enables it, and `--start-as-non-producing-node`, `--non-producing-node` and `StartAsNonProducingNode` start with forging disabled; a reload forges with the VRF key it read, and from the opening of the stores a SIGHUP is the reference's initial one (topology read again, `blockForgingUpdate` `NotEffective`) and then the full one on every node (#919)

## Evidence

A Conway block's transactions are selected with the era rules (#85), as the reference selects them
(`getSnapshotFor` and `snapshotTake`, ouroboros-consensus 82ecba32 `NodeKernel.hs:697-707`). The pool's snapshot of the
slot applies the held transactions again on the ledger of the slot, as the selected build's consensus applies them,
with the clock the block rules judge the block with. The body is the held transactions' parts as they were received,
with each verdict's flag. `ConwaySnapshotSuite` covers each build's reapplication and both kinds of no verdict;
`ConwayHeldSuite` covers the body bytes; `ForgeSuite` covers the clock and the cut. The design's
[as-built entry](../design/era-typed-transactions.md#6-results) has the detail.

A Dijkstra ranking block and the endorser block it announces are made of the era's transactions (#85), as the
reference's are (`mkBody` and `forgeLeiosEb`), byte for byte what the flat path forged before: `DijkstraEraPathSuite`
compares the two paths over every recorded Dijkstra transaction the build accepts, at each boundary of the capacities.
The design's [as-built entry](../design/era-typed-transactions.md#6-results) has the detail.

A Dijkstra block's snapshot is the pool's own revalidated transactions, read as they are, when the pool is at the
block's parent and the slot changes nothing they were judged by (#1380), as the reference's `getSnapshotFor` reads
its cache (`cheapTick`, ouroboros-consensus d91b3557 `Mempool/Query.hs:91-127`): the slot after the parent, or a
later one in the pool's epoch that every held transaction's validity interval, and each of its sub-transactions',
contains. A certifying block's snapshot is always revalidated, on the parent with the certified closure applied
(`getSnapshotForNoCache`). `DijkstraMempoolSuite` checks that the pool's own transactions are the snapshot a
revalidation makes at those slots, and that another tip, an earlier slot, another epoch or a slot past a
transaction's interval is revalidated. The Conway forger still revalidates at every slot (#1380).

The schedule (#1101, stage S2): `LeaderScheduleSuite`'s property that over generated epochs, nonces, stakes,
totals, coefficients and VRF keys every slot's decision and every led slot's certificate are `Leadership.check`'s;
`ScheduledForgerSuite`, the differential test of the wired forger, which runs each scenario through the scheduled
forger on the test clock and through the per-slot check at each slot's onset with the same changes and finds every
slot's outcome and every block's hash equal in each scenario (an epoch boundary, a fork past the next epoch's freeze while its
schedule is computed, a fork that changes the mark distribution, a led epoch's first slot whose tip holds a block of
that slot, slots the schedule does not cover, a SIGHUP of the VRF key, forging off and on, a start mid-epoch, the
early attempt with each change at each point where it can fall, a led first slot of a KES period with a
certificate issued again for the same KES key installed in its wake margin and with the certificate's expiry, and a
pending early attempt made again after a reload of another KES key, the clock moved back into the period before, then
that key's certificate issued again, also with the attempt held before its signature, and a slot handed over that
keeps its admission when the published slot goes back, also when the slot was claimed early and is admitted by the
slot driver's take);
`LeaderScheduleStreamSuite` (a new value stops the computation under way and waits for its end before its own
begins; a replaced value never undoes its successor's schedule, and a value replaced and restored before the stream
read the replacement is computed, each over 64 seeds of `TestControl`); `KesWriteBackClockSuite` and `KesConfirmedWriteBackSuite` (#1101, the KES amendment of 2026-11-18: on the test clock with the era history's horizon and the stability window as the node has them, a reading 450 s ahead at a KES period's first slot and a SIGHUP while the clock reads 60 periods ahead write nothing back, and a restart at the true time forges every led slot, where the code before wrote the key 4 and 60 periods ahead; a SIGHUP at the true time writes nothing and the next slot writes the key in use; normal operation writes once per period; a key in memory ahead of a forecast slot's period, or poisoned, is never written; `kesKeyAhead` at start-up exactly when the key is ahead); `ForgeInterleavingSuite` (a KES boundary
during assembly, two fibers on one slot in each order, the KES key's expiry in each order, an epoch's last slot
across the boundary, forging in slot order across a KES boundary, a clock that never waits, a forge waiting for its
admission that the slot driver's take of its slot admits); the devnet `pool` and
`kes-rotate` runs at stage S2 (`82ff8b161`), and the devnet `pool` run and one Antithesis run of the trial's base
configuration at `04b6b8447` ([evidence log](evidence-log-5.md), the entries for #1101), both before the fixes of
the review of the final diff; and the devnet `pool` and `kes-rotate` runs at `604c93439`, with those fixes (102 of 102
and 59 of 59 led slots forged and adopted, 15/15 and 10/10 comparisons); and, merged, one Antithesis run with KES periods of 250 slots at `e3d4692d0` ([evidence log, part 6](evidence-log-6.md)), in which every property that must
never fire held across 60 KES periods and the key reached its end at slot 15,000, as the Haskell pools' did. Before the schedule: `ForgeSuite` rebuilds byte for byte the body of a Conway block with 24 transactions that the cardano-node 11.1.3 devnet cluster forged (`fixtures/devnet/burst-24-txs-block.hex`), and checks each part at 0, 1, 23, 24 and 300 entries; `ForgerFailureSuite` forge defect (no block, no fatal, no ordinary refusal); devnet `pool` on 2026-11-14 with these changes (Scala node as pool 1: 89 forged, 89 adopted, 86 on the final chain, Haskell blocks-made 86 against 82.8 expected, 156 transactions, 16/16 comparisons, no refusal or defect); IETF draft-03 vectors 16 and 17 reproduced exactly; `LeadershipSuite` (#1024: over 1,000 slots of a fixed key, nonce and stake, and over generated keys, nonces, slots and stakes, the decision and the certificate equal those of the full proof computed in the test; a lost slot builds no proof and a won slot exactly one; an output or proof failure stays `VrfProof`) and `Draft03ProverSuite` (the output alone equals the output the proof certifies, for generated keys and inputs and for vectors 16 and 17); KES verification key reproduced from `cardano-cli` key seed; `Sum6KesSigningSuite`, `KeyFilesSuite`, `KesRotationSuite` (exact error cases and payloads, caller bytes retained, operator refusal leaves the key unchanged, terminal failure prevents later signing); `ForgerFailureSuite` (real rule/store failures, deferred defects, missing-file classification, cancellation and queued signers); `KesRotationSuite` dynamic forging (each of the three files absent disables forging with no slot, block or write-back; files back enable it after the reload checks; a refusal while disabled keeps it disabled; start as non-producing; the start-up trace; the key lock while disabled; the reference's reading order and `doesFileExist` rule), `MainArgsSuite` and `ConfigSuite` (the flags, the key and their precedence); `ForgeSuite`; `SystemStartSuite` and the geometry properties (slot clock), `ForgeSuite` counters; devnet `kes-rotate` (key rotated four times while forging, H2a); devnet `dynamic-forging` (#906: files moved away and SIGHUP, no block and no slot started in the 13 leader slots scheduled while disabled, no key written back, forging again after the files were put back; and #919: a SIGHUP during start-up answered `NotEffective` with the node running on; and #918: `kes-rotate` and `dynamic-forging` with the write-back by atomic exchange; [evidence log](evidence-log-5.md)); devnet `pool` (Scala node as pool 1 of three: 86 forged, 85 adopted, Haskell blocks-made 85 against 83.2 expected, 151 transactions, 18 KES periods, 16/16 comparisons)

## Explicitly unchecked or unconfirmed

The schedule (#1101): the Antithesis run of the base configuration (above) was made before the fixes of the review
of the final diff; the devnet runs at `604c93439` and the Antithesis run with 250-slot KES periods at `e3d4692d0`
carry them. No Antithesis run sends SIGHUP, so a reload under faults is not exercised. A held
transaction the selection has no verdict on is reachable in the wired forger only through a test's ledger (the
state's parameters cannot hold a deposit beyond its range); the differential test reaches it so. The KES key's
`kesEvolved` line comes from whichever of the slot driver and a forge moves the key in the period,
only by an attempt admitted at its slot or by a reload, and its write-back from the first check of a slot that passed the forecast check after it (the slot driver's or a forge's); neither is held with the slot's lines. A reading of the clock ahead that stays inside the forecast range (at most the stability window past the tip, one KES period on mainnet parameters) passes that check, and the key of that slot's period is written back, as the reference would sign with it there; a restart after the clock comes back then waits for that period, and `kesKeyAhead` says so (a remaining limit until the KES agent, accepted 2026-11-18). A reload keeps the key in use only while it is not past the period it must sign in; otherwise it installs the file's key, as the reference's reload makes its hot key afresh from the file, so a SIGHUP once the clock is right recovers a key left past the present. A slot not led whose ledger cannot be
carried into its epoch (a failed boundary tick) logs `nodeNotLeader`
where the per-slot loop logged `forgeFailed`, as the reference, which ticks the ledger to a slot only for a slot it leads.

No test reaches a forge defect with transactions to evict: the selection is bounded by the same parameters as the size check, and only a bug of ours can fail either check; the eviction is the call a forged invalid block makes (#987). A reference block with more than 23 auxiliary data entries or invalid transactions was not captured; those two parts follow the reference's encoder by source reading and unit tests only.

Relative KES targets are checked before narrowing, with an exact typed cause and a terminal
outcome when a configured window outlasts the signing key. The source correspondence and
implementation are described in the [error design](../design/domain-errors.md); regression
coverage includes period 64, signed-Int overflow and 32-bit wraparound, same-key and replacement
reloads, configured-window expiry precedence, and period 63 as a usable control. These are
executed Scarab tests, not a native-node comparison on a pathological genesis. The separate
forging-window end-overflow discrepancy remains tracked in
[#474](https://github.com/KD5UJC-XYZ/scarab/issues/474#issuecomment-6759847567).

TPraos forging (H3); no Byron (PBFT) forging, a decision (H7); no KES agent (H2); the evolved KES key is written back to its file, which the Haskell node never does (H2a), and the KES key and certificate are reloaded on SIGHUP after a check the Haskell node does not make; the write-back never makes a key file that is not there; on Linux the evolved key replaces the file by `renameat2(RENAME_EXCHANGE)`, and elsewhere a move in the instant between the existence check and the rename can still put the key back ([#918](https://github.com/KD5UJC-XYZ/scarab/issues/918), ADR 0015 amendment of 2026-11-13); no bulk credentials file (#222); opcert issuing is the driver `scripts/pool/rotate_kes.py` (`cardano-cli` does the signing), exercised by the devnet `kes-rotate` only; on a public network, run on preprod only: from epoch 321 (2026-10-23) the producer forged in each of its first seven leader slots and six of those blocks are on the canonical chain, the seventh lost in a same-height battle (H1/H1a, [#115](https://github.com/KD5UJC-XYZ/scarab/issues/115), [evidence log](evidence-log-1.md)); mainnet not run; a slot the loop overran is not forged late, as in the reference, so at 1 CPU on a loaded host about one leader slot in a hundred runs was lost that way (evidence log; since #543 such a slot is passed over without a count, as the reference's `knownSlotWatcher` does); side-channel properties of the secret-scalar arithmetic not reviewed
