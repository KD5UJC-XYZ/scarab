// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import cats.data.NonEmptyVector

/** The hard-fork combinator's summary of the era history at a ledger tip (`Summary xs`,
  * `HardFork/History/Summary.hs`): the eras up to the one the tip is in, each with its start and
  * its end, and the parameters of each. The ledger translates a slot of a transaction's validity
  * interval to time through the `EpochInfo` that consensus builds from it (`summaryToEpochInfo`,
  * `HardFork/History/EpochInfo.hs`), so a slot the summary does not cover is a failure of the
  * translation (`TimeTranslationPastHorizon`, cardano-ledger `Alonzo/Plutus/TxInfo.hs`
  * `transValidityInterval`, `slotToPOSIXTime` in `libs/cardano-ledger-core/.../Plutus/TxInfo.hs`)
  * and not a time.
  *
  * Sources: ouroboros-consensus `82ecba329d7d054340bf707d44fe6e9ac27cec40` (4.2.1.0),
  * cardano-ledger `f649f9751074d2ab3de033fc3912f29c9862c1f5`.
  *
  * @param eras
  *   the eras in order; every era but the last ends where the next begins, and the last is open
  *   only where the reference's is (`EraUnbounded`, never for a Cardano era)
  */
final case class Horizon(eras: NonEmptyVector[Horizon.Era]):

  /** The time of the start of `slot` since the system start, in picoseconds, or none where the
    * summary does not cover it. This is the query that `epochInfoSlotToRelativeTime` runs,
    * `slotToWallclock` (`Qry.hs:628-632`): the slot must lie in an era that has not ended
    * (`ESlotLength` guards the upper bound as exclusive, so the slot at which the last era ends is
    * past the horizon).
    */
  def timePs(slot: BigInt): Option[BigInt] =
    eras.toVector.collectFirst {
      case era if era.covers(slot) =>
        era.start.timePs + (slot - era.start.slot) * era.params.slotLengthPs
    }

  /** The length of the slot `slot` in picoseconds where the summary covers it (`ESlotLength`). */
  def slotLengthPs(slot: BigInt): Option[BigInt] =
    eras.toVector.collectFirst { case era if era.covers(slot) => era.params.slotLengthPs }

  /** The text of the reference's `PastHorizonException` for `slot`: `show` of it, which the ledger
    * keeps as the `Text` of `TimeTranslationPastHorizon` (consensus `Shelley/Ledger/Ledger.hs`
    * `completeLedgerConfig`: `hoistEpochInfo (… T.pack . show …)`). It names the call stack of the
    * query, the expression and the summary.
    */
  def pastHorizonText(slot: BigInt): String =
    val expression =
      s"Some (EPair (ERelToAbsTime (ERelSlotToTime (EAbsToRelSlot (ELit (SlotNo $slot))))) " +
        s"(ESlotLength (ELit (SlotNo $slot))))"
    s"PastHorizon {pastHorizonCallStack = ${Horizon.CallStack}, " +
      s"pastHorizonExpression = $expression, " +
      s"pastHorizonSummary = [${eras.toVector.map(_.show).mkString(",")}]}"

object Horizon:

  /** A point on the era history (`Bound`, `Summary.hs:82-88`): the time since the system start in
    * picoseconds, the slot and the epoch.
    */
  final case class Edge(timePs: BigInt, slot: BigInt, epoch: BigInt):
    def show: String =
      s"Bound {boundTime = RelativeTime ${showSeconds(timePs)}, boundSlot = SlotNo $slot, " +
        s"boundEpoch = EpochNo $epoch, boundPerasRound = MkPerasEnabled Nothing}"

  /** `EraParams` (`EraParams.hs`) of an era.
    *
    * @param epochSize
    *   slots in an epoch
    * @param slotLengthMillis
    *   length of a slot
    * @param safeZone
    *   `StandardSafeZone`: the slots after the tip up to which the era is certain to last
    * @param genesisWindow
    *   `GenesisWindow`
    */
  final case class Params(
      epochSize: BigInt,
      slotLengthMillis: BigInt,
      safeZone: BigInt,
      genesisWindow: BigInt
  ):
    def slotLengthPs: BigInt = slotLengthMillis * PsPerMilli

    /** `mkUpperBound`: the bound at the start of `epoch` of an era that starts at `start`. */
    def boundAt(start: Edge, epoch: BigInt): Edge =
      val epochs = epoch - start.epoch
      val slots = epochs * epochSize
      Edge(start.timePs + slots * slotLengthPs, start.slot + slots, epoch)

    /** `applySafeZone` of a standard safe zone: the bound of the first epoch boundary at or after
      * `from` plus the safe zone (`slotToEpochBound`: the slot rounded up to an epoch).
      */
    def safeEnd(start: Edge, from: BigInt): Edge =
      val slots = from + safeZone - start.slot
      boundAt(start, start.epoch + ceilDiv(slots, epochSize))

    def show: String =
      s"EraParams {eraEpochSize = EpochSize $epochSize, " +
        s"eraSlotLength = SlotLength ${showSeconds(slotLengthPs)}, " +
        s"eraSafeZone = StandardSafeZone $safeZone, " +
        s"eraGenesisWin = GenesisWindow {unGenesisWindow = $genesisWindow}, " +
        "eraPerasRoundLength = MkPerasEnabled Nothing}"

  /** An era of the summary (`EraSummary`, `Summary.hs:198-205`); no end is `EraUnbounded`. */
  final case class Era(start: Edge, end: Option[Edge], params: Params):

    /** Whether the translation of `slot` can be answered in this era. */
    def covers(slot: BigInt): Boolean = slot >= start.slot && end.forall(slot < _.slot)

    def show: String =
      val ending = end.fold("EraUnbounded")(e => s"EraEnd (${e.show})")
      s"EraSummary {eraStart = ${start.show}, eraEnd = $ending, eraParams = ${params.show}}"

  /** What the ledger tip tells of the end of the era it is in (`TransitionInfo`,
    * `HardFork/Combinator/State/Infra.hs`).
    */
  enum Transition:
    /** The next era's start is not known: the tip's slot (none at Origin) fixes the safe zone. */
    case Unknown(tip: Option[BigInt])

    /** The next era starts at the epoch. */
    case Known(epoch: BigInt)

  /** The eras of the chain up to the tip's, without what the tip tells (`reconstructSummary` takes
    * the rest, [[Transition]], separately).
    *
    * @param past
    *   the eras before the current one, each with its end
    * @param start
    *   where the current era starts
    * @param current
    *   the parameters of the current era
    * @param next
    *   the parameters of the era after the current one, where the code of the node has one
    */
  final case class History(past: Vector[Era], start: Edge, current: Params, next: Option[Params]):

    /** `reconstructSummary` (`State/Infra.hs:194-262`): the summary at a tip whose era's end is
      * `transition`.
      */
    def at(transition: Transition): Horizon =
      val tail: Vector[Era] = transition match
        case Transition.Known(epoch) =>
          val end = current.boundAt(start, epoch)
          // the safe zone of the next era applies from its start; without a next era in the
          // code only the current one is known (`ExactlyNil`)
          Era(start, Some(end), current) +: next.toVector.map(params =>
            Era(end, Some(params.safeEnd(end, end.slot)), params)
          )
        case Transition.Unknown(tip) =>
          // even a safe zone of zero starts at the slot after the tip
          val after = tip.fold(BigInt(0))(_ + 1)
          Vector(Era(start, Some(current.safeEnd(start, after)), current))
      Horizon(NonEmptyVector.fromVectorUnsafe(past ++ tail))

  /** A history of one open-ended era from the start of time, with the parameters `params`. */
  def single(params: Params): History =
    History(Vector.empty, Edge(0, 0, 0), params, None)

  private val PsPerMilli = BigInt(1000000000)

  private def ceilDiv(a: BigInt, b: BigInt): BigInt = (a + (b - 1 - (a + b - 1).mod(b))) / b

  /** `show` of a `NominalDiffTime` of `ps` picoseconds, as Haskell prints it: the seconds with the
    * trailing zeros of the fraction dropped, then `s`.
    */
  private[ledger] def showSeconds(ps: BigInt): String =
    val whole = ps / BigInt("1000000000000")
    val fraction = (ps % BigInt("1000000000000")).toString.reverse.padTo(12, '0').reverse
    val trimmed = fraction.reverse.dropWhile(_ == '0').reverse
    if trimmed.isEmpty then s"${whole}s" else s"$whole.${trimmed}s"

  /** The `CallStack` of the reference's query, as `show` renders it: the frames from `runQuery`
    * through the epoch info of the hard-fork combinator and the `EpochInfo` API of cardano-slotting
    * to `slotToPOSIXTime` of the ledger. The source locations are those of the reference node
    * 11.1.3's build (ouroboros-consensus 4.2.1.0, cardano-slotting 0.2.2.0, cardano-ledger-core
    * 1.21.0.0), the package names with the unit hashes of that build; the constant is what the
    * reference's refusal contains, byte for byte.
    */
  private val CallStack: String =
    def frame(
        name: String,
        pkg: String,
        module: String,
        file: String,
        line: Int,
        col: Int,
        end: Int
    ): String =
      s"""("$name",SrcLoc {srcLocPackage = "$pkg", srcLocModule = "$module", """ +
        s"""srcLocFile = "$file", srcLocStartLine = $line, srcLocStartCol = $col, """ +
        s"srcLocEndLine = $line, srcLocEndCol = $end})"
    val consensus = "ouroboros-consensus-4.2.1.0-FyOKMtU9KSI3k5ajYD0kou"
    val slotting = "cardano-slotting-0.2.2.0-7HLAaDHPOB6J12nDlytzL1"
    val ledger = "cardano-ledger-core-1.21.0.0-8OWkWokxAiZ2JD113WfJdK"
    val qry = "ouroboros-consensus/src/ouroboros-consensus/Ouroboros/Consensus/HardFork/History"
    val api = "src/Cardano/Slotting/EpochInfo/API.hs"
    val apiModule = "Cardano.Slotting.EpochInfo.API"
    val infoModule = "Ouroboros.Consensus.HardFork.History.EpochInfo"
    val inner = frame("epochInfoSlotToRelativeTime_", slotting, apiModule, api, 55, 5, 33)
    val use =
      frame("a use of `epochInfoSlotToRelativeTime_'", slotting, apiModule, api, 114, 31, 59)
    List(
      frame(
        "runQuery",
        consensus,
        "Ouroboros.Consensus.HardFork.History.Qry",
        s"$qry/Qry.hs",
        487,
        44,
        52
      ),
      frame("interpretQuery", consensus, infoModule, s"$qry/EpochInfo.hs", 40, 48, 62),
      frame("interpretQuery'", consensus, infoModule, s"$qry/EpochInfo.hs", 35, 9, 24),
      inner,
      use,
      frame("epochInfoSlotToRelativeTime", slotting, apiModule, api, 130, 42, 69),
      inner,
      use,
      frame("epochInfoSlotToRelativeTime", slotting, apiModule, api, 95, 30, 57),
      frame(
        "epochInfoSlotToUTCTime",
        ledger,
        "Cardano.Ledger.Plutus.TxInfo",
        "src/Cardano/Ledger/Plutus/TxInfo.hs",
        171,
        9,
        31
      )
    ).mkString("[", ",", "]")
