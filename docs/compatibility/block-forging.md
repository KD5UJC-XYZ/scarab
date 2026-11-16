<!-- SPDX-License-Identifier: Apache-2.0 -->
# Block forging

Part of the [compatibility matrix](../compatibility.md); the register entry is `block-forging` in [compatibility.json](../data/compatibility.json). The three sections below are the cells of the former matrix row, unchanged.

## Status as recorded

implemented: Praos (Conway) only; forging from a per-epoch leader schedule (#1101, an approved Deviation,
[design](../design/leader-schedule.md)): the schedule of the current epoch, and of the next once its nonce is
known, is computed from the committed chain and the credentials in force, ahead of the slots and spread over the
time left; a clock fiber keeps the reference's blockchain time (`checkValidClockChange` with its trace and its
stop beyond 20 s, `startTimeInTheFuture`, `currentSlotUnknown`); a slot driver takes the slots as
`knownSlotWatcher` does and publishes every slot's outcome, a slot not led with only its ending line (the
reference's line of a check before the leader check, else `nodeNotLeader`) and counted as before; a forging fiber
forges a led slot 50 ms before its onset, the leader check made again against the chain then, and hands the block
to chain selection at the onset only if its block context, inputs and credentials still hold, else forges it
again; the KES key is updated at each period's first slot under a reservation that a forge holds from its update
to its signature; the schedule is published in four gauges and the `leaderSchedule` line; the forged body is encoded as the reference encodes it, each of its four parts definite up to 23 entries and indefinite above (#987); the two checks of the block just built (body size, decoding) are forge defects: the slot ends without a block, the block's transactions leave the mempool and the node goes on, logged as `forgeDefect` and counted in `scarab_forge_defects_total` (owner decision of 2026-11-14, #987); G7-F3 retains typed terminal storage/unavailable/internal/defect outcomes, prevents signing after key-update failure and preserves the KES failure event/metric; the leader check computes the VRF output of every slot and builds the proof only for a won slot, where the reference evaluates the certified VRF (output and proof) for every slot (`checkIsLeader`, consensus 808e4005 `Protocol/Praos.hs` 408-434): the decision of every slot and a won slot's VRF output and proof are the same, and only the work changes: a lost slot builds no proof, and a won slot computes its output once more while it is proved (#1024); ECVRF draft-03 proving, Sum6 KES generate/sign/evolve, opcert loading; typed operational-certificate input/verifier causes retained through OCERT, credential checks and reload refusals (#82); KES signing-key validation returns closed error cases with depth/period/length payloads, preserved through `ForgeError.KesKey` and `KesEvolution` (#82); dynamic block forging as cardano-node 11.1.3 (`Run.hs` 620-668, #906): a SIGHUP that finds the VRF key, the certificate or the KES key file absent disables forging (no slot started, no block, no KES write-back, `blockForgingUpdate` `DisabledBlockForging`, `forging_enabled_int` 0), a later SIGHUP that finds good files enables it, and `--start-as-non-producing-node`, `--non-producing-node` and `StartAsNonProducingNode` start with forging disabled; a reload forges with the VRF key it read, and from the opening of the stores a SIGHUP is the reference's initial one (topology read again, `blockForgingUpdate` `NotEffective`) and then the full one on every node (#919)

## Evidence

The schedule (#1101, stage S2): `LeaderScheduleSuite`'s property that over generated epochs, nonces, stakes,
totals, coefficients and VRF keys every slot's decision and every led slot's certificate are `Leadership.check`'s;
`ScheduledForgerSuite`, the differential test of the wired forger, which runs each scenario through the scheduled
forger on the test clock and through the per-slot check at each slot's onset with the same changes and finds every
slot's outcome and every block's hash equal (an epoch boundary, a fork past the next epoch's freeze while its
schedule is computed, a fork that changes the mark distribution, a led epoch's first slot whose tip holds a block of
that slot, slots the schedule does not cover, a SIGHUP of the VRF key, forging off and on, a start mid-epoch, and
the early attempt with each change at each point where it can fall); `ForgeInterleavingSuite` (a KES boundary
during assembly, two fibers on one slot in each order, the KES key's expiry in each order, an epoch's last slot
across the boundary, forging in slot order across a KES boundary, a clock that never waits); the devnet `pool` run
and one Antithesis run of the schedule are stage S3's. Before the schedule: `ForgeSuite` rebuilds byte for byte the body of a Conway block with 24 transactions that the cardano-node 11.1.3 devnet cluster forged (`fixtures/devnet/burst-24-txs-block.hex`), and checks each part at 0, 1, 23, 24 and 300 entries; `ForgerFailureSuite` forge defect (no block, no fatal, no ordinary refusal); devnet `pool` on 2026-11-14 with these changes (Scala node as pool 1: 89 forged, 89 adopted, 86 on the final chain, Haskell blocks-made 86 against 82.8 expected, 156 transactions, 16/16 comparisons, no refusal or defect); IETF draft-03 vectors 16 and 17 reproduced exactly; `LeadershipSuite` (#1024: over 1,000 slots of a fixed key, nonce and stake, and over generated keys, nonces, slots and stakes, the decision and the certificate equal those of the full proof computed in the test; a lost slot builds no proof and a won slot exactly one; an output or proof failure stays `VrfProof`) and `Draft03ProverSuite` (the output alone equals the output the proof certifies, for generated keys and inputs and for vectors 16 and 17); KES verification key reproduced from `cardano-cli` key seed; `Sum6KesSigningSuite`, `KeyFilesSuite`, `KesRotationSuite` (exact error cases and payloads, caller bytes retained, operator refusal leaves the key unchanged, terminal failure prevents later signing); `ForgerFailureSuite` (real rule/store failures, deferred defects, missing-file classification, cancellation and queued signers); `KesRotationSuite` dynamic forging (each of the three files absent disables forging with no slot, block or write-back; files back enable it after the reload checks; a refusal while disabled keeps it disabled; start as non-producing; the start-up trace; the key lock while disabled; the reference's reading order and `doesFileExist` rule), `MainArgsSuite` and `ConfigSuite` (the flags, the key and their precedence); `ForgeSuite`; `SystemStartSuite` and the geometry properties (slot clock), `ForgeSuite` counters; devnet `kes-rotate` (key rotated four times while forging, H2a); devnet `dynamic-forging` (#906: files moved away and SIGHUP, no block and no slot started in the 13 leader slots scheduled while disabled, no key written back, forging again after the files were put back; and #919: a SIGHUP during start-up answered `NotEffective` with the node running on; and #918: `kes-rotate` and `dynamic-forging` with the write-back by atomic exchange; [evidence log](evidence-log-5.md)); devnet `pool` (Scala node as pool 1 of three: 86 forged, 85 adopted, Haskell blocks-made 85 against 83.2 expected, 151 transactions, 18 KES periods, 16/16 comparisons)

## Explicitly unchecked or unconfirmed

The schedule (#1101): not yet run on the devnet or under Antithesis (stage S3); the time from a slot's onset to its
block's adoption is measured (`adopted`'s `latencyMs`) but no run has recorded it yet (the design's Q5). A forge
made ahead of its slot runs the mempool's own housekeeping as the per-slot loop did (a held transaction with no
verdict leaves the mempool, `select`), even if the attempt is then discarded; the KES key's write-back and its
`kesEvolved` line come from the forge that moves the key, up to 50 ms before the period, not from the slot
driver. A slot not led whose ledger cannot be carried into its epoch (a failed boundary tick) logs `nodeNotLeader`
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
