// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.consensus.state

import xyz.kd5ujc.scarab.ledger.state.{Diff, Word64}
import cats.{Eq, Show}
import cats.derived.*
import xyz.kd5ujc.scarab.consensus.{ForkTrigger, HardForkConfig}
import xyz.kd5ujc.scarab.ledger.{EpochGeometry, Horizon}
import xyz.kd5ujc.scarab.ledger.byron.{ByronLedger, ByronLedgerState}
import xyz.kd5ujc.scarab.protocol.{Era, StateLayout}

/** One era of the history (`EraSummary`, `HardFork/History/Summary.hs:198-205`, ouroboros-consensus
  * 82ecba32): where it starts, where it ends (`None`: unbounded, `EraUnbounded`,
  * `Summary.hs:210-216`) and its parameters (`EraParams`: epoch size, slot length in milliseconds,
  * safe zone, genesis window, and the slots of a Peras round where the era has rounds,
  * `eraPerasRoundLength`, `EraParams.hs:140-147` at 2416493b; [[EraHistory.perasRoundLength]]).
  */
final case class EraSummary(
    start: Bound,
    end: Option[Bound],
    epochSize: BigInt,
    slotLengthMillis: BigInt,
    safeZone: BigInt,
    genesisWindow: BigInt,
    perasRoundLength: Option[BigInt]
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
    Bound(millis * PsPerMilli, slot, epoch, Bound.Layout.Node11_1(None))

  /** The slots of a Peras round of `era` in the build of `layout` (`eraPerasRoundLength`), none
    * where the era has no rounds (`NoPerasEnabled`). The 11.1 line gives no era rounds: Byron's
    * parameters (`byronEraParams`, `Byron/Ledger/Ledger.hs:323-331`) and the Shelley-based eras'
    * (`shelleyEraParams`, `Shelley/Ledger/Ledger.hs:168-180`), ouroboros-consensus 82ecba32. The
    * Leios build (2416493b) gives Byron none (`Byron/Ledger/Ledger.hs:326-334`) and each
    * Shelley-based era its `getShelleyEraPerasRoundLength` (`Shelley/Ledger/Ledger.hs:188-200`):
    * none by default (`Shelley/Eras.hs:147-151`), and for Dijkstra `dijkstraPerasRoundLength`
    * (`:283`), 90 slots (`Peras/Params.hs:240-242`), a constant of the build, not of the genesis.
    * The Leios layout is that one build's.
    */
  def perasRoundLength(layout: StateLayout, era: Era): Option[BigInt] = layout match
    case StateLayout.Node11_1 => None
    case StateLayout.Leios =>
      era match
        case Era.Dijkstra => Some(DijkstraPerasRoundLength)
        case Era.Byron | Era.Shelley | Era.Allegra | Era.Mary | Era.Alonzo | Era.Babbage |
            Era.Conway =>
          None

  private val DijkstraPerasRoundLength = BigInt(90)

  /** The Peras round of `initBound`, the start of the first era: none in the 11.1 line
    * (`NoPerasEnabled`, ouroboros-consensus 82ecba32 `Summary.hs:92-101`), round zero in the Leios
    * build (2416493b `Summary.hs:103-110`).
    */
  def originLayout(layout: StateLayout): Bound.Layout = layout match
    case StateLayout.Node11_1 => Bound.Layout.Node11_1(None)
    case StateLayout.Leios    => Bound.Layout.Leios(PerasRoundNo(Word64.zero))

  /** The Peras round of `mkUpperBound` for an era with no Peras rounds, from the round of its lower
    * bound: the 11.1 line's is none, `addPerasRounds <$> NoPerasEnabled <*> _` (82ecba32
    * `Summary.hs:125-131`); the Leios build's keeps the lower bound's next round (2416493b
    * `Summary.hs:134-147`).
    */
  def unroundedLayout(lo: Bound.Layout): Bound.Layout = lo match
    case Bound.Layout.Node11_1(_)  => Bound.Layout.Node11_1(None)
    case l @ Bound.Layout.Leios(_) => l

  /** The Peras round of `mkUpperBound` (2416493b `Summary.hs:126-151`, 82ecba32 `:117-141`): the
    * bound that ends `slots` slots of `era` after a lower bound of layout `lo`. An era with no
    * rounds is [[unroundedLayout]]; one with rounds adds the whole rounds of its slots to the lower
    * bound's, `div` of the slots by the round length and `addPerasRounds`, a `Word64` sum (2416493b
    * `HardFork/History/Util.hs:31-32`).
    */
  def upperLayout(lo: Bound.Layout, era: Era, slots: BigInt): Bound.Layout =
    perasRoundLength(lo.stateLayout, era).fold(unroundedLayout(lo)) { length =>
      def add(r: PerasRoundNo) =
        PerasRoundNo(
          Word64.fromUnsigned((r.word.toBigInt + slots / length).mod(Word64Modulus).toLong)
        )
      lo match
        case Bound.Layout.Node11_1(round) => Bound.Layout.Node11_1(round.map(add))
        case Bound.Layout.Leios(next)     => Bound.Layout.Leios(add(next))
    }

  private val Word64Modulus = BigInt(2).pow(64)

  /** `mkUpperBound` of `era` from `lo` to the bound `at` (the time, slot and epoch, as [[boundAt]]
    * or the summary's arithmetic places it), with the Peras round of [[upperLayout]].
    */
  def upperBound(lo: Bound, era: Era, at: Bound): Bound =
    at.copy(layout = upperLayout(lo.layout, era, at.slot - lo.slot))

  /** The era history from the configuration, every bound in the layout of `layout` 's build: the
    * first era starts at `initBound` ([[originLayout]]) and each era ends at its `mkUpperBound`
    * ([[upperBound]]), where the next begins.
    */
  def summary(
      geometry: EpochGeometry,
      byron: ByronParams,
      forks: HardForkConfig,
      observed: Map[Era, BigInt],
      current: Era,
      tipSlot: BigInt,
      layout: StateLayout
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
    val byronEra = (
      Era.Byron,
      BigInt(0),
      firstShelley,
      EraSummary(_, _, byron.epochSlots, byron.slotLengthMillis, byron.k * 2, byron.k * 2, _)
    )
    val shelleyBased = shelleyEpochs.zip(starts).zipWithIndex.map { case ((era, start), i) =>
      val end =
        starts.lift(i + 1).getOrElse {
          // the safe-zone bound of the current era
          val beyond = tipSlot + 1 + safe - bound(start).slot
          val size = BigInt(geometry.epochLength)
          val epochs = (beyond + size - 1) / size
          start + epochs.max(0)
        }
      (
        era,
        start,
        end,
        EraSummary(
          _,
          _,
          BigInt(geometry.epochLength),
          BigInt(geometry.slotLengthMillis),
          safe,
          safe,
          _
        )
      )
    }
    (byronEra +: shelleyBased)
      .foldLeft((originLayout(layout), Vector.empty[EraSummary])) {
        case ((from, eras), (era, start, end, summary)) =>
          val lo = bound(start).copy(layout = from)
          val hi = upperBound(lo, era, bound(end))
          (hi.layout, eras :+ summary(lo, Some(hi), perasRoundLength(layout, era)))
      }
      ._2

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
    * `Shelley/Ledger/Ledger.hs` `shelleyEraParams`), and the last era the node knows (Dijkstra) has
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
    * is never known, nor one the node does not enter ([[HardForkConfig.trigger]]).
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
  /** What a Byron ledger state at a tip tells of the end of Byron (`singleEraTransition` of
    * `ByronHFC.hs:240-247`, ouroboros-consensus 82ecba32): a configured epoch
    * (`TestShelleyHardForkAtEpoch`, a Byron span that is not derived) is known from the start;
    * otherwise the trigger is version 2 (`TriggerHardForkAtVersion`, as the node configures it) and
    * `byronTransition` (`:161-190`) reports the adoption epoch of a stable candidate update to it
    * ([[ByronLedger.transition]]) (#804).
    */
  def byronTransition(
      geometry: EpochGeometry,
      byron: ByronParams,
      ledger: Option[ByronLedgerState],
      tip: Option[BigInt]
  ): Horizon.Transition =
    if !geometry.byron.derived then Horizon.Transition.Known(geometry.firstShelleyEpoch)
    else
      ledger
        .flatMap(ByronLedger.transition(byron.k, byron.epochSlots, ByronLedger.ShelleyMajor, _))
        .fold(Horizon.Transition.Unknown(tip))(Horizon.Transition.Known(_))

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
    Era.values.lift(era.index + 1).flatMap(forks.trigger).fold(unknown) {
      case ForkTrigger.AtEpoch(e) => Horizon.Transition.Known(e)
      case ForkTrigger.AtVersion(v) =>
        if futureMajor.contains(BigInt(v)) && afterVoting >= securityParam then
          Horizon.Transition.Known(epoch + 1)
        else unknown
    }
