// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.consensus

import xyz.kd5ujc.scarab.ledger.EnvelopeError
import xyz.kd5ujc.scarab.protocol.Era

/** What makes the hard-fork combinator enter an era: the ledger's protocol major version reaching
  * the era's first version (`TriggerHardForkAtVersion`, mainnet style) or a configured epoch
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
  *   - `Shelley.ShelleyHFC.singleEraTransition` / `shelleyTransition` (lines 200-250): with a
  *     version trigger the transition is the epoch in which the ledger's pparams reach that major
  *     version, so the era of a block is a function of its epoch and the protocol major version of
  *     the ledger state ticked to the block's slot;
  *   - `Shelley.Protocol.EnvelopeChecks.envelopeCheck` (lines 77-91): header and body size against
  *     the ticked pparams, and `ObsoleteNode` against the maximum major version, which is the
  *     header's for TPraos (`Protocol/TPraos.hs` lines 70-80) and the ledger view's for Praos
  *     (`Protocol/Praos.hs` lines 85-110);
  *   - `Cardano.Ledger.Conway.Rules.Bbody` `checkHeaderProtVerTooHigh` (lines 312-328): on mainnet
  *     only, the header's major version must not exceed the ledger's plus one.
  *
  * Byron to Shelley stays a configured epoch (`firstShelleyEpoch`): the Byron ledger's update state
  * that upstream reads is not modelled.
  */
final case class HardForkConfig(triggers: Map[Era, ForkTrigger], maxMajorProtVer: Int):

  /** The era the chain is in at `epoch` when the ledger's protocol major version is `major`: the
    * eras are entered in order, so the first one whose trigger has not fired ends the walk.
    */
  def eraAt(epoch: BigInt, major: BigInt): Era =
    HardForkConfig.Sequence.drop(1).takeWhile(fired(_, epoch, major)).lastOption.getOrElse(Era.Shelley)

  private def fired(era: Era, epoch: BigInt, major: BigInt): Boolean =
    triggers(era) match
      case ForkTrigger.AtEpoch(e)   => epoch >= e
      case ForkTrigger.AtVersion(v) => major >= v

  /** Envelope checks on a Shelley-based header, against the ledger state ticked to its slot.
    *
    * @param ledgerTracksVersion
    *   false when the ledger cannot see version changes made by Conway governance (the bootstrap
    *   ledger): a Conway header is then also accepted where only Babbage is derived.
    */
  def check(
      era: Era,
      epoch: BigInt,
      headerMajor: BigInt,
      ledgerMajor: BigInt,
      headerSize: BigInt,
      maxHeaderSize: BigInt,
      bodySize: BigInt,
      maxBodySize: BigInt,
      mainnet: Boolean,
      ledgerTracksVersion: Boolean = true
  ): Either[EnvelopeError, Unit] =
    val expected = eraAt(epoch, ledgerMajor)
    val maxMajor = BigInt(maxMajorProtVer)
    val checked = if era.usesTPraos then headerMajor else ledgerMajor
    for
      _ <- Either.cond(
        era == expected ||
          (!ledgerTracksVersion && expected == Era.Babbage && era == Era.Conway),
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
        !(mainnet && era == Era.Conway && ledgerTracksVersion) || headerMajor <= ledgerMajor + 1,
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
  val Sequence: Vector[Era] =
    Vector(Era.Shelley, Era.Allegra, Era.Mary, Era.Alonzo, Era.Babbage, Era.Conway)

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
      Sequence.drop(1).map { era =>
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
