// SPDX-License-Identifier: Apache-2.0
package lab.node.ledger

import cats.syntax.all.*
import io.circe.{ACursor, Decoder, Json}
import lab.node.ConfigError

/** The protocol parameters the implemented ledger rules consult. */
final case class ProtocolParams(
    minFeeA: BigInt,
    minFeeB: BigInt,
    maxTxSize: BigInt,
    maxBlockBodySize: BigInt,
    maxValueSize: BigInt,
    coinsPerUTxOByte: BigInt,
    keyDeposit: BigInt,
    poolDeposit: BigInt,
    eMax: BigInt,
    nOpt: BigInt,
    a0: Rational,
    rho: Rational,
    tau: Rational,
    minPoolCost: BigInt,
    protocolMajor: BigInt,
    drepDeposit: BigInt,
    drepActivity: BigInt,
    govActionDeposit: BigInt,
    govActionLifetime: BigInt,
    minFeeRefScriptCostPerByte: Rational
)

object ProtocolParams:
  /** Initial parameters from the Shelley, Alonzo and Conway genesis files. */
  def fromGenesis(shelley: Json, alonzo: Json, conway: Json): Either[ConfigError, ProtocolParams] =
    val s = shelley.hcursor.downField("protocolParams")
    val a = alonzo.hcursor
    val c = conway.hcursor
    for
      minFeeA <- get[BigInt](s, "minFeeA")
      minFeeB <- get[BigInt](s, "minFeeB")
      maxTxSize <- get[BigInt](s, "maxTxSize")
      maxBlockBodySize <- get[BigInt](s, "maxBlockBodySize")
      maxValueSize <- get[BigInt](a, "maxValueSize")
      perWord <- get[BigInt](a, "lovelacePerUTxOWord")
      keyDeposit <- get[BigInt](s, "keyDeposit")
      poolDeposit <- get[BigInt](s, "poolDeposit")
      eMax <- get[BigInt](s, "eMax")
      nOpt <- get[BigInt](s, "nOpt")
      a0 <- get[BigDecimal](s, "a0").map(Rational.fromDecimal)
      rho <- get[BigDecimal](s, "rho").map(Rational.fromDecimal)
      tau <- get[BigDecimal](s, "tau").map(Rational.fromDecimal)
      minPoolCost <- get[BigInt](s, "minPoolCost")
      major <- get[BigInt](s.downField("protocolVersion"), "major")
      drepDeposit <- get[BigInt](c, "dRepDeposit")
      drepActivity <- get[BigInt](c, "dRepActivity")
      govDeposit <- get[BigInt](c, "govActionDeposit")
      govLifetime <- get[BigInt](c, "govActionLifetime")
      refCost <- get[BigDecimal](c, "minFeeRefScriptCostPerByte").map(Rational.fromDecimal)
    yield ProtocolParams(
      minFeeA,
      minFeeB,
      maxTxSize,
      maxBlockBodySize,
      maxValueSize,
      // Babbage translation: coinsPerUTxOByte = lovelacePerUTxOWord / 8 (8-byte words), floored.
      perWord / 8,
      keyDeposit,
      poolDeposit,
      eMax,
      nOpt,
      a0,
      rho,
      tau,
      minPoolCost,
      major,
      drepDeposit,
      drepActivity,
      govDeposit,
      govLifetime,
      refCost
    )

  private def get[A: Decoder](c: ACursor, name: String): Either[ConfigError, A] =
    c.downField(name).as[A].leftMap(e => ConfigError(s"protocol parameter $name: ${e.message}"))
