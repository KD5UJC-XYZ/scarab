// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.consensus.state

import xyz.kd5ujc.scarab.ledger.state.Diff
import cats.{Eq, Show}
import cats.derived.*
import xyz.kd5ujc.scarab.consensus.{ForkTrigger, HardForkConfig}
import xyz.kd5ujc.scarab.ledger.{EpochGeometry, Horizon}
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

  /** The first slot of `epoch` on the era history, with the time since the system start. */
  def boundAt(geometry: EpochGeometry, byron: ByronParams, epoch: BigInt): Bound =
    val slot = geometry.firstSlot(epoch)
    val millis =
      if slot <= geometry.byron.slots then slot * byron.slotLengthMillis
      else
        geometry.byron.slots * byron.slotLengthMillis +
          (slot - geometry.byron.slots) * BigInt(geometry.slotLengthMillis)
    Bound(millis * PsPerMilli, slot, epoch)

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
    def bound(epoch: BigInt): Bound = boundAt(geometry, byron, epoch)
    val safe = geometry.stabilityWindow.toBigInt
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

  private def edge(b: Bound): Horizon.Edge = Horizon.Edge(b.timePs, b.slot, b.epoch)

  private def params(geometry: EpochGeometry, byron: ByronParams, era: Era): Horizon.Params =
    if era == Era.Byron then
      Horizon.Params(byron.epochSlots, byron.slotLengthMillis, byron.k * 2, byron.k * 2)
    else
      val safe = geometry.stabilityWindow.toBigInt
      Horizon.Params(BigInt(geometry.epochLength), BigInt(geometry.slotLengthMillis), safe, safe)

  /** The eras of a telescope in `era`, which started at `start` after the `past` eras, as the
    * summary of the reference reconstructs them (`reconstructSummary`,
    * `HardFork/Combinator/State/Infra.hs`): every era has the parameters of the configuration
    * (Byron's own, the Shelley-based ones' 3k/f safe zone and genesis window,
    * `Shelley/Ledger/Ledger.hs` `shelleyEraParams`), and the last era the node knows (Conway) has
    * no successor.
    */
  def horizonHistory(
      geometry: EpochGeometry,
      byron: ByronParams,
      era: Era,
      past: Vector[Past],
      start: Bound
  ): Horizon.History =
    val closed = past.zipWithIndex.map { case (p, i) =>
      Horizon.Era(edge(p.start), Some(edge(p.end)), params(geometry, byron, Era.values(i)))
    }
    val next = Era.values.lift(era.index + 1).map(params(geometry, byron, _))
    Horizon.History(closed, edge(start), params(geometry, byron, era), next)

  /** What the ledger state at a tip tells of the end of its era (`singleEraTransition`,
    * `Shelley/ShelleyHFC.hs`, as `mostRecentTransitionInfo` reads it): a configured epoch of the
    * next era is known from the start; a version trigger is known once the parameters for the next
    * epoch have the next era's first protocol version and at least k blocks have been applied after
    * the voting deadline of the epoch (`shelleyTransition`). The era after the last the node knows
    * is never known.
    *
    * @param epoch
    *   the epoch of the new-epoch state (the parameters take effect in the next one)
    * @param futureMajor
    *   the protocol major version of the parameters already decided for the next epoch
    * @param afterVoting
    *   the blocks applied after the voting deadline in the epoch (`shelleyAfterVoting`)
    * @param tip
    *   the slot of the last block, none at Origin
    */
  def transition(
      forks: HardForkConfig,
      securityParam: BigInt,
      era: Era,
      epoch: BigInt,
      futureMajor: Option[BigInt],
      afterVoting: BigInt,
      tip: Option[BigInt]
  ): Horizon.Transition =
    val unknown = Horizon.Transition.Unknown(tip)
    Era.values.lift(era.index + 1).flatMap(forks.triggers.get).fold(unknown) {
      case ForkTrigger.AtEpoch(e) => Horizon.Transition.Known(e)
      case ForkTrigger.AtVersion(v) =>
        if futureMajor.contains(BigInt(v)) && afterVoting >= securityParam then
          Horizon.Transition.Known(epoch + 1)
        else unknown
    }
