// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

/** The slot to POSIX time translation of a script context: the start of a slot in milliseconds. The
  * reference builds it from the era history at the ledger tip, so a slot beyond the forecast
  * horizon has no time and the translation fails (`TimeTranslationPastHorizon`, see [[Horizon]]).
  */
sealed trait PosixClock:
  def posix(slot: BigInt): Either[TranslationError, BigInt]

object PosixClock:

  /** The era history at a tip, over the ledger's system start (`slotToPOSIXTime`,
    * [[SystemStart.posixMillis]]).
    */
  final case class Forecast(systemStart: SystemStart, horizon: Horizon) extends PosixClock:
    def posix(slot: BigInt): Either[TranslationError, BigInt] =
      horizon
        .timePs(slot)
        .map(systemStart.posixMillis)
        .toRight(TranslationError.PastHorizon(slot, horizon))

  /** Alonzo's clock: the era history extended linearly from the slot of the block (or of the
    * mempool's ledger) past the end of its horizon, with the slot length at that slot
    * (`unsafeLinearExtendEpochInfo`, cardano-base `cardano-slotting` `EpochInfo/Extend.hs`). The
    * Alonzo ledger builds its transactions' annotations through it, in the LEDGERS rule and in the
    * mempool (`Alonzo/Rules/Ledgers.hs` `alonzoLedgersTransition`, `Alonzo.hs`
    * `ApplyTx AlonzoEra`), so no slot is past the horizon there; Babbage and Conway take the epoch
    * info as it is (`Babbage/Rules/Ledgers.hs`, `Shelley/API/Mempool.hs`
    * `defaultApplyTxWithValidation`). A slot up to the basis is the underlying history's; the basis
    * itself must be inside it.
    */
  final case class Extended(basis: BigInt, base: Forecast) extends PosixClock:
    def posix(slot: BigInt): Either[TranslationError, BigInt] =
      if slot <= basis then base.posix(slot)
      else
        val ps = for
          start <- base.horizon.timePs(basis)
          length <- base.horizon.slotLengthPs(basis)
        yield start + (slot - basis) * length
        ps.map(base.systemStart.posixMillis)
          .toRight(TranslationError.PastHorizon(basis, base.horizon))

  /** A clock with no horizon: every slot has a time. For fixtures and recorded cases that carry no
    * era history; the node's own clocks are [[Forecast]].
    */
  final case class Total(time: BigInt => BigInt) extends PosixClock:
    def posix(slot: BigInt): Either[TranslationError, BigInt] = Right(time(slot))

  /** The clock of a chain at the tip of `history`, from the ledger's system start `start` (the
    * `systemStart` of `Globals`, not the hard-fork combinator's): see [[Horizon.History]]; Alonzo's
    * ([[Extended]]) when `extendedFrom` gives the basis slot.
    */
  def forecast(
      start: SystemStart,
      history: Horizon.History,
      transition: Horizon.Transition,
      extendedFrom: Option[BigInt] = None
  ): PosixClock =
    val base = Forecast(start, history.at(transition))
    extendedFrom.fold[PosixClock](base)(Extended(_, base))

  /** The slots of `geometry` from the ledger's system start `start`, without a horizon. */
  def total(start: SystemStart, geometry: EpochGeometry): PosixClock =
    Total(slot => start.posixMillis(geometry.slotOffsetMillis(slot) * PsPerMilli))

  private val PsPerMilli = BigInt(1000000000)
