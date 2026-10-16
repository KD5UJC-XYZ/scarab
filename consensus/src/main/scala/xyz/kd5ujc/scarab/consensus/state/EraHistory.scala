// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.consensus.state

import xyz.kd5ujc.scarab.ledger.state.Diff
import cats.{Eq, Show}
import cats.derived.*
import xyz.kd5ujc.scarab.consensus.{ForkTrigger, HardForkConfig}
import xyz.kd5ujc.scarab.ledger.EpochGeometry
import xyz.kd5ujc.scarab.protocol.Era

/** One era of the history (`EraSummary`, `HardFork/History/Summary.hs:198-205`): where it starts,
  * where it ends (`None`: unbounded, `EraUnbounded`, `Summary.hs:210-216`) and its parameters
  * (`EraParams`: epoch size, slot length in milliseconds, safe zone, genesis window).
  */
final case class EraSummary(
    start: Bound,
    end: Option[Bound],
    epochSize: BigInt,
    slotLengthMillis: BigInt,
    safeZone: BigInt,
    genesisWindow: BigInt
) derives Eq,
      Show,
      Diff

/** The hard-fork combinator's view of time: the interpreter that `cardano-cli query tip` and every
  * wallet use to turn slots into epochs and wall-clock times. [[Bound]] is the same type the
  * telescope's `Past` and `Current` carry.
  *
  * Every era up to the current one is listed (a Byron era first, empty when the chain starts in
  * Shelley). Earlier eras end where the next begins. The current era ends at the safe-zone bound:
  * the slot after the tip plus the safe zone, rounded up to an epoch boundary (the reference node
  * does the same while no later hard fork is known).
  *
  * The start epoch of an era is the configured `Test<Era>HardForkAtEpoch`, else the epoch in which
  * this node saw the transition, else the start of the previous era (a hard fork triggered by
  * protocol version before this node started cannot be placed more precisely; roadmap F1).
  */
object EraHistory:
  private val PsPerMilli = BigInt(1000000000)

  /** The Byron parameters the history needs: epoch size, slot length and the security parameter. */
  final case class ByronParams(epochSlots: BigInt, slotLengthMillis: BigInt, k: BigInt)

  def summary(
      geometry: EpochGeometry,
      byron: ByronParams,
      forks: HardForkConfig,
      observed: Map[Era, BigInt],
      current: Era,
      tipSlot: BigInt
  ): Vector[EraSummary] =
    val shelleyEpochs =
      Era.values.toVector.filter(e => e != Era.Byron && e.ordinal <= current.ordinal)
    val firstShelley = geometry.firstShelleyEpoch
    // start epoch per Shelley-based era
    val starts: Vector[BigInt] = shelleyEpochs
      .foldLeft(Vector.empty[BigInt]) { (acc, era) =>
        val previous = acc.lastOption.getOrElse(firstShelley)
        val epoch =
          if era == Era.Shelley then firstShelley
          else
            forks.triggers.get(era) match
              case Some(ForkTrigger.AtEpoch(e)) => e
              case _                            => observed.getOrElse(era, previous)
        acc :+ epoch.max(previous)
      }
    def bound(epoch: BigInt): Bound =
      val slot = geometry.firstSlot(epoch)
      val millis =
        if slot <= geometry.byron.slots then slot * byron.slotLengthMillis
        else
          geometry.byron.slots * byron.slotLengthMillis +
            (slot - geometry.byron.slots) * BigInt(geometry.slotLengthMillis)
      Bound(millis * PsPerMilli, slot, epoch)
    val safe = geometry.stabilityWindow
    val byronEra = EraSummary(
      bound(0),
      Some(bound(firstShelley)),
      byron.epochSlots,
      byron.slotLengthMillis,
      byron.k * 2,
      byron.k * 2
    )
    val shelleyBased = shelleyEpochs.zip(starts).zipWithIndex.map { case ((_, start), i) =>
      val startBound = bound(start)
      val end =
        if i + 1 < starts.size then Some(bound(starts(i + 1)))
        else
          // the safe-zone bound of the current era
          val beyond = tipSlot + 1 + safe - startBound.slot
          val size = BigInt(geometry.epochLength)
          val epochs = (beyond + size - 1) / size
          Some(bound(start + epochs.max(0)))
      EraSummary(
        startBound,
        end,
        BigInt(geometry.epochLength),
        BigInt(geometry.slotLengthMillis),
        safe,
        safe
      )
    }
    byronEra +: shelleyBased
