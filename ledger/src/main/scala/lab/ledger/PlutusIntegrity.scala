// SPDX-License-Identifier: Apache-2.0
package lab.ledger

import lab.Blake2b
import lab.plutus.PlutusExecution.Failure
import scala.util.control.NonFatal
import lab.cbor.{Bytes, Cbor, Node, Value}
import java.nio.charset.StandardCharsets.US_ASCII
import java.security.MessageDigest

/** V3-only integrity commitment. No datum-witness domain, no ledger-admission authority. */
object PlutusIntegrity:
  private val ModelHash = "6ab455d588e186649a6aae2761fec85ae5a2647cb002a2acb737604f698b21a2"
  private def digest(bytes: Bytes): String =
    MessageDigest.getInstance("SHA-256").digest(bytes.toArray).map(b => f"${b & 255}%02x").mkString
  private def node(value: Value): Node = Node(value, Bytes.empty)

  private[ledger] def languageView(model: Bytes): Either[String, Bytes] =
    if model == null || model.size > 16384 then Left("cost model byte limit")
    else if digest(model) != ModelHash then Left("unregistered PV9 cost model")
    else
      val text = new String(model.toArray, US_ASCII).trim
      if !text.startsWith("[") || !text.endsWith("]") then Left("cost array required")
      else
        val parts = text.drop(1).dropRight(1).split(",", -1).toVector.map(_.trim)
        if parts.size != 251 then Left("251 PV9 costs required")
        else
          for
            costs <- parts.foldLeft[Either[String, Vector[Long]]](Right(Vector.empty)) { (acc, x) =>
              for
                prior <- acc
                _ <- Either.cond(x.matches("-?(0|[1-9][0-9]*)"), (), "integer cost required")
                value <- x.toLongOption.toRight("cost outside signed Int64")
              yield prior :+ value
            }
            encoded <- Cbor.encode(
              Value.Map(
                Vector(
                  node(Value.UInt(2)) -> node(
                    Value.Arr(
                      costs.map(v =>
                        node(if v < 0 then Value.NInt(BigInt(v)) else Value.UInt(BigInt(v)))
                      )
                    )
                  )
                )
              )
            )
          yield encoded

  // Never normalize this domain: an indefinite original redeemer map hashes differently.
  private[ledger] def commitment(redeemers: Bytes, view: Bytes): Either[String, Bytes] =
    if redeemers == null || view == null || redeemers.size > 65536 || view.size > 4096 then
      Left("integrity domain byte limit")
    else Right(Blake2b.hash256.hash(Bytes(redeemers.value ++ view.value)))

  /** Check the whole original witness-key-5 value, not its payload or a reserialization. No success
    * is returned for a missing or mismatched supplied commitment.
    */
  def check(
      originalRedeemers: Bytes,
      modelText: Bytes,
      suppliedHash: Bytes
  ): Either[Failure, Bytes] =
    try
      if suppliedHash == null || suppliedHash.size != 32 || originalRedeemers == null then
        Left(Failure.MalformedInput("integrity input/hash required"))
      else
        for
          root <- Cbor
            .decode(originalRedeemers, Cbor.Limits(65536, 32, 4096, 4096))
            .left
            .map(Failure.MalformedInput.apply)
          _ <- Either.cond(
            root.value match
              case Value.Map(Vector((pointer, payload))) =>
                pointer.value match
                  case Value.Arr(Vector(purpose, index))
                      if purpose.value == Value.UInt(0) &&
                        index.value == Value.UInt(0) =>
                    payload.value match
                      case Value.Arr(Vector(redeemer, units)) if redeemer.value == Value.UInt(7) =>
                        units.value match
                          case Value.Arr(Vector(memory, steps)) =>
                            memory.value == Value.UInt(100000) && steps.value == Value.UInt(
                              30000000
                            )
                          case _ => false
                      case _ => false
                  case _ => false
              case _ => false,
            (),
            Failure.Unsupported("fixed spending redeemer map required")
          )
          view <- languageView(modelText).left.map(Failure.Unsupported.apply)
          expected <- commitment(originalRedeemers, view).left.map(Failure.MalformedInput.apply)
          _ <- Either.cond(
            suppliedHash == expected,
            (),
            Failure.MalformedInput("script integrity mismatch")
          )
        yield expected
    catch
      case _: NullPointerException => Left(Failure.MalformedInput("null integrity member"))
      case NonFatal(error)         => Left(Failure.InternalFailure(error.getClass.getSimpleName))
