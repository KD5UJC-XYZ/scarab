// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.conway

import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Rational
import xyz.kd5ujc.scarab.ledger.DecodeField
import xyz.kd5ujc.scarab.ledger.era.{PParamsError, UnitInterval}

/** The stake pools' voting thresholds (`data PoolVotingThresholds`, cardano-ledger f649f975
  * `eras/conway/impl/src/Cardano/Ledger/Conway/PParams.hs:304-310`), each a `UnitInterval`.
  */
final case class PoolVotingThresholds(
    pvtMotionNoConfidence: UnitInterval,
    pvtCommitteeNormal: UnitInterval,
    pvtCommitteeNoConfidence: UnitInterval,
    pvtHardForkInitiation: UnitInterval,
    pvtPPSecurityGroup: UnitInterval
):
  /** The thresholds in the form the node keeps. */
  def toStored: xyz.kd5ujc.scarab.ledger.PoolVotingThresholds =
    xyz.kd5ujc.scarab.ledger.PoolVotingThresholds(
      pvtMotionNoConfidence.rational,
      pvtCommitteeNormal.rational,
      pvtCommitteeNoConfidence.rational,
      pvtHardForkInitiation.rational,
      pvtPPSecurityGroup.rational
    )

object PoolVotingThresholds:
  /** `def` (`Conway/PParams.hs:333-334`, cardano-ledger f649f975): every threshold zero. */
  val zero: PoolVotingThresholds = PoolVotingThresholds(
    UnitInterval.zero,
    UnitInterval.zero,
    UnitInterval.zero,
    UnitInterval.zero,
    UnitInterval.zero
  )

  /** The thresholds the node keeps, each refused outside `[0, 1]`. */
  def fromStored(
      t: xyz.kd5ujc.scarab.ledger.PoolVotingThresholds
  ): Either[PParamsError, PoolVotingThresholds] =
    val u = VotingThresholds.unit(DecodeField.PoolVotingThresholds)
    (
      u(t.motionNoConfidence),
      u(t.committeeNormal),
      u(t.committeeNoConfidence),
      u(t.hardForkInitiation),
      u(t.securityGroup)
    ).mapN(PoolVotingThresholds.apply)

/** The DReps' voting thresholds (`data DRepVotingThresholds`, cardano-ledger f649f975
  * `eras/conway/impl/src/Cardano/Ledger/Conway/PParams.hs:394-405`), each a `UnitInterval`.
  */
final case class DRepVotingThresholds(
    dvtMotionNoConfidence: UnitInterval,
    dvtCommitteeNormal: UnitInterval,
    dvtCommitteeNoConfidence: UnitInterval,
    dvtUpdateToConstitution: UnitInterval,
    dvtHardForkInitiation: UnitInterval,
    dvtPPNetworkGroup: UnitInterval,
    dvtPPEconomicGroup: UnitInterval,
    dvtPPTechnicalGroup: UnitInterval,
    dvtPPGovGroup: UnitInterval,
    dvtTreasuryWithdrawal: UnitInterval
):
  /** The thresholds in the form the node keeps. */
  def toStored: xyz.kd5ujc.scarab.ledger.DRepVotingThresholds =
    xyz.kd5ujc.scarab.ledger.DRepVotingThresholds(
      dvtMotionNoConfidence.rational,
      dvtCommitteeNormal.rational,
      dvtCommitteeNoConfidence.rational,
      dvtUpdateToConstitution.rational,
      dvtHardForkInitiation.rational,
      dvtPPNetworkGroup.rational,
      dvtPPEconomicGroup.rational,
      dvtPPTechnicalGroup.rational,
      dvtPPGovGroup.rational,
      dvtTreasuryWithdrawal.rational
    )

object DRepVotingThresholds:
  /** `def` (`Conway/PParams.hs:413-414`, cardano-ledger f649f975): every threshold zero. */
  val zero: DRepVotingThresholds = DRepVotingThresholds(
    UnitInterval.zero,
    UnitInterval.zero,
    UnitInterval.zero,
    UnitInterval.zero,
    UnitInterval.zero,
    UnitInterval.zero,
    UnitInterval.zero,
    UnitInterval.zero,
    UnitInterval.zero,
    UnitInterval.zero
  )

  /** The thresholds the node keeps, each refused outside `[0, 1]`. */
  def fromStored(
      t: xyz.kd5ujc.scarab.ledger.DRepVotingThresholds
  ): Either[PParamsError, DRepVotingThresholds] =
    val u = VotingThresholds.unit(DecodeField.DRepVotingThresholds)
    (
      u(t.motionNoConfidence),
      u(t.committeeNormal),
      u(t.committeeNoConfidence),
      u(t.updateToConstitution),
      u(t.hardForkInitiation),
      u(t.ppNetworkGroup),
      u(t.ppEconomicGroup),
      u(t.ppTechnicalGroup),
      u(t.ppGovGroup),
      u(t.treasuryWithdrawal)
    ).mapN(DRepVotingThresholds.apply)

private object VotingThresholds:
  def unit(field: DecodeField)(r: Rational): Either[PParamsError, UnitInterval] =
    UnitInterval.boundRational(r).toRight(PParamsError.RatioOutOfBounds(field, r))
