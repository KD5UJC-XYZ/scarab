// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

/** GOV checks of Conway that need no state. */
object GovChecks:
  private[ledger] def isBootstrapAction(a: GovAction): Boolean = a match
    case _: GovAction.ParameterChange | _: GovAction.HardFork | GovAction.Info => true
    case _                                                                     => false

  /** `ppuWellFormed` (`Conway/PParams.hs:935-963`, f649f975): no zero where zero is meaningless,
    * the UTxO cost per byte only after the bootstrap phase, `nOpt` from protocol version 11, and a
    * non-empty update.
    */
  private[ledger] def wellFormed(pp: ProtocolParams, u: ConwayParamUpdate): Boolean =
    import ConwayParam.*
    def nonZero(f: ConwayParam): Boolean = f match
      case MaxBBSize(v)              => v.value != 0
      case MaxTxSize(v)              => v.value != 0
      case MaxBHSize(v)              => v.value != 0
      case MaxValSize(v)             => v.value != 0
      case CollateralPercentage(v)   => v.value != 0
      case CommitteeMaxTermLength(v) => v.value != 0
      case GovActionLifetime(v)      => v.value != 0
      case PoolDeposit(v)            => v.value != 0
      case GovActionDeposit(v)       => v.value != 0
      case DRepDeposit(v)            => v.value != 0
      case CoinsPerUTxOByte(v)       => pp.protocolMajor == 9 || v.value != 0
      case NOpt(v)                   => pp.protocolMajor < 11 || v.value != 0
      case _                         => true
    !u.isEmpty && u.params.forall(nonZero)

  private[ledger] def canFollow(prev: (BigInt, BigInt), next: (BigInt, BigInt)): Boolean =
    next == (prev._1 + 1, BigInt(0)) || next == (prev._1, prev._2 + 1)
