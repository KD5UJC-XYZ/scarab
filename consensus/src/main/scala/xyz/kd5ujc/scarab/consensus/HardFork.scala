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
  *   - `Shelley.ShelleyHFC.shelleyTransition` (82ecba32 lines 194-227): with a version trigger the
  *     era is left at the next epoch when the definite parameter update of the state has exactly
  *     the next era's major version and at least `k` blocks followed the voting deadline
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

  /** The era of a state whose history is not at hand (an initial or imported state), from its epoch
    * and protocol major version: the eras are entered in order, so the first one whose trigger has
    * not fired ends the walk. An approximation of the history: a running chain's era is the one its
    * state is in, moved on only by [[transition]].
    */
  def eraAt(epoch: BigInt, major: BigInt): Era =
    // the first era, unless a later one's trigger has fired
    HardForkConfig.Sequence.tail
      .takeWhile(fired(_, epoch, major))
      .lastOption
      .getOrElse(HardForkConfig.Sequence.head)

  private def fired(era: Era, epoch: BigInt, major: BigInt): Boolean =
    triggers(era) match
      case ForkTrigger.AtEpoch(e)   => epoch >= e
      case ForkTrigger.AtVersion(v) => major >= v

  /** The epoch at which the chain leaves the era of `pending` (`singleEraTransition`,
    * `ShelleyHFC.hs:220-227`): the configured epoch of the next era, or with a version trigger the
    * next epoch when the definite update is to exactly that version and `afterVoting >= k`.
    */
  def transition(k: BigInt): Hfc.Transition = pending =>
    HardForkConfig
      .next(pending.era)
      .flatMap(triggers.get)
      .flatMap {
        case ForkTrigger.AtEpoch(epoch) => Some(epoch)
        case ForkTrigger.AtVersion(major) =>
          Option.when(
            pending.update.contains(BigInt(major)) && pending.afterVoting >= k
          )(pending.epoch + 1)
      }

  /** The era a block at `epoch` is in, from the state `pending` of the chain before it: the eras
    * the combinator's tick enters on the way (`extendToSlot`), each asked of [[transition]] with
    * the same state, as the translations carry the update and the counter over.
    */
  def eraOf(k: BigInt)(pending: Hfc.Pending, epoch: BigInt): Era =
    @tailrec def go(era: Era): Era =
      val entered = transition(k)(pending.copy(era = era)).exists(_ <= epoch)
      HardForkConfig.next(era).filter(_ => entered) match
        case Some(next) => go(next)
        case None       => era
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
