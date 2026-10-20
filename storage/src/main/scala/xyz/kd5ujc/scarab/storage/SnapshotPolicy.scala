// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import scala.concurrent.duration.*

/** When the immutable tip is snapshotted. The decision is pure ([[SnapshotPolicy.due]]); the
  * storage supplies the facts.
  */
enum SnapshotPolicy:
  /** The reference node's policy (ouroboros-consensus 4.2.0.0, the version of cardano-node 11.1.3,
    * `Storage/LedgerDB/Snapshots.hs`: `defaultSnapshotPolicy` lines 689-754 with
    * `mithrilSnapshotPolicyArgs` lines 664-675): a snapshot is due when the immutable tip crosses a
    * multiple of `intervalSlots` (`40 * k` slots, `resolveSnapshotInterval` lines 639-642; the
    * offset is 0), unless the previous snapshot was requested less than `rateLimit` ago (10
    * minutes, `sfaRateLimit`, "useful while bulk-syncing"). A boundary that falls inside the rate
    * limit is skipped, not postponed. Far behind the tip, where a day of slots passes in minutes,
    * the rate limit is what spaces the snapshots; near the tip the interval is.
    */
  case Aligned(intervalSlots: BigInt, rateLimit: FiniteDuration)

  /** A snapshot once the immutable tip is `slots` past the previous snapshot (the
    * `--snapshot-every` override), whatever the time.
    */
  case EverySlots(slots: BigInt)

object SnapshotPolicy:
  /** The reference defaults for a chain with security parameter `k`. */
  def reference(k: BigInt): SnapshotPolicy =
    Aligned(ReferenceIntervalPerK * k, ReferenceRateLimit)

  /** `40 * k` slots (`resolveSnapshotInterval`, `DefaultSnapshotInterval`). */
  val ReferenceIntervalPerK: BigInt = 40

  /** Snapshots kept: `NumOfDiskSnapshots 2` (`mithrilSnapshotPolicyArgs`, line 675). */
  val ReferenceKeep: Int = 2

  /** `sfaRateLimit = secondsToDiffTime (10 * 60)` (line 672). */
  val ReferenceRateLimit: FiniteDuration = 10.minutes

  /** The facts a decision needs, when the immutable tip moves from `previousSlot` to `slot`.
    *
    * @param previousSlot
    *   The slot of the immutable tip before this block.
    * @param lastSnapshotSlot
    *   The slot of the newest snapshot (or of the state the node started from).
    * @param sinceLast
    *   Time since the previous snapshot was requested; none: none yet since this start (the
    *   reference's `sscTimeSinceLast = Nothing`).
    */
  final case class Facts(
      previousSlot: BigInt,
      slot: BigInt,
      lastSnapshotSlot: BigInt,
      sinceLast: Option[FiniteDuration]
  )

  extension (policy: SnapshotPolicy)
    /** Whether to snapshot now. */
    def due(facts: Facts): Boolean = policy match
      case Aligned(interval, rateLimit) =>
        // `candidate < n * interval <= next` for the previous and the new immutable slot
        val crossed = facts.slot / interval > facts.previousSlot / interval
        crossed && facts.sinceLast.forall(_ >= rateLimit)
      case EverySlots(slots) => facts.slot - facts.lastSnapshotSlot >= slots
