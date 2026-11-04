// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.consensus

import cats.data.NonEmptyVector
import scala.annotation.tailrec
import xyz.kd5ujc.scarab.ledger.EnvelopeError
import xyz.kd5ujc.scarab.ledger.rules.Hfc
import xyz.kd5ujc.scarab.protocol.Era

/** What makes the hard-fork combinator enter an era: a definite parameter update to exactly the
  * era's first protocol major version, with at least `k` blocks of the epoch after its voting
  * deadline (`TriggerHardForkAtVersion`, mainnet style), or a configured epoch
  * (`TriggerHardForkAtEpoch`, the `Test<Era>HardForkAtEpoch` keys of test clusters).
  */
enum ForkTrigger:
  case AtVersion(major: Int)
  case AtEpoch(epoch: BigInt)

/** The era history of the Shelley-based eras and the envelope checks that depend on it.
  *
  * Upstream (ouroboros-consensus 4.2.0.x, the version cardano-node 11.1.3 uses):
  *   - `Ouroboros.Consensus.Cardano.Node` `toTriggerHardFork` (lines 355-365): an era is entered at
  *     its `eraProtVerLow` unless a `Test<Era>HardForkAtEpoch` key gives an epoch (`cardano-node`
  *     `Cardano.Node.Protocol.Cardano` lines 204-260);
  *   - `Shelley.ShelleyHFC` `singleEraTransition` (82ecba32 lines 194-249): with a version trigger
  *     the era is left at the next epoch when the definite parameter update of the state has
  *     exactly the next era's major version and at least `k` blocks followed the voting deadline
  *     ([[transition]]). The era a state is in is therefore a matter of its history, and every path
  *     that decides it ([[xyz.kd5ujc.scarab.ledger.rules.Hfc.tick]], the Genesis window's era
  *     summary) asks [[transition]];
  *   - `Shelley.Protocol.EnvelopeChecks.envelopeCheck` (lines 77-91): header and body size against
  *     the ticked pparams, and `ObsoleteNode` against the maximum major version, which is the
  *     header's for TPraos (`Protocol/TPraos.hs` lines 70-80) and the ledger view's for Praos
  *     (`Protocol/Praos.hs` lines 85-110);
  *   - `Cardano.Ledger.Conway.Rules.Bbody` `checkHeaderProtVerTooHigh` (lines 312-328): on mainnet
  *     only, the header's major version must not exceed the ledger's plus one.
  *
  * Byron to Shelley is decided by the Byron rules (`ByronRules.Config`), not here.
  */
final case class HardForkConfig(triggers: Map[Era, ForkTrigger], maxMajorProtVer: Int):

  /** The era a chain started from genesis is in at its first slot (`epoch`, Shelley's first):
    * `injectInitialExtLedgerState` extends the initial state to slot 0, entering every era whose
    * transition its genesis state already decides (ouroboros-consensus 82ecba32
    * `HardFork/Combinator/Embed/Nary.hs:259-306`). A genesis state has no definite update and no
    * block after the voting deadline, so only a configured epoch (`Test<Era>HardForkAtEpoch`)
    * enters an era there; the protocol version of the Shelley genesis enters none.
    */
  def initialEra(k: BigInt)(epoch: BigInt): Era =
    // a genesis state knows its counter (zero), so the walk is always decided
    eraOf(k)(Hfc.Pending(HardForkConfig.Sequence.head, epoch, None, 0L), epoch)
      .getOrElse(HardForkConfig.Sequence.head)

  /** The epoch at which the chain leaves the era of `pending` (`singleEraTransition`,
    * `ShelleyHFC.hs:220-227`): the configured epoch of the next era, or with a version trigger the
    * next epoch when the definite update is to exactly that version and `afterVoting >= k`.
    *
    * @param counterKnown
    *   whether `pending.afterVoting` is the state's counter; when it is not
    *   (`Gap.ShelleyTransition` that could not be recounted) it is a lower bound, and a version
    *   trigger that it does not already decide is [[Hfc.Decision.Undecided]] rather than a guess
    *   either way (owner-approved 2026-11-04, PR #772). A configured epoch does not read the
    *   counter.
    */
  def transition(k: BigInt, counterKnown: Boolean = true): Hfc.Transition = pending =>
    HardForkConfig
      .next(pending.era)
      .flatMap(triggers.get)
      .fold(Hfc.Decision.Stays) {
        case ForkTrigger.AtEpoch(epoch) => Hfc.Decision.Leaves(epoch)
        case ForkTrigger.AtVersion(major) =>
          if !pending.update.contains(BigInt(major)) then Hfc.Decision.Stays
          else if pending.afterVoting >= k then Hfc.Decision.Leaves(pending.epoch + 1)
          else if counterKnown then Hfc.Decision.Stays
          else Hfc.Decision.Undecided(pending.epoch + 1)
      }

  /** The era a block at `epoch` is in, from the state `pending` of the chain before it: the eras
    * the combinator's tick enters on the way (`extendToSlot`), each asked of [[transition]] with
    * the same state, as the translations carry the update and the counter over. An
    * [[Hfc.Decision.Undecided]] transition that `epoch` reaches is not an era.
    */
  def eraOf(k: BigInt, counterKnown: Boolean = true)(
      pending: Hfc.Pending,
      epoch: BigInt
  ): Either[Hfc.UndecidedTransition, Era] =
    @tailrec def go(era: Era): Either[Hfc.UndecidedTransition, Era] =
      transition(k, counterKnown)(pending.copy(era = era)) match
        case Hfc.Decision.Leaves(at) if at <= epoch =>
          HardForkConfig.next(era) match
            case Some(next) => go(next)
            case None       => Right(era)
        case Hfc.Decision.Undecided(at) if at <= epoch => Left(Hfc.UndecidedTransition(era, at))
        case _                                         => Right(era)
    go(pending.era)

  /** Envelope checks on a Shelley-based header, against the ledger state ticked to its slot.
    *
    * @param expected
    *   the era the hard-fork combinator has the chain in at the header's slot (the ticked state's,
    *   [[eraOf]]); a header of another era is `WrongEra`
    */
  def check(
      era: Era,
      expected: Era,
      epoch: BigInt,
      headerMajor: BigInt,
      ledgerMajor: BigInt,
      headerSize: BigInt,
      maxHeaderSize: BigInt,
      bodySize: BigInt,
      maxBodySize: BigInt,
      mainnet: Boolean
  ): Either[EnvelopeError, Unit] =
    val maxMajor = BigInt(maxMajorProtVer)
    val checked = if era.usesTPraos then headerMajor else ledgerMajor
    for
      _ <- Either.cond(
        era == expected,
        (),
        EnvelopeError.WrongEra(era, expected, epoch, ledgerMajor)
      )
      _ <- Either.cond(checked <= maxMajor, (), EnvelopeError.ObsoleteNode(checked, maxMajor))
      _ <- Either.cond(
        headerSize <= maxHeaderSize,
        (),
        EnvelopeError.HeaderSizeTooLarge(headerSize, maxHeaderSize)
      )
      _ <- Either.cond(
        bodySize <= maxBodySize,
        (),
        EnvelopeError.BlockSizeTooLarge(bodySize, maxBodySize)
      )
      _ <- Either.cond(
        !(mainnet && era == Era.Conway) || headerMajor <= ledgerMajor + 1,
        (),
        EnvelopeError.HeaderProtVerTooHigh(headerMajor, ledgerMajor + 1)
      )
    yield ()

object HardForkConfig:
  /** The highest major protocol version cardano-node 11.1.3 supports without experimental eras
    * (`Cardano.Node.Protocol.Cardano` line 192: `ProtVer 11 2`).
    */
  val MaxMajorProtVer: Int = 11

  /** The Shelley-based eras in the order they are entered. */
  val Sequence: NonEmptyVector[Era] =
    NonEmptyVector.of(Era.Shelley, Era.Allegra, Era.Mary, Era.Alonzo, Era.Babbage, Era.Conway)

  /** The Shelley-based era entered after `era`, if any. */
  def next(era: Era): Option[Era] =
    Sequence.toVector.lift(Sequence.toVector.indexOf(era) + 1)

  /** The first protocol major version of each era (`eraProtVerLow`). */
  def eraProtVerLow(era: Era): Int = era match
    case Era.Byron   => 0
    case Era.Shelley => 2
    case Era.Allegra => 3
    case Era.Mary    => 4
    case Era.Alonzo  => 5
    case Era.Babbage => 7
    case Era.Conway  => 9

  /** An era with a `Test<Era>HardForkAtEpoch` key is entered at that epoch, any other at its first
    * protocol version.
    */
  def fromEpochs(epochs: Map[Era, BigInt], maxMajorProtVer: Int = MaxMajorProtVer): HardForkConfig =
    HardForkConfig(
      Sequence.tail.map { era =>
        era -> epochs
          .get(era)
          .fold[ForkTrigger](ForkTrigger.AtVersion(eraProtVerLow(era)))(
            ForkTrigger.AtEpoch(_)
          )
      }.toMap,
      maxMajorProtVer
    )

  /** Every era entered by protocol version, as on mainnet. */
  val default: HardForkConfig = fromEpochs(Map.empty)
