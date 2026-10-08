// SPDX-License-Identifier: Apache-2.0
package lab.ledger

import lab.cbor.{Bytes, Cbor, Value as CValue}

/** Restricted predicate over original decoded output spans, not a transaction validator. */
object MinimumOutput:
  val ProfileId = "conway-pv9-testnet-ada-minimum-output-v1"
  final class Parameters private[MinimumOutput] (val coinsPerUTxOByte: BigInt)
  object Parameters:
    def checked(
        era: String,
        major: BigInt,
        minor: BigInt,
        cost: BigInt
    ): Either[String, Parameters] =
      if era != "Conway" || major != 9 || minor != 0 then
        Left("unsupported minimum-output era/version")
      else if cost <= 0 || cost > (BigInt(1) << 64) - 1 then
        Left("positive uint64 coinsPerUTxOByte required")
      else Right(new Parameters(cost))

  final case class Output(index: Int, original: Bytes, coin: BigInt, required: BigInt):
    def satisfied: Boolean = coin >= required
  final case class Receipt(outputs: Vector[Output]):
    val profileId = ProfileId
    val fullLedgerValidated = false
    def satisfied: Boolean = outputs.forall(_.satisfied)

  def check(parameters: Parameters, original: Bytes): Either[String, Receipt] =
    checkProfile(parameters, original, false)

  private[ledger] def checkIntervalTransfer(
      parameters: Parameters,
      original: Bytes
  ): Either[String, Receipt] =
    checkProfile(parameters, original, true)

  private def checkProfile(
      parameters: Parameters,
      original: Bytes,
      interval: Boolean
  ): Either[String, Receipt] =
    for
      _ <- Cbor.decode(original, Cbor.Limits(1048576, 16, 65536, 1048576))
      tx <- (if interval then IntervalProjection.coverage(original)
             else Coverage.decode(original)).left.map(_.toString)
      _ <- Either.cond(
        tx.outputs.nonEmpty,
        (),
        "at least one output required by minimum-output profile"
      )
      outputs <- tx.outputs.zipWithIndex.foldLeft[Either[String, Vector[Output]]](
        Right(Vector.empty)
      ) { case (acc, (output, index)) =>
        for
          previous <- acc
          node <- Cbor.decode(output.original)
          amount <- node.value match
            case CValue.Arr(Vector(_, value)) => Right(value)
            case CValue.Map(fields) =>
              fields.find(_._1.value == CValue.UInt(1)).map(_._2).toRight("missing coin")
            case _ => Left("unsupported output representation")
          _ <- Either.cond(
            amount.value.isInstanceOf[CValue.UInt] && output.value.assets.isEmpty &&
              (output.address.value.head & 15) == 0,
            (),
            "minimum-output profile requires scalar ADA and testnet address"
          )
        yield previous :+ Output(
          index,
          output.original,
          output.value.lovelace,
          (BigInt(160) + output.original.size) * parameters.coinsPerUTxOByte
        )
      }
    yield Receipt(outputs)
