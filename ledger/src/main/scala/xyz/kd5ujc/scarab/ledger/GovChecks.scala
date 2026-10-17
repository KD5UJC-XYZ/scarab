// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

/** GOV checks of Conway that need no state. */
object GovChecks:
  private[ledger] def isBootstrapAction(a: GovAction): Boolean = a match
    case _: GovAction.ParameterChange | _: GovAction.HardFork | GovAction.Info => true
    case _                                                                     => false

  /** `ppuWellFormed`: no zero where zero is meaningless, and a non-empty update. */
  private[ledger] def wellFormed(pp: ProtocolParams, u: ParamUpdate): Boolean =
    def value(key: Int): Option[BigInt] =
      u.fields
        .get(key)
        .flatMap(raw => xyz.kd5ujc.scarab.codec.Cbor.decode(raw).toOption)
        .flatMap(n => CborRead.uint(n, "value").toOption)
    val nonZero = Vector(2, 3, 4, 22, 23, 28, 29, 6, 30, 31) ++
      (if pp.protocolMajor == 9 then Vector.empty else Vector(17)) ++
      (if pp.protocolMajor >= 11 then Vector(8) else Vector.empty)
    u.fields.nonEmpty && nonZero.forall(k => value(k).forall(_ != 0))

  private[ledger] def canFollow(prev: (BigInt, BigInt), next: (BigInt, BigInt)): Boolean =
    next == (prev._1 + 1, BigInt(0)) || next == (prev._1, prev._2 + 1)
